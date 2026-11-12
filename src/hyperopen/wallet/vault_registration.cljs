(ns hyperopen.wallet.vault-registration
  "Register a trading key (API wallet) for a contract account.

   An ordinary wallet signs `approveAgent` and the app posts it to
   Hyperliquid. A contract account cannot sign it, so its registration is a
   HyperEVM transaction to CoreWriter, sent through the connected wallet
   like any other transaction. Whatever stands behind that wallet (a Safe's
   owners, a Zodiac role) decides whether it goes through.

   The flow:

   1. make sure the wallet is on HyperEVM;
   2. send the CoreWriter transaction with `eth_sendTransaction`;
   3. poll Hyperliquid's `userRole` for the key until it reports the key as
      an agent of the account.

   Step 3 does not look at the transaction hash on purpose. A contract
   wallet may return a hash that never appears on chain (a Safe transaction
   hash, a simulation), or queue the call for other signers. Hyperliquid
   applying the registration is the only fact that matters.

   `approve-agent!` has the signature of
   `hyperopen.api.trading/approve-agent!` and resolves with a response-like
   object, so the callers upstream of it stay unchanged."
  (:require [clojure.string :as str]
            [hyperopen.api.trading :as trading-api]
            [hyperopen.api.trading.http :as trading-http]
            [hyperopen.funding.infrastructure.wallet-rpc :as wallet-rpc]
            [hyperopen.hyperevm.domain.abi :as abi]
            [hyperopen.hyperevm.domain.chain :as chain]
            [hyperopen.hyperevm.domain.core-writer :as core-writer]
            [hyperopen.platform :as platform]
            [hyperopen.wallet.agent-session :as agent-session]
            [hyperopen.wallet.contract-account :as contract-account]
            [hyperopen.wallet.provider-registry :as provider-registry]))

(def default-valid-days
  "Lifetime of a key registered for a contract account. Hyperliquid reads it
   from the `valid_until` suffix of the name."
  90)

(def default-poll-ms
  "`userRole` weighs 60 of the 1200 per minute an IP may spend on info
   requests, so the poll stays well below one request every 3 seconds."
  6000)

(def default-timeout-ms
  (* 10 60 1000))

(def removal-unsupported-message
  "Removing a trading key is not available for contract accounts yet.")

(def registration-timeout-message
  "Hyperliquid has not registered the trading key yet. If the transaction is still waiting for signatures, enable trading again once it has executed.")

(def ^:private state-path
  [:wallet :agent :vault-registration])

(defn- reject
  [message]
  (js/Promise.reject (js/Error. message)))

(defn- hyperevm-chain-config
  []
  (assoc chain/mainnet :verify-switch? true))

(defn name-with-valid-until
  "`agent-name` with a `valid_until` suffix `days` from `now-ms`, unless it
   already carries one. A blank name stays blank (the unnamed slot has no
   suffix to read)."
  [agent-name now-ms days]
  (let [{:keys [name valid-until-ms]} (agent-session/parse-agent-name-valid-until agent-name)]
    (cond
      (nil? name) nil
      (some? valid-until-ms) (some-> agent-name str str/trim)
      :else (agent-session/format-agent-name-with-valid-until name now-ms days))))

