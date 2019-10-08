(ns sample-query-processing
  (:require
   [com.wsscode.pathom.core :as p]
   [com.wsscode.pathom.connect :as pc]
   [clojure.core.async :refer [<!!]]
   [meander.epsilon :as m]))

;; How to go from :person/id to that person's details
(pc/defresolver person-resolver [env {:keys [person/id] :as params}]
  ;; The minimum data we must already know in order to resolve the outputs
  {::pc/input  #{:person/id}
   ;; A query template for what this resolver outputs
   ::pc/output [:person/name
                {:person/address [:address/id]}
                :person/zipcode]}
  ;; normally you'd pull the person from the db, and satisfy the listed
  ;; outputs. For demo, we just always return the same person details.
  {:person/name "Tom"
   :person/zipcode "01234567"
   :person/address {:address/id 1}})

;; how to go from :address/id to address details.
(pc/defresolver address-resolver [env {:keys [address/id] :as params}]
  {::pc/input  #{:address/id}
   ::pc/output [:address/city :address/state]}
  {:address/city "Salem"
   :address/state "MA"})

(pc/defresolver zipcode-resolver [env {:keys [person/zipcode] :as params}]
  {::pc/input  #{:person/zipcode}
   ::pc/output [:address/line1]}
  (if (= "0123457" zipcode)
    {:address/line1 "line1"}
    (throw (ex-info "cant resolve" {}))))

;; define a list with our resolvers
(def my-resolvers
  [person-resolver
   address-resolver
   zipcode-resolver])

;; setup for a given connect system
(def parser
  (p/parallel-parser
   {::p/env     {::p/reader               [p/map-reader
                                           pc/parallel-reader
                                           pc/open-ident-reader
                                           p/env-placeholder-reader]
                 ::p/placeholder-prefixes #{">"}}
    ::p/mutate  pc/mutate-async
    ::p/plugins [(pc/connect-plugin {::pc/register my-resolvers})
                 p/error-handler-plugin
                 p/trace-plugin]}))

;; A join on a lookup ref (Fulcro ident) supplies the starting state of :person/id 1.
;; env can have anything you want in it (e.g. a Datomic/SQL connection, network service endpoint, etc.)
;; the concurrency is handled though core.async, so you have to read the channel to get the output
(let [result (<!! (parser {} [{[:person/id 1]
                               [:person/name
                                {:person/address [:address/city]}
                                :address/line1]}]))]
  (clojure.pprint/pprint result)
  (m/search result
    {?lookup-ref {:person/name ?name
                  :person/address {:address/city ?city}
                  :address/line1 ?line1}}

    {:name+city (str ?name " " ?city)
     :extra-line ?line1}))
