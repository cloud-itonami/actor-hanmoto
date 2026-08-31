(ns hanmoto.usage
  "What this actor owes an invoice: how many register queries were answered, and
  how many distinct callers asked, per calendar month.

  The shape is `authn.usage`'s (ADR-2608110200) rather than a new one, and for
  the reason that namespace gives:

  > **A price can be decided later, and a month that was not measured can never
  > be measured afterwards.**

  Every day without this is a day of history that cannot be billed, audited, or
  even honestly described to a customer.

  ## Why a distinct caller, not a request

  A request count answers 'how busy' and not 'how many customers'. Exactness
  there means de-duplication, and **de-duplication is the part that cannot be
  reconstructed from a log afterwards** -- by the time you notice, the
  distinctness is gone. So it happens at write time, in the key.

  ## Why the caller is hashed

  A usage export is the thing that leaves this actor -- into an invoice, a
  spreadsheet, a dispute -- and a per-month list of caller identities is a
  membership list. The register itself holds no accounts (see README); it would
  be incoherent to let the meter become the thing the register refuses to be.

  The digest is salted with scope and month, so one caller is one key within a
  month and an unlinkable key across months or customers. The count stays exact.

  ## No crypto, no I/O

  `digest` is injected. This namespace is pure: it builds keys and folds
  records, and a host supplies the hash and the store."
  (:require [clojure.string :as str]))

(def dimensions
  "The only things this actor may price. `pricing.edn` may name no other -- an
  invoice quoting a dimension nobody counts is indefensible."
  #{:query :mac})

(defn month-of
  "`2026-08-31T04:05:06Z` -> `2026-08`. The billing period, from an instant, by
  string prefix so no date library is needed and no timezone is invented."
  [instant]
  (when (and (string? instant) (>= (count instant) 7))
    (subs instant 0 7)))

(defn scope
  "The customer a record belongs to. Absent scope is `:unscoped`, never nil --
  a nil scope silently merges every customer's usage into one bucket."
  [s]
  (if (str/blank? (str s)) "unscoped" (str s)))

(defn salt
  "Per-scope, per-month. Two callers are the same key within a month and
  unlinkable across months or customers."
  [scope- month]
  (str "hanmoto/v1/" (scope scope-) "/" month))

(defn mac-key
  "Monthly-active-caller key. `digest` is `(fn [s] -> hex)`, injected.

  The caller is a `did:pkh:eip155:…` derived from the key that paid
  (ADR-2608313700). **It is never stored in the clear** -- what is stored is
  `digest(salt + caller)`, and the salt carries the scope and the month, so the
  same caller is one key within a month and unlinkable outside it.

  nil when the instant has no month or the caller is blank: a key built from a
  missing subject would count an absence as a customer."
  [digest {:keys [caller scope-of instant]}]
  (let [m (month-of instant)]
    (when (and m (not (str/blank? (str caller))))
      (str "mac/" (scope scope-of) "/" m "/"
           (digest (str (salt scope-of m) "/" caller))))))

(defn count-key
  "Counter key for a plain dimension (`:query`). No de-duplication: a query is
  an event, not a subject.

  nil for a dimension outside `dimensions` -- the meter refuses to count
  something the price book would then be free to quote."
  [{:keys [dimension scope-of instant]}]
  (when-let [m (month-of instant)]
    (when (contains? dimensions dimension)
      (str "count/" (name dimension) "/" (scope scope-of) "/" m))))

(defn parse-mac-key
  "`mac/<scope>/<month>/<hash>` -> `{:scope :month :hash}`, or nil."
  [k]
  (let [ps (str/split (str k) #"/")]
    (when (and (= 4 (count ps)) (= "mac" (first ps)))
      {:scope (nth ps 1) :month (nth ps 2) :hash (nth ps 3)})))

(defn report
  "Records -> `{[scope month] {:query n :mac n}}`.

  Records are `{:key k}` for de-duplicated subjects and `{:key k :n n}` for
  counters. **Keys this namespace does not recognise are counted as
  `:unreadable` rather than dropped** -- a fold that silently ignores what it
  cannot parse reports the same total as a fold that had nothing to ignore."
  [records]
  (reduce
   (fn [acc {:keys [key n]}]
     (if-let [{:keys [scope month]} (parse-mac-key key)]
       (update-in acc [[scope month] :mac] (fnil inc 0))
       (let [ps (str/split (str key) #"/")]
         (if (and (= 4 (count ps)) (= "count" (first ps)))
           (update-in acc [[(nth ps 2) (nth ps 3)] (keyword (nth ps 1))]
                      (fnil + 0) (or n 1))
           (update acc :unreadable (fnil inc 0))))))
   {}
   records))
