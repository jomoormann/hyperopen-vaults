(ns hyperopen.api.trading.user-actions
  (:require [clojure.string :as str]
            [hyperopen.api.trading.debug-exchange-simulator :as debug-exchange-simulator]
            [hyperopen.api.trading.http :as http]
            [hyperopen.hyperevm.domain.core-writer :as core-writer]
            [hyperopen.hyperevm.domain.tokens :as tokens]
            [hyperopen.trading-crypto-modules :as trading-crypto-modules]
            [hyperopen.wallet.agent-session :as agent-session]
            [hyperopen.wallet.contract-account :as contract-account]
            [hyperopen.wallet.provider-registry :as provider-registry]))

(defn parse-chain-id-int
  [value]
  (let [raw (some-> value str str/trim)]
    (when (seq raw)
      (let [hex? (str/starts-with? raw "0x")
            source (if hex? (subs raw 2) raw)
            base (if hex? 16 10)
            parsed (js/parseInt source base)]
        (when (and (number? parsed)
                   (not (js/isNaN parsed)))
          (js/Math.floor parsed))))))

(defn- normalize-signature-chain-id
  [value]
  (cond
    (string? value)
    (let [text (str/trim value)]
      (when (seq text)
        text))

    (number? value)
    (str "0x" (.toString (js/Math.floor value) 16))

    :else nil))

(defn- testnet-signature-chain-id-int
  []
  (parse-chain-id-int
   (agent-session/default-signature-chain-id-for-environment false)))

(defn- wallet-signature-chain-id
  "Wallets such as Rabby reject typed data whose domain chainId differs from
   the wallet's active chain, and Hyperliquid accepts any signatureChainId, so
   sign with whatever chain the wallet is on."
  [wallet-chain-id]
  (let [candidate (normalize-signature-chain-id wallet-chain-id)
        candidate-int (parse-chain-id-int candidate)]
    (when (and (number? candidate-int)
               (pos? candidate-int))
      (str "0x" (.toString candidate-int 16)))))

(defn- signing-context-for-chain-id
  [wallet-chain-id]
  (let [signature-chain-id (or (wallet-signature-chain-id wallet-chain-id)
                               (agent-session/default-signature-chain-id-for-environment true))
        chain-id-int (parse-chain-id-int signature-chain-id)]
    {:signature-chain-id signature-chain-id
     :hyperliquid-chain (if (= chain-id-int (testnet-signature-chain-id-int))
                          "Testnet"
                          "Mainnet")}))

(defn resolve-user-signing-context
  [store]
  (signing-context-for-chain-id (get-in @store [:wallet :chain-id])))

(defn- request-wallet-chain-id!
  "Asks the provider for its active chain right before signing; the stored
   chain id can be stale if a chainChanged event was missed."
  [store]
  (let [stored (get-in @store [:wallet :chain-id])]
    (try
      (let [provider (provider-registry/provider)]
        (if (and (some? provider)
                 (fn? (.-request provider)))
          (-> (.request provider (clj->js {:method "eth_chainId"}))
              (.then (fn [chain-id]
                       (or (wallet-signature-chain-id chain-id) stored)))
              (.catch (fn [_] stored)))
          (js/Promise.resolve stored)))
      (catch :default _
        (js/Promise.resolve stored)))))

(defn- next-user-signed-nonce!
  [store]
  (let [cursor (get-in @store [:wallet :user-signed-nonce-cursor])
        nonce (http/next-nonce cursor)]
    (swap! store assoc-in [:wallet :user-signed-nonce-cursor] nonce)
    nonce))

(defn approve-agent!
  [store address action]
  (let [action* (atom action)
        crypto-promise (trading-crypto-modules/load-trading-crypto-module!)]
    (-> (request-wallet-chain-id! store)
        (.then (fn [wallet-chain-id]
                 (when-let [signature-chain-id (wallet-signature-chain-id wallet-chain-id)]
                   (swap! action* assoc :signatureChainId signature-chain-id))
                 crypto-promise))
        (.then (fn [crypto]
                 ((:sign-approve-agent-action! crypto) address @action*)))
        (.then (fn [sig]
                 (let [{:keys [r s v]} (js->clj sig :keywordize-keys true)
                       action @action*
                       payload {:action action
                                :nonce (:nonce action)
                                :signature {:r r
                                            :s s
                                            :v v}}]
                   (or (debug-exchange-simulator/simulated-fetch-response [[:approveAgent]])
                       (http/json-post! http/exchange-url payload))))))))

(declare sign-and-post-user-action-with-wallet!)

(def contract-account-user-action-message
  "A contract account cannot do this here. Move funds and change account settings from the account itself.")

(def contract-account-transfer-invalid-message
  "Enter an amount with at most 6 decimal places.")

(def contract-account-withdraw-invalid-message
  "Enter an amount with at most 8 decimal places.")

(def contract-account-send-asset-message
  "A contract account can only move USDC from Spot to its own HyperEVM balance here. Send funds to other accounts from the account itself.")

