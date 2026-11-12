(ns hyperopen.wallet.contract-account-guards-test
  "What a contract account must not be able to do: weaken how its trading
   key is stored, or be asked for a wallet signature Hyperliquid cannot
   accept."
  (:require [cljs.test :refer-macros [async deftest is use-fixtures]]
            [hyperopen.api.trading.agent-actions :as agent-actions]
            [hyperopen.api.trading.user-actions :as user-actions]
            [hyperopen.hyperevm.domain.core-writer :as core-writer]
            [hyperopen.hyperevm.domain.tokens :as tokens]
            [hyperopen.hyperevm.test-support.bridge-fixtures :as bridge-fixtures]
            [hyperopen.runtime.effect-adapters.wallet :as wallet-effects]
            [hyperopen.wallet.contract-account :as contract-account]
            [hyperopen.wallet.provider-registry :as provider-registry]))

(def ^:private vault-address "0x0eef159d0f5fe7d186bd105fce15b46c422141cf")

(use-fixtures :each
  {:before (fn [] (contract-account/reset-detections!))
   :after (fn []
            (contract-account/reset-detections!)
            (provider-registry/clear-provider-override!))})

(defn- fake-wallet
  "An EIP-1193 provider on HyperEVM that records what it is asked.
   `send-result` is what `eth_sendTransaction` answers."
  [requests send-result]
  #js {:request (fn [args]
                  (let [method (unchecked-get args "method")
                        params (js->clj (unchecked-get args "params") :keywordize-keys true)]
                    (swap! requests conj [method params])
                    (case method
                      "eth_chainId" (js/Promise.resolve "0x3e7")
                      "wallet_switchEthereumChain" (js/Promise.resolve nil)
                      "eth_sendTransaction" send-result
                      (js/Promise.reject (js/Error. (str "unexpected " method))))))})

(defn- vault-store
  []
  (atom {:wallet {:address vault-address
                  :chain-id "0x3e7"
                  :agent {:status :ready
                          :storage-mode :local
                          :local-protection-mode :passkey
                          :agent-address "0xagent"}}}))

(deftest contract-account-cannot-turn-off-the-passkey-test
  (contract-account/remember! vault-address true)
  (let [store (vault-store)]
    (wallet-effects/set-agent-local-protection-mode nil store :plain)
    (is (= :passkey (get-in @store [:wallet :agent :local-protection-mode])))
    (is (= :ready (get-in @store [:wallet :agent :status])))
    (is (= wallet-effects/contract-account-key-storage-message
           (get-in @store [:wallet :agent :error])))))

(deftest contract-account-cannot-switch-to-session-storage-test
  (contract-account/remember! vault-address true)
  (let [store (vault-store)]
    (wallet-effects/set-agent-storage-mode nil store :session)
    (is (= :local (get-in @store [:wallet :agent :storage-mode])))
    (is (= :ready (get-in @store [:wallet :agent :status])))
    (is (= wallet-effects/contract-account-key-storage-message
           (get-in @store [:wallet :agent :error])))))

(def ^:private usdc-wire-id "USDC:0x6d1e7cde53ba9467b783cb7c530ce054")

(defn- funded-vault-store
  "A vault store that knows Hyperliquid's spot tokens."
  []
  (let [store (vault-store)]
    (swap! store assoc-in [:spot :meta] bridge-fixtures/bridge-spot-meta)
    store))

(def ^:private usdc-to-own-evm
  {:type "sendAsset"
   :destination "0x2000000000000000000000000000000000000000"
   :sourceDex "spot"
   :destinationDex "spot"
   :token usdc-wire-id
   :amount "250.5"
   :fromSubAccount ""})

(deftest the-fixture-names-usdc-as-hyperliquid-does-test
  (is (= usdc-wire-id
         (:wire-id (tokens/token-by-index bridge-fixtures/bridge-spot-meta 0)))))

