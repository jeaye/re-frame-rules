(ns com.jeaye.re-frame-rules
  (:require [re-frame.core :as re-frame]
            [clojure.set]))

(defn event->callback-pred
  "Looks at the required-events items and returns a predicate which
   will either
   - match only the event-keyword if a keyword is supplied
   - match the entire event vector if a collection is supplied
   - returns a callback-pred if it is a fn"
  [callback-pred]
  (when callback-pred
    (cond (fn? callback-pred) callback-pred
          (keyword? callback-pred) (fn [[event-id _]]
                                     (= callback-pred event-id))
          (coll? callback-pred) (fn [event-v]
                                  (= callback-pred event-v))
          :else (throw
                  (ex-info (str (pr-str callback-pred)
                                " isn't an event predicate")
                           {:callback-pred callback-pred})))))

(defn process-event [{:as m
                      :keys [unregister register events dispatch-to]}]
  (let [_ (assert (map? m)
                  (str "re-frame: effects handler for :forward-events expected a map or a list of maps. Got: " m))
        _ (assert (or (= #{::unregister} (-> m keys set))
                      (= #{::register ::events ::dispatch-to} (-> m keys set)))
                  (str "re-frame: effects handler for :forward-events given wrong map keys" (-> m keys set)))]
    (if unregister
      (re-frame/remove-post-event-callback unregister)
      (let [events-preds (map event->callback-pred events)
            post-event-callback-fn (fn [event-v _]
                                     (when (some (fn [pred] (pred event-v))
                                                 events-preds)
                                       (re-frame/dispatch (conj dispatch-to event-v))))]
        (re-frame/add-post-event-callback register post-event-callback-fn)))))

(re-frame/reg-fx
  ::forward-events
  (fn [val]
    (cond
      (map? val) (process-event val)
      (sequential? val) (doseq [v val]
                          (process-event v))
      :else (re-frame/console :error
                              "::forward-events expected a map or a list of maps, but got: "
                              val))))

(defn seen-any?
  [required-events seen-event]
  (let [callback-preds (map as-callback-pred required-events)]
    (->> (some (fn [pred] (pred seen-event))
               callback-preds)
         some?)))

(defn matched-rules
  [rules seen-event]
  (filterv (fn [task]
             ((::when task) (::events task) seen-event))
           rules))

(def map-when->fn {:seen? seen-any?
                   :seen-any? seen-any?})

(defn when->fn
  [when-kw]
  (if-let [when-fn (map-when->fn when-kw)]
    when-fn
    (re-frame/console :error "async-flow: got bad value for :when - " when-kw)))

(defn massage-rule [index {:as rule
                           :keys [id when events dispatch dispatch-n unbind?]}]
  {::id (or id (str flow-id "#" index))
   ::unbind? (or unbind? false)
   ::when (when->fn when)
   ::events (if (coll? events)
              (set events)
              #{events})
   ::dispatch-n (cond
                  dispatch-n (if dispatch
                               (re-frame/console :error
                                                 "async-flow: rule can only specify one of :dispatch and :dispatch-n. Got both: "
                                                 rule)
                               dispatch-n)
                  dispatch [dispatch]
                  :else [])})

(defn massage-rules
  "Massage the supplied rules as follows:
   - replace `:when` keyword value with a function implementing the predicate
   - ensure that only `:dispatch` or `:dispatch-n` is provided
   - add a unique :id, if one not already present"
  [flow-id rules]
  (map-indexed massage-rule rules))

(defn make-flow-event-handler
  "Given a flow definition, returns an event handler which implements this definition"
  [{:keys [id rules]}]
  (let [rules (massage-rules id rules)] ;; all of the events refered to in the rules
    ;; Return an event handler which will manage the flow.
    ;; This event handler will receive 3 kinds of events:
    ;; (dispatch [:id ::setup])
    ;; (dispatch [:id [:forwarded :event :vector]])
    ;;
    ;; This event handler returns a map of effects - it expects to be registered using
    ;; reg-event-fx
    (fn async-flow-event-hander [{:keys [db]} [_ event-type :as event-v]]
      (condp = event-type
        ;; Setup this flow coordinator:
        ;; 1. Arrange for the events to be forwarded to this handler
        ::setup {:forward-events {::register id
                                  ::events (->> (map ::events rules)
                                                (apply clojure.set/union))
                                  ::dispatch-to [id]}}

        ;; Here we are managing the flow.
        ;; A new event has been forwarded, so work out what should happen:
        ;; 1. Does this new event mean we should dispatch another?
        (let [[_ forwarded-event] event-v
              matched-rules (match-rules rules forwarded-event)
              unbind? (some ::unbind? matched-rules)
              new-dispatches (mapcat ::dispatch-n matched-rules)]
          (merge {}
                 (when (seq new-dispatches)
                   {:dispatch-n new-dispatches})
                 (when unbind?
                   ;; Teardown this flow coordinator:
                   ;; 1. Remove this event handler
                   ;; 2. Deregister the events forwarder
                   {::forward-events {::unregister id}
                    :deregister-event-handler id})))))))

(defn- ensure-has-id
  "Ensure `flow` has an id. Return a vector of [id flow]."
  [flow]
  (if-let [id (:id flow)]
    [id flow]
    (let [new-id (->> (gensym "id-")
                      (str "re-frame-rules/")
                      keyword)]
      [new-id (assoc flow :id new-id)])))

(re-frame/reg-fx
  ::bind
  (fn [flow]
    (let [[id flow'] (ensure-has-id flow)]
      (re-frame/reg-event-fx id (make-flow-event-handler flow'))
      (re-frame/dispatch [id :setup]))))
