(ns hyperopen.wallet.contract-account-test
  (:require [cljs.test :refer-macros [async deftest is testing use-fixtures]]
            [hyperopen.wallet.contract-account :as contract-account]))

(def ^:private safe-address "0x0eef159d0f5fe7d186bd105fce15b46c422141cf")

(use-fixtures :each
  {:before (fn [] (contract-account/reset-detections!))
   :after (fn [] (contract-account/reset-detections!))})

(deftest hyperevm-chain-accepts-hex-and-decimal-test
  (is (true? (contract-account/hyperevm-chain? "0x3e7")))
  (is (true? (contract-account/hyperevm-chain? "0x3E7")))
  (is (true? (contract-account/hyperevm-chain? 999)))
  (is (true? (contract-account/hyperevm-chain? "999")))
  (is (false? (contract-account/hyperevm-chain? "0xa4b1")))
  (is (false? (contract-account/hyperevm-chain? "0x3e6")))
  (is (false? (contract-account/hyperevm-chain? nil))))

(deftest contract-code-test
  (testing "an account without code is not a contract"
    (is (false? (contract-account/contract-code? "0x")))
    (is (false? (contract-account/contract-code? "0x0")))
    (is (false? (contract-account/contract-code? nil)))
    (is (false? (contract-account/contract-code? "not hex"))))
  (testing "contract code is"
    (is (true? (contract-account/contract-code? "0x608060405234801561001057600080fd"))))
  (testing "an EIP-7702 delegation still signs with its own key"
    (is (false? (contract-account/contract-code?
                 "0xef010063c0c19a282a1b52b07dd5a65b58948a07dae32b"))))
  (testing "code that merely starts like a delegation is a contract"
    (is (true? (contract-account/contract-code? "0xef0100aabb")))))

(deftest detect-reads-code-once-and-remembers-test
  (async done
    (let [reads (atom [])
          read-code! (fn [address]
                       (swap! reads conj address)
                       (js/Promise.resolve "0x6080"))
          mixed-case "0x0EEf159D0F5fe7d186BD105fCE15B46C422141cF"]
      (-> (contract-account/detect! {:address mixed-case
                                     :chain-id "0x3e7"
                                     :read-code! read-code!})
          (.then (fn [contract?]
                   (is (true? contract?))
                   (is (= [safe-address] @reads))
                   (is (true? (contract-account/known-contract-account? mixed-case)))
                   (contract-account/detect! {:address safe-address
                                              :chain-id "0x3e7"
                                              :read-code! read-code!})))
          (.then (fn [_]
                   (is (= 1 (count @reads)) "the second answer comes from memory")
                   (done)))
          (.catch (fn [err]
                    (is false (str "unexpected rejection: " err))
                    (done)))))))

(deftest detect-skips-the-read-off-hyperevm-test
  (async done
    (let [reads (atom 0)
          read-code! (fn [_] (swap! reads inc) (js/Promise.resolve "0x6080"))]
      (-> (contract-account/detect! {:address safe-address
                                     :chain-id "0xa4b1"
                                     :read-code! read-code!})
          (.then (fn [contract?]
                   (is (false? contract?))
                   (is (= 0 @reads))
                   (is (nil? (contract-account/known-contract-account? safe-address))
                       "nothing is remembered, the wallet may move to HyperEVM later")
                   (done)))))))

(deftest detect-rejects-and-forgets-a-failed-read-test
  (async done
    (let [attempt (atom 0)
          read-code! (fn [_]
                       (if (= 1 (swap! attempt inc))
                         (js/Promise.reject (js/Error. "rate limited"))
                         (js/Promise.resolve "0x")))]
      (-> (contract-account/detect! {:address safe-address
                                     :chain-id "0x3e7"
                                     :read-code! read-code!})
          (.then (fn [_]
                   (is false "a failed read must not resolve")))
          (.catch (fn [err]
                    (is (= contract-account/detection-failed-message (.-message err)))
                    (is (nil? (contract-account/known-contract-account? safe-address)))
                    (contract-account/detect! {:address safe-address
                                               :chain-id "0x3e7"
                                               :read-code! read-code!})))
          (.then (fn [contract?]
                   (is (false? contract?))
                   (is (false? (contract-account/known-contract-account? safe-address)))
                   (done)))))))

