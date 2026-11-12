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

   Step 3 has no time limit. Other signers may take hours or days, so the
   key the account enables trading with is written down in
   `hyperopen.wallet.registration-record` before the transaction is sent,
   and `resume!` picks the wait up again after a reload.

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
            [hyperopen.wallet.agent-lockbox :as agent-lockbox]
            [hyperopen.wallet.agent-session :as agent-session]
            [hyperopen.wallet.contract-account :as contract-account]
            [hyperopen.wallet.key-backup :as key-backup]
            [hyperopen.wallet.provider-registry :as provider-registry]
            [hyperopen.wallet.registration-record :as registration-record]))

(def default-valid-days
  "Lifetime of a key registered for a contract account. Hyperliquid reads it
   from the `valid_until` suffix of the name."
  90)

(def max-named-keys
  "Hyperliquid keeps at most this many named API wallets per account."
  3)

(def removal-unsupported-message
  "Only a named trading key can be removed for a contract account.")

(def named-key-limit-message
  "This account already has 3 named trading keys. Remove one on the API wallets page first.")

(def named-keys-unavailable-message
  "Could not read the account's trading keys from Hyperliquid. Try again.")

(def registration-cancelled-message
  "Registration cancelled on this device. Reject the pending transaction in your wallet too.")

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

(defn poll-delay-ms
  "How long to wait before the next look at Hyperliquid, `elapsed-ms` into
   the wait. `userRole` weighs 60 of the 1200 a minute an IP may spend on
   info requests, and the same budget feeds the trading screen, so the poll
   is quick only while a single signer is likely to be clicking through."
  [elapsed-ms]
  (cond
    (< elapsed-ms (* 2 60 1000)) 6000
    (< elapsed-ms (* 15 60 1000)) 15000
    :else 60000))

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

;; ---------- Watching Hyperliquid ---------------------------------------------

(defonce ^:private watchers
  ;; owner address -> {:id :promise :nudge! :cancel!}
  (atom {}))

(defonce ^:private visibility-listener-installed?
  (atom false))

(defn- cancelled-error
  []
  (let [err (js/Error. registration-cancelled-message)]
    (unchecked-set err "registrationCancelled" true)
    err))

(defn cancelled?
  [err]
  (and (some? err)
       (true? (unchecked-get err "registrationCancelled"))))

(defn- install-visibility-listener!
  "Look again as soon as the user comes back to the tab, which is usually
   right after they signed somewhere else."
  []
  (when (and (not @visibility-listener-installed?)
             (exists? js/document)
             (fn? (.-addEventListener js/document)))
    (reset! visibility-listener-installed? true)
    (.addEventListener
     js/document
     "visibilitychange"
     (fn [_]
       (when (= "visible" (.-visibilityState js/document))
         (doseq [{:keys [nudge!]} (vals @watchers)]
           (nudge!)))))))

(defn- start-watch!
  [{:keys [confirmed? set-timeout-fn now-ms-fn poll-ms]}]
  (let [started-at (now-ms-fn)
        state (atom {:done? false :tick 0 :in-flight? false})
        settle (atom nil)
        promise (js/Promise.
                 (fn [resolve reject*]
                   (reset! settle {:resolve resolve :reject reject*})))]
    (letfn [(finish! [kind value]
              (when-not (:done? @state)
                (swap! state assoc :done? true)
                ((get @settle kind) value)))
            (schedule! []
              (let [tick (:tick (swap! state update :tick inc))
                    delay-ms (or poll-ms
                                 (poll-delay-ms (- (now-ms-fn) started-at)))]
                (set-timeout-fn
                 (fn []
                   (when (and (not (:done? @state))
                              (= tick (:tick @state)))
                     (poll!)))
                 delay-ms)))
            (poll! []
              (when-not (or (:done? @state) (:in-flight? @state))
                (swap! state assoc :in-flight? true)
                (-> (js/Promise.resolve nil)
                    (.then (fn [_] (confirmed?)))
                    (.then (fn [yes?]
                             (swap! state assoc :in-flight? false)
                             (if yes?
                               (finish! :resolve true)
                               (schedule!))))
                    ;; A failed read says nothing about the registration.
                    (.catch (fn [_]
                              (swap! state assoc :in-flight? false)
                              (schedule!))))))]
      ;; Nobody may be listening when a watch is cancelled.
      (.catch promise (fn [_] nil))
      (poll!)
      {:promise promise
       :nudge! (fn []
                 (swap! state update :tick inc)
                 (poll!))
       :cancel! (fn []
                  (finish! :reject (cancelled-error)))})))

