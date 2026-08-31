(ns hanmoto.principal
  "Who is calling, decided from two sources that are deliberately different.

  `hanmoto.serve` takes the principal beside the request so a caller cannot name
  the scope it is billed under. This namespace is the policy that produces it,
  and it is pure: the edge does the crypto and hands the results here.

  ## Two facts, two origins

  | field | comes from | why not the other |
  |---|---|---|
  | `:caller` | the **payer** of the x402 authorization | a did:pkh the caller proved by paying, not one it asserted |
  | `:scope` | a **capability token** (biscuit) | the billing key. Anything the caller can choose, the caller will choose to be someone else |

  Neither is read from the request. That is `hanmoto.serve`'s invariant; this is
  where the alternative comes from.

  ## A rejected token is not an anonymous call

  This is the whole reason the namespace exists rather than being three lines in
  the edge.

  If a token that fails to verify fell back to anonymous, then **a forged token
  and no token would produce the same response** -- and the forged one would be
  billed as load with nobody the wiser. So `of` distinguishes three outcomes and
  the caller must handle them separately:

    {:principal {...}}          authorised
    {:principal nil}            no token was presented -- anonymous, billable as load
    {:refuse reason}            a token was presented and did not authorise

  CLAUDE.md names this class repo-wide mandatory: the check that could not
  measure returning what the check that measured and found nothing returns.
  Here it would be the check that REFUSED returning what no-check returns."
  (:require [clojure.string :as str]))

(def anonymous
  "No token was presented. Load is real, the customer is not."
  {:caller nil :scope "unscoped"})

(defn- payer-did
  "The payer as a principal identifier, or nil. Accepts an EVM address and
  renders the CAIP-10-backed DID form; passes a did:pkh through unchanged.

  **Does not accept anything else.** A free-form string here would be the
  request naming the caller by another route."
  [payer chain-id]
  (let [p (some-> payer str str/trim)]
    (cond
      (str/blank? (str p)) nil
      (str/starts-with? p "did:pkh:") p
      (re-matches #"^0x[0-9a-fA-F]{40}$" p)
      (str "did:pkh:eip155:" (or chain-id 8453) ":" (str/lower-case p))
      :else nil)))

(defn of
  "-> `{:principal {...}}` | `{:principal nil}` | `{:refuse reason}`.

  `auth` is what the edge got back from `biscuit.authorizer/authorize`, or nil
  when no token was presented. `scope` must come from the token's own facts --
  an edge that passes a request-supplied scope here has moved the defect one
  namespace along, so `of` refuses a scope with no `:allowed?` behind it."
  [{:keys [auth payer chain-id]}]
  (let [caller (payer-did payer chain-id)]
    (cond
      (nil? auth)
      {:principal (assoc anonymous :caller caller)}

      (not (:allowed? auth))
      {:refuse (or (:reason auth) :biscuit/not-authorized)}

      (str/blank? (str (:scope auth)))
      ;; Authorised, but the token says nothing about which scope. Billing it to
      ;; `unscoped` would silently pool distinct customers into one bucket, and
      ;; guessing a scope is worse. Refuse and let the issuer fix the token.
      {:refuse :biscuit/no-scope-in-token}

      :else
      {:principal {:caller caller :scope (str (:scope auth))}})))

(defn refused? [r] (contains? r :refuse))
