(ns hanmoto.offer
  "What hanmoto sells over x402, declared ONCE.

  The shape is `kotobase.x402-offer`'s, and so is the reason it exists: a
  declaration and its consumer reading different tables is a defect this
  workspace has shipped to production before -- x402.nexus advertised a path
  murakumo had stopped serving, so **a buyer could pay and receive nothing**.

  So the prices, the path prefixes and the discovery document all come from
  `priced-resources` below. There is no second list.

  ## Reads only

  Every resource here is a read of the register. The register is content that
  hanmoto assembled; paying for a read does not authorise mutating anything,
  and nothing here writes."
  (:require [clojure.string :as str]))

(def seller "hanmoto")
(def default-facilitator "https://x402.nexus")

(def counts-prefix "/x402/counts")
(def host-prefix "/x402/host/")
(def tail-prefix "/x402/unclassified")

(def priced-resources
  "Everything hanmoto sells. Prices are USD strings, as x402 requires.

  `:dimension` names which meter dimension a call bills. **A resource whose
  dimension is not in `hanmoto.usage/dimensions` cannot be served** -- that is
  checked in `hanmoto.serve`, not left as a convention, because a priced path
  the meter does not count is revenue with no record behind it."
  [{:path "/x402/counts" :path-prefix counts-prefix :method "GET" :usd "0.001"
    :dimension :query
    :description "publisher counts by category, with the unclassified remainder"}
   {:path "/x402/host/{domain}" :path-prefix host-prefix :method "GET" :usd "0.001"
    :dimension :query
    :description "one publishing host: software, protocol, provenance, self-reported scale"}
   {:path "/x402/unclassified" :path-prefix tail-prefix :method "GET" :usd "0.002"
    :dimension :query
    :description "software values outside the category vocabulary, by descending count"}])

(def terms-url
  "Where the full terms live. The repository is public and the file changes by
  commit, so `git log -p TERMS.md` is their history."
  "https://github.com/cloud-itonami/actor-hanmoto/blob/main/TERMS.md")

(def upstream-licensing
  "What the two upstream directories actually license, read from
  `kotoba-lang/global-accounts-datoms` rather than assumed.

  This is DATA and it is the single copy. TERMS.md says the same thing in
  prose and `/terms` serves this map; prose and a served summary drift, a
  prose sentence and the value it describes drift, but there is only one place
  here where the licence kind is written down.

  Neither entry grants redistribution of the aggregate, which is why the terms
  sell answers rather than the register."
  [{:source "NodeInfo 2.x server self-descriptions"
    :rows 26406
    :license-kind :per-server
    :bulk-export? false
    :note "Each host publishes its own description under its own terms."}
   {:source "plc.directory"
    :rows 901
    :license-kind :none-published
    :bulk-export? true
    :note "Published for replication (relays depend on it); no dataset licence attached."}])

(def networks
  "Where each resource is sold. The same answer is offered on both, priced the
  same, and a buyer picks by its own network allowlist -- which is how a buyer
  under a testnet grant takes the testnet listing and never the other.

  base-sepolia is here so a paid path can be demonstrated without anyone
  moving value. `nexus.apply/settleable-networks` admits it and marks it a
  testnet, so nothing downstream infers that from the name."
  ["base" "base-sepolia"])

(def sold-path-prefixes (mapv :path-prefix priced-resources))

(defn resource-for
  "The declared resource covering `path`, or nil. **nil means not for sale**, and
  a caller that issues a 402 anyway is charging for something it has not
  promised to deliver."
  [method path]
  (when (and (string? method) (string? path))
    (first (filter #(and (= method (:method %))
                         (str/starts-with? path (:path-prefix %)))
                   priced-resources))))

(defn sells? [method path] (some? (resource-for method path)))

(defn usd-for
  "Declared price for a prefix. Throws on an unknown one rather than defaulting:
  a silent default is how a price becomes a second copy."
  [path-prefix]
  (or (some #(when (= path-prefix (:path-prefix %)) (:usd %)) priced-resources)
      (throw (ex-info "hanmoto.offer: no priced resource for prefix"
                      {:prefix path-prefix :known sold-path-prefixes}))))

(defn- valid-evm-address? [a]
  (boolean (and (string? a) (re-matches #"^0x[0-9a-fA-F]{40}$" (str/trim a)))))

(defn offer-status
  "Why the offer looks the way it does. An absent payTo takes the same
  `resources []` branch as deliberately selling nothing, which is a legal x402
  document -- so **unset must not be indistinguishable from empty-on-purpose**."
  [treasury-addr]
  (let [a (some-> treasury-addr str str/trim not-empty)]
    (cond
      (nil? a) {:selling? false :reason :unconfigured
                :would-sell (mapv :path priced-resources)}
      (not (valid-evm-address? a)) {:selling? false :reason :malformed-address
                                    :would-sell (mapv :path priced-resources)}
      (empty? priced-resources) {:selling? false :reason :no-priced-resources}
      :else {:selling? true :reason :ok :pay-to a
             :selling (mapv :path priced-resources)})))

(defn document
  "The `/.well-known/x402` body. Spec-shaped in every case -- an empty
  `resources` is legal and clients must not break on it, so the diagnosis lives
  in `offer-status` rather than leaking operational state to buyers."
  [treasury-addr schemes]
  (let [{:keys [selling? pay-to]} (offer-status treasury-addr)]
    {:x402Version 1
     :seller seller
     :schemes (vec schemes)
     ;; One entry per resource PER NETWORK. The registry keys a rule by
     ;; (seller, method, path, chain), so these are distinct listings rather
     ;; than one overwriting the other.
     :resources (if selling?
                  (vec (for [r priced-resources n networks]
                         {:path (:path r)
                          :method (:method r)
                          :description (:description r)
                          :price {:usd (:usd r) :asset "USDC" :network n
                                  :payTo pay-to}}))
                  [])}))
