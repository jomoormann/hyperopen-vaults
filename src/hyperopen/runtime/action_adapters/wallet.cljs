(ns hyperopen.runtime.action-adapters.wallet
  (:require [clojure.string :as str]
            [nexus.registry :as nxr]
            [hyperopen.platform :as platform]
            [hyperopen.portfolio.routes :as portfolio-routes]
            [hyperopen.borrow-lend.actions :as borrow-lend-actions]
            [hyperopen.staking.actions :as staking-actions]
            [hyperopen.trading-crypto-modules :as trading-crypto-modules]
            [hyperopen.wallet.actions :as wallet-actions]
            [hyperopen.wallet.agent-lockbox :as agent-lockbox]
            [hyperopen.wallet.agent-runtime :as agent-runtime]
            [hyperopen.wallet.agent-session :as agent-session]
            [hyperopen.wallet.connection-runtime :as wallet-connection-runtime]
            [hyperopen.wallet.contract-account :as contract-account]
            [hyperopen.wallet.key-backup :as key-backup]
            [hyperopen.wallet.vault-registration :as vault-registration]))

(def connect-wallet-action wallet-actions/connect-wallet-action)

(defn disconnect-wallet-action
  [_state]
  (wallet-actions/disconnect-wallet-action nil))

(def should-auto-enable-agent-trading?
  wallet-connection-runtime/should-auto-enable-agent-trading?)

(defn handle-wallet-connected
  [store connected-address]
  (let [result (wallet-connection-runtime/handle-wallet-connected!
                {:store store
                 :connected-address connected-address
                 :should-auto-enable-agent-trading? should-auto-enable-agent-trading?
                 :dispatch! nxr/dispatch})
        route (get-in @store [:router :path])]
    ;; Learn early whether this is a contract account, so the guards that
    ;; read the remembered answer have it before the first user action.
    (when (contract-account/candidate? store)
      (-> (contract-account/detect-for-store! store connected-address)
          (.catch (fn [_] nil))))
    (when (str/starts-with? (or route "") "/vaults")
      (nxr/dispatch store nil [[:actions/load-vault-route route]]))
    (when (staking-actions/staking-route? route)
      (nxr/dispatch store nil [[:actions/load-staking-route route]]))
    (when (borrow-lend-actions/earn-route? route)
      (nxr/dispatch store nil [[:actions/load-borrow-lend-route route]]))
    (when (portfolio-routes/portfolio-optimize-route? route)
      (nxr/dispatch store nil [[:actions/load-portfolio-optimizer-route route]]))
    result))

(defn- contract-account-options
  "A contract account's trading key is always kept behind a passkey, and it
   is stored before the registration is sent, because that registration can
   wait a long time for other signers
   (`hyperopen.wallet.agent-runtime.enable/lock-then-approve!`)."
  [options]
  (assoc options
         :storage-mode :local
         :local-protection-mode :passkey
         :lock-before-approval? true))

