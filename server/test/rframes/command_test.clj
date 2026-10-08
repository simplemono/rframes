(ns rframes.command-test
  (:require [clojure.test :refer [deftest is testing]]
            [rframes.command :as command]
            [rframes.http :as http]
            [rframes.transit :as transit]))

(def db
  (atom 0))

(defn- add
  [w]
  (swap! db + (:n (command/command-data w)))
  (command/accepted w))

(defn- reject
  [w]
  (command/rejected w :no))

(defn- fail
  [_w]
  (throw (ex-info "boom" {})))

(def register
  (concat command/register
          [{:command/kind :test/add
            :command/fn add}
           {:command/kind :test/reject
            :command/fn reject}
           {:command/kind :test/fail
            :command/fn fail}]))

(defn- post
  [command]
  (let [handler (http/ring-handler (constantly register))
        {:keys [status headers body]} (handler {:request-method :post
                                                :uri "/command"
                                                :body (java.io.ByteArrayInputStream.
                                                       (.getBytes (transit/write-str command) "UTF-8"))})]
    {:status status
     :type (get headers "Content-Type")
     :answer (transit/read-str body)}))

(deftest command-test
  (reset! db 0)
  (testing "an accepted command"
    (is (= {:status 200
            :type "application/transit+json"
            :answer {:success? true}}
           (post {:command/kind :test/add
                  :command/data {:n 2}})))
    (is (= 2 @db)))
  (testing "a rejected command carries its error"
    (is (= {:success? false
            :result {:error :no}}
           (:answer (post {:command/kind :test/reject})))))
  (testing "an unknown kind and a body that is no command"
    (is (= {:error :unknown-command-kind} (:answer (post {:command/kind :test/unknown}))))
    (is (= {:error :unknown-command-kind} (:answer (post "not a command")))))
  (testing "a command function that throws"
    (is (= {:error :command-fn-failed}
           (:answer (binding [*err* (java.io.StringWriter.)]
                      (post {:command/kind :test/fail})))))))
