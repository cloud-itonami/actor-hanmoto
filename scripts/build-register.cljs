#!/usr/bin/env nbb
;; Build hanmoto's register from the publisher corpus.
;;
;;   nbb scripts/build-register.cljs [--repo <path>] [--census <file>] [--out-dir <dir>]
;;
;; ## Why this file exists
;;
;; The register in R2 had no producer. It was loaded once, by hand, on
;; 2026-08-09, and `pricing.edn`'s `register-refreshed-on-a-schedule` gate could
;; not be met because there was nothing to put on a schedule. A directory sold
;; as a product needs a way to be made again.
;;
;; ## Two dates
;;
;; `as-of` is the corpus commit's own date -- when the DATA last changed. It is
;; NOT the time this script ran. Stamping the run time would make re-deriving an
;; unchanged snapshot look like a refresh, which is how a stale directory passes
;; as current.
;;
;; `checked-at` is the run time. Together they say the thing one field cannot:
;; twenty-two days old, re-derived an hour ago, unchanged.
;;
;; Measured 2026-08-31: re-deriving from the corpus tip reproduced the
;; 2026-08-09 category counts exactly -- 27,307 hosts, 1,628 unclassified.
(ns build-register
  (:require ["child_process" :as cp]
            ["fs" :as fs]
            ["path" :as path]
            [clojure.string :as str]))

(defn- opt [flag default]
  (let [a (vec (drop 2 (js->clj (.-argv js/process))))
        i (.indexOf a flag)]
    (if (neg? i) default (nth a (inc i) default))))

(def repo-root (opt "--repo" "../kotoba-lang/global-accounts-datoms"))
(def out-dir (opt "--out-dir" "/tmp/hanmoto-register"))
(def source "kotoba-lang/global-accounts-datoms")

(def corpus-files
  "The files the census actually reads. `as-of` is scoped to THESE, not to the
  repository, and the difference is not academic.

  Measured 2026-08-31: the corpus repo's tip was 2026-08-30 while these two
  files last changed on 2026-08-09. The commits in between migrated query code
  from DataScript to kotoba-lang/datalog and touched no data. Dating the
  register by the tip would have claimed twenty-one days of freshness that
  nobody measured -- the same lie as stamping the run time, arriving by a
  route that looks like provenance.

  Keep this list in step with `service-files` in the root workspace's
  scripts/publisher-census-datalake-export.cljs. If it drifts, this script
  dates the register by files the census did not read."
  ["data/corpus/nodeinfo/services.edn"
   "data/corpus/directory.plc/services.edn"])

(defn- refuse! [msg]
  (.write (.-stderr js/process) (str "REFUSING: " msg "\n"))
  (.exit js/process 2))

(defn- corpus-commit-date
  "The corpus commit's own date, as the data's provenance.

  Refuses rather than falling back to the wall clock: a register whose as-of is
  the moment it was built claims a freshness nobody measured."
  [dir]
  (let [missing (remove #(fs/existsSync (path/join dir %)) corpus-files)]
    (when (seq missing)
      (refuse! (str "corpus files absent: " (str/join ", " missing)
                    " -- dating a register by files that are not there would "
                    "report the age of nothing")))
    (let [r (try (.toString (cp/execSync (str "git -C " dir " log -1 --format=%cI -- "
                                             (str/join " " corpus-files))))
                 (catch :default _ nil))
          d (some-> r str/trim)]
      (when (and d (re-matches #"\d{4}-\d{2}-\d{2}T.*" d)) d))))

(defn -main []
  (when-not (fs/existsSync repo-root)
    (refuse! (str "corpus not checked out: " repo-root)))
  (let [as-of (corpus-commit-date repo-root)]
    (when-not as-of
      (refuse! (str "no commit date for " repo-root
                    " -- refusing to date the register by the clock")))
    (let [census (opt "--census" "/tmp/publisher-census/publisher_service.json")]
      (when-not (fs/existsSync census)
        (refuse! (str "census rows not found: " census
                      " -- run scripts/publisher-census-datalake-export.cljs first")))
      (let [rows (js->clj (js/JSON.parse (fs/readFileSync census "utf8")))
            by-cat (frequencies (map #(get % "category") rows))
            by-src (frequencies (map #(get % "source") rows))
            unknown (get by-cat "unknown" 0)
            n (count rows)]
        (when (zero? n) (refuse! "census has no rows"))
        (fs/mkdirSync out-dir #js {:recursive true})
        (let [summary {"as-of" as-of
                       "checked-at" (.toISOString (js/Date.))
                       "source" source
                       "hosts" n
                       "by_category" by-cat
                       "by_source" by-src
                       "unknown" unknown
                       "unknown_ratio" (/ (js/Math.round (* 1e6 (/ unknown n))) 1e6)}]
          (fs/writeFileSync (path/join out-dir "summary.json")
                            (js/JSON.stringify (clj->js summary)))
          (fs/writeFileSync (path/join out-dir "register.json")
                            (js/JSON.stringify (clj->js {"as-of" as-of "source" source
                                                         "rows" rows})))
          (println "AS-OF     " as-of "(corpus commit, not the clock)")
          (println "CHECKED-AT" (get summary "checked-at"))
          (println "HOSTS     " n)
          (println "UNKNOWN   " unknown (str "(" (get summary "unknown_ratio") ")"))
          (println "OUT       " out-dir))))))

(-main)
