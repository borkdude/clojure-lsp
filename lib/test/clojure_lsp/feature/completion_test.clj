(ns clojure-lsp.feature.completion-test
  (:require
   [babashka.fs :as fs]
   [clojure-lsp.feature.completion :as f.completion]
   [clojure-lsp.feature.completion-lib :as f.completion-lib]
   [clojure-lsp.test-helper.internal :as h]
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing]]))

(h/reset-components-before-test)

(deftest test-completion
  (h/load-java-path (str (fs/canonicalize (io/file "test" "fixtures" "java_interop" "System.java"))))
  (h/load-code-and-locs (h/code "(ns alpaca.ns (:require [user :as alpaca]))"
                                "(alpaca/)"
                                "(def barr)"
                                "(def bazz)") (h/file-uri "file:///a.cljc"))
  (h/load-code-and-locs (h/code "(ns user)"
                                "(def alpha)"
                                "alp"
                                "ba") (h/file-uri "file:///b.clj"))
  (h/load-code-and-locs (h/code "(ns alpaca.ns)"
                                "(def baff)"
                                "clj-") (h/file-uri "file:///c.cljs"))
  (h/load-code-and-locs (h/code "(ns d (:require [alpaca.ns :as alpaca])) frequen"
                                "(def bar \"some good docs\"123)"
                                "(defn barbaz [a b] 123)"
                                "(def some 123)")
                        (h/file-uri "file:///d.clj"))
  (h/load-code-and-locs (h/code "(ns e (:require [alpaca.ns :refer [ba]]"
                                "                [d :as d-alias]))"
                                "Syste"
                                "d-alias/b"
                                "123")
                        (h/file-uri "file:///e.clj"))
  (h/load-code-and-locs (h/code "(ns f (:require [alpaca.ns :refer [ba]]"
                                "                 alp))")
                        (h/file-uri "file:///f.clj"))
  (h/load-code-and-locs (h/code "(ns g (:require [alpaca.ns :as ba :refer [baq]]))"
                                "(defn bar [baz] ba)"
                                "(defn qux [bass] ba)"
                                "")
                        (h/file-uri "file:///g.clj"))
  (h/load-code-and-locs (h/code ";; comment")
                        (h/file-uri "file:///h.clj"))

  (swap! (h/db*) merge {:client-capabilities {:text-document {:hover {:content-format ["markdown"]}
                                                              :completion {:completion-item {:resolve-support {:properties ["documentation"]}}}}}})
  (testing "complete-alp"
    (h/assert-submaps
      [{:label "alpha" :kind :variable}
       {:label "alpaca" :kind :property :detail "alias to: alpaca.ns"}
       {:label "alpaca" :kind :property :detail "alias to: user"}
       {:label "alpaca.ns" :detail ":as ba"}]
      (f.completion/completion (h/file-uri "file:///b.clj") 3 3 (h/db))))
  (testing "complete-ba"
    (h/assert-submaps
      [{:label "ba" :kind :property}
       {:label "ba/baff"}
       {:label "ba/barr"}
       {:label "ba/bazz"}
       {:label "bases" :detail "clojure.core/bases"}]
      (f.completion/completion (h/file-uri "file:///b.clj") 4 3 (h/db))))
  (testing "complete-core-stuff"
    (h/assert-submaps
      [{:label "frequencies", :detail "clojure.core/frequencies"}]
      (f.completion/completion (h/file-uri "file:///d.clj") 1 49 (h/db)))
    (testing "complete symbols from alias"
      (h/assert-submaps
        [{:label "d-alias/bar"
          :kind :variable}
         {:label "d-alias/barbaz", :kind :function}]
        (f.completion/completion (h/file-uri "file:///e.clj") 4 10 (h/db)))))
  (testing "complete cljc files"
    (h/assert-submaps
      [{:label "alpaca/alpha" :kind :variable}]
      (f.completion/completion (h/file-uri "file:///a.cljc") 2 8 (h/db))))
  (testing "complete-core-stuff"
    (h/assert-submaps
      [{:label "clj->js", :detail "cljs.core/clj->js"}]
      (f.completion/completion (h/file-uri "file:///c.cljs") 3 4 (h/db)))
    (h/assert-submaps
      [{:label "frequencies", :detail "clojure.core/frequencies"}]
      (f.completion/completion (h/file-uri "file:///d.clj") 1 49 (h/db)))
    (h/assert-submaps
      [{:label "System", :detail "java.lang.System"}]
      (f.completion/completion (h/file-uri "file:///e.clj") 3 6 (h/db))))
  (testing "complete non symbols doesn't blow up"
    (is (= [] (f.completion/completion (h/file-uri "file:///e.clj") 5 3 (h/db)))))
  (testing "complete all available namespace definitions when inside require"
    (h/assert-submaps
      [{:label "alpaca.ns" :kind :module}]
      (f.completion/completion (h/file-uri "file:///f.clj") 2 21 (h/db))))
  (testing "complete locals"
    (h/assert-submaps
      [{:label "baz" :kind :variable}
       {:label "baq" :kind :reference}
       {:label "bar" :kind :function}
       {:label "ba" :kind :property}
       {:label "ba/baff" :kind :variable}
       {:label "ba/barr" :kind :variable}
       {:label "ba/bazz" :kind :variable}
       {:label "bases" :detail "clojure.core/bases"}]
      (f.completion/completion (h/file-uri "file:///g.clj") 2 18 (h/db))))
  (testing "complete without prefix return all available completions"
    (is (< 100 (count (f.completion/completion (h/file-uri "file:///g.clj") 3 1 (h/db))))))
  (testing "complete comment returns nothing"
    (is (empty? (f.completion/completion (h/file-uri "file:///h.clj") 1 10 (h/db))))))