(defn enable-agent-trading
  [_ store {:keys [storage-mode local-protection-mode is-mainnet agent-name signature-chain-id]
            :or {storage-mode :local
                 local-protection-mode :plain
                 is-mainnet true
                 agent-name nil
                 signature-chain-id nil}}]
  (letfn [(set-agent-load-error! [err]
            (swap! store update-in [:wallet :agent] merge
                   {:status :error
                    :error (agent-runtime/runtime-error-message err)
                    :agent-address nil
                    :last-approved-at nil
                    :nonce-cursor nil}))
          (enable-with-crypto! [contract-account? crypto]
            (when contract-account?
              (agent-session/persist-storage-mode-preference! :local)
              (agent-session/persist-local-protection-mode-preference! :passkey))
            (agent-runtime/enable-agent-trading!
             {:store store
              :options (cond-> {:storage-mode storage-mode
                                :local-protection-mode local-protection-mode
                                :is-mainnet is-mainnet
                                :agent-name agent-name
                                :signature-chain-id signature-chain-id}
                         contract-account? contract-account-options)
              :create-agent-credentials! (:create-agent-credentials! crypto)
              :now-ms-fn platform/now-ms
              :normalize-storage-mode agent-session/normalize-storage-mode
              :normalize-local-protection-mode agent-session/normalize-local-protection-mode
              :ensure-device-label! agent-session/ensure-device-label!
              :passkey-lock-supported? agent-lockbox/passkey-lock-supported?
              :create-locked-session! agent-lockbox/create-locked-session!
              :cache-unlocked-session! agent-lockbox/cache-unlocked-session!
              :persist-passkey-session-metadata! agent-session/persist-passkey-session-metadata!
              :delete-locked-session! agent-lockbox/delete-locked-session!
              :default-signature-chain-id-for-environment
              agent-session/default-signature-chain-id-for-environment
              :build-approve-agent-action agent-session/build-approve-agent-action
              :format-agent-name-with-valid-until agent-session/format-agent-name-with-valid-until
              ;; The key this device trades with: its registration is
              ;; recorded, so the wait survives a reload.
              :approve-agent! vault-registration/approve-own-agent!
              :persist-agent-session-by-mode! agent-session/persist-agent-session-by-mode!
              :clear-agent-session-by-mode! agent-session/clear-agent-session-by-mode!
              :clear-unlocked-session! agent-lockbox/clear-unlocked-session!
              :clear-passkey-session-metadata! agent-session/clear-passkey-session-metadata!
              :backup-stored-key! key-backup/backup!
              :restore-stored-key! key-backup/restore!
              :drop-key-backup! key-backup/drop!
              :runtime-error-message agent-runtime/runtime-error-message
              :exchange-response-error agent-runtime/exchange-response-error}))
          (enable! [contract-account?]
            (if-let [crypto (trading-crypto-modules/resolved-trading-crypto)]
              (enable-with-crypto! contract-account? crypto)
              (-> (trading-crypto-modules/load-trading-crypto-module!)
                  (.then (partial enable-with-crypto! contract-account?))
                  (.catch set-agent-load-error!))))]
    ;; Only a wallet on HyperEVM can be a contract account, so every other
    ;; wallet keeps the synchronous path and costs no RPC read.
    (if (contract-account/candidate? store)
      (-> (contract-account/detect-for-store! store)
          (.then enable!)
          (.catch set-agent-load-error!))
      (enable! false))))

(defn enable-agent-trading-action
  [state]
  (wallet-actions/enable-agent-trading-action
   state
   agent-session/normalize-storage-mode
   agent-session/normalize-local-protection-mode))

(defn set-agent-storage-mode-action
  [state storage-mode]
  (wallet-actions/set-agent-storage-mode-action
   state
   storage-mode
   agent-session/normalize-storage-mode))

(defn unlock-agent-trading
  [_ store]
  (agent-runtime/unlock-agent-trading!
   {:store store
    :normalize-storage-mode agent-session/normalize-storage-mode
    :normalize-local-protection-mode agent-session/normalize-local-protection-mode
    :load-passkey-session-metadata agent-session/load-passkey-session-metadata
    :unlock-locked-session! (fn [opts]
                              (agent-lockbox/unlock-locked-session!
                               (assoc opts :cache-session? false)))
    :cache-unlocked-session! agent-lockbox/cache-unlocked-session!
    :runtime-error-message agent-runtime/runtime-error-message}))

(defn unlock-agent-trading-action
  ([state]
   (wallet-actions/unlock-agent-trading-action state))
  ([state payload]
   (wallet-actions/unlock-agent-trading-action state payload)))

(defn set-agent-local-protection-mode-action
  [state local-protection-mode]
  (wallet-actions/set-agent-local-protection-mode-action
   state
   local-protection-mode
   agent-session/normalize-local-protection-mode))

(def copy-wallet-address-action wallet-actions/copy-wallet-address-action)
