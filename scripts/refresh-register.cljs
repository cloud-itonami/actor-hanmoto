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

(defn- corpus-distance
  "How far the pinned corpus is behind upstream. Measured, never moved.

  The corpus is a west project, so its checkout is a detached HEAD at the
  manifest pin -- measured 2026-08-31, the first run of this job tried
  `git merge --ff-only @{u}` and got `HEAD does not point to a branch`.

  Making that work would be worse than leaving it. Advancing a pin is a
  governed action with its own verification (default-branch reachability,
  forward-only), and a register refresher is not the place to do it quietly.
  So this reports the distance and re-derives from whatever the pin says: a
  register built from a stale pin is not wrong, it is a register of the corpus
  this workspace has agreed to. Recording the distance is what keeps that
  distinguishable from a corpus nobody has looked at."
  []
  (sh (str "git -C " corpus " fetch --quiet kotoba-lang 2>/dev/null || "
           "git -C " corpus " fetch --quiet origin 2>/dev/null"))
  (let [short (fn [x] (when-not (str/blank? x) (subs x 0 (min 12 (count x)))))
        head (str/trim (str (:out (sh (str "git -C " corpus " rev-parse HEAD")))))
        up (str/trim (str (:out (sh (str "git -C " corpus
                                         " rev-parse kotoba-lang/main 2>/dev/null || "
                                         "git -C " corpus " rev-parse origin/main")))))
        behind (str/trim (str (:out (sh (str "git -C " corpus " rev-list --count HEAD.."
                                             (if (str/blank? up) "HEAD" up))))))]
    {:corpus-head (short head)
     :corpus-upstream (short up)
     ;; nil, not 0, when it could not be counted. Zero means measured and level.
     :corpus-behind (when (re-matches #"\d+" behind) (js/parseInt behind))}))

(defn -main []
  (when-not (fs/existsSync corpus)
    (refuse! :corpus-absent corpus))
  (let [dist (corpus-distance)
        c (sh (str "cd " root " && nbb --classpath \".:scripts/nbb_compat\" "
                   "scripts/publisher-census-datalake-export.cljs --out-dir " census-dir))]
    (when-not (:ok? c) (refuse! :census-failed (str/trim (str (:out c)))))
    (let [b (sh (str "cd " here " && nbb scripts/build-register.cljs --repo " corpus
                     " --census " census-dir "/publisher_service.json --out-dir " work))]
      (when-not (:ok? b) (refuse! :build-failed (str/trim (str (:out b))))))
    (let [summary (js->clj (js/JSON.parse (fs/readFileSync (path/join work "summary.json") "utf8")))
          ;; `count`, the key `hanmoto.register/of` reads. An earlier version of
          ;; this file asked for "hosts" and refused a full register as empty --
          ;; the same key mismatch as the one build-register.cljs shipped, one
          ;; layer up. The guard was right to refuse; it just named the wrong
          ;; thing.
          n (get summary "count")]
      (when (or (nil? n) (zero? n)) (refuse! :empty-register (pr-str (keys summary))))
      (if dry?
        (do (write-state! (merge dist {:outcome :dry-run :as-of (get summary "as-of") :hosts n}))
            (println "DRY-RUN as-of" (get summary "as-of") "hosts" n
                     "corpus-behind" (:corpus-behind dist)))
        (let [ups (doall (for [f ["summary.json" "register.json"]]
                           [f (sh (str "cd " here "/appview/hanmoto && npx --yes wrangler r2 object put "
                                       "hanmoto-register/" f " --file " work "/" f
                                       " --remote --content-type application/json"))]))
              bad (remove #(:ok? (second %)) ups)]
          (if (seq bad)
            (refuse! :upload-failed (str/join "; " (map first bad)))
            (do (write-state! (merge dist {:outcome :published
                                           :as-of (get summary "as-of")
                                           :checked-at (get summary "checked-at")
                                           :hosts n
                                           :unknown (get summary "unknown")}))
                (println "PUBLISHED as-of" (get summary "as-of")
                         "checked-at" (get summary "checked-at")
                         "hosts" n "corpus-behind" (:corpus-behind dist)))))))))

(-main)
