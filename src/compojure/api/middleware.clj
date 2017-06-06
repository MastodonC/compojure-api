(ns compojure.api.middleware
  (:require [compojure.core :refer :all]
            [compojure.api.exception :as ex]
            [compojure.api.impl.logging :as logging]
            [ring.middleware.http-response]
            [ring.middleware.keyword-params :refer [wrap-keyword-params]]
            [ring.middleware.nested-params :refer [wrap-nested-params]]
            [ring.middleware.params :refer [wrap-params]]

            [muuntaja.middleware]
            [muuntaja.core :as m]
            [muuntaja.format.yaml :as yaml-format]
            [muuntaja.format.msgpack :as msgpack-format]

            [ring.swagger.common :as rsc]
            [ring.swagger.middleware :as rsm]
            [ring.swagger.coerce :as coerce]
            [ring.util.http-response :refer :all]
            [schema.core :as s]
            [schema.coerce :as sc])
  (:import [clojure.lang ArityException IMapEntry]
           [muuntaja.records Muuntaja]))

;;
;; Catch exceptions
;;

(def rethrow-exceptions? ::rethrow-exceptions?)

(defn- super-classes [^Class k]
  (loop [sk (.getSuperclass k), ks []]
    (if-not (= sk Object)
      (recur (.getSuperclass sk) (conj ks sk))
      ks)))

(defn- call-error-handler [default-handler handlers error request]
  (let [{:keys [type] :as data} (ex-data error)
        type (or (get ex/mapped-exception-types type) type)
        ex-class (class error)
        error-handler (or (get handlers type)
                          (get handlers ex-class)
                          (some
                            (partial get handlers)
                            (super-classes ex-class))
                          default-handler)]
    (try
      (error-handler error (assoc data :type type) request)
      (catch ArityException _
        (logging/log! :warn "Error-handler arity has been changed.")
        (error-handler error)))))

