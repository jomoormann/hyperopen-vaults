(ns hyperopen.wallet.vault-registration-test
  (:require [cljs.test :refer-macros [async deftest is testing use-fixtures]]
            [hyperopen.hyperevm.domain.core-writer :as core-writer]
            [hyperopen.wallet.agent-lockbox :as agent-lockbox]
            [hyperopen.wallet.agent-runtime.enable :as enable]
            [hyperopen.wallet.agent-session :as agent-session]
            [hyperopen.wallet.contract-account :as contract-account]
            [hyperopen.wallet.registration-record :as registration-record]
            [hyperopen.wallet.vault-registration :as vault-registration]))

(def ^:private vault-address "0x0eef159d0f5fe7d186bd105fce15b46c422141cf")
(def ^:private agent-address "0x5ce4a06175e2258e0423110f504cac3912dc53d7")
(def ^:private other-agent-address "0x1111111111111111111111111111111111111111")
(def ^:private zero-address "0x0000000000000000000000000000000000000000")
(def ^:private now-ms 1800000000000)
(def ^:private ninety-days-ms (* 90 24 60 60 1000))

(defn- fake-storage
  []
  (let [items (atom {})]
    #js {:getItem (fn [key] (get @items key))
         :setItem (fn [key value] (swap! items assoc key (str value)))
         :removeItem (fn [key] (swap! items dissoc key))}))

(def ^:private original-local-storage
  (atom nil))

(use-fixtures :each
  {:before (fn []
             (reset! original-local-storage (.-localStorage js/globalThis))
             (set! (.-localStorage js/globalThis) (fake-storage))
             (contract-account/reset-detections!)
             (vault-registration/reset-watchers!)
             (agent-lockbox/clear-all-unlocked-sessions!))
   :after (fn []
            (vault-registration/reset-watchers!)
            (agent-lockbox/clear-all-unlocked-sessions!)
            (contract-account/reset-detections!)
            (set! (.-localStorage js/globalThis) @original-local-storage))})

(defn- fail!
  [done]
  (fn [err]
    (is false (str "unexpected: " (or (some-> err .-message) err)))
    (done)))

(defn- tick!
  "Let pending promise callbacks run."
  []
  (js/Promise. (fn [resolve _] (js/setTimeout resolve 0))))

(defn- registration-deps
  "Fakes for `register-agent!`. `roles` is the sequence of `userRole`
   answers, one per poll; the last one repeats. With `:hold-timers? true`
   a scheduled poll waits until the test runs it."
  [{:keys [roles send-result extra-agents hold-timers?]
    :or {send-result (js/Promise.resolve "0xhash")
         extra-agents []}}]
  (let [calls (atom {:chains [] :txs [] :role-reads [] :timers [] :held []})
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
            :ensure-wallet-chain! (fn [_provider chain-config]
                                    (swap! calls update :chains conj chain-config)
                                    (js/Promise.resolve (:chain-id chain-config)))
            :send-transaction! (fn [_provider from tx]
                                 (swap! calls update :txs conj [from tx])
                                 send-result)
            :fetch-extra-agents! (fn [_owner]
                                   (if (= ::read-error extra-agents)
                                     (js/Promise.reject (js/Error. "info down"))
                                     (js/Promise.resolve extra-agents)))
            :fetch-user-role! (fn [address]
                                (swap! calls update :role-reads conj address)
                                (let [role (first @remaining)]
                                  (when (next @remaining)
                                    (swap! remaining rest))
                                  (if (= ::read-error role)
                                    (js/Promise.reject (js/Error. "info down"))
                                    (js/Promise.resolve role))))
            :set-timeout-fn (fn [f ms]
                              (swap! calls update :timers conj ms)
                              (if hold-timers?
                                (swap! calls update :held conj f)
                                ;; Each scheduled poll runs at once and
                                ;; advances the clock.
                                (do
                                  (swap! clock + ms)
                                  (js/Promise.resolve (f)))))}}))

