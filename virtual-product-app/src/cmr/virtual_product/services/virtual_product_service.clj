(ns cmr.virtual-product.services.virtual-product-service
  "Handles ingest events by filtering them to only events that matter for the virtual products and
  applies equivalent updates to virtual products."
  (:require [cmr.transmit.metadata-db :as mdb]
            [cmr.transmit.ingest :as ingest]
            [cmr.virtual-product.config :as config]
            [cmr.message-queue.services.queue :as queue]
            [cmr.common.log :refer (debug info warn error)]
            [cmr.umm.core :as umm]
            [cmr.umm.granule :as umm-g]
            [clojure.string :as str]
            [cmr.common.mime-types :as mime-types]
            [cmr.common.concepts :as concepts]
            [cmr.common.services.errors :as errors]
            [cmr.common.util :as u :refer [defn-timed]]))

(defmulti handle-ingest-event
  "Handles an ingest event. Checks if it is an event that should be applied to virtual granules. If
  it is then delegates to a granule event handler."
  (fn [context event]
    (keyword (:action event))))

(defmethod handle-ingest-event :default
  [context event]
  ;; Does nothing. We ignore events we don't care about.
  )

(defn subscribe-to-ingest-events
  "Subscribe to messages on the indexing queue."
  [context]
  (when (config/virtual-products-enabled)
    (let [queue-broker (get-in context [:system :queue-broker])
          queue-name (config/virtual-product-queue-name)]
      (dotimes [n (config/queue-listener-count)]
        (queue/subscribe queue-broker queue-name #(handle-ingest-event context %))))))

(def source-provider-id-entry-titles
  "A set of the provider id entry titles for the source collections."
  (-> config/source-to-virtual-product-config keys set))

(defn- annotate-event
  "Adds extra information to the event to help with processing"
  [{:keys [concept-id] :as event}]
  (let [{:keys [provider-id concept-type]} (concepts/parse-concept-id concept-id)]
    (-> event
        (update-in [:action] keyword)
        (assoc :provider-id provider-id :concept-type concept-type))))

(defn- virtual-granule-event?
  "Returns true if this is an event that should apply to virtual granules"
  [{:keys [concept-type provider-id entry-title]}]
  (and (= :granule concept-type)
       (contains? source-provider-id-entry-titles [provider-id entry-title])))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Handle updates

(defn- handle-update-response
  [response granule-ur]
  (let [{:keys [status body]} response]
    (cond
      (<= 200 status 299) (info (format "Ingested virtual granule [%s] with the follwing response: [%s]"
                                        granule-ur (pr-str body)))
      ;; This would occurs when an ingest event with lower revision-id is consumed after an event with
      ;; higher revision id for the same granule. The event is ignored and the revision is lost.
      (= status 409) (info (format "Received a response with status code [409] and the following body when ingesting the virtual granule [%s] : [%s]. The event will be ignored."
                                   granule-ur (pr-str body)))
      :else (errors/internal-error!
              (format "Received unexpected status code [%s] and the following response when ingesting the virtual granule [%s] : [%s] "
                      status (pr-str response) granule-ur)))))

(defn-timed apply-source-granule-update-event
  "Applies a source granule update event to the virtual granules"
  [context {:keys [provider-id entry-title concept-id revision-id]}]
  (let [orig-concept (mdb/get-concept context concept-id revision-id)
        orig-umm (umm/parse-concept orig-concept)
        vp-config (config/source-to-virtual-product-config [provider-id entry-title])]

    (doseq [virtual-coll (:virtual-collections vp-config)]
      (let [new-granule-ur (config/generate-granule-ur provider-id
                                                       (:source-short-name vp-config)
                                                       (:short-name virtual-coll)
                                                       (:granule-ur orig-umm))
            new-umm (assoc orig-umm
                           :granule-ur new-granule-ur
                           :collection-ref (umm-g/map->CollectionRef
                                             (select-keys virtual-coll [:entry-title])))
            new-metadata (umm/umm->xml new-umm (mime-types/mime-type->format
                                                 (:format orig-concept)))
            new-concept (-> orig-concept
                            (select-keys [:revision-id :format :provider-id :concept-type])
                            (assoc :native-id new-granule-ur
                                   :metadata new-metadata))
            resp (ingest/ingest-concept context new-concept true)]
        (handle-update-response resp new-granule-ur)))))

(defmethod handle-ingest-event :concept-update
  [context event]
  (when (config/virtual-products-enabled)
    (let [annotated-event (annotate-event event)]
      (when (virtual-granule-event? annotated-event)
        (apply-source-granule-update-event context annotated-event)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Handle deletes

(defn- annotate-granule-delete-event
  "Adds extra information to the granule delete event to aid in processing."
  [context event]
  (let [{:keys [concept-id revision-id provider-id]} event
        granule-delete-concept (mdb/get-concept context concept-id revision-id)
        {{:keys [parent-collection-id granule-ur]} :extra-fields} granule-delete-concept
        parent-collection-concept (mdb/get-latest-concept context parent-collection-id)
        entry-title (get-in parent-collection-concept [:extra-fields :entry-title])]
    (assoc event
           :granule-ur granule-ur
           :entry-title entry-title)))

(defn- handle-delete-response
  [response granule-ur]
  (let [{:keys [status body]} response]
    (cond
      (<= 200 status 299) (info (format "Deleted virtual granule [%s] with the following response: [%s]"
                                        granule-ur (pr-str body)))
      ;; This would occur if delete event is consumed before an ingest event and metadata-db does
      ;; not yet have the granule concept. This usually means that an ingest event for the same
      ;; granule is present in the virtual product queue and is not yet consumed. The exception will
      ;; cause the event to be put back in the queue. The event will be retried until the ingest
      ;; event is consumed.
      ;; Note that the existence of a delete event for the virtual granule means that the real
      ;; granule exists in the database (otherwise corresponding delete request for real granule
      ;; would fail with 404 and the delete event for the virtual granule would never be put on
      ;; the virtual product queue to begin with). Which in turn means that ingest event
      ;; corresponding to the granule has been created in the past (either recent past or
      ;; distant past) and that event has to to consumed before the delete event can be consumed
      ;; off the queue.
      (= status 404) (errors/internal-error!
                       (format "Received a response with status code [404] when deleting the virtual granule [%s]. The delete request will be retried." granule-ur))
      ;; This would occurs if a delete event with lower revision-id is consumed after an event with
      ;; higher revision id for the same granule. The event is ignored and the revision is lost.
      (= status 409) (info (format "Received a response with status code [409] and following body when deleting the virtual granule [%s] : [%s]. The event will be ignored"
                                   granule-ur (pr-str body)))
      :else (errors/internal-error!
              (format "Received unexpected status code [%s] and the following response when deleteing the virtual granule [%s] : [%s] "
                      status granule-ur (pr-str response))))))

(defn-timed apply-source-granule-delete-event
  "Applies a source granule delete event to the virtual granules"
  [context {:keys [provider-id revision-id granule-ur entry-title]}]
  (let [vp-config (config/source-to-virtual-product-config [provider-id entry-title])]
    (doseq [virtual-coll (:virtual-collections vp-config)]
      (let [new-granule-ur (config/generate-granule-ur provider-id
                                                       (:source-short-name vp-config)
                                                       (:short-name virtual-coll)
                                                       granule-ur)
            resp (ingest/delete-concept context {:provider-id provider-id
                                                 :concept-type :granule
                                                 :native-id new-granule-ur
                                                 :revision-id revision-id} true)]
        (handle-delete-response resp new-granule-ur)))))

(defmethod handle-ingest-event :concept-delete
  [context event]
  (when (config/virtual-products-enabled)
    (let [annotated-event (annotate-event event)]
      (when (= :granule (:concept-type annotated-event))
        (let [annotated-delete-event (annotate-granule-delete-event context annotated-event)]
          (when (virtual-granule-event? annotated-delete-event)
            (apply-source-granule-delete-event context annotated-delete-event)))))))


(comment
  (handle-update-response {:status 200 :body "body"} "granule-ur")
  (handle-update-response {:status 409 :body "body"} "granule-ur")
  (handle-update-response {:status 500 :body "body"} "granule-ur")

  (handle-delete-response {:status 204 :body "body"} "granule-ur")
  (handle-delete-response {:status 404 :body "body"} "granule-ur")
  (handle-delete-response {:status 409 :body "body"} "granule-ur")
  (handle-delete-response {:status 500 :body "body"} "granule-ur")
)