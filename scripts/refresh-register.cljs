#!/usr/bin/env nbb
;; Re-derive hanmoto's register and publish it, on a schedule.
;;
;;   nbb scripts/refresh-register.cljs [--dry-run]
;;
;; ## What this is for
;;
;; `pricing.edn`'s `register-refreshed-on-a-schedule` gate: selling a directory
;; implies keeping it current. Before this existed the register had no producer
;; at all -- it was loaded by hand once and could only age.
;;
;; ## It records that it ran, not only what it changed
;;
;; State goes to `~/.gftd/hanmoto-register.edn` on every run, including runs
;; that change nothing. A job that writes only on change is indistinguishable
;; from a job that never fired, and this workspace has a roster of 4,247 bots
;; whose state file has never appeared to prove the point.
;;
;; ## It refuses rather than publishing something it cannot vouch for
;;
;; Exit 2 -- neither 0 nor 1 -- for every case where it could not measure:
;; corpus absent, census failed, counts empty. A refusal must not share an exit
;; code with a clean run.
(ns refresh-register
  (:require ["child_process" :as cp]
            ["fs" :as fs]
            ["os" :as os]
            ["path" :as path]
            [clojure.string :as str]))

(defn- env [k d] (or (aget (.-env js/process) k) d))

(def root (env "COM_JUNKAWASAKI_ROOT" "/Users/junkawasaki/github/com-junkawasaki"))
(def corpus (path/join root "orgs/kotoba-lang/global-accounts-datoms"))
(def here (path/join root "orgs/cloud-itonami/actor-hanmoto"))
(def work "/tmp/hanmoto-register")
(def census-dir "/tmp/publisher-census")
(def state-file (path/join (os/homedir) ".gftd/hanmoto-register.edn"))
(def dry? (some #{"--dry-run"} (vec (drop 2 (js->clj (.-argv js/process))))))

(defn- write-state! [m]
  (fs/mkdirSync (path/dirname state-file) #js {:recursive true})
  (fs/writeFileSync state-file (pr-str (assoc m :at (.toISOString (js/Date.))))))

(defn- refuse! [reason detail]
  (write-state! {:outcome :refused :reason reason :detail detail})
  (.write (.-stderr js/process) (str "REFUSING: " reason " -- " detail "\n"))
  (.exit js/process 2))

(defn- sh [cmd]
  (try {:ok? true :out (.toString (cp/execSync cmd #js {:stdio "pipe"}))}
       (catch :default e {:ok? false :out (str (.-message e))})))

(defn -main []
  (when-not (fs/existsSync corpus)
    (refuse! :corpus-absent corpus))
  ;; Pull the corpus. A refresh that never looks upstream re-publishes the same
  ;; snapshot forever and reports success for it.
  (let [f (sh (str "git -C " corpus " fetch --quiet kotoba-lang 2>/dev/null || "
                   "git -C " corpus " fetch --quiet origin"))
        m (sh (str "git -C " corpus " merge --ff-only @{u}"))]
    (when-not (:ok? m)
      ;; Not fatal: a dirty or diverged corpus checkout still has data, and
      ;; refusing here would stop the refresh over somebody else's WIP. Recorded
      ;; so it cannot be mistaken for a clean pull.
      (write-state! {:outcome :pending :note "corpus not fast-forwarded"
                     :detail (str/trim (str (:out m)))}))
    (when-not (:ok? f) nil))
  (let [c (sh (str "cd " root " && nbb --classpath \".:scripts/nbb_compat\" "
                   "scripts/publisher-census-datalake-export.cljs --out-dir " census-dir))]
    (when-not (:ok? c) (refuse! :census-failed (str/trim (str (:out c))))))
  (let [b (sh (str "cd " here " && nbb scripts/build-register.cljs --repo " corpus
                   " --census " census-dir "/publisher_service.json --out-dir " work))]
    (when-not (:ok? b) (refuse! :build-failed (str/trim (str (:out b))))))
  (let [summary (js->clj (js/JSON.parse (fs/readFileSync (path/join work "summary.json") "utf8")))
        hosts (get summary "hosts")]
    (when (or (nil? hosts) (zero? hosts)) (refuse! :empty-register (pr-str summary)))
    (if dry?
      (do (write-state! {:outcome :dry-run :as-of (get summary "as-of") :hosts hosts})
          (println "DRY-RUN as-of" (get summary "as-of") "hosts" hosts))
      (let [ups (for [f ["summary.json" "register.json"]]
                  [f (sh (str "cd " here "/appview/hanmoto && npx --yes wrangler r2 object put "
                              "hanmoto-register/" f " --file " work "/" f
                              " --remote --content-type application/json"))])
            bad (remove #(:ok? (second %)) ups)]
        (if (seq bad)
          (refuse! :upload-failed (str/join "; " (map first bad)))
          (do (write-state! {:outcome :published
                             :as-of (get summary "as-of")
                             :checked-at (get summary "checked-at")
                             :hosts hosts
                             :unknown (get summary "unknown")})
              (println "PUBLISHED as-of" (get summary "as-of")
                       "checked-at" (get summary "checked-at")
                       "hosts" hosts)))))))

(-main)
