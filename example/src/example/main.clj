(ns example.main
  "A counter and a list of notes, shared by every open tab. The page is a
  pure function of `db`, commands change `db`, the watch pushes a new
  frame to every connection."
  (:require [clojure.string :as str]
            [rframes.command :as command]
            [rframes.http :as http]
            [rframes.replicant :as replicant]
            [rframes.sse :as sse]
            [org.httpkit.server :as server]))

(defonce db
  (atom {:count 0
         :notes []}))

(defn page-view
  [{:keys [tabid]}]
  (let [{:keys [count notes]} @db]
    [:main
     [:h1 "rframes"]
     [:p "Clicked " [:strong count] " times, from tab " [:code tabid] "."]
     [:button {:on {:click [[:data/command {:command/kind :count/increment}]]}}
      "+1"]
     [:h2 "Notes"]
     ;; The draft is client state: a new frame does not reset it.
     [:ui/textarea {:ui/store-key :draft
                    :rows 2
                    :placeholder "Something worth keeping"}]
     [:button {:on {:click [[:data/command
                             {:command/kind :note/add
                              :command/data {:text [:store/deref :draft]}}
                             {:on-success [[:store/dissoc :draft]]}]]}}
      "Add"]
     [:ul
      (for [[index note] (map-indexed vector notes)]
        [:li {:replicant/key index} note])]
     [:p.hint "Open this page in a second tab: both show the same state."]]))

(defn- increment
  [w]
  (swap! db update :count inc)
  (command/accepted w))

(defn- add-note
  [w]
  (let [text (str/trim (str (:text (command/command-data w))))]
    (if (str/blank? text)
      (command/rejected w :empty-note)
      (do (swap! db update :notes conj text)
          (command/accepted w)))))

(def head
  (str "<title>rframes</title>"
       "<link rel=\"stylesheet\" href=\"/app.css\">"
       "<script defer src=\"/js/main.js\"></script>"))

(def register
  [(http/resources "/app.css" "public/app.css")
   (http/resources "/js/*" "public/js")
   {:replicant/shim "/"
    :head head}
   {:replicant/render "/"
    :render/fn #'page-view}
   {:command/kind :count/increment
    :command/fn #'increment}
   {:command/kind :note/add
    :command/fn #'add-note}])

(defn get-register
  []
  (replicant/expand (concat register command/register)))

(defn -main
  [& _args]
  (sse/watch! db)
  (server/run-server (http/ring-handler get-register) {:port 8080})
  (println "example on http://localhost:8080")
  @(promise))
