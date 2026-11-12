(ns hyperopen.wallet.contract-account
  "Is the connected account a contract on HyperEVM (a Safe, for example)?

   A contract account cannot produce the ECDSA signatures Hyperliquid's
   user-signed actions need. It acts on HyperCore through CoreWriter
   instead (`hyperopen.hyperevm.domain.core-writer`), so the app has to know
   which kind of account it is talking to before it asks for a signature.

   An account counts as a contract account only when both hold:

   - the wallet is on HyperEVM. A contract wallet reports the chain it lives
     on, and CoreWriter exists only there. This also keeps ordinary wallets
     on other chains from costing an RPC read.
   - `eth_getCode` on HyperEVM returns code that is not an EIP-7702
     delegation designator (`0xef0100` + address). A delegated EOA still
     signs with its own key.

   Results are remembered per address for the page's lifetime. A failed read
   is not remembered and rejects: guessing either way would send the account
   down the wrong path."
  (:require [clojure.string :as str]
            [hyperopen.hyperevm.domain.abi :as abi]
            [hyperopen.hyperevm.domain.chain :as chain]
            [hyperopen.hyperevm.infrastructure.rpc :as rpc]))

(defonce ^:private detections
  (atom {}))

(def detection-failed-message
  "Could not check whether this account is a contract. Try again.")

(defn- chain-id-int
  [value]
  (let [raw (some-> value str str/trim)]
    (when (seq raw)
      (let [hex? (str/starts-with? raw "0x")
            parsed (js/parseInt (if hex? (subs raw 2) raw) (if hex? 16 10))]
        (when (and (number? parsed)
                   (not (js/isNaN parsed)))
          (js/Math.floor parsed))))))

(defn hyperevm-chain?
  "True when `chain-id` (hex or decimal) is HyperEVM mainnet."
  [chain-id]
  (= (:chain-id-decimal chain/mainnet) (chain-id-int chain-id)))

(defn contract-code?
  "True when `code` (an `eth_getCode` result) is contract code that the
   account's own key does not control."
  [code]
  (let [code* (some-> code str str/trim str/lower-case)]
    (boolean
     (and (seq code*)
          (re-matches #"^0x[0-9a-f]+$" code*)
          (not (re-matches #"^0x0*$" code*))
          (not (and (str/starts-with? code* "0xef0100")
                    (= 48 (count code*))))))))

(defn known-contract-account?
  "The remembered answer for `address`: true, false, or nil when it has not
   been checked yet."
  [address]
  (when-let [address* (abi/normalize-address address)]
    (get @detections address*)))

(defn remember!
  [address contract?]
  (when-let [address* (abi/normalize-address address)]
    (swap! detections assoc address* (boolean contract?))
    (boolean contract?)))

(defn reset-detections!
  []
  (reset! detections {})
  true)

(defn- read-code-from-rpc!
  [address]
  (rpc/rpc-request! {} "eth_getCode" [address "latest"]))

(defn detect!
  "Resolves true when `address` is a contract account on HyperEVM and the
   wallet is on HyperEVM, false otherwise. Rejects when the code could not
   be read."
  [{:keys [address chain-id read-code!]
    :or {read-code! read-code-from-rpc!}}]
  (let [address* (abi/normalize-address address)
        known (known-contract-account? address*)]
    (cond
      (nil? address*)
      (js/Promise.resolve false)

      (not (hyperevm-chain? chain-id))
      (js/Promise.resolve false)

      (some? known)
      (js/Promise.resolve known)

      :else
      (-> (js/Promise.resolve nil)
          (.then (fn [_] (read-code! address*)))
          (.then (fn [code]
                   (if (string? code)
                     (remember! address* (contract-code? code))
                     (js/Promise.reject (js/Error. detection-failed-message)))))
          (.catch (fn [_]
                    (js/Promise.reject (js/Error. detection-failed-message))))))))

(defn detect-for-store!
  "`detect!` for the wallet in `store`. Resolves false without any request
   when the wallet is not on HyperEVM."
  ([store]
   (detect-for-store! store nil))
  ([store address]
   (let [wallet (:wallet @store)]
     (detect! {:address (or address (:address wallet))
               :chain-id (:chain-id wallet)}))))

(defn candidate?
  "Cheap synchronous pre-check: could the wallet in `store` be a contract
   account at all? False means the answer is certainly no."
  [store]
  (hyperevm-chain? (get-in @store [:wallet :chain-id])))
