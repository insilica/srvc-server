## srvc-server

### Usage

`nix run`

You will need a srvc-server-config.edn (see below). Subdirectories with sr.yaml
files will be treated as srvc projects.

### srvc-server-config.edn

This is a working dev config. The passwords are user1pass and user2pass.

```edn
{:host "http://localhost:8090"
 :local-auth
 {:users
  [{:email "user1@example.com"
    :password "bcrypt+sha512$0b7cc3702249eb0af73b596d330c752a$12$bf5d3529c7c7659ce437f8fb3297de7f4f009611a0cfdda0"}
   {:email "user2@example.com"
    :password "bcrypt+sha512$60e6e89515e750e770ea9816c9d45234$12$1addd7512e6de4269a30be59fdb46e1dc7ac9c3eb4db15d1"}]}
 :port 8090
 :proxy
 {:host "localhost"
  :listen-ports [9500 9501 9502 9503 9504 9505 9506 9507 9508 9509]}
 :saml
 {:idp-url "http://localhost:8080/simplesaml/saml2/idp/SSOService.php"}
 :session
 {:secret-key "gM/glaKg0C0ubnVGiNRpbw=="}}
```

New password hashes can be generated with `(buddy.hashers/derive "password")`

A new session secret key can be generated with `(String. (.encode (Base64/getEncoder) ((requiring-resolve 'crypto.random/bytes) 16)))`
