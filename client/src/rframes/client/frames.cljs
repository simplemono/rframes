(ns rframes.client.frames
  "The view stream: POST the page's own path, read SSE `frame` events, hand
  each transit-decoded view to the app. Owns staying connected: retry
  forever with a capped backoff (users close tabs, they do not debug
  streams), an immediate reconnect when the browser comes back online, and
  a hidden tab closes its stream (it would pin one of the browser's ~6
  per-host connections and hold a server connection for an invisible page)
  and reconnects fresh on becoming visible, which also covers mobile tabs
  thawing after a freeze."
  (:require [clojure.string :as str]
            [cognitect.transit :as t]))

;; --- SSE parsing (pure) ---

(defn- parse-event
  "One blank-line-delimited SSE block -> {:event s :data s}, or nil for
  comment-only blocks. Multiple data: lines join with newlines per the SSE
  spec; unknown fields (retry:, id:) are ignored."
  [block]
  (let [fields (keep (fn [line]
                       (when-let [[_ k v] (re-matches #"([^:]+): ?(.*)" line)]
                         [k v]))
                     (str/split-lines block))
        data (keep (fn [[k v]] (when (= "data" k) v)) fields)]
    (when (seq data)
      {:event (or (some (fn [[k v]] (when (= "event" k) v)) fields)
                  "message")
       :data (str/join "\n" data)})))

(defn parse-chunk
  "Accumulate `chunk` onto `buffer` and split complete events off the
  front: {:events [...] :buffer rest}. An event split across chunk
  boundaries stays in :buffer until its blank line arrives; CRLF is
  tolerated."
  [buffer chunk]
  (let [text (str/replace (str buffer chunk) "\r\n" "\n")
        parts (str/split text #"\n\n+" -1)]
    {:events (into [] (keep parse-event) (butlast parts))
     :buffer (last parts)}))

;; --- the connection ---

(defn connect!
  "Open the stream and keep it open: POST `url` with `body` (transit
  {:tabid ...}), call (on-frame view) per decoded `frame` event. Returns a
  reconnect fn (also wired to the online and visibilitychange events). One
  AbortController per attempt: a deliberate reconnect aborts the old socket
  and its pump loop recognizes itself as stale."
  [{:keys [url body on-frame]}]
  (let [state (atom {:ctrl nil :backoff 1000})
        treader (t/reader :json)
        payload (t/write (t/writer :json) body)]
    (letfn [(current? [c] (identical? c (:ctrl @state)))
            (schedule-retry [c]
              (when (current? c)
                (let [ms (:backoff @state)]
                  (swap! state assoc :backoff (min 15000 (* 2 ms)))
                  (js/setTimeout #(when (current? c) (open!)) ms))))
            (handle! [events]
              (doseq [{:keys [event data]} events]
                (when (= "frame" event)
                  ;; a healthy frame resets the backoff
                  (swap! state assoc :backoff 1000)
                  (try (on-frame (t/read treader data))
                       (catch :default e
                         (js/console.error "[frame decode]" e))))))
            (pump [c ^js rdr ^js decoder buffer]
              (-> (.read rdr)
                  (.then (fn [^js r]
                           (if (.-done r)
                             (schedule-retry c)
                             (let [{:keys [events buffer]}
                                   (parse-chunk buffer
                                                (.decode decoder (.-value r)
                                                         #js {:stream true}))]
                               (handle! events)
                               (pump c rdr decoder buffer)))))
                  (.catch (fn [_] (schedule-retry c)))))
            (open! []
              ;; never while hidden: becoming visible reconnects
              (when-not (= "hidden" js/document.visibilityState)
                (let [^js c (js/AbortController.)]
                  (swap! state assoc :ctrl c)
                  (-> (js/fetch url #js {:method "POST"
                                         :headers #js {"Accept" "text/event-stream"}
                                         :body payload
                                         :signal (.-signal c)})
                      (.then (fn [^js res]
                               (pump c (.getReader (.-body res))
                                     (js/TextDecoder.) "")))
                      (.catch (fn [_] (schedule-retry c)))))))
            (close! []
              (let [^js old (:ctrl @state)]
                (swap! state assoc :ctrl nil)
                (when old (.abort old))))
            (reconnect! []
              (swap! state assoc :backoff 1000)
              (close!)
              (open!))]
      (js/window.addEventListener "online" (fn [_] (reconnect!)))
      (js/document.addEventListener
       "visibilitychange"
       (fn [_]
         (if (= "hidden" js/document.visibilityState)
           (close!)
           (reconnect!))))
      ;; belt for a race the visibilitychange braces can't catch: a tab
      ;; created in the background whose visibility flips between module
      ;; init and listener registration would otherwise stay closed
      ;; forever. Focus with no live connection means open.
      (js/window.addEventListener
       "focus"
       (fn [_] (when (nil? (:ctrl @state)) (reconnect!))))
      (open!)
      reconnect!)))
