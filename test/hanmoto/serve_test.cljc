(ns hanmoto.serve-test
  (:require [clojure.test :refer [deftest is testing]]
            [hanmoto.serve :as serve]
            [hanmoto.offer :as offer]
            [hanmoto.usage :as usage]
            [hanmoto.register :as register]))

(defn- fake-digest [s] (str "d" (hash s)))

(def rows
  [{"domain" "a.example" "software" "wordpress" "source" "self-reported"}
   {"domain" "b.example" "software" "mastodon" "source" "self-reported"}
   {"domain" "c.example" "software" "somethingnew" "source" "self-reported"}])

(def reg (register/of {:rows rows :as-of "2026-08-09T00:00:00Z"
                         :source "kotoba-lang/global-accounts-datoms"}))
(def ctx {:register reg :digest fake-digest})

(defn- req [path & {:keys [instant method]
                    :or {method "GET" instant "2026-08-15T00:00:00Z"}}]
  {:method method :path path :instant instant})

(defn- who
  "The principal the HOST establishes. Never part of the request."
  [caller & [scope]] {:caller caller :scope (or scope "acme")})

(deftest a-path-not-in-the-offer-is-not-charged-for
  (let [r (serve/handle ctx (req "/x402/anything") (who nil))]
    (is (= 404 (:status r)))
    (is (empty? (:usage r)) "売っていない path に usage を計上しない")))

(deftest an-answer-that-was-served-and-not-counted-is-not-expressible
  (testing "応答と usage が同じ返り値で来るので、答えて記録し忘れる形が書けない"
    (let [r (serve/handle ctx (req "/x402/counts") (who "did:pkh:eip155:8453:0xa"))]
      (is (= 200 (:status r)))
      (is (= 2 (count (:usage r))) ":query の counter と :mac の 2 件"))))

(deftest an-anonymous-call-is-load-but-not-a-customer
  (let [r (serve/handle ctx (req "/x402/counts") (who nil))]
    (is (= 200 (:status r)))
    (is (= 1 (count (:usage r))))
    (testing "caller が無ければ :mac は立たない —— 主体を発明したら匿名の通信が顧客になる"
      (is (nil? (usage/parse-mac-key (:key (first (:usage r)))))))))

(deftest a-miss-still-bills-and-says-what-a-miss-means
  (let [r (serve/handle ctx (req "/x402/host/nowhere.example") (who "did:pkh:x"))]
    (is (= 200 (:status r)))
    (is (false? (get-in r [:body :found])))
    (is (= 2 (count (:usage r)))
        "不在も答えである。課金しないと不在が存在より安くなり、それは走査の誘因になる")))

(deftest the-counts-answer-carries-its-unclassified-remainder
  (let [b (:body (serve/handle ctx (req "/x402/counts") (who nil)))]
    (is (= 3 (:total b)))
    (is (= 1 (:unknown b)))
    (is (contains? b :unknown-ratio))))

(deftest every-priced-resource-names-a-dimension-the-meter-counts
  (testing "計器が数えない次元に値段が付いている状態を、宣言の側で作れない"
    (doseq [r offer/priced-resources]
      (is (contains? usage/dimensions (:dimension r))
          (str (:path r) " の dimension が hanmoto.usage/dimensions に無い")))))

(deftest every-priced-resource-has-an-arm-in-the-router
  (testing "宣言と router がずれていたら 500 を返す —— つまり宣言だけ足して
            router を忘れると、この test が赤くなる"
    (doseq [r offer/priced-resources]
      (let [path (if (= (:path-prefix r) offer/host-prefix)
                   (str (:path-prefix r) "a.example")
                   (:path-prefix r))
            resp (serve/handle ctx (req path :method (:method r)) (who nil))]
        (is (= 200 (:status resp)) (str (:path r) " は宣言されているのに router に arm が無い"))))))

;; ── 計器を実際に走らせる ────────────────────────────────────────────────────
;;
;; gate `:meter-running` が言っているのは『計器に host が繋がっていない。まだ何も
;; 記録していない』である。ここは繋いだ host を実際に呼び、記録を集め、畳む。
;; **形が在ることではなく、通した結果を assert する。**

