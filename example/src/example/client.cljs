(ns example.client
  "The browser bundle: the rframes runtime with the components of this app.
  There are none, the page gets by with the built-in :ui/textarea."
  (:require [rframes.client :as rframes]))

(defn init!
  []
  (rframes/init! {:register []}))
