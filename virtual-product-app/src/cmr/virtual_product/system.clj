(ns cmr.virtual-product.system
  "Defines functions for creating, starting, and stopping the application. Applications are
  represented as a map of components. Design based on
  http://stuartsierra.com/2013/09/15/lifecycle-composition and related posts."
  (:require [cmr.common.lifecycle :as lifecycle]
            [cmr.common.log :as log :refer (debug info warn error)]
            [cmr.common.nrepl :as nrepl]
            [cmr.virtual-product.api.routes :as routes]
            [cmr.common.api.web-server :as web]
            [cmr.system-trace.context :as context]
            [cmr.common.config :as cfg :refer [defconfig]]
            [cmr.transmit.config :as transmit-config]
            [cmr.virtual-product.services.virtual-product-service :as vps]
            [cmr.virtual-product.config :as config]
            [cmr.message-queue.queue.rabbit-mq :as rmq]))

(defconfig virtual-product-nrepl-port
  "Port to listen for nREPL connections"
  {:default nil
   :parser cfg/maybe-long})

(def
  ^{:doc "Defines the order to start the components."
    :private true}
  component-order [:log :queue-broker :web :nrepl])

(defn create-system
  "Returns a new instance of the whole application."
  []
  (let [sys {:log (log/create-logger)
             :web (web/create-web-server (transmit-config/virtual-product-port) routes/make-api)
             :nrepl (nrepl/create-nrepl-if-configured (virtual-product-nrepl-port))
             :relative-root-url (transmit-config/virtual-product-relative-root-url)
             :zipkin (context/zipkin-config "virtual-product" false)
             :queue-broker (rmq/create-queue-broker (config/rabbit-mq-config))}]
    (transmit-config/system-with-connections sys [:metadata-db :ingest])))

(defn start
  "Performs side effects to initialize the system, acquire resources,
  and start it running. Returns an updated instance of the system."
  [this]
  (info "virtual-product System starting")
  (let [started-system (reduce (fn [system component-name]
                                 (update-in system [component-name]
                                            #(when % (lifecycle/start % system))))
                               this
                               component-order)]

    (vps/subscribe-to-ingest-events {:system started-system})

    (info "virtual-product System started")
    started-system))


(defn stop
  "Performs side effects to shut down the system and release its
  resources. Returns an updated instance of the system."
  [this]
  (info "virtual-product System shutting down")
  (let [stopped-system (reduce (fn [system component-name]
                                 (update-in system [component-name]
                                            #(when % (lifecycle/stop % system))))
                               this
                               (reverse component-order))]
    (info "virtual-product System stopped")
    stopped-system))
