(ns hyperopen.hyperevm.domain.core-writer
  "Calldata for HyperCore actions sent through the CoreWriter contract.

   A contract account (a Safe, for example) cannot sign Hyperliquid's
   user-signed actions, because Hyperliquid recovers the signer with ECDSA.
   It reaches HyperCore through CoreWriter instead: an ordinary HyperEVM
   transaction to `sendRawAction(bytes)` whose sender becomes the HyperCore
   user of the action.

   The raw action is one version byte (1), a 3-byte big-endian action id and
   the ABI-encoded fields of that action.

   `usd-class-transfer` (action 7, fields `(uint64 ntl, bool toPerp)`) moves
   USDC between the sender's own perp and spot balances; `ntl` is the amount
   in millionths of a dollar. Its vectors were produced with viem.

   `spot-usdc-to-evm` (action 13, `sendAsset`) moves USDC from the sender's
   spot balance to USDC's system address, which HyperCore pays out to the
   sender's own address on HyperEVM. Every field but the amount is fixed
   here, so this encoder cannot name another recipient, token or balance.
   Its vectors were produced with viem.

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

(def ^:private usd-class-transfer-action-id 7)

(def ^:private send-asset-action-id 13)

(def ^:private usd-decimals
  "HyperCore counts the USD amount of a class transfer in millionths."
  6)

(def ^:private usdc-wei-decimals
  "HyperCore keeps USDC, spot token 0, with 8 decimals, and `sendAsset`
   counts in those."
  8)

(def ^:private usdc-token-index 0)

(def usdc-system-address
  "HyperCore pays USDC that is sent here out to the sender's own address on
   HyperEVM."
  "0x2000000000000000000000000000000000000000")

(def ^:private zero-address
  "0x0000000000000000000000000000000000000000")

(def ^:private spot-dex
  "The id `sendAsset` uses for the spot balance."
  4294967295)

(def ^:private max-uint64
  (js/BigInt "18446744073709551615"))

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

(defn decimal->units
  "The decimal amount `amount` (text or number) as a count of units with
   `decimals` decimal places (a `js/BigInt`), or nil when it is not a
   positive amount with at most `decimals` places that fits a uint64. Text
   is read digit by digit, so no amount is rounded on the way."
  [amount decimals]
  (let [text (some-> amount str str/trim)]
    (when-let [[_ whole fraction] (and (seq text)
                                       (re-matches #"^(\d+)(?:\.(\d*))?$" text))]
      (let [fraction* (or fraction "")
            kept (subs fraction* 0 (min decimals (count fraction*)))
            dropped (subs fraction* (min decimals (count fraction*)))]
        (when (re-matches #"^0*$" dropped)
          (let [units (js/BigInt
                       (str whole
                            kept
                            (apply str (repeat (- decimals (count kept)) "0"))))]
            (when (and (> units (js/BigInt 0))
                       (<= units max-uint64))
              units)))))))

(defn usd-amount->ntl
  "The decimal USD amount `amount` as a count of millionths, or nil
   (`decimal->units`)."
  [amount]
  (decimal->units amount usd-decimals))

(defn usd-class-transfer-action
  "Raw action bytes (hex, no `0x`) that move `amount` USD between the
   sender's perp and spot balances (`to-perp?` true: spot to perp), or nil
   for an invalid amount."
  [amount to-perp?]
  (when-let [ntl (usd-amount->ntl amount)]
    (str (action-header usd-class-transfer-action-id)
         (uint-word ntl)
         (uint-word (if to-perp? 1 0)))))

(defn encode-usd-class-transfer
  [amount to-perp?]
  (some-> (usd-class-transfer-action amount to-perp?)
          send-raw-action-calldata))

(defn usd-class-transfer-tx
  "The HyperEVM transaction a contract account sends to move `amount` USD
   between its own perp and spot balances, or nil for an invalid amount."
  ([amount to-perp?]
   (usd-class-transfer-tx chain/mainnet amount to-perp?))
  ([chain amount to-perp?]
   (when-let [data (encode-usd-class-transfer amount to-perp?)]
     {:to core-writer-address
      :data data
      :chainId (:chain-id chain)})))

(defn spot-usdc-to-evm-action
  "Raw action bytes (hex, no `0x`) that move `amount` USDC from the sender's
   spot balance to its own address on HyperEVM, or nil for an invalid
   amount."
  [amount]
  (when-let [wei (decimal->units amount usdc-wei-decimals)]
    (str (action-header send-asset-action-id)
         (address-word usdc-system-address)
         (address-word zero-address)
         (uint-word spot-dex)
         (uint-word spot-dex)
         (uint-word usdc-token-index)
         (uint-word wei))))

(defn encode-spot-usdc-to-evm
  [amount]
  (some-> (spot-usdc-to-evm-action amount)
          send-raw-action-calldata))

(defn spot-usdc-to-evm-tx
  "The HyperEVM transaction a contract account sends to move `amount` USDC
   from its spot balance to its own address on HyperEVM, or nil for an
   invalid amount."
  ([amount]
   (spot-usdc-to-evm-tx chain/mainnet amount))
  ([chain amount]
   (when-let [data (encode-spot-usdc-to-evm amount)]
     {:to core-writer-address
      :data data
      :chainId (:chain-id chain)})))

;; ---------- General send-asset and staking -------------------------------------
;;
;; These encode what the caller hands them. Which destinations a contract
;; account may name is decided by the caller
;; (`hyperopen.api.trading.user-actions`); the encoders only refuse values
;; that do not fit their ABI types. Vectors were produced with viem.

(def ^:private token-delegate-action-id 3)

(def ^:private staking-deposit-action-id 4)

(def ^:private staking-withdraw-action-id 5)

(def ^:private max-uint32
  (js/BigInt "4294967295"))

(defn spot-dex-id
  "The dex id `sendAsset` uses for the spot balance."
  []
  spot-dex)

(defn- uint-in-range
  "`value` (a `js/BigInt`, a non-negative safe integer or digit text) as a
   `js/BigInt` no greater than `max`, or nil."
  [value max]
  (let [n (cond
            (= "bigint" (goog/typeOf value)) value
            (and (number? value) (js/Number.isSafeInteger value) (>= value 0)) (js/BigInt value)
            (and (string? value) (re-matches #"^\d+$" (str/trim value))) (js/BigInt (str/trim value))
            :else nil)]
    (when (and (some? n) (>= n (js/BigInt 0)) (<= n max))
      n)))

(defn- positive-uint64
  [value]
  (when-let [n (uint-in-range value max-uint64)]
    (when (> n (js/BigInt 0)) n)))

(defn send-asset-action
  "Raw action bytes (hex, no `0x`) of `sendAsset` (action 13): `wei` of spot
   token `token` from the sender (or its `sub-account`; the zero address for
   none) on dex `source-dex` to `destination` on dex `destination-dex`. Nil
   when any field is invalid."
  [{:keys [destination sub-account source-dex destination-dex token wei]}]
  (let [destination-word (address-word destination)
        sub-account-word (address-word (or sub-account zero-address))
        source (uint-in-range source-dex max-uint32)
        target (uint-in-range destination-dex max-uint32)
        token* (uint-in-range token max-uint64)
        wei* (positive-uint64 wei)]
    (when (and destination-word sub-account-word source target token* wei*)
      (str (action-header send-asset-action-id)
           destination-word
           sub-account-word
           (uint-word source)
           (uint-word target)
           (uint-word token*)
           (uint-word wei*)))))

(defn- core-writer-tx
  [action-hex]
  (when action-hex
    {:to core-writer-address
     :data (send-raw-action-calldata action-hex)
     :chainId (:chain-id chain/mainnet)}))

(defn send-asset-tx
  "The HyperEVM transaction of `send-asset-action`, or nil."
  [fields]
  (core-writer-tx (send-asset-action fields)))

(defn staking-deposit-tx
  "Move `wei` HYPE (8 decimals) from the sender's spot balance to its staking
   balance (action 4), or nil."
  [wei]
  (core-writer-tx (when-let [wei* (positive-uint64 wei)]
                    (str (action-header staking-deposit-action-id) (uint-word wei*)))))

(defn staking-withdraw-tx
  "Move `wei` HYPE from the sender's staking balance back to its spot balance
   (action 5), or nil."
  [wei]
  (core-writer-tx (when-let [wei* (positive-uint64 wei)]
                    (str (action-header staking-withdraw-action-id) (uint-word wei*)))))

(defn token-delegate-tx
  "Delegate (or with `undelegate?`, undelegate) `wei` HYPE of the sender's
   staking balance to `validator` (action 3), or nil."
  [validator wei undelegate?]
  (core-writer-tx (let [validator-word (address-word validator)
                        wei* (positive-uint64 wei)]
                    (when (and validator-word wei*)
                      (str (action-header token-delegate-action-id)
                           validator-word
                           (uint-word wei*)
                           (uint-word (if undelegate? 1 0)))))))