(deftest candidate-reads-the-store-chain-test
  (is (true? (contract-account/candidate? (atom {:wallet {:chain-id "0x3e7"}}))))
  (is (false? (contract-account/candidate? (atom {:wallet {:chain-id "0x1"}}))))
  (is (false? (contract-account/candidate? (atom {})))))

(deftest detect-for-store-notes-the-answer-for-the-views-test
  (async done
    (let [vault "0x0eef159d0f5fe7d186bd105fce15b46c422141cf"
          other "0x2222222222222222222222222222222222222222"
          store (atom {:wallet {:connected? true
                                :address vault
                                :chain-id "0x3e7"}})]
      (is (false? (contract-account/contract-account-state? @store)))
      (contract-account/remember! vault true)
      (contract-account/remember! other false)
      (-> (contract-account/detect-for-store! store)
          (.then (fn [contract?]
                   (is (true? contract?))
                   (is (true? (contract-account/contract-account-state? @store)))
                   (testing "the note is about one address"
                     (is (false? (contract-account/contract-account-state?
                                  (assoc-in @store [:wallet :address] other))))
                     (is (false? (contract-account/contract-account-state?
                                  (assoc-in @store [:wallet :connected?] false)))))
                   (swap! store assoc-in [:wallet :address] other)
                   (contract-account/detect-for-store! store)))
          (.then (fn [contract?]
                   (is (false? contract?))
                   (is (nil? (get-in @store [:wallet :contract-account-address])))
                   (done)))
          (.catch (fn [err]
                    (is false (str "unexpected: " err))
                    (done)))))))

(deftest install-detection-checks-on-connect-and-on-chain-change-test
  (async done
    (let [vault "0x0eef159d0f5fe7d186bd105fce15b46c422141cf"
          store (atom {:wallet {:connected? false :address nil :chain-id "0xa4b1"}})
          settle (fn [] (js/Promise. (fn [resolve _] (js/setTimeout resolve 0))))]
      (contract-account/remember! vault true)
      (contract-account/install-detection! store)
      (swap! store update :wallet merge {:connected? true :address vault})
      (-> (settle)
          (.then (fn [_]
                   (is (false? (contract-account/contract-account-state? @store))
                       "not on HyperEVM: no check, no note")
                   (swap! store assoc-in [:wallet :chain-id] "0x3e7")
                   (settle)))
          (.then (fn [_]
                   (is (true? (contract-account/contract-account-state? @store)))
                   (remove-watch store ::contract-account/detect)
                   (done)))
          (.catch (fn [err]
                    (is false (str "unexpected: " err))
                    (done)))))))

(def ^:private pilot-not-simulated-message
  ;; The text Zodiac Pilot rejects with on a chain it cannot simulate
  ;; (`NOT_SIMULATED_ERROR` in its `useSendTransaction.ts`).
  "Pilot added this transaction to the batch without simulating it, as this chain cannot be simulated.")

(deftest queued-by-wallet-reads-pilots-answer-test
  (is (true? (contract-account/queued-by-wallet? (js/Error. pilot-not-simulated-message))))
  (is (true? (contract-account/queued-by-wallet?
              #js {:code -32603 :message pilot-not-simulated-message}))
      "a plain EIP-1193 error object")
  (is (true? (contract-account/queued-by-wallet?
              (ex-info pilot-not-simulated-message {:code -32603}))))
  (is (false? (contract-account/queued-by-wallet? (js/Error. "User rejected the request."))))
  (is (false? (contract-account/queued-by-wallet? #js {:code 4001})))
  (is (false? (contract-account/queued-by-wallet? nil))))

(deftest send-transaction-accepts-a-hash-or-a-queued-transaction-test
  (async done
    (let [send (fn [answer]
                 (-> (contract-account/send-transaction! (fn [_provider _from _tx] answer)
                                                         #js {} "0xowner" {:to "0xto"})
                     (.catch (fn [err] [:refused (.-message err)]))))]
      (-> (js/Promise.all
           #js [(send (js/Promise.resolve "0xhash"))
                (send (js/Promise.reject (js/Error. pilot-not-simulated-message)))
                (send (js/Promise.reject (js/Error. "User rejected the request.")))])
          (.then (fn [results]
                   (is (= [{:hash "0xhash"}
                           {:hash nil :queued? true}
                           [:refused "User rejected the request."]]
                          (vec results)))
                   (done)))
          (.catch (fn [err]
                    (is false (str "unexpected: " err))
                    (done)))))))