(defn wrap-exceptions
  "Catches all exceptions and delegates to correct error handler according to :type of Exceptions
  - **:handlers** - a map from exception type to handler
    - **:compojure.api.exception/default** - Handler used when exception type doesn't match other handler,
                                             by default prints stack trace."
  [handler {:keys [handlers]}]
  (let [default-handler (get handlers ::ex/default ex/safe-handler)
        rethrow-or-respond (fn [e request respond raise]
                             ;; FIXME: Used for validate
                             (if (rethrow-exceptions? request)
                               (raise e)
                               (respond (call-error-handler default-handler handlers e request))))]
    (assert (fn? default-handler) "Default exception handler must be a function.")
    (fn
      ([request]
       (try
         (handler request)
         (catch Throwable e
           (rethrow-or-respond e request identity #(throw %)))))
      ([request respond raise]
       (try
         (handler request respond (fn [e] (rethrow-or-respond e request respond raise)))
         (catch Throwable e
           (rethrow-or-respond e request respond raise)))))))

;;
;; Component integration
;;

(defn wrap-components
  "Assoc given components to the request."
  [handler components]
  (fn
    ([req]
     (handler (assoc req ::components components)))
    ([req respond raise]
     (handler (assoc req ::components components) respond raise))))

(defn get-components [req]
  (::components req))

;;
;; Options
;;

(defn wrap-options
  "Injects compojure-api options into the request."
  [handler options]
  (fn
    ([request]
     (handler (update-in request [::options] merge options)))
    ([request respond raise]
     (handler (update-in request [::options] merge options) respond raise))))

(defn get-options
  "Extracts compojure-api options from the request."
  [request]
  (::options request))

;;
;; coercion
;;

(def default-coercion :schema)

(defn coercion [request]
  (let [options (get-options request)]
    (if-let [entry (find options :coercion)]
      (.val ^IMapEntry entry)
      default-coercion)))

(def coercion-request-ks [::options :coercion])

(defn wrap-coercion [handler coercion]
  (fn
    ([request]
     (handler (assoc-in request coercion-request-ks coercion)))
    ([request respond raise]
     (handler (assoc-in request coercion-request-ks coercion) respond raise))))

;;
;; Muuntaja
;;

(defn encode?
  "Returns true if the response body is serializable: body is a
  collection or response has key :compojure.api.meta/serializable?"
  [_ response]
  (or (:compojure.api.meta/serializable? response)
      (coll? (:body response))))

(def muuntaja-options
  (-> m/default-options
      (yaml-format/with-yaml-format)
      (msgpack-format/with-msgpack-format)))

(defn create-muuntaja
  ([]
   (create-muuntaja muuntaja-options))
  ([muuntaja-or-options]
   (if muuntaja-or-options
     (if (instance? Muuntaja muuntaja-or-options)
       (assoc muuntaja-or-options :encode-response-body? encode?)
       (m/create
         (-> muuntaja-or-options
             (assoc-in [:http :encode-response-body?] encode?)))))))

;;
;; middleware
;;

(defn middleware-fn [middleware]
  (if (vector? middleware)
    (let [[f & arguments] middleware]
      #(apply f % arguments))
    middleware))

(defn compose-middleware [middleware]
  (->> middleware
       (keep identity)
       (map middleware-fn)
       (apply comp identity)))

;;
;; Api Middleware
;;

(def api-middleware-defaults
  {:formats muuntaja-options
   :exceptions {:handlers {::ex/request-validation ex/request-validation-handler
                           ::ex/request-parsing ex/request-parsing-handler
                           ::ex/response-validation ex/response-validation-handler
                           ::ex/default ex/safe-handler}}
   :middleware nil
   :coercion default-coercion
   :ring-swagger nil})

(defn api-middleware-options [options]
  (-> (rsc/deep-merge api-middleware-defaults options)
      ;; [:formats :formats] can't be deep merged, else defaults always enables all the
      ;; formats. Figure out this or use meta-merge?
      (assoc-in [:formats :formats] (or (:formats (:formats options))
                                        (:formats (:formats api-middleware-defaults))))))

;; TODO: test all options! (https://github.com/metosin/compojure-api/issues/137)
(defn api-middleware
  "Opinionated chain of middlewares for web apis. Takes optional options-map.

  ### Exception handlers

  An error handler is a function of exception, ex-data and request to response.

  When defining these options, it is suggested to use alias for the exceptions namespace,
  e.g. `[compojure.api.exception :as ex]`.

  Default:

      {::ex/request-validation  ex/request-validation-handler
       ::ex/request-parsing     ex/request-parsing-handler
       ::ex/response-validation ex/response-validation-handler
       ::ex/default             ex/safe-handler}

  Note: Because the handlers are merged into default handlers map, to disable default handler you
  need to provide `nil` value as handler.

  Note: To catch Schema errors use `{:schema.core/error ex/schema-error-handler}`.

  ### Options

  - **:exceptions**                for *compojure.api.middleware/wrap-exceptions* (nil to unmount it)
      - **:handlers**                Map of error handlers for different exception types, type refers to `:type` key in ExceptionInfo data.

  - **:formats**                   for Muuntaja middleware. Value can be a valid muuntaja options-map,
                                   a Muuntaja instance or nil (to unmount it). See
                                   https://github.com/metosin/muuntaja/wiki/Configuration for details.

  - **:middleware**                vector of extra middleware to be applied last (just before the handler).

  - **:ring-swagger**              options for ring-swagger's swagger-json method.
                                   e.g. `{:ignore-missing-mappings? true}`

  - **:coercion**                  A function from request->type->coercion-matcher, used
                                   in endpoint coercion for types :body, :string and :response.
                                   Defaults to `compojure.api.middleware/default-coercion`
                                   Setting value to nil disables all coercion.

  - **:components**                Components which should be accessible to handlers using
                                   :components restructuring. (If you are using api,
                                   you might want to take look at using wrap-components
                                   middleware manually.). Defaults to nil (middleware not mounted)."
  ([handler]
   (api-middleware handler nil))
  ([handler options]
   (let [options (api-middleware-options options)
         {:keys [exceptions components formats middleware]} options
         muuntaja (create-muuntaja formats)]

     ;; 1.2.0+
     (assert (not (map? (:format options)))
             (str "ERROR: Option [:format] is not used with 1.2.0 or later. Compojure-api uses now Muuntaja insted of"
                  "ring-middleware-format and the new formatting options for it should be under [:formats]. See "
                  "'(doc compojure.api.middleware/api-middleware)' for more details."))

     (-> handler
         (cond-> middleware ((compose-middleware middleware)))
         (cond-> components (wrap-components components))
         ring.middleware.http-response/wrap-http-response
         (cond-> muuntaja (rsm/wrap-swagger-data (select-keys muuntaja [:consumes :produces])))
         (wrap-options (select-keys options [:ring-swagger :coercion]))
         (cond-> muuntaja (muuntaja.middleware/wrap-params))
         (cond-> muuntaja (muuntaja.middleware/wrap-format-request muuntaja))
         (cond-> exceptions (wrap-exceptions exceptions))
         (cond-> muuntaja (muuntaja.middleware/wrap-format-response muuntaja))
         (cond-> muuntaja (muuntaja.middleware/wrap-format-negotiate muuntaja))
         wrap-keyword-params
         wrap-nested-params
         wrap-params))))
