(ns rframes.transit
  "Transit JSON, the encoding of the wire in both directions: the frames the
  server pushes and the commands the client posts."
  (:require [cognitect.transit :as transit]))

(defn write-str
  "`x` as a transit JSON string, which has no line breaks."
  [x]
  (let [out (java.io.ByteArrayOutputStream.)]
    (transit/write (transit/writer out :json) x)
    (.toString out "UTF-8")))

(defn read-stream
  "One transit value from an input stream."
  [in]
  (transit/read (transit/reader in :json)))

(defn read-str
  [^String s]
  (read-stream (java.io.ByteArrayInputStream. (.getBytes s "UTF-8"))))
