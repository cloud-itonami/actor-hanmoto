(ns hanmoto.register
  "The register and how old it is, carried together.

  ## Why provenance is not metadata here

  A directory that does not say its age is the one that gets sold as current.
  `pricing.edn`'s `:register-refreshed-on-a-schedule` gate says the same thing
  from the other side: selling a directory implies keeping it current.

  The failure this closes is not that the snapshot is old -- it is that an
  answer built from a snapshot **looked exactly like an answer built from a live
  register**. So `load` refuses rows without provenance, and every answer
  carries the as-of.

  This is the same discipline `hanmoto.serve` applies to usage: the thing that
  could be forgotten is returned with the thing that cannot be."
  (:require [clojure.string :as str]))

(defn- iso? [s] (boolean (and (string? s) (re-matches #"\d{4}-\d{2}-\d{2}.*" s))))

(defn of
  "-> `{:rows [...] :summary {...} :as-of \"…\" :source \"…\" :count n}` or throws.

  **Refuses rows with no provenance.** A register whose age nobody recorded
  cannot report its age, and an answer that omits the as-of is indistinguishable
  from one that is current.

  Either `:rows` or `:summary` may be absent. The full register is 27,307 rows
  and a Worker that parsed it on every cold start to answer a category count
  would be paying ten megabytes for five kilobytes of answer -- so aggregates
  ship precomputed. **A path that needs rows and has none is refused, never
  answered from the summary**: an aggregate is not a substitute for a lookup,
  and a `found false` produced by an absent register would be a lie."
  [{:keys [rows summary as-of source]}]
  (when-not (or (sequential? rows) (map? summary))
    (throw (ex-info "hanmoto.register: needs rows or a summary" {})))
  (when (and (some? rows) (not (sequential? rows)))
    (throw (ex-info "hanmoto.register: rows must be a sequence" {})))
  (when-not (iso? as-of)
    (throw (ex-info "hanmoto.register: refusing rows with no as-of -- an answer
                     built from an undated snapshot is indistinguishable from a
                     current one" {:as-of as-of})))
  (when (str/blank? (str source))
    (throw (ex-info "hanmoto.register: refusing rows with no source" {})))
  (cond-> {:as-of as-of :source (str source)
           :count (or (:count summary) (count rows))}
    rows (assoc :rows (vec rows))
    summary (assoc :summary summary)))

(defn has-rows? [reg] (sequential? (:rows reg)))

(defn- parse-int
  "Digits -> int, or nil. The one place a platform difference is allowed in this
  namespace, and it is confined to parsing rather than to arithmetic."
  [s]
  (when (re-matches #"\d+" (str s))
    #?(:clj (Integer/parseInt (str s)) :cljs (js/parseInt (str s) 10))))

(defn- civil-days
  "Days since 1970-01-01 from an ISO date prefix. Howard Hinnant's days_from_civil,
  written out rather than delegated to a platform Date -- **the same instant must
  produce the same age on the JVM and in a Worker**, and a date library that
  exists on one host and not the other is how an answer differs by where it ran."
  [s]
  (when (and (string? s) (>= (count s) 10))
    (let [y (parse-int (subs s 0 4))
          m (parse-int (subs s 5 7))
          d (parse-int (subs s 8 10))]
      (when (and y m d (pos? m) (<= m 12) (pos? d))
        (let [y (if (<= m 2) (dec y) y)
              era (quot (if (>= y 0) y (- y 399)) 400)
              yoe (- y (* era 400))
              doy (+ (quot (+ (* 153 (+ m (if (> m 2) -3 9))) 2) 5) (dec d))
              doe (+ (* yoe 365) (quot yoe 4) (- (quot yoe 100)) doy)]
          (+ (* era 146097) doe -719468))))))

(defn age-days
  "How old the register is at `now`, in whole days. **nil when it cannot be
  computed -- never 0**, because 0 reads as fresh and an unmeasurable age is not
  freshness."
  [{:keys [as-of]} now]
  (let [a (civil-days as-of) n (civil-days now)]
    (when (and a n) (- n a))))

(defn provenance
  "The block every answer carries. `:stale?` is left to the caller's policy --
  this namespace reports age, it does not rule on freshness."
  [reg now]
  (cond-> {:as-of (:as-of reg) :source (:source reg) :hosts (:count reg)}
    (age-days reg now) (assoc :age-days (age-days reg now))))