(deftest the-meter-runs-end-to-end
  (let [alice "did:pkh:eip155:8453:0xaaa"
        bob   "did:pkh:eip155:8453:0xbbb"
        ;; [request, principal] の組。**identity は request の中に無い。**
        calls (concat
               (for [_ (range 3)] [(req "/x402/counts" :instant "2026-08-10T00:00:00Z") (who alice)])
               (for [_ (range 2)] [(req "/x402/counts" :instant "2026-08-20T00:00:00Z") (who bob)])
               [[(req "/x402/counts" :instant "2026-09-02T00:00:00Z") (who alice)]]
               ;; 匿名: host が誰も認証していない
               [[(req "/x402/counts" :instant "2026-08-25T00:00:00Z") (who nil)]])
        responses (mapv (fn [[q p]] (serve/handle ctx q p)) calls)
        ;; 書き込み時の重複排除: 同じ鍵は 1 度しか保存されない
        records (->> responses (mapcat :usage))
        deduped (->> records
                     (reduce (fn [acc {:keys [key n]}]
                               (if n
                                 (update acc key (fnil + 0) n)   ; counter は加算
                                 (assoc acc key nil)))           ; subject は上書き = 重複排除
                             {})
                     (map (fn [[k v]] (if v {:key k :n v} {:key k}))))
        r (usage/report deduped)]
    (testing "全部 200 で返っている"
      (is (every? #(= 200 (:status %)) responses)))
    (testing "8 月: query は 6 回（alice 3 + bob 2 + 匿名 1）"
      (is (= 6 (get-in r [["acme" "2026-08"] :query]))))
    (testing "8 月: mac は 2（alice と bob。匿名は数えない。alice の 3 回は 1）"
      (is (= 2 (get-in r [["acme" "2026-08"] :mac]))))
    (testing "9 月は別の月として立つ"
      (is (= 1 (get-in r [["acme" "2026-09"] :query])))
      (is (= 1 (get-in r [["acme" "2026-09"] :mac]))))
    (testing "読めなかった記録は 0 —— 何かを黙って落としていない"
      (is (nil? (:unreadable r))))))

;; ── 名簿は年齢を名乗る ──────────────────────────────────────────────────────

(deftest a-register-without-provenance-is-refused
  (testing "日付の無いスナップショットから作った答えは、現在の名簿から作った
            答えと見分けが付かない。だから読み込みの時点で拒否する"
    (is (thrown? #?(:clj Exception :cljs js/Error)
                 (register/of {:rows [] :source "x"})))
    (is (thrown? #?(:clj Exception :cljs js/Error)
                 (register/of {:rows [] :as-of "2026-08-09T00:00:00Z" :source ""})))))

(deftest every-answer-says-how-old-the-register-is
  (doseq [path ["/x402/counts" "/x402/unclassified" "/x402/host/a.example"]]
    (let [b (:body (serve/handle ctx (req path :instant "2026-08-31T00:00:00Z") (who nil)))]
      (is (= "2026-08-09T00:00:00Z" (get-in b [:register :as-of])) path)
      (is (= 22 (get-in b [:register :age-days])) path)
      (is (= 3 (get-in b [:register :hosts])) path))))

(deftest age-is-nil-not-zero-when-it-cannot-be-computed
  (testing "0 は『新しい』と読める。測れなかったことを新しさとして出さない"
    (is (nil? (register/age-days reg "not-an-instant")))
    (is (nil? (register/age-days {:as-of nil} "2026-08-31T00:00:00Z")))))

;; ── identity は request の中に無い ──────────────────────────────────────────

(deftest the-request-cannot-name-the-caller-or-the-scope
  (testing "request に caller / scope を仕込んでも無視される —— scope は課金キーで、
            request から来るなら誰でも他人の請求書に書き込める
            （ADR-2608150100 が /infer/transfer で見つけた形）"
    (let [hostile (assoc (req "/x402/counts") :caller "did:pkh:victim" :scope "victim-corp")
          r (serve/handle ctx hostile (who nil))
          keys- (map :key (:usage r))]
      (is (= 1 (count keys-)) "principal が nil なので :mac は立たない")
      (is (not-any? #(clojure.string/includes? % "victim") keys-)
          "request が名乗った scope も caller も鍵に入っていない"))))

(deftest the-host-decides-the-scope
  (let [a (serve/handle ctx (req "/x402/counts") (who "did:pkh:x" "acme"))
        b (serve/handle ctx (req "/x402/counts") (who "did:pkh:x" "other"))]
    (is (not= (map :key (:usage a)) (map :key (:usage b)))
        "scope が違えば鍵が違う。その scope を決めるのは host であって呼び手ではない")))
