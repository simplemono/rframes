(ns rframes.worker.durable
  "The macro side of `rframes.worker.durable`: `defobject`."
  (:require [cljf.x]))

(defmacro defobject
  "A Durable Object class `name` that serves the pages of rframes: the
   frame streams the Worker's relay opens and the commands it hands
   over. `options` is the map `rframes.worker.durable/page` takes, or a
   function of the constructor's `ctx` and `env` that answers it. Export
   the class from the Worker's module and name it in wrangler.jsonc as
   a SQLite-backed class.

   An Object with more methods than these is a class of its own: the
   three methods below over `rframes.worker.object`, next to whatever
   else it has."
  [name options]
  `(cljf.x/defclass ~name
     (~'extends rframes.worker.durable/durable-object)
     (~'constructor [this# ctx# env#]
       (~'super ctx# env#)
       (set! (.-page this#) (rframes.worker.durable/page ~options ctx# env#)))
     ~'Object
     (~'fetch [this# request#]
       (rframes.worker.object/fetch (.-page this#) request#))
     (~'webSocketClose [this# socket# code# reason# clean#]
       (rframes.worker.object/closed! (.-page this#) socket#))
     (~'webSocketError [this# socket# error#]
       (rframes.worker.object/closed! (.-page this#) socket#))))
