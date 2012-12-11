;; Copyright 2012 BigML
;; Licensed under the Apache License, Version 2.0
;; http://www.apache.org/licenses/LICENSE-2.0

(ns bigml.api.core
  (:require (clj-http [client :as client]))
  (:refer-clojure :exclude [get list]))

(def ^:dynamic *username* nil)
(def ^:dynamic *api-key* nil)
(def ^:dynamic *dev-mode* nil)

(def ^:private api-version "andromeda")
(def ^:private api-base "https://bigml.io")

(def auth-params
  "Parameters used for authentication with the BigML API."
  #{:username :api_key})

(def conn-params
  "Parameters used for connecting with the BigML API."
  (conj auth-params :dev_mode))

(defn query-params
  "Transforms a list of parameters into a map of query parameters
   including the connection specific keys (:username, :api_key,
   and :dev_mode)."
  [& {:keys [username api_key dev_mode] :as params}]
  (let [env (System/getenv)
        username (or username *username* (clojure.core/get env "BIGML_USERNAME"))
        api_key (or api_key *api-key* (clojure.core/get env "BIGML_API_KEY"))
        dev_mode (or dev_mode *dev-mode* (clojure.core/get env "BIGML_DEV_MODE"))]
    (if (and username api_key)
      (assoc params :username username :api_key api_key :dev_mode dev_mode)
      (throw (Exception. "No authentication defined.")))))

(defn location
  "Returns the resource location."
  [resource]
  (if (map? resource) (:resource resource) resource))

(defn- resource-base [dev-mode]
  (str api-base "/" (if dev-mode "dev/" "") api-version "/"))

(defn- resource-type-url [resource-type dev-mode]
  (str (resource-base dev-mode) (name resource-type)))

(defn- resource-url [resource dev-mode]
  (str (resource-base dev-mode) (location resource)))

(defn create
  "Create a resource given a resouce-type, a boolean for development
   mode, and a map of parameters formatted for a clj-http POST.  It's
   recommended to use the more friendly create functions in the
   source, dataset, model, evaluation, and prediction namespaces."
  [resource-type dev-mode params]
  (let [params (assoc params :as :json)
        {:keys [body status]}
        (client/post (resource-type-url resource-type dev-mode) params)]
    (with-meta body {:http-status status})))

(defn list
  "Retrieves a list of the desired resource type."
  [resource-type & params]
  (let [params (apply query-params params)
        {:keys [status body]}
        (client/get (resource-type-url resource-type (:dev_mode params))
                    {:query-params (dissoc params :dev_mode)
                     :as :json})]
    (let [{:keys [meta objects]} body]
      (with-meta objects (assoc meta :http-status status)))))

(defn update
  "Updates the specified resource.  Returns the updated resource upon
   success."
  [resource updates & params]
  (let [params (apply query-params params)
        {:keys [status body]}
        (client/put (resource-url resource (:dev_mode params))
                    {:query-params (dissoc params :dev_mode)
                     :form-params updates
                     :content-type :json
                     :as :json})]
    (with-meta body {:http-status status})))

(defn delete
  "Deletes the specified resource.  Returns nil upon success."
  [resource & params]
  (let [params (apply query-params params)]
    (when (client/delete (resource-url resource (:dev_mode params))
                         {:query-params (dissoc params :dev_mode)}))))

(defn get
  "Retrieves a resource."
  [resource & params]
  (let [params (apply query-params params)
        {:keys [status body]}
        (client/get (resource-url resource (:dev_mode params))
                    {:query-params (dissoc params :dev_mode)
                     :as :json})]
    (with-meta body {:http-status status})))

(defn status-code
  "Return the status code of the resource as a keyword."
  [resource]
  ({0 :waiting 1 :queued 2 :started 3 :in-progress 4 :summarized
    5 :finished -1 :faulty -2 :unknown -3 :runnable}
   (:code (:status resource))))

(defn finished?
  "Returns true if the resource's status is finished."
  [resource]
  (= :finished (status-code resource)))

(defn final?
  "Returns true if the resource is final, meaning either finished or
  an error state (faulty, unknown, and runnable)."
  [resource]
  (#{:finished :faulty :unknown :runnable} (status-code resource)))

(def ^:private decay-rate 1.618)
(def ^:private init-wait 500)
(def ^:private max-wait 120000)

(defn get-final
  "Retries GETs to the resource until it is finalized."
  [resource & params]
  (loop [sleep-time init-wait]
    (let [result (apply get resource params)]
      (if (final? result)
        result
        (do (Thread/sleep (long sleep-time))
            (recur (min max-wait (* decay-rate sleep-time))))))))

(defn convert-inputs
  "Attempts to convert a sequence of inputs into an input map."
  [model inputs]
  (let [input-fields (or (:input_fields model)
                         (:input_fields (get-final model))
                         (throw (Exception. "Inaccessable model")))]
    (apply hash-map (flatten (map clojure.core/list input-fields inputs)))))