(deftest completing-aliases
  (h/load-code-and-locs (h/code "(ns bbb)"
                                "(def bar)")
                        (h/file-uri "file:///bbb.clj"))
  (h/load-code-and-locs (h/code "(ns ccc"
                                "  (:require [bbb :as bb]))")
                        (h/file-uri "file:///ccc.clj"))
  (let [[[bb-row bb-col]]
        (h/load-code-and-locs (h/code "(ns aaa)"
                                      "bb|")
                              (h/file-uri "file:///aaa.clj"))]
    (testing "without resolve support"
      (swap! (h/db*) merge {:client-capabilities {:text-document {:completion {:completion-item {:resolve-support {:properties ["documentation"]}}}}}})
      (h/assert-submaps
        [;; to ns
         {:label "bb",
          :kind :property,
          :detail "alias to: bbb",
          :additional-text-edits
          [{:range {:start {:line 0, :character 0}, :end {:line 0, :character 8}},
            :new-text (h/code "(ns aaa "
                              "  (:require"
                              "    [bbb :as bb]))")}]}
         ;; to var in ns
         {:label "bb/bar",
          :kind :variable
          :data {"unresolved" [["documentation" {"name" "bar"
                                                 "uri" (h/file-uri "file:///bbb.clj")
                                                 "name-row" 2
                                                 "name-col" 6}]]},
          :additional-text-edits
          [{:range {:start {:line 0, :character 0}, :end {:line 0, :character 8}},
            :new-text (h/code "(ns aaa "
                              "  (:require"
                              "    [bbb :as bb]))")}]}]
        (f.completion/completion (h/file-uri "file:///aaa.clj") bb-row bb-col (h/db))))
    (testing "with resolve support"
      (swap! (h/db*) merge {:client-capabilities {:text-document {:completion {:completion-item {:resolve-support {:properties ["documentation" "additionalTextEdits"]}}}}}})
      (h/assert-submaps
        [;; to ns
         {:label "bb",
          :kind :property,
          :detail "alias to: bbb",
          :data {"unresolved" [["alias" {"ns-to-add" "bbb"
                                         "alias-to-add" "bb"
                                         "rcf-pos" nil
                                         "uri" (h/file-uri "file:///aaa.clj")}]]}}
         ;; to var in ns
         {:label "bb/bar",
          :kind :variable
          :data {"unresolved" [["documentation" {"name" "bar"
                                                 "uri" (h/file-uri "file:///bbb.clj")
                                                 "name-row" 2
                                                 "name-col" 6}]
                               ["alias" {"ns-to-add" "bbb"
                                         "alias-to-add" "bb"
                                         "rcf-pos" nil
                                         "uri" (h/file-uri "file:///aaa.clj")}]]}}]
        (f.completion/completion (h/file-uri "file:///aaa.clj") bb-row bb-col (h/db))))))

(deftest completing-aliases-to-rcf
  (h/load-code-and-locs (h/code "(ns bbb)"
                                "(def bar)")
                        (h/file-uri "file:///bbb.clj"))
  (h/load-code-and-locs (h/code "(ns ccc"
                                "  (:require [bbb :as bb]))")
                        (h/file-uri "file:///ccc.clj"))
  (let [[[bb-row bb-col]]
        (h/load-code-and-locs (h/code "(ns aaa)"
                                      "(comment"
                                      "  bb|)")
                              (h/file-uri "file:///aaa.clj"))]
    (testing "without resolve support"
      (swap! (h/db*) merge {:settings {:add-missing {:add-to-rcf :always}}
                            :client-capabilities {:text-document {:completion {:completion-item {:resolve-support {:properties ["documentation"]}}}}}})
      (h/assert-submaps
        [;; to ns
         {:label "bb",
          :kind :property,
          :detail "alias to: bbb",
          :additional-text-edits
          [{:range {:start {:line 1, :character 8}, :end {:line 1, :character 8}},
            :new-text (h/code "" "  (require '[bbb :as bb])")}]}
         ;; to var in ns
         {:label "bb/bar",
          :kind :variable
          :data {"unresolved" [["documentation" {"name" "bar"
                                                 "uri" (h/file-uri "file:///bbb.clj")
                                                 "name-row" 2
                                                 "name-col" 6}]]},
          :additional-text-edits
          [{:range {:start {:line 1, :character 8}, :end {:line 1, :character 8}},
            :new-text (h/code "" "  (require '[bbb :as bb])")}]}]
        (f.completion/completion (h/file-uri "file:///aaa.clj") bb-row bb-col (h/db))))
    (testing "with resolve support"
      (swap! (h/db*) merge {:settings {:add-missing {:add-to-rcf :always}}
                            :client-capabilities {:text-document {:completion {:completion-item {:resolve-support {:properties ["documentation" "additionalTextEdits"]}}}}}})
      (h/assert-submaps
        [;; to ns
         {:label "bb",
          :kind :property,
          :detail "alias to: bbb",
          :data {"unresolved" [["alias" {"ns-to-add" "bbb"
                                         "alias-to-add" "bb"
                                         "rcf-pos" {"row" 2, "col" 2, "end-row" 2, "end-col" 9}
                                         "uri" (h/file-uri "file:///aaa.clj")}]]}}
         ;; to var in ns
         {:label "bb/bar",
          :kind :variable
          :data {"unresolved" [["documentation" {"name" "bar"
                                                 "uri" (h/file-uri "file:///bbb.clj")
                                                 "name-row" 2
                                                 "name-col" 6}]
                               ["alias" {"ns-to-add" "bbb"
                                         "alias-to-add" "bb"
                                         "rcf-pos" {"row" 2, "col" 2, "end-row" 2, "end-col" 9}
                                         "uri" (h/file-uri "file:///aaa.clj")}]]}}]
        (f.completion/completion (h/file-uri "file:///aaa.clj") bb-row bb-col (h/db))))))

(deftest completing-full-ns
  (h/load-code-and-locs
    (h/code "(ns alpaca.ns)"
            "(def foo 1)"))
  (let [[[full-ns-r full-ns-c]] (h/load-code-and-locs
                                  (h/code "(ns foo)"
                                          "(alpaca.ns/f|)") (h/file-uri "file:///b.clj"))]
    (testing "completing a project full ns"
      (h/assert-submaps
        [{:label "alpaca.ns/foo" :kind :variable}]
        (f.completion/completion (h/file-uri "file:///b.clj") full-ns-r full-ns-c (h/db))))))

