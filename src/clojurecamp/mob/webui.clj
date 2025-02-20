(ns clojurecamp.mob.webui
  (:require
   [clojure.pprint :as pprint]
   [huff2.core :as h]
   [org.httpkit.server :as http]
   [ring.middleware.defaults :as rmd]
   [ring.middleware.oauth2 :as oauth]
   [clojurecamp.mob.github :as github]
   [clojurecamp.mob.config :as config]
   [clojurecamp.mob.log :refer [log! get-log]]
   [clojurecamp.mob.cron :as cron]
   [clojurecamp.mob.orchestration :refer [mob-progress
                                          ->mob-state
                                          mob-can-start?
                                          mob-can-stop?]
    :as orch]))

(defn log-page []
  [:div
   [:h2 "Log"]
   [:table
    [:tbody
     (for [log-entry (->> (get-log)
                          (sort-by :time)
                          reverse
                          (take 100))]
       [:tr
        [:td [:code (str (:time log-entry))]]
        [:td [:code (str (:event-type log-entry))]]
        [:td
         (if (:extra-info log-entry)
           [:details
            [:summary (pr-str (:main-info log-entry))]
            [:code {:style {:width "50em"
                            :display "block"
                            :overflow-x "auto"}}
             ;; slow
             [:pre
              (with-out-str
                (pprint/pprint (:extra-info log-entry)))]]]
           [:div (pr-str (:main-info log-entry))])]])]]])

(defn main-page []
  (let [progress (mob-progress)
        state (->mob-state progress)]
    [:html
     [:head
      [:title "Clojure Camp - Mob Server Controller"]]
     [:body
      (cond
        (mob-can-start? state)
        [:<>
         [:button {:onclick "fetch('/start/north-america', {method: 'POST'}).then(() => location.reload())"} "Start (in North America)"]
         [:br]
         [:button {:onclick "fetch('/start/europe', {method: 'POST'}).then(() => location.reload())"} "Start (in Europe)"]]
        (mob-can-stop? state)
        [:button {:onclick "fetch('/stop', {method: 'POST'}).then(() => location.reload())"} "Stop"]
        :else
        "Doing stuff...")
      [:hr]
      [:div
       [:pre (pr-str state)]]
      [:hr]
      [:table
       [:tbody
        (for [[label status] progress]
          [:tr
           [:td (name label)]
           [:td (if status "✅" "❌")]])]]
      [:hr]
      (when-let [ip (:ip (meta progress))]
        [:<>
         [:div
          [:a {:href "https://mob.clojure.camp"} "https://mob.clojure.camp"]
          [:pre "=>"]
          [:pre ip]]
         [:hr]])
      [:div
       [:pre
        "cron running? " (pr-str (cron/running?))]]
      [:hr]
      [:div
       [:a {:href "/logs"} "logs"]]
      (when (cron/running?)
        [:script
         [:hiccup/raw-html
          ;; auto refresh page
          "setInterval(() => location.reload(), 15000);"]])]]))

(def oauth-launch-uri "/oauth/github")
(def oauth-redirect-uri "/oauth/github/callback")
(def oauth-landing-uri "/oauth/github/post-auth")

(defn wrap-auth [handler]
  (fn [req]
    (cond
      ;; post-auth url => convert oauth token to our own session
      (and
        (= oauth-landing-uri (:uri req))
        (= :get (:request-method req)))
      (let [token (get-in req [:session ::oauth/access-tokens :github :token])
            email (github/get-email token)]
        (if (contains? (config/config :email-allowlist) email)
          {:status 302
           :session {:email email}
           :headers {"Location" "/"}}
          {:status 401
           :body "Unauthorized"}))
      ;; logged in? => handle request
      (get-in req [:session :email])
      (handler req)
      ;; home page (not logged in) => show login link
      (and
        (= "/" (:uri req))
        (= :get (:request-method req)))
      {:status 200
       :headers {"Content-Type" "text/html; charset=utf-8"}
       :body (str (h/html [:html
                           [:body
                            [:a {:href oauth-launch-uri}
                             "Log In"]]]))}
      ;; otherwise, all routes unauthorized
      :else
      {:status 401
       :body "Unauthorized"})))

(defn handler [req]
  (cond
    (and
      (= "/" (:uri req))
      (= :get (:request-method req)))
    {:status 200
     :headers {"Content-Type" "text/html; charset=utf-8"}
     :body (str (h/html {:allow-raw true} (main-page)))}

    (and
      (= "/logs" (:uri req))
      (= :get (:request-method req)))
    {:status 200
     :headers {"Content-Type" "text/html; charset=utf-8"}
     :body (str (h/html (log-page)))}

    (and
      (= "/start/north-america" (:uri req))
      (= :post (:request-method req)))
    (do
      (orch/mob-start! "ca-central")
      {:status 200})

    (and
      (= "/start/europe" (:uri req))
      (= :post (:request-method req)))
    (do
      (orch/mob-start! "eu-central")
      {:status 200})

    (and
      (= "/stop" (:uri req))
      (= :post (:request-method req)))
    (do
      (orch/mob-stop!)
      {:status 200})))

(def app
  (-> handler
      wrap-auth
      (oauth/wrap-oauth2 {:github
                          {:authorize-uri    "https://github.com/login/oauth/authorize"
                           :access-token-uri "https://github.com/login/oauth/access_token"
                           :client-id        (config/config :github-oauth-client-id)
                           :client-secret    (config/config :github-oauth-client-secret)
                           :scopes           ["user:email"]
                           :launch-uri       oauth-launch-uri
                           :redirect-uri     oauth-redirect-uri
                           :landing-uri      oauth-landing-uri}})
      (rmd/wrap-defaults
       (-> rmd/site-defaults
           ;; our oauth2 library requires lax (instead of strict), due to setting state in the session
           (assoc-in [:session :cookie-attrs :same-site] :lax)
           (assoc-in [:session :cookie-name] "clojure-camp-mob-control")))))

(defonce server (atom nil))

(defn start-server! []
  (when @server
    (@server))
  (reset! server (http/run-server #'app {:port
                                         (or (some-> (System/getenv "PORT")
                                                     parse-long)
                                             9624)})))

#_(start-server!)

