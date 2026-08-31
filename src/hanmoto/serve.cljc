(ns hanmoto.serve
  "The register as answers, and the usage those answers owe.

  Pure. `handle` takes a request, the register rows and a clock, and returns
  **both** the response and the usage records it incurred. It writes nothing;
  the edge appends the records and sends the body.

  ## Why usage comes back with the response

  If the meter were called separately, a path could answer and forget to record
  -- and the two would drift in the direction that costs money to notice. Coming
  back together means **an answer that was served and not counted is not
  expressible here**.

  ## The caller and the scope are NOT taken from the request

  `scope` is the billing key: `usage/count-key` and `usage/mac-key` both fold on
  it. If it arrived in the request body, a caller could write usage into someone
  else's scope, or rotate scopes to keep their own monthly-active count at one
  forever. `caller` has the same problem in the other direction -- any string
  would become a customer.

  This is the shape ADR-2608150100 found on `/infer/transfer`: the only check
  was a balance, and a balance is an answer about solvency, not about who is
  asking.

  So `handle` takes a `principal` **beside** the request, never inside it, and
  the request's own `:caller` / `:scope` are ignored if present. A host that has
  not authenticated anyone passes `nil`, and then the call is anonymous: it
  counts as load and cannot count as a customer.

  ⚠ **Nothing here verifies the principal.** This namespace makes the trust
  boundary explicit and unforgeable-by-the-caller; establishing it is the host's
  job. The intended source is the payer of the x402 `exact` authorization
  (a `did:pkh`, ADR-2608313700) for `:caller`, and a capability token for
  `:scope` -- biscuit is this workspace's delegation centre (ADR-2608180200) and
  verifies with no secret at the edge, which is what an edge needs.

  ## A priced path the meter cannot count is refused

  `hanmoto.offer` gives each resource a `:dimension`. If that dimension is not
  one `hanmoto.usage` counts, `handle` returns 500 rather than serving --
  charging for something with no record behind it is worse than an outage,
  because nothing goes red."
  (:require [clojure.string :as str]
            [hanmoto.census :as census]
            [hanmoto.offer :as offer]
            [hanmoto.register :as register]
            [hanmoto.usage :as usage]))

(defn- json-ok [body records] {:status 200 :body body :usage records})

(defn- with-provenance
  "Every answer says how old the register is. **Not optional** -- an answer that
  omits its as-of is indistinguishable from one built from a live register, and
  that is the way a directory gets sold as current."
  [body reg now]
  (assoc body :register (register/provenance reg now)))
(defn- err [status body] {:status status :body body :usage []})

(defn- records-for
  "The usage a single answered call owes: one counter for the resource's
  dimension, and one monthly-active-caller key when a caller is known.

  A call with no caller still counts as `:query` -- load happened. It just
  cannot count toward `:mac`, and inventing a subject there would turn
  anonymous traffic into customers."
  [digest {:keys [dimension caller scope instant]}]
  (into []
        (remove nil?)
        [(when-let [k (usage/count-key {:dimension dimension :scope-of scope :instant instant})]
           {:key k :n 1})
         (when-let [k (usage/mac-key digest {:caller caller :scope-of scope :instant instant})]
           {:key k})]))

(defn handle
  "-> `{:status n :body v :usage [records]}`.

  `req` is `{:method :path :instant}` -- **no identity fields**. `principal` is
  `{:caller :scope}` and comes from the host, or nil for an anonymous call.
  `digest` is injected."
  [{:keys [register digest] :as _ctx}
   {:keys [method path instant] :as _req}
   {:keys [caller scope] :as _principal}]
  (let [res (offer/resource-for method path)
        rows (:rows register)]
    (cond
      (nil? res)
      (err 404 {:error "not-for-sale"
                :note "This path is not in hanmoto.offer/priced-resources. A 402 here
                       would charge for something never promised."
                :sells (mapv :path offer/priced-resources)})

      (not (contains? usage/dimensions (:dimension res)))
      (err 500 {:error "unmetered-resource"
                :note "This path is priced but its dimension is not one the meter
                       counts. Refusing to serve: revenue with no record behind it
                       is worse than an outage, because nothing goes red."
                :dimension (:dimension res)})

      :else
      (let [recs (records-for digest {:dimension (:dimension res) :caller caller
                                      :scope scope :instant instant})]
        (cond
          (= (:path-prefix res) offer/counts-prefix)
          (json-ok (with-provenance (census/by-category rows) register instant) recs)

          (= (:path-prefix res) offer/tail-prefix)
          (json-ok (with-provenance
                     {:unclassified (census/unclassified-software rows)
                      :note "Outside the declared vocabulary. Not the nearest box."}
                     register instant)
                   recs)

          (= (:path-prefix res) offer/host-prefix)
          (let [domain (subs path (count offer/host-prefix))
                hit (census/lookup rows domain)]
            ;; **A miss still bills.** The register was searched; the caller
            ;; consumed the answer "not in this register", which is an answer.
            ;; Not billing it would also make absence cheaper than presence,
            ;; which is a scraping incentive.
            (json-ok (with-provenance
                       (or hit {:domain domain :found false
                                :note "Not in this register. That is not the same as
                                       this host not existing."})
                       register instant)
                     recs))

          :else
          (err 500 {:error "declared-but-unrouted"
                    :note "hanmoto.offer sells this prefix and handle has no arm for
                           it. The declaration and the router are out of step."
                    :prefix (:path-prefix res)}))))))