(deftest contract-account-moves-usdc-from-spot-to-its-own-hyperevm-balance-test
  (async done
    (contract-account/remember! vault-address true)
    (let [requests (atom [])
          store (funded-vault-store)]
      (provider-registry/set-provider-override!
       (fake-wallet requests (js/Promise.resolve "0xhash")))
      (-> (user-actions/submit-send-asset! store vault-address usdc-to-own-evm)
          (.then (fn [response]
                   (is (= "ok" (:status response)))
                   (let [sent (filter #(= "eth_sendTransaction" (first %)) @requests)
                         [[_ [tx]]] sent]
                     (is (= 1 (count sent)))
                     (is (= vault-address (:from tx)))
                     (is (= core-writer/core-writer-address (:to tx)))
                     (is (= (core-writer/encode-spot-usdc-to-evm "250.5") (:data tx))))
                   (is (empty? (filter #(re-find #"sign" (first %)) @requests)))
                   (is (nil? (get-in @store [:wallet :user-signed-nonce-cursor])))
                   (done)))
          (.catch (fn [err]
                    (is false (str "unexpected: " (.-message err)))
                    (done)))))))

(deftest contract-account-send-goes-through-when-the-wallet-queues-it-test
  (async done
    (contract-account/remember! vault-address true)
    (provider-registry/set-provider-override!
     (fake-wallet (atom [])
                  (js/Promise.reject
                   (js/Error. (str "Pilot added this transaction to the batch without "
                                   "simulating it, as this chain cannot be simulated.")))))
    (-> (user-actions/submit-send-asset! (funded-vault-store) vault-address usdc-to-own-evm)
        (.then (fn [response]
                 (is (= "ok" (:status response)))
                 (done)))
        (.catch (fn [err]
                  (is false (str "unexpected: " (.-message err)))
                  (done))))))

(deftest contract-account-sends-nothing-to-anyone-else-test
  (async done
    (contract-account/remember! vault-address true)
    (let [requests (atom [])
          store (funded-vault-store)
          refused (fn [action]
                    (-> (user-actions/submit-send-asset! store vault-address action)
                        (.then (fn [_] ::resolved))
                        (.catch (fn [err] (.-message err)))))
          stranger "0x1111111111111111111111111111111111111111"]
      (provider-registry/set-provider-override!
       (fake-wallet requests (js/Promise.resolve "0xhash")))
      (-> (js/Promise.all
           #js [(refused (assoc usdc-to-own-evm :destination stranger))
                (refused (assoc usdc-to-own-evm :destination vault-address))
                (refused (assoc usdc-to-own-evm
                                :destination "0x2000000000000000000000000000000000000001"))
                (refused (assoc usdc-to-own-evm :token "PURR:0xc1fb593aeffbeb02f85e0308e9956a90"))
                (refused (assoc usdc-to-own-evm :token "USDC:0xdeadbeefdeadbeefdeadbeefdeadbeef"))
                (refused (assoc usdc-to-own-evm :sourceDex ""))
                (refused (assoc usdc-to-own-evm :destinationDex "xyz"))
                (refused (assoc usdc-to-own-evm :fromSubAccount stranger))
                (refused (assoc usdc-to-own-evm :amount "1.000000001"))
                (-> (user-actions/submit-send-asset! (vault-store) vault-address usdc-to-own-evm)
                    (.then (fn [_] ::resolved))
                    (.catch (fn [err] (.-message err))))])
          (.then (fn [messages]
                   (is (= (concat (repeat 8 user-actions/contract-account-send-asset-message)
                                  [user-actions/contract-account-withdraw-invalid-message
                                   ;; Without the spot metadata USDC cannot be
                                   ;; told from a token that only has its name.
                                   user-actions/contract-account-send-asset-message])
                          (vec messages)))
                   (is (= [] @requests) "nothing reached the wallet")
                   (is (nil? (get-in @store [:wallet :user-signed-nonce-cursor]))
                       "no nonce was taken, nothing was signed")
                   (done)))))))

(deftest contract-account-is-not-asked-for-user-signatures-test
  (async done
    (contract-account/remember! vault-address true)
    (let [store (vault-store)]
      (-> (user-actions/submit-withdraw3!
           store
           vault-address
           {:type "withdraw3"
            :destination "0x1111111111111111111111111111111111111111"
            :amount "1"})
          (.then (fn [_]
                   (is false "must not resolve")
                   (done)))
          (.catch (fn [err]
                    (is (= user-actions/contract-account-user-action-message
                           (.-message err)))
                    (is (nil? (get-in @store [:wallet :user-signed-nonce-cursor]))
                        "no nonce was taken, nothing was signed")
                    (done)))))))

(deftest contract-account-key-signs-trading-actions-only-test
  (let [allowed? (fn [action-type]
                   (agent-actions/contract-account-action-allowed?
                    vault-address
                    {:type action-type}))]
    (contract-account/remember! vault-address true)
    (doseq [action-type ["order" "cancel" "cancelByCloid" "modify" "batchModify"
                         "twapOrder" "twapCancel" "scheduleCancel"
                         "updateLeverage" "updateIsolatedMargin"]]
      (is (true? (allowed? action-type)) action-type))
    (doseq [action-type ["vaultTransfer" "subAccountTransfer" "subAccountSpotTransfer"
                         "createSubAccount" "subAccountModify" "borrowLend"
                         "setReferrer" "registerReferrer" "claimRewards"
                         "userOutcome" "somethingNew" nil]]
      (is (false? (allowed? action-type)) (str action-type)))
    (is (true? (agent-actions/contract-account-action-allowed?
                vault-address
                #js {:type "order"}))
        "a JS action object is read too")
    (contract-account/remember! vault-address false)
    (is (true? (allowed? "vaultTransfer")) "an ordinary wallet's key is not limited")
    (contract-account/reset-detections!)
    (is (true? (allowed? "vaultTransfer")) "nor is an account that was not checked")))

(deftest contract-account-key-refuses-a-fund-move-before-it-signs-test
  (async done
    (contract-account/remember! vault-address true)
    (let [store (vault-store)
          refused (fn [promise]
                    (-> promise
                        (.then (fn [_] ::resolved))
                        (.catch (fn [err] (.-message err)))))]
      (-> (js/Promise.all
           #js [(refused (agent-actions/submit-vault-transfer!
                          store
                          vault-address
                          {:type "vaultTransfer"
                           :vaultAddress "0x1111111111111111111111111111111111111111"
                           :isDeposit true
                           :usd 1000000}))
                (refused (agent-actions/resolve-signing-context!
                          store
                          vault-address
                          {:type "subAccountTransfer"}))])
          (.then (fn [messages]
                   (is (= [agent-actions/contract-account-agent-action-message
                           agent-actions/contract-account-agent-action-message]
                          (vec messages)))
                   (is (nil? (get-in @store [:wallet :agent :nonce-cursor]))
                       "no nonce was taken, nothing was signed")
                   (done)))))))

(deftest contract-account-moves-usd-between-perp-and-spot-with-a-transaction-test
  (async done
    (contract-account/remember! vault-address true)
    (let [requests (atom [])
          store (vault-store)]
      (provider-registry/set-provider-override!
       (fake-wallet requests (js/Promise.resolve "0xhash")))
      (-> (user-actions/submit-usd-class-transfer!
           store
           vault-address
           {:type "usdClassTransfer" :amount "250.5" :toPerp false})
          (.then (fn [response]
                   (is (= "ok" (:status response)))
                   (let [sent (filter #(= "eth_sendTransaction" (first %)) @requests)
                         [[_ [tx]]] sent]
                     (is (= 1 (count sent)))
                     (is (= vault-address (:from tx)))
                     (is (= core-writer/core-writer-address (:to tx)))
                     (is (= (core-writer/encode-usd-class-transfer "250.5" false)
                            (:data tx))))
                   (is (empty? (filter #(re-find #"sign" (first %)) @requests))
                       "the wallet was not asked for a signature")
                   (is (nil? (get-in @store [:wallet :user-signed-nonce-cursor])))
                   (done)))
          (.catch (fn [err]
                    (is false (str "unexpected: " (.-message err)))
                    (done)))))))

(deftest contract-account-transfer-refuses-an-amount-it-would-have-to-round-test
  (async done
    (contract-account/remember! vault-address true)
    (let [requests (atom [])]
      (provider-registry/set-provider-override!
       (fake-wallet requests (js/Promise.resolve "0xhash")))
      (-> (user-actions/submit-usd-class-transfer!
           (vault-store)
           vault-address
           {:type "usdClassTransfer" :amount "1.0000001" :toPerp true})
          (.then (fn [_]
                   (is false "must not resolve")
                   (done)))
          (.catch (fn [err]
                    (is (= user-actions/contract-account-transfer-invalid-message
                           (.-message err)))
                    (is (= [] @requests) "nothing reached the wallet")
                    (done)))))))

(deftest contract-account-transfer-surfaces-a-wallet-refusal-test
  (async done
    (contract-account/remember! vault-address true)
    (provider-registry/set-provider-override!
     (fake-wallet (atom []) (js/Promise.reject (js/Error. "User rejected the request."))))
    (-> (user-actions/submit-usd-class-transfer!
         (vault-store)
         vault-address
         {:type "usdClassTransfer" :amount "1" :toPerp true})
        (.then (fn [_]
                 (is false "must not resolve")
                 (done)))
        (.catch (fn [err]
                  (is (re-find #"rejected" (.-message err)))
                  (done))))))
