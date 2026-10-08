(ns rframes.sse-test
  (:require [clojure.test :refer [deftest is testing]]
            [rframes.sse :as sse]))

(deftest watch-test
  (let [db (atom 0)
        refreshes (atom 0)]
    (with-redefs [sse/refresh-all! (fn [] (swap! refreshes inc))]
      (sse/watch! db)
      (dotimes [_ 5]
        (swap! db inc))
      (Thread/sleep 300)
      (testing "changes in a row are one refresh"
        (is (= 1 @refreshes)))
      (swap! db identity)
      (Thread/sleep 300)
      (testing "a swap that leaves the value is no refresh"
        (is (= 1 @refreshes)))
      (swap! db inc)
      (Thread/sleep 300)
      (testing "the next change is the next refresh"
        (is (= 2 @refreshes))))))
