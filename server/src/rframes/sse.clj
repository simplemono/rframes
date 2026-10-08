(ns rframes.sse
  "The SSE view connections: the connection registry, the coalesced refresh
  and the stream handler that holds a POST open and pushes one encoded frame
  per refresh. Frame encoding belongs to the caller, this namespace only
  knows channels and timing."
  (:require [clojure.string :as str]
            [org.httpkit.server :as server]))

(defonce ^:private conns
  (atom {}))

(defonce ^:private next-conn-id
  (atom 0))

(defn refresh-all!
  []
  (doseq [{:keys [render]} (vals @conns)]
    (render)))

(defonce ^:private refresh-pending?
  (atom false))

(defn schedule-refresh!
  "A refresh of every connection, coalesced to at most 10 per second."
  []
  (when (compare-and-set! refresh-pending? false true)
    (future
      (Thread/sleep 100)
      (reset! refresh-pending? false)
      (refresh-all!))))

(defn watch!
  "Refreshes every connection after a change of the atom `db`. With this
  watch in place, mutating the atom is all a command has to do to update
  every open page."
  [db]
  (add-watch db ::refresh
             (fn [_key _ref old-value new-value]
               (when (not= old-value new-value)
                 (schedule-refresh!)))))

(def gzip?
  "Gzip the stream (default). Every frame is compressed against the frames
  before it, so a frame that barely differs from the last one costs little.
  RFRAMES_SSE_GZIP=0 turns it off, for proxies that buffer encoded streams."
  (not= "0" (System/getenv "RFRAMES_SSE_GZIP")))

(defn- accepts-gzip?
  [request]
  (str/includes? (get-in request [:headers "accept-encoding"] "") "gzip"))

(defn gzip-encoder
  "A function from a frame string to its compressed bytes. The gzip stream is
  never finished, every frame ends with a sync flush."
  []
  (let [buffer (java.io.ByteArrayOutputStream.)
        gzip (java.util.zip.GZIPOutputStream. buffer true)]
    (fn [frame]
      (.write gzip (.getBytes frame "UTF-8"))
      (.flush gzip)
      (let [compressed (.toByteArray buffer)]
        (.reset buffer)
        compressed))))

(defn- view
  [render-fn w]
  (try
    (render-fn w)
    (catch Exception e
      (binding [*out* *err*]
        (println "[render]" (str e)))
      nil)))

(defn stream-handler
  "A handler `w -> w` that holds an SSE view connection. `:render/fn` is
  `w -> view`, called per refresh with the world of the connection, which is
  captured at connect. `encode` turns a view into a frame string, nil skips
  the frame."
  [{render-fn :render/fn} encode]
  (fn [w]
    (let [request (:ring/request w)
          conn-id (swap! next-conn-id inc)
          w (assoc w :conn-id conn-id)
          compress (when (and gzip? (accepts-gzip? request))
                     (gzip-encoder))
          headers (cond-> {"Content-Type" "text/event-stream"
                           "Cache-Control" "no-store"}
                    compress (assoc "Content-Encoding" "gzip"))
          first-frame? (atom true)
          lock (Object.)
          send-frame! (fn [channel frame]
                        (let [body (if compress
                                     (java.io.ByteArrayInputStream. (compress frame))
                                     frame)]
                          (if (compare-and-set! first-frame? true false)
                            (server/send! channel
                                          {:status 200
                                           :headers headers
                                           :body body}
                                          false)
                            (server/send! channel body false))))]
      (assoc w
             :ring/response
             (server/as-channel
              request
              {:on-open (fn [channel]
                          (let [render (fn []
                                         ;; The refresh thread and the open
                                         ;; callback may render at the same
                                         ;; time, the gzip stream needs the
                                         ;; frames in order.
                                         (locking lock
                                           (when-let [frame (some-> (view render-fn w)
                                                                    (encode))]
                                             (send-frame! channel frame))))]
                            (swap! conns assoc conn-id {:render render})
                            (render)))
               :on-close (fn [_channel _status]
                           (swap! conns dissoc conn-id))})))))
