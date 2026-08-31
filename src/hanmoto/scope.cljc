(ns hanmoto.scope
  "The billing scope, taken from a token rather than from the caller.

  `hanmoto.principal` already refuses a scope with no `:allowed?` behind it.
  This is where the `:allowed?` comes from: a Biscuit, verified against a root
  public key this actor holds and the caller does not.

  ## Why not `biscuit.authorizer/authorize`

  Because the token arrives on the wire. `biscuit.token/verify` recomputes a
  block payload from the *model* -- the shape a hand-written token has -- while
  a wire token's signature is over protobuf bytes. Handing one to the other
  verifies a signature over the wrong bytes and fails in a way that looks
  exactly like a forgery. `biscuit.wire/verify` is the wire path, and
  `biscuit.wire/token->model` is the bridge to the grant model afterwards.
  `biscuit.wire`'s own docstring says conversion is not verification; the order
  here is decode, verify, and only then read.

  ## A token's checks are run, and refused when they cannot be

  This used to refuse every token carrying a check, because `biscuit.wire`
  counted them and dropped them. It now decodes the ones it can evaluate --
  predicates only -- and `biscuit.authorizer/run-checks` runs them against the
  facts this actor states about the call.

  The refusal did not go away, it got narrower. A check carrying an expression,
  a `kind` other than One, or a disjunction is still refused
  (`:biscuit/checks-not-evaluated`), because honouring one would mean ignoring
  the limits the token places on itself -- a `check if time($t), $t < …` would
  become unlimited, and the answer would be indistinguishable from one where
  the check passed.

  A check that ran and did NOT pass is a different answer again
  (`:biscuit/check-failed`): the token asked, and this actor could not satisfy
  it. Collapsing that into the cannot-evaluate answer would tell an issuer to
  fix the wrong thing.

  ## One scope, or none

  `biscuit.authority/->grant` folds `scope` facts the way the spec means them:
  alternatives within a block, narrowing across blocks. A grant may therefore
  legitimately reach several scopes -- and a *billing* key cannot. Two scopes
  is not a licence to pick one; it is a token that has not said who to charge.
  Refused, like zero, with a different reason so the issuer can tell which
  mistake they made."
  (:require [authority.scope :as scope]
            [biscuit.authority :as authority]
            [biscuit.authorizer :as az]
            [biscuit.wire :as wire]
            [clojure.string :as str]))

(defn- blank? [s] (or (nil? s) (and (string? s) (str/blank? s))))

(defn- deny
  "Every refusal carries a namespaced reason.

  `biscuit.wire` answers with bare keywords such as `:signature-mismatch`.
  They reach the caller through `hanmoto.principal` and into a 401 body, where
  a bare keyword sits beside this actor's own `:biscuit/…` ones and reads as if
  it came from somewhere else. Qualifying here keeps one vocabulary."
  [reason]
  {:allowed? false
   :reason (if (qualified-keyword? reason)
             reason
             (keyword "biscuit" (name reason)))})

(defn of
  "-> the `:auth` map `hanmoto.principal/of` consumes:
  `{:allowed? true :scope \"…\"}` or `{:allowed? false :reason kw}`.

  `token-bytes` is the decoded bearer token, `root-public-key` this actor's
  configured root key, `verify-fn` `(fn [pk payload sig] bool)`, `now` an
  ISO-8601 instant compared against the token's `before` facts, and `facts`
  what this actor is willing to state about the call -- the verifier's own
  knowledge, which is never taken from the token."
  [{:keys [token-bytes root-public-key verify-fn now facts]}]
  (cond
    (empty? root-public-key) (deny :biscuit/no-root-key-configured)
    (empty? token-bytes) (deny :biscuit/malformed)
    :else
    (let [t (try (wire/decode-token token-bytes) (catch #?(:clj Exception :cljs :default) _ nil))]
      (if (nil? t)
        (deny :biscuit/malformed)
        (let [v (try (wire/verify t root-public-key verify-fn)
                     (catch #?(:clj Exception :cljs :default) _ {:ok? false :reason :biscuit/malformed}))]
          (if-not (:ok? v)
            (deny (or (:reason v) :biscuit/not-authorized))
            (let [m (wire/token->model t)]
              (let [ck (az/run-checks m v {:facts (vec facts)})]
                (cond
                  (and (not (:allowed? ck)) (= :checks-not-decoded (:reason ck)))
                  (deny :biscuit/checks-not-evaluated)

                  (not (:allowed? ck))
                  (deny (keyword "biscuit" (name (:reason ck))))

                  :else
                (let [;; One argument: what THIS token grants, with no prior authority
                        ;; of ours folded in. Passing {} here read like an
                        ;; unconstrained base and meant the empty antichain, so
                        ;; every scope came back empty and every token was
                        ;; answered :no-scope-in-token.
                        g (authority/->grant m)
                      scopes (vec (:grant/scopes g))
                      expires (:grant/expires g)]
                  (cond
                    (seq (:grant/rejected g)) (deny :biscuit/unparseable-scope)
                    (and (not (blank? expires)) (not (blank? now)) (neg? (compare (str expires) (str now))))
                    (deny :biscuit/expired)
                    (zero? (count scopes)) (deny :biscuit/no-scope-in-token)
                    (> (count scopes) 1) (deny :biscuit/ambiguous-scope)
                    :else (let [s (scope/render (first scopes))]
                            (if (blank? s)
                              (deny :biscuit/no-scope-in-token)
                              {:allowed? true :scope s})))))))))))))
