(ns hyperopen.api.trading.user-actions
  (:require [clojure.string :as str]
            [hyperopen.account.context :as account-context]
            [hyperopen.api.trading.debug-exchange-simulator :as debug-exchange-simulator]
            [hyperopen.api.trading.http :as http]
            [hyperopen.hyperevm.domain.chain :as chain]
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
  "A contract account can only move funds to its own HyperEVM address, between its own balances, or to its own sub-accounts here. Send funds to other accounts from the account itself.")

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

(defn- submit-core-writer-transactions!
  "Send `txs` to CoreWriter through the connected wallet, as one batch when
   it can. Resolves like a posted action once the wallet has taken them.
   Hyperliquid applies them when they are executed, which for a contract
   account can be after other signers approved them."
  [address txs]
  (-> (contract-account/send-transactions! address txs)
      (.then (fn [_]
               {:status "ok"
                :response {:type "default"}}))))

(defn- submit-core-writer-transaction!
  [address tx]
  (submit-core-writer-transactions! address [tx]))

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

(defn- lower-address
  [value]
  (some-> value str str/trim str/lower-case not-empty))

(defn- field
  "Read `k` from a map whose keys may be keywords or strings."
  [m k]
  (let [value (get m k)]
    (if (some? value) value (get m (name k)))))

(defn- spot-token-by-wire-id
  "The spot token that `wire-id` (`NAME:0xtokenId`) names exactly, from the
   spot metadata in `store`: `{:index :name :wei-decimals}`, or nil. A name
   alone is not enough, so a look-alike token cannot stand in."
  [store wire-id]
  (let [[token-name token-id] (str/split (str wire-id) #":" 2)]
    (when (and (seq token-name) (seq token-id))
      (some (fn [token]
              (when (and (= token-name (str (field token :name)))
                         (= (str/lower-case token-id)
                            (some-> (field token :tokenId) str str/lower-case)))
                {:index (field token :index)
                 :name (field token :name)
                 :wei-decimals (field token :weiDecimals)}))
            (field (get-in @store [:spot :meta]) :tokens)))))

(defn- token-system-address
  "Where HyperCore pays `token` out to the sender's own HyperEVM address."
  [store {:keys [index name]}]
  (or (some-> (tokens/token-by-index (get-in @store [:spot :meta]) index)
              :system-address
              lower-address)
      (if (= "HYPE" name)
        (lower-address (:hype-system-address chain/mainnet))
        (lower-address (tokens/system-address index)))))

(defn- dex-id
  "`sendAsset`'s dex name as CoreWriter's id: spot, the default perp dex
   (0), or a named perp dex. A named dex's id is its index in Hyperliquid's
   `perpDexs` list, whose entry 0 is the default dex; the app keeps the
   named entries in order (`[:perp-dexs]`), so the id is one past the
   name's position there. An unknown name gives nil and is refused."
  [store dex]
  (let [dex* (some-> dex str str/trim)]
    (cond
      (= "spot" dex*) (core-writer/spot-dex-id)
      (str/blank? dex*) 0
      :else (let [names (vec (get @store :perp-dexs))
                  position (.indexOf (clj->js names) dex*)]
              (when (>= position 0)
                (inc position))))))

(defn- own-send-asset
  "For a contract account at `address`, `{:tx tx}` for a `sendAsset` that
   stays with the account, `{:refusal message}` otherwise. It stays when it
   goes to:

   - the token's system address, from Spot to Spot: HyperCore pays it out
     to the sender's own HyperEVM address;
   - the account itself, between two different balances;
   - one of the account's own sub-accounts.

   The source is the account or one of its own sub-accounts."
  [store address action]
  (let [owner (lower-address address)
        own-subs (set (keep lower-address
                            (account-context/owned-subaccount-addresses @store)))
        token (spot-token-by-wire-id store (:token action))
        destination (lower-address (:destination action))
        source-dex (dex-id store (:sourceDex action))
        destination-dex (dex-id store (:destinationDex action))
        from-sub (lower-address (:fromSubAccount action))
        to-evm? (and token
                     (= destination (token-system-address store token))
                     (= "spot" (:sourceDex action))
                     (= "spot" (:destinationDex action)))
        ;; To itself: from a sub-account, or between two different balances
        ;; (the same balance to itself would move nothing).
        to-self? (and (= destination owner)
                      (or (not (blank-text? from-sub))
                          (not= source-dex destination-dex)))
        to-own-sub? (contains? own-subs destination)
        stays? (and token
                    (some? source-dex)
                    (some? destination-dex)
                    (or (blank-text? from-sub) (contains? own-subs from-sub))
                    (or to-evm? to-self? to-own-sub?))
        wei (when stays?
              (core-writer/decimal->units (:amount action) (:wei-decimals token)))]
    (cond
      (not stays?)
      {:refusal contract-account-send-asset-message}

      (nil? wei)
      {:refusal (if (= 8 (:wei-decimals token))
                  contract-account-withdraw-invalid-message
                  (str "Enter an amount with at most " (:wei-decimals token)
                       " decimal places."))}

      :else
      (if-let [tx (core-writer/send-asset-tx {:destination destination
                                              :sub-account from-sub
                                              :source-dex source-dex
                                              :destination-dex destination-dex
                                              :token (:index token)
                                              :wei wei})]
        {:tx tx}
        {:refusal contract-account-send-asset-message}))))

(defn submit-send-asset!
  "Send an asset. A contract account cannot sign this. It sends a
   CoreWriter transaction instead, and only for a send that stays with the
   account (`own-send-asset`): back to its own HyperEVM address, between its
   own balances, or to its own sub-accounts. Anything that would reach
   another account is refused before the wallet is asked."
  [store address action]
  (if-not (true? (contract-account/known-contract-account? address))
    (sign-and-post-user-action! store address action :nonce :sign-send-asset-action!)
    (let [{:keys [tx refusal]} (own-send-asset store address action)]
      (if tx
        (submit-core-writer-transaction! address tx)
        (js/Promise.reject (js/Error. refusal))))))

(def contract-account-staking-invalid-message
  "Enter a positive HYPE amount.")

(defn- submit-staking!
  "A contract account's staking move as the CoreWriter transactions built by
   `->txs`, sent as one batch; an ordinary wallet signs the action."
  [store address action sign-action-key ->txs]
  (if (true? (contract-account/known-contract-account? address))
    (let [txs (->txs action)]
      (if (and (seq txs) (every? some? txs))
        (submit-core-writer-transactions! address txs)
        (js/Promise.reject (js/Error. contract-account-staking-invalid-message))))
    (sign-and-post-user-action! store address (dissoc action :stakeFromSpotWei)
                                :nonce sign-action-key)))

(defn submit-c-deposit!
  "Spot HYPE to the account's own staking balance (CoreWriter action 4 for a
   contract account)."
  [store address action]
  (submit-staking! store address action :sign-c-deposit-action!
                   #(vector (core-writer/staking-deposit-tx (:wei %)))))

(defn submit-c-withdraw!
  "Staking HYPE back to the account's own spot balance (CoreWriter action 5
   for a contract account)."
  [store address action]
  (submit-staking! store address action :sign-c-withdraw-action!
                   #(vector (core-writer/staking-withdraw-tx (:wei %)))))

(defn submit-token-delegate!
  "Delegate or undelegate the account's own staking balance (CoreWriter
   action 3 for a contract account). The HYPE stays the account's.

   A contract account's delegation may carry `:stakeFromSpotWei`, HYPE to
   move from its Spot balance to its staking balance first (action 4): both
   go as one batch, so it stakes from Spot in one step. An ordinary wallet's
   action never carries it."
  [store address action]
  (submit-staking! store address action :sign-token-delegate-action!
                   (fn [{:keys [validator wei isUndelegate stakeFromSpotWei]}]
                     (cond-> []
                       (some? stakeFromSpotWei)
                       (conj (when-not (true? isUndelegate)
                               (core-writer/staking-deposit-tx stakeFromSpotWei)))

                       :always
                       (conj (core-writer/token-delegate-tx validator wei (true? isUndelegate)))))))

(defn submit-withdraw3! [store address action]
  (sign-and-post-user-action! store address action :time :sign-withdraw3-action!))
