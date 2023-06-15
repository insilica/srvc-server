(ns srvc.server.api
  (:refer-clojure :exclude [hash])
  (:require [babashka.fs :as fs]
            [clj-yaml.core :as yaml]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [muuntaja.middleware :as mw]
            [org.httpkit.server :as server]
            [reitit.core :as re]
            [srvc.server.process :as process])
  (:import [java.io OutputStreamWriter PipedInputStream PipedOutputStream]))

(defn err [message]
  {:body {:error message}})

(def not-found
  (-> (err "not-found")
      (assoc :status 404)))

(def success
  {:body {:success true}})

(defn get-projects [_request]
  (let [projects-dir (fs/real-path (fs/path "."))
        projects (->> (fs/list-dir projects-dir)
                      (keep #(when (fs/exists? (fs/path % "sr.yaml"))
                               (str (fs/relativize projects-dir %))))
                      (sort-by str/lower-case))]
    {:body {:projects projects}}))

(defn POST-project [{:keys [body-params]}]
  (let [{:keys [name]} body-params
        sr-yaml (fs/path name "sr.yaml")]
    (if (fs/exists? sr-yaml)
      (err "already-exists")
      (do
        (fs/create-dirs (fs/path name))
        (with-open [writer (io/writer (fs/file sr-yaml))]
          (yaml/generate-stream
           writer
           {:db "sink.db"}
           :dumper-options {:flow-style :block}))
        success))))

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

(defn load-data [project-name source]
  (let [items (->> (process/process
                    {:dir project-name
                     :err :inherit
                     :out :stream}
                    "sr" "pull" "--db" "-" source)
                   :out io/reader line-seq
                   (keep #(when-not (str/blank? %)
                            (json/read-str % :key-fn keyword))))]
    (reduce add-data (delay {}) items)))

(defn sink-process [project-name]
  (let [out (PipedOutputStream.)]
    (-> (process/process
         {:dir project-name
          :err :inherit
          :in (PipedInputStream. out)
          :out :inherit}
         "sr" "pull" "-")
        (assoc :writer (OutputStreamWriter. out)))))

(defn git-origin [project-name]
  (let [{:keys [exit out]} (try
                             @(process/process
                               {:dir project-name
                                :err :string
                                :out :string}
                               "git" "remote" "get-url" "origin")
                             (catch java.io.IOException _))]
    (when (some-> exit zero?)
      (str/trim out))))

(defn get-full-config [project-name]
  (let [config-file (fs/path project-name "sr.yaml")]
    (when (fs/exists? config-file)
      (let [{:keys [exit out] :as p} @(process/process
                                       {:dir project-name
                                        :err :string
                                        :out :string}
                                       "sr" "print-config")]
        (if (zero? exit)
          (json/read-str out :key-fn keyword)
          (throw (ex-info (str "sr exited with code " exit)
                          {:process p})))))))

(defn load-project [projects name]
  (or
   (get @projects name)
   (let [config-file (fs/path name "sr.yaml")]
     (when (fs/exists? config-file)
       (let [{:keys [db] :as config} (get-full-config name)
             project {:config config
                      :config-file config-file
                      :events (future @(load-data name db))
                      :git
                      {:remotes
                       {:origin (git-origin name)}}
                      :sink-process (sink-process name)}]
         (swap! projects assoc name project)
         project)))))

(defn get-project [request projects]
  (let [{:keys [project-name]} (-> request ::re/match :path-params)
        project (load-project projects project-name)]
    (if-not project
      not-found
      {:status 200
       :body (select-keys project [:config :git])})))

(defn add-events! [projects name events]
  (let [{:keys [sink-process] :as project} (load-project projects name)
        {:keys [writer]} sink-process]
    (doseq [{:keys [hash] :as item} events]
      (when-not (get-in project [:events :by-hash hash])
        (json/write item writer)
        (.write writer "\n")
        (.flush writer)
        (swap! projects update-in [name :events] add-data item)))))

(defn get-documents [request projects]
  (let [{:keys [project-name]} (-> request ::re/match :path-params)
        {:keys [events] :as project} (load-project projects project-name)]
    (if-not project
      not-found
      {:status 200
       :body (->> @events :raw
                  (filter (comp #{"document"} :type)))})))

(defn get-recent-events [request projects]
  (let [{:keys [project-name]} (-> request ::re/match :path-params)
        {:keys [events] :as project} (load-project projects project-name)
        recent-events (some->> events deref :raw rseq (take 100))]
    (if-not project
      not-found
      {:status 200
       :body (vec recent-events)})))

(defn hash [request projects]
  (let [{:keys [id project-name]} (-> request ::re/match :path-params)
        {:keys [events]} (load-project projects project-name)
        event (get (:by-hash @events) id)]
    (if event
      {:body event}
      not-found)))

(defn hashes [request dtm]
  (server/as-channel
   request
   {:on-open (fn [ch]
               (server/send! ch {:status 200} false)
               (doseq [{:keys [hash]} (:raw @dtm)]
                 (server/send! ch (str (json/write-str hash) "\n") false))
               (server/close ch))}))

(defn doc-answers [request projects]
  (let [{:keys [id project-name]} (-> request ::re/match :path-params)
        {:keys [events]} (load-project projects project-name)
        event (get (:by-hash @events) id)]
    (when event
      {:body (-> @events :doc-to-answers (get id))})))

(defn upload [request projects]
  (let [{:keys [project-name]} (-> request ::re/match :path-params)]
    (some->> request :body-params
             (add-events! projects project-name)))
  success)

(defn routes [{:keys [projects]}]
  (let [;; Allow hot-reloading in dev when handler is a var.
        ;; reitit does not natively understand vars.
        h (fn [handler] (fn [request] (handler request)))]
    ["/api/v1" {:middleware [mw/wrap-format]}
     ["/project" {:get (h #'get-projects)
                  :post (h #'POST-project)}]
     ["/project/:project-name"
      ["" {:get #(get-project % projects)}]
      ["/document" {:get #(get-documents % projects)}]
      ["/document/:id/label-answers" {:get #(doc-answers % projects)}]
      ["/hash/:id" {:get #(hash % projects)}]
      ["/hashes" {:get #(hashes % projects)}]
      ["/recent-events" {:get #(get-recent-events % projects)}]
      ["/upload" {:post #(upload % projects)}]]]))
