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
