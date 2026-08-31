(ns hanmoto.edge
  "The only Request/Response layer. Everything it decides, it asks a pure
  namespace; everything it performs -- R2, KV, headers -- happens here and
  nowhere else.

  ## Where the principal comes from

  `:caller` is the payer nexus forwards after settling. Trusting a header is
  trusting the host that set it -- so `hanmoto.gateway` establishes that the
  host really was the facilitator, by a secret a direct caller cannot produce,
  *before* this value means anything. Read for billing, never for authority.

  `:scope` would come from a biscuit. **There is no root key configured here**,
  so a presented token cannot be verified -- and an unverifiable token is
  REFUSED, not ignored. Ignoring it would make a forged token and no token
  produce the same answer, which is the class `hanmoto.principal` exists to
  keep apart."
  ;; ⚠ env と decode 済み JS オブジェクトのプロパティは `aget` と文字列キーで読む。
  ;; `:advanced` は `(.-TREASURY_ADDR env)` を rename しうるので、読めずに
  ;; `undefined` になり、`offer-status` が `:unconfigured` を返し、**resources 空の
  ;; 正当な x402 文書**を配ることになる —— 設定漏れと「売る物が無い」が
  ;; 見分けられなくなる形で。`kotobase.edge-cacao` が同じ理由で同じ規約を持つ。
  (:require [ed25519.sign :as ed]
            [hanmoto.gateway :as gateway]
            [hanmoto.offer :as offer]
            [hanmoto.principal :as principal]
            [hanmoto.scope :as scope]
            [hanmoto.register :as register]
            [hanmoto.serve :as serve]
            [clojure.string :as str]))

(def scope-header "authorization")

(defn- json [status body]
  (js/Response. (js/JSON.stringify (clj->js body))
                #js {:status status
                     :headers #js {"content-type" "application/json; charset=utf-8"
                                   "cache-control" "no-store"}}))

(defn- sha256-hex [s]
  (-> (.digest (.-subtle js/crypto) "SHA-256" (.encode (js/TextEncoder.) s))
      (.then (fn [buf]
               (->> (js/Array.from (js/Uint8Array. buf))
                    (map #(.padStart (.toString % 16) 2 "0"))
                    (apply str))))))

(defn- r2-json [env key]
  (-> (.get (aget env "REGISTER") key)
      (.then (fn [o] (when o (.json o))))
      (.then (fn [o] (when o (js->clj o :keywordize-keys true))))))

(defn- load-register
  "Summary always; rows only when the path needs them. The full register is ten
  megabytes and a category count is five kilobytes of answer."
  [env needs-rows?]
  (-> (js/Promise.all
       #js [(r2-json env "summary.json")
            (if needs-rows? (r2-json env "register.json") (js/Promise.resolve nil))])
      (.then (fn [[summary full]]
               (when summary
                 (register/of (cond-> {:summary summary
                                       :as-of (:as-of summary)
                                       ;; When the register was last re-derived,
                                       ;; which is not when its data last
                                       ;; changed. nil travels through: never
                                       ;; rebuilt must not read as not
                                       ;; applicable.
                                       :checked-at (:checked-at summary)
                                       :source (:source summary)}
                                full (assoc :rows (:rows full)))))))))

