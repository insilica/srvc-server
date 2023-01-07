(ns srvc.server.review
  (:require [aleph.http :as ahttp]
            [babashka.fs :as fs]
            [babashka.process :as p]
            [clj-yaml.core :as yaml]
            [clojure.java.io :as io]
            [hyperlight.http-proxy :as http-proxy]
            [ring.middleware.session :as session])
  (:import [org.apache.commons.io.input Tailer TailerListener]))

(def default-opts
  {:in nil
   :err :inherit
   :out :inherit
   :shutdown p/destroy-tree})

(defn process [& args]
  (let [[maybe-opts & more] args]
    (if (map? maybe-opts)
      (apply p/process (merge default-opts maybe-opts) more)
      (apply p/process args))))

(defrecord TailListener [f g]
  TailerListener
  (fileNotFound [_this])
  (fileRotated [_this])
  (^void handle [_this ^String line]
    (f line))
  (^void handle [_this ^Exception e]
    (g e))
  (init [_this _tailer]))

(defn tailer [f g file]
  (Tailer.
   (fs/file file)
   (TailListener. f g)
   500))

(defn start-daemon [runnable]
  (doto (Thread. runnable)
    (.setDaemon true)
    .start))

(defn review-process [project-name config flow-name add-events! tail-exception! reviewer]
  (let [dir (fs/path project-name)
        db (some->> config :db (fs/path dir))
        temp-dir (fs/create-temp-dir)
        config-file (fs/path temp-dir (str "config-" (random-uuid) ".yaml"))
        sink (fs/path temp-dir (str "sink-" (random-uuid) ".jsonl"))
        config (assoc config
                      :db (str sink)
                      :reviewer reviewer
                      :sink_all_events true)]
    (with-open [writer (io/writer (fs/file config-file))]
      (yaml/generate-stream writer config))
    (when (fs/exists? db)
      (fs/copy db sink))
    {:process (process
               {:dir (str dir)}
               "sr" "--config" (str config-file) "review" flow-name)
     :sink-path sink
     :sink-thread (-> (tailer add-events! tail-exception! sink) start-daemon)
     :temp-dir temp-dir}))

(defn review-processes-component []
  #:donut.system
   {:start (fn [_]
             (atom {:processes {}}))
    :stop (fn [{:donut.system/keys [instance]}]
            (doseq [process (vals (:processes instance))]
              (p/destroy-tree process))
            nil)})

(defn get-connection-pool []
  (ahttp/connection-pool
   {:connection-options
    {:keep-alive? false
     :raw-stream? false}}))

(defn proxy-handler [get-url session-opts]
  (let [pool (get-connection-pool)]
    (fn [request]
      (let [request (session/session-request request session-opts)]
        ((http-proxy/create-handler
          {:pool pool
           :url (get-url request)})
         request)))))

(defn proxy-server [get-url listen-port session-opts]
  (http-proxy/start-server
   (proxy-handler get-url session-opts)
   {:port listen-port}))

(defn session-url [{:keys [session]}]
  (:review-proxy-url session))

(defn proxy-server-component [config]
  #:donut.system
   {:config config
    :start (fn [{{:keys [listen-ports session-opts]} :donut.system/config}]
             {:servers (->> listen-ports
                            (map #(do [% (proxy-server session-url % session-opts)]))
                            (into {}))})
    :stop (fn [{:donut.system/keys [instance]}]
            (doseq [server (vals (:servers instance))]
              (.close server))
            nil)})
