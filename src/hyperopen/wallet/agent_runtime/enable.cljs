(ns hyperopen.wallet.agent-runtime.enable
  (:require [hyperopen.wallet.agent-runtime.approval :as approval]
            [hyperopen.wallet.agent-runtime.errors :as errors]
            [hyperopen.wallet.agent-runtime.state-projection :as state]))

(defn- noop
  [& _]
  nil)

(defn- noop-promise
  [& _]
  (js/Promise.resolve nil))

(defn- fallback-local-protection-mode
  [local-protection-mode]
  (if (or (= :passkey local-protection-mode)
          (= "passkey" local-protection-mode))
    :passkey
    :plain))

(defn- passkey-local-mode?
  [storage-mode local-protection-mode]
  (and (= :local storage-mode)
       (= :passkey local-protection-mode)))

(defn- cleanup-failed-persistence!
  [{:keys [owner-address
           storage-mode
           clear-agent-session-by-mode!
           clear-unlocked-session!]}]
  (when (seq owner-address)
    (when (fn? clear-agent-session-by-mode!)
      (clear-agent-session-by-mode! owner-address storage-mode))
    (when (fn? clear-unlocked-session!)
      (clear-unlocked-session! owner-address))))

(defn- ignore-delete-failure!
  [delete-promise]
  (if (some? delete-promise)
    (.catch delete-promise (fn [_] (js/Promise.resolve nil)))
    (js/Promise.resolve nil)))

(defn- persist-enabled-agent-session!
  [{:keys [owner-address
           agent-address
           private-key
           storage-mode
           local-protection-mode
           last-approved-at
           nonce-cursor
           passkey-lock-supported?
           create-locked-session!
           cache-unlocked-session!
           persist-passkey-session-metadata!
           delete-locked-session!
           persist-agent-session-by-mode!
           clear-agent-session-by-mode!
           clear-unlocked-session!
           persist-session-error
           current-operation?]
    :or {passkey-lock-supported? (constantly false)
         create-locked-session! noop-promise
         cache-unlocked-session! noop
         persist-passkey-session-metadata! (constantly false)
         delete-locked-session! noop-promise
         persist-session-error "Unable to persist agent credentials."
         current-operation? (constantly true)}}]
  (cond
    (not (current-operation?))
    (js/Promise.resolve nil)

    (passkey-local-mode? storage-mode local-protection-mode)
    (if-not (passkey-lock-supported?)
      (js/Promise.reject
       (errors/known-error "Passkey unlock is unavailable in this browser."))
      (-> (create-locked-session! {:wallet-address owner-address
                                   :session {:agent-address agent-address
                                             :private-key private-key
                                             :last-approved-at last-approved-at
                                             :nonce-cursor nonce-cursor}})
          (.then
           (fn [{:keys [metadata session]}]
             (cond
               (not (current-operation?))
               (ignore-delete-failure! (delete-locked-session! owner-address))

               (persist-passkey-session-metadata! owner-address metadata)
               (do
                 (cache-unlocked-session! owner-address
                                          (assoc session
                                                 :storage-mode storage-mode
                                                 :local-protection-mode local-protection-mode))
                 {:storage-mode storage-mode
                  :local-protection-mode local-protection-mode
                  :agent-address agent-address
                  :last-approved-at last-approved-at
                  :nonce-cursor nonce-cursor})

               :else
               (-> (delete-locked-session! owner-address)
                   (.then (fn [_]
                            (js/Promise.reject
                             (errors/known-error persist-session-error))))))))))

    (persist-agent-session-by-mode!
     owner-address
     storage-mode
     {:agent-address agent-address
      :private-key private-key
      :last-approved-at last-approved-at
      :nonce-cursor nonce-cursor})
    (do
      (cache-unlocked-session! owner-address
                               {:agent-address agent-address
                                :private-key private-key
                                :last-approved-at last-approved-at
                                :nonce-cursor nonce-cursor
                                :storage-mode storage-mode
                                :local-protection-mode local-protection-mode})
      (js/Promise.resolve {:storage-mode storage-mode
                           :local-protection-mode local-protection-mode
                           :agent-address agent-address
                           :last-approved-at last-approved-at
                           :nonce-cursor nonce-cursor}))

    :else
    (do
      (cleanup-failed-persistence! {:owner-address owner-address
                                    :storage-mode storage-mode
                                    :clear-agent-session-by-mode! clear-agent-session-by-mode!
                                    :clear-unlocked-session! clear-unlocked-session!})
      (js/Promise.reject
       (errors/known-error persist-session-error)))))

