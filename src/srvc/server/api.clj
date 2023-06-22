(ns srvc.server.api
  (:refer-clojure :exclude [hash])
  (:require [babashka.fs :as fs]
            [clj-yaml.core :as yaml]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [muuntaja.middleware :as mw]
            [reitit.core :as re]
            [srvc.server.account :as acct]
            [srvc.server.postgres.client :as pg]
            [srvc.server.process :as process])
  (:import [java.io OutputStreamWriter PipedInputStream PipedOutputStream]))

(defn err [message]
  {:body {:error message}})

(def not-found
  (-> (err "not-found")
      (assoc :status 404)))

(def success
  {:body {:success true}})

(defn get-projects [{:keys [config]}]
  (let [{:keys [postgres]} config
        projects (-> postgres
                     (pg/execute!
                      {:select [:name :username]
                       :from :project
                       :join [:account [:= :account.id :project.account]]})
                     (->>
                      (map #(str (:account/username %) "/" (:project/name %)))
                      (sort-by str/lower-case)))]
    {:body {:projects projects}}))

(defn POST-project [{:keys [body-params config]}]
  (let [{:keys [postgres]} config
        {:keys [account-id name]} body-params
        {:account/keys [username]} (acct/get-account! postgres account-id)
        sr-yaml (fs/path username name "sr.yaml")]
    (when-not (fs/exists? sr-yaml)
      (fs/create-dirs (fs/path username name))
      (with-open [writer (io/writer (fs/file sr-yaml))]
        (yaml/generate-stream
         writer
         {:db "sink.db"}
         :dumper-options {:flow-style :block})))
    (-> postgres
        (pg/execute-one!
         {:insert-into :project
          :values [{:account account-id
                    :invite-code (str (random-uuid))
                    :name name}]}))
    success))

(defn realized-delay
  "Creates an already realized delay. Used when a value may be either a future
   or a delay. Allows `realized?` to return true when the reference is immediately
   available."
  [x]
  (let [id (delay x)]
    (force id)
    id))

(defn add-data [events {:keys [hash type] :as item}]
  (let [{:keys [by-hash raw] :as data} @events]
    (realized-delay
     (if (get by-hash hash)
       data
       (cond-> (assoc data
                      :by-hash (assoc (or by-hash {}) hash item)
                      :raw (conj (or raw []) item))

         (= "label-answer" type)
         (update-in [:doc-to-answers (-> item :data :document)]
                    (fnil conj []) item))))))

(defn load-data [project-dir source]
  (let [items (->> (process/process
                    {:dir project-dir
                     :err :inherit
                     :out :stream}
                    "sr" "pull" "--db" "-" source)
                   :out io/reader line-seq
                   (keep #(when-not (str/blank? %)
                            (json/read-str % :key-fn keyword))))]
    (reduce add-data (delay {}) items)))

(defn sink-process [project-dir]
  (let [out (PipedOutputStream.)]
    (-> (process/process
         {:dir project-dir
          :err :inherit
          :in (PipedInputStream. out)
          :out :inherit}
         "sr" "pull" "-")
        (assoc :writer (OutputStreamWriter. out)))))

(defn git-origin [project-dir]
  (let [{:keys [exit out]} (try
                             @(process/process
                               {:dir project-dir
                                :err :string
                                :out :string}
                               "git" "remote" "get-url" "origin")
                             (catch java.io.IOException _))]
    (when (some-> exit zero?)
      (str/trim out))))

(defn get-full-config [project-dir]
  (let [config-file (fs/file (fs/path project-dir "sr.yaml"))]
    (when (fs/exists? config-file)
      (let [{:keys [exit out] :as p} @(process/process
                                       {:dir project-dir
                                        :err :string
                                        :out :string}
                                       "sr" "print-config")]
        (if (zero? exit)
          (json/read-str out :key-fn keyword)
          (throw (ex-info (str "sr exited with code " exit)
                          {:process p})))))))

(defn load-project [{{:keys [projects]} :config} username project-name]
  (or
   (get-in @projects [username project-name])
   (let [project-dir (fs/file (fs/path username project-name))
         config-file (fs/file (fs/path project-dir "sr.yaml"))]
     (when (fs/exists? config-file)
       (let [{:keys [db] :as config} (get-full-config project-dir)
             project {:config config
                      :config-file config-file
                      :events (future @(load-data project-dir db))
                      :git
                      {:remotes
                       {:origin (git-origin project-dir)}}
                      :sink-process (sink-process project-dir)}]
         (swap! projects assoc-in [username project-name] project)
         project)))))

(defn get-project [{:as request :keys [::re/match]}]
  (let [{:keys [project-name username]} (:path-params match)
        project (load-project request username project-name)]
    (if-not project
      not-found
      {:status 200
       :body (select-keys project [:config :git])})))

(defn add-events! [{:as request :keys [projects]} username project-name events]
  (let [{:keys [sink-process] :as project} (load-project request username project-name)
        {:keys [writer]} sink-process]
    (doseq [{:keys [hash] :as item} events]
      (when-not (get-in project [:events :by-hash hash])
        (json/write item writer)
        (.write writer "\n")
        (.flush writer)
        (swap! projects update-in [name :events] add-data item)))))

(defn get-documents [request]
  (let [{:keys [project-name username]} (-> request ::re/match :path-params)
        {:keys [events] :as project} (load-project request username project-name)]
    (if-not project
      not-found
      {:status 200
       :body (->> @events :raw
                  (filter (comp #{"document"} :type)))})))

(defn get-recent-events [request]
  (let [{:keys [project-name username]} (-> request ::re/match :path-params)
        {:keys [events] :as project} (load-project request username project-name)
        recent-events (some->> events deref :raw rseq (take 100))]
    (if-not project
      not-found
      {:status 200
       :body (vec recent-events)})))

(defn hash [request]
  (let [{:keys [id project-name username]} (-> request ::re/match :path-params)
        {:keys [events]} (load-project request username project-name)
        event (get (:by-hash @events) id)]
    (if event
      {:body event}
      not-found)))

(defn doc-answers [request]
  (let [{:keys [id project-name username]} (-> request ::re/match :path-params)
        {:keys [events]} (load-project request username project-name)
        event (get (:by-hash @events) id)]
    (when event
      {:body (-> @events :doc-to-answers (get id))})))

(defn upload [request]
  (let [{:keys [project-name username]} (-> request ::re/match :path-params)]
    (some->> request :body-params
             (add-events! request username project-name)))
  success)

(defn routes [{:as config}]
  (let [;; Allow hot-reloading in dev when handler is a var.
        ;; reitit does not natively understand vars.
        h (fn [handler]
            (fn [request]
              (-> request
                  (assoc :config config)
                  handler)))]
    ["/api/v1" {:middleware [mw/wrap-format]}
     ["/project" {:get (h #'get-projects)
                  :post (h #'POST-project)}]
     ["/project/:username/:project-name"
      ["" {:get (h #'get-project)}]
      ["/document" {:get (h #'get-documents)}]
      ["/document/:id/label-answers" {:get (h #'doc-answers)}]
      ["/hash/:id" {:get (h #'hash)}]
      ["/recent-events" {:get (h #'get-recent-events)}]
      ["/upload" {:post (h #'upload)}]]]))