(defn- watch!
  "One wait per owner. Asking again for the same `id` returns the wait that
   is already running; a different `id` replaces it."
  [owner id opts]
  (install-visibility-listener!)
  (let [current (get @watchers owner)]
    (if (and current (= id (:id current)))
      (:promise current)
      (let [_ (when current ((:cancel! current)))
            watcher (assoc (start-watch! opts) :id id)
            forget! (fn [_]
                      (swap! watchers
                             (fn [m]
                               (if (identical? (:promise (get m owner))
                                               (:promise watcher))
                                 (dissoc m owner)
                                 m))))]
        (swap! watchers assoc owner watcher)
        (-> (:promise watcher)
            (.then forget!)
            (.catch forget!))
        (:promise watcher)))))

(defn watching?
  [owner-address]
  (contains? @watchers (abi/normalize-address owner-address)))

(defn cancel-watch!
  [owner-address]
  (when-let [watcher (get @watchers (abi/normalize-address owner-address))]
    ((:cancel! watcher))
    true))

(defn reset-watchers!
  []
  (doseq [{:keys [cancel!]} (vals @watchers)]
    (cancel!))
  (reset! watchers {})
  true)

;; ---------- Reads -------------------------------------------------------------

(defn fetch-extra-agents!
  "The named API wallets of `owner-address` as `[{:name :address
   :valid-until-ms}]`."
  [owner-address]
  (-> (trading-http/post-info! {:type "extraAgents"
                                :user owner-address})
      (.then trading-http/parse-json!)
      (.then (fn [rows]
               (if-not (sequential? rows)
                 (js/Promise.reject (js/Error. named-keys-unavailable-message))
                 (mapv (fn [row]
                         {:name (some-> (:name row) str str/trim)
                          :address (abi/normalize-address (:address row))
                          :valid-until-ms (:validUntil row)})
                       rows))))))

(defn- base-name
  [agent-name]
  (:name (agent-session/parse-agent-name-valid-until agent-name)))

(defn- named-key-present?
  [agents agent-name]
  (let [name* (base-name agent-name)]
    (boolean (some #(= name* (:name %)) agents))))

(defn- ensure-free-named-slot!
  "Hyperliquid drops a CoreWriter action it cannot apply without any error,
   so a registration that cannot fit is refused here, before anyone signs."
  [fetch-extra-agents!* owner-address agent-name]
  (if (nil? (base-name agent-name))
    (js/Promise.resolve true)
    (-> (js/Promise.resolve nil)
        (.then (fn [_] (fetch-extra-agents!* owner-address)))
        (.catch (fn [_] (reject named-keys-unavailable-message)))
        (.then (fn [agents]
                 (if (and (not (named-key-present? agents agent-name))
                          (>= (count agents) max-named-keys))
                   (reject named-key-limit-message)
                   true))))))

;; ---------- After Hyperliquid confirmed --------------------------------------

(defn- apply-registered-state!
  "The registration of `agent-address` is in. When the page still shows it
   as waiting, move it on: ready when the key is unlocked in this page,
   locked when it has to be unlocked with the passkey first."
  [store owner-address agent-address now-ms]
  (when store
    (let [state @store
          agent-state (get-in state [:wallet :agent])
          owner* (abi/normalize-address (get-in state [:wallet :address]))]
      (when (and (= owner* owner-address)
                 (= :approving (:status agent-state))
                 (= agent-address
                    (abi/normalize-address
                     (get-in agent-state [:vault-registration :agent-address]))))
        (let [metadata (agent-session/load-passkey-session-metadata owner-address)
              unlocked (agent-lockbox/load-unlocked-session owner-address)
              unlocked? (= agent-address
                           (abi/normalize-address (:agent-address unlocked)))]
          (swap! store update-in [:wallet :agent]
                 (fn [agent-state*]
                   (-> agent-state*
                       (merge {:status (if unlocked? :ready :locked)
                               :agent-address agent-address
                               :storage-mode :local
                               :local-protection-mode :passkey
                               :last-approved-at (:last-approved-at metadata)
                               :nonce-cursor (or (:nonce-cursor unlocked)
                                                 (:nonce-cursor metadata))
                               :key-expiry (registration-record/key-expiry-for
                                            owner-address
                                            agent-address
                                            now-ms)
                               :error nil
                               :recovery-modal-open? false})
                       (dissoc :vault-registration)))))))))

(defn- on-registered!
  [store owner-address agent-address now-ms-fn]
  (registration-record/mark-registered! owner-address agent-address (now-ms-fn))
  (key-backup/drop! owner-address)
  (apply-registered-state! store owner-address agent-address (now-ms-fn)))

(defn- watch-registration!
  [{:keys [store owner-address agent-address fetch-user-role!
           set-timeout-fn now-ms-fn poll-ms track?]}]
  (-> (watch! owner-address
              [:register agent-address]
              {:confirmed? (fn []
                             (-> (fetch-user-role! agent-address)
                                 (.then (fn [role]
                                          (trading-http/user-role-agent-for-owner?
                                           owner-address
                                           role)))))
               :set-timeout-fn set-timeout-fn
               :now-ms-fn now-ms-fn
               :poll-ms poll-ms})
      (.then (fn [_]
               (when track?
                 (on-registered! store owner-address agent-address now-ms-fn))
               true))))

;; ---------- Register ----------------------------------------------------------

(defn register-agent!
  "Send the CoreWriter registration of `agent-address` for `owner-address`
   through `provider` and resolve with a response-like `{status: \"ok\"}`
   once Hyperliquid reports the key as the account's agent.

   With `:track? true` the registration is the account's own trading key on
   this device: it is recorded so the wait survives a reload."
  [{:keys [store provider owner-address agent-address agent-name track?
           ensure-wallet-chain! send-transaction! fetch-user-role!
           fetch-extra-agents! set-timeout-fn now-ms-fn poll-ms valid-days]
    :or {ensure-wallet-chain! wallet-rpc/ensure-wallet-chain!
         send-transaction! wallet-rpc/send-transaction!
         fetch-user-role! trading-http/fetch-user-role!
         fetch-extra-agents! fetch-extra-agents!
         set-timeout-fn platform/set-timeout!
         now-ms-fn platform/now-ms
         valid-days default-valid-days}}]
  (let [owner* (abi/normalize-address owner-address)
        agent* (abi/normalize-address agent-address)
        started-at-ms (now-ms-fn)
        full-name (name-with-valid-until agent-name started-at-ms valid-days)
        tx (when agent*
             (core-writer/add-api-wallet-tx agent* full-name))]
    (cond
      (nil? provider)
      (reject "No wallet provider found. Connect your wallet first.")

      (nil? owner*)
      (reject "Connect your wallet before enabling trading.")

      (nil? tx)
      (reject "Unable to build the trading key registration.")

      ;; The zero address removes a key: that is `remove-agent!`.
      (= agent* agent-session/zero-address)
      (reject "Unable to build the trading key registration.")

      :else
      (-> (js/Promise.resolve nil)
          (.then (fn [_]
                   (set-step! store :wallet agent*)
                   (ensure-wallet-chain! provider (hyperevm-chain-config))))
          (.then (fn [_]
                   (ensure-free-named-slot! fetch-extra-agents! owner* full-name)))
          (.then (fn [_]
                   (when track?
                     (registration-record/persist-record!
                      owner*
                      {:kind :register
                       :agent-address agent*
                       :agent-name full-name
                       :valid-until-ms (:valid-until-ms
                                        (agent-session/parse-agent-name-valid-until
                                         full-name))
                       :started-at-ms started-at-ms
                       :saved-at-ms started-at-ms}))
                   (-> (send-transaction! provider owner* tx)
                       (.catch (fn [err]
                                 ;; Nothing was sent, so there is nothing to
                                 ;; wait for.
                                 (when track?
                                   (registration-record/clear-record! owner*))
                                 (js/Promise.reject err))))))
          (.then (fn [_]
                   (set-step! store :hyperliquid agent*)
                   (watch-registration!
                    {:store store
                     :owner-address owner*
                     :agent-address agent*
                     :fetch-user-role! fetch-user-role!
                     :set-timeout-fn set-timeout-fn
                     :now-ms-fn now-ms-fn
                     :poll-ms poll-ms
                     :track? track?})))
          (.then (fn [_]
                   (clear-step! store)
                   (ok-response)))
          (.catch (fn [err]
                    (when-not (cancelled? err)
                      (clear-step! store))
                    (js/Promise.reject err)))))))