(defn- restored-key-error
  "`err`, marked to say that the key the account used before is back in
   place and locked."
  [err metadata]
  (let [err* (if (instance? js/Error err) err (js/Error. (str err)))]
    (unchecked-set err* "restoredKeyMetadata" metadata)
    err*))

(defn- lock-then-approve!
  "The order for an account whose approval is not instant (a contract
   account whose registration may wait for other signers): store the key
   first, then ask for the approval.

   The usual order approves first, and stores the key only once Hyperliquid
   said OK. Here the user may be in another tab or window while the
   approval is pending. A passkey prompt at that point would fail, because
   the browser only shows one for a focused page, and the approved key would
   be lost.

   Storing the new key overwrites the one the account trades with now, so
   that one is set aside first. When the approval fails, the new key is
   removed and the old one is put back."
  [{:keys [owner-address
           storage-mode
           local-protection-mode
           now-ms-fn
           approve!
           persist!
           current-operation?
           clear-agent-session-by-mode!
           clear-unlocked-session!
           clear-passkey-session-metadata!
           delete-locked-session!
           backup-stored-key!
           restore-stored-key!
           drop-key-backup!]}]
  (let [locked-at (now-ms-fn)
        discard-stored-key!
        (fn []
          (cleanup-failed-persistence! {:owner-address owner-address
                                        :storage-mode storage-mode
                                        :clear-agent-session-by-mode! clear-agent-session-by-mode!
                                        :clear-unlocked-session! clear-unlocked-session!})
          (if (passkey-local-mode? storage-mode local-protection-mode)
            (do
              (clear-passkey-session-metadata! owner-address)
              (ignore-delete-failure! (delete-locked-session! owner-address)))
            (js/Promise.resolve nil)))
        restore-and-reject!
        (fn [err]
          (-> (restore-stored-key! owner-address)
              (.then (fn [metadata]
                       (js/Promise.reject
                        (if (map? metadata)
                          (restored-key-error err metadata)
                          err))))))
        fail!
        (fn [err]
          (-> (discard-stored-key!)
              (.then (fn [_] (restore-and-reject! err)))))]
    (-> (backup-stored-key! owner-address)
        (.then (fn [_]
                 (-> (persist! {:last-approved-at locked-at
                                :nonce-cursor locked-at})
                     ;; The new key was not stored (the passkey prompt was
                     ;; dismissed, for example), so there is nothing of it
                     ;; to remove. The old key may have been overwritten on
                     ;; the way, so it is put back from what was set aside.
                     (.catch restore-and-reject!))))
        (.then
         (fn [session]
           (if-not session
             ;; Another operation took over while the key was being stored.
             (-> (restore-stored-key! owner-address)
                 (.then (fn [_] nil)))
             (-> (approve!)
                 (.then (fn [_]
                          (drop-key-backup! owner-address)
                          session))
                 (.catch
                  (fn [err]
                    (if (current-operation?)
                      (fail! err)
                      (js/Promise.reject err)))))))))))

