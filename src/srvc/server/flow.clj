(ns srvc.server.flow
  (:require [aleph.http :as ahttp]
            [babashka.fs :as fs]
            [babashka.process :as p]
            [clj-yaml.core :as yaml]
            [clojure.java.io :as io]
            [hyperlight.http-proxy :as http-proxy]
            [ring.middleware.session :as session]
            [srvc.server.process :as process])
  (:import [org.apache.commons.io.input Tailer TailerListener]))

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
(defn remove-ports [{:keys [flows] :as config}]
  (->>
   (reduce
    (fn [m [k {:keys [steps] :as v}]]
      (assoc m k
             (assoc v :steps (map #(dissoc % :port) steps))))
    {}
    flows)
   (assoc config :flows)))

(defn remove-sink-steps [{:keys [flows] :as config}]
  (->>
   (reduce
    (fn [m [k {:keys [steps] :as v}]]
      (assoc m k
             (if (-> steps last :run-embedded (= "sink"))
               (assoc v :steps (vec (butlast steps)))
               v)))
    {}
    flows)
   (assoc config :flows)))

(defn flow-process [project-name config flow-name add-events! tail-exception! reviewer]
  (let [dir (fs/path project-name)
        db (some->> config :db (fs/path dir))
        temp-dir (fs/create-temp-dir)
        config-file (fs/path temp-dir (str "config-" (random-uuid) ".yaml"))
        sink (fs/path temp-dir (str "sink-" (random-uuid) ".jsonl"))
        config(-> (assoc config
                         :db (str sink)
                         :reviewer reviewer
                         :sink-all-events true)
                  remove-sink-steps
                  remove-ports)]
    (with-open [writer (io/writer (fs/file config-file))]
      (yaml/generate-stream writer config))
    (when (fs/exists? db)
      @(process/process
           {:dir (str dir)}
          "sr" "--config" (str config-file) "pull" (str (fs/canonicalize db))))
    {:process (process/process
               {:dir (str dir)}
               "sr" "--config" (str config-file) "flow" flow-name)
     :sink-path sink
     :sink-thread (-> (tailer add-events! tail-exception! sink) start-daemon)
     :temp-dir temp-dir}))

(defn flow-processes-component []
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
  (:flow-proxy-url session))

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
