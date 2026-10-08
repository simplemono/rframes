(ns rframes.client
  "The browser runtime for pages served by rframes.replicant. The shim's
  div#root[data-rframes-view] is the cue: absent it, init! is a no-op. One
  store atom (rframes.client.state/store): the latest frame of the server
  lives under :server/frame, everything else is client-local state, and
  every store change re-renders.

  Client state reaches components as alias attrs, not :alias-data: render!
  walks the frame and assocs the state onto every :ui/* alias node as
  :ui/state. Replicant re-renders an alias on changed alias data in dev
  builds only, and alias expansion memoizes on attrs and children, so changed
  attrs are the release-safe way to make state flow.

  Events dispatch through the register-driven event loop of
  parts.replicant.core, commands go to /command."
  (:require [clojure.walk :as walk]
            [rframes.client.frames :as frames]
            [rframes.client.state :as state]
            [rframes.client.ui :as ui]
            [parts.replicant.basic :as basic]
            [parts.replicant.command-query :as command-query]
            [parts.replicant.core :as core]
            [replicant.alias :as alias]
            [replicant.dom :as r]))

(defn- full-register
  "The built-in vocabulary (parts, then rframes.client.ui), followed by the
  entries of the app."
  [register]
  (concat (basic/get-all)
          command-query/register
          ui/register
          register))

(defn- register-aliases!
  [register]
  (doseq [entry register]
    (when-let [kind (:ui.alias/kind entry)]
      (alias/register! kind (:ui.alias/fn entry)))))

(defn- with-ui-state
  "Assocs the client state as :ui/state onto the attrs of every :ui/* alias
  node, so that a state change re-expands the aliases."
  [frame state]
  (walk/postwalk
   (fn [x]
     ;; Map entries also arrive as vectors: [:ui/src "x"] inside an attrs
     ;; map must not be mistaken for an alias node.
     (if (and (vector? x)
              (not (map-entry? x))
              (keyword? (first x))
              (= "ui" (namespace (first x))))
       (let [[tag & args] x
             attrs (when (map? (first args))
                     (first args))
             children (if (or (map? (first args)) (nil? (first args)))
                        (rest args)
                        args)]
         (into [tag (assoc (or attrs {}) :ui/state state)] children))
       x))
   frame))

(defn- stick-to-end
  "The element that follows its newest content, like a chat: it stays
  scrolled to its end as long as the user has not scrolled away from it.
  The server marks it with a data-stick-to-end attribute."
  []
  (js/document.querySelector "[data-stick-to-end]"))

(defn- at-end?
  [^js element]
  (< (- (.-scrollHeight element) (.-scrollTop element) (.-clientHeight element))
     80))

(defn- render!
  [{:keys [ui/state ui/root]}]
  (when-let [frame (:server/frame state)]
    (let [before (stick-to-end)
          follow? (or (nil? before) (at-end? before))]
      (r/render root (with-ui-state frame (dissoc state :server/frame)))
      (when follow?
        (when-let [^js element (stick-to-end)]
          (set! (.-scrollTop element) (.-scrollHeight element)))))))

(defn start!
  "Mounts the runtime on the root node of the shim and opens the view
  stream. `register` holds the client entries of the app: aliases
  (:ui.alias/kind), action handlers (:ui.action/kind), enrichers and
  predicates, see parts.replicant.core. They follow the built-in ones."
  [{:keys [register]}]
  (when-let [root (js/document.querySelector "[data-rframes-view]")]
    (let [register (full-register register)]
      (register-aliases! register)
      (let [w (-> {:ui/get-register (constantly register)
                   :ui/root root
                   :ui/render! render!
                   :ui/store state/store}
                  (core/add-ui-log)
                  (core/add-event-handler)
                  (core/add-dispatch!)
                  (core/add-store-dispatch!)
                  (core/add-render-watcher!))]
        (frames/connect!
         {:url (str js/location.pathname js/location.search)
          :body {:tabid state/tabid}
          :on-frame (fn [view]
                      (swap! (:ui/store w) assoc :server/frame view))})))))

(defn init!
  "Starts the runtime once the document is parsed. The :init-fn of the
  bundle calls this with the register of the app:

      (defn init! [] (rframes.client/init! {:register my-register}))"
  [opts]
  (if (= "loading" (.-readyState js/document))
    (js/document.addEventListener "DOMContentLoaded" #(start! opts))
    (start! opts)))
