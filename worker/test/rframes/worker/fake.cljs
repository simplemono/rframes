(ns rframes.worker.fake
  "Stand-ins for what workerd gives a Worker and an Object, for the tests
   under Node: a WebSocket, a WebSocketPair, the Object's state and the
   stub of an Object.")

(defn socket
  "A WebSocket stand-in: an EventTarget that records what is done to it
   under `.-log` (`:accepted?`, `:closed`, `:sent`, `:attachment`)."
  []
  (let [^js target (js/EventTarget.)
        log (atom {:accepted? false
                   :closed nil
                   :sent []
                   :attachment nil})]
    (set! (.-accept target) (fn [] (swap! log assoc :accepted? true)))
    (set! (.-close target) (fn [code reason] (swap! log assoc :closed [code reason])))
    (set! (.-send target) (fn [text] (swap! log update :sent conj text)))
    (set! (.-serializeAttachment target) (fn [value] (swap! log assoc :attachment value)))
    (set! (.-deserializeAttachment target) (fn [] (:attachment @log)))
    (set! (.-log target) log)
    target))

(defn log
  [^js socket]
  @(.-log socket))

(defn message!
  "A message of the other end arrives on `socket`."
  [^js socket data]
  (.dispatchEvent socket (js/MessageEvent. "message" #js {:data data})))

(defn close!
  "The other end closes `socket`."
  [^js socket]
  (.dispatchEvent socket (js/Event. "close")))

(defn install-pair!
  "`WebSocketPair` as a global, as workerd has it: two sockets, not
   connected to each other."
  []
  (set! (.-WebSocketPair js/globalThis)
        (fn []
          (js-obj "0" (socket) "1" (socket)))))

(defn ctx
  "The state of a Durable Object as far as the page uses it: the sockets
   it accepted. They outlive a page, as they outlive a hibernation."
  []
  (let [sockets (atom [])]
    #js {:acceptWebSocket (fn [socket] (swap! sockets conj socket))
         :getWebSockets (fn [] (to-array @sockets))}))

(defn accepted
  "The sockets `ctx` accepted."
  [^js ctx]
  (vec (.getWebSockets ctx)))

(defn sleep
  [ms]
  (js/Promise. (fn [resolve] (js/setTimeout resolve ms))))
