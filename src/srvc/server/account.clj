(ns srvc.server.account
  (:require [buddy.hashers :as hashers]
            [clojure.string :as str]
            [lambdaisland.regal :as regal]
            [srvc.server.postgres.client :as pg]))

(def re-email #"^[a-zA-Z0-9_.+-]+@[a-zA-Z0-9-]+\.[a-zA-Z0-9-.]+$")

(def regal-username
  [:cat
   :start
   [:* [:cat [:+ [:class [\A \Z] [\a \z] [\0 \9]]] \-]]
   [:+ [:class [\A \Z] [\a \z] [\0 \9]]]
   :end])

(def re-username (regal/regex regal-username))

(defn get-account! [postgres id-username-or-email & [select]]
  (let [[k v] (cond
                (number? id-username-or-email) [:id id-username-or-email]
                (str/includes? id-username-or-email "@") [[:lower :email] (str/lower-case id-username-or-email)]
                (string? id-username-or-email) [[:lower :username] (str/lower-case id-username-or-email)])]
    (-> postgres
        (pg/execute-one!
         {:select (or select :*)
          :from :account
          :where [:= k v]}))))

(defn create-account! [postgres account]
  (-> postgres
      (pg/execute-one!
       {:insert-into :account
        :values [account]
        :returning [:id]})))

(defn set-password! [postgres account-id password]
  (-> postgres
      (pg/execute-one!
       {:update :account
        :set {:buddy-hash (hashers/derive password)
              :password-reset-code nil
              :password-reset-code-expires nil}
        :where [:= :id account-id]})))

(defn create-password-reset-code! [postgres account-id]
  (let [code (str (random-uuid))]
    (-> postgres
        (pg/execute-one!
         {:update :account
          :set {:password-reset-code code
                :password-reset-code-expires [:+ [:now] [:interval "3 days"]]}
          :where [:= :id account-id]}))
    code))

(defn get-account-for-reset-code! [postgres code & [select]]
  (-> postgres
      (pg/execute-one!
       {:select (or select :*)
        :from :account
        :where [:and
                [:= :password-reset-code (str/lower-case code)]
                [:> :password-reset-code-expires [:now]]]})))
