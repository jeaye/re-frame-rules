(ns com.jeaye.re-frame-rules
  (:require [re-frame.core :as re-frame]
            [clojure.set]))

; TODO: allow {:stub [::foo ::bar]} to generate stubs

(defn event->callback-pred
  "Looks at the required-events items and returns a predicate which
   will either
   - match only the event-keyword if a keyword is supplied
   - match the entire event vector if a collection is supplied
   - returns a callback-pred if it is a fn"
  [callback-pred]
  ;(println "build pred for" callback-pred)
  (when callback-pred
    (cond (fn? callback-pred) callback-pred
          (keyword? callback-pred) (fn [[event-id _]]
                                     ;(println "pred " callback-pred event-id)
                                     (= callback-pred event-id))
          (coll? callback-pred) (fn [event-v]
                                  ;(println "coll pred " callback-pred event-v)
                                  (= callback-pred event-v))
          :else (throw
                  (ex-info (str (pr-str callback-pred)
                                " isn't an event predicate")
                           {:callback-pred callback-pred})))))

(defn process-event [{:as m
                      :keys [::unregister ::register ::events ::dispatch-to]}]
  ;(println "process events" m)
  (let [_ (assert (map? m)
                  (str "re-frame-rules: effects handler for :forward-events expected a map or a list of maps. Got: " m))
        _ (assert (or (= #{::unregister} (-> m keys set))
                      (= #{::register ::events ::dispatch-to} (-> m keys set)))
                  (str "re-frame-rules: effects handler for :forward-events given wrong map keys" (-> m keys set)))]
    (if unregister
      (re-frame/remove-post-event-callback unregister)
      (let [events-preds (map event->callback-pred events)
            post-event-callback-fn (fn [event-v _]
                                     ;(println "post-callback for" event-v)
                                     (when (some (fn [pred] (pred event-v))
                                                 events-preds)
                                       ;(println "dispatching" (conj dispatch-to event-v))
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
                              "re-frame-rules: ::forward-events expected a map or a list of maps, but got: "
                              val))))

(defn seen-any?
  [required-events seen-event]
  (let [callback-preds (map event->callback-pred required-events)]
    (->> (some (fn [pred] (pred seen-event)) callback-preds)
         some?)))

(defn match-rules
  [rules seen-event]
  (filterv (fn [rule]
             ;(println "matching rule" [seen-event ((::when rule) (::events rule) seen-event)])
             ((::when rule) (::events rule) seen-event))
           rules))

(def map-when->fn {:seen? seen-any?
                   :seen-any? seen-any?})

(defn when->fn
  [when-kw]
  (if-let [when-fn (map-when->fn when-kw)]
    when-fn
    (re-frame/console :error "re-frame-rules: got bad value for :when - " when-kw)))

(defn get-dispatch-n [dispatch dispatch-n]
  (cond
    dispatch-n (if (some? dispatch)
                 (re-frame/console :error
                                   "re-frame-rules: can only specify one of :dispatch and :dispatch-n.")
                 dispatch-n)
    dispatch [dispatch]
    :else []))

(defn massage-rule [flow-id index {:keys [id when events
                                          dispatch dispatch-n dispatch-fn
                                          unbind?]
                                   :as rule}]
  (merge {::id (or id (str flow-id "#" index))
          ::unbind? (or unbind? false)
          ::when (when->fn when)
          ::events (if (coll? events)
                     (set events)
                     #{events})}
         (if (some? dispatch-fn)
           {::dispatch-fn dispatch-fn}
           {::dispatch-n (get-dispatch-n dispatch dispatch-n)})))

(defn massage-rules
  "Massage the supplied rules as follows:
   - replace `:when` keyword value with a function implementing the predicate
   - ensure that only `:dispatch` or `:dispatch-n` is provided
   - add a unique :id, if one not already present"
  [flow-id rules]
  ;(println "massaging rules" rules)
  ;(println "massaged" (map-indexed #(massage-rule flow-id %1 %2) rules))
  (map-indexed #(massage-rule flow-id %1 %2) rules))

(defn rules->dispatches
  "Given an rule and event, return a sequence of dispatches. For each dispatch in the rule:
   - if the dispatch is a keyword, return it as is
   - if the dispatch is a function, call the function with the event"
  [rules event]
  (mapcat (fn [rule]
            (let [{:keys [::dispatch-fn ::dispatch-n]} rule]
              (cond
                (some? dispatch-n) dispatch-n
                (some? dispatch-fn) (let [dispatch-n (dispatch-fn event)]
                                      (if (every? vector? dispatch-n)
                                        dispatch-n
                                        (re-frame/console :error "re-frame-rules: dispatch-fn must return a seq of events " rule)))
                :else [])))
          rules))

(defn make-rules-event-handler
  "Given a flow definition, returns an event handler which implements this definition"
  [{:keys [id rules first-dispatch first-dispatch-n] :as m}]
  ;(println "full rules" m)
  (let [rules (massage-rules id rules)] ;; all of the events refered to in the rules
    ;; Return an event handler which will manage the flow.
    ;; This event handler will receive 3 kinds of events:
    ;; (dispatch [:id ::setup])
    ;; (dispatch [:id [:forwarded :event :vector]])
    ;;
    ;; This event handler returns a map of effects - it expects to be registered using
    ;; reg-event-fx
    (fn re-frame-rules-event-hander [{:keys [db]} [_ event-type :as event-v]]
      (condp = event-type
        ;; Setup this flow coordinator:
        ;; 1. Arrange for the events to be forwarded to this handler
        ::setup {:dispatch-n (get-dispatch-n first-dispatch first-dispatch-n)
                 ::forward-events {::register id
                                   ::events (->> (map ::events rules)
                                                 (apply clojure.set/union))
                                   ::dispatch-to [id]}}

        ;; Here we are managing the flow.
        ;; A new event has been forwarded, so work out what should happen:
        ;; 1. Does this new event mean we should dispatch another?
        (let [[_ forwarded-event] event-v
              matched-rules (match-rules rules forwarded-event)
              unbind? (some ::unbind? matched-rules)
              new-dispatches (rules->dispatches matched-rules forwarded-event)]
          (merge {}
                 (when (seq new-dispatches)
                   (println "rules dispatching" new-dispatches)
                   {:dispatch-n new-dispatches})
                 (when unbind?
                   ;(println "unbinding" id)
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
      (re-frame/reg-event-fx id (make-rules-event-handler flow'))
      (re-frame/dispatch [id ::setup]))))
