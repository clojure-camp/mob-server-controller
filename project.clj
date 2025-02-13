(defproject clojurecamp-mob-control "0.0.1"
  :dependencies [[org.clojure/clojure "1.12.0"]
                 [http-kit "2.8.0"]
                 [cheshire "5.13.0"]
                 [io.github.escherize/huff "0.2.12"]]
  :main clojurecamp.mob.core
  :profiles {:uberjar {:aot :all}
             :dev {:repl-options {:init-ns clojurecamp.mob.core}}})
