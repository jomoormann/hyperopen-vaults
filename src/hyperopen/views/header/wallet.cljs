(ns hyperopen.views.header.wallet
  (:require [hyperopen.views.header.account-selector :as account-selector]
            [hyperopen.views.header.icons :as icons]))

(defn- wallet-copy-feedback-row
  [{:keys [kind message]}]
  (let [text-classes (if (= :success kind)
                       ["text-success"]
                       ["text-error"])]
    [:div {:class (into ["flex"
                         "items-center"
                         "gap-1.5"
                         "px-3"
                         "pb-2"
                         "text-xs"
                         "font-medium"]
                        text-classes)
           :data-role "wallet-copy-feedback"}
     (icons/feedback-icon
      kind
      {:class ["h-3.5" "w-3.5"]
       :data-role (if (= :success kind)
                    "wallet-copy-feedback-success-icon"
                    "wallet-copy-feedback-error-icon")})
     [:span message]]))

(defn- enable-trading-button
  [{:keys [action disabled? label]}]
  (when label
    [:button {:type "button"
              :class ["mx-3"
                      "mb-2"
                      "mt-1"
                      "block"
                      "w-[calc(100%-1.5rem)]"
                      "rounded-lg"
                      "bg-teal-600"
                      "px-3"
                      "py-2"
                      "text-sm"
                      "font-medium"
                      "text-teal-100"
                      "transition-colors"
                      "hover:bg-teal-700"
                      "disabled:cursor-not-allowed"
                      "disabled:opacity-60"]
              :disabled disabled?
              :on {:click action}
              :data-role "wallet-enable-trading"}
     label]))

(defn- wallet-agent-error-row
  [message]
  (when (seq message)
    [:div {:class ["px-3"
                   "pb-2"
                   "text-xs"
                   "font-medium"
                   "leading-snug"
                   "text-error"]
           :aria-live "polite"
           :data-role "wallet-agent-error"}
     message]))

(defn- menu-hint
  [text data-role]
  [:div {:class ["px-3"
                 "pb-2"
                 "text-xs"
                 "leading-snug"
                 "text-gray-400"]
         :data-role data-role}
   text])

(defn- menu-text-button
  [{:keys [action label]} data-role]
  [:button {:type "button"
            :class ["block"
                    "w-full"
                    "px-3"
                    "pb-3"
                    "text-left"
                    "text-xs"
                    "font-medium"
                    "text-[#50f6d2]"
                    "transition-colors"
                    "hover:underline"
                    "focus:outline-none"]
            :on {:click action}
            :data-role data-role}
   label])

(defn- account-mode-rows
  [{:keys [hint title] :as account-mode}]
  (when account-mode
    [[:div {:class ["px-3"
                    "pb-1"
                    "text-xs"
                    "font-medium"
                    "leading-snug"
                    "text-white"]
            :data-role "wallet-account-mode"}
      title]
     (menu-hint hint "wallet-account-mode-hint")]))

(defn- cancel-registration-rows
  [{:keys [hint key-address] :as cancel-registration}]
  (when cancel-registration
    [(menu-hint hint "wallet-registration-hint")
     (when (seq key-address)
       [:div {:class ["px-3" "pb-2" "text-xs" "leading-snug"]
              :data-role "wallet-registration-key"}
        [:div {:class ["text-gray-400"]} "Key address"]
        [:div {:class ["num" "break-all" "text-white" "select-all"]} key-address]])
     (menu-text-button cancel-registration "wallet-cancel-registration")]))

(defn- key-expiry-rows
  [{:keys [hint message] :as key-expiry}]
  (when key-expiry
    [(when (seq message)
       [:div {:class ["px-3"
                      "pt-1"
                      "pb-1"
                      "text-xs"
                      "font-medium"
                      "leading-snug"
                      "text-white"]
              :aria-live "polite"
              :data-role "wallet-key-expiry"}
        message])
     (menu-hint hint "wallet-key-expiry-hint")
     (menu-text-button key-expiry "wallet-renew-trading-key")
     [:div {:class ["h-px" "w-full" "bg-base-300"]}]]))

(defn- account-selector-section
  [selector]
  (when-let [content (seq (map account-selector/option-row (:options selector)))]
    [:div {:class ["border-b" "border-white/10"]
           :data-role "wallet-account-selector-section"}
     content]))

(defn- wallet-menu
  [{:keys [account-mode account-selector agent-error cancel-registration copy-action
           copy-feedback disconnect-action enable-trading key-expiry menu-address-label]}]
  (let [agent-error-row (wallet-agent-error-row agent-error)
        enable-trading-cta (enable-trading-button enable-trading)]
    (into
     [:div {:class ["ui-dropdown-panel"
                   "absolute"
                   "right-0"
                   "top-full"
                   "mt-2"
                   "w-48"
                   "overflow-hidden"
                   "rounded-xl"
                   "border"
                   "border-base-300"
                   "bg-trading-bg"
                   "isolate"
                   "shadow-2xl"
                   "z-[260]"]
           :data-ui-native-details-panel "true"
           :data-role "wallet-menu-panel"}
     (account-selector-section account-selector)
     [:button {:type "button"
               :class ["flex"
                       "w-full"
                       "items-center"
                       "justify-between"
                       "gap-2"
                       "px-3"
                       "py-3"
                       "text-left"
                       "text-sm"
                       "text-white"
                       "transition-colors"
                       "hover:bg-base-200"
                       "focus:outline-none"]
               :on {:click copy-action}
               :title "Copy address"
               :aria-label "Copy address"
               :data-role "wallet-menu-copy"}
      [:span {:class ["truncate" "num"]} menu-address-label]
      (icons/wallet-copy-icon)]
     (when (and (map? copy-feedback)
                (seq (:message copy-feedback)))
       (wallet-copy-feedback-row copy-feedback))
     (when agent-error-row
       agent-error-row)]
     ;; Spliced: this repo's Replicant has no fragment tag.
     (concat
      (account-mode-rows account-mode)
      (key-expiry-rows key-expiry)
      [(when enable-trading-cta
         enable-trading-cta)]
      (cancel-registration-rows cancel-registration)
      [(when enable-trading-cta
         [:div {:class ["h-px" "w-full" "bg-base-300"]}])
       [:button {:type "button"
                 :class ["block"
                         "w-full"
                         "px-3"
                         "py-3"
                         "text-left"
                         "text-sm"
                         "font-medium"
                         "text-[#50f6d2]"
                         "transition-colors"
                         "hover:bg-base-200"
                         "focus:outline-none"]
                 :on {:click disconnect-action}
                 :data-role "wallet-menu-disconnect"}
        "Disconnect"]]))))

