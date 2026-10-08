(ns rframes.replicant-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [rframes.replicant :as replicant]
            [rframes.sse :as sse]
            [rframes.transit :as transit]))

(defn- read-frame
  [frame]
  (let [[_ data] (re-matches #"(?s)event: frame\ndata: (.*)\n\n" frame)]
    (transit/read-str data)))

(deftest frame-event-test
  (let [view [:div {:on {:click [[:store/assoc :open? true]]}}
              (for [n (range 2)]
                [:span {:replicant/key n} "line\nbreak"])]
        frame (replicant/frame-event view)]
    (testing "one frame is one SSE event on one data line"
      (is (= ["event: frame" "" "" ""]
             (assoc (str/split frame #"\n" -1) 1 ""))
          frame)
      (is (str/starts-with? (second (str/split-lines frame)) "data: [")))
    (testing "the client reads the same hiccup, a fragment stays a seq"
      (is (= view (read-frame frame)))
      (is (seq? (nth (read-frame frame) 2))))
    (testing "a view that transit cannot write is skipped"
      (is (nil? (binding [*err* (java.io.StringWriter.)]
                  (replicant/frame-event [:button {:on {:click (fn [] nil)}}])))))))

(defn- body
  [s]
  (java.io.ByteArrayInputStream. (.getBytes ^String s "UTF-8")))

(deftest tabid-test
  (is (= "a1b2c3d4" (replicant/tabid {:body (body (transit/write-str {:tabid "a1b2c3d4"}))})))
  (is (nil? (replicant/tabid {:body (body (transit/write-str [1 2]))})))
  (is (nil? (replicant/tabid {:body (body "not transit")})))
  (is (nil? (replicant/tabid {}))))

(deftest shim-test
  (let [{:keys [ring/route ring/handler]} (replicant/expand-entry {:replicant/shim "/"
                                                                   :head "<title>t</title>"})
        {:keys [status body]} (:ring/response (handler {}))]
    (is (= [:get "/"] route))
    (is (= 200 status))
    (is (str/includes? body "<title>t</title>"))
    (is (str/includes? body "data-rframes-view"))))

(deftest expand-test
  (is (= [:post "/"] (:ring/route (replicant/expand-entry {:replicant/render "/"
                                                           :render/fn identity}))))
  (is (= {:ring/route [:get "/x"]} (replicant/expand-entry {:ring/route [:get "/x"]}))))

(defn- gunzip
  [chunks]
  (let [out (java.io.ByteArrayOutputStream.)]
    (doseq [chunk chunks]
      (.write out chunk))
    (let [in (java.util.zip.GZIPInputStream.
              (java.io.ByteArrayInputStream. (.toByteArray out)))
          buffer (byte-array 100000)
          n (.read in buffer)]
      (String. buffer 0 n "UTF-8"))))

(deftest gzip-encoder-test
  (let [encode (sse/gzip-encoder)
        frame (str "event: frame\ndata: " (apply str (repeat 500 "[\"~:div\",\"text\"]")) "\n\n")
        first-chunk (encode frame)
        second-chunk (encode frame)]
    (testing "every frame can be read as soon as it arrived"
      (is (= frame (gunzip [first-chunk]))))
    (testing "a frame that repeats the one before costs next to nothing"
      (is (< (count second-chunk) (quot (count first-chunk) 2))))))
