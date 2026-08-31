(ns hanmoto.usage-test
  (:require [clojure.test :refer [deftest is testing]]
            [hanmoto.usage :as u]))

(defn- fake-digest [s] (str "d" (hash s)))

(deftest month-comes-from-the-instant-not-from-now
  (is (= "2026-08" (u/month-of "2026-08-31T04:05:06Z")))
  (testing "短すぎる / 文字列でない instant は nil。今月に落とさない ——
            落とすと、時刻を持たない記録が黙って当月の売上になる"
    (is (nil? (u/month-of "2026")))
    (is (nil? (u/month-of nil)))))

(deftest a-blank-scope-is-not-nil
  (testing "nil scope を許すと、全顧客の使用量が 1 つのバケツに黙って混ざる"
    (is (= "unscoped" (u/scope nil)))
    (is (= "unscoped" (u/scope "  ")))
    (is (= "acme" (u/scope "acme")))))

(deftest the-caller-is-never-stored-in-the-clear
  (let [caller "did:pkh:eip155:8453:0xabc"
        k (u/mac-key fake-digest {:caller caller :scope-of "acme"
                                  :instant "2026-08-31T00:00:00Z"})]
    (is (some? k))
    (is (not (clojure.string/includes? k caller))
        "鍵に caller がそのまま入っていたら、使用量の export が名簿になる")
    (is (clojure.string/starts-with? k "mac/acme/2026-08/"))))

(deftest the-salt-makes-one-caller-one-key-within-a-month-and-unlinkable-outside
  (let [c "did:pkh:eip155:8453:0xabc"
        aug (u/mac-key fake-digest {:caller c :scope-of "acme" :instant "2026-08-01T00:00:00Z"})
        aug2 (u/mac-key fake-digest {:caller c :scope-of "acme" :instant "2026-08-30T00:00:00Z"})
        sep (u/mac-key fake-digest {:caller c :scope-of "acme" :instant "2026-09-01T00:00:00Z"})
        other (u/mac-key fake-digest {:caller c :scope-of "other" :instant "2026-08-01T00:00:00Z"})]
    (testing "同じ月・同じ顧客なら 1 つの鍵 —— これが数の正確さ"
      (is (= aug aug2)))
    (testing "月をまたぐと別の鍵 —— 追跡できない"
      (is (not= aug sep)))
    (testing "顧客をまたいでも別の鍵"
      (is (not= aug other)))))

(deftest a-missing-subject-is-not-a-customer
  (is (nil? (u/mac-key fake-digest {:caller "" :scope-of "acme" :instant "2026-08-01T00:00:00Z"})))
  (is (nil? (u/mac-key fake-digest {:caller "x" :scope-of "acme" :instant "bad"}))))

(deftest the-meter-refuses-a-dimension-the-price-book-could-then-quote
  (is (some? (u/count-key {:dimension :query :scope-of "acme" :instant "2026-08-01T00:00:00Z"})))
  (testing "dimensions に無い次元は nil。計器が数えないものを価格表が引用できる状態を作らない"
    (is (nil? (u/count-key {:dimension :seats :scope-of "acme" :instant "2026-08-01T00:00:00Z"})))))

(deftest report-folds-both-dimensions
  (let [c1 (u/mac-key fake-digest {:caller "a" :scope-of "acme" :instant "2026-08-01T00:00:00Z"})
        c2 (u/mac-key fake-digest {:caller "b" :scope-of "acme" :instant "2026-08-02T00:00:00Z"})
        c1b (u/mac-key fake-digest {:caller "a" :scope-of "acme" :instant "2026-08-20T00:00:00Z"})
        q (u/count-key {:dimension :query :scope-of "acme" :instant "2026-08-05T00:00:00Z"})
        r (u/report [{:key c1} {:key c2} {:key c1b} {:key q :n 40} {:key q :n 2}])]
    (testing "同じ caller の 2 回目は鍵が同じなので、記録が重複排除されていれば 1 度しか来ない"
      (is (= c1 c1b)))
    (is (= 42 (get-in r [["acme" "2026-08"] :query])))
    (is (= 3 (get-in r [["acme" "2026-08"] :mac]))
        "この fold は与えられた記録を数える。重複排除は書き込み時の鍵が担う")))

(deftest unrecognised-keys-are-counted-not-dropped
  (testing "読めなかった記録を黙って捨てる fold は、捨てるものが無かった fold と
            同じ合計を報告する"
    (let [r (u/report [{:key "garbage"} {:key "also/garbage/x"}])]
      (is (= 2 (:unreadable r))))))
