(ns rframes.worker.object-test
  (:require [clojure.test :refer [async deftest is testing]]
            [rframes.worker.command :as command]
            [rframes.worker.envelope :as envelope]
            [rframes.worker.fake :as fake :refer [sleep]]
            [rframes.worker.object :as object]
            [rframes.worker.transit :as transit]))

(fake/install-pair!)

(defn- view
  [{:keys [db tabid conn-id user/name ring/route-params ring/request]}]
  [:main
   [:h1 "Count " (:count @db)]
   [:p tabid " " name " " (:* route-params) " " (:query-string request)]
   [:p (some? conn-id)]])

(defn- register
  [opened]
  [{:replicant/shim "/l/*"
    :head ""}
   {:replicant/render "/l/*"
    :render/fn view
    :open/fn (fn [w] (swap! opened conj (:tabid w)))}
   {:command/kind :count/increment
    :command/fn (fn [w]
                  (swap! (:db w) update :count + (or (:by (command/command-data w)) 1))
                  (command/accepted w))}
   {:command/kind :count/who
    :command/fn (fn [w]
                  (swap! (:db w) assoc :who [(:user/name w) (:uri (:ring/request w))])
                  (command/accepted w))}])

(defn- page-of
  "A page over `ctx` with the state `db`, as an Object's constructor makes it."
  [ctx db opened]
  (object/create {:ctx ctx
                  :env #js {}
                  :get-register (fn [] (register opened))
                  :world (fn [] {:db db})}))

(defn- frames
  "The frames `socket` was sent, read."
  [socket]
  (mapv transit/read-str (:sent (fake/log socket))))

(defn- envelope
  [tabid name]
  {:uri "/l/abc/"
   :query-string "x=1"
   :tabid tabid
   :connection {:user/name name}})

(deftest accept-test
  (let [ctx (fake/ctx)
        db (atom {:count 0})
        opened (atom [])
        page (page-of ctx db opened)
        client (object/accept! page (envelope "tab-1" "Anna"))
        [server] (fake/accepted ctx)]
    (testing "the other end goes to the Worker, the Object's end is accepted"
      (is (some? client))
      (is (not (identical? client server)))
      (is (= 1 (object/connections page))))
    (testing "what the connection is rides on the socket, as plain data"
      (let [attached (transit/read-str (:attachment (fake/log server)))]
        (is (= (envelope "tab-1" "Anna") (dissoc attached :conn-id)))
        (is (string? (:conn-id attached)))))
    (testing "the entry's :open/fn ran, and the first frame is out"
      (is (= ["tab-1"] @opened))
      (is (= [[:main [:h1 "Count " 0] [:p "tab-1" " " "Anna" " " "abc/" " " "x=1"] [:p true]]]
             (frames server))))
    (testing "a path no render entry serves"
      (is (nil? (object/accept! page (assoc (envelope "tab-2" "Ben") :uri "/elsewhere"))))
      (is (= 1 (object/connections page))))))

(deftest refresh-test
  (let [ctx (fake/ctx)
        db (atom {:count 0})
        page (page-of ctx db (atom []))
        _ (object/accept! page (envelope "tab-1" "Anna"))
        _ (object/accept! page (envelope "tab-2" "Ben"))
        [anna ben] (fake/accepted ctx)]
    (object/refresh-all! page)
    (testing "a refresh that renders the same frame again sends nothing"
      (is (= 1 (count (frames anna))))
      (is (= 1 (count (frames ben)))))
    (swap! db update :count inc)
    (object/refresh-all! page)
    (testing "a change reaches every connection, each rendered with its own world"
      (is (= [:h1 "Count " 1] (second (last (frames anna)))))
      (is (= "Anna" (get-in (last (frames anna)) [2 3])))
      (is (= "Ben" (get-in (last (frames ben)) [2 3]))))
    (swap! db update :count inc)
    (object/refresh-where! page (fn [w] (= "tab-2" (:tabid w))))
    (testing "refresh-where! renders the connections its predicate picks"
      (is (= 2 (count (frames anna))))
      (is (= 3 (count (frames ben)))))
    (object/closed! page ben)
    (testing "a closed socket is answered"
      (is (= [1000 "closed"] (:closed (fake/log ben)))))))

(deftest unfit-connection-test
  (let [ctx (fake/ctx)
        db (atom {:count 0})
        page (page-of ctx db (atom []))
        _ (object/accept! page (envelope "tab-1" "Anna"))
        [anna] (fake/accepted ctx)]
    (testing "connection data a socket cannot carry: no connection, the socket closed"
      (let [pair (.-WebSocketPair js/globalThis)]
        (set! (.-WebSocketPair js/globalThis)
              (fn []
                (let [server (fake/socket)]
                  (set! (.-serializeAttachment server) (fn [_] (throw (js/Error. "too large"))))
                  (js-obj "0" (fake/socket) "1" server))))
        (is (nil? (object/accept! page (envelope "tab-2" "Ben"))))
        (set! (.-WebSocketPair js/globalThis) pair))
      (is (= [1011 "attachment"] (:closed (fake/log (second (fake/accepted ctx)))))))
    (swap! db update :count inc)
    (object/refresh-all! page)
    (testing "a socket that carries nothing is skipped, the others get their frame"
      (is (= [:h1 "Count " 1] (second (last (frames anna))))))))

