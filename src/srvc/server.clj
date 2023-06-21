(ns srvc.server
  (:gen-class)
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [donut.system :as ds]
            [hato.client :as hc]
            [reitit.ring :as rr]
            [ring.adapter.jetty :as jetty]
            [ring.middleware.session :as session]
            [ring.middleware.session.cookie :as cookie]
            [srvc.server.api :as api]
            [srvc.server.html :as html]
            [srvc.server.nrepl :as nrepl]
            [srvc.server.flow :as flow]
            [srvc.server.postgres.client :as pg]
            [srvc.server.postgres.server :as pgs]
            [srvc.server.saml :as saml])
  (:import [java.util Base64]))

(defn routes [config]
  [(html/routes config)
   (api/routes config)
   (saml/routes "http://localhost:8090")])

(defn default-handler []
  (rr/create-resource-handler
   {:not-found-handler
    (rr/create-default-handler
     {:not-found html/not-found})
    :path "/"}))

(defonce state (atom nil))

(defn first-line [s]
  (some-> s not-empty (str/split #"\n") first))

(defn signal! [system signal-name]
  (let [{out ::ds/out :as system} (ds/signal system signal-name)
        {:keys [error validation]} out]
    (cond
      (seq error)
      (throw (ex-info
              (str "Error during " signal-name
                   (some->> error :services first val :message
                            first-line (str ": ")))
              out))

      (seq validation)
      (throw (ex-info
              (str "Validation failed during " signal-name
                   (some->> validation :services first val :message
                            first-line (str ": ")))
              out))

      :else system)))

(defn get-config [filename]
  (if-let [resource (or (io/resource filename) (io/file filename))]
    (with-open [reader (-> resource io/reader java.io.PushbackReader.)]
      (try
        (edn/read reader)
        (catch Exception e
          (throw
           (ex-info (str "Error parsing EDN in config file \"" filename
                         \" ": " (.getMessage e))
                    {:filename filename}
                    e)))))
    (throw
     (ex-info (str "Config file not found: \"" filename "\"")
              {:filename filename}))))

(defn config-component []
  #::ds{:start (fn [{::ds/keys [config]}]
                 (get-config (:filename config)))
        :stop (constantly nil)
        :config {:filename (ds/local-ref [:env :config-file])}})

(def default-hato-config
  {:connect-timeout 10000
   :redirect-policy :always})

(defn hato-component [config]
  #::ds{:config (merge default-hato-config config)
        :start (fn [{::ds/keys [config instance]}]
                 (if (:client instance)
                   instance
                   {:client (hc/build-http-client config)}))
        :stop (fn [{::ds/keys [instance]}]
                (if (:client instance)
                  (dissoc instance :client)
                  instance))})

(defn http-server-component [config]
  #::ds{:config config
        :start (fn [{:keys [::ds/config]}]
                 (let [{:keys [host port session-opts]} config
                       wrap-session (fn [handler]
                                      (session/wrap-session handler session-opts))
                       server (jetty/run-jetty
                               (-> (routes config)
                                   rr/router
                                   (rr/ring-handler
                                    (default-handler)
                                    {:middleware [wrap-session]}))
                               {:host host :join? false :port port})]
                   {:port (-> server .getURI .getPort)
                    :server server}))
        :stop (fn [{{:keys [server]} ::ds/instance}]
                (.stop server)
                nil)})

(defn projects-component []
  #::ds{:start (fn [_]
                 (atom {}))
        :stop (constantly nil)})

(defn session-opts-component [config]
  #::ds{:config config
        :start (fn [{::ds/keys [config]}]
                 (let [k (some->> config :secret-key (.decode (Base64/getDecoder)))]
                   {:cookie-attrs (:cookie-attrs config {:http-only true})
                    :cookie-name (:cookie-name config "ring-session")
                    :root (:root config "/")
                    :store (cookie/cookie-store {:key k})}))
        :stop (constantly nil)})

(defn merge-config [& configs]
  {::ds/config {:configs configs}
   ::ds/start (fn [{{:keys [configs]} ::ds/config}]
             (apply merge configs))
   ::ds/stop (constantly nil)})

(defn system [env]
  {::ds/defs
   {:srvc-server
    {:env {::ds/start (constantly env)}
     :config (config-component)
     :hato (hato-component (ds/local-ref [:config :hato]))
     :http-server (http-server-component
                   {:flow-processes (ds/local-ref [:flow-processes])
                    :hato (ds/local-ref [:hato])
                    :host (ds/local-ref [:config :host])
                    :port (ds/local-ref [:config :port])
                    :postgres (ds/local-ref [:postgres])
                    :projects (ds/local-ref [:projects])
                    :proxy-config (ds/local-ref [:config :proxy])
                    :saml (ds/local-ref [:config :saml])
                    :session-opts (ds/local-ref [:session-opts])})
     :nrepl-server (nrepl/nrepl-server-component
                    (ds/local-ref [:config :nrepl]))
     :postgres (pg/component (ds/local-ref [:postgres-config]))
     :postgres-config (merge-config
                       (ds/local-ref [:config :postgres])
                       {:server-dir (ds/local-ref [:postgres-server :dir])})
     :postgres-server (pgs/component (ds/local-ref [:config :postgres-server]))
     :projects (projects-component)
     :proxy-server (flow/proxy-server-component
                    {:listen-ports (ds/local-ref [:config :proxy :listen-ports])
                     :session-opts (ds/local-ref [:session-opts])})
     :flow-processes (flow/flow-processes-component)
     :session-opts (session-opts-component
                    (ds/local-ref [:config :session]))}}})

;; Not thread-safe. For use by -main and at REPL
(defn start! []
  (let [env {:config-file "srvc-server-config.edn"}]
    (swap! state #(signal! (or % (system env)) ::ds/start))))

;; Not thread-safe. For use by -main and at REPL
(defn stop! []
  (swap! state #(when % (signal! % ::ds/stop) nil)))

(defn -main []
  (start!)
  (Thread/sleep Long/MAX_VALUE))

(comment
  (do (stop!) (start!) nil)
  (do (stop!) (reset! state nil) (start!) nil)
  (-> @state ::ds/instances :srvc-server :postgres-config)

  ;; Generate a new session secret-key
  (String. (.encode (Base64/getEncoder) ((requiring-resolve 'crypto.random/bytes) 16)))
  )