;; ---------- Remove ------------------------------------------------------------

(defn- forget-own-key!
  "The removed key was the one this device trades with: drop it here too."
  [store owner-address]
  (registration-record/clear-record! owner-address)
  (agent-session/clear-passkey-session-metadata! owner-address)
  (agent-lockbox/clear-unlocked-session! owner-address)
  (key-backup/drop! owner-address)
  (when store
    (let [agent-state (get-in @store [:wallet :agent])]
      (when (= owner-address
               (abi/normalize-address (get-in @store [:wallet :address])))
        (swap! store assoc-in [:wallet :agent]
               (agent-session/default-agent-state
                :storage-mode :local
                :local-protection-mode :passkey
                :passkey-supported? (true? (:passkey-supported? agent-state)))))))
  (.catch (agent-lockbox/delete-locked-session! owner-address)
          (fn [_] nil)))

(defn remove-agent!
  "Send the CoreWriter transaction that removes the named API wallet
   `agent-name` of `owner-address`, and resolve with a response-like
   `{status: \"ok\"}` once Hyperliquid no longer lists it."
  [{:keys [store provider owner-address agent-name
           ensure-wallet-chain! send-transaction! fetch-extra-agents!
           set-timeout-fn now-ms-fn poll-ms]
    :or {ensure-wallet-chain! wallet-rpc/ensure-wallet-chain!
         send-transaction! wallet-rpc/send-transaction!
         fetch-extra-agents! fetch-extra-agents!
         set-timeout-fn platform/set-timeout!
         now-ms-fn platform/now-ms}}]
  (let [owner* (abi/normalize-address owner-address)
        name* (base-name agent-name)
        tx (when name*
             (core-writer/add-api-wallet-tx agent-session/zero-address name*))]
    (cond
      (nil? provider)
      (reject "No wallet provider found. Connect your wallet first.")

      (nil? owner*)
      (reject "Connect your wallet before removing an API wallet.")

      (nil? tx)
      (reject removal-unsupported-message)

      :else
      (let [record (registration-record/load-record owner*)
            own-key? (= name* (base-name (:agent-name record)))]
        (-> (js/Promise.resolve nil)
            (.then (fn [_]
                     (ensure-wallet-chain! provider (hyperevm-chain-config))))
            (.then (fn [_]
                     (send-transaction! provider owner* tx)))
            (.then (fn [_]
                     (watch! owner*
                             [:remove name*]
                             {:confirmed? (fn []
                                            (-> (fetch-extra-agents! owner*)
                                                (.then (fn [agents]
                                                         (not (named-key-present?
                                                               agents
                                                               name*))))))
                              :set-timeout-fn set-timeout-fn
                              :now-ms-fn now-ms-fn
                              :poll-ms poll-ms})))
            (.then (fn [_]
                     (when own-key?
                       (forget-own-key! store owner*))))
            (.then (fn [_]
                     (ok-response))))))))

