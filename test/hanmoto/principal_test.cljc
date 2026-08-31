(ns hanmoto.principal-test
  (:require [clojure.test :refer [deftest is testing]]
            [hanmoto.principal :as principal]))

(deftest no-token-is-anonymous-and-that-is-fine
  (let [r (principal/of {:auth nil :payer "0xAbC0000000000000000000000000000000000001"})]
    (is (not (principal/refused? r)))
    (is (= "unscoped" (get-in r [:principal :scope])))
    (is (= "did:pkh:eip155:8453:0xabc0000000000000000000000000000000000001"
           (get-in r [:principal :caller]))
        "払った側は proof から来る。名乗りではない")))

(deftest a-rejected-token-is-not-an-anonymous-call
  (testing "これが名前空間の存在理由 —— 偽造 token と token 無しが同じ応答に
            なったら、偽造の方は誰にも気付かれずに load として課金される"
    (let [r (principal/of {:auth {:allowed? false :reason :sig/bad} :payer nil})]
      (is (principal/refused? r))
      (is (= :sig/bad (:refuse r))))))

(deftest an-authorised-token-with-no-scope-is-refused-not-pooled
  (testing "unscoped に落とすと、別々の顧客が黙って 1 つのバケツに混ざる。
            推測はもっと悪い"
    (let [r (principal/of {:auth {:allowed? true :scope "  "} :payer nil})]
      (is (principal/refused? r))
      (is (= :biscuit/no-scope-in-token (:refuse r))))))

(deftest the-scope-comes-from-the-token
  (let [r (principal/of {:auth {:allowed? true :scope "acme"} :payer nil})]
    (is (= "acme" (get-in r [:principal :scope])))))

(deftest the-payer-must-be-a-payer-shaped-thing
  (testing "自由文字列を受けると、request が別経路で caller を名乗れてしまう"
    (is (nil? (get-in (principal/of {:auth nil :payer "alice"}) [:principal :caller])))
    (is (nil? (get-in (principal/of {:auth nil :payer "0xshort"}) [:principal :caller])))
    (is (= "did:pkh:eip155:8453:0xa"
           (get-in (principal/of {:auth nil :payer "did:pkh:eip155:8453:0xa"}) [:principal :caller]))
        "既に did:pkh ならそのまま通す")))

(deftest chain-id-is-not-invented
  (let [r (principal/of {:auth nil :chain-id 84532
                         :payer "0xAbC0000000000000000000000000000000000001"})]
    (is (clojure.string/starts-with? (get-in r [:principal :caller]) "did:pkh:eip155:84532:")
        "testnet で mainnet の chain-id を焼き込まない")))