(defn- hex->bytes
  "Hex to a byte vector, or nil when the string is not hex. nil rather than a
  partial parse: half a public key verifies nothing and must not look like a
  configured one."
  [s]
  (when (and (string? s) (even? (count s)) (pos? (count s))
             (re-matches #"^[0-9a-fA-F]+$" s))
    (mapv #(js/parseInt (subs s % (+ % 2)) 16) (range 0 (count s) 2))))

(defn- bearer->bytes
  "`Authorization: Bearer <base64url>` to a byte vector, or nil."
  [header]
  (when (and (string? header) (re-find #"(?i)^bearer\s+" header))
    (let [b64 (-> header (str/replace #"(?i)^bearer\s+" "")
                  (str/replace "-" "+") (str/replace "_" "/"))
          pad (case (mod (count b64) 4) 2 "==" 3 "=" 0 "" nil)]
      (when pad
        (try (let [bin (js/atob (str b64 pad))]
               (mapv #(.charCodeAt bin %) (range (.-length bin))))
             (catch :default _ nil))))))

(defn- call-facts
  "What this actor is willing to STATE about the call, for the token's checks to
  reason against. The verifier's own knowledge -- never taken from the token,
  which would let a token satisfy its own restrictions.

  Two facts, both of which hanmoto can actually vouch for: which path was asked
  for, and that this is a read. It sells a directory; there is no write."
  [method path]
  [['resource path] ['operation (if (= "GET" method) "read" "write")]])

(defn- principal-of [env req now method path]
  (let [h (.-headers req)
        payer (.get h gateway/payer-header)
        token (.get h scope-header)]
    (principal/of
     {:payer payer
      :chain-id (some-> (aget env "CHAIN_ID") js/parseInt)
      ;; A presented token is decided, never ignored. `hanmoto.scope` refuses
      ;; for a named reason -- no root key, malformed, bad signature, checks
      ;; this implementation cannot evaluate, no scope, or more than one --
      ;; and each of those must stay distinguishable from an anonymous call.
      :auth (when-not (or (nil? token) (= "" token))
              (scope/of {:token-bytes (bearer->bytes token)
                         :root-public-key (hex->bytes (aget env "BISCUIT_ROOT_PUBLIC_KEY"))
                         :verify-fn ed/verify
                         :now now
                         :facts (call-facts method path)}))})))

(defn- record-usage! [env recs]
  (js/Promise.all
   (clj->js
    (for [{:keys [key n]} recs]
      (if n
        ;; counters: read-modify-write. Not atomic, and said so rather than
        ;; implied -- concurrent calls can lose an increment, which is a real
        ;; undercount and the reason a durable object belongs here eventually.
        (-> (.get (aget env "USAGE") key)
            (.then (fn [v] (.put (aget env "USAGE") key (str (+ (or (some-> v js/parseInt) 0) n))))))
        (.put (aget env "USAGE") key "1"))))))

(defn handler [req env _ctx]
  (let [url (js/URL. (.-url req))
        path (.-pathname url)
        method (.-method req)
        now (.toISOString (js/Date.))
        ;; Decided once. Asking twice invites the two answers to drift.
        gate (gateway/admit {:presented (.get (.-headers req) gateway/token-header)
                             :configured (aget env "NEXUS_ORIGIN_TOKEN")})]
    (cond
      (= path "/.well-known/x402")
      (js/Promise.resolve
       (json 200 (offer/document (aget env "TREASURY_ADDR") ["transaction"])))

      ;; Free, and it has to be: a buyer cannot agree to terms it must pay to
      ;; read. Serves the licence facts as DATA from `hanmoto.offer` and points
      ;; at the prose, rather than restating the prose here where the two
      ;; copies would drift.
      (= path "/terms")
      (js/Promise.resolve
       (json 200 {:terms offer/terms-url
                  :sells (mapv :path offer/priced-resources)
                  :upstream-licensing offer/upstream-licensing
                  :note "Answers are sold, not the register. No upstream licence
                         covers the aggregate, so none is passed on."}))

      (= path "/health")
      (js/Promise.resolve (json 200 {:ok true :seller offer/seller :at now}))

      ;; Priced paths must have come through the facilitator. Checked before the
      ;; register is even read: a bypassed call should cost us nothing, and it
      ;; must not be answerable from cache, R2 or anywhere else.
      ;;
      ;; Only priced paths. Gating the rest would answer 402 for a path this
      ;; actor never offered -- charging for something never promised, which is
      ;; the mistake `hanmoto.serve`'s 404 arm exists to avoid.
      (and (offer/sells? method path) (not (gateway/admitted? gate)))
      (let [{:keys [status body]} (get gateway/refusal (:refuse gate))]
        (js/Promise.resolve
         (json status (assoc body :buy (str offer/default-facilitator
                                            "/gateway/" offer/seller path)))))

      :else
      (let [p (principal-of env req now method path)]
        (if (principal/refused? p)
          ;; 401, not an anonymous answer. A token that did not authorise must
          ;; not be served as though none was presented.
          (js/Promise.resolve (json 401 {:error "unauthorized" :reason (str (:refuse p))}))
          (-> (load-register env (str/starts-with? path offer/host-prefix))
              (.then
               (fn [reg]
                 (if-not reg
                   (json 503 {:error "register-unavailable"
                              :note "R2 returned no summary. This is not an empty
                                     register; it is a register that was not read."})
                   (let [caller (get-in p [:principal :caller])]
                     (-> (if caller (sha256-hex caller) (js/Promise.resolve nil))
                         (.then
                          (fn [_]
                            (let [r (serve/handle
                                     {:register reg
                                      :digest (fn [s] (str "h" (hash s)))}
                                     {:method method :path path :instant now}
                                     (:principal p))]
                              (-> (record-usage! env (:usage r))
                                  (.then (fn [_] (json (:status r) (:body r))))))))))))))))))) 

(def default-export #js {:fetch handler})
