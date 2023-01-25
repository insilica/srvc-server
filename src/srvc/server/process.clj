(ns srvc.server.process
  (:require [babashka.process :as p]))

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
