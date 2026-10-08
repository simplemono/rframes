(ns rframes.http
  "Routing over a register and file responses with range requests.

  A register is a sequence of entries. An entry with a route looks like

      {:ring/route [:get \"/project/*\"] :ring/handler #'handler}

  and its handler is a function `w -> w` of the world: it reads
  `:ring/request` and `:ring/route-params` and adds `:ring/response`."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

(def not-found
  {:status 404
   :headers {"Content-Type" "text/plain"}
   :body "not found"})

(defn match
  "The route params if `route` matches the request, otherwise nil. A path
  that ends with /* matches its subtree, the rest of the path is under :*."
  [[method path] {:keys [request-method uri]}]
  (when (= method request-method)
    (if (str/ends-with? path "/*")
      (let [prefix (subs path 0 (dec (count path)))]
        (when (str/starts-with? uri prefix)
          {:* (subs uri (count prefix))}))
      (when (= path uri)
        {}))))

(defn ring-handler
  "The ring handler over `get-register`. The register is consulted per
  request, so a redefined entry is live at once. The first matching entry
  wins."
  [get-register]
  (fn [request]
    (let [w {:ring/request request
             :system/get-register get-register}]
      (or (some (fn [{:keys [ring/route ring/handler]}]
                  (when-let [route-params (and route (match route request))]
                    (:ring/response (handler (assoc w :ring/route-params route-params)))))
                (get-register))
          not-found))))

(def content-types
  {"html" "text/html; charset=utf-8"
   "css" "text/css; charset=utf-8"
   "js" "application/javascript; charset=utf-8"
   "mjs" "application/javascript; charset=utf-8"
   "json" "application/json; charset=utf-8"
   "svg" "image/svg+xml"
   "png" "image/png"
   "jpg" "image/jpeg"
   "jpeg" "image/jpeg"
   "gif" "image/gif"
   "webp" "image/webp"
   "avif" "image/avif"
   "mp4" "video/mp4"
   "m4v" "video/mp4"
   "webm" "video/webm"
   "mov" "video/quicktime"
   "mp3" "audio/mpeg"
   "m4a" "audio/mp4"
   "aac" "audio/aac"
   "wav" "audio/wav"
   "ogg" "audio/ogg"
   "woff" "font/woff"
   "woff2" "font/woff2"
   "ttf" "font/ttf"
   "otf" "font/otf"
   "txt" "text/plain; charset=utf-8"
   "vtt" "text/vtt; charset=utf-8"})

(defn content-type
  [file-name]
  (get content-types
       (str/lower-case (or (second (re-find #"\.([^./]+)$" file-name)) ""))
       "application/octet-stream"))

(defn resolve-file
  "The file for the URL path `subpath` below `root`, or nil if it leaves the
  root or is not a regular file."
  [root subpath]
  (let [decoded (try
                  (java.net.URLDecoder/decode (str/replace subpath "+" "%2B") "UTF-8")
                  (catch Exception _
                    nil))
        root (.getCanonicalFile (io/file root))
        file (when (seq decoded)
               (.getCanonicalFile (io/file root decoded)))]
    (when (and file
               (str/starts-with? (.getPath file)
                                 (str (.getPath root) java.io.File/separator))
               (.isFile file))
      file)))

(defn parse-range
  "The inclusive byte positions [start end] of a Range header for a file of
  `length` bytes, or nil if the header does not describe one satisfiable
  range."
  [header length]
  (when-let [[_ from to] (re-matches #"bytes=(\d*)-(\d*)" (str/trim (or header "")))]
    (let [last-byte (dec length)]
      (cond
        (and (seq from) (seq to))
        (let [start (parse-long from)
              end (min (parse-long to) last-byte)]
          (when (<= start end)
            [start end]))

        (seq from)
        (let [start (parse-long from)]
          (when (<= start last-byte)
            [start last-byte]))

        (seq to)
        (let [suffix (parse-long to)]
          (when (and (pos? suffix) (pos? length))
            [(max 0 (- length suffix)) last-byte]))))))

(def max-range-bytes
  "A range response carries at most this many bytes. The browser asks for the
  rest with its next range request."
  (* 4 1024 1024))

(defn- read-bytes
  [file start n]
  (with-open [in (java.io.RandomAccessFile. file "r")]
    (.seek in start)
    (let [buffer (byte-array n)]
      (.readFully in buffer)
      buffer)))

(defn file-response
  "Serves `file`. A range request gets a 206, which <video> and <audio> need
  for seeking."
  [request file]
  (let [length (.length file)
        headers {"Content-Type" (content-type (.getName file))
                 "Accept-Ranges" "bytes"
                 "Cache-Control" "no-cache"}]
    (if-let [[start end] (parse-range (get-in request [:headers "range"]) length)]
      (let [end (min end (+ start (dec max-range-bytes)))]
        {:status 206
         :headers (assoc headers
                         "Content-Range" (str "bytes " start "-" end "/" length))
         :body (java.io.ByteArrayInputStream.
                (read-bytes file start (inc (- end start))))})
      {:status 200
       :headers headers
       :body file})))

(defn resource-response
  "Serves a classpath resource, e.g. a file inside the uberjar."
  [resource-path]
  (if-let [resource (io/resource resource-path)]
    {:status 200
     :headers {"Content-Type" (content-type resource-path)
               "Cache-Control" "no-cache"}
     :body (io/input-stream resource)}
    not-found))

(defn resources
  "A register entry that serves classpath resources on `path`: the one
  resource `resource-root` for a plain path, the resources below it for a
  path that ends with /*.

      (resources \"/app.css\" \"public/app.css\")
      (resources \"/js/*\" \"public/js\")"
  [path resource-root]
  {:ring/route [:get path]
   :ring/handler (fn [w]
                   (let [subpath (get-in w [:ring/route-params :*])]
                     (assoc w
                            :ring/response
                            (cond
                              (nil? subpath)
                              (resource-response resource-root)

                              (or (str/blank? subpath) (str/includes? subpath ".."))
                              not-found

                              :else
                              (resource-response (str resource-root "/" subpath))))))})

(comment
  (match [:get "/project/*"] {:request-method :get :uri "/project/assets/a.mp4"})
  (parse-range "bytes=0-" 1000)
  (parse-range "bytes=-500" 1000)
  )