(defn enable-agent-trading!
  [{:keys [store
           options
           create-agent-credentials!
           now-ms-fn
           normalize-storage-mode
           normalize-local-protection-mode
           ensure-device-label!
           passkey-lock-supported?
           create-locked-session!
           cache-unlocked-session!
           persist-passkey-session-metadata!
           delete-locked-session!
           default-signature-chain-id-for-environment
           build-approve-agent-action
           format-agent-name-with-valid-until
           approve-agent!
           persist-agent-session-by-mode!
           clear-agent-session-by-mode!
           clear-unlocked-session!
           clear-passkey-session-metadata!
           backup-stored-key!
           restore-stored-key!
           drop-key-backup!
           runtime-error-message
           exchange-response-error]
    :or {normalize-storage-mode identity
         normalize-local-protection-mode fallback-local-protection-mode
         ensure-device-label! (constantly nil)
         passkey-lock-supported? (constantly false)
         create-locked-session! noop-promise
         cache-unlocked-session! noop
         persist-passkey-session-metadata! (constantly false)
         delete-locked-session! noop-promise
         clear-passkey-session-metadata! noop
         backup-stored-key! noop-promise
         restore-stored-key! noop-promise
         drop-key-backup! noop
         runtime-error-message errors/runtime-error-message
         exchange-response-error errors/exchange-response-error}}]
  (let [{:keys [storage-mode local-protection-mode is-mainnet agent-name signature-chain-id
                lock-before-approval?]
         :or {storage-mode :local
              local-protection-mode :plain
              is-mainnet true
              agent-name nil
              signature-chain-id nil
              lock-before-approval? false}} options
        owner-address (get-in @store [:wallet :address])]
    (if-not (seq owner-address)
      (state/set-agent-error! store "Connect your wallet before enabling trading.")
      (try
        (state/invalidate-operations! store
                                      :active-unlock-token
                                      :active-protection-token)
        (let [operation-token (state/next-operation-token! store :active-enable-token)
              current-operation? #(state/operation-current? store
                                                            :active-enable-token
                                                            operation-token)
              {:keys [private-key agent-address]} (create-agent-credentials!)
              normalized-storage-mode (normalize-storage-mode storage-mode)
              normalized-local-protection-mode
              (normalize-local-protection-mode local-protection-mode)
              device-label (ensure-device-label!)
              resolved-agent-name (or agent-name device-label)]
          (let [approve!
                (fn []
                  (approval/approve-agent-request!
                   {:store store
                    :owner-address owner-address
                    :agent-address agent-address
                    :private-key private-key
                    :storage-mode normalized-storage-mode
                    :is-mainnet is-mainnet
                    :agent-name resolved-agent-name
                    :signature-chain-id signature-chain-id
                    :persist-session? false
                    :missing-owner-error "Connect your wallet before enabling trading."
                    :persist-session-error "Unable to persist agent credentials."
                    :now-ms-fn now-ms-fn
                    :normalize-storage-mode normalize-storage-mode
                    :default-signature-chain-id-for-environment default-signature-chain-id-for-environment
                    :build-approve-agent-action build-approve-agent-action
                    :format-agent-name-with-valid-until format-agent-name-with-valid-until
                    :approve-agent! approve-agent!
                    :persist-agent-session-by-mode! persist-agent-session-by-mode!
                    :runtime-error-message runtime-error-message
                    :exchange-response-error exchange-response-error}))
                persist!
                (fn [{:keys [last-approved-at nonce-cursor]}]
                  (persist-enabled-agent-session!
                   {:owner-address owner-address
                    :agent-address agent-address
                    :private-key private-key
                    :storage-mode normalized-storage-mode
                    :local-protection-mode normalized-local-protection-mode
                    :last-approved-at last-approved-at
                    :nonce-cursor nonce-cursor
                    :passkey-lock-supported? passkey-lock-supported?
                    :create-locked-session! create-locked-session!
                    :cache-unlocked-session! cache-unlocked-session!
                    :persist-passkey-session-metadata! persist-passkey-session-metadata!
                    :delete-locked-session! delete-locked-session!
                    :persist-agent-session-by-mode! persist-agent-session-by-mode!
                    :clear-agent-session-by-mode! clear-agent-session-by-mode!
                    :clear-unlocked-session! clear-unlocked-session!
                    :persist-session-error "Unable to persist agent credentials."
                    :current-operation? current-operation?}))
                enabled-session
                (if lock-before-approval?
                  (lock-then-approve!
                   {:owner-address owner-address
                    :storage-mode normalized-storage-mode
                    :local-protection-mode normalized-local-protection-mode
                    :now-ms-fn now-ms-fn
                    :approve! approve!
                    :persist! persist!
                    :current-operation? current-operation?
                    :clear-agent-session-by-mode! clear-agent-session-by-mode!
                    :clear-unlocked-session! clear-unlocked-session!
                    :clear-passkey-session-metadata! clear-passkey-session-metadata!
                    :delete-locked-session! delete-locked-session!
                    :backup-stored-key! backup-stored-key!
                    :restore-stored-key! restore-stored-key!
                    :drop-key-backup! drop-key-backup!})
                  (-> (approve!)
                      (.then persist!)))]
            (-> enabled-session
                (.then
                 (fn [session]
                   (when (and session (current-operation?))
                     (state/apply-ready-agent-session! store
                                                       session
                                                       :active-enable-token))))
                (.catch
                 (fn [err]
                   (when (current-operation?)
                     (let [message (errors/known-or-runtime-error-message
                                    runtime-error-message
                                    err)
                           restored (when (some? err)
                                      (unchecked-get err "restoredKeyMetadata"))]
                       (if (map? restored)
                         ;; The key used before is back, locked.
                         (swap! store update-in [:wallet :agent]
                                (fn [agent-state]
                                  (-> agent-state
                                      (merge (state/metadata->ready-session
                                              restored
                                              normalized-storage-mode
                                              normalized-local-protection-mode)
                                             {:status :locked
                                              :error message})
                                      (dissoc :active-enable-token))))
                         (state/set-agent-error! store message)))))))))
        (catch :default err
          (state/set-agent-error! store (runtime-error-message err)))))))
