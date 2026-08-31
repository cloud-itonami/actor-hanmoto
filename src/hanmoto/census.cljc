(ns hanmoto.census
  "The register: which hosts publish on the open web, on what software, at what
  scale.

  Pure. It takes rows and returns answers; it opens no file and makes no
  request. The rows come from `kotoba-lang/global-accounts-datoms` via
  `scripts/publisher-census-datalake-export.cljs` (ADR-2608314300).

  ## Categories are a vocabulary, not a derivation

  `software` does not tell you what kind of publishing a host does, any more
  than a repository can tell you that 端末 and terminal are the same word. So
  the mapping is DECLARED here, and anything outside it is `unknown` --
  **never pushed into the nearest box.** Rounding the tail would make the table
  look finished and the answer false.

  Every categorical answer carries `:unknown` and `:unknown-ratio` beside it,
  because a category count without its unclassified remainder is a number that
  reads as complete and is not.

  ## Provenance is never summed

  `:self-reported` (NodeInfo, the host's own declaration) and `:observed`
  (counted from the account side, carrying no software) wear the same shape and
  come from different places. `by-source` keeps them apart; nothing here adds
  them into one 'number of publishers'."
  (:require [clojure.string :as str]))

(def software->category
  "Declared. 35 of the 344 distinct software values seen in the corpus; the rest
  are `unknown` on purpose."
  {"wordpress" "blog" "ghost" "blog" "writefreely" "blog" "plume" "blog"
   "sutty-distributed-press" "blog" "micro.blog" "blog"
   "mastodon" "social" "gotosocial" "social" "misskey" "social" "pleroma" "social"
   "akkoma" "social" "sharkey" "social" "friendica" "social" "snac" "social"
   "hometown" "social" "iceshrimp" "social" "iceshrimp.net" "social"
   "cherrypick" "social" "mitra" "social" "wafrn" "social" "hollo" "social"
   "bonfire" "social" "hubzilla" "social"
   "lemmy" "forum" "piefed" "forum" "kbin" "forum" "mbin" "forum"
   "peertube" "media" "owncast" "media" "pixelfed" "media" "funkwhale" "media"
   "castopod" "media"
   "gancio" "events" "mobilizon" "events" "bookwyrm" "books"})

(defn category-of
  "`unknown` for anything the vocabulary does not name. Not the nearest box."
  [software]
  (or (get software->category (some-> software str/lower-case)) "unknown"))

(defn- host-name [row] (or (get row "domain") (:domain row)))
(defn- host-software [row] (or (get row "software") (:software row)))
(defn- host-source [row] (or (get row "source") (:source row)))

(defn by-category
  "-> `{:counts {category n} :total n :unknown n :unknown-ratio r}`.

  `:unknown` and `:unknown-ratio` are not optional extras. A categorical answer
  without its unclassified remainder reads as complete when it is not."
  [rows]
  (let [cs (frequencies (map #(category-of (host-software %)) rows))
        total (count rows)
        unknown (get cs "unknown" 0)]
    {:counts cs
     :total total
     :unknown unknown
     :unknown-ratio (if (zero? total) 0 (/ (double unknown) total))}))

(defn by-source
  "Provenance, kept apart. Never summed into one count of publishers."
  [rows]
  (frequencies (map host-source rows)))

(defn by-software
  "The raw distribution, so a caller can see what the vocabulary did not name."
  [rows]
  (frequencies (map host-software rows)))

(defn unclassified-software
  "The software values outside the vocabulary, by descending count -- the list a
  vocabulary change would be argued from."
  [rows]
  (->> rows
       (map host-software)
       (remove #(contains? software->category (some-> % str/lower-case)))
       frequencies
       (sort-by (comp - val))
       vec))

(defn lookup
  "One host by domain, or nil. Nil means not in this register -- which is not the
  same as not existing, and callers must not report it as absence."
  [rows domain]
  (first (filter #(= domain (host-name %)) rows)))

(defn hosts-in
  "Hosts in a category. `unknown` is a category here too: it is askable, because
  a caller that wants to improve the vocabulary needs to see them."
  [rows category]
  (filterv #(= category (category-of (host-software %))) rows))
