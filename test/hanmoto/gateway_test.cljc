(ns hanmoto.gateway-test
  (:require [clojure.test :refer [deftest is testing]]
            [hanmoto.gateway :as gateway]
            [hanmoto.offer :as offer]))

(def secret "s3cr3t-origin-token-value")

(deftest the-matching-token-is-admitted
  (is (gateway/admitted? (gateway/admit {:presented secret :configured secret}))))

(deftest no-token-is-payment-required-not-a-free-answer
  (testing "これがこの名前空間の存在理由 —— 2026-08-31 の実測では gateway が 402、
            origin が 200 を返しており、値段は本物で迂回も本物だった"
    (let [r (gateway/admit {:presented nil :configured secret})]
      (is (not (gateway/admitted? r)))
      (is (= :gateway/no-token (:refuse r)))
      (is (= 402 (:status (get gateway/refusal (:refuse r))))))))

(deftest a-wrong-token-answers-differently-from-a-missing-one
  (testing "拒否された credential が、提示されなかった credential と同じ顔を
            してはならない"
    (let [r (gateway/admit {:presented "not-the-secret" :configured secret})]
      (is (= :gateway/token-mismatch (:refuse r)))
      (is (not= (:refuse r)
                (:refuse (gateway/admit {:presented nil :configured secret})))
          "この 2 つが畳まれたら、穴は元どおり")
      (is (= 403 (:status (get gateway/refusal (:refuse r))))))))

(deftest an-origin-that-cannot-check-refuses-rather-than-serves
  (testing "測れなかった検査が、測って問題が無かった検査と同じ値を返さないこと。
            secret 未設定で admit が通れば、それは元の穴そのもの"
    (doseq [c [nil "" "   "]]
      (let [r (gateway/admit {:presented "anything" :configured c})]
        (is (not (gateway/admitted? r)) (str "configured=" (pr-str c)))
        (is (= :gateway/no-secret (:refuse r)))
        (is (= 503 (:status (get gateway/refusal (:refuse r)))))))))

(deftest a-blank-presented-token-is-not-a-token
  (doseq [p ["" "  "]]
    (is (= :gateway/no-token (:refuse (gateway/admit {:presented p :configured secret}))))))

(deftest every-refusal-has-an-answer
  (testing "reason を足して refusal を足し忘れると、edge は status nil を返す"
    (doseq [reason [:gateway/no-token :gateway/token-mismatch :gateway/no-secret]]
      (is (integer? (:status (get gateway/refusal reason))) (str reason))
      (is (string? (:error (:body (get gateway/refusal reason))))))))

(deftest a-near-miss-is-not-admitted
  (testing "長さだけ同じ / 1 文字違い / 前後の余白"
    (doseq [p [(str secret " ") (str " " secret)
               (str (subs secret 0 (dec (count secret))) "X")
               (apply str (reverse secret))]]
      (is (not (gateway/admitted? (gateway/admit {:presented p :configured secret})))
          (pr-str p)))))

(deftest the-payer-header-is-the-one-the-facilitator-actually-sends
  (testing "以前は x-402-payer を読んでいたが、その名前は facilitator の
            どこにも無い —— 本番では決して埋まらず、埋める唯一の経路は偽装だった"
    (is (= "x-nexus-payment-payer" gateway/payer-header))
    (is (= "x-nexus-origin-token" gateway/token-header))))

(deftest the-gate-covers-exactly-what-is-for-sale
  (testing "売り物は全部塞がれ、売り物でないものは 402 にしない"
    (doseq [{:keys [method path-prefix]} offer/priced-resources]
      (is (offer/sells? method path-prefix) (str method " " path-prefix)))
    (is (not (offer/sells? "GET" "/health")))
    (is (not (offer/sells? "GET" "/.well-known/x402")))))
