(ns rframes.worker.transit
  "Transit JSON, the encoding of the wire in every direction: the frames
   the Object pushes, the commands the browser posts and what the Worker
   tells the Object about a request. The port of rframes.transit to
   transit-cljs."
  (:require [cognitect.transit :as t]))

(def ^:private writer
  (t/writer :json))

(def ^:private reader
  (t/reader :json))

(defn write-str
  "`x` as a transit JSON string, which has no line breaks."
  [x]
  (t/write writer x))

(defn read-str
  [s]
  (t/read reader s))
