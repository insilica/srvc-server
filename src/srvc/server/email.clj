(ns srvc.server.email
  (:require [aws-api-failjure :as aaf]))

(defn invoke [client op-map]
  (aaf/throwing-invoke (:client client client) op-map))

(defn send-text-email [sesv2 from-address to-addresses subject body]
  (invoke
   sesv2
   {:op :SendEmail
    :request
    {:FromEmailAddress from-address
     :Destination {:ToAddresses to-addresses}
     :Content
     {:Simple
      {:Subject {:Data subject}
       :Body {:Text {:Data body}}}}}}))

(defn email-password-reset-code! [sesv2 to-address reset-code]
  (send-text-email
   sesv2
   "\"srvc.ai\" <no-reply@srvc.ai>"
   [to-address]
   "Password reset for srvc.ai account"
   (str
    "We have received an password reset request for "
    to-address ". To create a new password, please visit "
    "https://srvc.ai/password-reset-entry?code=" reset-code
    "\n\nThis link is only valid for 72 hours.\n\n"
    "If you did not request a password reset, please ignore this message.\n\n")))
