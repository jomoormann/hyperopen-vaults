(ns hyperopen.runtime.collaborators.wallet
  (:require [hyperopen.wallet.actions :as wallet-actions]))

(defn action-deps []
  {:connect-wallet-action wallet-actions/connect-wallet-action
   :disconnect-wallet-action wallet-actions/disconnect-wallet-action
   :close-agent-recovery-modal-action wallet-actions/close-agent-recovery-modal-action
   :cancel-agent-registration-action wallet-actions/cancel-agent-registration-action
   :copy-wallet-address-action wallet-actions/copy-wallet-address-action})