(defn- wallet-trigger
  [{:keys [account-selector trigger-label]}]
  (let [active-subaccount (:active-subaccount account-selector)
        label (if active-subaccount
                (:trigger-label account-selector)
                trigger-label)]
    [:summary {:class ["relative"
                       "z-[170]"
                       "inline-flex"
                       "h-9"
                       "sm:h-10"
                       "items-center"
                       "gap-2"
                       "rounded-xl"
                       "border"
                       "border-base-300"
                       "bg-base-100"
                       "px-2.5"
                       "sm:px-3"
                       "text-xs"
                       "sm:text-sm"
                       "text-white"
                       "transition-colors"
                       "hover:bg-base-200"
                       "list-none"
                       "cursor-pointer"]
               :data-role "wallet-menu-trigger"
               :aria-haspopup "menu"
               :aria-label "Connected account"}
     [:span {:class [(if active-subaccount "font-medium" "num")]} label]
     (icons/chevron-down-icon
      {:class ["h-4" "w-4" "text-gray-300" "transition-transform" "group-open:rotate-180"]
       :data-role "wallet-menu-chevron"})]))

(defn- connect-wallet-button
  [{:keys [connect-action connecting?]}]
  (into
   [:button {:class ["bg-teal-700"
                     "hover:bg-teal-800"
                     "text-white"
                     "inline-flex"
                     "h-9"
                     "items-center"
                     "justify-center"
                     "px-3"
                     "rounded-lg"
                     "text-xs"
                     "sm:text-sm"
                     "font-medium"
                     "transition-colors"]
             :disabled connecting?
             :on {:click connect-action}
             :data-role "wallet-connect-button"}]
   (if connecting?
     [[:span {:class ["sm:hidden"]} "Connecting…"]
      [:span {:class ["hidden" "sm:inline"]} "Connecting…"]]
     [[:span {:class ["sm:hidden"]} "Connect"]
      [:span {:class ["hidden" "sm:inline"]} "Connect Wallet"]])))

(defn- provider-connect-action
  [provider]
  (let [provider-id (:id provider)]
    (if (seq provider-id)
      [[:actions/connect-wallet provider-id]]
      [[:actions/connect-wallet]])))

(defn- provider-connect-option
  [provider connecting?]
  [:button {:class ["flex"
                    "w-full"
                    "items-center"
                    "justify-between"
                    "gap-3"
                    "px-3"
                    "py-2"
                    "text-left"
                    "text-xs"
                    "text-trading-text"
                    "hover:bg-trading-border/30"
                    "transition-colors"]
            :disabled connecting?
            :on {:click (provider-connect-action provider)}
            :data-role "wallet-connect-provider"
            :data-provider-id (:id provider)}
   [:span {:class ["truncate"]} (:name provider)]
   (when-let [rdns (:rdns provider)]
     [:span {:class ["hidden"
                     "max-w-[8rem]"
                     "truncate"
                     "text-xs"
                     "text-trading-muted"
                     "sm:inline"]}
      rdns])])

(defn- provider-connect-menu
  [{:keys [connecting? providers]}]
  (if connecting?
    (connect-wallet-button {:connect-action [[:actions/connect-wallet]]
                            :connecting? true})
    [:details {:class ["relative" "group"]
               :data-role "wallet-connect-provider-details"}
     [:summary {:class ["bg-teal-700"
                        "hover:bg-teal-800"
                        "text-white"
                        "inline-flex"
                        "h-9"
                        "cursor-pointer"
                        "list-none"
                        "items-center"
                        "justify-center"
                        "px-3"
                        "rounded-lg"
                        "text-xs"
                        "sm:text-sm"
                        "font-medium"
                        "transition-colors"
                        "[&::-webkit-details-marker]:hidden"]
                :data-role "wallet-connect-button"}
      [:span {:class ["sm:hidden"]} "Connect"]
      [:span {:class ["hidden" "sm:inline"]} "Connect Wallet"]]
     [:div {:class ["absolute"
                    "right-0"
                    "z-50"
                    "mt-2"
                    "w-64"
                    "overflow-hidden"
                    "rounded-lg"
                    "border"
                    "border-trading-border"
                    "bg-[#071a1f]"
                    "shadow-xl"]
            :data-role "wallet-provider-menu"}
      (for [provider providers]
        (provider-connect-option provider connecting?))]]))

(defn render
  [{:keys [connected? providers] :as wallet}]
  (if connected?
    [:details {:class ["relative" "group"] :data-role "wallet-menu-details"}
     (wallet-trigger wallet)
     (wallet-menu wallet)]
    (if (> (count providers) 1)
      (provider-connect-menu wallet)
      (connect-wallet-button wallet))))
