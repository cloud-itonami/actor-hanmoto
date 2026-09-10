(ns hanmoto.register-test
  (:require [clojure.test :refer [deftest is testing]]
            [hanmoto.register :as register]))

(def base {:summary {:count 27307} :source "kotoba-lang/global-accounts-datoms"
           :as-of "2026-08-09T23:02:56+09:00"})

(deftest the-two-dates-say-different-things
  (testing "as-of はデータが最後に変わった時、checked-at は最後に作り直した時。
            片方だけなら『22 日前のまま誰も見ていない』と『22 日前だが今朝
            作り直して変わっていなかった』が同じ顔になる"
    (let [p (register/provenance
             (register/of (assoc base :checked-at "2026-08-31T06:56:21Z"))
             "2026-08-31T07:00:00Z")]
      (is (= "2026-08-09T23:02:56+09:00" (:as-of p)))
      (is (= "2026-08-31T06:56:21Z" (:checked-at p)))
      (is (= 22 (:age-days p)) "年齢はデータの側から測る")
      (is (not= (:as-of p) (:checked-at p))))))

(deftest a-register-nobody-rebuilt-says-so-rather-than-omitting-it
  (testing "field が消えると『再導出していない』が『この register には
            当てはまらない』に見える"
    (let [p (register/provenance (register/of base) "2026-08-31T07:00:00Z")]
      (is (contains? p :checked-at))
      (is (nil? (:checked-at p))))))

(deftest a-rebuild-does-not-move-as-of
  (testing "作り直しで as-of が動いたら、変わっていない snapshot が新鮮に見える
            —— この名前空間が防いでいる失敗が 1 field 隣に移るだけ"
    (let [a (register/of (assoc base :checked-at "2026-08-31T06:00:00Z"))
          b (register/of (assoc base :checked-at "2026-08-31T18:00:00Z"))]
      (is (= (:as-of a) (:as-of b)))
      (is (not= (:checked-at a) (:checked-at b)))
      (is (= (register/age-days a "2026-08-31T20:00:00Z")
             (register/age-days b "2026-08-31T20:00:00Z"))
          "何度作り直しても年齢は縮まない"))))

(deftest an-undated-register-is-still-refused
  (doseq [bad [nil "" "not-a-date"]]
    (is (thrown? #?(:clj Exception :cljs :default)
                 (register/of (assoc base :as-of bad :checked-at "2026-08-31T06:00:00Z")))
        (pr-str bad))))
