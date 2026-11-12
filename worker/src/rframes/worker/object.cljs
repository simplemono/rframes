(ns rframes.worker.object
  "The Object's half: a Durable Object that holds the state of its pages
   and renders them. The Worker's relay opens one WebSocket per browser
   tab (`rframes.worker.relay`); the Object accepts it with the
   WebSocket Hibernation API, renders the view per refresh and sends
   the frame down the socket. Commands arrive as requests and run here,
   next to the state.

   Nothing lives at namespace level. Objects of one class share an
   isolate, and with it every `def`: a registry of connections kept
   here would be every organization's at once. What an Object holds,
   it holds in the page `create` answers, on the instance. The same
   goes for an app: its atom belongs to the instance, not to a
   namespace.

   An Object that nobody talks to hibernates after a few seconds: its
   memory goes, its sockets stay. So what a connection is lives in the
   socket's attachment (the page's path, the tab's id, the connection
   data of the Worker), plain data of at most 16 kB, and the state of
   the app lives in the Object's storage. The constructor runs again
   at the next event.

   The world of a view and of a command function:

     :system/get-register  the register
     :object/page          the page, for `schedule-refresh!`
     :object/ctx           the Object's state (storage under `.-storage`)
     :object/env           the Worker's bindings
     :ring/request         the page's `:uri` and `:query-string`
     :ring/route-params    of the page's render entry (views)
     :conn-id :tabid       of the connection (views)

   under them what the app's `:world` answers, and what the Worker's
   `:connection` told about who asks."
  (:require [rframes.worker.command :as command]
            [rframes.worker.envelope :as envelope]
            [rframes.worker.http :as http]
            [rframes.worker.transit :as transit]))

(defn create
  "The page of one Durable Object, made in its constructor.

     :ctx           the Object's state, the constructor's first argument
     :env           the bindings, its second
     :get-register  a function that answers the register: the
                    `:replicant/render` and `:command/kind` entries
     :world         a function that answers what every view and command
                    of this Object finds in its world, the app's state
                    above all; optional"
  [{:keys [ctx env get-register world]}]
  {:object/ctx ctx
   :object/env env
   :system/get-register get-register
   :object/world (or world (constantly nil))
   ;; :pending   what the next refresh goes to: nil for none, :all, or
   ;;            the predicates gathered since the last one
   ;; :last      when the last scheduled refresh went, ms
   ;; :sent      conn-id -> the frame last sent, gone with a hibernation
   :object/state (atom {:pending nil
                        :last 0
                        :sent {}})})

(defn- world
  "What every view and command of the page starts from."
  [page]
  (merge ((:object/world page))
         {:system/get-register (:system/get-register page)
          :object/page page
          :object/ctx (:object/ctx page)
          :object/env (:object/env page)}))

(defn- render-entry
  "`[entry route-params]` of the render entry that serves `uri`."
  [page uri]
  (some (fn [{path :replicant/render :as entry}]
          (when path
            (when-let [route-params (http/match [:post path] {:request-method :post
                                                              :uri uri})]
              [entry route-params])))
        ((:system/get-register page))))

(defn- connection-world
  "The world of the connection whose attachment is `attachment`."
  [page {:keys [conn-id tabid uri query-string connection]} route-params]
  (merge (world page)
         connection
         {:conn-id conn-id
          :tabid tabid
          :ring/request {:request-method :post
                         :uri uri
                         :query-string query-string}
          :ring/route-params route-params}))

(defn- attachment
  "What the connection of `socket` is, nil for a socket that carries
   nothing readable."
  [^js socket]
  (try
    (some-> (.deserializeAttachment socket) (transit/read-str))
    (catch :default _
      nil)))

(defn- sockets
  [page]
  (array-seq (.getWebSockets ^js (:object/ctx page))))

(defn connections
  "How many tabs hold a frame stream to this Object right now."
  [page]
  (count (sockets page)))

(defn- frame
  "The frame of the view of `w`, transit JSON; nil with the error logged
   when the view throws or holds something transit cannot write: losing
   one frame beats killing the stream, and the next refresh usually
   heals it."
  [render-fn w]
  (try
    (transit/write-str (render-fn w))
    (catch :default e
      (js/console.error "[render]" (str e))
      nil)))

(defn- render!
  "The connection of `socket` rendered and its frame sent, unless it
   equals the one the connection got last. `pred`, when given, picks by
   the connection's world whether to render at all."
  ([page socket]
   (render! page socket nil))
  ([page ^js socket pred]
   ;; one connection that fails must not cost the others their frame
   (try
     (when-let [{:keys [conn-id uri] :as attached} (attachment socket)]
       (when-let [[entry route-params] (render-entry page uri)]
         (let [w (connection-world page attached route-params)]
           (when (or (nil? pred) (pred w))
             (when-let [text (frame (:render/fn entry) w)]
               (when (not= text (get-in @(:object/state page) [:sent conn-id]))
                 (swap! (:object/state page) assoc-in [:sent conn-id] text)
                 (.send socket text)))))))
     (catch :default e
       (js/console.error "[connection]" (str e))))))

(defn refresh-all!
  [page]
  (doseq [socket (sockets page)]
    (render! page socket)))