;; ---------- Resume and cancel -------------------------------------------------

(defn resume!
  "Pick a recorded registration up again: when the connected account still
   waits for Hyperliquid to register its key and nothing is watching, start
   watching. Returns true when a wait is running afterwards."
  ([store]
   (resume! store {}))
  ([store {:keys [fetch-user-role! set-timeout-fn now-ms-fn poll-ms]
           :or {fetch-user-role! trading-http/fetch-user-role!
                set-timeout-fn platform/set-timeout!
                now-ms-fn platform/now-ms}}]
   (let [owner* (abi/normalize-address (get-in @store [:wallet :address]))
         record (when owner*
                  (registration-record/load-record owner*))
         metadata (when owner*
                    (agent-session/load-passkey-session-metadata owner*))]
     (cond
       (not (registration-record/pending-registration? record))
       false

       ;; The key the record waits for is gone from this device, so the
       ;; record describes nothing any more.
       (not (registration-record/for-agent? record (:agent-address metadata)))
       (do
         (registration-record/clear-record! owner*)
         false)

       :else
       (do
         (when-not (watching? owner*)
           (-> (watch-registration!
                {:store store
                 :owner-address owner*
                 :agent-address (:agent-address record)
                 :fetch-user-role! fetch-user-role!
                 :set-timeout-fn set-timeout-fn
                 :now-ms-fn now-ms-fn
                 :poll-ms poll-ms
                 :track? true})
               (.catch (fn [_] nil))))
         true)))))

