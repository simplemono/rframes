(ns rframes.worker.envelope-test
  (:require [clojure.test :refer [deftest is testing]]
            [rframes.worker.envelope :as envelope]))

(defn- request
  [headers]
  (js/Request. "https://object/" (clj->js {:headers headers})))

(deftest round-trip-test
  (let [envelope {:uri "/l/abc/"
                  :query-string "x=1"
                  :tabid "1a2b3c4d"
                  :connection {:user/name "Zoë Müller ✓"
                               :user/role :admin}}]
    (testing "what the Worker wrote is what the Object reads, whatever the characters"
      (is (= envelope (envelope/read (request {envelope/header (envelope/write envelope)})))))
    (testing "the value is fit for a header"
      (is (re-matches #"[\x21-\x7E]+" (envelope/write envelope))))))

(deftest missing-test
  (is (nil? (envelope/read (request {}))))
  (is (nil? (envelope/read (request {envelope/header "%7Bnot%20transit"}))))
  (is (nil? (envelope/read (request {envelope/header (js/encodeURIComponent "[1,2]")})))))
