(ns srvc.server.html
  (:require [buddy.hashers :as hashers]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [hato.client :as hc]
            [lambdaisland.uri :as uri]
            [reitit.core :as re]
            [reitit.ring.middleware.parameters :refer [parameters-middleware]]
            [rum.core :as rum :refer [defc]]
            [srvc.server.account :as acct]
            [srvc.server.email :as email]
            [srvc.server.flow :as flow]
            [srvc.server.project :as proj]))

(def re-project-name
  #"[A-Za-z](?:[A-Za-z0-9]|-(?=[A-Za-z0-9])){0,38}")

(defn redirect-after-post [url]
  {:status 303
   :headers {"Location" url}})

(defn api-url [request path]
  (str (:api-base request "http://localhost:8090") "/api/v1" path))

(defn parse-json-body [{:keys [body] :as response}]
  (assoc response
         :body (some-> body io/reader (json/read :key-fn keyword))))

(defn json-get [request path]
  (let [{:keys [body status] :as response}
        (-> (hc/get
             (api-url request path)
             {:as :stream
              :http-client (-> request :config :hato :client)})
            parse-json-body)]
    (if (= 200 status)
      body
      (throw (ex-info "Unexpected response" {:response response})))))

(defn get-event [request project-name hash]
  (json-get request (str "/project/" project-name "/hash/" hash)))

(defn get-projects [request]
  (:projects (json-get request "/project")))

(defn project-GET [request username project-name & [path]]
  (-> (hc/get
       (api-url request (str "/project/" username "/" project-name path))
       {:as :stream
        :http-client (-> request :config :hato :client)})
      parse-json-body))

(defc head []
  [:head
   [:meta {:charset "UTF-8"}]
   [:meta {:name "viewport" :content "width=device-width, initial-scale=1.0"}]
   [:script {:src "/js/tailwind-3.1.3.min.js"}]
   [:script {:src "/js/htmx-1.7.0.min.js"}]])

(defc page [body]
  [:html
   (head)
   body])

(defn response [body]
  {:status 200
   :headers {"Content-Type" "text/html"}
   :body (list "<!doctype html>" (rum/render-static-markup (page body)))})

(defc body [{:keys [::re/match session] :as request} & content]
  (let [{:keys [project-name username]} (:path-params match)
        {:keys [email]} session
        project-url #(str "/p/" username "/" project-name %)]
    [:body {:class "bg-slate-100 dark:bg-slate-900 text-slate-900 dark:text-slate-100"}
     [:div {:class "flex h-screen"}
      [:div {:class "h-screen w-64 pl-4 pt-4 text-lg text-slate-100 bg-slate-900"}
       (-> (when project-name
             [[:a {:href (project-url "")} "Overview"]
              [:a {:href (project-url "/activity")} "Activity"]
              [:a {:href (project-url "/documents")} "Documents"]
              [:a {:href (project-url "/flow")} "Flows"]])
           (concat
            (if email
               [[:a {:href "/logout"} "Log Out (" email ")"]]
               [[:a {:href "/login"} "Log In"]
                [:a {:href "/register"} "Register"]]))
           (->> (map #(vector :li %))
                (into [:ul])))
       [:hr {:class "m-4"}]
       (let [projects (get-projects request)]
         (when (seq projects)
           [:div
            [:a {:href "/"} "Projects: " [:span {:class "font-bold"} "+"]]
            [:div
             (->> projects
                  (mapv #(do [:li [:a {:href (str "/p/" %)} %]]))
                  (into [:ul]))]]))]
      [:div {:class "flex-1 flex flex-col overflow-hidden pt-4 ml-4"}
       content]]]))

(defn not-found [request]
  {:status 404
   :headers {"Content-Type" "text/html"}
   :body (list "<!doctype html>"
               (rum/render-static-markup
                (page
                 (body
                  request
                  [:div [:h1 {:class "text-2xl text-bold text-gray-700 bg-gray-50 dark:bg-gray-700 dark:text-gray-400"}
                         "404 Not Found"]]))))})

(defn server-error [request]
  {:status 500
   :headers {"Content-Type" "text/html"}
   :body (list "<!doctype html>"
               (rum/render-static-markup
                (page
                 (body
                  request
                  [:div [:h1 {:class "text-2xl text-bold text-gray-700 bg-gray-50 dark:bg-gray-700 dark:text-gray-400"}
                         "500 Internal Server Error"]]))))})

(defc table-head [col-names]
  [:thead {:class "text-xs text-gray-700 uppercase bg-gray-50 dark:bg-gray-700 dark:text-gray-400"}
   [:tr
    (map #(vector :th {:scope "col" :class "px-6 py-3"} %) col-names)]])

(defc table-row [row]
  [:tr {:class "border-b dark:bg-gray-800 dark:border-gray-700 odd:bg-white even:bg-gray-50 odd:dark:bg-gray-800 even:dark:bg-gray-700"}
   [:th {:scope "row" :class "px-6 py-4 font-medium text-gray-900 dark:text-white whitespace-nowrap"}
    (first row)]
   (map #(vector :td {:class "px-6 py-4"} %) (rest row))])

(defc table-body [rows]
  [:tbody
   (map table-row rows)])

(defc table [col-names rows]
  [:div {:class "relative overflow-x-auto shadow-md sm:rounded-lg"}
   [:table {:class "w-full text-sm text-left text-gray-500 dark:text-gray-400"}
    (table-head col-names)
    (table-body rows)]])

(defn message-page [title message]
  (response
   (list
    [:script {:defer true
              :src "/js/alpine2.js"}]
    [:div {:class "container max-w-full mx-auto py-24 px-6"}
     [:div.font-sans
      [:div {:class "max-w-sm mx-auto px-6"}
       [:div {:class "relative flex flex-wrap"}
        [:div {:class "w-full relative"}
         [:div.mt-6
          [:div {:class "text-center font-semibold text-black"}
           title]
          [:div.mx-auto.max-w-lg.mt-3
           message]
          [:div.mx-auto.max-w-lg.mt-3
           [:a.text-blue-600 {:href "/"}
            "Return to srvc"]]]]]]]])))

(defn validate-project-name [request _form _field name]
  (let [name (some-> name str/trim)
        err #(do {:error %
                  :valid? false
                  :value name})]
    (cond
      (empty? name) (err "Required.")

      (not (re-matches re-project-name name))
      (err "Invalid project name.")

      (some (partial = name) (get-projects request))
      (err "There is already a project with that name.")

      :else
      {:valid? true
       :value name})))

(defn validate-form [request form]
  (let [{:keys [params]} request]
    (->> form :fields
         (map (fn [{:keys [id validate] :as field}]
                [id (validate request form field (get params id))]))
         (into {}))))

(defn validate-url [form field]
  (str "/hx/validate/" (:id form) "/" (:id field)))

(defc form-input [form {:keys [id label] :as field}
                  & [{:keys [error] :as validation}]]
  [:div {:hx-swap "outerHTML" :hx-target "this"}
   [:label {:for id} label]
   [:input {:class ["ml-4" "dark:text-slate-900"]
            :hx-post (validate-url form field)
            :id id
            :name id
            :value (if validation (:value validation) (:value field))}]
   (if error
     [:div {:class ["text-red-500"]}
      error]
     [:div {:style {:height "1em"}}])])

(defc form [request
            {:keys [extra-content fields] :as form}
            & [validations]]
  [:form {:method "post"}
   (map
    (fn [{:keys [id] :as field}]
      (form-input form field (get validations id))) fields)
   (extra-content request)])

(def create-project-form
  {:extra-content (fn [_request]
                    (list
                     [:br]
                     [:input {:type "submit" :value "Create"}]))
   :fields [{:id "project-name"
             :label "Project Name"
             :validate validate-project-name}]
   :id "create-project"})

(def forms
  (->> [create-project-form]
       (map (juxt :id identity))
       (into {})))

(defn create-project [{:as request :keys [config session]} name]
  (let [{:keys [account-id]} session
        {:keys [hato postgres]} config]
    (if-not account-id
      (message-page "Unauthenticated" "You must be logged in to create a project")
      (let [api-key (acct/get-root-key! postgres account-id)
            {:keys [status] :as response}
            (hc/post
             (api-url request "/project")
             {:as :stream
              :body (json/write-str {:account-id account-id :name name})
              :headers {"Accept" "application/json"
                        "Authorization" (str "Bearer " api-key)
                        "Content-Type" "application/json"}
              :http-client (:client hato)})]
        (if (and status (<= 200 status 299))
          (redirect-after-post "/")
          (throw (ex-info "Unexpected response" {:response response})))))))

(defn project-POST [request project-name path body]
  (let [{:keys [status] :as response}
        (hc/post
         (api-url request (str "/project/" project-name path))
         {:as :stream
          :body (json/write-str body)
          :headers {"Accept" "application/json"
                    "Content-Type" "application/json"}
          :http-client (-> request :config :hato :client)})]
    (when-not (and status (<= 200 status 299))
      (throw (ex-info "Unexpected response" {:response response})))))

(defn home [{:keys [form-params session] :as request}]
  (let [{:keys [account-id]} session
        projects (get-projects request)
        validations (when (seq form-params)
                      (validate-form request create-project-form))]
    (response
     (body
      request
      [:div
       (if account-id
         [:<>
          [:h2.text-lg.font-bold "Create Project"]
          (form request create-project-form validations)]
         [:h2.text-lg.font-bold
          [:a {:href "/login"} "Create Project"]])
       (when (seq projects)
         [:div.mt-4
          [:h2.text-lg.font-bold "Projects"]
          [:div
           (->> projects
                (mapv #(do [:li [:a {:href (str "/p/" %)} %]]))
                (into [:ul]))]])]))))

(defn POST-home [request]
  (let [validations (validate-form request create-project-form)]
    (if (every? :valid? (vals validations))
      (create-project request (-> validations (get "project-name") :value))
      (home request))))

(defn doc-title [{:keys [data hash type uri]}]
  (or (get-in data [:ProtocolSection :IdentificationModule :OfficialTitle])
      uri
      (str type " " hash)))

(defn documents [request]
  (let [{:keys [project-name username]} (-> request ::re/match :path-params)
        {:keys [status] documents :body} (project-GET request username project-name "/document")]
    (case status
      200 (response
           (body
            request
            (table ["Document" "Inclusion"]
                   (map (fn [doc]
                          [(doc-title doc) "Yes"])
                        documents))))
      404 (not-found request)
      (server-error request))))

(defc answer-table [request project-name event-hash reviewer]
  (let [answers (some->> (try (json-get request (str "/project/" project-name "/document/" event-hash "/label-answers"))
                          (catch java.io.EOFException _))
                     (filter #(-> % :data :reviewer (= reviewer))))]
    (when (seq answers)
      (table ["Label" "Answer"]
             (for [{{:keys [answer label]} :data} answers]
               [(-> (get-event request project-name label) :data :question)
                (if (string? answer) answer (pr-str answer))])))))

(defn user-display [user-uri]
  (some-> user-uri uri/uri (assoc :scheme nil) str))

(defn recent-event-seq [request]
  (let [{:keys [project-name username]} (-> request ::re/match :path-params)
        recent-events (json-get request (str "/project/" username "/" project-name "/recent-events"))]
    (distinct
     (for [{:keys [data type] :as event} recent-events]
       [(case type
          "document" (str "New document: " (doc-title event))
          "label" (str "New label: " (:question data))
          "label-answer" (let [{:keys [reviewer]
                                data-event :event} data]
                           [:div (str (user-display reviewer)
                                      " labeled "
                                      (->> data-event
                                           (get-event request project-name)
                                           doc-title))
                            (answer-table request project-name data-event reviewer)])
          (pr-str event))]))))

(defc activity-table [request]
  [:div#activity-table
   (table ["Event"]
          (take 10 (recent-event-seq request)))])

(defn activity [request]
  (let [{:keys [project-name username]} (-> request ::re/match :path-params)
        {:keys [status]} (project-GET request username project-name "/recent-events")]
    (case status
      200 (response
           (body
            request
            [:div {:hx-get (str "/hx/project/" username "/" project-name "/activity")
                   :hx-trigger "every 1s"}
             (activity-table request)]))
      404 (not-found request)
      (server-error request))))

(defn git-remote-link
  "Turns a URL like git@github.com:insilica/sfac.git into a URL
   viewable in the browser, like https://github.com/insilica/sfac
   Returns non-'git@' URLs as-is.

   Relies on convention. Won't be accurate for all git remotes."
  [git-remote]
  (if (str/starts-with? git-remote "git@")
    (-> git-remote
        (subs 4)
        (str/replace-first ":" "/")
        (->> (str "https://")))
    git-remote))

(defn get-project [{:as request :keys [config]}]
  (let [{:keys [postgres]} config
        {:keys [project-name username]} (-> request ::re/match :path-params)
        {:keys [status]
         {:keys [git]} :body}
        #__ (project-GET request username project-name)
        git-origin (-> git :remotes :origin)
        {:project/keys [id invite-code]} (proj/get-project! postgres username project-name [:project.id :invite-code])
        invite-link (str "/p/" username "/" project-name "/invite?code=" invite-code)]
    (case status
      200 (response
           (body
            request
            [:div
             [:h1.font-bold username " / " project-name]
             (when git-origin
               [:div.mt-3
                [:a {:href (git-remote-link git-origin)}
                 "Git repository: " git-origin]])
             [:div.mt-3
              [:h3.font-bold "Members"]
              (->> (proj/get-project-members! postgres id [:account.username])
                   (sort-by (comp str/lower-case :account/username))
                   (map #(do [:li (:account/username %)]))
                   (into [:ul]))
              [:div.mt-3
               "Project invite link: "
               [:a {:href invite-link} invite-link]]]]))
      404 (not-found request)
      (server-error request))))

(defn hx-response [hiccup]
  {:status 200
   :body (rum/render-static-markup hiccup)})

(defn hx-activity [request]
  (hx-response (activity-table request)))

(defn hx-validate-form-field [{:keys [::re/match params] :as request}]
  (let [{:keys [field-id form-id]} (:path-params match)
        {:keys [fields] :as form} (get forms form-id)
        {:keys [validate] :as field} (some #(when (= field-id (:id %)) %) fields)]
    (when (and field field-id)
      (hx-response
       (form-input
        form field
        (validate request form field (get params field-id)))))))

(defn handle-tail-line [request project-name http-port-promise line]
  (let [{:keys [data type] :as event} (json/read-str line :key-fn keyword)
        {:keys [http-port]} data]
    (if (= "control" type)
      (when http-port
        (deliver http-port-promise http-port))
      (project-POST request project-name "/upload" [event]))))

(defn load-flow-process
  [{:keys [session] :as request} flow-processes project-name project-config flow-name]
  (let [{:keys [email]} session
        k [project-name flow-name email]]
    (or
     (get @flow-processes k)
     (let [http-port-promise (promise)
           process (-> (flow/flow-process
                        project-name
                        project-config
                        flow-name
                        (partial handle-tail-line request project-name http-port-promise)
                        prn
                        (str "mailto:" (:email session)))
                       (assoc :http-port-promise http-port-promise))]
       (swap! flow-processes assoc k process)
       process))))

(defn get-flows [request]
  (let [{:keys [project-name username]} (-> request ::re/match :path-params)
        resp (project-GET request username project-name)]
    (case (:status resp)
      404 (not-found request)
      200 (response
           (body
            request
            [:div
             [:h2 "Flows:"]
             [:ul
              (map
               #(do [:li
                     [:a {:href (str "/p/" project-name "/flow/" %)}
                      %]])
               (->> resp :body :config :flows keys
                    (map name)
                    (sort-by str/lower-case)))]]))
      (server-error request))))

(defn get-flow [{:keys [config ::re/match scheme session] :as request}]
  (let [{:keys [flow-processes proxy-config]} config
        {:keys [flow-name project-name username]} (:path-params match)
        {:keys [status] :as resp} (project-GET request username project-name)
        flow (-> resp :body :config :flows (get (keyword flow-name)))]
    (cond
      (not (:account-id session)) {:status 302
                                   :headers {"Location" "/login"}}
      (or (= 404 status) (not flow)) (not-found request)
      (not= 200 status) (server-error request)
      :else
      (let [process (load-flow-process
                     request flow-processes
                     project-name (-> resp :body :config)
                     flow-name)
            proxy-url (str "http://localhost:" @(:http-port-promise process))]
        {:status 302
         :headers {"Location" (str (name scheme) "://" (:host proxy-config)
                                   ":" (first (:listen-ports proxy-config)))}
         :session (assoc session :flow-proxy-url proxy-url)}
        #_(-> (response
               (body
                request
                [:a {;:class ["w-full" "bg-white"]
                        ;:style {:height "100vh"}
                     :href (str (name scheme) "://" (:host proxy-config)
                                ":" (first (:listen-ports proxy-config)))
                     :target "_blank"}
                 "Open flow in new tab"]))
              (assoc :session (assoc session :flow-proxy-url proxy-url)))))))

(defn invite [{:keys [config ::re/match params session]}]
  (let [{:keys [postgres]} config
        {:keys [project-name username]} (:path-params match)
        {:strs [code]} params
        {:keys [account-id]} session
        {:project/keys [id invite-code]}
        (when (and account-id (seq code))
          (proj/get-project! postgres username project-name [:project.id :invite-code]))]
    (cond
      (not account-id) (message-page "Unauthorized" "Not logged in")
      (not (seq code)) (message-page "Error" "Blank code")
      (not= code invite-code) (message-page "Error" "Invalid or expired code")
      :else
      (do
        (proj/add-member! postgres id account-id)
        {:status 302
         :headers {"Location" (str "/p/" username "/" project-name)}}))))

(defn login [{:keys [params]} & [error]]
  ;; https://tailwindcomponents.com/component/login-showhide-password
  (response
   (list
    [:script {:defer true
              :src "/js/alpine2.js"}]
    [:div {:class "container max-w-full mx-auto py-24 px-6"}
     [:div.font-sans
      [:div {:class "max-w-sm mx-auto px-6"}
       [:div {:class "relative flex flex-wrap"}
        [:div {:class "w-full relative"}
         [:div.mt-6
          [:div {:class "text-center font-semibold text-black"}
           "Log in to srvc"]
          [:form.mt-8 {:method "post"}
           [:div.mx-auto.max-w-lg
            [:div.py-2
             [:label {:class "px-1 text-sm text-gray-600"
                      :for "username"}
              "Username or email address"]
             [:input {:class "text-md block px-3 py-2  rounded-lg w-full bg-white border-2 border-gray-300 placeholder-gray-600 shadow-md focus:placeholder-gray-500 focus:bg-white focus:border-gray-600 focus:outline-none"
                      :id "username"
                      :name "username"
                      :type "text"
                      :value (get params "username")}]]
            [:div.py-2 {:x-data "{ show: true }"}
             [:label {:class "px-1 text-sm text-gray-600"
                      :for "password"}
              "Password"]
             [:div.relative
              [:input {:class "text-md block px-3 py-2 rounded-lg w-full bg-white border-2 border-gray-300 placeholder-gray-600 shadow-md focus:placeholder-gray-500 focus:bg-white focus:border-gray-600 focus:outline-none"
                       :id "password"
                       :name "password"
                       :type "password"
                       ":type" "show ? 'password' : 'text'"}]
              [:div {:class "absolute inset-y-0 right-0 pr-3 flex items-center text-sm leading-5"}
               [:svg {:class "h-6 text-gray-700"
                      ":class" "{'hidden': !show, 'block':show }"
                      "@click" "show = !show"
                      :fill "none"
                      :viewbox "0 0 576 512"
                      :xmlns "http://www.w3.org/2000/svg"}
                [:path {:d "M572.52 241.4C518.29 135.59 410.93 64 288 64S57.68 135.64 3.48 241.41a32.35 32.35 0 0 0 0 29.19C57.71 376.41 165.07 448 288 448s230.32-71.64 284.52-177.41a32.35 32.35 0 0 0 0-29.19zM288 400a144 144 0 1 1 144-144 143.93 143.93 0 0 1-144 144zm0-240a95.31 95.31 0 0 0-25.31 3.79 47.85 47.85 0 0 1-66.9 66.9A95.78 95.78 0 1 0 288 160z"
                        :fill "currentColor"}]]
               [:svg {:class "h-6 text-gray-700"
                      ":class" "{'block': !show, 'hidden':show }"
                      "@click" "show = !show"
                      :fill "none"
                      :viewbox "0 0 640 512"
                      :xmlns "http://www.w3.org/2000/svg"}
                [:path {:d "M320 400c-75.85 0-137.25-58.71-142.9-133.11L72.2 185.82c-13.79 17.3-26.48 35.59-36.72 55.59a32.35 32.35 0 0 0 0 29.19C89.71 376.41 197.07 448 320 448c26.91 0 52.87-4 77.89-10.46L346 397.39a144.13 144.13 0 0 1-26 2.61zm313.82 58.1l-110.55-85.44a331.25 331.25 0 0 0 81.25-102.07 32.35 32.35 0 0 0 0-29.19C550.29 135.59 442.93 64 320 64a308.15 308.15 0 0 0-147.32 37.7L45.46 3.37A16 16 0 0 0 23 6.18L3.37 31.45A16 16 0 0 0 6.18 53.9l588.36 454.73a16 16 0 0 0 22.46-2.81l19.64-25.27a16 16 0 0 0-2.82-22.45zm-183.72-142l-39.3-30.38A94.75 94.75 0 0 0 416 256a94.76 94.76 0 0 0-121.31-92.21A47.65 47.65 0 0 1 304 192a46.64 46.64 0 0 1-1.54 10l-73.61-56.89A142.31 142.31 0 0 1 320 112a143.92 143.92 0 0 1 144 144c0 21.63-5.29 41.79-13.9 60.11z"
                        :fill "currentColor"}]]]]]
            [:div {:class "flex justify-between"}
             [:label {:class "block text-gray-500 font-bold my-4"}
              [:input {:checked (contains? params "rememberme")
                       :class "leading-loose text-pink-600"
                       :id "rememberme"
                       :name "rememberme"
                       :type "checkbox"}]
              [:span {:class "py-2 text-sm text-gray-600 leading-snug"}
               " Remember me"]]]
            [:div {:class "text-red-600 font-bold"}
             error]
            [:button {:class "mt-3 text-lg font-semibold  bg-gray-800 w-full text-white rounded-lg px-6 py-3 block shadow-xl hover:text-white hover:bg-black"}
             "Log in"]
            [:div {:class "mt-3 flex justify-between"}
             [:span {:class "py-2 text-sm text-blue-600 leading-snug"}
              [:a {:href "/register"}
               "Create a new account"]]]
            [:div {:class "mt-3 flex justify-between"}
             [:span {:class "py-2 text-sm text-blue-600 leading-snug"}
              [:a {:href "/password-reset"}
               "Forgot your username or password?"]]]]]]]]]]])))

(defn POST-login [{:keys [config params session] :as request}]
  (let [{:keys [postgres]} config
        {:strs [password username]} params
        username (some-> username str/trim str/lower-case)]
    (if-not (seq username)
      (login request)
      (let [{:account/keys [buddy-hash email id]}
            (acct/get-account! postgres username [:buddy-hash :email :id])]
        (cond
          (and buddy-hash (:valid (hashers/verify password buddy-hash)))
          {:status 302
           :headers {"Location" "/"}
           :session (assoc session :account-id id :email email)}
          :else
          (login request "Wrong username, email or password"))))))

(defn register [{:keys [params]} & [error]]
  ;; https://tailwindcomponents.com/component/login-showhide-password
  (response
   (list
    [:script {:defer true
              :src "/js/alpine2.js"}]
    [:div {:class "container max-w-full mx-auto py-24 px-6"}
     [:div.font-sans
      [:div {:class "max-w-sm mx-auto px-6"}
       [:div {:class "relative flex flex-wrap"}
        [:div {:class "w-full relative"}
         [:div.mt-6
          [:div {:class "text-center font-semibold text-black"}
           "Create an srvc account"]
          [:form.mt-8 {:method "post"}
           [:div.mx-auto.max-w-lg
            [:div.py-2
             [:label {:class "px-1 text-sm text-gray-600"
                      :for "username"}
              "Username"]
             [:input {:class "text-md block px-3 py-2 rounded-lg w-full bg-white border-2 border-gray-300 placeholder-gray-600 shadow-md focus:placeholder-gray-500 focus:bg-white focus:border-gray-600 focus:outline-none"
                      :id "username"
                      :name "username"
                      :type "text"
                      :value (get params "username")}]]
            [:div.py-2
             [:label {:class "px-1 text-sm text-gray-600"
                      :for "email"}
              "Email address"]
             [:input {:class "text-md block px-3 py-2 rounded-lg w-full bg-white border-2 border-gray-300 placeholder-gray-600 shadow-md focus:placeholder-gray-500 focus:bg-white focus:border-gray-600 focus:outline-none"
                      :id "email"
                      :name "email"
                      :type "text"
                      :value (get params "email")}]]
            [:div.py-2 {:x-data "{ show: true }"}
             [:label {:class "px-1 text-sm text-gray-600"
                      :for "password"}
              "Password"]
             [:div.relative
              [:input {:class "text-md block px-3 py-2 rounded-lg w-full bg-white border-2 border-gray-300 placeholder-gray-600 shadow-md focus:placeholder-gray-500 focus:bg-white focus:border-gray-600 focus:outline-none"
                       :id "password"
                       :name "password"
                       :type "password"
                       ":type" "show ? 'password' : 'text'"}]
              [:div {:class "absolute inset-y-0 right-0 pr-3 flex items-center text-sm leading-5"}
               [:svg {:class "h-6 text-gray-700"
                      ":class" "{'hidden': !show, 'block':show }"
                      "@click" "show = !show"
                      :fill "none"
                      :viewbox "0 0 576 512"
                      :xmlns "http://www.w3.org/2000/svg"}
                [:path {:d "M572.52 241.4C518.29 135.59 410.93 64 288 64S57.68 135.64 3.48 241.41a32.35 32.35 0 0 0 0 29.19C57.71 376.41 165.07 448 288 448s230.32-71.64 284.52-177.41a32.35 32.35 0 0 0 0-29.19zM288 400a144 144 0 1 1 144-144 143.93 143.93 0 0 1-144 144zm0-240a95.31 95.31 0 0 0-25.31 3.79 47.85 47.85 0 0 1-66.9 66.9A95.78 95.78 0 1 0 288 160z"
                        :fill "currentColor"}]]
               [:svg {:class "h-6 text-gray-700"
                      ":class" "{'block': !show, 'hidden':show }"
                      "@click" "show = !show"
                      :fill "none"
                      :viewbox "0 0 640 512"
                      :xmlns "http://www.w3.org/2000/svg"}
                [:path {:d "M320 400c-75.85 0-137.25-58.71-142.9-133.11L72.2 185.82c-13.79 17.3-26.48 35.59-36.72 55.59a32.35 32.35 0 0 0 0 29.19C89.71 376.41 197.07 448 320 448c26.91 0 52.87-4 77.89-10.46L346 397.39a144.13 144.13 0 0 1-26 2.61zm313.82 58.1l-110.55-85.44a331.25 331.25 0 0 0 81.25-102.07 32.35 32.35 0 0 0 0-29.19C550.29 135.59 442.93 64 320 64a308.15 308.15 0 0 0-147.32 37.7L45.46 3.37A16 16 0 0 0 23 6.18L3.37 31.45A16 16 0 0 0 6.18 53.9l588.36 454.73a16 16 0 0 0 22.46-2.81l19.64-25.27a16 16 0 0 0-2.82-22.45zm-183.72-142l-39.3-30.38A94.75 94.75 0 0 0 416 256a94.76 94.76 0 0 0-121.31-92.21A47.65 47.65 0 0 1 304 192a46.64 46.64 0 0 1-1.54 10l-73.61-56.89A142.31 142.31 0 0 1 320 112a143.92 143.92 0 0 1 144 144c0 21.63-5.29 41.79-13.9 60.11z"
                        :fill "currentColor"}]]]]]
            [:div {:class "flex justify-between"}
             [:label {:class "block text-gray-500 font-bold my-4"}
              [:input {:checked (contains? params "rememberme")
                       :class "leading-loose text-pink-600"
                       :id "rememberme"
                       :name "rememberme"
                       :type "checkbox"}]
              [:span {:class "py-2 text-sm text-gray-600 leading-snug"}
               " Remember me"]]]
            [:div {:class "text-red-600 font-bold"}
             error]
            [:button {:class "mt-3 text-lg font-semibold bg-gray-800 w-full text-white rounded-lg px-6 py-3 block shadow-xl hover:text-white hover:bg-black"}
             "Create account"]
            [:div {:class "mt-3 flex justify-between"}
             [:span {:class "py-2 text-sm text-blue-600 leading-snug"}
              [:a {:href "/login"}
               "Log in to an existing account"]]]]]]]]]]])))

(defn POST-register [{:keys [config params session] :as request}]
  (let [{:keys [postgres]} config
        {:strs [email password username]} params
        email (some-> email str/trim str/lower-case)
        username (some-> username str/trim)]
    (cond
      (not (or (seq email) (seq password) (seq username)))
      (register request)

      (not (seq username))
      (register request "Please enter a username")

      (not (seq email))
      (register request "Please enter an email address")

      (str/blank? password)
      (register request "Please enter a password")

      (not (re-matches acct/re-username username))
      (register request "Username must contain only letters, numbers, and hyphens. Hyphens can only appear one at a time and not at the beginning or end.")

      (not (re-matches acct/re-email email))
      (register request "That is not a valid email address")

      (acct/get-account! postgres username)
      (register request "There is already an account with that username")

      (acct/get-account! postgres email)
      (register request "There is already an account with that email")

      :else
      (let [acct {:buddy-hash (hashers/derive password)
                  :email email
                  :username username
                  :verification-code (str (random-uuid))}
            {:account/keys [id]} (acct/create-account! postgres acct)]
        {:status 302
         :headers {"Location" "/"}
         :session (assoc session :account-id id :email email)}))))

(defn password-reset [{:keys [params]} & [error]]
  ;; https://tailwindcomponents.com/component/login-showhide-password
  (response
   (list
    [:script {:defer true
              :src "/js/alpine2.js"}]
    [:div {:class "container max-w-full mx-auto py-24 px-6"}
     [:div.font-sans
      [:div {:class "max-w-sm mx-auto px-6"}
       [:div {:class "relative flex flex-wrap"}
        [:div {:class "w-full relative"}
         [:div.mt-6
          [:div {:class "text-center font-semibold text-black"}
           "Password Recovery"]
          [:form.mt-8 {:method "post"}
           [:div.mx-auto.max-w-lg
            [:div.py-2
             [:label {:class "px-1 text-sm text-gray-600"
                      :for "username"}
              "Username or email address"]
             [:input {:class "text-md block px-3 py-2 rounded-lg w-full bg-white border-2 border-gray-300 placeholder-gray-600 shadow-md focus:placeholder-gray-500 focus:bg-white focus:border-gray-600 focus:outline-none"
                      :id "username"
                      :name "username"
                      :type "text"
                      :value (get params "username")}]]
            [:div {:class "text-red-600 font-bold"}
             error]
            [:button {:class "mt-3 text-lg font-semibold bg-gray-800 w-full text-white rounded-lg px-6 py-3 block shadow-xl hover:text-white hover:bg-black"}
             "Reset password"]]]]]]]]])))

(defn POST-password-reset [{:keys [config params] :as request}]
  (let [{:keys [postgres sesv2]} config
        {:strs [username]} params
        username (some-> username str/trim str/lower-case)]
    (if-not (seq username)
      (password-reset request)
      (let [{:account/keys [email id]}
            (acct/get-account! postgres username [:email :id])]
        (when id
          (email/email-password-reset-code!
           sesv2 email
           (acct/create-password-reset-code! postgres id)))
        (message-page "Password Recovery" "Please check your email for a link to reset your password.")))))

(defn password-reset-entry-page [_ username & [error]]
  ;; https://tailwindcomponents.com/component/login-showhide-password
  (response
   (list
    [:script {:defer true
              :src "/js/alpine2.js"}]
    [:div {:class "container max-w-full mx-auto py-24 px-6"}
     [:div.font-sans
      [:div {:class "max-w-sm mx-auto px-6"}
       [:div {:class "relative flex flex-wrap"}
        [:div {:class "w-full relative"}
         [:div.mt-6
          [:div {:class "text-center font-semibold text-black"}
           "Set new password for account " username]
          [:form.mt-8 {:method "post"}
           [:div.mx-auto.max-w-lg
            [:div.py-2 {:x-data "{ show: true }"}
             [:label {:class "px-1 text-sm text-gray-600"
                      :for "password"}
              "Password"]
             [:div.relative
              [:input {:class "text-md block px-3 py-2 rounded-lg w-full bg-white border-2 border-gray-300 placeholder-gray-600 shadow-md focus:placeholder-gray-500 focus:bg-white focus:border-gray-600 focus:outline-none"
                       :id "password"
                       :name "password"
                       :type "password"
                       ":type" "show ? 'password' : 'text'"}]
              [:div {:class "absolute inset-y-0 right-0 pr-3 flex items-center text-sm leading-5"}
               [:svg {:class "h-6 text-gray-700"
                      ":class" "{'hidden': !show, 'block':show }"
                      "@click" "show = !show"
                      :fill "none"
                      :viewbox "0 0 576 512"
                      :xmlns "http://www.w3.org/2000/svg"}
                [:path {:d "M572.52 241.4C518.29 135.59 410.93 64 288 64S57.68 135.64 3.48 241.41a32.35 32.35 0 0 0 0 29.19C57.71 376.41 165.07 448 288 448s230.32-71.64 284.52-177.41a32.35 32.35 0 0 0 0-29.19zM288 400a144 144 0 1 1 144-144 143.93 143.93 0 0 1-144 144zm0-240a95.31 95.31 0 0 0-25.31 3.79 47.85 47.85 0 0 1-66.9 66.9A95.78 95.78 0 1 0 288 160z"
                        :fill "currentColor"}]]
               [:svg {:class "h-6 text-gray-700"
                      ":class" "{'block': !show, 'hidden':show }"
                      "@click" "show = !show"
                      :fill "none"
                      :viewbox "0 0 640 512"
                      :xmlns "http://www.w3.org/2000/svg"}
                [:path {:d "M320 400c-75.85 0-137.25-58.71-142.9-133.11L72.2 185.82c-13.79 17.3-26.48 35.59-36.72 55.59a32.35 32.35 0 0 0 0 29.19C89.71 376.41 197.07 448 320 448c26.91 0 52.87-4 77.89-10.46L346 397.39a144.13 144.13 0 0 1-26 2.61zm313.82 58.1l-110.55-85.44a331.25 331.25 0 0 0 81.25-102.07 32.35 32.35 0 0 0 0-29.19C550.29 135.59 442.93 64 320 64a308.15 308.15 0 0 0-147.32 37.7L45.46 3.37A16 16 0 0 0 23 6.18L3.37 31.45A16 16 0 0 0 6.18 53.9l588.36 454.73a16 16 0 0 0 22.46-2.81l19.64-25.27a16 16 0 0 0-2.82-22.45zm-183.72-142l-39.3-30.38A94.75 94.75 0 0 0 416 256a94.76 94.76 0 0 0-121.31-92.21A47.65 47.65 0 0 1 304 192a46.64 46.64 0 0 1-1.54 10l-73.61-56.89A142.31 142.31 0 0 1 320 112a143.92 143.92 0 0 1 144 144c0 21.63-5.29 41.79-13.9 60.11z"
                        :fill "currentColor"}]]]]]
            [:div {:class "text-red-600 font-bold"}
             error]
            [:button {:class "mt-3 text-lg font-semibold bg-gray-800 w-full text-white rounded-lg px-6 py-3 block shadow-xl hover:text-white hover:bg-black"}
             "Set password"]]]]]]]]])))

(defn password-reset-entry [{:keys [config params] :as request}]
  (let [{:keys [postgres]} config
        {:strs [code]} params
        {:account/keys [id username]} (when code (acct/get-account-for-reset-code! postgres code [:id :username]))]
    (if id
      (password-reset-entry-page request username)
      (message-page "Invalid code" "The code is invalid or expired."))))

(defn POST-password-reset-entry [{:keys [config params] :as request}]
  (let [{:keys [postgres]} config
        {:strs [code password]} params
        {:account/keys [id]} (when code (acct/get-account-for-reset-code! postgres code [:id]))]
    (prn code)
    (if-not (and id (seq password))
      (password-reset-entry request)
      (do
        (acct/set-password! postgres id password)
        (message-page "Password Set" "Your password has been set.")))))

(defn logout [{:keys [session]}]
  {:status 302
   :headers {"Location" "/"}
   :session (dissoc session :account-id :email)})

(defn routes [config]
  (let [;; Allow hot-reloading in dev when handler is a var.
        ;; reitit does not natively understand vars.
        h (fn [handler]
            (fn [request]
              (-> request
                  (assoc :config config)
                  handler)))]
    [["/" {:get (h #'home)
           :middleware [parameters-middleware]
           :post (h #'POST-home)}]
     ["/" {:middleware [parameters-middleware]}
      ["login" {:get (h #'login)
                :post (h #'POST-login)}]
      ["logout" {:get (h #'logout)}]
      ["password-reset" {:get (h #'password-reset)
                         :post (h #'POST-password-reset)}]
      ["password-reset-entry" {:get (h #'password-reset-entry)
                               :post (h #'POST-password-reset-entry)}]
      ["register" {:get (h #'register)
                   :post (h #'POST-register)}]
      ["p/:username/:project-name"
       ["" {:get (h #'get-project)}]
       ["/activity" {:get (h #'activity)}]
       ["/documents" {:get (h #'documents)}]
       ["/flow" {:get (h #'get-flows)}]
       ["/flow/:flow-name" {:get (h #'get-flow)}]
       ["/invite" {:get (h #'invite)}]]
      ["hx"
       ["/validate/:form-id/:field-id"
        {:post (h #'hx-validate-form-field)}]
       ["/project/:username/:project-name"
        ["/activity" {:get (h #'hx-activity)}]]]]]))
