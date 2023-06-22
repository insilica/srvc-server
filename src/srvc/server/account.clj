(ns srvc.server.account
  (:require [buddy.hashers :as hashers]
            [clojure.string :as str]
            [crypto.random :as random]
            [lambdaisland.regal :as regal]
            [next.jdbc :as jdbc]
            [srvc.server.postgres.client :as pg])
  (:import [java.util Base64]))

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

(defn random-secret-key []
  (String. (.encode (Base64/getEncoder) (random/bytes 33))))

(defn create-account! [postgres account]
  (jdbc/with-transaction [tx postgres]
    (let [{:account/keys [id]}
          (-> tx
              (pg/execute-one!
               {:insert-into :account
                :values [account]
                :returning [:id]}))
          {api-key-id :api-key/id}
          (-> tx
              (pg/execute-one!
               {:insert-into :api-key
                :values [{:account id
                          :secret-key (random-secret-key)}]
                :returning [:id]}))]
      (-> tx
          (pg/execute-one!
           {:insert-into :api-key-scope
            :values [{:api-key api-key-id
                      :level "write"
                      :scope "root"}]}))
      id)))

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

(defn get-root-key! [postgres account-id]
  (-> postgres
      (pg/execute-one!
       {:select [:secret-key]
        :from :api-key
        :join [:api-key-scope [:= :api-key.id :api-key-scope.api-key]]
        :where [:and
                [:= :account account-id]
                [:= :level "write"]
                [:= :scope "root"]]})
      :api-key/secret-key))
