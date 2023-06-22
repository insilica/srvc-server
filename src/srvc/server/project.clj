(ns srvc.server.project
  (:require [srvc.server.postgres.client :as pg]))

(defn get-project! [postgres username project-name & [select]]
  (-> postgres
      (pg/execute-one!
       {:select (or select :*)
        :from :project
        :join [:account [:= :project.account :account.id]]
        :where [:and
                [:= [:lower :project.name] [:lower project-name]]
                [:= [:lower :account.username] [:lower username]]]})))

(defn get-project-members! [postgres project-id & [select]]
  (-> postgres
      (pg/execute!
       {:select (or select :*)
        :from :account
        :where [:or
                [:= :id {:select :account :from :project :where [:= :id project-id]}]
                [:in :id {:select :account :from :project-account :where [:= :project project-id]}]]})))

(defn add-member! [postgres project-id account-id & {:keys [level]}]
  (-> postgres
      (pg/execute-one!
       {:insert-into :project-account
        :values [{:account account-id
                  :level (or level "member")
                  :project project-id}]
        :on-conflict []
        :do-nothing []})))
