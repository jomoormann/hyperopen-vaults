(ns hyperopen.hyperevm.domain.core-writer-test
  "The unnamed vector is the inner CoreWriter call of a registration that
   Hyperliquid mainnet applied on 2026-10-16 (a Safe registered agent
   0x5ce4…53d7 through CoreWriter). The named vectors were produced
   independently with viem (`encodeFunctionData` over `encodeAbiParameters`)
   on 2026-11-12."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.hyperevm.domain.core-writer :as core-writer]))

(def ^:private agent-address
  "0x5ce4A06175E2258e0423110f504caC3912dC53D7")

(def ^:private unnamed-registration-calldata
  (str "0x17938e13"
       "0000000000000000000000000000000000000000000000000000000000000020"
       "0000000000000000000000000000000000000000000000000000000000000064"
       "01000009"
       "0000000000000000000000005ce4a06175e2258e0423110f504cac3912dc53d7"
       "0000000000000000000000000000000000000000000000000000000000000040"
       "0000000000000000000000000000000000000000000000000000000000000000"
       "00000000000000000000000000000000000000000000000000000000"))

(def ^:private named-registration-calldata
  ;; name "desk valid_until 1800000000000" = 30 bytes
  (str "0x17938e13"
       "0000000000000000000000000000000000000000000000000000000000000020"
       "0000000000000000000000000000000000000000000000000000000000000084"
       "01000009"
       "0000000000000000000000005ce4a06175e2258e0423110f504cac3912dc53d7"
       "0000000000000000000000000000000000000000000000000000000000000040"
       "000000000000000000000000000000000000000000000000000000000000001e"
       ;; "desk " + "valid_until " + "18" + eleven "0" + 2 bytes of padding
       "6465736b20" "76616c69645f756e74696c20" "3138" "3030303030303030303030" "0000"
       "00000000000000000000000000000000000000000000000000000000"))

(deftest add-api-wallet-matches-the-applied-mainnet-registration-test
  (is (= unnamed-registration-calldata
         (core-writer/encode-add-api-wallet agent-address "")))
  (is (= unnamed-registration-calldata
         (core-writer/encode-add-api-wallet agent-address nil))))

(deftest add-api-wallet-encodes-the-name-as-a-dynamic-string-test
  (is (= named-registration-calldata
         (core-writer/encode-add-api-wallet agent-address
                                            "desk valid_until 1800000000000")))
  (testing "a name longer than one word spans two"
    (let [calldata (core-writer/encode-add-api-wallet
                    agent-address
                    "a name that is longer than thirty-two bytes")]
      ;; selector + offset + length + (4 + 32 + 32 + 32 + 64 = 164 bytes,
      ;; padded to 192)
      (is (= (+ 10 64 64 (* 2 192)) (count calldata)))
      (is (= "00000000000000000000000000000000000000000000000000000000000000a4"
             (subs calldata 74 138))))))

(deftest add-api-wallet-refuses-invalid-addresses-test
  (is (nil? (core-writer/encode-add-api-wallet "0x1234" "desk")))
  (is (nil? (core-writer/encode-add-api-wallet nil "desk")))
  (is (nil? (core-writer/add-api-wallet-tx "not an address" "desk"))))

(deftest add-api-wallet-tx-targets-core-writer-on-hyperevm-test
  (is (= {:to "0x3333333333333333333333333333333333333333"
          :data unnamed-registration-calldata
          :chainId "0x3e7"}
         (core-writer/add-api-wallet-tx agent-address ""))))

;; --- usdClassTransfer (action 7) ------------------------------------------------
;; Both vectors were produced independently with viem on 2026-11-12:
;; `encodeFunctionData(sendRawAction, 0x01000007 ++ encodeAbiParameters(
;; [uint64, bool], [ntl, toPerp]))`.

(def ^:private spot-to-perp-5-usd-calldata
  (str "0x17938e13"
       "0000000000000000000000000000000000000000000000000000000000000020"
       "0000000000000000000000000000000000000000000000000000000000000044"
       "01000007"
       "00000000000000000000000000000000000000000000000000000000004c4b40"
       "0000000000000000000000000000000000000000000000000000000000000001"
       "00000000000000000000000000000000000000000000000000000000"))

(def ^:private perp-to-spot-1234-56-usd-calldata
  (str "0x17938e13"
       "0000000000000000000000000000000000000000000000000000000000000020"
       "0000000000000000000000000000000000000000000000000000000000000044"
       "01000007"
       "000000000000000000000000000000000000000000000000000000004995e400"
       "0000000000000000000000000000000000000000000000000000000000000000"
       "00000000000000000000000000000000000000000000000000000000"))

(deftest usd-class-transfer-matches-the-viem-vectors-test
  (is (= spot-to-perp-5-usd-calldata
         (core-writer/encode-usd-class-transfer "5" true)))
  (is (= perp-to-spot-1234-56-usd-calldata
         (core-writer/encode-usd-class-transfer "1234.56" false)))
  (is (= {:to "0x3333333333333333333333333333333333333333"
          :data spot-to-perp-5-usd-calldata
          :chainId "0x3e7"}
         (core-writer/usd-class-transfer-tx "5.000000" true))))

(deftest usd-amount-is-read-exactly-test
  (let [ntl (fn [amount] (some-> (core-writer/usd-amount->ntl amount) str))]
    (is (= "5000000" (ntl "5")))
    (is (= "5000000" (ntl 5)))
    (is (= "1" (ntl "0.000001")))
    (is (= "1234560000" (ntl " 1234.56 ")))
    (is (= "100000000" (ntl "100.")))
    (is (= "1500000" (ntl "1.5000000")) "trailing zeros past 6 places lose nothing")
    (is (= "9007199254740993" (ntl "9007199254.740993"))
        "more digits than a float holds exactly")
    (is (= "18446744073709551615" (ntl "18446744073709.551615"))
        "the largest uint64")
    (testing "what would have to be rounded or guessed is refused"
      (is (nil? (ntl "0.0000001")))
      (is (nil? (ntl "1.2345678")))
      (is (nil? (ntl "0")))
      (is (nil? (ntl "0.000000")))
      (is (nil? (ntl "-1")))
      (is (nil? (ntl "1e3")))
      (is (nil? (ntl "1,5")))
      (is (nil? (ntl ".5")))
      (is (nil? (ntl "")))
      (is (nil? (ntl nil)))
      (is (nil? (ntl "18446744073709.551616")) "one more than a uint64 holds"))
    (is (nil? (core-writer/usd-class-transfer-tx "abc" true)))))

;; --- sendAsset (action 13): USDC from Spot to the sender on HyperEVM -------------
;; Both vectors were produced independently with viem on 2026-11-12:
;; `0x0100000d ++ encodeAbiParameters([address, address, uint32, uint32,
;; uint64, uint64], [0x2000…0000, 0x0, 4294967295, 4294967295, 0, wei])`.

(defn- spot-usdc-to-evm-calldata
  [wei-word]
  (str "0x17938e13"
       "0000000000000000000000000000000000000000000000000000000000000020"
       "00000000000000000000000000000000000000000000000000000000000000c4"
       "0100000d"
       "0000000000000000000000002000000000000000000000000000000000000000"
       "0000000000000000000000000000000000000000000000000000000000000000"
       "00000000000000000000000000000000000000000000000000000000ffffffff"
       "00000000000000000000000000000000000000000000000000000000ffffffff"
       "0000000000000000000000000000000000000000000000000000000000000000"
       wei-word
       "00000000000000000000000000000000000000000000000000000000"))

(deftest spot-usdc-to-evm-matches-the-viem-vectors-test
  (is (= (spot-usdc-to-evm-calldata
          "0000000000000000000000000000000000000000000000000000000005f5e100")
         (core-writer/encode-spot-usdc-to-evm "1")))
  (is (= (spot-usdc-to-evm-calldata
          "0000000000000000000000000000000000000000000000000000001cbe991a14")
         (core-writer/encode-spot-usdc-to-evm "1234.56789012")))
  (is (= {:to "0x3333333333333333333333333333333333333333"
          :data (spot-usdc-to-evm-calldata
                 "0000000000000000000000000000000000000000000000000000000005f5e100")
          :chainId "0x3e7"}
         (core-writer/spot-usdc-to-evm-tx "1.00"))))

(deftest spot-usdc-to-evm-fixes-everything-but-the-amount-test
  (let [body (fn [amount] (subs (core-writer/encode-spot-usdc-to-evm amount) 138))
        fixed (fn [amount] (subs (body amount) 0 (+ 8 (* 5 64))))]
    (is (= (fixed "1") (fixed "999999.5"))
        "recipient, sub-account, balances and token never change")
    (is (= "0x2000000000000000000000000000000000000000" core-writer/usdc-system-address)))
  (testing "USDC on HyperCore has 8 decimals"
    (is (some? (core-writer/encode-spot-usdc-to-evm "0.00000001")))
    (is (nil? (core-writer/encode-spot-usdc-to-evm "0.000000001")))
    (is (nil? (core-writer/encode-spot-usdc-to-evm "0")))
    (is (nil? (core-writer/encode-spot-usdc-to-evm "-5")))
    (is (nil? (core-writer/encode-spot-usdc-to-evm nil)))))