(deftest completing-all-ns-vars
  (h/load-code-and-locs
    (h/code "(ns alpaca.ns)"
            "(def foo 1)"
            "(defn foobar [] 2)"
            "(def bar 3)"))
  (let [[[all-vars-r all-vars-c]] (h/load-code-and-locs
                                    (h/code "(ns other.ns"
                                            " (:require [alpaca.ns :as alpaca]))"
                                            "alpaca/|"
                                            "")
                                    (h/file-uri "file:///b.clj"))]
    (testing "completing all vars of a ns alias"
      (h/assert-submaps
        [{:label "alpaca/bar" :kind :variable}
         {:label "alpaca/foo" :kind :variable}
         {:label "alpaca/foobar" :kind :function}]
        (f.completion/completion (h/file-uri "file:///b.clj") all-vars-r all-vars-c (h/db))))))

(deftest completing-with-reader-macros
  (let [[[before-reader-r before-reader-c]
         [after-reader-r after-reader-c]] (h/load-code-and-locs
                                            (h/code "(ns foo)"
                                                    "(def some-function 1)"
                                                    "some-fun|"
                                                    "1"
                                                    "#?(:clj \"clojure\" :cljs \"clojurescript\")"
                                                    "1"
                                                    "some-fun|") (h/file-uri "file:///a.cljc"))]
    (testing "before reader macro"
      (h/assert-submaps
        [{:label         "some-function"
          :kind          :variable}]
        (f.completion/completion (h/file-uri "file:///a.cljc") before-reader-r before-reader-c (h/db))))
    (testing "after reader macro"
      (h/assert-submaps
        [{:label         "some-function"
          :kind          :variable}]
        (f.completion/completion (h/file-uri "file:///a.cljc") after-reader-r after-reader-c (h/db))))))

(deftest resolve-item-test
  (swap! (h/db*) merge {:settings {:completion {:additional-edits-warning-text "* includes additional edits"}}})
  (h/load-code-and-locs "(ns a) (def foo \"Some docs\" 1)")
  (testing "When element does not contains data"
    (is (= {:label "Some" :kind :module}
           (f.completion/resolve-item {:label "Some" :kind :module} (h/db*)))))
  (testing "When element needs documentation and has a position"
    (h/assert-submap {:label "foo"
                      :documentation (h/code "a/foo"
                                             "Some docs"
                                             (h/file-path "/a.clj"))
                      :kind :variable}
                     (f.completion/resolve-item {:label "foo"
                                                 :kind :variable
                                                 :data {:unresolved [["documentation"
                                                                      {:name "foo"
                                                                       :uri (h/file-uri "file:///a.clj")
                                                                       :name-row 1
                                                                       :name-col 13}]]}}
                                                (h/db*))))
  (testing "When element needs documentation and has a namespace"
    (h/assert-submap {:label "foo"
                      :documentation (h/code "a/foo"
                                             "Some docs"
                                             (h/file-path "/a.clj"))
                      :kind :function}
                     (f.completion/resolve-item {:label "foo"
                                                 :kind :function
                                                 :data {:unresolved [["documentation"
                                                                      {:uri "file:///clojure.core.clj"
                                                                       :name "foo"
                                                                       :ns "a"}]]}}
                                                (h/db*))))
  (testing "When element needs an alias"
    (h/load-code-and-locs "(ns aaa)" (h/file-uri "file:///aaa.clj"))
    (h/assert-submap {:label "foo"
                      :kind :function
                      :additional-text-edits [{:range {:start {:line 0, :character 0}, :end {:line 0, :character 8}},
                                               :new-text (h/code "(ns aaa "
                                                                 "  (:require"
                                                                 "    [bbb :as b]))")}]}
                     (f.completion/resolve-item {:label "foo"
                                                 :kind :function
                                                 :data {:unresolved [["alias"
                                                                      {:ns-to-add "bbb"
                                                                       :alias-to-add "b"
                                                                       :uri (h/file-uri "file:///aaa.clj")}]]}}
                                                (h/db*))))
  (testing "When element needs an alias and documentation"
    (h/load-code-and-locs "(ns aaa)" (h/file-uri "file:///aaa.clj"))
    (h/assert-submap {:label "foo"
                      :documentation (h/code "a/foo"
                                             "* includes additional edits"
                                             "Some docs"
                                             (h/file-path "/a.clj"))
                      :kind :function
                      :additional-text-edits [{:range {:start {:line 0, :character 0}, :end {:line 0, :character 8}},
                                               :new-text (h/code "(ns aaa "
                                                                 "  (:require"
                                                                 "    [bbb :as b]))")}]}
                     (f.completion/resolve-item {:label "foo"
                                                 :kind :function
                                                 :data {:unresolved [["documentation"
                                                                      {:name "foo"
                                                                       :uri (h/file-uri "file:///a.clj")
                                                                       :name-row 1
                                                                       :name-col 13}]
                                                                     ["alias"
                                                                      {:ns-to-add "bbb"
                                                                       :alias-to-add "b"
                                                                       :uri (h/file-uri "file:///aaa.clj")}]]}}
                                                (h/db*)))
    (swap! (h/db*) merge {:settings {:completion {:additional-edits-warning-text nil}}})))

(deftest completing-namespace-usages
  (h/load-code-and-locs
    (h/code "(ns some.foo-ns)") (h/file-uri "file:///a.clj"))
  (h/load-code-and-locs
    (h/code "(ns some.bar-ns)") (h/file-uri "file:///b.clj"))
  (let [[[row col]] (h/load-code-and-locs
                      (h/code "(ns some.baz-ns"
                              "  (:require [some.foo-ns :as f]"
                              "            [some.bar-ns]))"
                              "some.|") (h/file-uri "file:///c.clj"))]
    (testing "completing all available namespace-usages"
      (h/assert-submaps
        [{:label "some.bar-ns" :kind :module :detail ""}
         {:label "some.baz-ns" :kind :module}
         ;; TODO avoid adding same namespace twice
         {:label "some.foo-ns" :kind :module :detail ""}
         {:label "some.foo-ns" :kind :property :detail ":as f"}]
        (f.completion/completion (h/file-uri "file:///c.clj") row col (h/db))))))

