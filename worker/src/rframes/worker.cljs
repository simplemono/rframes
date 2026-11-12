(ns rframes.worker
  "rframes on a Cloudflare Worker with a Durable Object. The loop is the
   one of every rframes server: a view is a pure function of the state
   to hiccup, the server pushes the whole view as a transit frame over a
   stream it holds open, commands are maps the browser posts. Here the
   server is two halves.

   The Worker, this namespace, answers the browser. It serves the static
   shell of a page, holds the frame stream of every tab and hands the
   commands on. It keeps no state: it finds the Durable Object a request
   belongs to and relays.

   The Object (`rframes.worker.object`) holds the state, runs the
   commands and renders the views. Between two changes it sleeps.

   Both halves read the same register, with the entries of rframes:

       {:replicant/shim \"/\" :head html}            GET, the static shell
       {:replicant/render \"/\" :render/fn #'view}   POST, the frame stream
       {:command/kind :note/add :command/fn #'add}  a command
       {:ring/route [:get \"/x\"] :ring/handler #'f} a route of the Worker

   A POST to a path that ends in /command is a command; the browser
   posts to `command` next to its page."
  (:require [clojure.string :as str]
            [rframes.worker.envelope :as envelope]
            [rframes.worker.http :as http]
            [rframes.worker.relay :as relay]
            [rframes.worker.transit :as transit]))

(defn shim-html
  "The static shell. The client runtime mounts at #root, the attribute is its
   cue to open the view stream on this path. `head` is an HTML string with
   the stylesheets and script tags of the page."
  [head]
  (str "<!DOCTYPE html>"
       "<html lang=\"en\"><head><meta charset=\"UTF-8\">"
       "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">"
       head
       "</head><body><noscript>This UI requires JavaScript.</noscript>"
       "<div id=\"root\" data-rframes-view=\"\" style=\"display:contents\"></div>"
       "</body></html>"))

(defn- command-path?
  [uri]
  (str/ends-with? uri "/command"))

(defn- tabid
  "The tab id from the body of the stream-open POST, transit {:tabid ...},
   or nil when the body is missing or not readable."
  [text]
  (try
    (let [params (transit/read-str text)]
      (when (map? params)
        (:tabid params)))
    (catch :default _
      nil)))

(defn- ^:async envelope-headers
  "The header that tells the Object about the request of `w`: where it
   went, the tab, and what the app's `:connection` says about who asks."
  [{:keys [connection]} w tabid]
  (let [{:keys [uri query-string]} (:ring/request w)]
    {envelope/header (envelope/write {:uri uri
                                      :query-string query-string
                                      :tabid tabid
                                      :connection (when connection
                                                    (await (connection w)))})}))

(defn- ^:async stream
  "The frame stream of a page: a WebSocket to the page's Object, relayed
   to the browser as server-sent events. Answers nothing for a command's
   path, which a render entry with a wildcard matches too, and for a
   request no Object serves."
  [{:keys [object] :as opts} w]
  (let [^js request (:worker/request w)
        stub (when-not (command-path? (:uri (:ring/request w)))
               (object w))]
    (if-not stub
      w
      (let [headers (await (envelope-headers opts w (tabid (await (:body (:ring/request w))))))
            socket (await (relay/open stub headers))]
        (assoc w
               :ring/response
               (if socket
                 (relay/response {:socket socket
                                  :encoding (relay/encoding (relay/client-accept-encoding request))
                                  :signal (.-signal request)
                                  :heartbeat-ms (get opts :heartbeat-ms 25000)
                                  :brotli (:brotli opts)})
                 http/not-found))))))

(defn- ^:async forward-command
  "A command handed to the Object of its page, the Object's answer handed
   back as it is."
  [{:keys [object] :as opts} w]
  (let [stub (when (command-path? (:uri (:ring/request w)))
               (object w))]
    (if-not stub
      w
      (let [text (await (:body (:ring/request w)))
            headers (await (envelope-headers opts w nil))]
        (assoc w
               :ring/response
               (await (.fetch ^js stub "https://object/command"
                              (clj->js {:method "POST"
                                        :headers (assoc headers "Content-Type" "application/transit+json")
                                        :body text}))))))))

(defn- expand-entry
  "Gives a replicant entry the :ring/route and :ring/handler the router
   serves, anything else passes through."
  [opts entry]
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
           :ring/handler (fn [w] (stream opts w)))

    :else
    entry))

(defn routes
  "The register the Worker serves: the command endpoint, then the app's
   entries with the shim and render entries expanded."
  [{:keys [get-register] :as opts}]
  (cons {:ring/route [:post "/*"]
         :ring/handler (fn [w] (forward-command opts w))}
        (map #(expand-entry opts %) (get-register))))

(defn fetch-handler
  "The `fetch` of the Worker's default export, `(fn [request env ctx])`.

     :get-register  a function that answers the app's register
     :object        a function of the world that answers the stub of the
                    Durable Object a request belongs to, nil for none.
                    The world has `:ring/request`, `:worker/env` (the
                    bindings) and `:worker/request`. This is where a
                    session decides: never let the browser name its
                    Object without a check.
     :connection    a function of the world that answers plain data
                    about who asks (a user's id, a role), or a promise
                    of it. It reaches the world of the Object's views
                    and commands and has to fit, with the page's path,
                    into 16 kB. Optional.
     :heartbeat-ms  the pause between two comment lines on a quiet
                    stream, 25 s when missing, nil for none
     :brotli        `{:lgwin :quality}` of the stream's brotli encoder"
  [opts]
  (fn [^js request env ctx]
    (let [handler (http/ring-handler (fn [] (routes opts))
                                     {:worker/request request
                                      :worker/env env
                                      :worker/ctx ctx})]
      (-> (js/Promise.resolve nil)
          ;; a handler that throws before it answers a promise rejects here too
          (.then (fn [] (handler (http/request-map request))))
          (.then http/response)
          (.catch (fn [error]
                    (js/console.error "[http]" (.-url request) (str error))
                    (http/response {:status 500
                                    :headers {"Content-Type" "text/plain"}
                                    :body "internal error"})))))))
