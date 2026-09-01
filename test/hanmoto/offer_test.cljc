(ns hanmoto.offer-test
  (:require [clojure.test :refer [deftest is testing]]
            [hanmoto.offer :as offer]))

(def addr "0xA00366234D29d4F882088048c0B2fa0dB7302D4E")
(def testnet-addr
  "The x402.nexus faucet pool -- an address whose key we hold, so what the
  faucet hands out returns when it is spent."
  "0xD030410BABA777b1e7FacABf679295641203628A")

(deftest each-network-settles-to-its-own-address
  ;; Measured 2026-09-01: `addr` is a Safe on Ethereum mainnet with NO CONTRACT
  ;; on Base Sepolia (code 0, nonce 0) and no private key behind it, so testnet
  ;; payments sent there were destroyed on arrival.
  (let [rs (:resources (offer/document addr ["transaction"] testnet-addr))
        by (into {} (map (juxt (juxt :path #(get-in % [:price :network]))
                               #(get-in % [:price :payTo])))
                 rs)]
    (is (= 6 (count rs)) "three resources on two networks")
    (doseq [[[_ net] p] by]
      (is (= (if (= net "base-sepolia") testnet-addr addr) p)
          (str net " must settle where a key can reach it")))))

(deftest no-testnet-treasury-means-no-testnet-listing
  ;; Fail CLOSED. Falling back to the mainnet Safe is not the smaller mistake --
  ;; it is the one that destroys the payment, and the offer still looks valid.
  (let [rs (:resources (offer/document addr ["transaction"]))]
    (is (= 3 (count rs)))
    (is (= #{"base"} (set (map #(get-in % [:price :network]) rs))))
    (testing "and nothing carries the mainnet Safe under a testnet name"
      (is (every? #(= addr (get-in % [:price :payTo])) rs)))))
