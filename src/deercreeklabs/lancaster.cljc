(ns deercreeklabs.lancaster
  (:require
   [camel-snake-kebab.core :as csk]
   [deercreeklabs.lancaster.utils :as u]
   [taoensso.timbre :as timbre :refer [debugf errorf infof]])
  #?(:cljs
     (:require-macros
      deercreeklabs.lancaster)))

#?(:cljs
   (set! *warn-on-infer* true))

(defn make-default-record [schema]
  (let [add-field (fn [acc {:keys [type name default]}]
                    (let [avro-type (u/get-avro-type type)
                          val (if (= :record avro-type)
                                (make-default-record type)
                                default)]
                      (assoc acc name val)))]
    (reduce add-field {} (:fields schema))))

(defn make-default-enum [enum-schema field-default]
  (let [sym (or field-default
                (first (:symbols enum-schema)))]
    (-> (name sym)
        (csk/->SCREAMING_SNAKE_CASE))))

(defn get-field-default [field-schema field-default]
  (let [avro-type (u/get-avro-type field-schema)]
    (if (= :enum avro-type)
      (make-default-enum field-schema field-default)
      (or field-default
          (case avro-type
            :null nil
            :boolean false
            :int (int -1)
            :long -1
            :float (float -1.0)
            :double (double -1.0)
            :bytes ""
            :string ""
            :array []
            :map {}
            :fixed ""
            :union (first field-schema)
            :record (make-default-record field-schema))))))

(defn drop-schema-from-name [s]
  (-> (name s)
      (clojure.string/split #"-schema")
      (first)))

(defn make-named-schema
  [schema-ns schema-name]
  (let [avro-name (csk/->PascalCase (name schema-name))
        schema (vary-meta
                 {:namespace nil ;; declare this now to preserve key order
                  :name avro-name}
                 assoc :avro-schema true)]
    (if schema-ns
      (assoc schema :namespace (namespace-munge (name schema-ns)))
      (dissoc schema :namespace))))

(defn avro-rec
  ([schema-name fields]
   (avro-rec nil schema-name fields))
  ([schema-ns schema-name fields]
   (let [make-field (fn [[field-name field-type field-default]]
                      {:name (csk/->camelCase (name field-name))
                       :type (u/get-schema-name field-type)
                       :default (get-field-default field-type field-default)})]
     (-> (make-named-schema schema-ns schema-name)
         (assoc :type :record)
         (assoc :fields (mapv make-field fields))))))

(defn avro-enum
  ([schema-name symbols]
   (avro-enum nil schema-name symbols))
  ([schema-ns schema-name symbols]
   (let [make-enum-symbol (fn [sym]
                            (-> (name sym)
                                (csk/->SCREAMING_SNAKE_CASE)))]
     (-> (make-named-schema schema-ns schema-name)
         (assoc :type :enum)
         (assoc :symbols (mapv make-enum-symbol symbols))))))

(defn avro-fixed
  ([schema-name size]
   (avro-fixed nil schema-name size))
  ([schema-ns schema-name size]
   (-> (make-named-schema schema-ns schema-name)
       (assoc :type :fixed)
       (assoc :size size))))

(defn avro-union [elements]
  (mapv u/get-schema-name elements))

;;;;;;;;;;;;;;;;;;;; Macros ;;;;;;;;;;;;;;;;;;;;
(defmacro def-avro-named-schema
  [schema-fn schema-name args]
  (let [name* (drop-schema-from-name schema-name)
        args* (if (sequential? args)
                (vec args)
                args)]
    `(def ~(vary-meta schema-name assoc :avro-schema true)
       (let [ns# (.getName *ns*)]
         (~schema-fn ns# ~name* ~args*)))))

(defmacro def-avro-rec
  [schema-name & fields]
  `(def-avro-named-schema avro-rec ~schema-name ~fields))

(defmacro def-avro-enum
  [schema-name & symbols]
  `(def-avro-named-schema avro-enum ~schema-name ~symbols))

(defmacro def-avro-fixed
  [schema-name size]
  `(def-avro-named-schema avro-fixed ~schema-name ~size))

(defmacro def-avro-union
  [schema-name & elements]
  `(def ~(vary-meta schema-name assoc :avro-schema true)
     (avro-union [~@elements])))
