(ns clojure-lsp.feature.semantic-tokens
  (:require
   [clojure-lsp.parser :as parser]
   [clojure-lsp.queries :as q]
   [clojure.string :as string]
   [rewrite-clj.node :as n]
   [rewrite-clj.zip :as z])
  (:import
   [clojure.lang PersistentVector]))

(set! *warn-on-reflection* true)

(def token-types
  [:namespace
   :type
   :function
   :macro
   :keyword
   :class
   :variable
   :method
   :event
   :interface
   :comment])

(def token-types-str
  (->> token-types
       (map name)
       vec))

(def token-modifiers
  [:definition
   :defaultLibrary
   :implementation])

(def token-modifiers-str
  (->> token-modifiers
       (map name)
       vec))

(defn ^:private rpad-seq [s n x]
  (take n (concat s (repeat x))))

(defn ^:private token-modifiers->decimal-bit
  [modifiers]
  (as-> token-modifiers $
    (mapv #(if (contains? (set modifiers) %) 1 0) $)
    (rpad-seq $ 8 "0")
    (reverse $)
    (string/join "" $)
    (Integer/parseInt $ 2)))

(defn ^:private decimal-bit->token-modifiers
  [decimal-bit]
  (->> decimal-bit
       Integer/toBinaryString
       reverse
       (map-indexed #(when (= \1 %2) (nth token-modifiers %1)))
       (remove nil?)
       vec))

(defn ^:private element-inside-range?
  [{element-row :name-row element-end-row :name-end-row}
   {:keys [name-row name-end-row]}]
  (and (>= ^Long element-row ^Long name-row)
       (<= ^Long element-end-row ^Long name-end-row)))

(defn ^:private element->absolute-token
  ([element token-type]
   (element->absolute-token element token-type []))
  ([{:keys [name-row name-col name-end-col]}
    token-type
    token-modifier]
   [(dec ^Long name-row)
    (dec ^Long name-col)
    (- ^Long name-end-col ^Long name-col)
    (.indexOf ^PersistentVector token-types token-type)
    (token-modifiers->decimal-bit token-modifier)]))

(defn ^:private java-class-element->absolute-tokens
  [element]
  (cond
    ;; TODO Color only until /
    ;; clj-kondo needs to return more info to handle File/createTempFile and java.io.File/createTempFile
    ;;
    ;; (string/includes? class "/")
    ;; (let [slash (string/index-of class "/")
    ;;       class-pos (assoc element :name-end-col slash)]
    ;;   [(element->absolute-token class-pos :class)])

    :else
    [(element->absolute-token element :class)]))

(defn ^:private var-definition-element->absolute-tokens
  [{:keys [protocol-ns] :as element}]
  (let [defined-bys (q/defined-bys element)]
    (cond

      (and (not protocol-ns)
           (some #{'clojure.core/defprotocol
                   'clojure.core/definterface} defined-bys))
      [(element->absolute-token element :interface [])]

      (seq defined-bys)
      [(element->absolute-token element :function [:definition])]

      :else
      nil)))

(defn ^:private var-usage-element->absolute-tokens
  [{:keys [name alias macro name-col to full-qualified-symbol?] :as element}]
  (let [name-str ^String (str name)]
    (cond
      (and macro
           (not alias))
      [(element->absolute-token element :macro)]

      (and macro
           alias)
      (let [slash (+ name-col (count (str alias)))
            alias-pos (assoc element :name-end-col slash)
            slash-pos (assoc element :name-col slash :name-end-col (inc slash))
            name-pos (assoc element :name-col (inc slash))]
        [(element->absolute-token alias-pos :type)
         (element->absolute-token slash-pos :event)
         (element->absolute-token name-pos :macro)])

      (or alias
          full-qualified-symbol?)
      (let [slash (+ name-col (count (str (or alias to))))
            slash-pos (assoc element :name-col slash :name-end-col (inc slash))
            alias-pos (assoc element :name-end-col slash)
            name-pos (assoc element :name-col (inc slash))]
        [(element->absolute-token alias-pos :type)
         (element->absolute-token slash-pos :event)
         (element->absolute-token name-pos :function)])

      (identical? :clj-kondo/unknown-namespace to)
      nil

      (and (string/starts-with? name-str "*")
           (string/ends-with? name-str "*")
           (> (count name-str) 2))
      [(element->absolute-token element :variable [:defaultLibrary])]

      :else
      [(element->absolute-token element :function)])))

(defn ^:private keywords->absolute-tokens
  [{:keys [ns alias name-col auto-resolved namespace-from-prefix] :as element}]
  (cond
    (and ns
         (or (not auto-resolved)
             alias)
         (not namespace-from-prefix))
    (let [slash (+ (if alias 2 1) name-col (count (str (or alias ns))))
          ns-pos (-> element
                     (assoc :name-end-col slash)
                     (assoc :name-col (+ (if alias 2 1) (:name-col element))))
          slash-pos (assoc element :name-col slash :name-end-col (inc slash))
          name-pos (assoc element :name-col (inc slash))]
      [(element->absolute-token ns-pos :type)
       (element->absolute-token slash-pos :event)
       (element->absolute-token name-pos :keyword)])

    :else
    (as-> element $
      (assoc $ :name-col (if (and ns
                                  (not namespace-from-prefix))
                           (+ name-col 2)
                           (inc name-col)))
      [(element->absolute-token $ :keyword)])))

(defn ^:private elements->absolute-tokens
  [elements]
  (->> elements
       (sort-by (juxt :name-row :name-col))
       (keep
         (fn [{:keys [bucket] :as element}]
           (cond
             (identical? :var-usages bucket)
             (var-usage-element->absolute-tokens element)

             (identical? :var-definitions bucket)
             (var-definition-element->absolute-tokens element)

             (#{:locals :local-usages} bucket)
             [(element->absolute-token element :variable)]

             (identical? :namespace-definitions bucket)
             [(element->absolute-token element :namespace)]

             (and (contains? #{:keyword-definitions :keyword-usages} bucket)
                  (not (:str element))
                  (not (:keys-destructuring element)))
             (keywords->absolute-tokens element)

             (#{:java-class-definitions
                :java-class-usages} bucket)
             (java-class-element->absolute-tokens element)

             (identical? :instance-invocations bucket)
             [(element->absolute-token element :method)]

             (identical? :protocol-impls bucket)
             [(element->absolute-token element :method [:implementation])])))
       (mapcat identity)))

(defn ^:private absolute-token->relative-token
  [[[previous-row previous-col _ _ _]
    [row col length token-type token-modifier :as token]]]
  (cond
    (nil? previous-row)
    token

    (= ^Long previous-row ^Long row)
    [0
     (- ^Long col ^Long previous-col)
     length
     token-type
     token-modifier]

    :else
    [(- ^Long row ^Long previous-row)
     col
     length
     token-type
     token-modifier]))

(defn ^:private absolute-tokens->relative-tokens [absolute-tokens]
  (->> absolute-tokens
       (cons nil)
       (partition 2 1)
       (mapcat absolute-token->relative-token)
       doall))

(defn ^:private node->absolute-token [token-type node]
  (let [{:keys [row col]} (meta node)]
    [(dec ^Long row)
     (dec ^Long col)
     (n/length node)
     (.indexOf ^PersistentVector token-types token-type)
     0]))

(defn ^:private rewrite-clj-tokens*
  [uri db]
  (when (string/includes? (or (get-in db [:documents uri :text]) "") "#_")
    (let [zloc (parser/safe-zloc-of-file db uri)
          uneval-nodes (loop [zloc zloc
                              nodes []]
                         (if-let [uneval-zloc (z/find-tag zloc z/next :uneval)]
                           (recur (z/right uneval-zloc)
                                  (conj nodes (z/node uneval-zloc)))
                           nodes))]
      (map (partial node->absolute-token :comment) uneval-nodes))))

(defn full-tokens [uri db]
  (let [buckets (get-in db [:analysis uri])
        kondo-tokens (->> buckets (mapcat val) elements->absolute-tokens)
        rewrite-clj-tokens (rewrite-clj-tokens* uri db)
        tokens (sort-by (juxt first second)
                        (concat kondo-tokens rewrite-clj-tokens))]
    (absolute-tokens->relative-tokens tokens)))

(defn range-tokens
  [uri range db]
  (let [buckets (get-in db [:analysis uri])]
    (->> buckets
         (mapcat val)
         (filter #(element-inside-range? % range))
         elements->absolute-tokens
         absolute-tokens->relative-tokens)))

(defn element->token-type [element]
  (->> [element]
       elements->absolute-tokens
       (mapv (fn [[_ _ _ type modifier]]
               {:token-type (nth token-types type type)
                :token-modifier (decimal-bit->token-modifiers modifier)}))))
