(ns hyperopen.wallet.idle-lock
  "Lock a contract account's trading key after a time without input.

   Hyperliquid does not limit what a trading key may do, so an unlocked key
   in a page nobody is watching is the largest exposure this app has. After
   `AGENT_IDLE_LOCK_MINUTES` without a pointer or key event the key leaves
   memory, and the next action asks for the passkey again. Open orders are
   not affected: they live on Hyperliquid.

   Only keys that were registered with a transaction are locked this way
   (`hyperopen.wallet.registration-record`); other wallets keep the
   behaviour they chose."
  (:require [hyperopen.config :as config]
            [hyperopen.platform :as platform]
            [hyperopen.wallet.agent-lockbox :as agent-lockbox]
            [hyperopen.wallet.agent-runtime :as agent-runtime]
            [hyperopen.wallet.agent-session :as agent-session]
            [hyperopen.wallet.registration-record :as registration-record]))

(def check-interval-ms
  30000)

(def ^:private activity-events
  ["pointerdown" "keydown" "wheel" "touchstart"])

(defonce ^:private last-activity-ms
  (atom nil))

(defonce ^:private installed?
  (atom false))

(defn idle-limit-ms
  "The idle time after which the key is locked, or nil when locking is off."
  []
  (let [minutes config/AGENT_IDLE_LOCK_MINUTES]
    (when (and (number? minutes) (pos? minutes))
      (* minutes 60 1000))))

(defn should-lock?
  "True when the key of the connected account must be locked now."
  [{:keys [state now-ms last-activity-ms limit-ms keeps-key?]}]
  (let [wallet (:wallet state)]
    (boolean
     (and (number? limit-ms)
          (number? last-activity-ms)
          (:connected? wallet)
          (seq (:address wallet))
          (= :ready (get-in wallet [:agent :status]))
          (>= (- now-ms last-activity-ms) limit-ms)
          (keeps-key? (:address wallet))))))

(defn- lock!
  [store]
  (agent-runtime/lock-agent-trading!
   {:store store
    :normalize-storage-mode agent-session/normalize-storage-mode
    :normalize-local-protection-mode agent-session/normalize-local-protection-mode
    :clear-unlocked-session! agent-lockbox/clear-unlocked-session!}))

(defn check!
  "Lock the key when the page has been idle for too long. Returns true when
   it locked."
  ([store]
   (check! store {}))
  ([store {:keys [now-ms-fn limit-ms keeps-key? lock-fn]
           :or {now-ms-fn platform/now-ms
                keeps-key? registration-record/keeps-key?
                lock-fn lock!}}]
   (let [limit-ms* (if (some? limit-ms) limit-ms (idle-limit-ms))]
     (if (should-lock? {:state @store
                        :now-ms (now-ms-fn)
                        :last-activity-ms @last-activity-ms
                        :limit-ms limit-ms*
                        :keeps-key? keeps-key?})
       (do
         (lock-fn store)
         true)
       false))))

(defn note-activity!
  ([]
   (note-activity! (platform/now-ms)))
  ([now-ms]
   (reset! last-activity-ms now-ms)))

(defn install!
  "Start watching for idle time. Safe to call more than once."
  [store]
  (when (and (not @installed?)
             (some? (idle-limit-ms))
             (exists? js/window)
             (fn? (.-addEventListener js/window)))
    (reset! installed? true)
    (note-activity!)
    (let [on-activity (fn [_]
                        ;; The first input after a long absence must not
                        ;; count as activity before the key is locked.
                        (check! store)
                        (note-activity!))]
      (doseq [event-name activity-events]
        (.addEventListener js/window event-name on-activity
                           #js {:capture true :passive true})))
    (when (exists? js/document)
      ;; Timers are throttled in a background tab, so look again as soon as
      ;; the tab is shown.
      (.addEventListener js/document "visibilitychange"
                         (fn [_]
                           (when (= "visible" (.-visibilityState js/document))
                             (check! store)))))
    (platform/set-interval! #(check! store) check-interval-ms)
    true))
