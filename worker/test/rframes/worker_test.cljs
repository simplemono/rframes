(ns rframes.worker-test
  (:require [clojure.string :as str]
            [clojure.test :refer [async deftest is testing]]
            [rframes.worker :as worker]
            [rframes.worker.envelope :as envelope]
            [rframes.worker.fake :as fake :refer [sleep]]
            [rframes.worker.transit :as transit]))

(def register
  [{:replicant/shim "/l/*"
    :head "<title>t</title>"}
   {:replicant/render "/l/*"
    :render/fn (fn [_] [:p])}
   {:ring/route [:get "/health"]
    :ring/handler (fn [w]
                    (assoc w :ring/response {:status 200 :body (str "ok " (.-NAME ^js (:worker/env w)))}))}])

(defn- stub
  "The stub of an Object that records what the Worker asks of it: a socket
   for an Upgrade, the answer of a command otherwise."
  [calls socket]
  #js {:fetch (fn [url ^js init]
                (let [headers (js->clj (.-headers init))]
                  (swap! calls conj {:url url
                                     :method (.-method init)
                                     :envelope (envelope/read (js/Request. "https://object/" #js {:headers (.-headers init)}))
                                     :upgrade (get headers "Upgrade")
                                     :body (.-body init)})
                  (js/Promise.resolve
                   (if (get headers "Upgrade")
                     #js {:webSocket socket}
                     (js/Response. (transit/write-str {:success? true})
                                   #js {:headers #js {"Content-Type" "application/transit+json"}})))))})

(defn- handler-of
  [calls socket]
  (worker/fetch-handler {:get-register (constantly register)
                         :object (fn [w]
                                   ;; the Object by the list in the path, as an app would
                                   (when (re-find #"^/l/[a-z]+/" (:uri (:ring/request w)))
                                     (stub calls socket)))
                         :connection (fn [w]
                                       {:user/name (get-in w [:ring/request :headers "x-user"])})
                         :heartbeat-ms nil}))

(defn- request
  [path init]
  (js/Request. (str "https://example.test" path) (clj->js init)))

(deftest pages-test
  (async done
    (-> ((^:async fn []
           (let [handler (handler-of (atom []) (fake/socket))
                 env #js {:NAME "env"}]
             (let [^js response (await (handler (request "/l/abc/" {}) env nil))
                   html (await (.text response))]
               (testing "the shell of a page"
                 (is (= 200 (.-status response)))
                 (is (str/starts-with? (.get (.-headers response) "content-type") "text/html"))
                 (is (str/includes? html "<title>t</title>"))
                 (is (str/includes? html "data-rframes-view"))))
             (testing "a route of the app sees the bindings"
               (is (= "ok env" (await (.text (await (handler (request "/health" {}) env nil)))))))
             (testing "nothing there"
               (is (= 404 (.-status (await (handler (request "/nothing" {}) env nil)))))))))
        (.catch (fn [e] (is (nil? e) (str e))))
        (.finally done))))

(deftest stream-test
  (async done
    (-> ((^:async fn []
           (let [calls (atom [])
                 socket (fake/socket)
                 handler (handler-of calls socket)
                 ^js response (await (handler (request "/l/abc/?x=1"
                                                       {:method "POST"
                                                        :headers {"X-User" "Anna"
                                                                  ;; a browser cannot write the envelope
                                                                  "X-Rframes" (envelope/write {:connection {:user/name "Mallory"}})}
                                                        :body (transit/write-str {:tabid "tab-1"})})
                                              #js {} nil))
                 ^js reader (.getReader (.-body response))]
             (testing "the stream of a page opens a socket to its Object, told where and who"
               (is (= 200 (.-status response)))
               (is (= "text/event-stream" (.get (.-headers response) "content-type")))
               (is (= [{:url "https://object/"
                        :method nil
                        :upgrade "websocket"
                        :body nil
                        :envelope {:uri "/l/abc/"
                                   :query-string "x=1"
                                   :tabid "tab-1"
                                   :connection {:user/name "Anna"}}}]
                      @calls)))
             (fake/message! socket "[\"a\"]")
             (testing "and relays the Object's frames"
               (is (= "event: frame\ndata: [\"a\"]\n\n"
                      (.decode (js/TextDecoder.) (.-value (await (.read reader)))))))
             (fake/close! socket)
             (await (sleep 20))
             (testing "a path no Object serves is not found"
               (is (= 404 (.-status (await (handler (request "/l/123/" {:method "POST" :body "x"}) #js {} nil)))))))))
        (.catch (fn [e] (is (nil? e) (str e))))
        (.finally done))))

(deftest command-test
  (async done
    (-> ((^:async fn []
           (let [calls (atom [])
                 handler (handler-of calls (fake/socket))
                 command (transit/write-str {:command/kind :note/add :command/data {:text "x"}})
                 ^js response (await (handler (request "/l/abc/command"
                                                       {:method "POST"
                                                        :headers {"X-User" "Ben"}
                                                        :body command})
                                              #js {} nil))]
             (testing "a POST to command next to a page goes to the page's Object, not into a stream"
               (is (= {:success? true} (transit/read-str (await (.text response)))))
               (is (= [{:url "https://object/command"
                        :method "POST"
                        :upgrade nil
                        :body command
                        :envelope {:uri "/l/abc/command"
                                   :query-string nil
                                   :tabid nil
                                   :connection {:user/name "Ben"}}}]
                      @calls)))
             (testing "a command no Object serves is not found"
               (is (= 404 (.-status (await (handler (request "/command" {:method "POST" :body command}) #js {} nil)))))))))
        (.catch (fn [e] (is (nil? e) (str e))))
        (.finally done))))

(deftest failing-handler-test
  (async done
    (-> ((^:async fn []
           (let [handler (worker/fetch-handler {:get-register (constantly [{:ring/route [:get "/"]
                                                                            :ring/handler (fn [_] (throw (ex-info "boom" {})))}])
                                                :object (constantly nil)})]
             (is (= 500 (.-status (await (handler (request "/" {}) #js {} nil))))))))
        (.catch (fn [e] (is (nil? e) (str e))))
        (.finally done))))
