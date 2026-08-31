(ns hanmoto.edge
  "The only Request/Response layer. Everything it decides, it asks a pure
  namespace; everything it performs -- R2, KV, headers -- happens here and
  nowhere else.

  ## Where the principal comes from

  `:caller` is the payer, taken from the header the x402 gateway sets after it
  verified the payment. Trusting a header is trusting the host that set it, and
  that host is the facilitator this actor registered with. It is NOT the caller.

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
  (:require [hanmoto.offer :as offer]
            [hanmoto.principal :as principal]
            [hanmoto.register :as register]
            [hanmoto.serve :as serve]))

(def payer-header "x-402-payer")
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
                                       :source (:source summary)}
                                full (assoc :rows (:rows full)))))))))

(defn- principal-of [env req]
  (let [h (.-headers req)
        payer (.get h payer-header)
        token (.get h scope-header)]
    (principal/of
     {:payer payer
      :chain-id (some-> (aget env "CHAIN_ID") js/parseInt)
      ;; A token cannot be verified without a root key. Refuse rather than
      ;; ignore: see the namespace docstring.
      :auth (when-not (or (nil? token) (= "" token))
              {:allowed? false :reason :biscuit/no-root-key-configured})})))

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
        now (.toISOString (js/Date.))]
    (cond
      (= path "/.well-known/x402")
      (js/Promise.resolve
       (json 200 (offer/document (aget env "TREASURY_ADDR") ["transaction"])))

      (= path "/health")
      (js/Promise.resolve (json 200 {:ok true :seller offer/seller :at now}))

      :else
      (let [p (principal-of env req)]
        (if (principal/refused? p)
          ;; 401, not an anonymous answer. A token that did not authorise must
          ;; not be served as though none was presented.
          (js/Promise.resolve (json 401 {:error "unauthorized" :reason (str (:refuse p))}))
          (-> (load-register env (clojure.string/starts-with? path offer/host-prefix))
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
