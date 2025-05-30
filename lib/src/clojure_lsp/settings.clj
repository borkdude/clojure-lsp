(ns clojure-lsp.settings
  (:refer-clojure :exclude [get])
  (:require
   [clojure-lsp.config :as config]
   [clojure-lsp.shared :as shared]
   [clojure.core.memoize :as memoize]
   [clojure.string :as string]
   [clojure.walk :as walk]
   [medley.core :as medley]))

(set! *warn-on-reflection* true)

(defn- typify-json [root]
  (walk/postwalk (fn [n]
                   (if (string? n)
                     (keyword n)
                     n))
                 root))

(defn- clean-symbol-map [m]
  (->> (or m {})
       (medley/map-keys #(if (string/starts-with? % "#")
                           (re-pattern (subs % 1))
                           (symbol %)))
       (medley/map-vals typify-json)))

(defn- clean-keys-map [m]
  (->> (or m {})
       ;; (medley/map-keys keyword)
       (medley/map-vals typify-json)))

(defn parse-source-paths [paths]
  (when (seq paths)
    (->> paths
         (keep #(when (string? %)
                  (if (string/starts-with? % ":")
                    (subs % 1)
                    %)))
         (into #{})
         (not-empty))))

(defn kwd-string [s]
  (cond
    (keyword? s) s
    (and (string? s)
         (string/starts-with? s ":")) (keyword (subs s 1))
    (string? s) (keyword s)))

(defn parse-source-aliases [aliases]
  (when (seq aliases)
    (->> aliases
         (keep kwd-string)
         (into #{})
         (not-empty))))

(defn clean-client-settings [client-settings]
  (-> client-settings
      (update :dependency-scheme #(or % "zipfile"))
      (update :text-document-sync-kind kwd-string)
      (update :source-paths parse-source-paths)
      (update :source-aliases parse-source-aliases)
      (update :cljfmt-config-path #(or % ".cljfmt.edn"))
      (medley/update-existing-in [:cljfmt :indents] clean-symbol-map)
      (medley/update-existing-in [:linters] clean-keys-map)
      (update :document-formatting? (fnil identity true))
      (update :document-range-formatting? (fnil identity true))))

(defn ^:private get-refreshed-settings [project-root-uri settings force-settings kondo-config]
  (let [new-project-settings (config/resolve-for-root project-root-uri)]
    (-> (config/deep-merge-considering-settings settings
                                                new-project-settings
                                                force-settings)
        (config/with-legacy-linters-kondo-config kondo-config))))

(def ttl-threshold-milis 1000)

(def ^:private memoized-settings
  (memoize/ttl get-refreshed-settings :ttl/threshold ttl-threshold-milis))

(defn all
  "Get memoized settings from db.
  Refreshes settings if memoize threshold met."
  [{:keys [settings-auto-refresh? kondo-config env project-root-uri settings force-settings]}]
  (if (or (not settings-auto-refresh?)
          (#{:unit-test :api-test} env))
    (get-refreshed-settings project-root-uri settings force-settings kondo-config)
    (memoized-settings project-root-uri settings force-settings kondo-config)))

(defn get
  ([db kws]
   (get db kws nil))
  ([db kws default]
   (get-in (all db) kws default)))

(defn set-all
  [db* settings]
  (let [db (deref db*)
        old-settings (:settings db)
        new-settings (shared/deep-merge old-settings settings)]
    (swap! db* assoc :settings new-settings)))