(def ^:private agent-of-vault
  {:role "agent" :data {:user vault-address}})

(defn- expected-name
  [name]
  (str name " valid_until " (+ now-ms ninety-days-ms)))

(defn- store-metadata!
  [agent]
  (agent-session/persist-passkey-session-metadata!
   vault-address
   {:agent-address agent
    :credential-id "cred"
    :prf-salt "salt"
    :last-approved-at now-ms
    :nonce-cursor now-ms}))

(deftest name-with-valid-until-test
  (is (= (expected-name "Desk ab12cd")
         (vault-registration/name-with-valid-until "Desk ab12cd" now-ms 90)))
  (testing "an existing expiry is kept"
    (is (= "Desk valid_until 1700000000000"
           (vault-registration/name-with-valid-until
            " Desk valid_until 1700000000000 " now-ms 90))))
  (testing "the unnamed slot has no name to carry an expiry"
    (is (nil? (vault-registration/name-with-valid-until nil now-ms 90)))
    (is (nil? (vault-registration/name-with-valid-until "  " now-ms 90)))))

(deftest poll-delay-slows-down-the-longer-the-wait-test
  (is (= 6000 (vault-registration/poll-delay-ms 0)))
  (is (= 6000 (vault-registration/poll-delay-ms (dec (* 2 60 1000)))))
  (is (= 15000 (vault-registration/poll-delay-ms (* 2 60 1000))))
  (is (= 60000 (vault-registration/poll-delay-ms (* 15 60 1000))))
  (is (= 60000 (vault-registration/poll-delay-ms (* 3 24 60 60 1000)))
      "days later it still looks once a minute"))

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
                             (expected-name "Desk ab12cd"))
                            (:data tx)))
                     (is (= [agent-address agent-address agent-address] role-reads)
                         "a missing role and a failed read both poll again")
                     (is (= [6000 6000] timers)))
                   (is (nil? (get-in @store [:wallet :agent :vault-registration])))
                   (is (nil? (registration-record/load-record vault-address))
                       "an untracked registration leaves no record")
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

(deftest tracked-registration-is-recorded-before-the-wallet-is-asked-test
  (async done
    (let [record-at-send (atom ::unset)
          {:keys [deps]} (registration-deps {:roles [agent-of-vault]})
          send! (:send-transaction! deps)
          store (atom {:wallet {:address vault-address
                                :agent {:status :approving}}})]
      (store-metadata! agent-address)
      (-> (vault-registration/register-agent!
           (assoc deps
                  :store store
                  :track? true
                  :send-transaction! (fn [& args]
                                       (reset! record-at-send
                                               (registration-record/load-record vault-address))
                                       (apply send! args))))
          (.then (fn [_]
                   (is (= {:kind :register
                           :agent-address agent-address
                           :agent-name (expected-name "Desk ab12cd")
                           :valid-until-ms (+ now-ms ninety-days-ms)
                           :started-at-ms now-ms
                           :registered-at-ms nil}
                          (select-keys @record-at-send
                                       [:kind :agent-address :agent-name :valid-until-ms
                                        :started-at-ms :registered-at-ms]))
                       "written down before the transaction leaves the page")
                   (let [record (registration-record/load-record vault-address)]
                     (is (number? (:registered-at-ms record)))
                     (is (false? (registration-record/pending? record))))
                   (done)))
          (.catch (fail! done))))))

(deftest register-agent-surfaces-a-wallet-refusal-without-polling-test
  (async done
    (let [{:keys [calls deps]} (registration-deps
                                {:roles [agent-of-vault]
                                 :send-result (js/Promise.reject (js/Error. "User rejected"))})]
      (-> (vault-registration/register-agent! (assoc deps :track? true))
          (.then (fn [_]
                   (is false "must not resolve")
                   (done)))
          (.catch (fn [err]
                    (is (= "User rejected" (.-message err)))
                    (is (= [] (:role-reads @calls)))
                    (is (nil? (registration-record/load-record vault-address))
                        "nothing was sent, so nothing is waited for")
                    (done)))))))

