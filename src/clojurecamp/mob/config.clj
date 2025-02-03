(ns clojurecamp.mob.config)

(defn config [k]
  (-> "config.edn"
      slurp
      read-string
      (get k)))

#_(config :cloudflare-token)

;; expects:
;;  :cloudflare-token
;;    https://dash.cloudflare.com/profile/api-tokens
;;  :cloudflare-zone-id
;;    find using cloudflare-zone-id below
;;  :linode-token
;;    https://cloud.linode.com/profile/tokens


