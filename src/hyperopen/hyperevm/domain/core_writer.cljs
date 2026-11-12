(ns hyperopen.hyperevm.domain.core-writer
  "Calldata for HyperCore actions sent through the CoreWriter contract.

   A contract account (a Safe, for example) cannot sign Hyperliquid's
   user-signed actions, because Hyperliquid recovers the signer with ECDSA.
   It reaches HyperCore through CoreWriter instead: an ordinary HyperEVM
   transaction to `sendRawAction(bytes)` whose sender becomes the HyperCore
   user of the action.

   The raw action is one version byte (1), a 3-byte big-endian action id and
   the ABI-encoded fields of that action.

   `add-api-wallet` (action 9, fields `(address wallet, string name)`) was
   checked byte for byte against a registration that Hyperliquid mainnet
   applied on 2026-10-16; the test namespace holds that vector. An empty name
   uses the single unnamed slot.

   Every public function returns nil on invalid input and never throws."
  (:require [clojure.string :as str]
            [hyperopen.hyperevm.domain.abi :as abi]
            [hyperopen.hyperevm.domain.chain :as chain]))

(def core-writer-address
  "0x3333333333333333333333333333333333333333")

(def send-raw-action-selector
  "First 4 bytes of keccak-256 of `sendRawAction(bytes)`."
  "0x17938e13")

(def ^:private action-version-hex "01")

(def ^:private add-api-wallet-action-id 9)

(def ^:private word-hex-length 64)

(defn- left-pad-hex
  [hex width]
  (str (apply str (repeat (- width (count hex)) "0")) hex))

(defn- right-pad-to-word
  [hex]
  (let [remainder (js-mod (count hex) word-hex-length)]
    (if (= 0 remainder)
      hex
      (str hex (apply str (repeat (- word-hex-length remainder) "0"))))))

(defn- uint-word
  [value]
  (left-pad-hex (.toString value 16) word-hex-length))

(defn- address-word
  [address]
  (when-let [address* (abi/normalize-address address)]
    (left-pad-hex (subs address* 2) word-hex-length)))

(defn- utf8-hex
  [text]
  (let [bytes (.encode (js/TextEncoder.) (str (or text "")))
        parts (array)]
    (dotimes [i (.-length bytes)]
      (.push parts (left-pad-hex (.toString (aget bytes i) 16) 2)))
    (.join parts "")))

(defn- hex-byte-length
  [hex]
  (/ (count hex) 2))

(defn- action-header
  [action-id]
  (str action-version-hex (left-pad-hex (.toString action-id 16) 6)))

(defn- send-raw-action-calldata
  "Calldata for `sendRawAction(bytes)` carrying `action-hex` (no `0x`)."
  [action-hex]
  (str send-raw-action-selector
       (uint-word 32)
       (uint-word (hex-byte-length action-hex))
       (right-pad-to-word action-hex)))

(defn add-api-wallet-action
  "Raw action bytes (hex, no `0x`) that register `agent-address` as an API
   wallet named `agent-name`, or nil for an invalid address."
  [agent-address agent-name]
  (when-let [wallet-word (address-word agent-address)]
    (let [name-hex (utf8-hex (some-> agent-name str str/trim))]
      (str (action-header add-api-wallet-action-id)
           wallet-word
           (uint-word 64)
           (uint-word (hex-byte-length name-hex))
           (right-pad-to-word name-hex)))))

(defn encode-add-api-wallet
  "Calldata for CoreWriter `sendRawAction` that registers `agent-address`
   as an API wallet of the sender."
  [agent-address agent-name]
  (some-> (add-api-wallet-action agent-address agent-name)
          send-raw-action-calldata))

(defn add-api-wallet-tx
  "The HyperEVM transaction a contract account sends to register
   `agent-address` as its API wallet, or nil for an invalid address."
  ([agent-address agent-name]
   (add-api-wallet-tx chain/mainnet agent-address agent-name))
  ([chain agent-address agent-name]
   (when-let [data (encode-add-api-wallet agent-address agent-name)]
     {:to core-writer-address
      :data data
      :chainId (:chain-id chain)})))
