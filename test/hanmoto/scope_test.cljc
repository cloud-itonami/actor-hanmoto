(ns hanmoto.scope-test
  (:require [biscuit.wire :as wire]
            [clojure.test :refer [deftest is testing]]
            [ed25519.sign :as ed]
            [hanmoto.scope :as scope]))

(def root-public-key
  "biscuit-auth/biscuit samples/current/samples.json, `root_public_key`. The
  fixtures below were minted by the reference Rust implementation, so what is
  exercised here is interoperability, not this workspace agreeing with itself."
  (mapv #(#?(:clj Integer/parseInt :cljs js/parseInt)
          (subs "1055c750b1a1505937af1537c626ba3263995c33a64758aaafb1275b0312e284" % (+ % 2)) 16)
        (range 0 64 2)))

(defn- fixture [n]
  #?(:clj (with-open [in (java.io.FileInputStream. (str "test/fixtures/" n ".bc"))]
            (mapv #(bit-and % 255) (.readAllBytes in)))
     :cljs nil))

(defn- of [m] (scope/of (merge {:root-public-key root-public-key
                                :verify-fn ed/verify
                                :now "2026-08-31T00:00:00Z"} m)))

(deftest an-unconfigured-root-key-refuses-rather-than-admits
  (testing "測れない検査が、測って通った検査と同じ値を返さないこと"
    (doseq [k [nil [] ""]]
      (let [r (of {:token-bytes (fixture "test001_basic") :root-public-key k})]
        (is (false? (:allowed? r)) (str "root-public-key=" (pr-str k)))
        (is (= :biscuit/no-root-key-configured (:reason r)))))))

(deftest a-real-tokens-check-is-now-run-and-can-fail
  (testing "以前はここが :checks-not-evaluated だった —— wire が check を
            捨てていたため。いまは評価され、facts を出さない verifier には
            通らない。**署名不一致ではこの理由に到達できない**"
    (let [r (of {:token-bytes (fixture "test001_basic")})]
      (is (false? (:allowed? r)))
      (is (= :biscuit/check-failed (:reason r))
          "「評価できない」ではなく「評価して通らなかった」"))))

(deftest the-same-token-passes-its-check-once-the-facts-are-there
  (testing "両方向を出す —— 落ちるだけの検査は、何も discriminate していない"
    (let [r (of {:token-bytes (fixture "test001_basic")
                 :facts (quote [[resource "file1"] [operation "read"]])})]
      (is (not= :biscuit/check-failed (:reason r))
          "check は通る。ここから先は scope の問題であって check の問題ではない")
      (is (= :biscuit/no-scope-in-token (:reason r))
          "この token は scope を宣言していない —— check を通った先の別の拒否"))))

(deftest a-token-under-another-root-key-is-refused-as-a-signature-failure
  (testing "実データの負のコントロール。scope 不在とは別の理由でなければならない"
    (let [r (of {:token-bytes (fixture "test002_different_root_key")})]
      (is (false? (:allowed? r)))
      (is (= :biscuit/signature-mismatch (:reason r))
          "署名の失敗として拒否される。検証後の拒否と同じ理由になってはならない")
      (is (not= :biscuit/checks-not-evaluated (:reason r))))))

(deftest a-forged-signature-is-refused
  (let [r (of {:token-bytes (fixture "test005_invalid_signature")})]
    (is (false? (:allowed? r)))
    (is (= :biscuit/signature-mismatch (:reason r))
        "偽造署名は、検証を通った token のどの拒否とも別の理由でなければならない")))

(deftest garbage-is-malformed-and-says-so
  (doseq [b [[] nil [0 1 2 3] (vec (repeat 40 255))]]
    (let [r (of {:token-bytes b})]
      (is (false? (:allowed? r)) (pr-str b))
      (is (keyword? (:reason r))))))

(deftest every-refusal-names-a-reason
  (testing "理由の無い拒否は、匿名呼び出しと区別できない"
    (doseq [b [nil [0 1 2] (fixture "test001_basic") (fixture "test002_different_root_key")]]
      (let [r (of {:token-bytes b})]
        (is (contains? r :reason))
        (is (qualified-keyword? (:reason r)))))))

;; ── minting a token this actor accepts ──────────────────────────────────────
;;
;; The gate said no wire token could be minted here. That was wrong:
;; `biscuit.wire/encode-authority-token` writes a facts-only authority block,
;; and a billing scope IS a fact. This is the end-to-end proof -- a token minted
;; by this workspace, verified and scoped by this actor, with no fixture and no
;; borrowed key.

(defn- keypair [seed-byte]
  (let [seed (vec (repeat 32 seed-byte))
        sk (ed/secret-key! seed)]
    {:seed seed :secret sk :public (:public sk)}))

(defn- mint
  "-> token octets carrying `facts`, signed by `root`."
  [root facts]
  (let [nk (keypair 7)]
    (wire/encode-authority-token
     {:facts facts
      :root-private-key (:secret root)
      :next-secret (:seed nk)
      :next-public-key (:public nk)
      :sign-fn (fn [sk payload] (ed/sign sk payload))})))

(deftest a-token-this-workspace-minted-is-accepted-and-carries-its-scope
  (testing "発行 → 検証 → scope 抽出が、fixture も借り物の鍵も無しで通る"
    (let [root (keypair 3)
          t (mint root '[[scope "kotoba://graph/acme"]])
          r (scope/of {:token-bytes t :root-public-key (:public root)
                       :verify-fn ed/verify :now "2026-08-31T00:00:00Z"})]
      (is (true? (:allowed? r)) (pr-str r))
      (is (= "kotoba://graph/acme" (:scope r))))))

(deftest a-token-minted-under-a-different-root-is-refused
  (testing "負のコントロール —— 受理が『署名を見ていないから通った』ではない"
    (let [t (mint (keypair 3) '[[scope "kotoba://graph/acme"]])
          r (scope/of {:token-bytes t :root-public-key (:public (keypair 9))
                       :verify-fn ed/verify :now "2026-08-31T00:00:00Z"})]
      (is (false? (:allowed? r)))
      (is (= :biscuit/signature-mismatch (:reason r))))))

(deftest two-scopes-in-one-minted-token-are-refused-not-picked
  (testing "課金先を推測しない。2 つは『どちらでもよい』ではなく
            『誰に請求するか言っていない』"
    (let [root (keypair 3)
          t (mint root '[[scope "kotoba://graph/acme"] [scope "kotoba://graph/beta"]])
          r (scope/of {:token-bytes t :root-public-key (:public root)
                       :verify-fn ed/verify :now "2026-08-31T00:00:00Z"})]
      (is (false? (:allowed? r)))
      (is (= :biscuit/ambiguous-scope (:reason r))))))

(deftest an-expired-minted-token-is-refused
  (let [root (keypair 3)
        t (mint root '[[scope "kotoba://graph/acme"] [before "2026-08-01T00:00:00Z"]])
        r (scope/of {:token-bytes t :root-public-key (:public root)
                     :verify-fn ed/verify :now "2026-08-31T00:00:00Z"})]
    (is (false? (:allowed? r)))
    (is (= :biscuit/expired (:reason r)))))
