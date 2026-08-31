(ns hanmoto.census-test
  (:require [clojure.test :refer [deftest is testing]]
            [hanmoto.census :as c]))

(def rows
  [{"domain" "a.example" "software" "wordpress" "source" "self-reported"}
   {"domain" "b.example" "software" "ghost" "source" "self-reported"}
   {"domain" "c.example" "software" "mastodon" "source" "self-reported"}
   {"domain" "d.example" "software" "peertube" "source" "self-reported"}
   {"domain" "e.example" "software" "lemmy" "source" "self-reported"}
   {"domain" "f.example" "software" "somethingnew" "source" "self-reported"}
   {"domain" "g.example" "software" nil "source" "observed"}])

(deftest the-vocabulary-names-what-it-knows-and-nothing-else
  (is (= "blog" (c/category-of "wordpress")))
  (is (= "social" (c/category-of "MASTODON")) "大小は問わない")
  (testing "語彙に無いものは unknown。いちばん近い箱に入れない"
    (is (= "unknown" (c/category-of "somethingnew")))
    (is (= "unknown" (c/category-of nil)))))

(deftest every-categorical-answer-carries-its-unclassified-remainder
  (let [r (c/by-category rows)]
    (is (= 7 (:total r)))
    (is (= 2 (:unknown r)) "somethingnew と software nil")
    (is (< 0.28 (:unknown-ratio r) 0.29))
    (testing "unknown を欠いた答えは、完成しているように読めて完成していない"
      (is (contains? r :unknown))
      (is (contains? r :unknown-ratio)))))

(deftest provenance-is-never-summed
  (let [r (c/by-source rows)]
    (is (= 6 (get r "self-reported")))
    (is (= 1 (get r "observed")))
    (testing "この関数は合計を返さない —— 出所の違う 2 つを 1 つの『発行者数』に
              しないことが目的だから"
      (is (map? r)))))

(deftest the-unclassified-tail-is-askable
  (let [t (c/unclassified-software rows)]
    (is (some #(= "somethingnew" (first %)) t)
        "語彙を変える議論は、この一覧から始まる")))

(deftest lookup-absence-is-not-nonexistence
  (is (some? (c/lookup rows "a.example")))
  (is (nil? (c/lookup rows "nowhere.example"))))

(deftest unknown-is-itself-a-category-you-can-ask-for
  (is (= 2 (count (c/hosts-in rows "unknown"))))
  (is (= 2 (count (c/hosts-in rows "blog")))))
