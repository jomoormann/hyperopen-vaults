(ns hyperopen.funding.application.contract-account-moves-test
  "A contract account (a Safe, for example) moving USDC between its own
   HyperEVM balance and its Spot balance. Its transactions are handed to the
   wallet and may be executed much later, so no run follows a receipt."
  (:require [cljs.test :refer-macros [async deftest is testing]]
            [hyperopen.funding.actions :as funding-actions]
            [hyperopen.funding.application.submit-effects :as effects]
            [hyperopen.funding.domain.evm-transfer-preview :as evm-preview]
            [hyperopen.funding.domain.transfer-invariants :as transfer-invariants]
            [hyperopen.funding.domain.transfer-route :as transfer-route]
            [hyperopen.funding.test-support.hyperevm-effects
             :refer [core->evm evm->core store-for harness run-of token]]
            [hyperopen.hyperevm.domain.core-writer :as core-writer]
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

(deftest contract-account-deposits-hype-as-value-to-its-system-address-test
  (async done
    (let [store (contract-store hype-to-spot)
          sent (atom [])
          {:keys [deps toasts]}
          (harness store (evm->core @store support/hype-index "1")
                   {:send-contract-account-transactions! (sender sent (js/Promise.resolve [{:hash nil :queued? true}]))
                    :submit-hyperevm-to-core! (fn [& _]
                                                (throw (js/Error. "must not follow receipts")))})]
      (-> (js/Promise.resolve (effects/api-submit-funding-transfer! deps))
          (.then (fn [_]
                   (let [[[from [tx]]] @sent]
                     (is (= 1 (count @sent)))
                     (is (= support/owner from))
                     (is (= {:from support/owner
                             :to "0x2222222222222222222222222222222222222222"
                             :chainId "0x3e7"
                             :value "0xde0b6b3a7640000"}
                            tx)
                         "1 HYPE as value to HYPE's system address, which credits the sender"))
                   (is (= [[:success effects/contract-account-sent-message]] @toasts))
                   (is (false? (get-in @store [:funding-ui :modal :open?])))
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

;; ---------- Perps <-> HyperEVM in one batch ----------------------------------------

(def ^:private usdc-perps-to-evm
  {:transfer-from :perps :transfer-to :hyperevm :transfer-asset support/usdc-index})

(def ^:private usdc-evm-to-perps
  {:transfer-from :hyperevm :transfer-to :perps :transfer-asset support/usdc-index})

(deftest only-a-contract-account-moves-between-perps-and-hyperevm-directly-test
  (testing "the route is direct only in a contract account's modal"
    (is (true? (:valid? (transfer-route/transfer-route
                         (assoc usdc-perps-to-evm :transfer-direct? true)))))
    (is (true? (:valid? (transfer-route/transfer-route
                         (assoc usdc-evm-to-perps :transfer-direct? true)))))
    (is (false? (:valid? (transfer-route/transfer-route usdc-perps-to-evm))))
    (is (false? (:valid? (transfer-route/transfer-route usdc-evm-to-perps)))))
  (testing "a direct route keeps Perps and HyperEVM when they are chosen or swapped"
    (let [route (transfer-route/transfer-route
                 (assoc usdc-perps-to-evm :transfer-direct? true))]
      (is (= [:hyperevm :perps] (transfer-route/swapped route)))
      (is (= [:perps :hyperevm] (transfer-route/with-location route :to :hyperevm)))))
  (testing "an ordinary wallet still goes through Spot"
    (let [route (transfer-route/transfer-route usdc-perps-to-evm)]
      (is (= [:hyperevm :spot] (transfer-route/swapped route))))))

(deftest contract-account-opens-transfer-with-the-direct-route-test
  (let [modal-of (fn [state]
                   (some (fn [[effect-id path modal]]
                           (when (and (= :effects/save effect-id) (= [:funding-ui :modal] path))
                             modal))
                         (funding-actions/open-funding-transfer-modal state)))
        state (support/state {})]
    (is (false? (:transfer-direct? (modal-of state))))
    (is (true? (:transfer-direct? (modal-of (assoc-in state [:wallet :contract-account-address]
                                                      support/owner)))))))

(deftest contract-account-moves-perps-usdc-to-hyperevm-in-one-batch-test
  (async done
    (let [store (contract-store usdc-perps-to-evm)
          sent (atom [])
          request (evm-preview/core->evm-request @store {:from :perps :to :hyperevm}
                                                 (token @store support/usdc-index) "2.5")
          {:keys [deps signed toasts]}
          (harness store request
                   {:send-contract-account-transactions!
                    (sender sent (js/Promise.resolve [{:hash nil :queued? true}
                                                      {:hash nil :queued? true}]))})]
      (-> (effects/api-submit-funding-transfer! deps)
          (.then (fn [_]
                   (is (= [[support/owner
                            [(core-writer/usd-class-transfer-tx "2.5" false)
                             (core-writer/spot-usdc-to-evm-tx "2.5")]]]
                          @sent)
                       "Perps -> Spot, then Spot -> its own HyperEVM address, in one batch")
                   (is (empty? @signed) "nothing goes through the single-send path")
                   (is (= [[:success effects/contract-account-sent-message]] @toasts))
                   (is (false? (get-in @store [:funding-ui :modal :open?])))
                   (done)))
          (.catch (async-support/unexpected-error done))))))

(deftest contract-account-moves-only-usdc-from-perps-test
  (async done
    (let [store (contract-store usdc-perps-to-evm)
          sent (atom [])
          request (assoc-in (evm-preview/core->evm-request @store {:from :perps :to :hyperevm}
                                                           (token @store support/hype-index) "1")
                            [:evm :from] :perps)
          {:keys [deps toasts]}
          (harness store request
                   {:send-contract-account-transactions! (sender sent (js/Promise.resolve []))})]
      (-> (js/Promise.resolve (effects/api-submit-funding-transfer! deps))
          (.then (fn [_]
                   (is (= [] @sent))
                   (is (= [[:error effects/contract-account-perps-usdc-only-message]] @toasts))
                   (done)))
          (.catch (async-support/unexpected-error done))))))

(deftest contract-account-deposits-usdc-straight-into-perps-test
  (async done
    (let [store (contract-store usdc-evm-to-perps)
          sent (atom [])
          {:keys [deps toasts]}
          (harness store (evm->core @store support/usdc-index "250.5" :perps)
                   {:send-contract-account-transactions!
                    (sender sent (js/Promise.resolve [{:hash nil :queued? true}
                                                      {:hash nil :queued? true}]))})]
      (-> (effects/api-submit-funding-transfer! deps)
          (.then (fn [_]
                   (let [[[_ [approve deposit]]] @sent]
                     (is (= (abi/encode-approve core-deposit-wallet (js/BigInt "250500000"))
                            (:data approve)))
                     (is (= (abi/encode-core-deposit (js/BigInt "250500000") 0) (:data deposit))
                         "destination dex 0: the account's own Perps balance"))
                   (is (= [[:success effects/contract-account-sent-message]] @toasts))
                   (done)))
          (.catch (async-support/unexpected-error done))))))

(deftest only-a-contract-account-may-deposit-straight-into-perps-test
  (let [ordinary (support/state (assoc usdc-evm-to-perps :amount-input "10"))
        contract (assoc-in ordinary [:wallet :contract-account-address] support/owner)
        request (evm->core ordinary support/usdc-index "10" :perps)]
    (is (nil? (transfer-invariants/check-request contract request)))
    (is (re-find #"land in Spot" (transfer-invariants/check-request ordinary request)))
    (is (re-find #"only USDC"
                 (transfer-invariants/check-request
                  contract
                  (evm->core contract support/hype-index "1" :perps))))))

(deftest contract-account-previews-the-direct-routes-test
  (let [preview (fn [state] (evm-preview/evm-transfer-preview state
                                                            (get-in state [:funding-ui :modal])
                                                            support/now-ms))
        contract (fn [modal]
                   (assoc-in (support/state (assoc modal :amount-input "1" :transfer-direct? true))
                             [:wallet :contract-account-address] support/owner))
        to-evm (preview (contract usdc-perps-to-evm))
        to-perps (preview (contract usdc-evm-to-perps))]
    (is (true? (:ok? to-evm)) (pr-str (:blocked to-evm)))
    (is (= :perps (get-in to-evm [:request :evm :from])))
    (is (= "spot" (get-in to-evm [:request :action :sourceDex]))
        "the request's send leaves Spot; the submit moves Perps -> Spot first")
    (is (true? (:ok? to-perps)) (pr-str (:blocked to-perps)))
    (is (= 0 (get-in to-perps [:request :action :destinationDex])))
    (is (= :perps (get-in to-perps [:request :evm :to])))))
