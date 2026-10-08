(ns rframes.command
  "The one POST /command endpoint, the write side of the loop. A command is
  a transit map

      {:command/kind :agent/feedback :command/data {...}}

  resolved against the `{:command/kind .. :command/fn ..}` entries of the
  register. A command function is `w -> w`: it reads `(command-data w)` and
  answers with `accepted` or `rejected`. The answer never carries state, the
  frame stream shows what the command caused."
  (:require [rframes.transit :as transit]))

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

(defn- respond
  [w params]
  (assoc w
         :ring/response
         {:status 200
          :headers {"Content-Type" "application/transit+json"}
          :body (transit/write-str params)}))

(defn- command-fn
  [w kind]
  (some (fn [entry]
          (when (and kind (= kind (:command/kind entry)))
            (:command/fn entry)))
        ((:system/get-register w))))

(defn handler
  "The handler of the endpoint, `w -> w`."
  [w]
  (let [command (try
                  (transit/read-stream (:body (:ring/request w)))
                  (catch Exception _
                    nil))
        f (command-fn w (:command/kind command))]
    (if-not f
      (respond w {:error :unknown-command-kind})
      (try
        (respond w (merge {:success? true}
                          (:command/result (f (assoc w :command command)))))
        (catch Exception e
          (binding [*out* *err*]
            (println "[command]" (:command/kind command) (str e)))
          (respond w {:error :command-fn-failed}))))))

(def register
  "The entry that serves the endpoint on /command, where the client posts
  to by default."
  [{:ring/route [:post "/command"]
    :ring/handler #'handler}])