(defn- sign-and-post-user-action!
  [store address action nonce-field sign-action-key]
  (if (true? (contract-account/known-contract-account? address))
    ;; Hyperliquid recovers the signer of these actions with ECDSA. Asking a
    ;; contract wallet to sign one would at best fail and at worst leave a
    ;; stray message-signing transaction with it.
    (js/Promise.reject (js/Error. contract-account-user-action-message))
    (sign-and-post-user-action-with-wallet! store address action nonce-field sign-action-key)))

(defn- sign-and-post-user-action-with-wallet!
  [store address action nonce-field sign-action-key]
  (let [nonce (next-user-signed-nonce! store)
        action* (atom nil)
        crypto-promise (trading-crypto-modules/load-trading-crypto-module!)]
    (-> (request-wallet-chain-id! store)
        (.then (fn [wallet-chain-id]
                 (let [{:keys [signature-chain-id hyperliquid-chain]}
                       (signing-context-for-chain-id wallet-chain-id)]
                   (reset! action* (-> action
                                       (assoc :signatureChainId signature-chain-id
                                              :hyperliquidChain hyperliquid-chain)
                                       (assoc nonce-field nonce))))
                 crypto-promise))
        (.then (fn [crypto]
                 (when-not (contains? crypto sign-action-key)
                   (throw (js/Error.
                           (str "Missing trading crypto signer: " sign-action-key))))
                 ((get crypto sign-action-key) address @action*)))
        (.then (fn [sig]
                 (let [{:keys [r s v]} (js->clj sig :keywordize-keys true)
                       signature {:r r
                                  :s s
                                  :v v}]
                   (-> (http/post-signed-action! @action* nonce signature)
                       (.then http/parse-json!))))))))

(defn- submit-core-writer-transaction!
  "Send `tx` to CoreWriter through the connected wallet. Resolves like a
   posted action once the wallet has taken the transaction. Hyperliquid
   applies it when the transaction is executed, which for a contract
   account can be after other signers approved it."
  [address tx]
  (-> (contract-account/send-transactions! address [tx])
      (.then (fn [_]
               {:status "ok"
                :response {:type "default"}}))))

(defn submit-usd-class-transfer!
  "Move USDC between the perp and spot balances of `address`. A contract
   account does it with a CoreWriter transaction instead of a signature:
   the funds stay in the same account, so this is the one fund move the
   trading screen makes for it."
  [store address action]
  (if (true? (contract-account/known-contract-account? address))
    (if-let [tx (core-writer/usd-class-transfer-tx (:amount action) (:toPerp action))]
      (submit-core-writer-transaction! address tx)
      (js/Promise.reject (js/Error. contract-account-transfer-invalid-message)))
    (sign-and-post-user-action! store address action :nonce :sign-usd-class-transfer-action!)))

(defn- blank-text?
  [value]
  (str/blank? (str (or value ""))))

(defn- spot-usdc-to-own-evm?
  "True when `action` is exactly the `sendAsset` that moves USDC from the
   Spot balance to the sender's own HyperEVM balance: USDC as the spot
   metadata in `store` knows it (token 0), to USDC's system address, from
   Spot, not from a sub-account."
  [store action]
  (let [usdc (tokens/token-by-index (get-in @store [:spot :meta]) 0)]
    (boolean
     (and (map? usdc)
          (seq (:wire-id usdc))
          (= (:wire-id usdc) (:token action))
          (= core-writer/usdc-system-address
             (some-> (:destination action) str str/trim str/lower-case))
          (= core-writer/usdc-system-address
             (some-> (:system-address usdc) str str/lower-case))
          (= "spot" (:sourceDex action))
          (= "spot" (:destinationDex action))
          (blank-text? (:fromSubAccount action))))))

(defn submit-send-asset!
  "Send an asset. A contract account cannot sign this, and the one send it
   makes here is USDC from Spot to its own HyperEVM balance, as a CoreWriter
   transaction whose every field but the amount is fixed
   (`core-writer/spot-usdc-to-evm-tx`). Anything that would reach another
   account is refused."
  [store address action]
  (cond
    (not (true? (contract-account/known-contract-account? address)))
    (sign-and-post-user-action! store address action :nonce :sign-send-asset-action!)

    (not (spot-usdc-to-own-evm? store action))
    (js/Promise.reject (js/Error. contract-account-send-asset-message))

    :else
    (if-let [tx (core-writer/spot-usdc-to-evm-tx (:amount action))]
      (submit-core-writer-transaction! address tx)
      (js/Promise.reject (js/Error. contract-account-withdraw-invalid-message)))))

(defn submit-c-deposit! [store address action]
  (sign-and-post-user-action! store address action :nonce :sign-c-deposit-action!))

(defn submit-c-withdraw! [store address action]
  (sign-and-post-user-action! store address action :nonce :sign-c-withdraw-action!))

(defn submit-token-delegate! [store address action]
  (sign-and-post-user-action! store address action :nonce :sign-token-delegate-action!))

(defn submit-withdraw3! [store address action]
  (sign-and-post-user-action! store address action :time :sign-withdraw3-action!))