(deftest register-agent-keeps-waiting-until-cancelled-test
  (async done
    (let [{:keys [calls clock deps]} (registration-deps {:roles [{:role "missing"}]
                                                         :hold-timers? true})
          store (atom {:wallet {:address vault-address
                                :agent {:status :approving
                                        :passkey-supported? true}}})
          outcome (atom nil)]
      (store-metadata! agent-address)
      (-> (vault-registration/register-agent! (assoc deps :store store :track? true))
          (.then (fn [_] (reset! outcome :resolved)))
          (.catch (fn [err] (reset! outcome err))))
      (-> (tick!)
          (.then (fn [_]
                   ;; Three days later the page is still waiting.
                   (swap! clock + (* 3 24 60 60 1000))
                   ((last (:held @calls)))
                   (tick!)))
          (.then (fn [_]
                   (is (nil? @outcome) "no time limit")
                   (is (= 2 (count (:role-reads @calls))))
                   (is (true? (vault-registration/watching? vault-address)))
                   (is (true? (registration-record/pending-registration-for?
                               vault-address agent-address)))
                   (vault-registration/cancel-registration! store)))
          (.then (fn [_] (tick!)))
          (.then (fn [_]
                   (is (true? (vault-registration/cancelled? @outcome)))
                   (is (false? (vault-registration/watching? vault-address)))
                   (is (nil? (registration-record/load-record vault-address)))
                   (is (nil? (agent-session/load-passkey-session-metadata vault-address))
                       "the stored key is gone")
                   (is (= {:status :not-ready
                           :agent-address nil
                           :error vault-registration/registration-cancelled-message
                           :passkey-supported? true}
                          (select-keys (get-in @store [:wallet :agent])
                                       [:status :agent-address :error :passkey-supported?])))
                   (done)))
          (.catch (fail! done))))))

(deftest register-agent-checks-the-named-key-limit-before-anyone-signs-test
  (async done
    (let [run (fn [extra-agents overrides]
                (let [{:keys [calls deps]} (registration-deps {:roles [agent-of-vault]
                                                               :extra-agents extra-agents})]
                  (-> (vault-registration/register-agent! (merge deps overrides))
                      (.then (fn [_] [::ok (count (:txs @calls))]))
                      (.catch (fn [err] [(.-message err) (count (:txs @calls))])))))
          three-others [{:name "Bot A"} {:name "Bot B"} {:name "Bot C"}]]
      (-> (js/Promise.all
           #js [(run three-others {})
                (run [{:name "Bot A"} {:name "Bot B"} {:name "Desk ab12cd"}] {})
                (run [{:name "Bot A"} {:name "Bot B"}] {})
                (run ::read-error {})
                (run three-others {:agent-name nil})])
          (.then (fn [results]
                   (is (= [[vault-registration/named-key-limit-message 0]
                           [::ok 1]
                           [::ok 1]
                           [vault-registration/named-keys-unavailable-message 0]
                           [::ok 1]]
                          (vec results))
                       "full: refused; same name: replaces; free slot; unreadable: refused; unnamed slot: not counted")
                   (done)))
          (.catch (fail! done))))))

(deftest register-agent-refuses-what-it-cannot-do-test
  (async done
    (let [{:keys [calls deps]} (registration-deps {:roles [agent-of-vault]})
          message-of (fn [overrides]
                       (-> (vault-registration/register-agent! (merge deps overrides))
                           (.then (fn [_] ::resolved))
                           (.catch (fn [err] (.-message err)))))]
      (-> (js/Promise.all
           #js [(message-of {:agent-address zero-address})
                (message-of {:agent-address "0x1234"})
                (message-of {:provider nil})
                (message-of {:owner-address nil})])
          (.then (fn [messages]
                   (is (= ["Unable to build the trading key registration."
                           "Unable to build the trading key registration."
                           "No wallet provider found. Connect your wallet first."
                           "Connect your wallet before enabling trading."]
                          (vec messages)))
                   (is (= [] (:txs @calls)) "nothing was sent")
                   (done)))
          (.catch (fail! done))))))

