(ns rframes.worker.envelope
  "What the Worker tells the Object about a request it hands over: the
   page's path and query string, the tab's id and the connection data
   of the app (who asks). It travels as one header, transit made safe
   for a header value. Only the Worker reaches the Object, and the
   Worker writes the header itself, so the Object can trust what it
   reads there; a header of that name a browser sent never gets this
   far."
  (:require [rframes.worker.transit :as transit]))

(def header
  "x-rframes")

(defn write
  "The header value of `envelope`, a map."
  [envelope]
  (js/encodeURIComponent (transit/write-str envelope)))

(defn read
  "The envelope of a request to the Object, nil when it carries none."
  [^js request]
  (when-let [value (.get (.-headers request) header)]
    (try
      (let [envelope (transit/read-str (js/decodeURIComponent value))]
        (when (map? envelope)
          envelope))
      (catch :default _
        nil))))
