(ns hyperopen.wallet.registered-key-lifecycle-test
  "A trading key that a contract account registered with a transaction: its
   record, what survives a disconnect, the copy that is set aside during a
   renewal, and the idle lock."
  (:require [cljs.test :refer-macros [async deftest is testing use-fixtures]]
            [hyperopen.api.trading.agent-actions :as agent-actions]
            [hyperopen.api.trading.http :as trading-http]
            [hyperopen.platform.indexed-db :as indexed-db]
            [hyperopen.wallet.agent-lockbox :as agent-lockbox]
            [hyperopen.wallet.agent-session :as agent-session]
            [hyperopen.wallet.core :as wallet]
            [hyperopen.wallet.idle-lock :as idle-lock]
            [hyperopen.wallet.key-backup :as key-backup]
            [hyperopen.wallet.registration-record :as registration-record]))

(def ^:private vault-address "0x0eef159d0f5fe7d186bd105fce15b46c422141cf")
(def ^:private eoa-address "0x2222222222222222222222222222222222222222")
(def ^:private agent-address "0x5ce4a06175e2258e0423110f504cac3912dc53d7")
(def ^:private new-agent-address "0x1111111111111111111111111111111111111111")
(def ^:private now-ms 1800000000000)
(def ^:private day-ms (* 24 60 60 1000))

(defn- fake-storage
  []
  (let [items (atom {})]
    #js {:getItem (fn [key] (get @items key))
         :setItem (fn [key value] (swap! items assoc key (str value)))
         :removeItem (fn [key] (swap! items dissoc key))}))

(def ^:private originals
  (atom nil))

(def ^:private lockbox-db
  (atom {}))

(use-fixtures :each
  {:before (fn []
             (reset! originals {:local-storage (.-localStorage js/globalThis)
                                :get-json! indexed-db/get-json!
                                :put-json! indexed-db/put-json!
                                :delete-key! indexed-db/delete-key!})
             (reset! lockbox-db {})
             (set! (.-localStorage js/globalThis) (fake-storage))
             ;; Multi-arity like the real ones: the compiler calls a known
             ;; arity directly.
             (set! indexed-db/get-json!
                   (fn
                     ([_store key] (js/Promise.resolve (get @lockbox-db key)))
                     ([_store key _opts] (js/Promise.resolve (get @lockbox-db key)))))
             (set! indexed-db/put-json!
                   (fn
                     ([_store key value]
                      (swap! lockbox-db assoc key value)
                      (js/Promise.resolve true))
                     ([_store key value _opts]
                      (swap! lockbox-db assoc key value)
                      (js/Promise.resolve true))))
             (set! indexed-db/delete-key!
                   (fn
                     ([_store key]
                      (swap! lockbox-db dissoc key)
                      (js/Promise.resolve true))
                     ([_store key _opts]
                      (swap! lockbox-db dissoc key)
                      (js/Promise.resolve true))))
             (agent-lockbox/clear-all-unlocked-sessions!))
   :after (fn []
            (agent-lockbox/clear-all-unlocked-sessions!)
            (let [{:keys [local-storage get-json! put-json! delete-key!]} @originals]
              (set! (.-localStorage js/globalThis) local-storage)
              (set! indexed-db/get-json! get-json!)
              (set! indexed-db/put-json! put-json!)
              (set! indexed-db/delete-key! delete-key!)))})

(defn- fail!
  [done]
  (fn [err]
    (is false (str "unexpected: " (or (some-> err .-message) err)))
    (done)))

(defn- metadata
  [agent]
  {:agent-address agent
   :credential-id (str "cred-" agent)
   :prf-salt "salt"
   :last-approved-at now-ms
   :nonce-cursor now-ms})

(defn- store-key!
  [owner agent]
  (agent-session/persist-passkey-session-metadata! owner (metadata agent))
  (swap! lockbox-db assoc owner {:ciphertext (str "sealed-" agent)}))