(defn install-resume!
  "Resume a recorded registration now and whenever the wallet state starts
   to show one (after a reload, a reconnect or an account switch)."
  [store]
  (add-watch store ::resume
             (fn [_ _ old-state new-state]
               (let [old-wait (get-in old-state state-path)
                     new-wait (get-in new-state state-path)]
                 (when (and (not= old-wait new-wait)
                            (= :hyperliquid (:step new-wait)))
                   (platform/queue-microtask! #(resume! store))))))
  (resume! store))

(defn cancel-registration!
  "Stop waiting for the registration of the connected account and remove
   the key that was stored for it. The key the account used before, when
   there was one, is put back."
  [store]
  (let [owner* (abi/normalize-address (get-in @store [:wallet :address]))
        agent-state (get-in @store [:wallet :agent])
        passkey-supported? (true? (:passkey-supported? agent-state))
        record (when owner*
                 (registration-record/load-record owner*))]
    (if-not (registration-record/pending-registration? record)
      (js/Promise.resolve false)
      (do
        (registration-record/clear-record! owner*)
        (cancel-watch! owner*)
        (agent-session/clear-passkey-session-metadata! owner*)
        (swap! store assoc-in [:wallet :agent]
               (assoc (agent-session/default-agent-state
                       :storage-mode :local
                       :local-protection-mode :passkey
                       :passkey-supported? passkey-supported?)
                      :error registration-cancelled-message))
        (-> (agent-lockbox/delete-locked-session! owner*)
            (.catch (fn [_] nil))
            (.then (fn [_] (key-backup/restore! owner*)))
            (.then
             (fn [metadata]
               (when (and (map? metadata)
                          (= owner*
                             (abi/normalize-address
                              (get-in @store [:wallet :address]))))
                 (swap! store update-in [:wallet :agent] merge
                        {:status :locked
                         :agent-address (:agent-address metadata)
                         :last-approved-at (:last-approved-at metadata)
                         :nonce-cursor (:nonce-cursor metadata)}))
               true)))))))

;; ---------- Drop-in for approveAgent -----------------------------------------

(defn approve-agent!
  "Drop-in for `hyperopen.api.trading/approve-agent!`. A contract account on
   HyperEVM registers the key through CoreWriter; every other account signs
   `approveAgent` as before. The zero address removes the named key.

   The 4-arity takes overrides: `:approve-with-signature!`, `:detect!`,
   `:provider`, `:track?` and everything `register-agent!` accepts."
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
         (.then
          (fn [contract?]
            (if-not contract?
              (approve-with-signature! store address action)
              (let [opts (merge (dissoc deps :approve-with-signature! :detect!)
                                {:store store
                                 :provider (or provider (provider-registry/provider))
                                 :owner-address address
                                 :agent-name (:agentName action)})]
                (if (= agent-session/zero-address
                       (abi/normalize-address (:agentAddress action)))
                  (remove-agent! opts)
                  (register-agent!
                   (assoc opts :agent-address (:agentAddress action))))))))))))

(defn approve-own-agent!
  "`approve-agent!` for the trading key of this device: the registration is
   recorded and resumed."
  [store address action]
  (approve-agent! {:track? true} store address action))
