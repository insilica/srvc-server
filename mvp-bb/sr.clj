#!/usr/bin/env bb

(load-file "hash.clj")

(ns sr
  (:require [babashka.curl :as curl]
            [babashka.fs :as fs]
            [babashka.process :as p]
            [clj-yaml.core :as yaml]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [insilica.canonical-json :as json]))

(def default-opts
  {:inherit true
   :shutdown p/destroy-tree})

(def default-sink-step {:run "sink/sink.clj"})
(def remote-sink-step {:run "sink/sink-remote.clj"})

(defn process [args & [opts]]
  (p/process args (merge default-opts opts)))

(defn make-fifo [path]
  @(p/process ["mkfifo" (str path)])
  path)

;; TODO #3 include shouldn't be a special default label. 
(def default-include
  {:id "sr_include"
   :type "boolean"
   :inclusion-values [true]
   :question "Include?"
   :required true})

;; TODO #4 'type' should be wrapped into json-schema.
(defn canonical-label [label]
  (-> label
      (update :id str/lower-case)
      (update :required boolean)
      (update :type str/lower-case))) 

(defn parse-labels [labels]
  (let [labels (mapv canonical-label labels)]
    (if (some #(= "sr_include" (:id %)) labels)
      labels
      (into [default-include] labels))))

(defn get-config [filename]
  (-> filename slurp yaml/parse-string
      (update :labels parse-labels)))

(defn usage []
  (println "Usage: sr review flow-id"))

(defn write-step-config [config dir step]
  (let [config-json (str (fs/path dir (str (random-uuid) ".json")))]
    (-> config
        (assoc :current_step step)
        json/write-str
        (->> (spit config-json)))
    config-json))

(defn remote-target? [s]
  (let [lc (str/lower-case s)]
    (or (str/starts-with? lc "http://")
        (str/starts-with? lc "https://"))))

(defn api-route [target & path-parts]
  (str target (when-not (str/ends-with? target "/") "/")
       "api/v1/" (str/join "/" path-parts)))

(defn make-remote-in-file [in-source dir]
  (let [in-file (-> (fs/path dir (str (random-uuid) ".fifo")) make-fifo str)
        server-hashes (->> (curl/get (api-route in-source "hashes") {:as :stream})
                           :body io/reader line-seq
                           (map json/read-str))]
    (future
      (with-open [writer (io/writer in-file)]
        (doseq [hash server-hashes]
          (->> (curl/get (api-route in-source "hash" hash))
               :body
               (.write writer))
          (.write writer "\n")
          (.flush writer))))
    in-file))

(defn push-to-target [in-source out-file]
  (when-not (or (remote-target? out-file) (fs/exists? out-file))
    (-> out-file fs/absolutize fs/parent fs/create-dirs)
    (fs/create-file out-file))
  (fs/with-temp-dir [dir {:prefix "srvc"}]
    (let [config (-> (get-config "sr.yaml")
                     (assoc :db out-file))
          {:keys [run] :as step} (if (remote-target? out-file)
                                   remote-sink-step
                                   default-sink-step)
          config-json (write-step-config config dir step)
          in-file (if (remote-target? in-source)
                    (make-remote-in-file in-source dir)
                    in-source)]
      @(process ["bb" run config-json in-file]))))

(defn pull [target]
  (let [{:keys [db]} (get-config "sr.yaml")]
    (push-to-target target db)))

(defn push [target]
  (let [{:keys [db]} (get-config "sr.yaml")]
    (push-to-target db target)))

(defn sync [target]
  (pull target)
  (push target))

(defn review [flow-name]
  (fs/with-temp-dir [dir {:prefix "srvc"}]
    (let [config (get-config "sr.yaml")
          {:keys [steps]} (get-in config [:flows (keyword flow-name)])
          steps (concat steps [default-sink-step])]
      (loop [[{:keys [run] :as step} & more] steps
             in-file nil]
        (let [config-json (write-step-config config dir step)]
          (if more
            (let [out-file (-> (fs/path dir (str (random-uuid) ".fifo")) make-fifo str)]
              (process ["bb" run config-json out-file in-file])
              (recur more out-file))
            @(process ["bb" run config-json in-file])))))))

(let [[command & args] *command-line-args*
      command (some-> command str/lower-case)]
  (case command
    nil (usage)
    "review" (apply review args)
    "pull" (apply pull args)
    "push" (apply push args)
    "sync" (apply sync args)
    (do (println "Unknown command" (pr-str command))
        (System/exit 1))))

nil
