(ns rframes.client.state
  "The one client store atom, the browser twin of the server's db: the
  latest frame of the server lives under :server/frame, everything else in
  it is client-local state. The runtime (rframes.client) renders from it, the
  components read and dispatch through it. Its own namespace so that both
  can require it without a cycle.")

(defonce store
  (atom {}))

;; The id of this browser tab, sent to the server when the stream opens.
;; Per document, deliberately not sessionStorage: a same-origin iframe
;; shares sessionStorage with its parent, and two documents must not share
;; a tab.
(defonce tabid
  (.substring (str (random-uuid)) 0 8))

(defn dispatch!
  "Runs action vectors through the event loop, for code that lives outside
  a DOM event: a window listener, the callback of a web component."
  [actions]
  (when-let [dispatch (:replicant/dispatch! @store)]
    (dispatch {:actions actions})))
