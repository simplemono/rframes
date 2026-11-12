(ns rframes.worker.http
  "Routing over a register, and the adapter between the Request and
   Response of a Worker and the ring-shaped maps of rframes. The port of
   rframes.http: the world, the register entries and the request and
   response maps are the original's. Files are not served here, a Worker
   has its static assets for that (`assets` in wrangler.jsonc).

   A register is a sequence of entries. An entry with a route looks like

       {:ring/route [:get \"/about\"] :ring/handler #'handler}

   and its handler is a function `w -> w` of the world, or `w -> promise
   of w`: it reads `:ring/request` and `:ring/route-params` and adds
   `:ring/response`, a ring map or a Response."
  (:require [clojure.string :as str]))

(def not-found
  {:status 404
   :headers {"Content-Type" "text/plain"}
   :body "not found"})

(defn match
  "The route params if `route` matches the request, otherwise nil. A path
   that ends with /* matches its subtree, the rest of the path is under :*."
  [[method path] {:keys [request-method uri]}]
  (when (= method request-method)
    (if (str/ends-with? path "/*")
      (let [prefix (subs path 0 (dec (count path)))]
        (when (str/starts-with? uri prefix)
          {:* (subs uri (count prefix))}))
      (when (= path uri)
        {}))))

(defn- ^:async respond-with
  "The response of the first of the matching entries, `[entry route-params]`
   pairs, whose handler answers one; not-found when none does."
  [w matches]
  (if-let [[{handler :ring/handler} route-params] (first matches)]
    (if-let [response (:ring/response (await (handler (assoc w :ring/route-params route-params))))]
      response
      (await (respond-with w (rest matches))))
    not-found))

(defn ring-handler
  "The ring handler over `get-register`, a function from a ring request to
   a promise of the response. The register is consulted per request, so a
   redefined entry is live at once. The first matching entry that answers
   wins. `w0` is what every request's world starts from."
  ([get-register]
   (ring-handler get-register {}))
  ([get-register w0]
   (fn [request]
     (let [w (assoc w0
                    :ring/request request
                    :system/get-register get-register)]
       (respond-with w (keep (fn [{:keys [ring/route] :as entry}]
                               (when-let [route-params (and route (match route request))]
                                 [entry route-params]))
                             (get-register)))))))

(defn request-map
  "The ring request of a Worker's Request: the method, the path and the
   query string, the headers in lower case and, for a request that
   carries one, the body as a promise of its text."
  [^js request]
  (let [url (js/URL. (.-url request))
        method (keyword (str/lower-case (.-method request)))
        search (.-search url)]
    {:request-method method
     :uri (.-pathname url)
     :query-string (when (seq search)
                     (subs search 1))
     :headers (into {}
                    (map (fn [pair] [(aget pair 0) (aget pair 1)]))
                    (es6-iterator-seq (.entries (.-headers request))))
     :scheme (keyword (str/replace (.-protocol url) ":" ""))
     :body (when (#{:post :put :patch} method)
             (.text request))}))

(defn response
  "The Response of a ring response. A header's value may be a vector,
   several headers of that name. A Response passes through as it is: the
   frame stream and the answer of the Object are made as one."
  [ring-response]
  (if (instance? js/Response ring-response)
    ring-response
    (let [{:keys [status headers body]} ring-response
          h (js/Headers.)]
      (doseq [[name value] headers
              one (if (vector? value) value [value])]
        (.append h name (str one)))
      (js/Response. (or body "") #js {:status (or status 200)
                                      :headers h}))))
