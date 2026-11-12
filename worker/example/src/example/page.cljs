(ns example.page
  "A counter and a list of notes, shared by every open tab: the page of
   example/, on a Worker. The view is a pure function of `db`, the
   Object's state; commands change `db`. Both run in the Object."
  (:require [clojure.string :as str]
            [rframes.worker.command :as command]))

(defn page-view
  [{:keys [db tabid woke-at]}]
  (let [{:keys [count notes]} @db]
    [:main
     [:h1 "rframes on a Worker"]
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
     [:p.hint "Open this page in a second tab: both show the same state."]
     [:p.hint "The Object behind this page woke at " [:code woke-at] ". "
      "Leave the page alone for half a minute: the Object sleeps, the page "
      "stays connected, and the next click wakes it."]]))

(defn- increment
  [w]
  (swap! (:db w) update :count inc)
  (command/accepted w))

(defn- add-note
  [w]
  (let [text (str/trim (str (:text (command/command-data w))))]
    (if (str/blank? text)
      (command/rejected w :empty-note)
      (do (swap! (:db w) update :notes conj text)
          (command/accepted w)))))

(def head
  (str "<title>rframes on a Worker</title>"
       "<link rel=\"stylesheet\" href=\"/app.css\">"
       "<script type=\"module\" src=\"/js/main.js\"></script>"))

(def register
  [{:replicant/shim "/"
    :head head}
   {:replicant/render "/"
    :render/fn #'page-view}
   {:command/kind :count/increment
    :command/fn #'increment}
   {:command/kind :note/add
    :command/fn #'add-note}])