;; --- resume --------------------------------------------------------------------

(defn- pending-record!
  [agent]
  (registration-record/persist-record!
   vault-address
   {:kind :register
    :agent-address agent
    :agent-name (expected-name "Desk ab12cd")
    :valid-until-ms (+ now-ms ninety-days-ms)
    :started-at-ms now-ms}))

(defn- waiting-store
  []
  (atom {:wallet {:address vault-address
                  :connected? true
                  :agent {:status :approving
                          :agent-address agent-address
                          :vault-registration {:step :hyperliquid
                                               :agent-address agent-address}}}}))

(deftest resume-picks-a-recorded-registration-up-and-leaves-the-key-locked-test
  (async done
    (let [{:keys [calls deps]} (registration-deps {:roles [{:role "missing"} agent-of-vault]})
          store (waiting-store)]
      (store-metadata! agent-address)
      (pending-record! agent-address)
      (is (true? (vault-registration/resume! store deps)))
      (is (true? (vault-registration/resume! store deps)) "asking twice starts one wait")
      (-> (tick!)
          (.then (fn [_] (tick!)))
          (.then (fn [_]
                   (is (= [agent-address agent-address] (:role-reads @calls)))
                   (is (false? (registration-record/pending?
                                (registration-record/load-record vault-address))))
                   (is (= {:status :locked
                           :agent-address agent-address
                           :storage-mode :local
                           :local-protection-mode :passkey}
                          (select-keys (get-in @store [:wallet :agent])
                                       [:status :agent-address :storage-mode
                                        :local-protection-mode]))
                       "after a reload the key is still behind the passkey")
                   (is (not (contains? (get-in @store [:wallet :agent]) :vault-registration)))
                   (done)))
          (.catch (fail! done))))))

(deftest resume-makes-the-key-ready-when-it-is-unlocked-in-this-page-test
  (async done
    (let [{:keys [deps]} (registration-deps {:roles [agent-of-vault]})
          store (waiting-store)]
      (store-metadata! agent-address)
      (pending-record! agent-address)
      (agent-lockbox/cache-unlocked-session! vault-address
                                             {:agent-address agent-address
                                              :private-key "0xpriv"
                                              :nonce-cursor (+ now-ms 5)})
      (vault-registration/resume! store deps)
      (-> (tick!)
          (.then (fn [_] (tick!)))
          (.then (fn [_]
                   (is (= :ready (get-in @store [:wallet :agent :status])))
                   (is (= (+ now-ms 5) (get-in @store [:wallet :agent :nonce-cursor])))
                   (done)))
          (.catch (fail! done))))))

(deftest resume-drops-a-record-whose-key-is-gone-test
  (let [{:keys [calls deps]} (registration-deps {:roles [agent-of-vault]})
        store (waiting-store)]
    (pending-record! agent-address)
    (store-metadata! other-agent-address)
    (is (false? (vault-registration/resume! store deps)))
    (is (nil? (registration-record/load-record vault-address)))
    (is (= [] (:role-reads @calls)))
    (testing "nothing recorded, nothing to resume"
      (is (false? (vault-registration/resume! store deps))))))

(deftest install-resume-starts-waiting-when-the-wallet-state-shows-a-pending-key-test
  (async done
    (let [store (atom {:wallet {:address vault-address
                                :agent {:status :not-ready}}})]
      (store-metadata! agent-address)
      (pending-record! agent-address)
      (is (true? (vault-registration/install-resume! store)))
      (vault-registration/reset-watchers!)
      (is (false? (vault-registration/watching? vault-address)))
      (swap! store assoc-in [:wallet :agent]
             {:status :approving
              :agent-address agent-address
              :vault-registration {:step :hyperliquid
                                   :agent-address agent-address}})
      (-> (tick!)
          (.then (fn [_]
                   (is (true? (vault-registration/watching? vault-address)))
                   (remove-watch store ::vault-registration/resume)
                   (done)))
          (.catch (fail! done))))))

