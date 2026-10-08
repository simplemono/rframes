(ns rframes.replicant
  "Replicant glue: a static shim that loads the client bundle, and the view
  hiccup pushed as transit frames over the connections of `rframes.sse`. The
  client decodes a frame and lets Replicant diff it into the DOM, there is
  no HTML on this wire.

  Register entries this namespace expands:

      {:replicant/shim path :head html}            GET, static shell
      {:replicant/render path :render/fn #'view}   POST, SSE stream

  The view function gets the world of its connection: `:ring/request`,
  `:conn-id` and `:tabid`, the id the client sends when it opens the
  stream, with which a view can keep state per browser tab."
  (:require [rframes.sse :as sse]
            [rframes.transit :as transit]))

(defn frame-event
  "One SSE frame: the view hiccup as transit JSON, which has no line breaks.
  Returns nil with the error logged when the view holds something transit
  cannot write: losing one frame beats killing the stream, and the next
  refresh usually heals it."
  [view]
  (try
    (str "event: frame\ndata: " (transit/write-str view) "\n\n")
    (catch Exception e
      (binding [*out* *err*]
        (println "[frame]" (str e)))
      nil)))

(defn shim-html
  "The static shell. The client runtime mounts at #root, the attribute is its
  cue to open the view stream on this path. `head` is an HTML string with the
  stylesheets and script tags of the page."
  [head]
  (str "<!DOCTYPE html>"
       "<html lang=\"en\"><head><meta charset=\"UTF-8\">"
       "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">"
       head
       "</head><body><noscript>This UI requires JavaScript.</noscript>"
       "<div id=\"root\" data-rframes-view=\"\" style=\"display:contents\"></div>"
       "</body></html>"))

(defn tabid
  "The tab id from the body of the stream-open POST, transit {:tabid ...},
  or nil when the body is missing or not readable."
  [request]
  (when-let [body (:body request)]
    (try
      (let [params (transit/read-stream body)]
        (when (map? params)
          (:tabid params)))
      (catch Exception _
        nil))))

(defn expand-entry
  "Gives a replicant entry the :ring/route and :ring/handler the router
  serves, anything else passes through."
  [entry]
  (cond
    (:replicant/shim entry)
    (assoc entry
           :ring/route [:get (:replicant/shim entry)]
           :ring/handler (fn [w]
                           (assoc w
                                  :ring/response
                                  {:status 200
                                   :headers {"Content-Type" "text/html; charset=utf-8"
                                             "Cache-Control" "no-cache"}
                                   :body (shim-html (:head entry))})))

    (:replicant/render entry)
    (assoc entry
           :ring/route [:post (:replicant/render entry)]
           :ring/handler (let [stream (sse/stream-handler entry frame-event)]
                           (fn [w]
                             (stream (assoc w :tabid (tabid (:ring/request w)))))))

    :else
    entry))

(defn expand
  [entries]
  (map expand-entry entries))

(comment
  (frame-event [:div {:on {:click [[:store/assoc :open? true]]}}
                (for [n (range 3)]
                  [:span n])])
  )
