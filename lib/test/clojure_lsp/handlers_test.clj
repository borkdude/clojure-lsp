(ns clojure-lsp.handlers-test
  (:require
   [clojure-lsp.feature.clean-ns :as f.clean-ns]
   [clojure-lsp.feature.file-management :as f.file-management]
   [clojure-lsp.handlers :as handlers]
   [clojure-lsp.kondo :as lsp.kondo]
   [clojure-lsp.test-helper.internal :as h]
   [clojure.core.async :as async]
   [clojure.test :refer [deftest is testing]]
   [rewrite-clj.zip :as z]))

(def dummy-empty-file "")
(def project-uri (h/file-uri "file:///user/project"))
(def project-source-paths #{(h/file-path "/user/project/src")})

(h/reset-components-before-test)

(defn- run-clean-ns-and-check-result
  ([input-code expected-code]
   (run-clean-ns-and-check-result input-code expected-code true))
  ([input-code expected-code in-form]
   (run-clean-ns-and-check-result input-code expected-code in-form "file:///a.clj"))
  ([input-code expected-code in-form uri]
   (h/load-code-and-locs input-code (h/file-uri uri))
   (let [zloc (when in-form
                (-> (z/of-string input-code) z/down z/right z/right))
         [{:keys [loc range]}] (f.clean-ns/clean-ns-edits zloc (h/file-uri uri) (h/db))]
     (is (some? range))
     (is (= expected-code
            (z/root-string loc))))))

(deftest initialize
  (testing "detects URI format with lower-case drive letter and encoded colons"
    (h/reset-components!)
    (with-redefs [lsp.kondo/config-hash (constantly "123")]
      (handlers/initialize (h/components) "file:///c%3A/project/root" {} {} nil nil))
    (is (= {:encode-colons-in-path?   true
            :upper-case-drive-letter? false}
           (get-in (h/db) [:settings :uri-format]))))
  (testing "detects URI format with upper-case drive letter and non-encoded colons"
    (h/reset-components!)
    (with-redefs [lsp.kondo/config-hash (constantly "123")]
      (handlers/initialize (h/components) "file:///C:/project/root" {} {} nil nil))
    (is (= {:encode-colons-in-path?   false
            :upper-case-drive-letter? true}
           (get-in (h/db) [:settings :uri-format])))))

(deftest did-open
  (testing "opening a existing file"
    (h/reset-components!)
    (let [mock-diagnostics-chan (async/chan 1)]
      (h/load-code-and-locs "(ns a) (when)" h/default-uri (assoc (h/components)
                                                                 :diagnostics-chan mock-diagnostics-chan))
      (is (some? (get-in (h/db) [:analysis (h/file-uri "file:///a.clj")])))
      (let [{:keys [uri diagnostics]} (h/take-or-timeout mock-diagnostics-chan 500)]
        (is (= (h/file-uri "file:///a.clj") uri))
        (h/assert-submaps
          [{:code "missing-body-in-when"}
           {:code "invalid-arity"}]
          diagnostics))))
  (testing "opening a new clojure file adding the ns"
    (h/reset-components!)
    (swap! (h/db*) merge {:settings {:auto-add-ns-to-new-files? true
                                     :source-paths #{(h/file-path "/project/src")}}
                          :client-capabilities {:workspace {:workspace-edit {:document-changes true}}}
                          :project-root-uri (h/file-uri "file:///project")})
    (let [mock-edits-chan (async/chan 1)]
      (h/load-code-and-locs "" (h/file-uri "file:///project/src/foo/bar.clj") (assoc (h/components)
                                                                                     :edits-chan mock-edits-chan))
      (h/assert-submaps
        [{:edits [{:range {:start {:line 0, :character 0}
                           :end {:line 999998, :character 999998}}
                   :new-text "(ns foo.bar)"}]}]
        (:document-changes (h/take-or-timeout mock-edits-chan 500)))
      (is (some? (get-in (h/db) [:analysis (h/file-uri "file:///project/src/foo/bar.clj")])))))
  (testing "opening a new edn file not adding the ns"
    (h/reset-components!)
    (swap! (h/db*) merge {:settings {:auto-add-ns-to-new-files? true
                                     :source-paths #{(h/file-path "/project/src")}}
                          :client-capabilities {:workspace {:workspace-edit {:document-changes true}}}
                          :project-root-uri (h/file-uri "file:///project")})
    (let [mock-edits-chan (async/chan 1)]
      (h/load-code-and-locs "" (h/file-uri "file:///project/src/foo/baz.edn") (assoc (h/components)
                                                                                     :edits-chan mock-edits-chan))
      (h/assert-no-take mock-edits-chan 500)
      (is (some? (get-in (h/db) [:analysis (h/file-uri "file:///project/src/foo/baz.edn")]))))))

(deftest document-symbol
  (let [code "(ns a) (def bar ::bar) (def ^:m baz 1) (defmulti mult identity) (defmethod mult \"foo\")"
        result [{:name "a"
                 :kind :namespace
                 :range {:start {:line 0, :character 0}, :end {:line 0, :character 6}}
                 :selection-range {:start {:line 0, :character 4}, :end {:line 0, :character 5}}}
                {:name "bar"
                 :kind :variable
                 :range {:start {:line 0 :character 7} :end {:line 0 :character 22}}
                 :selection-range {:start {:line 0 :character 12} :end {:line 0 :character 15}}
                 :tags []}
                {:name "baz"
                 :kind :variable
                 :range {:start {:line 0 :character 23} :end {:line 0 :character 38}}
                 :selection-range {:start {:line 0 :character 32} :end {:line 0 :character 35}}
                 :tags []}
                 ;; defmulti
                {:name "mult",
                 :kind :interface,
                 :range {:start {:line 0, :character 39}, :end {:line 0, :character 63}},
                 :selection-range {:start {:line 0, :character 49}, :end {:line 0, :character 53}}
                 :tags []}
                 ;; defmethod
                {:name "mult \"foo\"",
                 :kind :function,
                 :range {:start {:line 0, :character 75}, :end {:line 0, :character 79}},
                 :selection-range {:start {:line 0, :character 75}, :end {:line 0, :character 79}}
                 :tags []}]]
    (testing "clj files"
      (h/load-code-and-locs code)
      (h/assert-submaps result
                        (handlers/document-symbol (h/components)
                                                  {:text-document {:uri (h/file-uri "file:///a.clj")}})))
    (testing "cljs files"
      (h/load-code-and-locs code (h/file-uri "file:///b.cljs"))
      (h/assert-submaps result
                        (handlers/document-symbol (h/components)
                                                  {:text-document {:uri (h/file-uri "file:///b.cljs")}})))
    (testing "cljc files"
      (h/load-code-and-locs code (h/file-uri "file:///c.cljc"))
      (h/assert-submaps result
                        (handlers/document-symbol (h/components)
                                                  {:text-document {:uri (h/file-uri "file:///c.cljc")}})))))

(deftest document-highlight
  (let [[bar-start] (h/load-code-and-locs "(ns a) (def |bar ::bar) (def ^:m baz 1)")]
    (h/assert-submaps
      [{:range {:start {:line 0 :character 12} :end {:line 0 :character 15}}}]
      (handlers/document-highlight (h/components)
                                   {:text-document {:uri (h/file-uri "file:///a.clj")}
                                    :position (h/->position bar-start)}))))

(deftest references
  (testing "simple single reference"
    (let [[bar-def-pos] (h/load-code-and-locs "(ns a) (def |bar 1)")
          _ (h/load-code-and-locs "(ns b (:require [a :as foo])) (foo/bar)" (h/file-uri "file:///b.clj"))]
      (h/assert-submaps
        [{:uri (h/file-uri "file:///b.clj")
          :range {:start {:line 0 :character 31} :end {:line 0 :character 38}}}]
        (handlers/references (h/components)
                             {:text-document {:uri (h/file-uri "file:///a.clj")}
                              :position (h/->position bar-def-pos)}))))
  (testing "when including declaration"
    (let [[bar-def-pos] (h/load-code-and-locs "(ns a) (def |bar 1)")
          _ (h/load-code-and-locs "(ns b (:require [a :as foo])) (foo/bar)" (h/file-uri "file:///b.clj"))]
      (h/assert-submaps
        #{{:uri (h/file-uri "file:///a.clj")
           :range {:start {:line 0 :character 12} :end {:line 0 :character 15}}}
          {:uri (h/file-uri "file:///b.clj")
           :range {:start {:line 0 :character 31} :end {:line 0 :character 38}}}}
        (handlers/references (h/components)
                             {:text-document {:uri (h/file-uri "file:///a.clj")}
                              :position (h/->position bar-def-pos)
                              :context {:include-declaration true}})))))

(deftest test-rename
  (let [[abar-start abar-stop
         akw-start akwbar-start akwbar-stop
         abaz-start abaz-stop] (h/load-code-and-locs (h/code "(ns a.aa)"
                                                             "(def |bar| |::|bar|)"
                                                             "(def ^:m |baz| 1)") (h/file-uri "file:///a.clj"))
        [balias-start balias-stop
         ba1-start _ba1-stop
         bbar-start bbar-stop
         ba2-kw-start ba2-kw-stop] (h/load-code-and-locs (h/code "(ns b.bb (:require [a.aa :as |aa|]))"
                                                                 "(def x |aa|/|bar|)"
                                                                 "|::aa/bar|"
                                                                 ":aa/bar") (h/file-uri "file:///b.clj"))
        [cbar-start cbar-stop
         cbaz-start cbaz-stop] (h/load-code-and-locs (h/code "(ns c.cc (:require [a.aa :as aa]))"
                                                             "(def x aa/|bar|)"
                                                             "^:xab aa/|baz|") (h/file-uri "file:///c.clj"))
        [d-name-kw-start d-name-kw-stop] (h/load-code-and-locs (h/code "(ns d.dd)"
                                                                       "(def name |::name|)") (h/file-uri "file:///d.clj"))
        [kw-aliased-start kw-aliased-stop
         kw-unaliased-start kw-unaliased-stop] (h/load-code-and-locs (h/code "(ns e.ee (:require [d.dd :as dd]))"
                                                                             "(def name |::dd/name|)"
                                                                             "(def other-name |:d.dd/name|)") (h/file-uri "file:///e.clj"))
        [main-uname-kw-start main-uname-kw-end] (h/load-code-and-locs (h/code "(ns main (:require [user :as u]))"
                                                                              "(def name |::u/name|)") (h/file-uri "file:///main.cljc"))
        [uname-kw-start uname-kw-end] (h/load-code-and-locs (h/code "(ns user)"
                                                                    "(def name |::name|)") (h/file-uri "file:///user.cljc"))]
    (testing "on symbol without namespace"
      (let [changes (:changes (handlers/rename (h/components)
                                               {:text-document {:uri (h/file-uri "file:///a.clj")}
                                                :position (h/->position abar-start)
                                                :new-name "foo"}))]
        (is (= {(h/file-uri "file:///a.clj") [{:new-text "foo" :range (h/->range abar-start abar-stop)}]
                (h/file-uri "file:///b.clj") [{:new-text "foo" :range (h/->range bbar-start bbar-stop)}]
                (h/file-uri "file:///c.clj") [{:new-text "foo" :range (h/->range cbar-start cbar-stop)}]}
               changes))))
    (testing "on symbol with metadata namespace"
      (let [changes (:changes (handlers/rename (h/components)
                                               {:text-document {:uri (h/file-uri "file:///a.clj")}
                                                :position (h/->position abaz-start)
                                                :new-name "qux"}))]
        (is (= {(h/file-uri "file:///a.clj") [{:new-text "qux" :range (h/->range abaz-start abaz-stop)}]
                (h/file-uri "file:///c.clj") [{:new-text "qux" :range (h/->range cbaz-start cbaz-stop)}]}
               changes))))
    (testing "on symbol with namespace adds existing namespace"
      (let [changes (:changes (handlers/rename (h/components)
                                               {:text-document {:uri (h/file-uri "file:///b.clj")}
                                                :position (h/->position [(first bbar-start) (dec (second bbar-start))])
                                                :new-name "foo"}))]
        (is (= {(h/file-uri "file:///a.clj") [{:new-text "foo" :range (h/->range abar-start abar-stop)}]
                (h/file-uri "file:///b.clj") [{:new-text "foo" :range (h/->range bbar-start bbar-stop)}]
                (h/file-uri "file:///c.clj") [{:new-text "foo" :range (h/->range cbar-start cbar-stop)}]}
               changes))))
    (testing "on symbol with namespace removes passed-in namespace"
      (let [changes (:changes (handlers/rename (h/components)
                                               {:text-document {:uri (h/file-uri "file:///b.clj")}
                                                :position (h/->position bbar-start)
                                                :new-name "aa/foo"}))]
        (is (= {(h/file-uri "file:///a.clj") [{:new-text "foo" :range (h/->range abar-start abar-stop)}]
                (h/file-uri "file:///b.clj") [{:new-text "aa/foo" :range (h/->range ba1-start bbar-stop)}
                                              {:new-text "aa" :range (h/->range balias-start balias-stop)}]
                (h/file-uri "file:///c.clj") [{:new-text "foo" :range (h/->range cbar-start cbar-stop)}]}
               changes))))
    (testing "on ::keyword"
      (let [changes (:changes (handlers/rename (h/components)
                                               {:text-document {:uri (h/file-uri "file:///a.clj")}
                                                :position (h/->position akwbar-start)
                                                :new-name "::foo"}))]
        (is (= {(h/file-uri "file:///a.clj") [{:new-text "::foo" :range (h/->range akw-start akwbar-stop)}]
                (h/file-uri "file:///b.clj") [{:new-text "::aa/foo" :range (h/->range ba2-kw-start ba2-kw-stop)}]}
               changes))))
    (testing "on single-name-namespace'd keyword"
      (let [changes (:changes (handlers/rename (h/components)
                                               {:text-document {:uri (h/file-uri "file:///main.cljc")}
                                                :position (h/->position main-uname-kw-start)
                                                :new-name "::full-name"}))]
        (is (= {(h/file-uri "file:///main.cljc") [{:new-text "::u/full-name" :range (h/->range main-uname-kw-start main-uname-kw-end)}]
                (h/file-uri "file:///user.cljc") [{:new-text "::full-name" :range (h/->range uname-kw-start uname-kw-end)}]}
               changes))))
    (testing "on qualified keyword without alias"
      (let [changes (:changes (handlers/rename (h/components)
                                               {:text-document {:uri (h/file-uri "file:///d.clj")}
                                                :position (h/->position d-name-kw-start)
                                                :new-name "::other-name"}))]
        (is (= {(h/file-uri "file:///d.clj") [{:new-text "::other-name" :range (h/->range d-name-kw-start d-name-kw-stop)}]
                (h/file-uri "file:///e.clj") [{:new-text "::dd/other-name" :range (h/->range kw-aliased-start kw-aliased-stop)}
                                              {:new-text ":d.dd/other-name" :range (h/->range kw-unaliased-start kw-unaliased-stop)}]}
               changes))))
    (testing "on alias changes namespaces inside file"
      (let [changes (:changes (handlers/rename (h/components)
                                               {:text-document {:uri (h/file-uri "file:///b.clj")}
                                                :position (h/->position balias-start)
                                                :new-name "xx"}))]
        (is (= {(h/file-uri "file:///b.clj")
                [{:range (h/->range balias-start balias-stop) :new-text "xx"}
                 {:range (h/->range ba2-kw-start ba2-kw-stop) :new-text "::xx/bar"}
                 {:range (h/->range ba1-start bbar-stop) :new-text "xx/bar"}]}
               changes))))))

(deftest test-code-actions-handle
  (h/load-code-and-locs (str "(ns some-ns)\n"
                             "(def foo)")
                        (h/file-uri "file:///a.clj"))
  (h/load-code-and-locs (str "(ns other-ns (:require [some-ns :as sns]))\n"
                             "(def bar 1)\n"
                             "(defn baz []\n"
                             "  bar)")
                        (h/file-uri "file:///b.clj"))
  (h/load-code-and-locs (str "(ns another-ns)\n"
                             "(def bar ons/bar)\n"
                             "(def foo sns/foo)\n"
                             "(deftest some-test)\n"
                             "MyClass.\n"
                             "Date.")
                        (h/file-uri "file:///c.clj"))
  (testing "when it has unresolved-namespace and can find namespace"
    (is (some #(= (:title %) "Add require '[some-ns :as sns]' × 1")
              (handlers/code-actions
                (h/components)
                {:text-document {:uri (h/file-uri "file:///c.clj")}
                 :context {:diagnostics [{:code "unresolved-namespace"
                                          :range {:start {:line 2 :character 10}}}]}
                 :range {:start {:line 2 :character 10}}}))))
  (testing "without workspace edit client capability"
    (swap! (h/db*) merge {:client-capabilities {:workspace {:workspace-edit false}}})
    (is (not-any? #(= (:title %) "Clean namespace")
                  (handlers/code-actions
                    (h/components)
                    {:text-document {:uri (h/file-uri "file:///b.clj")}
                     :context {:diagnostics []}
                     :range {:start {:line 1 :character 1}}}))))
  (testing "with workspace edit client capability"
    (swap! (h/db*) merge {:client-capabilities {:workspace {:workspace-edit true}}})
    (is (some #(= (:title %) "Clean namespace")
              (handlers/code-actions
                (h/components)
                {:text-document {:uri (h/file-uri "file:///b.clj")}
                 :context {:diagnostics []}
                 :range {:start {:line 1 :character 1}}})))))

(deftest code-lens-can-be-resolved
  (h/load-code-and-locs (str "(ns some-ns)\n"
                             "(def foo 1)\n"
                             "(defn- foo2 []\n"
                             " foo)\n"
                             "(defn bar [a b]\n"
                             "  (+ a b (foo2)))\n"
                             "(s/defn baz []\n"
                             "  (bar 2 3))\n"))
  (let [code-lenses (handlers/code-lens (h/components) {:text-document {:uri h/default-uri}})
        resolved-code-lenses (map (fn [code-lens]
                                    (handlers/code-lens-resolve (h/components) code-lens))
                                  code-lenses)]
    (is (= [0 1 2 4]
           (map #(get-in % [:range :start :line])
                resolved-code-lenses)))
    (is (= ["0 references"
            "1 reference"
            "1 reference"
            "1 reference"]
           (map #(get-in % [:command :title])
                resolved-code-lenses)))))

(deftest server-info-raw
  (testing "returns kebab-case strings, to avoid camelCase conversion of keywords"
    (is (seq (get (handlers/server-info-raw (h/components)) "server-version")))))

(deftest cursor-info-raw
  (testing "returns kebab-case strings, to avoid camelCase conversion of keywords"
    (let [[row-and-col] (h/load-code-and-locs "(ns |a)")]
      (is (seq (get (handlers/cursor-info-raw (h/components)
                                              {:text-document {:uri h/default-uri}
                                               :position (h/->position row-and-col)})
                    "elements"))))))

(deftest change-settings
  (testing "Changing [:settings :clean :sort :require] setting while server is running"
    (h/reset-components!)

    ;; Set settings that don't sort the requires
    (handlers/did-change-configuration (h/components)
                                       {:client-capabilities {:workspace {:workspace-edit {:document-changes true}}}
                                        :clean {:sort {:require false}}})

    ;; Run run-clean-ns and verify that the requires isn't changed
    (run-clean-ns-and-check-result
      (h/code "(ns project.a"
              "  (:require"
              "   [a.a :as a]"
              "   [c.c :as c]"
              "   [b.b :as b]))"
              "(a/foo 1)"
              "(b/bar 2)"
              "(c/something 123)")
      (h/code "(ns project.a"
              "  (:require"
              "   [a.a :as a]"
              "   [c.c :as c]"
              "   [b.b :as b]))"
              "(a/foo 1)"
              "(b/bar 2)"
              "(c/something 123)")
      true
      "file:///project/a.clj")

    ;; Change settings to sort requires
    (handlers/did-change-configuration (h/components)
                                       {:clean {:sort {:require true}}})

    ;; Run run-clean-ns and verify that the requires was sorted
    (run-clean-ns-and-check-result
      (h/code "(ns project.a"
              "  (:require"
              "   [a.a :as a]"
              "   [c.c :as c]"
              "   [b.b :as b]))"
              "(a/foo 1)"
              "(b/bar 2)"
              "(c/something 123)")
      (h/code "(ns project.a"
              "  (:require"
              "   [a.a :as a]"
              "   [b.b :as b]"
              "   [c.c :as c]))"
              "(a/foo 1)"
              "(b/bar 2)"
              "(c/something 123)")
      true
      "file:///project/a.clj"))

  (testing "Changing [:settings :auto-add-ns-to-new-files?] setting while server is running"
    ;; Reset the (h/db)
    (h/reset-components!)

    ;; Loads a project on (h/db)
    (h/load-project project-uri project-source-paths)

    ;; Set settings that don't add ns to new files
    (handlers/did-change-configuration (h/components)
                                       {:client-capabilities {:workspace {:workspace-edit {:document-changes true}}}
                                        :auto-add-ns-to-new-files? false})

    (let [create-ns-changes (f.file-management/create-ns-changes (h/file-uri (str project-uri "/src/models/my_model.clj"))
                                                                 dummy-empty-file
                                                                 (h/db))]
      ;; Check if create-ns-changes returns nil because settings are set to not add ns to new files
      (is (nil? create-ns-changes)))

    ;; Set settings that do add ns to new files
    (handlers/did-change-configuration (h/components)
                                       {:client-capabilities {:workspace {:workspace-edit {:document-changes true}}}
                                        :auto-add-ns-to-new-files? true})

    (let [create-ns-changes (f.file-management/create-ns-changes (h/file-uri (str project-uri "/src/models/my_model.clj"))
                                                                 dummy-empty-file
                                                                 (h/db))]
      ;; Check if create-ns-changes is not null
      (is (not (nil? create-ns-changes)))

      ;; Check if the ns was added on the file
      (is (= "(ns models.my-model)"
             (-> create-ns-changes
                 :changes
                 (get (h/file-uri (str project-uri "/src/models/my_model.clj")))
                 first
                 :new-text))))))