(deftest hibernation-test
  (let [ctx (fake/ctx)
        db (atom {:count 4})
        _ (object/accept! (page-of ctx db (atom [])) (envelope "tab-1" "Anna"))
        [anna] (fake/accepted ctx)
        ;; the Object slept: its memory is gone, its sockets are not
        woken (page-of ctx (atom {:count 5}) (atom []))]
    (object/refresh-all! woken)
    (testing "a page made anew renders the sockets of before, by their attachments"
      (is (= 1 (object/connections woken)))
      (is (= [:main [:h1 "Count " 5] [:p "tab-1" " " "Anna" " " "abc/" " " "x=1"] [:p true]]
             (last (frames anna)))))))

(deftest broken-view-test
  (let [ctx (fake/ctx)
        broken? (atom false)
        page (object/create {:ctx ctx
                             :get-register (fn []
                                             [{:replicant/render "/"
                                               :render/fn (fn [_]
                                                            (if @broken?
                                                              (throw (ex-info "boom" {}))
                                                              [:p "fine"]))}])})
        _ (object/accept! page {:uri "/" :tabid "t"})
        [socket] (fake/accepted ctx)]
    (reset! broken? true)
    (object/refresh-all! page)
    (testing "a view that throws costs that frame, not the connection"
      (is (= [[:p "fine"]] (frames socket))))
    (reset! broken? false)
    (object/refresh-all! page)
    (is (= [[:p "fine"]] (frames socket)))))

(deftest schedule-test
  (async done
    (-> ((^:async fn []
           (let [ctx (fake/ctx)
                 db (atom {:count 0})
                 page (page-of ctx db (atom []))
                 _ (object/accept! page (envelope "tab-1" "Anna"))
                 [anna] (fake/accepted ctx)]
             (object/watch! page db)
             (dotimes [_ 5]
               (swap! db update :count inc))
             (await (sleep 30))
             (testing "the changes of one tick are one frame, and it does not wait"
               (is (= [0 5] (mapv #(get-in % [1 2]) (frames anna)))))
             (swap! db update :count inc)
             (swap! db update :count inc)
             (await (sleep 30))
             (testing "a change right after waits for the gap"
               (is (= 2 (count (frames anna)))))
             (await (sleep 200))
             (is (= [0 5 7] (mapv #(get-in % [1 2]) (frames anna))))
             (swap! db identity)
             (await (sleep 200))
             (testing "a swap that leaves the value is no refresh"
               (is (= 3 (count (frames anna)))))
             (object/unwatch! db)
             (swap! db update :count inc)
             (await (sleep 200))
             (testing "no watch, no refresh"
               (is (= 3 (count (frames anna))))))))
        (.catch (fn [e] (is (nil? e) (str e))))
        (.finally done))))

(deftest instances-test
  (let [db-a (atom {:count 1})
        db-b (atom {:count 2})
        ctx-a (fake/ctx)
        ctx-b (fake/ctx)
        page-a (page-of ctx-a db-a (atom []))
        page-b (page-of ctx-b db-b (atom []))]
    (object/accept! page-a (envelope "tab-a" "Anna"))
    (object/accept! page-b (envelope "tab-b" "Ben"))
    (swap! db-a update :count inc)
    (object/refresh-all! page-a)
    (testing "two Objects of one class share nothing: a refresh of one leaves the other alone"
      (is (= 2 (count (frames (first (fake/accepted ctx-a))))))
      (is (= 1 (count (frames (first (fake/accepted ctx-b)))))))))

(defn- request
  [headers init]
  (js/Request. "https://object/" (clj->js (merge {:headers headers} init))))

(deftest fetch-test
  (async done
    (-> ((^:async fn []
           (let [ctx (fake/ctx)
                 db (atom {:count 0})
                 page (page-of ctx db (atom []))
                 _ (object/accept! page (envelope "tab-1" "Anna"))
                 [anna] (fake/accepted ctx)
                 post (fn [command who]
                        (object/fetch page (request {envelope/header (envelope/write {:uri "/l/abc/command"
                                                                                      :connection {:user/name who}})}
                                                    {:method "POST"
                                                     :body (transit/write-str command)})))]
             (let [^js response (await (post {:command/kind :count/increment :command/data {:by 2}} "Ben"))]
               (testing "a command runs here and answers transit"
                 (is (= 200 (.-status response)))
                 (is (= {:success? true} (transit/read-str (await (.text response)))))
                 (is (= 2 (:count @db)))))
             (await (sleep 30))
             (testing "the open pages are rendered again after a command, no watch needed"
               (is (= [:h1 "Count " 2] (second (last (frames anna))))))
             (await (post {:command/kind :count/who} "Ben"))
             (testing "a command function sees who asks and where the command went"
               (is (= ["Ben" "/l/abc/command"] (:who @db))))
             (testing "a command no entry knows"
               (is (= {:error :unknown-command-kind}
                      (transit/read-str (await (.text (await (post {:command/kind :nope} "Ben"))))))))
             (testing "a request without an envelope is not the Worker's"
               (is (= 404 (.-status (await (object/fetch page (request {} {:method "POST" :body "x"})))))))
             (testing "an envelope alone is no command"
               (is (= 404 (.-status (await (object/fetch page (request {envelope/header (envelope/write {:uri "/"})} {}))))))))))
        (.catch (fn [e] (is (nil? e) (str e))))
        (.finally done))))