(defn- ok-response
  []
  #js {:ok true
       :status 200
       :json (fn []
               (js/Promise.resolve
                #js {:status "ok"
                     :response #js {:type "default"}}))})

(defn- set-step!
  [store step agent-address]
  (when store
    (swap! store assoc-in state-path {:step step
                                      :agent-address agent-address})))

(defn- clear-step!
  [store]
  (when store
    (swap! store update-in [:wallet :agent] dissoc :vault-registration)))

(defn- wait-until-registered!
  [{:keys [owner-address agent-address fetch-user-role! set-timeout-fn now-ms-fn
           poll-ms timeout-ms]}]
  (let [started-at (now-ms-fn)]
    (js/Promise.
     (fn [resolve reject*]
       (letfn [(again []
                 (if (>= (- (now-ms-fn) started-at) timeout-ms)
                   (reject* (js/Error. registration-timeout-message))
                   (set-timeout-fn poll poll-ms)))
               (poll []
                 (-> (js/Promise.resolve nil)
                     (.then (fn [_] (fetch-user-role! agent-address)))
                     (.then (fn [role]
                              (if (trading-http/user-role-agent-for-owner? owner-address role)
                                (resolve true)
                                (again))))
                     ;; A failed read says nothing about the registration.
                     (.catch (fn [_] (again)))))]
         (poll))))))

(defn register-agent!
  "Send the CoreWriter registration of `agent-address` for `owner-address`
   through `provider` and resolve with a response-like `{status: \"ok\"}`
   once Hyperliquid reports the key as the account's agent."
  [{:keys [store provider owner-address agent-address agent-name
           ensure-wallet-chain! send-transaction! fetch-user-role!
           set-timeout-fn now-ms-fn poll-ms timeout-ms valid-days]
    :or {ensure-wallet-chain! wallet-rpc/ensure-wallet-chain!
         send-transaction! wallet-rpc/send-transaction!
         fetch-user-role! trading-http/fetch-user-role!
         set-timeout-fn platform/set-timeout!
         now-ms-fn platform/now-ms
         poll-ms default-poll-ms
         timeout-ms default-timeout-ms
         valid-days default-valid-days}}]
  (let [owner* (abi/normalize-address owner-address)
        agent* (abi/normalize-address agent-address)
        tx (when agent*
             (core-writer/add-api-wallet-tx
              agent*
              (name-with-valid-until agent-name (now-ms-fn) valid-days)))]
    (cond
      (nil? provider)
      (reject "No wallet provider found. Connect your wallet first.")

      (nil? owner*)
      (reject "Connect your wallet before enabling trading.")

      (= agent* agent-session/zero-address)
      (reject removal-unsupported-message)

      (nil? tx)
      (reject "Unable to build the trading key registration.")

      :else
      (-> (js/Promise.resolve nil)
          (.then (fn [_]
                   (set-step! store :wallet agent*)
                   (ensure-wallet-chain! provider (hyperevm-chain-config))))
          (.then (fn [_]
                   (send-transaction! provider owner* tx)))
          (.then (fn [_]
                   (set-step! store :hyperliquid agent*)
                   (wait-until-registered!
                    {:owner-address owner*
                     :agent-address agent*
                     :fetch-user-role! fetch-user-role!
                     :set-timeout-fn set-timeout-fn
                     :now-ms-fn now-ms-fn
                     :poll-ms poll-ms
                     :timeout-ms timeout-ms})))
          (.then (fn [_]
                   (clear-step! store)
                   (ok-response)))
          (.catch (fn [err]
                    (clear-step! store)
                    (js/Promise.reject err)))))))

(defn approve-agent!
  "Drop-in for `hyperopen.api.trading/approve-agent!`. A contract account on
   HyperEVM registers the key through CoreWriter; every other account signs
   `approveAgent` as before.

   The 4-arity takes overrides: `:approve-with-signature!`, `:detect!`,
   `:provider` and everything `register-agent!` accepts."
  ([store address action]
   (approve-agent! {} store address action))
  ([{:keys [approve-with-signature! detect! provider]
     :or {approve-with-signature! trading-api/approve-agent!
          detect! contract-account/detect-for-store!}
     :as deps}
    store address action]
   (if-not (contract-account/candidate? store)
     (approve-with-signature! store address action)
     (-> (detect! store address)
         (.then (fn [contract?]
                  (if contract?
                    (register-agent!
                     (merge (dissoc deps :approve-with-signature! :detect!)
                            {:store store
                             :provider (or provider (provider-registry/provider))
                             :owner-address address
                             :agent-address (:agentAddress action)
                             :agent-name (:agentName action)}))
                    (approve-with-signature! store address action))))))))
