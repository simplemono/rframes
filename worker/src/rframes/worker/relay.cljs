(ns rframes.worker.relay
  "The Worker's half of a frame stream. The browser holds a POST open and
   reads server-sent events from it, as it does with every rframes
   server. The Worker answers that stream and holds a WebSocket to the
   Object of the page, which the Object accepts as a hibernatable one:
   a frame the Object sends becomes one `frame` event here. So the
   Object sleeps while nothing changes, and the stream stays open.

   The stream is compressed as one, never finished and flushed after
   every frame, so a frame that resembles the one before it costs a few
   bytes. Brotli where the browser takes it: gzip looks back 32 kB, and
   a frame longer than that would cost its full size every time. The
   encoder, the stream and the last frame live in this invocation,
   nothing of them in the Object. Needs the `nodejs_compat` flag, for
   node:zlib."
  (:require ["node:zlib" :as zlib]
            [clojure.string :as str]))

(defn encoding
  "The content encoding of a stream for a client that sent `accept` as its
   Accept-Encoding: \"br\", \"gzip\" or nil for none."
  [accept]
  (let [offered (into #{}
                      (keep (fn [part]
                              (let [[name & params] (map str/trim (str/split part #";"))]
                                (when-not (some #(re-matches #"q=0(\.0*)?" %) params)
                                  name))))
                      (str/split (str/lower-case (or accept "")) #","))]
    (cond
      (contains? offered "br") "br"
      (contains? offered "gzip") "gzip")))

(defn client-accept-encoding
  "What the browser itself sent as Accept-Encoding. Cloudflare replaces
   the header before the Worker sees it (\"gzip, br\" even for a client
   that sent none) and keeps the browser's own under
   `request.cf.clientAcceptEncoding`, so that one counts wherever a `cf`
   object exists."
  [^js request]
  (if-let [cf (.-cf request)]
    (.-clientAcceptEncoding cf)
    (.get (.-headers request) "accept-encoding")))

(defn frame-event
  "One server-sent event of a frame, transit JSON, which has no line
   breaks."
  [frame]
  (str "event: frame\ndata: " frame "\n\n"))

(def beat
  "A comment line, which the client skips. It keeps the stream from
   looking idle to what sits between the Worker and the browser."
  ": beat\n\n")

(def brotli-defaults
  "A window of 1 MB (2^20) and a quality that costs about a millisecond
   per frame."
  {:lgwin 20
   :quality 5})

(defn encoder
  "The encoder of one stream: `{:push! :end!}`. `(push! text)` hands the
   bytes of `text` to `out!`, compressed against everything pushed
   before and flushed, so that they reach the browser now. `(end!)`
   frees the encoder."
  [encoding brotli out!]
  (case encoding
    "br"
    (let [{:keys [lgwin quality]} (merge brotli-defaults brotli)
          params (js-obj)
          _ (aset params (.-BROTLI_PARAM_LGWIN zlib/constants) lgwin)
          _ (aset params (.-BROTLI_PARAM_QUALITY zlib/constants) quality)
          ^js stream (zlib/createBrotliCompress #js {:params params})]
      (.on stream "data" out!)
      {:push! (fn [text]
                (.write stream text "utf8")
                (.flush stream (.-BROTLI_OPERATION_FLUSH zlib/constants)))
       :end! (fn [] (.destroy stream))})

    "gzip"
    (let [^js stream (zlib/createGzip)]
      (.on stream "data" out!)
      {:push! (fn [text]
                (.write stream text "utf8")
                (.flush stream (.-Z_SYNC_FLUSH zlib/constants)))
       :end! (fn [] (.destroy stream))})

    (let [text-encoder (js/TextEncoder.)]
      {:push! (fn [text] (out! (.encode text-encoder text)))
       :end! (fn [])})))

(defn response
  "The Response that carries the frames of `socket`, the WebSocket to the
   Object, to the browser as server-sent events.

     :socket        the WebSocket, not yet accepted
     :encoding      \"br\", \"gzip\" or nil (`encoding`)
     :signal        the request's AbortSignal: the tab went
     :heartbeat-ms  the pause between two comment lines, 25 s when
                    missing, nil or 0 for none
     :brotli        `{:lgwin :quality}` over `brotli-defaults`

   A frame equal to the one before it is not sent. The stream ends when
   the browser goes or the Object closes the socket, whichever comes
   first; the socket and the encoder end with it."
  [{:keys [^js socket encoding ^js signal heartbeat-ms brotli]
    :or {heartbeat-ms 25000}}]
  (let [^js stream (js/TransformStream.)
        ^js writer (.getWriter (.-writable stream))
        state (atom {:closed? false
                     :sent nil
                     :timer nil
                     :chain (js/Promise.resolve nil)})
        sink (atom nil)
        close! (fn []
                 (when-not (:closed? @state)
                   (swap! state assoc :closed? true)
                   (when-let [timer (:timer @state)]
                     (js/clearInterval timer))
                   (try
                     (.close socket 1000 "tab gone")
                     (catch :default _))
                   (try
                     ((:end! @sink))
                     (catch :default _))
                   (try
                     (.catch (.close writer) (fn [_]))
                     (catch :default _))))
        ;; the bytes of the encoder in their order, one write after the other
        out! (fn [chunk]
               (swap! state update :chain
                      (fn [^js chain]
                        (-> chain
                            (.then (fn []
                                     (when-not (:closed? @state)
                                       (.write writer chunk))))
                            (.catch (fn [_] (close!)))))))
        push! (fn [text]
                (when-not (:closed? @state)
                  ((:push! @sink) text)))]
    (reset! sink (encoder encoding brotli out!))
    (.addEventListener socket "message"
                       (fn [^js event]
                         (let [frame (.-data event)]
                           (when (not= frame (:sent @state))
                             (swap! state assoc :sent frame)
                             (push! (frame-event frame))))))
    (.addEventListener socket "close" (fn [_] (close!)))
    (.addEventListener socket "error" (fn [_] (close!)))
    (.accept socket)
    (when (and heartbeat-ms (pos? heartbeat-ms))
      (swap! state assoc :timer (js/setInterval (fn [] (push! beat)) heartbeat-ms)))
    (when signal
      (.addEventListener signal "abort" (fn [_] (close!))))
    (.then (.-closed writer) close! close!)
    (js/Response. (.-readable stream)
                  (clj->js (cond-> {:status 200
                                    :headers (cond-> {"Content-Type" "text/event-stream"
                                                      "Cache-Control" "no-store"}
                                               encoding (assoc "Content-Encoding" encoding))}
                             ;; the body is compressed already
                             encoding (assoc :encodeBody "manual"))))))

(defn ^:async open
  "The WebSocket to the Object behind `stub` for a page, nil when the
   Object did not take it. `headers` carries the envelope."
  [^js stub headers]
  (let [^js answer (await (.fetch stub "https://object/"
                                  (clj->js {:headers (assoc headers "Upgrade" "websocket")})))]
    (.-webSocket answer)))
