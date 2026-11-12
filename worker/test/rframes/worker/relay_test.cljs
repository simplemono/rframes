(ns rframes.worker.relay-test
  (:require ["node:zlib" :as zlib]
            [clojure.string :as str]
            [clojure.test :refer [async deftest is testing]]
            [rframes.worker.fake :as fake :refer [sleep]]
            [rframes.worker.relay :as relay]))

(deftest encoding-test
  (is (= "br" (relay/encoding "gzip, deflate, br")))
  (is (= "br" (relay/encoding "GZIP, BR;q=0.9")))
  (is (= "gzip" (relay/encoding "gzip, deflate")))
  (is (= "gzip" (relay/encoding "br;q=0, gzip")))
  (is (nil? (relay/encoding "deflate")))
  (is (nil? (relay/encoding "")))
  (is (nil? (relay/encoding nil))))

(deftest client-accept-encoding-test
  (testing "without a cf object the header counts"
    (is (= "gzip" (relay/client-accept-encoding
                   (js/Request. "https://example.test/" #js {:headers #js {"Accept-Encoding" "gzip"}})))))
  (testing "with one, what Cloudflare kept of the browser's header counts, also when it is none"
    (is (= "br" (relay/client-accept-encoding
                 #js {:cf #js {:clientAcceptEncoding "br"}
                      :headers (js/Headers. #js {"Accept-Encoding" "gzip, br"})})))
    (is (nil? (relay/encoding (relay/client-accept-encoding
                               #js {:cf #js {:clientAcceptEncoding ""}
                                    :headers (js/Headers. #js {"Accept-Encoding" "gzip, br"})}))))))

(defn- collect
  "Reads the body of `response` as it comes: `{:text :wire :done?}`, atoms
   of the decoded text so far, the bytes of every chunk and whether the
   stream ended."
  [^js response]
  (let [text (atom "")
        wire (atom [])
        done? (atom false)
        ^js decoder (case (.get (.-headers response) "content-encoding")
                      "br" (zlib/createBrotliDecompress)
                      "gzip" (zlib/createGunzip)
                      nil)
        ^js reader (.getReader (.-body response))
        text-decoder (js/TextDecoder.)
        pump (fn pump []
               (-> (.read reader)
                   (.then (fn [^js result]
                            (if (.-done result)
                              (reset! done? true)
                              (let [chunk (.-value result)]
                                (swap! wire conj (.-length chunk))
                                (if decoder
                                  (.write decoder chunk)
                                  (swap! text str (.decode text-decoder chunk #js {:stream true})))
                                (pump)))))
                   (.catch (fn [_] (reset! done? true)))))]
    (when decoder
      (.on decoder "data" (fn [^js chunk] (swap! text str (.toString chunk "utf8")))))
    (pump)
    {:text text
     :wire wire
     :done? done?}))

(defn- events
  "The data of the frame events in `text`."
  [text]
  (mapv second (re-seq #"event: frame\ndata: (.*)\n\n" text)))

(deftest stream-test
  (async done
    (-> ((^:async fn []
           (doseq [encoding ["br" "gzip" nil]]
             (let [socket (fake/socket)
                   ^js response (relay/response {:socket socket
                                                 :encoding encoding
                                                 :heartbeat-ms nil})
                   {:keys [text done?]} (collect response)]
               (testing (str "the head, " (or encoding "not encoded"))
                 (is (= 200 (.-status response)))
                 (is (= "text/event-stream" (.get (.-headers response) "content-type")))
                 (is (= "no-store" (.get (.-headers response) "cache-control")))
                 (is (= encoding (.get (.-headers response) "content-encoding")))
                 (is (:accepted? (fake/log socket)) "the socket is accepted"))
               (fake/message! socket "[\"a\"]")
               (await (sleep 60))
               (testing "a frame of the Object is one event, there at once"
                 (is (= ["[\"a\"]"] (events @text))))
               (fake/message! socket "[\"a\"]")
               (fake/message! socket "[\"b\"]")
               (await (sleep 60))
               (testing "a frame equal to the one before is not sent"
                 (is (= ["[\"a\"]" "[\"b\"]"] (events @text))))
               (fake/close! socket)
               (await (sleep 60))
               (testing "the Object closed the socket: the stream ends"
                 (is @done?))))))
        (.catch (fn [e] (is (nil? e) (str e))))
        (.finally done))))

(deftest delta-test
  (async done
    (-> ((^:async fn []
           (let [;; 100 kB that do not compress against themselves
                 body (str/join (repeatedly 50000 #(.toString (rand-int 256) 16)))
                 frame (fn [n] (str "[\"count\"," n ",\"" body "\"]"))
                 sizes (fn [encoding]
                         ((^:async fn []
                            (let [socket (fake/socket)
                                  {:keys [wire]} (collect (relay/response {:socket socket
                                                                           :encoding encoding
                                                                           :heartbeat-ms nil}))]
                              (fake/message! socket (frame 1))
                              (await (sleep 300))
                              (let [first-frame (reduce + @wire)]
                                (reset! wire [])
                                (fake/message! socket (frame 2))
                                (await (sleep 300))
                                [first-frame (reduce + @wire)])))))
                 [br-first br-next] (await (sizes "br"))
                 [gzip-first gzip-next] (await (sizes "gzip"))]
             (testing "brotli: a frame like the one before costs a few bytes, however long it is"
               (is (< 30000 br-first))
               (is (< br-next 200)))
             (testing "gzip looks back 32 kB only: a long frame costs its size again"
               (is (< 30000 gzip-first))
               (is (< 30000 gzip-next))))))
        (.catch (fn [e] (is (nil? e) (str e))))
        (.finally done))))

(deftest heartbeat-test
  (async done
    (-> ((^:async fn []
           (let [socket (fake/socket)
                 {:keys [text]} (collect (relay/response {:socket socket
                                                         :encoding "br"
                                                         :heartbeat-ms 40}))]
             (await (sleep 150))
             (testing "a quiet stream carries comment lines, which are no events"
               (is (<= 2 (count (re-seq #": beat\n\n" @text))))
               (is (= [] (events @text))))
             (fake/close! socket))))
        (.catch (fn [e] (is (nil? e) (str e))))
        (.finally done))))

(deftest tab-gone-test
  (async done
    (-> ((^:async fn []
           (let [socket (fake/socket)
                 controller (js/AbortController.)
                 {:keys [text done?]} (collect (relay/response {:socket socket
                                                                :encoding "gzip"
                                                                :signal (.-signal controller)
                                                                :heartbeat-ms 20}))]
             (fake/message! socket "[\"a\"]")
             (await (sleep 60))
             (.abort controller)
             (await (sleep 60))
             (testing "the tab went: the socket to the Object is closed and the stream ends"
               (is (= [1000 "tab gone"] (:closed (fake/log socket))))
               (is @done?))
             (let [before @text]
               (fake/message! socket "[\"late\"]")
               (await (sleep 80))
               (testing "nothing is written after that, no frame and no beat"
                 (is (= before @text)))))))
        (.catch (fn [e] (is (nil? e) (str e))))
        (.finally done))))

(deftest open-test
  (async done
    (-> ((^:async fn []
           (let [calls (atom [])
                 socket (fake/socket)
                 stub #js {:fetch (fn [url init]
                                    (swap! calls conj [url (js->clj (.-headers init))])
                                    (js/Promise.resolve #js {:webSocket socket}))}]
             (is (identical? socket (await (relay/open stub {"x-rframes" "e"}))))
             (is (= [["https://object/" {"x-rframes" "e" "Upgrade" "websocket"}]] @calls))
             (testing "an Object that answers no socket"
               (is (nil? (await (relay/open #js {:fetch (fn [_ _] (js/Promise.resolve #js {}))} {}))))))))
        (.catch (fn [e] (is (nil? e) (str e))))
        (.finally done))))
