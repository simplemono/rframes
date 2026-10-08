(ns rframes.client.ui
  "The built-in client vocabulary, concatenated after the parts set
  (parts.replicant.basic and command-query) and before the register of the
  app: generic aliases and enrichers. The output of an alias never crosses
  the wire, so it may hold real functions. The server names an alias, this
  side owns what it renders.")

(defn kind-issued?
  "Is a command of `kind` in flight? The command log is keyed by the full
  command map, so busy UI per kind scans it."
  [state kind]
  (some (fn [[command entries]]
          (and (= kind (:command/kind command))
               (= :command.status/issued (:command/status (first entries)))))
        (:ui.command/log state)))

(defn textarea
  "A textarea whose text lives in the store under :ui/store-key, so that a
  new frame from the server does not reset what the user types. The other
  attributes pass through."
  [{:keys [ui/store-key ui/state] :as attrs} _children]
  [:textarea
   (-> (dissoc attrs :ui/store-key :ui/state)
       (assoc :value (str (get state store-key ""))
              :on {:input [[:store/assoc store-key :event/target.value]]}))])

(def register
  [{:ui.alias/kind :ui/textarea
    :ui.alias/fn textarea}

   ;; [:store/deref k] in an action is replaced with the current value of k
   ;; in the store when the event is dispatched. A missing key becomes "",
   ;; so that the vector itself never rides into command data.
   {:ui.action-enricher/kind :store/deref
    :ui.action-enricher/fn (fn [{:keys [ui.action/x store]}]
                             (when (and (vector? x)
                                        (= :store/deref (first x)))
                               (get @store (second x) "")))}])
