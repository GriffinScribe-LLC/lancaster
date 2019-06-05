(ns deercreeklabs.unit.lt-test
  (:require
   [clojure.test :refer [deftest is use-fixtures]]
   [deercreeklabs.baracus :as ba]
   [deercreeklabs.lancaster :as l]
   [deercreeklabs.lancaster.bilt :as bilt]
   [deercreeklabs.lancaster.utils :as u]
   [deercreeklabs.unit.lancaster-test :as lt]
   [schema.core :as s :include-macros true])
  #?(:clj
     (:import
      (clojure.lang ExceptionInfo))))

;; Use this instead of fixtures, which are hard to make work w/ async testing.
(s/set-fn-validation! true)

(def im-schema (bilt/int-map-schema l/string-schema))
(def sku-to-qty-schema (bilt/int-map-schema l/int-schema))
(def sku-to-qty-v2-schema (bilt/int-map-schema l/long-schema))
(def fm-schema (bilt/fixed-map-schema  16 l/string-schema))

(l/def-union-schema im-or-fm-schema
  im-schema
  fm-schema)

(deftest test-int-map-schema
  (is (= {:name :deercreeklabs.lancaster.bilt/int-to-int-flex-map
          :type :record
          :fields [{:default [], :name :ks, :type {:items :int, :type :array}}
                   {:default [], :name :vs, :type {:items :int, :type :array}}]
          :key-ns-type :none}
         (u/strip-lt-attrs (l/edn sku-to-qty-schema))))
  #?(:clj (is (lt/fp-matches? sku-to-qty-schema)))
  (is (= (str
          "{\"name\":\"deercreeklabs.lancaster.bilt.IntToIntFlexMap\",\"type\":"
          "\"record\",\"fields\":[{\"name\":\"ks\",\"type\":{\"type\":\"array\""
          ",\"items\":\"int\"}},{\"name\":\"vs\",\"type\":{\"type\":\"array\","
          "\"items\":\"int\"}}]}")
         (l/pcf sku-to-qty-schema)))
  (is (= (str
          "{\"name\":\"deercreeklabs.lancaster.bilt.IntToIntFlexMap\","
          "\"fields\":[{\"name\":\"ks\",\"type\":{\"type\":\"array\","
          "\"items\":\"int\"},\"default\":[]},{\"name\":\"vs\",\"type\":"
          "{\"type\":\"array\",\"items\":\"int\"},\"default\":[]}],\"type\":"
          "\"record\",\"logicalType\":\"int-map\"}")
         (l/json sku-to-qty-schema)))
  (is (= "1697957785636113360"
         (u/long->str (l/fingerprint64 sku-to-qty-schema)))))

(deftest test-embedded-int-map-pcf
  (let [fms (bilt/int-map-schema l/int-schema)
        rs (l/record-schema :r [[:fm fms]])]
    (is (= (str
            "{\"name\":\"R\",\"type\":\"record\",\"fields\":[{\"name\":\"fm\","
            "\"type\":{\"name\":\"deercreeklabs.lancaster.bilt.IntToIntFlexMap"
            "\",\"type\":\"record\",\"fields\":[{\"name\":\"ks\",\"type\":"
            "{\"type\":\"array\",\"items\":\"int\"}},{\"name\":\"vs\",\"type\":"
            "{\"type\":\"array\",\"items\":\"int\"}}]}}]}")
           (l/pcf rs)))))

(deftest test-int-map-schema-serdes
  (let [data {123 10
              456 100
              789 2}
        encoded (l/serialize sku-to-qty-schema data)
        decoded (l/deserialize-same sku-to-qty-schema encoded)]
    (is (ba/equivalent-byte-arrays?
         (ba/byte-array [6 -10 1 -112 7 -86 12 0 6 20 -56 1 4 0])
         encoded))
    (is (= data decoded))))

(deftest test-maybe-int-map
  (let [int-map-schema (bilt/int-map-schema l/int-schema)
        maybe-schema (l/maybe int-map-schema)
        data1 {1 1}
        data2 nil]
    (is (lt/round-trip? maybe-schema data1))
    (is (lt/round-trip? maybe-schema data2))))

(deftest test-bad-fixed-map-size
  (is (thrown-with-msg?
       #?(:clj ExceptionInfo :cljs js/Error)
       #"Second argument to fixed-map-schema must be a positive integer"
       (bilt/fixed-map-schema -1 l/string-schema))))

(deftest test-int-map-evolution
  (let [data {123 10
              456 100
              789 2}
        encoded (l/serialize sku-to-qty-schema data)
        decoded (l/deserialize sku-to-qty-v2-schema
                               sku-to-qty-schema encoded)]
    (is (= data decoded))))

(deftest test-schema-at-path-int-map
  (let [path [1]
        ret (-> (l/schema-at-path sku-to-qty-schema path)
                (u/edn-schema)
                (u/edn-schema->name-kw))]
    (is (= :int ret))))

(deftest test-schema-at-path-int-map-bad-key
  (is (thrown-with-msg?
       #?(:clj ExceptionInfo :cljs js/Error)
       #"Key `a` is not a valid key for logical type `int-map`"
       (l/schema-at-path sku-to-qty-schema ["a"]))))

(deftest test-schema-at-path-int-map-empty-path
  (let [path []
        ret (-> (l/schema-at-path sku-to-qty-schema path)
                (u/edn-schema)
                (u/edn-schema->name-kw))]
    (is (= :deercreeklabs.lancaster.bilt/int-to-int-flex-map ret))))

(deftest test-sub-schemas-int-map
  (let [ret (->> (l/sub-schemas sku-to-qty-v2-schema)
                 (map u/edn-schema)
                 (map u/edn-schema->name-kw)
                 (set))
        expected #{:deercreeklabs.lancaster.bilt/int-to-int-flex-map
                   :int :long}]
    (is (= expected ret))))

(deftest test-flex-map-union
  (let [data1 {(ba/byte-array (range 16)) "name1"}
        data2 {1 "str2"}
        enc1 (l/serialize im-or-fm-schema data1)
        enc2 (l/serialize im-or-fm-schema data2)
        rt1 (l/deserialize-same im-or-fm-schema enc1)
        rt2 (l/deserialize-same im-or-fm-schema enc2)]
    (is (ba/equivalent-byte-arrays? (ffirst data1) (ffirst rt1)))
    (is (= (nfirst data1) (nfirst rt1)))
    (is (= data2 rt2))))

(deftest test-keyword-schema
  (let [data1 :a-simple-kw
        data2 ::a-namespaced-kw]
    (is (lt/round-trip? bilt/keyword-schema data1))
    (is (lt/round-trip? bilt/keyword-schema data2))))