(defn- record!
  [owner agent & {:keys [registered-at-ms]}]
  (registration-record/persist-record!
   owner
   {:kind :register
    :agent-address agent
    :agent-name "Desk ab12cd valid_until 1807776000000"
    :valid-until-ms (+ now-ms (* 90 day-ms))
    :started-at-ms now-ms
    :registered-at-ms registered-at-ms}))

;; --- the record ----------------------------------------------------------------

(deftest record-round-trips-and-normalizes-test
  (is (nil? (registration-record/load-record vault-address)))
  (is (false? (registration-record/keeps-key? vault-address)))
  (is (true? (record! (.toUpperCase vault-address) (.toUpperCase agent-address))))
  (let [record (registration-record/load-record vault-address)]
    (is (= {:version 1
            :kind :register
            :agent-address agent-address
            :agent-name "Desk ab12cd valid_until 1807776000000"
            :valid-until-ms (+ now-ms (* 90 day-ms))
            :started-at-ms now-ms
            :registered-at-ms nil
            :saved-at-ms nil}
           record))
    (is (true? (registration-record/pending-registration? record)))
    (is (true? (registration-record/keeps-key? vault-address)))
    (is (true? (registration-record/pending-registration-for? vault-address agent-address)))
    (is (false? (registration-record/pending-registration-for? vault-address new-agent-address))))
  (testing "another key cannot mark this one as registered"
    (is (nil? (registration-record/mark-registered! vault-address new-agent-address now-ms)))
    (is (true? (registration-record/pending-registration-for? vault-address agent-address))))
  (registration-record/mark-registered! vault-address agent-address (+ now-ms 5))
  (is (false? (registration-record/pending-registration-for? vault-address agent-address)))
  (is (true? (registration-record/keeps-key? vault-address)) "a registered key is still kept")
  (registration-record/clear-record! vault-address)
  (is (nil? (registration-record/load-record vault-address))))

(deftest record-refuses-what-is-not-a-record-test
  (is (false? (registration-record/persist-record! vault-address {:agent-address "0x12"
                                                                   :started-at-ms now-ms})))
  (is (false? (registration-record/persist-record! vault-address {:agent-address agent-address})))
  (is (false? (registration-record/persist-record! "not an address" {:agent-address agent-address
                                                                      :started-at-ms now-ms})))
  (.setItem (.-localStorage js/globalThis)
            (registration-record/record-key vault-address)
            "{broken")
  (is (nil? (registration-record/load-record vault-address))))

(deftest days-left-test
  (let [record {:valid-until-ms (+ now-ms (* 10 day-ms) 1000)}]
    (is (= 10 (registration-record/days-left record now-ms)))
    (is (= -1 (registration-record/days-left record (+ now-ms (* 11 day-ms)))))
    (is (nil? (registration-record/days-left {} now-ms)))))

;; --- connect, disconnect, switch ---------------------------------------------

(defn- wallet-store
  [& {:keys [local-protection-mode] :or {local-protection-mode :passkey}}]
  (atom {:wallet {:connected? false
                  :address nil
                  :agent (agent-session/default-agent-state
                          :storage-mode :local
                          :local-protection-mode local-protection-mode
                          :passkey-supported? true)}}))

(deftest connect-shows-a-key-that-still-waits-for-its-registration-test
  (let [store (wallet-store)]
    (store-key! vault-address agent-address)
    (record! vault-address agent-address)
    (wallet/set-connected! store vault-address)
    (is (= {:status :approving
            :agent-address agent-address
            :vault-registration {:step :hyperliquid :agent-address agent-address}}
           (select-keys (get-in @store [:wallet :agent])
                        [:status :agent-address :vault-registration])))
    (testing "also when the key is unlocked in this page"
      (agent-lockbox/cache-unlocked-session! vault-address {:agent-address agent-address
                                                            :private-key "0xpriv"})
      (wallet/set-connected! store vault-address)
      (is (= :approving (get-in @store [:wallet :agent :status]))))
    (testing "once registered it is an ordinary locked key"
      (agent-lockbox/clear-unlocked-session! vault-address)
      (registration-record/mark-registered! vault-address agent-address now-ms)
      (wallet/set-connected! store vault-address)
      (is (= :locked (get-in @store [:wallet :agent :status])))
      (is (nil? (get-in @store [:wallet :agent :vault-registration]))))))

