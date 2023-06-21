(ns srvc.server.postgres.client
  (:require [babashka.fs :as fs]
            [clojure.data.json :as json]
            [donut.system :as-alias ds]
            [hikari-cp.core :as hikari-cp]
            [honey.sql :as sql]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as result-set])
  (:import (org.flywaydb.core Flyway)
           (org.flywaydb.core.api.configuration FluentConfiguration)
           (org.postgresql.util PGobject PSQLException)))

(defn flyway-migrate! [datasource {:keys [flyway-locations]}]
  (-> (Flyway/configure)
      .loadDefaultConfigurationFiles
      ^FluentConfiguration
      (.locations ^"[Ljava.lang.String;" (into-array String flyway-locations))
      (.dataSource datasource)
      .load
      .migrate))

(defn get-jdbc-url [{:keys [dbname host jdbc-url]}]
  (or jdbc-url
      (str "jdbc:postgresql:///" dbname (when host (str "?host=" host)))))

(defn get-datasource [config]
  (-> config
      (assoc :jdbcUrl (get-jdbc-url config))
      jdbc/get-datasource))

(defn make-datasource
  "Creates a Postgres db pool object to use with JDBC."
  [{:as config :keys [dbname socketFactory socketFactoryArg password user]}]
  (-> {:adapter "postgresql"
       :database-name dbname
       :jdbc-url (get-jdbc-url config)
       :maximum-pool-size 10
       :minimum-idle 4
       :password password
       :socket-factory socketFactory
       :socket-factory-arg socketFactoryArg
       :username user}
      hikari-cp/make-datasource))

(defn create-db-if-not-exists! [{:keys [template-dbname] :as config}]
  (let [ds (get-datasource (assoc config :dbname "postgres"))]
    (try
      (jdbc/execute! ds [(str "CREATE DATABASE " (:dbname config)
                              (some->> template-dbname (str " TEMPLATE ")))])
      (catch PSQLException e
        (when-not (re-find #"database .* already exists" (.getMessage e))
          (throw e))))))

(defn create-template-db-if-not-exists! [{:keys [template-dbname] :as config}]
  (try
    (jdbc/execute! (get-datasource (assoc config :dbname "postgres"))
                   [(str "CREATE DATABASE " template-dbname)])
    (catch PSQLException e
      (when-not (re-find #"database .* already exists" (.getMessage e))
        (throw e))))
  (-> (assoc config :dbname template-dbname)
      get-datasource
      (flyway-migrate! config)))

(defn start! [{::ds/keys [instance]
               {:as config :keys [server-dir template-dbname user]} ::ds/config}]
  (if (:datasource instance)
    instance
    (let [config (merge {:dbtype "postgresql"
                         :socketFactoryArg (when server-dir
                                             (str (fs/path server-dir "socket" ".s.PGSQL.5432")))
                         :username user}
                        config)]
      (when (:create-if-not-exists? config)
        (when template-dbname
          (create-template-db-if-not-exists! config))
        (create-db-if-not-exists! config))
      (let [datasource (-> config
                           (assoc :jdbc-url (get-jdbc-url config))
                           make-datasource)]
        (when-not template-dbname
          (flyway-migrate! config datasource))
        (assoc instance :datasource datasource)))))

(defn stop! [{{:as instance :keys [datasource]} ::ds/instance}]
  (if datasource
    (do
      (hikari-cp/close-datasource datasource)
      (dissoc instance :datasource))
    instance))

(defn component [config]
  {::ds/config config
   ::ds/start start!
   ::ds/stop stop!})

(comment
  (def instance (start! {::ds/config {:create-if-not-exists? true
                                      :dbname "srvc"
                                      :flyway-locations ["classpath:/sql"]
                                      :host "localhost"
                                      :port :none
                                      :server-dir "/tmp/pgtst"
                                      :socketFactory "org.newsclub.net.unix.AFUNIXSocketFactory$FactoryArg"
                                      :template-dbname "srvc_template"}})))

(doseq [op [(keyword "@@") ;; Register postgres text search operator
            ;; Register JSON operators
            (keyword "->") (keyword "->>") (keyword "#>") (keyword "#>>")]]
  (sql/register-op! op))

(def jdbc-config
  {:builder-fn result-set/as-kebab-maps})

(defn jsonb-pgobject
  "Returns a jsonb-type `org.postgresql.util.PGobject` representing `x`.

  `x` will be JSON-encoded by `sysrev.json.interface/write-str`."
  [x]
  (doto (PGobject.)
    (.setType "jsonb")
    (.setValue (json/write-str x))))

(defn serialization-error?
  "Returns true if e is caused by a postgres serialization error.
   See https://wiki.postgresql.org/wiki/SSI

   The transaction can often be successfully retried when these errors occur."
  [^Throwable e]
  (and (or (instance? PSQLException e)
           (some->> e ex-cause (instance? PSQLException)))
       (->> e ex-message (re-find #"could not serialize access") boolean)))

(defmacro wrap-ex-info [sqlmap & body]
  `(try
     ~@body
     (catch PSQLException e#
       (let [sqlmap# ~sqlmap]
         (throw (ex-info (str "Error during SQL execution: " (.getMessage e#))
                         {:sqlmap sqlmap# :sqlstr (sql/format sqlmap#)}
                         e#))))))

(defn execute!
  "General SQL execution function that returns a fully-realized result set.

  Builds a query with (`honey.sql/format` sqlmap) and executes it with
  `next.jdbc/execute!`."
  [connectable sqlmap]
  (wrap-ex-info
   sqlmap
   (jdbc/execute! (:datasource connectable connectable) (sql/format sqlmap) jdbc-config)))

(defn execute-one!
  "General SQL execution function that returns just the first row of a result.

  Builds a query with (`honey.sql/format` sqlmap) and executes it with
  `next.jdbc/execute-one!`."
  [connectable sqlmap]
  (wrap-ex-info
   sqlmap
   (jdbc/execute-one! (:datasource connectable connectable) (sql/format sqlmap) jdbc-config)))

(defn plan
  "General SQL execution function (for working with result sets).

  Returns a reducible that, when reduced, runs the SQL and yields the result.

  Builds a query with (`honey.sql/format` sqlmap) and passes it to
  `next.jdbc/plan`."
  [connectable sqlmap]
  (wrap-ex-info
   sqlmap
   (jdbc/plan (:datasource connectable connectable) (sql/format sqlmap) jdbc-config)))
