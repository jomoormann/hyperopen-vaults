(ns hyperopen.wallet.key-backup
  "Keep the working trading key while its replacement waits for approval.

   A contract account stores a new key before it asks for the registration
   (`hyperopen.wallet.agent-runtime.enable/lock-then-approve!`). Storing it
   overwrites the key the account uses now. When the registration is
   refused or cancelled, that would leave the account with no key at all,
   although the old one is still registered with Hyperliquid.

   So the old lockbox record and its passkey metadata are set aside first,
   and put back when the replacement does not go through. Both are already
   encrypted or public: nothing here sees the key."
  (:require [clojure.string :as str]
            [hyperopen.platform.indexed-db :as indexed-db]
            [hyperopen.wallet.agent-session :as agent-session]))

(def ^:private previous-metadata-prefix
  "hyperopen:agent-passkey-session-previous:v1:")

(defn- normalize-address
  [address]
  (agent-session/normalize-wallet-address address))

(defn- previous-lockbox-key
  [owner]
  (str owner ":previous"))

(defn- previous-metadata-key
  [owner]
  (str previous-metadata-prefix owner))

(defn- local-storage
  []
  (some-> js/globalThis .-localStorage))

(defn- load-previous-metadata
  [owner]
  (when-let [storage (local-storage)]
    (try
      (let [raw (.getItem storage (previous-metadata-key owner))]
        (when (seq raw)
          (let [metadata (js->clj (js/JSON.parse raw) :keywordize-keys true)]
            (when (and (map? metadata)
                       (seq (some-> (:agent-address metadata) str str/trim)))
              metadata))))
      (catch :default _
        nil))))

(defn- persist-previous-metadata!
  [owner metadata]
  (when-let [storage (local-storage)]
    (try
      (.setItem storage
                (previous-metadata-key owner)
                (js/JSON.stringify (clj->js metadata)))
      true
      (catch :default _
        false))))

(defn- clear-previous-metadata!
  [owner]
  (when-let [storage (local-storage)]
    (try
      (.removeItem storage (previous-metadata-key owner))
      true
      (catch :default _
        false))))

(defn- settle-false
  [promise]
  (.catch promise (fn [_] false)))

(defn- copy-lockbox-record!
  "Copy the lockbox record stored under `from-key` to `to-key`. Resolves
   true when there was a record and it was written."
  [from-key to-key]
  (-> (indexed-db/get-json! indexed-db/agent-locked-session-store from-key)
      (.then
       (fn [lockbox-record]
         (if (map? lockbox-record)
           (indexed-db/put-json! indexed-db/agent-locked-session-store
                                 to-key
                                 lockbox-record)
           false)))
      (.then boolean)))

(defn backup!
  "Set the stored key of `owner-address` aside. Resolves true when there was
   one, false otherwise. Never rejects."
  [owner-address]
  (let [owner (normalize-address owner-address)
        metadata (when owner
                   (agent-session/load-passkey-session-metadata owner))]
    (if-not (map? metadata)
      (js/Promise.resolve false)
      (settle-false
       (-> (copy-lockbox-record! owner (previous-lockbox-key owner))
           (.then (fn [copied?]
                    (boolean
                     (and copied?
                          (persist-previous-metadata! owner metadata))))))))))

(defn- delete-previous-lockbox!
  [owner]
  (settle-false
   (indexed-db/delete-key! indexed-db/agent-locked-session-store
                           (previous-lockbox-key owner))))

(defn restore!
  "Put the key that was set aside back in place. Resolves its passkey
   metadata, or nil when there was nothing to restore. Never rejects."
  [owner-address]
  (let [owner (normalize-address owner-address)
        metadata (when owner
                   (load-previous-metadata owner))]
    (if-not (map? metadata)
      (js/Promise.resolve nil)
      (-> (copy-lockbox-record! (previous-lockbox-key owner) owner)
          (.then
           (fn [copied?]
             (when (and copied?
                        (agent-session/persist-passkey-session-metadata! owner metadata))
               (clear-previous-metadata! owner)
               (-> (delete-previous-lockbox! owner)
                   (.then (fn [_]
                            (agent-session/load-passkey-session-metadata owner)))))))
          (.catch (fn [_] nil))))))

(defn drop!
  "Forget the key that was set aside. Never rejects."
  [owner-address]
  (if-let [owner (normalize-address owner-address)]
    (do
      (clear-previous-metadata! owner)
      (delete-previous-lockbox! owner))
    (js/Promise.resolve false)))
