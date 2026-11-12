(ns hyperopen.wallet.vault-registration-test
  (:require [cljs.test :refer-macros [async deftest is testing use-fixtures]]
            [hyperopen.hyperevm.domain.core-writer :as core-writer]
            [hyperopen.wallet.agent-runtime.enable :as enable]
            [hyperopen.wallet.contract-account :as contract-account]
            [hyperopen.wallet.vault-registration :as vault-registration]))

(def ^:private vault-address "0x0eef159d0f5fe7d186bd105fce15b46c422141cf")
(def ^:private agent-address "0x5ce4a06175e2258e0423110f504cac3912dc53d7")
(def ^:private now-ms 1800000000000)
(def ^:private ninety-days-ms (* 90 24 60 60 1000))

(use-fixtures :each
  {:before (fn [] (contract-account/reset-detections!))
   :after (fn [] (contract-account/reset-detections!))})

(defn- fail!
  [done]
  (fn [err]
    (is false (str "unexpected: " (or (some-> err .-message) err)))
    (done)))

(defn- registration-deps
  "Fakes for `register-agent!`. `roles` is the sequence of `userRole`
   answers, one per poll; the last one repeats."
  [{:keys [roles send-result]
    :or {send-result (js/Promise.resolve "0xhash")}}]
  (let [calls (atom {:chains [] :txs [] :role-reads [] :timers []})
        clock (atom now-ms)
        remaining (atom roles)]
    {:calls calls
     :clock clock
     :deps {:provider #js {}
            :owner-address vault-address
            :agent-address agent-address
            :agent-name "Desk ab12cd"
            :now-ms-fn (fn [] @clock)
            :poll-ms 6000
            :timeout-ms 20000
            :ensure-wallet-chain! (fn [_provider chain-config]
                                    (swap! calls update :chains conj chain-config)
                                    (js/Promise.resolve (:chain-id chain-config)))
            :send-transaction! (fn [_provider from tx]
                                 (swap! calls update :txs conj [from tx])
                                 send-result)
            :fetch-user-role! (fn [address]
                                (swap! calls update :role-reads conj address)
                                (let [role (first @remaining)]
                                  (when (next @remaining)
                                    (swap! remaining rest))
                                  (if (= ::read-error role)
                                    (js/Promise.reject (js/Error. "info down"))
                                    (js/Promise.resolve role))))
            ;; Each scheduled poll runs at once and advances the clock.
            :set-timeout-fn (fn [f ms]
                              (swap! calls update :timers conj ms)
                              (swap! clock + ms)
                              (js/Promise.resolve (f)))}}))

(def ^:private agent-of-vault
  {:role "agent" :data {:user vault-address}})

(deftest name-with-valid-until-test
  (is (= (str "Desk ab12cd valid_until " (+ now-ms ninety-days-ms))
         (vault-registration/name-with-valid-until "Desk ab12cd" now-ms 90)))
  (testing "an existing expiry is kept"
    (is (= "Desk valid_until 1700000000000"
           (vault-registration/name-with-valid-until
            " Desk valid_until 1700000000000 " now-ms 90))))
  (testing "the unnamed slot has no name to carry an expiry"
    (is (nil? (vault-registration/name-with-valid-until nil now-ms 90)))
    (is (nil? (vault-registration/name-with-valid-until "  " now-ms 90)))))

(deftest register-agent-sends-core-writer-tx-and-waits-for-hyperliquid-test
  (async done
    (let [{:keys [calls deps]} (registration-deps
                                {:roles [{:role "missing"}
                                         ::read-error
                                         agent-of-vault]})
          store (atom {:wallet {:agent {:status :approving}}})]
      (-> (vault-registration/register-agent! (assoc deps :store store))
          (.then (fn [response] (.json response)))
          (.then (fn [body]
                   (is (= "ok" (aget body "status")))
                   (let [{:keys [chains txs role-reads timers]} @calls
                         [[from tx]] txs]
                     (is (= ["0x3e7"] (mapv :chain-id chains)))
                     (is (true? (:verify-switch? (first chains))))
                     (is (= 1 (count txs)))
                     (is (= vault-address from))
                     (is (= core-writer/core-writer-address (:to tx)))
                     (is (= "0x3e7" (:chainId tx)))
                     (is (= (core-writer/encode-add-api-wallet
                             agent-address
                             (str "Desk ab12cd valid_until " (+ now-ms ninety-days-ms)))
                            (:data tx)))
                     (is (= [agent-address agent-address agent-address] role-reads)
                         "a missing role and a failed read both poll again")
                     (is (= [6000 6000] timers)))
                   (is (nil? (get-in @store [:wallet :agent :vault-registration])))
                   (done)))
          (.catch (fail! done))))))

