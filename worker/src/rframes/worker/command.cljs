(ns rframes.worker.command
  "The write side of the loop. A command is a transit map

       {:command/kind :note/add :command/data {...}}

   that the browser posts to `command` next to its page. The Worker hands
   it to the Object of the page, and there it is resolved against the
   `{:command/kind .. :command/fn ..}` entries of the register. A command
   function is `w -> w`, or `w -> promise of w`: it reads
   `(command-data w)` and answers with `accepted` or `rejected`. The
   answer never carries state, the frame stream shows what the command
   caused."
  (:require [rframes.worker.transit :as transit]))

(defn command-data
  [w]
  (get-in w [:command :command/data]))

(defn accepted
  [w]
  (assoc w :command/result {:success? true}))

(defn rejected
  "A command error that the command log of the client records."
  [w error]
  (assoc w :command/result {:success? false
                            :result {:error error}}))

(defn- command-fn
  [w kind]
  (some (fn [entry]
          (when (and kind (= kind (:command/kind entry)))
            (:command/fn entry)))
        ((:system/get-register w))))

(defn read-command
  "The command of a request body, nil when the text is no transit map."
  [text]
  (try
    (let [command (transit/read-str text)]
      (when (map? command)
        command))
    (catch :default _
      nil)))

(defn ^:async run
  "What the endpoint answers for `command` in the world `w`: the result
   of its command function, or the error of a command no entry knows or
   whose function threw."
  [w command]
  (let [f (command-fn w (:command/kind command))]
    (if-not f
      {:error :unknown-command-kind}
      (try
        (merge {:success? true}
               (:command/result (await (f (assoc w :command command)))))
        (catch :default e
          (js/console.error "[command]" (str (:command/kind command)) (str e))
          {:error :command-fn-failed})))))

(defn response
  "The ring response of an answer of `run`."
  [answer]
  {:status 200
   :headers {"Content-Type" "application/transit+json"}
   :body (transit/write-str answer)})
