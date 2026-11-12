(ns example.worker
  "The Worker's module: the fetch handler, its default export, and the
   Durable Object class of the page. vite.worker.config.mjs exports
   both, wrangler.jsonc names the class.

   The state is an atom per Object, never one at namespace level:
   Objects of one class share an isolate. It is read from the Object's
   SQLite when the Object wakes and written back at every change, since
   a sleeping Object keeps no memory."
  (:require [example.page :as page]
            [rframes.worker :as worker]
            [rframes.worker.durable :refer [defobject]]
            [rframes.worker.object :as object]
            [rframes.worker.transit :as transit]))

(defn- load-db
  "The state the Object stored, or the one of a new Object."
  [^js sql]
  (.exec sql "create table if not exists state (id integer primary key check (id = 1), value text not null)")
  (if-let [^js row (first (.toArray (.exec sql "select value from state where id = 1")))]
    (transit/read-str (.-value row))
    {:count 0
     :notes []}))

(defn- save-db!
  [^js sql value]
  (.exec sql
         "insert into state (id, value) values (1, ?) on conflict (id) do update set value = excluded.value"
         (transit/write-str value)))

(defobject Page
  (fn [^js ctx _env]
    (let [sql (.-sql (.-storage ctx))
          db (atom (load-db sql))
          woke-at (.toISOString (js/Date.))]
      (add-watch db ::save (fn [_key _ref _old value] (save-db! sql value)))
      {:get-register (fn [] page/register)
       :world (fn [] {:db db
                      :woke-at woke-at})
       ;; with the watch, changing the atom is all a command has to do
       :init (fn [page] (object/watch! page db))})))

(defn- object
  "The Object of a request. The example has one page and one Object for
   it; an app picks the Object of the organization a session belongs to."
  [{:keys [worker/env]}]
  (let [^js pages (.-PAGE ^js env)]
    (.get pages (.idFromName pages "example"))))

(def handler
  "The module's default export: what the Worker runtime calls."
  #js {:fetch (worker/fetch-handler {:get-register (fn [] page/register)
                                     :object object})})
