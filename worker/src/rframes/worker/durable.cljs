(ns rframes.worker.durable
  "The Durable Object class of an app's pages. `(defobject Page options)`
   defines it: its `fetch` takes what the Worker hands over, a WebSocket
   per browser tab and the commands, its `webSocketClose` and
   `webSocketError` forget a connection. Everything else is
   `rframes.worker.object`, which knows no class and is tested without
   one."
  (:require-macros [rframes.worker.durable])
  (:require ["cloudflare:workers" :as cf]
            [cljf.x]
            [rframes.worker.object :as object]))

(def durable-object
  "The base class an Object of pages extends."
  cf/DurableObject)

(defn page
  "The page of a new Object: `rframes.worker.object/create` over
   `options` with the constructor's `ctx` and `env`. `options` is a map,
   or a function of `ctx` and `env` that answers one, for an app whose
   state is made per Object:

     :get-register  a function that answers the register
     :world         a function that answers the app's part of the world
     :init          a function of the page, called once it is made: the
                    place for `(object/watch! page db)`"
  [options ctx env]
  (let [{:keys [init] :as options} (if (fn? options)
                                     (options ctx env)
                                     options)
        page (object/create (assoc options :ctx ctx :env env))]
    (when init
      (init page))
    page))
