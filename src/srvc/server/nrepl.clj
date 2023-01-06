(ns srvc.server.nrepl
  (:require [cider.nrepl.middleware]
            [donut.system :as ds]
            [nrepl.server]
            [refactor-nrepl.middleware]))

(def nrepl-handler
  (apply nrepl.server/default-handler
         (conj cider.nrepl.middleware/cider-middleware
               #'refactor-nrepl.middleware/wrap-refactor)))

(defn nrepl-server-component [config]
  #::ds{:config config
        :start (fn [{::ds/keys [config instance]}]
                 (or instance
                     (when (:port config)
                       (nrepl.server/start-server
                        :bind "localhost"
                        :handler nrepl-handler
                        :port (:port config)))))
        :stop (fn [{::ds/keys [instance]}]
                (when instance
                  (nrepl.server/stop-server instance)
                  nil))})