(deftest completing-inside-refers
  (h/load-code-and-locs
    (h/code "(ns some-ns)"
            "(def foob 1)"
            "(defn barb [] foo)") (h/file-uri "file:///a.clj"))
  (h/load-code-and-locs
    (h/code "(ns alpaca.ns)"
            "(def foo 1)"
            "(defn bar [] foo)") (h/file-uri "file:///b.clj"))
  (let [[[refer-vec-r refer-vec-c]] (h/load-code-and-locs
                                      (h/code "(ns foo"
                                              "  (:require [alpaca.ns :refer [|]]))") (h/file-uri "file:///c.clj"))
        [[ba-refer-r ba-refer-c]] (h/load-code-and-locs
                                    (h/code "(ns foo"
                                            "  (:require [alpaca.ns :refer [ba|]]))") (h/file-uri "file:///d.clj"))]
    (testing "completing all available refers"
      (h/assert-submaps
        [{:label "bar" :kind :function}
         {:label "foo" :kind :variable}]
        (f.completion/completion (h/file-uri "file:///c.clj") refer-vec-r refer-vec-c (h/db))))
    (testing "completing specific refer"
      (h/assert-submaps
        [{:label "bar" :kind :function}]
        (f.completion/completion (h/file-uri "file:///d.clj") ba-refer-r ba-refer-c (h/db))))))

(deftest completing-refer-var-usages
  (h/load-code-and-locs
    (h/code "(ns alpaca.ns)"
            "(def bazinga 1)"
            "(def foo 1)"
            "(defn bazingu [] foo)") (h/file-uri "file:///a.clj"))
  (let [[[refer-usage-r refer-usage-c]] (h/load-code-and-locs
                                          (h/code "(ns foo"
                                                  "  (:require [alpaca.ns :refer [foo bazinga]]))"
                                                  "(bazing|)") (h/file-uri "file:///b.clj"))]
    (testing "completing all available refers"
      (h/assert-submaps
        [{:label "bazinga" :kind :reference}]
        (f.completion/completion (h/file-uri "file:///b.clj") refer-usage-r refer-usage-c (h/db))))))

