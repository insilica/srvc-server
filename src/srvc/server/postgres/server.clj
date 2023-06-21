(ns srvc.server.postgres.server
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [donut.system :as-alias ds]
            [next.jdbc :as jdbc]
            [srvc.server.process :as process]))

(defn postgresql-conf [{:keys [dir]}]
  (format
   "max_connections = 100
    listen_addresses = ''
    unix_socket_directories = '%s'
    log_destination = 'stderr'
    logging_collector = on
    log_directory = 'pg_log'
    log_filename = 'postgresql.log'"
   (str (fs/path dir "socket"))))

(defn init-db! [{:as config :keys [dir]}]
  (if (fs/exists? (fs/path dir "data"))
    (log/debug "Postgres db in " dir " already exists. Skipping initialization")
    (do
      (log/info "Initializing postgres db in " dir)
      (fs/create-dirs (fs/path dir "socket"))
      (try
        (let [{:keys [err exit out] :as init}
              @(process/process
                {:dir dir :err :string :out :string}
                "initdb" "-D" "./data")]
          (when-not (str/blank? out)
            (log/debug "initdb output: " out))
          (when-not (str/blank? err)
            (log/debug "initdb errors: " err))
          (when-not (zero? exit)
            (throw
             (ex-info
              (str "Unexpected error code " exit " from initdb: " (pr-str err))
              {:process init})))
          (spit
           (fs/file (fs/path dir "data" "postgresql.conf"))
           (postgresql-conf config)))
        (catch Exception e
          (fs/delete-tree (fs/path dir "data"))
          (throw e))))))

(defn run-postgres! [{:keys [dir]}]
  ;; Hide ""Future log output will appear in directory "pg_log""" message on stderr
  (process/process
   {:dir dir :err :none :out :none}
   "postgres" "-D" "./data"))

(defn wait-on-server-ready! [{:as config :keys [dir]}]
  (log/info "Waiting on postgres server")
  (loop [n 20]
    (let [config (assoc config
                        :jdbcUrl (str "jdbc:postgresql:///postgres?host=localhost")
                        :socketFactory "org.newsclub.net.unix.AFUNIXSocketFactory$FactoryArg"
                        :socketFactoryArg (str (fs/path dir "socket" ".s.PGSQL.5432")))
          x (try
              (-> (jdbc/get-datasource config)
                  (jdbc/execute! ["SHOW work_mem"]))
              (catch Exception e
                (if (zero? n)
                  (throw e)
                  :recur)))]
      ;; Can't recur from catch block, so use a special value
      (when (= :recur x)
        (Thread/sleep 500)
        (recur (dec n)))))
  (log/info "Postgres server ready"))

(defn start! [{::ds/keys [instance]
               {:as config :keys [dir init? tmp-dir?]} ::ds/config}]
  (if (:server instance)
    instance
    (let [dir (or dir
                  (when tmp-dir?
                    (str (fs/create-temp-dir {:prefix "srvc-postgres-"}))))
          config (assoc config :dir dir)]
      (when init?
        (if (fs/exists? (fs/path dir "data"))
          (log/debug "Postgres db in " dir " already exists. Skipping initialization")
          (init-db! config)))
      (let [server (run-postgres! config)]
        (wait-on-server-ready! config)
        (assoc instance :dir dir :server server)))))

(defn stop! [{{:as instance :keys [dir server]} ::ds/instance
              {:keys [tmp-dir?]} ::ds/config}]
  (if server
    (do
      (log/info "Stopping postgres server")
      @(process/process
        {:dir dir :err :stream :out :stream}
        "pg_ctl" "-D" "./data" "stop")
      (p/destroy-tree server)
      (when tmp-dir?
        (log/info "Deleting temp dir")
        (fs/delete-tree dir))
      (dissoc instance :server))
    instance))

(defn component [config]
  {::ds/config config
   ::ds/start start!
   ::ds/stop stop!})

(comment
  (def dir "/tmp/pgtst")
  (fs/delete-tree (fs/path dir "data"))
  (def instance (start! {::ds/config {:dir dir :init? true}}))
  (stop! {::ds/instance instance}))
