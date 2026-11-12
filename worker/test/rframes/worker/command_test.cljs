(ns rframes.worker.command-test
  (:require [clojure.test :refer [async deftest is testing]]
            [rframes.worker.command :as command]
            [rframes.worker.transit :as transit]))

(def register
  [{:command/kind :test/add
    :command/fn (fn [w]
                  (swap! (:db w) + (:n (command/command-data w)))
                  (command/accepted w))}
   {:command/kind :test/later
    :command/fn (fn [w]
                  (js/Promise.resolve (command/accepted w)))}
   {:command/kind :test/reject
    :command/fn (fn [w] (command/rejected w :no))}
   {:command/kind :test/fail
    :command/fn (fn [_w] (throw (ex-info "boom" {})))}])

(deftest run-test
  (async done
    (-> ((^:async fn []
           (let [db (atom 0)
                 w {:system/get-register (constantly register)
                    :db db}]
             (testing "a command function reads its data and accepts"
               (is (= {:success? true}
                      (await (command/run w {:command/kind :test/add :command/data {:n 3}}))))
               (is (= 3 @db)))
             (testing "a command function may answer a promise"
               (is (= {:success? true} (await (command/run w {:command/kind :test/later})))))
             (testing "a rejection carries its error"
               (is (= {:success? false :result {:error :no}}
                      (await (command/run w {:command/kind :test/reject})))))
             (testing "a function that throws"
               (is (= {:error :command-fn-failed} (await (command/run w {:command/kind :test/fail})))))
             (testing "a kind no entry knows, and no command at all"
               (is (= {:error :unknown-command-kind} (await (command/run w {:command/kind :test/nothing}))))
               (is (= {:error :unknown-command-kind} (await (command/run w nil))))))))
        (.catch (fn [e] (is (nil? e) (str e))))
        (.finally done))))

(deftest read-command-test
  (is (= {:command/kind :a} (command/read-command (transit/write-str {:command/kind :a}))))
  (is (nil? (command/read-command "not transit")))
  (is (nil? (command/read-command (transit/write-str [1 2]))))
  (is (nil? (command/read-command nil))))

(deftest response-test
  (let [{:keys [status headers body]} (command/response {:success? true})]
    (is (= 200 status))
    (is (= "application/transit+json" (get headers "Content-Type")))
    (is (= {:success? true} (transit/read-str body)))))