;; --- remove --------------------------------------------------------------------

(deftest remove-agent-sends-the-zero-address-and-waits-until-the-name-is-gone-test
  (async done
    (let [lists (atom [[{:name "Bot A"} {:name "Desk ab12cd"}]
                       [{:name "Bot A"}]])
          {:keys [calls deps]} (registration-deps {:roles []})
          store (atom {:wallet {:address vault-address
                                :agent {:status :ready
                                        :agent-address agent-address
                                        :passkey-supported? true}}})]
      (store-metadata! agent-address)
      (pending-record! agent-address)
      (registration-record/mark-registered! vault-address agent-address now-ms)
      (-> (vault-registration/remove-agent!
           (assoc deps
                  :store store
                  :agent-name (expected-name "Desk ab12cd")
                  :fetch-extra-agents! (fn [_owner]
                                         (let [agents (first @lists)]
                                           (when (next @lists)
                                             (swap! lists rest))
                                           (js/Promise.resolve agents)))))
          (.then (fn [response] (.json response)))
          (.then (fn [body]
                   (is (= "ok" (aget body "status")))
                   (let [[[from tx]] (:txs @calls)]
                     (is (= vault-address from))
                     (is (= (core-writer/encode-add-api-wallet zero-address "Desk ab12cd")
                            (:data tx))
                         "the bare name, without the expiry"))
                   (is (= [6000] (:timers @calls)))
                   (testing "it was this device's key, so it is dropped here too"
                     (is (nil? (registration-record/load-record vault-address)))
                     (is (nil? (agent-session/load-passkey-session-metadata vault-address)))
                     (is (= :not-ready (get-in @store [:wallet :agent :status]))))
                   (done)))
          (.catch (fail! done))))))

(deftest remove-agent-leaves-this-device-alone-when-another-key-is-removed-test
  (async done
    (let [{:keys [deps]} (registration-deps {:roles []})
          store (atom {:wallet {:address vault-address
                                :agent {:status :ready :agent-address agent-address}}})]
      (store-metadata! agent-address)
      (pending-record! agent-address)
      (-> (vault-registration/remove-agent! (assoc deps :store store :agent-name "Bot A"))
          (.then (fn [_]
                   (is (some? (registration-record/load-record vault-address)))
                   (is (some? (agent-session/load-passkey-session-metadata vault-address)))
                   (is (= :ready (get-in @store [:wallet :agent :status])))
                   (done)))
          (.catch (fail! done))))))

(deftest remove-agent-needs-a-name-test
  (async done
    (let [{:keys [calls deps]} (registration-deps {:roles []})]
      (-> (vault-registration/remove-agent! (assoc deps :agent-name nil))
          (.then (fn [_]
                   (is false "must not resolve")
                   (done)))
          (.catch (fn [err]
                    (is (= vault-registration/removal-unsupported-message (.-message err)))
                    (is (= [] (:txs @calls)))
                    (done)))))))

;; --- approve-agent! -------------------------------------------------------------

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

(defn- contract-approval-deps
  [deps]
  (merge (dissoc deps :owner-address :agent-address :agent-name)
         {:approve-with-signature! (fn [& _]
                                     (throw (js/Error. "must not sign")))
          :detect! (fn [_store _address] (js/Promise.resolve true))}))

(deftest approve-agent-registers-a-contract-account-through-core-writer-test
  (async done
    (let [{:keys [calls deps]} (registration-deps {:roles [agent-of-vault]})
          store (atom {:wallet {:address vault-address :chain-id "0x3e7"}})]
      (-> (vault-registration/approve-agent!
           (contract-approval-deps deps)
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
                             (expected-name "Hyperopen ab12cd"))
                            (:data tx))))
                   (is (nil? (registration-record/load-record vault-address))
                       "a key made for something else is not this device's key")
                   (done)))
          (.catch (fail! done))))))