(deftest completing-known-snippets
  (h/load-code-and-locs
    (h/code "(ns foo)"
            "comment"
            "(comm)"))

  (testing "completing comment snippet when client does not support snippets"
    (swap! (h/db*) merge {:client-capabilities {:text-document {:completion {:completion-item {:snippet-support false
                                                                                               :resolve-support {:properties ["documentation"]}}}}}})
    (h/assert-submaps
      [{:label "comment"
        :kind :function
        :data {"unresolved" [["documentation" {"name" "comment"
                                               "uri" "file:///clojure.core.clj"
                                               "ns" "clojure.core"}]]}
        :detail "clojure.core/comment"}]
      (f.completion/completion (h/file-uri "file:///a.clj") 2 8 (h/db))))

  (testing "completing with snippets enable return all available snippets"
    (swap! (h/db*) merge {:client-capabilities {:text-document {:completion {:completion-item {:snippet-support true}}}}})
    (h/assert-submaps
      [{:label "comment"
        :detail "Insert comment block"
        :insert-text "(comment\n  $0\n  )"
        :kind :snippet
        :insert-text-format :snippet}]
      (filter (comp #(= "comment" %) :label) (f.completion/completion (h/file-uri "file:///a.clj") 2 8 (h/db)))))
  (testing "completing from a function call should not create duplicate parens"
    (swap! (h/db*) merge {:client-capabilities {:text-document {:completion {:completion-item {:snippet-support true}}}}})
    (h/assert-submaps
      [{:label "comment"
        :detail "Insert comment block"
        :insert-text "comment\n  $0\n  "
        :kind :snippet
        :insert-text-format :snippet}]
      (filter (comp #(= "comment" %) :label) (f.completion/completion (h/file-uri "file:///a.clj") 3 6 (h/db))))))

(deftest completing-user-snippets
  (h/load-code-and-locs
    (h/code "(ns foo)"
            "(defn foo []"
            "  wrap-in(+ 1 2))"))

  (testing "completing replacing $current-form"
    (swap! (h/db*) merge {:settings {:additional-snippets
                                     [{:name "wrap-in-let-sexpr$"
                                       :detail "Wrap in let"
                                       :snippet "(let [$1] $0$current-form)"}]}
                          :client-capabilities {:text-document {:completion {:completion-item {:snippet-support true}}}}})
    (h/assert-submaps
      [{:label "wrap-in-let-sexpr$"
        :detail "Wrap in let"
        :text-edit {:range {:start {:line 2 :character 2}
                            :end {:line 2 :character 16}}
                    :new-text "(let [$1] $0(+ 1 2))"}
        :kind :snippet
        :insert-text-format :snippet}]
      (filter (comp #(= "wrap-in-let-sexpr$" %) :label) (f.completion/completion (h/file-uri "file:///a.clj") 3 10 (h/db))))))

(deftest completing-locals
  (h/load-code-and-locs
    (h/code "(ns foo)"
            "(defn some [bar]"
            "  ba"
            "  (let [baz 1]"
            "    ba)"
            "  ba)"
            "(defn other [bass] 1)"))
  (testing "outside before let"
    (h/assert-submaps
      [{:label "bar" :kind :variable}
       {:label "bases" :kind :function :detail "clojure.core/bases"}]
      (f.completion/completion (h/file-uri "file:///a.clj") 3 5 (h/db))))
  (testing "inside let"
    (h/assert-submaps
      [{:label "bar" :kind :variable}
       {:label "baz" :kind :variable}
       {:label "bases" :kind :function :detail "clojure.core/bases"}]
      (f.completion/completion (h/file-uri "file:///a.clj") 5 7 (h/db))))
  (testing "outside before let"
    (h/assert-submaps
      [{:label "bar" :kind :variable}
       {:label "bases" :kind :function :detail "clojure.core/bases"}]
      (f.completion/completion (h/file-uri "file:///a.clj") 6 5 (h/db)))))

(deftest completing-normal-keywords
  (h/load-code-and-locs (h/code ":foo"))
  (h/assert-submaps
    []
    (f.completion/completion (h/file-uri "file:///a.clj") 1 4 (h/db))))

(deftest completing-namespaced-keywords
  (let [_ (h/load-code-and-locs (h/code "(ns a)"
                                        ":foo/bar"
                                        ":foo/baq"
                                        ":bar"
                                        ":foob"))
        [[foo-r foo-c]
         [bar-r bar-c]] (h/load-code-and-locs (h/code "(ns b)"
                                                      ":foo/bax"
                                                      ":ba"
                                                      ":foo/b|"
                                                      ":b|"
                                                      ":foob/bol") (h/file-uri "file:///b.clj"))]

    (testing "namespaced keywords are available everywhere"
      (h/assert-submaps
        [{:label ":foo/bax" :kind :keyword}
         {:label ":foo/baq" :kind :keyword}
         {:label ":foo/bar" :kind :keyword}]
        (f.completion/completion (h/file-uri "file:///b.clj") foo-r foo-c (h/db))))
    (testing "simple keywords are available everywhere"
      (h/assert-submaps
        [{:label ":ba" :kind :keyword}
         {:label ":bar" :kind :keyword}]
        (f.completion/completion (h/file-uri "file:///b.clj") bar-r bar-c (h/db))))))

(deftest completing-aliased-keywords
  (h/load-code-and-locs
    (h/code "(ns some.alpaca (:require [clojure.spec.alpha :as s]))"
            "(s/def ::foo 1)"
            "(s/def ::foob 1)"
            "(s/def ::bar 1)"
            "::fooba"
            ":some.alpaca/foobar"
            "(defn fooba [bass] 1)") (h/file-uri "file:///some/alpaca.clj"))
  (h/load-code-and-locs
    (h/code "(ns other.ns (:require [some.alpaca :as alp]))"
            "::alp/f"
            "") (h/file-uri "file:///other/ns.clj"))
  (h/load-code-and-locs
    (h/code "(ns other.ns (:require [some.alpaca :as alp]))"
            "::alp/"
            "") (h/file-uri "file:///someother/ns.clj"))
  (testing "return all matching reg keywords for that aliased keyword"
    (h/assert-submaps
      [{:label "::alp/foo" :kind :keyword}
       {:label "::alp/foob" :kind :keyword}]
      (f.completion/completion (h/file-uri "file:///other/ns.clj") 2 8 (h/db))))
  (testing "return all reg keywords for plain alias"
    (h/assert-submaps
      [{:label "::alp/bar" :kind :keyword}
       {:label "::alp/foo" :kind :keyword}
       {:label "::alp/foob" :kind :keyword}]
      (f.completion/completion (h/file-uri "file:///someother/ns.clj") 2 7 (h/db)))))

(deftest completing-arg-keywords-from-function-definition
  (h/load-code-and-locs (h/code "(ns some.a) (defn my-api [{:keys [foo bar baz barz] :as bla}] 1)")
                        (h/file-uri "file:///some/a.clj"))
  (h/load-code-and-locs (h/code "(ns some.b (:require [some.a :as a]))"
                                "(a/my-api {:})")
                        (h/file-uri "file:///some/b.clj"))
  (h/load-code-and-locs (h/code "(ns some.c (:require [some.a :as a]))"
                                "(a/my-api {:foo 1"
                                "           :barz 2"
                                "           :b})")
                        (h/file-uri "file:///some/c.clj"))
  (h/assert-submaps
    [{:label ":bar" :kind :keyword}
     {:label ":barz" :kind :keyword}
     {:label ":baz" :kind :keyword}
     {:label ":foo" :kind :keyword}]
    (f.completion/completion (h/file-uri "file:///some/b.clj") 2 13 (h/db)))
  (h/assert-submaps
    [{:label ":bar" :kind :keyword}
     {:label ":baz" :kind :keyword}]
    (f.completion/completion (h/file-uri "file:///some/c.clj") 4 13 (h/db))))

(deftest completing-java-class-usages
  (h/load-code-and-locs
    (h/code "(ns foo (:import (java.time LocalDateTime) (foo SomeClass)))"
            "LocalD"
            "SomeC"))
  (testing "known class import"
    (h/assert-submaps
      [{:label "LocalDateTime" :kind :class :detail "java.time.LocalDateTime"}]
      (f.completion/completion (h/file-uri "file:///a.clj") 2 4 (h/db))))
  (testing "unknown class import"
    (h/assert-submaps
      [{:label "SomeClass" :kind :class :detail "foo.SomeClass"}]
      (f.completion/completion (h/file-uri "file:///a.clj") 3 2 (h/db)))))

(deftest completing-java-methods
  (h/load-java-path (str (fs/canonicalize (io/file "test" "fixtures" "java_interop" "SomeClass.class"))))
  (let [[[field-r field-c]
         [method-r method-c]
         [full-method-r full-method-c]] (h/load-code-and-locs
                                          (h/code "(ns foo (:import (my_class SomeClass)))"
                                                  "SomeClass/m|"
                                                  "(SomeClass/m|)"
                                                  "(my_class.SomeClass/my|)"
                                                  ""))]

    (testing "public static methods and fields from simple class name"
      (h/assert-submaps
        [{:label "SomeClass/myField2" :kind :constant :detail "double"}
         {:label "SomeClass/myMethod2" :kind :method :detail "int (int)"}
         {:label "SomeClass/myMethod3" :kind :method :detail "void ()"}]
        (f.completion/completion (h/file-uri "file:///a.clj") field-r field-c (h/db)))
      (h/assert-submaps
        [{:label "SomeClass/myField2" :kind :constant :detail "double"}
         {:label "SomeClass/myMethod2" :kind :method :detail "int (int)"}
         {:label "SomeClass/myMethod3" :kind :method :detail "void ()"}]
        (f.completion/completion (h/file-uri "file:///a.clj") method-r method-c (h/db))))
    (testing "public static methods and fields from full class name"
      (h/assert-submaps
        [{:label "my_class.SomeClass/myField2" :kind :constant :detail "double"}
         {:label "my_class.SomeClass/myMethod2" :kind :method :detail "int (int)"}
         {:label "my_class.SomeClass/myMethod3" :kind :method :detail "void ()"}]
        (f.completion/completion (h/file-uri "file:///a.clj") full-method-r full-method-c (h/db))))))

(deftest completing-lein-lib-versions
  (let [[[dependency-1-r dependency-1-c]
         [plugin-1-r plugin-1-c]]
        (h/load-code-and-locs
          (h/code "(defproject my-project \"0.0.1\""
                  "  :description \"FIXME: write description\""
                  "  :main ^:skip-aot clojure-sample.core"
                  "  :dependencies [[org.clojure/clojurescript \"1.10.758\"]"
                  "                 [org.clojure/clojure \"|\"]]"
                  "  :plugins [[com.github.clojure-lsp/lein-clojure-lsp \"|\"]])")
          (h/file-uri "file:///some/my-project/project.clj"))]
    (testing ":dependencies"
      (reset! f.completion-lib/libs* f.completion-lib/initial-libs-value)
      (h/assert-submaps
        [{:label "1.11.0" :kind :text :sort-text "001"}
         {:label "1.12.0" :kind :text :detail "latest" :sort-text "000"}]
        (with-redefs [f.completion-lib/fetch-clojars-libs! (constantly {})
                      f.completion-lib/get-mvn-artifacts! (constantly {'foo/bar [{:mvn/version "0.0.1"} {:mvn/version "0.0.2"}]
                                                                       'org.clojure/clojure [{:mvn/version "1.12.0"} {:mvn/version "1.11.0"}]
                                                                       'org.clojure-android/foo [{:mvn/version "1.0.0"} {:mvn/version "1.1.0"}]})
                      f.completion-lib/fetch-github-clojure-libs! (constantly {})]
          (f.completion/completion (h/file-uri "file:///some/my-project/project.clj") dependency-1-r dependency-1-c (h/db)))))
    (testing ":plugins"
      (reset! f.completion-lib/libs* f.completion-lib/initial-libs-value)
      (h/assert-submaps
        [{:label "0.1.4" :kind :text  :sort-text "001"}
         {:label "3.10.2" :kind :text :detail "latest" :sort-text "000"}]
        (with-redefs [f.completion-lib/fetch-clojars-libs! (constantly {'com.github.clojure-lsp/lein-clojure-lsp [{:mvn/version "3.10.2"} {:mvn/version "0.1.4"}]})
                      f.completion-lib/get-mvn-artifacts! (constantly {'foo/bar [{:mvn/version "0.0.1"} {:mvn/version "0.0.2"}]
                                                                       'org.clojure/clojure [{:mvn/version "1.12.0"} {:mvn/version "1.11.0"} {:mvn/version "1.10.0-SNAPSHOT"}]
                                                                       'org.clojure-android/foo [{:mvn/version "1.0.0"} {:mvn/version "1.1.0"}]})
                      f.completion-lib/fetch-github-clojure-libs! (constantly {})]
          (f.completion/completion (h/file-uri "file:///some/my-project/project.clj") plugin-1-r plugin-1-c (h/db)))))))

(deftest completing-clojure-deps-lib-versions
  (let [[[mvn-1-r mvn-1-c]
         [git-tag-1-r git-tag-1-c]
         [all-coord-r all-coord-c]]
        (h/load-code-and-locs
          (h/code "{:deps {org.clojure/clojure {:mvn/version \"1.10.3\"}"
                  "        org.clojure/core.async {:mvn/version \"1.5.648\"}"
                  "        nubank/matcher-combinators {:mvn/version \"3.|\"}"
                  "        io.github.cognitect-labs/test-runner {:git/tag \"|\", :git/sha \"dfb30dd\"}"
                  "        foo/bar {|}}"
                  " :paths [\"src\" \"resources\"]"
                  " :aliases {:test {:extra-paths [\"test\"]}}}")
          (h/file-uri "file:///some/my-project/deps.edn"))]
    (testing "mvn coordinate"
      (reset! f.completion-lib/libs* f.completion-lib/initial-libs-value)
      (h/assert-submaps
        [{:label "3.4.0" :kind :text :sort-text "001"}
         {:label "3.4.1" :kind :text :detail "latest" :sort-text "000"}]
        (with-redefs [f.completion-lib/fetch-clojars-libs! (constantly {'nubank/matcher-combinators [{:mvn/version "3.4.1"} {:mvn/version "3.4.0"} {:mvn/version "1.2.0"}]})
                      f.completion-lib/get-mvn-artifacts! (constantly {'org.clojure/clojure [{:mvn/version "1.12.0"} {:mvn/version "1.11.0"} {:mvn/version "1.10.0-SNAPSHOT"}]})
                      f.completion-lib/fetch-github-clojure-libs! (constantly {})]
          (f.completion/completion (h/file-uri "file:///some/my-project/deps.edn") mvn-1-r mvn-1-c (h/db)))))
    (testing "git tag coordinate"
      (reset! f.completion-lib/libs* f.completion-lib/initial-libs-value)
      (h/assert-submaps
        [{:label "v0.3.4" :kind :text :sort-text "001"}
         {:label "v0.3.5" :kind :text :detail "latest" :sort-text "000"}]
        (with-redefs [f.completion-lib/fetch-clojars-libs! (constantly {})
                      f.completion-lib/get-mvn-artifacts! (constantly {'org.clojure/clojure [{:mvn/version "1.12.0"} {:mvn/version "1.11.0"} {:mvn/version "1.10.0-SNAPSHOT"}]})
                      f.completion-lib/fetch-github-clojure-libs! (constantly {'io.github.cognitect-labs/test-runner [#:git{:tag "v0.3.5" :sha "1234"} #:git{:tag "v0.3.4" :sha "2345"}]})]
          (f.completion/completion (h/file-uri "file:///some/my-project/deps.edn") git-tag-1-r git-tag-1-c (h/db)))))
    (testing "all coordinates"
      (reset! f.completion-lib/libs* f.completion-lib/initial-libs-value)
      (h/assert-submaps
        [{:label ":git{:tag \"v0.3.4\", :sha \"2345\"" :kind :text :sort-text "002"}
         {:label ":git{:tag \"v0.3.5\", :sha \"1234\"" :kind :text :sort-text "001"}
         {:label ":mvn{:version \"0.3.4\"" :kind :text :sort-text "000"}]
        (with-redefs [f.completion-lib/fetch-clojars-libs! (constantly {'foo/bar [{:mvn/version "0.3.4"}]})
                      f.completion-lib/get-mvn-artifacts! (constantly {'org.clojure/clojure [{:mvn/version "1.12.0"} {:mvn/version "1.11.0"} {:mvn/version "1.10.0-SNAPSHOT"}]})
                      f.completion-lib/fetch-github-clojure-libs! (constantly {'foo/bar [#:git{:tag "v0.3.5" :sha "1234"} #:git{:tag "v0.3.4" :sha "2345"}]})]
          (f.completion/completion (h/file-uri "file:///some/my-project/deps.edn") all-coord-r all-coord-c (h/db)))))))

(deftest completing-clojure-deps-lib-names
  (let [[[dep-1-r dep-1-c]
         [dep-2-r dep-2-c]]
        (h/load-code-and-locs
          (h/code "{:deps {org.clojure/clojure {:mvn/version \"1.10.3\"}"
                  "        org.cloj|"
                  "        nubank/m|"
                  "        io.github.cognitect-labs/test-runner {:git/tag \"|\", :git/sha \"dfb30dd\"}}"
                  " :paths [\"src\" \"resources\"]"
                  " :aliases {:test {:extra-paths [\"test\"]}}}")
          (h/file-uri "file:///some/my-project/deps.edn"))]
    (testing "org.clojure completion"
      (reset! f.completion-lib/libs* f.completion-lib/initial-libs-value)
      (h/assert-submaps
        [{:label "org.clojure-android/foo" :kind :text}
         {:label "org.clojure/clojure" :kind :text}
         {:label "org.clojure/core.async" :kind :text}]
        (with-redefs [f.completion-lib/fetch-clojars-libs! (constantly {'foo/bar [{:mvn/version "0.0.1"} {:mvn/version "0.0.2"}]
                                                                        'bar/baz [{:mvn/version "1.0.2"}]
                                                                        'org.clojure-android/foo [{:mvn/version "1.0.0"} {:mvn/version "1.1.0"}]})
                      f.completion-lib/get-mvn-artifacts! (constantly {'org.clojure/core.async [{:mvn/version "0.0.1"} {:mvn/version "0.0.2"}]
                                                                       'org.clojure/clojure [{:mvn/version "1.0.0"} {:mvn/version "1.1.0"}]})
                      f.completion-lib/fetch-github-clojure-libs! (constantly {})]
          (f.completion/completion (h/file-uri "file:///some/my-project/deps.edn") dep-1-r dep-1-c (h/db)))))
    (testing "artifact-id completion"
      (reset! f.completion-lib/libs* f.completion-lib/initial-libs-value)
      (h/assert-submaps
        [{:label "nubank/matcher-combinators" :kind :text}]
        (with-redefs [f.completion-lib/fetch-clojars-libs! (constantly {'foo/bar [{:mvn/version "0.0.1"} {:mvn/version "0.0.2"}]
                                                                        'nubank/foo [{:mvn/version "1.0.2"}]
                                                                        'nubank/matcher-combinators [{:mvn/version "1.0.0"} {:mvn/version "1.1.0"}]})
                      f.completion-lib/get-mvn-artifacts! (constantly {'org.clojure/core.async [{:mvn/version "0.0.1"} {:mvn/version "0.0.2"}]
                                                                       'org.clojure/clojure [{:mvn/version "1.0.0"} {:mvn/version "1.1.0"}]})
                      f.completion-lib/fetch-github-clojure-libs! (constantly {})]
          (f.completion/completion (h/file-uri "file:///some/my-project/deps.edn") dep-2-r dep-2-c (h/db)))))))

(deftest completing-lein-lib-names
  (let [[[dep-1-r dep-1-c]
         [dep-2-r dep-2-c]
         [dep-3-r dep-3-c]]
        (h/load-code-and-locs
          (h/code "(defproject my-project \"0.0.1\""
                  "  :description \"FIXME: write description\""
                  "  :main ^:skip-aot clojure-sample.core"
                  "  :dependencies [[org.clojure/clojurescript \"1.10.758\"]"
                  "                 [org.cl|]"
                  "                 [|]"
                  "                 [nubank/ma|]]"
                  "  :plugins [[com.github.clojure-lsp/lein-clojure-lsp \"|\"]])")
          (h/file-uri "file:///some/my-project/project.clj"))]
    (testing "org.clojure completion"
      (reset! f.completion-lib/libs* f.completion-lib/initial-libs-value)
      (h/assert-submaps
        [{:label "org.clojure-android/foo" :kind :text}
         {:label "org.clojure/clojure" :kind :text}
         {:label "org.clojure/core.async" :kind :text}]
        (with-redefs [f.completion-lib/fetch-clojars-libs! (constantly {'foo/bar [{:mvn/version "0.0.1"} {:mvn/version "0.0.2"}]
                                                                        'bar/baz [{:mvn/version "1.0.2"}]
                                                                        'org.clojure-android/foo [{:mvn/version "1.0.0"} {:mvn/version "1.1.0"}]})
                      f.completion-lib/get-mvn-artifacts! (constantly {'org.clojure/core.async [{:mvn/version  "0.0.1"} {:mvn/version "0.0.2"}]
                                                                       'org.clojure/clojure [{:mvn/version "1.0.0"} {:mvn/version "1.1.0"}]})
                      f.completion-lib/fetch-github-clojure-libs! (constantly {})]
          (f.completion/completion (h/file-uri "file:///some/my-project/project.clj") dep-1-r dep-1-c (h/db)))))
    (testing "empty completion"
      (reset! f.completion-lib/libs* f.completion-lib/initial-libs-value)
      (h/assert-submaps
        [{:label "bar/baz" :kind :text}
         {:label "foo/bar" :kind :text}
         {:label "org.clojure/clojure" :kind :text}]
        (with-redefs [f.completion-lib/fetch-clojars-libs! (constantly {'foo/bar [{:mvn/version "0.0.1"} {:mvn/version "0.0.2"}]
                                                                        'bar/baz [{:mvn/version "1.0.2"}]})
                      f.completion-lib/get-mvn-artifacts! (constantly {'org.clojure/clojure [{:mvn/version "1.0.0"} {:mvn/version "1.1.0"}]})
                      f.completion-lib/fetch-github-clojure-libs! (constantly {})]
          (f.completion/completion (h/file-uri "file:///some/my-project/project.clj") dep-2-r dep-2-c (h/db)))))
    (testing "artifact completion"
      (reset! f.completion-lib/libs* f.completion-lib/initial-libs-value)
      (h/assert-submaps
        [{:label "nubank/matcher-combinators" :kind :text}]
        (with-redefs [f.completion-lib/fetch-clojars-libs! (constantly {'foo/bar [{:mvn/version "0.0.1"} {:mvn/version "0.0.2"}]
                                                                        'nubank/foo [{:mvn/version "1.0.2"}]
                                                                        'nubank/matcher-combinators [{:mvn/version "1.0.0"} {:mvn/version "1.1.0"}]})
                      f.completion-lib/get-mvn-artifacts! (constantly {'org.clojure/core.async [{:mvn/version "0.0.1"} {:mvn/version "0.0.2"}]
                                                                       'org.clojure/clojure [{:mvn/version "1.0.0"} {:mvn/version "1.1.0"}]})
                      f.completion-lib/fetch-github-clojure-libs! (constantly {})]
          (f.completion/completion (h/file-uri "file:///some/my-project/project.clj") dep-3-r dep-3-c (h/db)))))))

(deftest completing-sorting
  (let [[[row col]] (h/load-code-and-locs
                      (h/code "(ns foo)"
                              ":foo"
                              "(def foo 1)"
                              "(let [foob 1]"
                              " fo|)"))]
    (testing "items are sorted properly"
      (h/assert-submaps
        [{:label "foob" :kind :variable :score 15}
         {:label "foo" :kind :module :score 14}
         {:label "foo" :kind :variable :score 14}
         {:label "for" :kind :function :detail "clojure.core/for" :score 6}
         {:label "force" :kind :function :detail "clojure.core/force" :score 6}
         {:label "format" :kind :function :detail "clojure.core/format" :score 6}]
        (f.completion/completion (h/file-uri "file:///a.clj") row col (h/db))))))

(deftest resolve-item-test-js-libs
  (swap! (h/db*) merge {:settings {:completion {:additional-edits-warning-text "* includes additional edits"}}})
  (h/load-code-and-locs "(ns a (:require [\"@mui/material/Grid$default\" :as Grid]))")
  (testing "When element needs an alias"
    (let [[[row col]] (h/load-code-and-locs "(ns aaa) Grid|" (h/file-uri "file:///aaa.clj"))]
      (is (= [{:label "Grid"
               :kind :property
               :detail "alias to: @mui/material/Grid$default"
               :additional-text-edits [{:range {:start {:line 0, :character 0}, :end {:line 0, :character 8}},
                                        :new-text (h/code "(ns aaa "
                                                          "  (:require"
                                                          "    [\"@mui/material/Grid$default\" :as Grid]))")}]
               :text-edit {:new-text "Grid",
                           :range {:end {:character 13, :line 0}, :start {:character 9, :line 0}}}
               :score 9}]
             (f.completion/completion (h/file-uri "file:///aaa.clj") row col (h/db)))))

    (h/assert-submap {:label "Grid"
                      :kind :property
                      :additional-text-edits [{:range {:start {:line 0, :character 0}, :end {:line 0, :character 8}},
                                               :new-text (h/code "(ns aaa "
                                                                 "  (:require"
                                                                 "    [\"@mui/material/Grid$default\" :as Grid]))")}]}
                     (f.completion/resolve-item {:label "Grid"
                                                 :kind :property
                                                 :data {:unresolved [["alias"
                                                                      {:ns-to-add "@mui/material/Grid$default"
                                                                       :alias-to-add "Grid"
                                                                       :uri (h/file-uri "file:///aaa.clj")
                                                                       :js-require true}]]}}
                                                (h/db*)))))

(deftest text-edit-range
  (let [[[ns-end-r ns-end-c]
         [ns-mid-r ns-mid-c]
         [fn-end-r fn-end-c]
         [fn-mid-r fn-mid-c]
         [arg-end-r arg-end-c]
         [arg-mid-r arg-mid-c]] (h/load-code-and-locs (h/code "(ns aaa"
                                                              "  (:require [bbb]))"
                                                              "(defn foo [whatsit]"
                                                              "  (bb|)"
                                                              "  (bb|b)"
                                                              "  (bbb/thi|)"
                                                              "  (bbb/thi|ng)"
                                                              "  (bbb/thingy wh|)"
                                                              "  (bbb/thingy wh|at))")
                                                      (h/file-uri "file:///aaa.clj"))
        _ (h/load-code (h/code "(ns bbb)"
                               "(defn thingy [_a _b _c] nil)")
                       (h/file-uri "file:///bbb.clj"))]
    (testing "At end of ns"
      (h/assert-submap {:text-edit {:range {:start {:line 3 :character 3} :end {:line 3 :character 5}}}}
                       (first (f.completion/completion (h/file-uri "file:///aaa.clj") ns-end-r ns-end-c (h/db)))))
    (testing "Within ns"
      (h/assert-submap {:text-edit {:range {:start {:line 4 :character 3} :end {:line 4 :character 6}}}}
                       (first (f.completion/completion (h/file-uri "file:///aaa.clj") ns-mid-r ns-mid-c (h/db)))))
    (testing "At end of fn"
      (h/assert-submap {:text-edit {:range {:start {:line 5 :character 3} :end {:line 5 :character 10}}}}
                       (first (f.completion/completion (h/file-uri "file:///aaa.clj") fn-end-r fn-end-c (h/db)))))
    (testing "Within fn"
      (h/assert-submap {:text-edit {:range {:start {:line 6 :character 3} :end {:line 6 :character 12}}}}
                       (first (f.completion/completion (h/file-uri "file:///aaa.clj") fn-mid-r fn-mid-c (h/db)))))
    (testing "At end of arg"
      (h/assert-submap {:text-edit {:range {:start {:line 7 :character 14} :end {:line 7 :character 16}}}}
                       (first (f.completion/completion (h/file-uri "file:///aaa.clj") arg-end-r arg-end-c (h/db)))))
    (testing "Within arg"
      (h/assert-submap {:text-edit {:range {:start {:line 8 :character 14} :end {:line 8 :character 18}}}}
                       (first (f.completion/completion (h/file-uri "file:///aaa.clj") arg-mid-r arg-mid-c (h/db)))))))

(deftest text-edit-range-invalid-syntax
  (h/load-code-and-locs (h/code "(ns aaa"
                                "  (:require [bbb]))"
                                "(defn foo [whatsit]"
                                "  (bb")
                        (h/file-uri "file:///aaa.clj"))
  (testing "text edit should not be added if syntax is invalid"
    (is (nil? (get-in (first (f.completion/completion (h/file-uri "file:///aaa.clj") 3 6 (h/db))) [:text-edit])))))