(deftest connect-finds-a-registered-key-whatever-the-last-account-preferred-test
  (let [store (wallet-store :local-protection-mode :plain)]
    (store-key! vault-address agent-address)
    (record! vault-address agent-address :registered-at-ms now-ms)
    (wallet/set-connected! store vault-address)
    (is (= {:status :locked
            :agent-address agent-address
            :storage-mode :local
            :local-protection-mode :passkey}
           (select-keys (get-in @store [:wallet :agent])
                        [:status :agent-address :storage-mode :local-protection-mode])))))

(deftest disconnect-and-account-switch-keep-a-registered-key-test
  (let [store (wallet-store)]
    (store-key! vault-address agent-address)
    (record! vault-address agent-address :registered-at-ms now-ms)
    (wallet/set-connected! store vault-address)
    (agent-lockbox/cache-unlocked-session! vault-address {:agent-address agent-address
                                                          :private-key "0xpriv"})
    (testing "switching to another account"
      (wallet/set-connected! store eoa-address)
      (is (some? (agent-session/load-passkey-session-metadata vault-address)))
      (is (some? (get @lockbox-db vault-address)))
      (is (nil? (agent-lockbox/load-unlocked-session vault-address))
          "but it is locked again"))
    (testing "coming back and disconnecting"
      (wallet/set-connected! store vault-address)
      (is (= :locked (get-in @store [:wallet :agent :status])))
      (wallet/set-disconnected! store)
      (is (some? (agent-session/load-passkey-session-metadata vault-address)))
      (is (some? (get @lockbox-db vault-address))))))

(deftest disconnect-still-forgets-a-key-that-was-approved-by-signature-test
  (let [store (wallet-store)]
    (store-key! eoa-address agent-address)
    (wallet/set-connected! store eoa-address)
    (is (= :locked (get-in @store [:wallet :agent :status])))
    (wallet/set-disconnected! store)
    (is (nil? (agent-session/load-passkey-session-metadata eoa-address)))))

;; --- a key Hyperliquid does not know (yet) --------------------------------------

(deftest a-key-that-waits-for-its-registration-is-not-thrown-away-test
  (async done
    (let [reads (atom 0)
          original trading-http/fetch-user-role!
          session {:agent-address agent-address}]
      (set! trading-http/fetch-user-role!
            (fn [_address]
              (swap! reads inc)
              (js/Promise.resolve {:role "missing"})))
      (record! vault-address agent-address)
      (-> (agent-actions/should-invalidate-missing-api-wallet-session! vault-address session)
          (.then (fn [invalidate?]
                   (is (false? invalidate?))
                   (is (= 0 @reads) "no need to ask Hyperliquid")
                   (registration-record/mark-registered! vault-address agent-address now-ms)
                   (agent-actions/should-invalidate-missing-api-wallet-session!
                    vault-address session)))
          (.then (fn [invalidate?]
                   (is (true? invalidate?) "once registered, a missing key is really gone")
                   (is (= 1 @reads))))
          (.catch (fn [err] (is false (str err))))
          (.then (fn [_]
                   (set! trading-http/fetch-user-role! original)
                   (done)))))))

;; --- the key that is set aside --------------------------------------------------