(deftest approve-agent-removes-a-contract-account-key-through-core-writer-test
  (async done
    (let [{:keys [calls deps]} (registration-deps {:roles []})
          store (atom {:wallet {:address vault-address :chain-id "0x3e7"}})]
      (-> (vault-registration/approve-agent!
           (contract-approval-deps deps)
           store
           vault-address
           {:type "approveAgent"
            :agentAddress zero-address
            :agentName "Bot A"})
          (.then (fn [_]
                   (let [[[_from tx]] (:txs @calls)]
                     (is (= (core-writer/encode-add-api-wallet zero-address "Bot A")
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

(defn- backup-deps
  "Fakes for the key that is set aside. `previous` is the metadata of the
   key the account used before, or nil."
  [log previous]
  {:backup-stored-key! (fn [_] (swap! log conj :backup) (js/Promise.resolve (some? previous)))
   :restore-stored-key! (fn [_] (swap! log conj :restore) (js/Promise.resolve previous))
   :drop-key-backup! (fn [_] (swap! log conj :drop-backup))})

(defn- wait-for-status!
  [store status]
  (js/Promise.
   (fn [resolve _]
     (letfn [(check []
               (if (= status (get-in @store [:wallet :agent :status]))
                 (resolve true)
                 (js/setTimeout check 0)))]
       (check)))))

(def ^:private ok-approval
  #(js/Promise.resolve
    #js {:json (fn [] (js/Promise.resolve #js {:status "ok"}))}))

(deftest lock-before-approval-stores-the-key-before-asking-test
  (async done
    (let [log (atom [])
          store (atom {:wallet {:address vault-address
                                :agent {:status :approving}}})]
      (enable/enable-agent-trading!
       (merge (enable-deps log {:approval ok-approval})
              (backup-deps log nil)
              {:store store}))
      (-> (wait-for-status! store :ready)
          (.then (fn [_]
                   (is (= [:backup :lock :metadata :cache :approve :drop-backup] @log))
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
       (merge (enable-deps log {:approval #(js/Promise.reject (js/Error. "User rejected"))})
              (backup-deps log nil)
              {:store store}))
      (-> (wait-for-status! store :error)
          (.then (fn [_]
                   (is (= [:backup :lock :metadata :cache :approve
                           :clear-raw :clear-cache :clear-metadata :delete-lockbox
                           :restore]
                          @log))
                   (is (nil? (get-in @store [:wallet :agent :agent-address])))
                   (is (string? (get-in @store [:wallet :agent :error])))
                   (done)))
          (.catch (fail! done))))))

(deftest lock-before-approval-puts-the-old-key-back-when-a-renewal-fails-test
  (async done
    (let [log (atom [])
          previous {:agent-address other-agent-address
                    :last-approved-at (- now-ms 1000)
                    :nonce-cursor (- now-ms 500)}
          store (atom {:wallet {:address vault-address
                                :agent {:status :approving}}})]
      (enable/enable-agent-trading!
       (merge (enable-deps log {:approval #(js/Promise.reject (js/Error. "User rejected"))})
              (backup-deps log previous)
              {:store store}))
      (-> (wait-for-status! store :locked)
          (.then (fn [_]
                   (is (= :restore (last @log)))
                   (is (= {:status :locked
                           :agent-address other-agent-address
                           :storage-mode :local
                           :local-protection-mode :passkey
                           :nonce-cursor (- now-ms 500)
                           :error "User rejected"}
                          (select-keys (get-in @store [:wallet :agent])
                                       [:status :agent-address :storage-mode
                                        :local-protection-mode :nonce-cursor :error]))
                       "the account still has the key it traded with")
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
