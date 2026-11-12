(ns example.client
  "The browser bundle: the rframes runtime with the components of this app.
   There are none, the page gets by with the built-in :ui/textarea. It is
   the client of every rframes server, the Worker changes nothing for it."
  (:require [rframes.client :as rframes]))

(rframes/init! {:register []})