(deftest register-agent-reports-progress-in-the-store-test
  (async done
    (let [steps (atom [])
          store (atom {:wallet {:agent {}}})
          {:keys [deps]} (registration-deps {:roles [agent-of-vault]})]
      (add-watch store ::steps
                 (fn [_ _ old new]
                   (let [step (get-in new [:wallet :agent :vault-registration])]
                     (when (not= step (get-in old [:wallet :agent :vault-registration]))
                       (swap! steps conj step)))))
      (-> (vault-registration/register-agent! (assoc deps :store store))
          (.then (fn [_]
                   (is (= [{:step :wallet :agent-address agent-address}
                           {:step :hyperliquid :agent-address agent-address}
                           nil]
                          @steps))
                   (done)))
          (.catch (fail! done))))))

(deftest register-agent-times-out-when-hyperliquid-never-registers-test
  (async done
    (let [{:keys [calls deps]} (registration-deps {:roles [{:role "missing"}]})
          store (atom {:wallet {:agent {}}})]
      (-> (vault-registration/register-agent! (assoc deps :store store))
          (.then (fn [_]
                   (is false "must not resolve")
                   (done)))
          (.catch (fn [err]
                    (is (= vault-registration/registration-timeout-message (.-message err)))
                    (is (= 1 (count (:txs @calls))))
                    (is (nil? (get-in @store [:wallet :agent :vault-registration])))
                    (done)))))))

(deftest register-agent-surfaces-a-wallet-refusal-without-polling-test
  (async done
    (let [{:keys [calls deps]} (registration-deps
                                {:roles [agent-of-vault]
                                 :send-result (js/Promise.reject (js/Error. "User rejected"))})]
      (-> (vault-registration/register-agent! deps)
          (.then (fn [_]
                   (is false "must not resolve")
                   (done)))
          (.catch (fn [err]
                    (is (= "User rejected" (.-message err)))
                    (is (= [] (:role-reads @calls)))
                    (done)))))))

(deftest register-agent-refuses-what-it-cannot-do-test
  (async done
    (let [{:keys [calls deps]} (registration-deps {:roles [agent-of-vault]})
          message-of (fn [overrides]
                       (-> (vault-registration/register-agent! (merge deps overrides))
                           (.then (fn [_] ::resolved))
                           (.catch (fn [err] (.-message err)))))]
      (-> (js/Promise.all
           #js [(message-of {:agent-address "0x0000000000000000000000000000000000000000"})
                (message-of {:agent-address "0x1234"})
                (message-of {:provider nil})
                (message-of {:owner-address nil})])
          (.then (fn [messages]
                   (is (= [vault-registration/removal-unsupported-message
                           "Unable to build the trading key registration."
                           "No wallet provider found. Connect your wallet first."
                           "Connect your wallet before enabling trading."]
                          (vec messages)))
                   (is (= [] (:txs @calls)) "nothing was sent")
                   (done)))
          (.catch (fail! done))))))

(deftest approve-agent-keeps-the-signature-path-off-hyperevm-test
  (let [signed (atom [])
        store (atom {:wallet {:address vault-address :chain-id "0xa4b1"}})
        result (vault-registration/approve-agent!
                {:approve-with-signature! (fn [& args]
                                            (swap! signed conj args)
                                            ::signed)
                 :detect! (fn [& _]
                            (throw (js/Error. "must not detect")))}
                store vault-address {:agentAddress agent-address})]
    (is (= ::signed result) "synchronous, exactly as before")
    (is (= [[store vault-address {:agentAddress agent-address}]] @signed))))

(deftest approve-agent-keeps-the-signature-path-for-an-eoa-on-hyperevm-test
  (async done
    (let [signed (atom 0)
          store (atom {:wallet {:address vault-address :chain-id "0x3e7"}})]
      (-> (vault-registration/approve-agent!
           {:approve-with-signature! (fn [& _]
                                       (swap! signed inc)
                                       (js/Promise.resolve ::signed))
            :detect! (fn [_store _address] (js/Promise.resolve false))
            :send-transaction! (fn [& _] (throw (js/Error. "must not send")))}
           store vault-address {:agentAddress agent-address})
          (.then (fn [result]
                   (is (= ::signed result))
                   (is (= 1 @signed))
                   (done)))
          (.catch (fail! done))))))

(deftest approve-agent-registers-a-contract-account-through-core-writer-test
  (async done
    (let [{:keys [calls deps]} (registration-deps {:roles [agent-of-vault]})
          store (atom {:wallet {:address vault-address :chain-id "0x3e7"}})]
      (-> (vault-registration/approve-agent!
           (merge (dissoc deps :owner-address :agent-address :agent-name)
                  {:approve-with-signature! (fn [& _]
                                              (throw (js/Error. "must not sign")))
                   :detect! (fn [_store _address] (js/Promise.resolve true))})
           store
           vault-address
           {:type "approveAgent"
            :agentAddress agent-address
            :agentName "Hyperopen ab12cd"})
          (.then (fn [response] (.json response)))
          (.then (fn [body]
                   (is (= "ok" (aget body "status")))
                   (let [[[from tx]] (:txs @calls)]
                     (is (= vault-address from))
                     (is (= (core-writer/encode-add-api-wallet
                             agent-address
                             (str "Hyperopen ab12cd valid_until " (+ now-ms ninety-days-ms)))
                            (:data tx))))
                   (done)))
          (.catch (fail! done))))))

