(ns rframes.http-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [rframes.http :as http]))

(deftest match-test
  (is (= {} (http/match [:get "/"] {:request-method :get
                                    :uri "/"})))
  (is (= {:* "assets/a.mp4"}
         (http/match [:get "/project/*"] {:request-method :get
                                          :uri "/project/assets/a.mp4"})))
  (is (nil? (http/match [:get "/"] {:request-method :post
                                    :uri "/"})))
  (is (nil? (http/match [:get "/project/*"] {:request-method :get
                                             :uri "/projects"}))))

(deftest parse-range-test
  (is (= [0 999] (http/parse-range "bytes=0-" 1000)))
  (is (= [100 199] (http/parse-range "bytes=100-199" 1000)))
  (is (= [900 999] (http/parse-range "bytes=900-5000" 1000)))
  (is (= [500 999] (http/parse-range "bytes=-500" 1000)))
  (is (nil? (http/parse-range "bytes=1000-" 1000)))
  (is (nil? (http/parse-range "items=0-1" 1000)))
  (is (nil? (http/parse-range nil 1000))))

(deftest files-test
  (let [root (.toFile (java.nio.file.Files/createTempDirectory
                       "project"
                       (make-array java.nio.file.attribute.FileAttribute 0)))
        file (io/file root "assets" "a b.txt")]
    (try
      (io/make-parents file)
      (spit file "0123456789")
      (testing "the path is URL-decoded and stays below the root"
        (is (= (.getCanonicalFile file) (http/resolve-file root "assets/a%20b.txt")))
        (is (nil? (http/resolve-file root "../etc/passwd")))
        (is (nil? (http/resolve-file root "assets")))
        (is (nil? (http/resolve-file root "missing.txt"))))
      (testing "a range request gets the bytes it asked for"
        (let [{:keys [status headers body]} (http/file-response {:headers {"range" "bytes=2-5"}} file)]
          (is (= 206 status))
          (is (= "bytes 2-5/10" (get headers "Content-Range")))
          (is (= "2345" (slurp body)))))
      (testing "without a range the whole file"
        (is (= 200 (:status (http/file-response {:headers {}} file)))))
      (finally
        (io/delete-file file)
        (io/delete-file (io/file root "assets"))
        (io/delete-file root)))))

(deftest resources-test
  (let [handler (http/ring-handler (constantly [(http/resources "/hello.txt" "rframes/fixtures/hello.txt")
                                                (http/resources "/files/*" "rframes/fixtures")]))
        fetch (fn [uri]
              (handler {:request-method :get
                        :uri uri}))]
    (testing "one resource on a plain path"
      (let [{:keys [status headers body]} (fetch "/hello.txt")]
        (is (= 200 status))
        (is (= "text/plain; charset=utf-8" (get headers "Content-Type")))
        (is (= "hello\n" (slurp body)))))
    (testing "the resources below a root on a /* path"
      (is (= "hello\n" (slurp (:body (fetch "/files/hello.txt")))))
      (is (= 404 (:status (fetch "/files/missing.txt"))))
      (is (= 404 (:status (fetch "/files/"))))
      (is (= 404 (:status (fetch "/files/../fixtures/hello.txt")))))))
