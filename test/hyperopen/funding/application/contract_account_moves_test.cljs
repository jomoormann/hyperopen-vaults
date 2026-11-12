(ns hyperopen.funding.application.contract-account-moves-test
  "A contract account (a Safe, for example) moving USDC between its own
   HyperEVM balance and its Spot balance. Its transactions are handed to the
   wallet and may be executed much later, so no run follows a receipt."
  (:require [cljs.test :refer-macros [async deftest is testing]]
            [hyperopen.funding.application.submit-effects :as effects]
            [hyperopen.funding.domain.evm-transfer-preview :as evm-preview]
            [hyperopen.funding.test-support.hyperevm-effects
             :refer [core->evm evm->core store-for harness run-of]]
            [hyperopen.funding.test-support.hyperevm-transfer :as support]
            [hyperopen.hyperevm.domain.abi :as abi]
            [hyperopen.hyperevm.domain.transfer-state :as transfer-state]
            [hyperopen.test-support.async :as async-support]))

(def ^:private usdc-to-evm
  {:transfer-from :spot :transfer-to :hyperevm :transfer-asset support/usdc-index})

(def ^:private usdc-to-spot
  {:transfer-from :hyperevm :transfer-to :spot :transfer-asset support/usdc-index})

(def ^:private hype-to-spot
  {:transfer-from :hyperevm :transfer-to :spot :transfer-asset support/hype-index})

(def ^:private usdc-contract "0xb88339cb7199b77e23db6e890353e22632ba630f")
(def ^:private core-deposit-wallet "0x6b9e773128f453f5c2c60935ee2de2cbc5390a24")

(defn- contract-store
  [modal]
  (let [store (store-for modal)]
    (swap! store assoc-in [:wallet :contract-account-address] support/owner)
    store))

(defn- sender
  "A transaction sender that records what it is handed and answers
   `result`."
  [sent result]
  (fn [from txs]
    (swap! sent conj [from txs])
    result))

(deftest contract-account-deposits-usdc-with-an-approval-and-a-deposit-test
  (async done
    (let [store (contract-store usdc-to-spot)
          sent (atom [])
          {:keys [deps toasts dispatches]}
          (harness store (evm->core @store support/usdc-index "250.5")
                   {:send-contract-account-transactions!
                    (sender sent (js/Promise.resolve [{:hash nil :queued? true}
                                                      {:hash nil :queued? true}]))
                    :submit-hyperevm-to-core! (fn [& _]
                                                (throw (js/Error. "must not follow receipts")))})]
      (-> (effects/api-submit-funding-transfer! deps)
          (.then (fn [_]
                   (let [[[from [approve deposit]]] @sent]
                     (is (= 1 (count @sent)))
                     (is (= support/owner from))
                     (is (= {:from support/owner
                             :to usdc-contract
                             :chainId "0x3e7"
                             :data (abi/encode-approve core-deposit-wallet (js/BigInt "250500000"))}
                            approve)
                         "exactly the amount, to Circle's CoreDepositWallet")
                     (is (= {:from support/owner
                             :to core-deposit-wallet
                             :chainId "0x3e7"
                             :data (abi/encode-core-deposit (js/BigInt "250500000") 4294967295)}
                            deposit)
                         "credits the sender's own Spot balance"))
                   (is (false? (get-in @store [:funding-ui :modal :open?])))
                   (is (= [[:success effects/contract-account-sent-message]] @toasts))
                   (is (nil? (run-of store)) "no run follows a receipt")
                   (is (nil? (transfer-state/in-flight-entry @store support/owner))
                       "nothing blocks the next move")
                   (is (some #{[:actions/load-user-data support/owner]} @dispatches))
                   (done)))
          (.catch (async-support/unexpected-error done))))))

(deftest contract-account-deposit-is-usdc-only-test
  (async done
    (let [store (contract-store hype-to-spot)
          sent (atom [])
          {:keys [deps toasts]}
          (harness store (evm->core @store support/hype-index "1")
                   {:send-contract-account-transactions! (sender sent (js/Promise.resolve []))})]
      (-> (js/Promise.resolve (effects/api-submit-funding-transfer! deps))
          (.then (fn [_]
                   (is (= [] @sent) "nothing reached the wallet")
                   (is (= [[:error effects/contract-account-usdc-only-message]] @toasts))
                   (is (true? (get-in @store [:funding-ui :modal :open?])))
                   (done)))
          (.catch (async-support/unexpected-error done))))))

(deftest contract-account-deposit-shows-a-wallet-refusal-test
  (async done
    (let [store (contract-store usdc-to-spot)
          {:keys [deps toasts]}
          (harness store (evm->core @store support/usdc-index "10")
                   {:send-contract-account-transactions!
                    (sender (atom []) (js/Promise.reject (js/Error. "User rejected the request.")))})]
      (-> (effects/api-submit-funding-transfer! deps)
          (.then (fn [_]
                   (is (= [[:error "Transfer failed: User rejected the request."]] @toasts))
                   (is (true? (get-in @store [:funding-ui :modal :open?])))
                   (done)))
          (.catch (async-support/unexpected-error done))))))

(deftest contract-account-withdraw-hands-the-send-over-and-closes-test
  (async done
    (let [store (contract-store usdc-to-evm)
          {:keys [deps signed toasts]} (harness store (core->evm @store support/usdc-index "100") {})]
      (-> (effects/api-submit-funding-transfer! deps)
          (.then (fn [_]
                   (is (= [[:send-asset support/owner
                            {:type "sendAsset"
                             :destination "0x2000000000000000000000000000000000000000"
                             :sourceDex "spot"
                             :destinationDex "spot"
                             :token (get-in (first @signed) [2 :token])
                             :amount "100"
                             :fromSubAccount ""}]]
                          @signed))
                   (is (false? (get-in @store [:funding-ui :modal :open?])))
                   (is (= [[:success effects/contract-account-sent-message]] @toasts))
                   (is (nil? (run-of store)) "no arrival is watched")
                   (done)))
          (.catch (async-support/unexpected-error done))))))

(deftest an-ordinary-wallet-keeps-the-hyperevm-runs-test
  (async done
    (let [store (store-for usdc-to-evm)
          {:keys [deps]} (harness store (core->evm @store support/usdc-index "100") {})]
      (-> (effects/api-submit-funding-transfer! deps)
          (.then (fn [_]
                   (is (true? (get-in @store [:funding-ui :modal :open?])))
                   (is (= :succeeded (:phase (run-of store))))
                   (done)))
          (.catch (async-support/unexpected-error done))))))

(deftest contract-account-needs-no-gas-of-its-own-to-deposit-test
  (let [no-gas (fn [state]
                 (support/with-evm-entry state (assoc support/evm-entry :native-wei "0")))
        preview (fn [state] (evm-preview/evm-transfer-preview state
                                                              (get-in state [:funding-ui :modal])
                                                              support/now-ms))
        modal (assoc usdc-to-spot :amount-input "100")
        ordinary (no-gas (support/state modal))
        contract (assoc-in ordinary [:wallet :contract-account-address] support/owner)]
    (is (= :no-evm-gas (get-in (preview ordinary) [:blocked :code])))
    (testing "whoever executes a contract account's transaction pays for it"
      (is (true? (:ok? (preview contract))))
      (is (= "usdcCoreDeposit" (get-in (preview contract) [:request :action :kind]))))
    (testing "a note about another address changes nothing"
      (is (= :no-evm-gas
             (get-in (preview (assoc-in ordinary [:wallet :contract-account-address]
                                        support/subaccount))
                     [:blocked :code]))))))