;; --- enable order -------------------------------------------------------------

(defn- enable-deps
  "Fakes for `enable-agent-trading!` in passkey mode that record the order of
   the steps in `log`."
  [log {:keys [approval]}]
  {:options {:storage-mode :local
             :local-protection-mode :passkey
             :lock-before-approval? true}
   :create-agent-credentials! (fn [] {:private-key "0xpriv" :agent-address agent-address})
   :now-ms-fn (fn [] now-ms)
   :ensure-device-label! (fn [] "Desk ab12cd")
   :passkey-lock-supported? (constantly true)
   :create-locked-session! (fn [{:keys [session]}]
                             (swap! log conj :lock)
                             (js/Promise.resolve {:metadata {:credential-id "cred"}
                                                  :session session}))
   :persist-passkey-session-metadata! (fn [_ _] (swap! log conj :metadata) true)
   :cache-unlocked-session! (fn [_ _] (swap! log conj :cache))
   :delete-locked-session! (fn [_] (swap! log conj :delete-lockbox) (js/Promise.resolve true))
   :clear-passkey-session-metadata! (fn [_] (swap! log conj :clear-metadata))
   :clear-unlocked-session! (fn [_] (swap! log conj :clear-cache))
   :clear-agent-session-by-mode! (fn [_ _] (swap! log conj :clear-raw))
   :persist-agent-session-by-mode! (fn [& _] (swap! log conj :persist-raw) true)
   :default-signature-chain-id-for-environment (constantly "0xa4b1")
   :build-approve-agent-action (fn [agent nonce & _] {:agentAddress agent :nonce nonce})
   :approve-agent! (fn [_store _owner _action]
                     (swap! log conj :approve)
                     (approval))})

(defn- wait-for-status!
  [store status]
  (js/Promise.
   (fn [resolve _]
     (letfn [(check []
               (if (= status (get-in @store [:wallet :agent :status]))
                 (resolve true)
                 (js/setTimeout check 0)))]
       (check)))))

(deftest lock-before-approval-stores-the-key-before-asking-test
  (async done
    (let [log (atom [])
          store (atom {:wallet {:address vault-address
                                :agent {:status :approving}}})]
      (enable/enable-agent-trading!
       (assoc (enable-deps log {:approval #(js/Promise.resolve
                                            #js {:json (fn [] (js/Promise.resolve
                                                               #js {:status "ok"}))})})
              :store store))
      (-> (wait-for-status! store :ready)
          (.then (fn [_]
                   (is (= [:lock :metadata :cache :approve] @log))
                   (is (= {:status :ready
                           :agent-address agent-address
                           :storage-mode :local
                           :local-protection-mode :passkey
                           :last-approved-at now-ms
                           :nonce-cursor now-ms}
                          (select-keys (get-in @store [:wallet :agent])
                                       [:status :agent-address :storage-mode
                                        :local-protection-mode :last-approved-at
                                        :nonce-cursor])))
                   (done)))
          (.catch (fail! done))))))

(deftest lock-before-approval-removes-the-key-when-approval-fails-test
  (async done
    (let [log (atom [])
          store (atom {:wallet {:address vault-address
                                :agent {:status :approving}}})]
      (enable/enable-agent-trading!
       (assoc (enable-deps log {:approval #(js/Promise.reject (js/Error. "timed out"))})
              :store store))
      (-> (wait-for-status! store :error)
          (.then (fn [_]
                   (is (= [:lock :metadata :cache :approve
                           :clear-raw :clear-cache :clear-metadata :delete-lockbox]
                          @log))
                   (is (nil? (get-in @store [:wallet :agent :agent-address])))
                   (is (string? (get-in @store [:wallet :agent :error])))
                   (done)))
          (.catch (fail! done))))))

(deftest lock-before-approval-sends-nothing-without-a-passkey-test
  (async done
    (let [log (atom [])
          store (atom {:wallet {:address vault-address
                                :agent {:status :approving}}})]
      (enable/enable-agent-trading!
       (assoc (enable-deps log {:approval #(js/Promise.resolve nil)})
              :store store
              :passkey-lock-supported? (constantly false)))
      (-> (wait-for-status! store :error)
          (.then (fn [_]
                   (is (= [] @log) "no key stored, no approval requested")
                   (is (= "Passkey unlock is unavailable in this browser."
                          (get-in @store [:wallet :agent :error])))
                   (done)))
          (.catch (fail! done))))))