(defn refresh-where!
  "The connections whose world `pred` picks rendered."
  [page pred]
  (doseq [socket (sockets page)]
    (render! page socket pred)))

(def ^:private refresh-gap
  "The least milliseconds between two refreshes."
  100)

(defn- refresh-pending!
  [page]
  (let [state (:object/state page)
        pending (:pending @state)]
    (swap! state assoc :pending nil :last (js/Date.now))
    (if (= :all pending)
      (refresh-all! page)
      (refresh-where! page (fn [w] (some #(% w) pending))))))

(defn schedule-refresh!
  "A refresh of every connection of the page (`:all`, the default), or of
   the ones `which`, a predicate of a connection's world, picks;
   coalesced to at most 10 per second, the predicates gathered meanwhile
   all applied. The first change after a quiet moment goes at once (the
   end of the tick, so the changes of one command are one frame); a
   stream of changes is paced by the gap since the last refresh. The
   timer keeps the Object awake for that gap at most."
  ([page]
   (schedule-refresh! page :all))
  ([page which]
   (let [state (:object/state page)
         before (:pending @state)]
     (swap! state update :pending
            (fn [pending]
              (cond
                (= :all pending) :all
                (= :all which) :all
                :else (conj (or pending []) which))))
     (when (nil? before)
       (js/setTimeout #(refresh-pending! page)
                      ;; never longer than the gap: the clock may step back
                      (min refresh-gap (max 0 (- refresh-gap (- (js/Date.now) (:last @state))))))))))

(defn watch!
  "Refreshes the connections of the page after a change of the atom `db`:
   every connection, or, with `affected`, what `(affected old new)`
   answers, nil for none, `:all`, or a predicate of a connection's world.
   The atom is the instance's: made in the constructor, read from the
   Object's storage."
  ([page db]
   (watch! page db (fn [_ _] :all)))
  ([page db affected]
   (add-watch db ::refresh
              (fn [_key _ref old-value new-value]
                (when (not= old-value new-value)
                  (when-let [which (affected old-value new-value)]
                    (schedule-refresh! page which)))))))

(defn unwatch!
  "The watch of `watch!` taken off the atom `db` again."
  [db]
  (remove-watch db ::refresh))

(defn accept!
  "A new connection for `envelope` (the page's `:uri` and `:query-string`,
   the `:tabid`, the `:connection` data): the Object's end of a new
   WebSocket pair accepted as a hibernatable socket, what the connection
   is attached to it, the entry's `:open/fn` called with the
   connection's world, and the first frame sent. Answers the other end,
   for the Worker; nil when no render entry serves the path, or when
   what the connection is does not fit a socket's attachment."
  [page {:keys [uri query-string tabid connection]}]
  (when-let [[entry route-params] (render-entry page uri)]
    (let [^js pair (js/WebSocketPair.)
          ^js client (aget pair 0)
          ^js server (aget pair 1)
          attached {:conn-id (str (random-uuid))
                    :uri uri
                    :query-string query-string
                    :tabid tabid
                    :connection connection}]
      (.acceptWebSocket ^js (:object/ctx page) server)
      (if-not (try
                (.serializeAttachment server (transit/write-str attached))
                true
                (catch :default e
                  ;; more than the 16 kB a socket carries
                  (js/console.error "[attachment]" (str e))
                  false))
        (do (try
              (.close server 1011 "attachment")
              (catch :default _))
            nil)
        (do (when-let [open-fn (:open/fn entry)]
              (try
                (open-fn (connection-world page attached route-params))
                (catch :default e
                  (js/console.error "[open]" (str e)))))
            (render! page server)
            client)))))

(defn closed!
  "A socket of the page closed, the Object's `webSocketClose` and
   `webSocketError`: its frame is forgotten and the close answered."
  [page ^js socket]
  (swap! (:object/state page) update :sent dissoc (:conn-id (attachment socket)))
  (try
    (.close socket 1000 "closed")
    (catch :default _)))

(defn ^:async execute
  "The answer to the command of the transit `text`, run in the world of
   the page with what the envelope says about who asks. Whatever the
   command did, the open pages are rendered again afterwards; a page it
   left as it was gets no frame."
  [page envelope text]
  (let [command (command/read-command text)
        w (merge (world page)
                 (:connection envelope)
                 {:ring/request {:request-method :post
                                 :uri (:uri envelope)
                                 :query-string (:query-string envelope)}})
        answer (await (command/run w command))]
    (schedule-refresh! page)
    answer))

(defn ^:async fetch
  "The Object's `fetch`. The Worker asks for two things: a WebSocket for a
   page (an Upgrade request) and the answer to a command (a POST), each
   with an envelope. Anything else is not found."
  [page ^js request]
  (let [envelope (envelope/read request)]
    (cond
      (nil? envelope)
      (http/response http/not-found)

      (= "websocket" (some-> (.get (.-headers request) "upgrade") (.toLowerCase)))
      (if-let [client (accept! page envelope)]
        (js/Response. nil #js {:status 101
                               :webSocket client})
        (http/response http/not-found))

      (= "POST" (.-method request))
      (http/response (command/response (await (execute page envelope (await (.text request))))))

      :else
      (http/response http/not-found))))
