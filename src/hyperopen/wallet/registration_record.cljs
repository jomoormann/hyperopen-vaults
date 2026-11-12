(ns hyperopen.wallet.registration-record
  "The record of a trading key that a contract account registers through
   CoreWriter (`hyperopen.wallet.vault-registration`).

   The registration is a transaction that can wait hours or days for other
   signers, so the page cannot hold it in memory. The record is what lets
   the app pick the registration up again after a reload, and what tells
   the rest of the wallet code that this key must not be thrown away on a
   disconnect or an account switch: getting a new one costs another round
   of signatures.

   It holds no secret. The key itself stays in the passkey lockbox.

   One small record per account, read synchronously when the wallet
   connects: `localStorage` per `/hyperopen/docs/BROWSER_STORAGE.md`."
  (:require [clojure.string :as str]))

(def ^:private record-prefix
  "hyperopen:agent-registration:v1:")

(def ^:private address-regex
  #"^0x[0-9a-f]{40}$")

(defn- normalize-address
  [address]
  (let [address* (some-> address str str/trim str/lower-case)]
    (when (and (seq address*)
               (re-matches address-regex address*))
      address*)))

(defn record-key
  [owner-address]
  (when-let [owner (normalize-address owner-address)]
    (str record-prefix owner)))

(defn- floor-when-number
  [value]
  (when (and (number? value)
             (not (js/isNaN value)))
    (js/Math.floor value)))

(defn- sanitize
  [record]
  (when-let [agent-address (normalize-address (:agent-address record))]
    (when-let [started-at-ms (floor-when-number (:started-at-ms record))]
      {:version 1
       :kind (if (= "remove" (some-> (:kind record) name)) :remove :register)
       :agent-address agent-address
       :agent-name (some-> (:agent-name record) str str/trim not-empty)
       :valid-until-ms (floor-when-number (:valid-until-ms record))
       :started-at-ms started-at-ms
       :registered-at-ms (floor-when-number (:registered-at-ms record))
       :saved-at-ms (floor-when-number (:saved-at-ms record))})))

(defn- local-storage
  []
  (some-> js/globalThis .-localStorage))

(defn load-record
  [owner-address]
  (let [storage (local-storage)
        key (record-key owner-address)]
    (when (and storage (seq key))
      (try
        (let [raw (.getItem storage key)]
          (when (seq raw)
            (sanitize (js->clj (js/JSON.parse raw) :keywordize-keys true))))
        (catch :default _
          nil)))))

(defn persist-record!
  [owner-address record]
  (let [storage (local-storage)
        key (record-key owner-address)
        record* (sanitize record)]
    (if-not (and storage (seq key) record*)
      false
      (try
        (.setItem storage key
                  (js/JSON.stringify
                   (clj->js (update record* :kind name))))
        true
        (catch :default _
          false)))))

(defn clear-record!
  [owner-address]
  (let [storage (local-storage)
        key (record-key owner-address)]
    (if-not (and storage (seq key))
      false
      (try
        (.removeItem storage key)
        true
        (catch :default _
          false)))))

(defn pending?
  "True while Hyperliquid has not confirmed what the record asks for."
  [record]
  (and (map? record)
       (nil? (:registered-at-ms record))))

(defn pending-registration?
  [record]
  (and (pending? record)
       (= :register (:kind record))))

(defn pending-removal?
  [record]
  (and (pending? record)
       (= :remove (:kind record))))

(defn for-agent?
  [record agent-address]
  (let [agent* (normalize-address agent-address)]
    (and (map? record)
         (some? agent*)
         (= agent* (:agent-address record)))))

(defn keeps-key?
  "True when `owner-address` has a key that was registered, or is being
   registered, by a transaction. Such a key outlives a disconnect."
  [owner-address]
  (some? (load-record owner-address)))

(defn pending-registration-for?
  "True when `agent-address` is the key `owner-address` is still waiting to
   have registered."
  [owner-address agent-address]
  (let [record (load-record owner-address)]
    (and (pending-registration? record)
         (for-agent? record agent-address))))

(defn mark-registered!
  [owner-address agent-address now-ms]
  (let [record (load-record owner-address)]
    (when (and (pending-registration? record)
               (for-agent? record agent-address))
      (persist-record! owner-address
                       (assoc record
                              :registered-at-ms now-ms
                              :saved-at-ms now-ms)))))

(def renewal-notice-days
  "From this many days before the expiry the app asks for a renewal."
  14)

(declare days-left)

(defn key-expiry
  "What the wallet state says about the expiry of the registered key in
   `record`: `{:valid-until-ms :days-left}`, or nil while the key is not
   registered or has no expiry."
  [record now-ms]
  (when (and (map? record)
             (not (pending? record))
             (= :register (:kind record))
             (number? (:valid-until-ms record)))
    {:valid-until-ms (:valid-until-ms record)
     :days-left (days-left record now-ms)}))

(defn key-expiry-for
  [owner-address agent-address now-ms]
  (let [record (load-record owner-address)]
    (when (for-agent? record agent-address)
      (key-expiry record now-ms))))

(defn days-left
  "Whole days until the registered key expires, or nil when the record has
   no expiry. Negative once it has passed."
  [record now-ms]
  (when-let [valid-until-ms (:valid-until-ms record)]
    (js/Math.floor (/ (- valid-until-ms now-ms)
                      (* 24 60 60 1000)))))
