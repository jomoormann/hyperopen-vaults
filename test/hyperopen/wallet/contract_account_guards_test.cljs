(ns hyperopen.wallet.contract-account-guards-test
  "What a contract account must not be able to do: weaken how its trading
   key is stored, or be asked for a wallet signature Hyperliquid cannot
   accept."
  (:require [cljs.test :refer-macros [async deftest is use-fixtures]]
            [hyperopen.api.trading.user-actions :as user-actions]
            [hyperopen.runtime.effect-adapters.wallet :as wallet-effects]
            [hyperopen.wallet.contract-account :as contract-account]))

(def ^:private vault-address "0x0eef159d0f5fe7d186bd105fce15b46c422141cf")

(use-fixtures :each
  {:before (fn [] (contract-account/reset-detections!))
   :after (fn [] (contract-account/reset-detections!))})

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

(deftest contract-account-is-not-asked-for-user-signatures-test
  (async done
    (contract-account/remember! vault-address true)
    (let [store (vault-store)]
      (-> (user-actions/submit-usd-class-transfer!
           store
           vault-address
           {:type "usdClassTransfer" :amount "1" :toPerp true})
          (.then (fn [_]
                   (is false "must not resolve")
                   (done)))
          (.catch (fn [err]
                    (is (= user-actions/contract-account-user-action-message
                           (.-message err)))
                    (is (nil? (get-in @store [:wallet :user-signed-nonce-cursor]))
                        "no nonce was taken, nothing was signed")
                    (done)))))))