(deftest backup-and-restore-bring-the-old-key-back-test
  (async done
    (store-key! vault-address agent-address)
    (-> (key-backup/backup! vault-address)
        (.then (fn [backed-up?]
                 (is (true? backed-up?))
                 ;; The replacement overwrites the lockbox and its metadata.
                 (store-key! vault-address new-agent-address)
                 (key-backup/restore! vault-address)))
        (.then (fn [restored]
                 (is (= agent-address (:agent-address restored)))
                 (is (= (str "cred-" agent-address) (:credential-id restored)))
                 (is (= {:ciphertext (str "sealed-" agent-address)}
                        (get @lockbox-db vault-address)))
                 (is (= agent-address
                        (:agent-address
                         (agent-session/load-passkey-session-metadata vault-address))))
                 (is (= [vault-address] (keys @lockbox-db)) "the copy is gone")
                 (key-backup/restore! vault-address)))
        (.then (fn [restored-again]
                 (is (nil? restored-again) "nothing is restored twice")
                 (done)))
        (.catch (fail! done)))))

(deftest backup-has-nothing-to-do-without-a-key-test
  (async done
    (-> (key-backup/backup! vault-address)
        (.then (fn [backed-up?]
                 (is (false? backed-up?))
                 (is (= {} @lockbox-db))
                 (key-backup/restore! vault-address)))
        (.then (fn [restored]
                 (is (nil? restored))
                 (done)))
        (.catch (fail! done)))))

(deftest drop-forgets-the-old-key-test
  (async done
    (store-key! vault-address agent-address)
    (-> (key-backup/backup! vault-address)
        (.then (fn [_]
                 (store-key! vault-address new-agent-address)
                 (key-backup/drop! vault-address)))
        (.then (fn [_] (key-backup/restore! vault-address)))
        (.then (fn [restored]
                 (is (nil? restored))
                 (is (= {:ciphertext (str "sealed-" new-agent-address)}
                        (get @lockbox-db vault-address)))
                 (is (= [vault-address] (keys @lockbox-db)))
                 (done)))
        (.catch (fail! done)))))

;; --- idle lock ------------------------------------------------------------------

(def ^:private idle-limit-ms (* 30 60 1000))

(defn- ready-state
  [status]
  {:wallet {:connected? true
            :address vault-address
            :agent {:status status
                    :storage-mode :local
                    :local-protection-mode :passkey}}})

(deftest should-lock-only-an-idle-unlocked-registered-key-test
  (let [base {:state (ready-state :ready)
              :now-ms (+ now-ms idle-limit-ms)
              :last-activity-ms now-ms
              :limit-ms idle-limit-ms
              :keeps-key? (constantly true)}]
    (is (true? (idle-lock/should-lock? base)))
    (is (false? (idle-lock/should-lock? (update base :now-ms dec))) "not idle long enough")
    (is (false? (idle-lock/should-lock? (assoc base :state (ready-state :locked)))))
    (is (false? (idle-lock/should-lock? (assoc base :keeps-key? (constantly false))))
        "other wallets keep the behaviour they chose")
    (is (false? (idle-lock/should-lock? (assoc base :limit-ms nil))) "turned off")
    (is (false? (idle-lock/should-lock? (assoc-in base [:state :wallet :connected?] false))))))

(deftest check-locks-the-key-and-takes-it-out-of-memory-test
  (let [store (atom (ready-state :ready))]
    (record! vault-address agent-address :registered-at-ms now-ms)
    (agent-lockbox/cache-unlocked-session! vault-address {:agent-address agent-address
                                                          :private-key "0xpriv"})
    (idle-lock/note-activity! now-ms)
    (is (false? (idle-lock/check! store {:now-ms-fn (constantly (+ now-ms 1000))
                                         :limit-ms idle-limit-ms})))
    (is (= :ready (get-in @store [:wallet :agent :status])))
    (is (true? (idle-lock/check! store {:now-ms-fn (constantly (+ now-ms idle-limit-ms))
                                        :limit-ms idle-limit-ms})))
    (is (= :locked (get-in @store [:wallet :agent :status])))
    (is (nil? (agent-lockbox/load-unlocked-session vault-address)))
    (is (some? (registration-record/load-record vault-address)) "locked, not removed")))
