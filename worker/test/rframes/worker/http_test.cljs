(ns rframes.worker.http-test
  (:require [clojure.test :refer [async deftest is testing]]
            [rframes.worker.http :as http]))

(deftest match-test
  (testing "an exact path"
    (is (= {} (http/match [:get "/"] {:request-method :get :uri "/"})))
    (is (nil? (http/match [:get "/"] {:request-method :get :uri "/x"})))
    (is (nil? (http/match [:get "/"] {:request-method :post :uri "/"}))))
  (testing "a subtree"
    (is (= {:* "abc/"} (http/match [:post "/l/*"] {:request-method :post :uri "/l/abc/"})))
    (is (= {:* ""} (http/match [:post "/l/*"] {:request-method :post :uri "/l/"})))
    (is (nil? (http/match [:post "/l/*"] {:request-method :post :uri "/m/abc"})))))

(deftest ring-handler-test
  (async done
    (-> ((^:async fn []
           (let [register [{:ring/route [:get "/skip"]
                            :ring/handler (fn [w] w)}
                           {:ring/route [:get "/skip"]
                            :ring/handler (fn [w] (assoc w :ring/response {:status 200 :body "second"}))}
                           {:ring/route [:get "/files/*"]
                            :ring/handler (fn [w]
                                            (js/Promise.resolve
                                             (assoc w :ring/response {:status 200
                                                                      :body (str (:* (:ring/route-params w))
                                                                                 (:answer w))})))}]
                 handler (http/ring-handler (constantly register) {:answer "!"})]
             (testing "an entry that answers nothing leaves the request to the next"
               (is (= "second" (:body (await (handler {:request-method :get :uri "/skip"}))))))
             (testing "a handler may answer a promise, and sees the route params and the first world"
               (is (= "a/b!" (:body (await (handler {:request-method :get :uri "/files/a/b"}))))))
             (testing "no entry"
               (is (= 404 (:status (await (handler {:request-method :get :uri "/nothing"})))))))))
        (.catch (fn [e] (is (nil? e) (str e))))
        (.finally done))))

(deftest request-map-test
  (async done
    (-> ((^:async fn []
           (let [request (http/request-map (js/Request. "https://example.test/l/abc/?x=1&y=2"
                                                        #js {:method "POST"
                                                             :headers #js {"Content-Type" "text/plain"
                                                                           "X-Thing" "7"}
                                                             :body "hello"}))]
             (is (= :post (:request-method request)))
             (is (= "/l/abc/" (:uri request)))
             (is (= "x=1&y=2" (:query-string request)))
             (is (= "7" (get-in request [:headers "x-thing"])))
             (is (= :https (:scheme request)))
             (is (= "hello" (await (:body request)))))
           (let [request (http/request-map (js/Request. "https://example.test/"))]
             (is (= :get (:request-method request)))
             (is (nil? (:query-string request)))
             (is (nil? (:body request))))))
        (.catch (fn [e] (is (nil? e) (str e))))
        (.finally done))))

(deftest response-test
  (async done
    (-> ((^:async fn []
           (let [^js response (http/response {:status 201
                                              :headers {"Content-Type" "text/plain"
                                                        "Set-Cookie" ["a=1" "b=2"]}
                                              :body "made"})]
             (is (= 201 (.-status response)))
             (is (= "text/plain" (.get (.-headers response) "content-type")))
             (is (= ["a=1" "b=2"] (vec (.getSetCookie (.-headers response)))))
             (is (= "made" (await (.text response)))))
           (testing "a Response passes through"
             (let [made (js/Response. "x")]
               (is (identical? made (http/response made)))))))
        (.catch (fn [e] (is (nil? e) (str e))))
        (.finally done))))
