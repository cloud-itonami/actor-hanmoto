(ns hanmoto.gateway
  "Did this request come through the facilitator, or straight off the internet?

  Everything else in this actor answers *what* to serve. This namespace answers
  *whether the request was allowed to reach us at all*, and it exists because
  for a while the answer was yes to everyone.

  ## The hole this closes

  Measured 2026-08-31, both against the deployment:

      x402.nexus/gateway/hanmoto/x402/counts -> 402, a Base USDC challenge
      hanmoto.itonami.cloud/x402/counts      -> 200, the counts themselves

  The price was real and so was the bypass. From the seller's side the two calls
  were the same shape -- a 200 carrying data -- so nothing went red. A control
  that can be skipped returns what a satisfied control returns, which is the
  defect class this workspace keeps meeting.

  ## Why a shared secret, and not the payment headers

  The gateway also sets `x-nexus-payment-payer` and `x-nexus-payment-tx`. Those
  are *claims about* a payment, and a caller reaching this origin directly can
  write them just as easily as the gateway can. They identify; they do not
  authenticate. The origin token is the only inbound value a direct caller
  cannot produce, because it never leaves the two Workers that hold it.

  (The other honest design is to re-verify the buyer's `X-PAYMENT` on-chain, the
  way murakumo's x402 lane does -- nexus keeps that header intact for exactly
  this. It needs no secret. It is heavier, and it is the upgrade path, not a
  reason to keep serving free in the meantime.)

  ## Not configured is not permitted

  If this origin holds no secret, `admit` REFUSES. It does not pass, and it does
  not fall back to serving.

  That will take the paid path down until the secret is provisioned on both
  sides, and down is the correct state: this repo already decided the same
  question the same way for an unmetered resource -- *revenue with no record
  behind it is worse than an outage, because nothing goes red* (`hanmoto.serve`,
  the 500 arm). A resource served without payment is that, exactly.

  Four outcomes, deliberately distinct, because collapsing any two of them is
  how the original hole would come back:

      {:admit true}                        the token matched
      {:refuse :gateway/no-token}          none presented -- for sale, unpaid
      {:refuse :gateway/token-mismatch}    one presented, and it was wrong
      {:refuse :gateway/no-secret}         this origin cannot check at all"
  (:require [clojure.string :as str]))

(def token-header
  "Set by nexus `proxy-to-origin` when the seller rule declares `auth-header`.
  The name is nexus's, not ours -- `docs/infer-memory-seller.json` is the
  precedent, and murakumo already receives it."
  "x-nexus-origin-token")

(def payer-header
  "The payer nexus forwards after settling.

  Read for billing, never for authority. This actor previously read
  `x-402-payer`, a name that appears nowhere in the facilitator, so the field
  was unreachable in production and reachable only by forging it -- a spoofing
  surface with no legitimate writer."
  "x-nexus-payment-payer")

(defn- blank? [s] (or (nil? s) (not (string? s)) (str/blank? s)))

(defn- constant-time=
  "Compare without returning early on the first differing character.

  Over TLS and a network this is close to unmeasurable, and it is three lines.
  The reason to write it is that the alternative has to be argued each time
  someone reads it."
  [a b]
  (and (= (count a) (count b))
       (zero? (reduce (fn [acc i]
                        (bit-or acc (bit-xor (int (nth a i)) (int (nth b i)))))
                      0
                      (range (count a))))))

(defn admit
  "-> `{:admit true}` | `{:refuse reason}`.

  `presented` is the inbound token; `configured` is the secret this origin
  holds. Both are read by the edge; nothing here touches a request."
  [{:keys [presented configured]}]
  (cond
    (blank? configured) {:refuse :gateway/no-secret}
    (blank? presented)  {:refuse :gateway/no-token}
    (constant-time= presented configured) {:admit true}
    :else {:refuse :gateway/token-mismatch}))

(defn admitted? [r] (true? (:admit r)))

(def refusal
  "How each refusal answers, and why that status and not another.

  `:no-token` is 402 rather than 401 because the resource *is* for sale and the
  caller simply has not paid; the body points at the gateway, so the answer is
  actionable rather than a door with no handle.

  `:no-secret` is 503 because the fault is ours. Charging a buyer for something
  we cannot verify they paid for would be the same lie in the other direction."
  {:gateway/no-token
   {:status 402
    :body {:error "payment-required"
           :note "This resource is sold through the facilitator. A request that
                  reaches this origin directly has not been settled, and serving
                  it would make the posted price optional."}}
   :gateway/token-mismatch
   {:status 403
    :body {:error "forbidden"
           :note "An origin token was presented and it did not match. Answered
                  differently from a missing one on purpose: a rejected
                  credential must not look like an absent one."}}
   :gateway/no-secret
   {:status 503
    :body {:error "origin-token-unconfigured"
           :note "This origin holds no shared secret, so it cannot tell a settled
                  request from any other. Refusing to serve a priced resource it
                  cannot check -- not passing, and not falling back."}}})
