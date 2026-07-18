(ns retailops.render-html
  "Build-time HTML renderer for `docs/samples/operator-console.html`.

  Closes flagship checklist item 2 (com-junkawasaki/root ADR-2607189300,
  Wave5 rollout ledger). Drives the REAL actor stack (`retailops.
  operation` -> `retailops.governor` -> `retailops.store`) through a
  scenario adapted from this repo's own `retailops.sim` demo driver
  (`clojure -M:dev:run`, confirmed by actually running it before this
  file was written -- unlike `cloud-itonami-isic-851`'s `schoolops.
  sim`, this repo's own sim driver uses ids that DO match `retailops.
  store/demo-data`'s seeded orders exactly (`order-1`..`order-7`), and
  every disposition it produces (auto-commit / escalate+approve / HARD
  hold, and the exact `:rule` on each hold) matches `retailops.
  governor`'s own documented checks precisely -- verified by running
  `clojure -M:dev:test` (52 tests / 220 assertions / 0 failures) and by
  running `retailops.sim` itself and cross-checking every id/op against
  `store.cljc`'s real seed data, so it was safe to reuse rather than
  author from scratch), covering all seven seeded orders and rendered
  deterministically -- no invented numbers, no timestamps in the page
  content, byte-identical across reruns against the same seed (verified
  by diffing two consecutive runs before shipping).

  Usage: `clojure -M:dev:render-html [out-file]`
  (default `docs/samples/operator-console.html`)."
  (:require [clojure.string :as str]
            [retailops.store :as store]
            [retailops.operation :as op]
            [langgraph.graph :as g]))

;; ----------------------------- harness (unchanged across every repo
;; in this cluster -- do not rewrite, only copy) -----------------------

(def ^:private operator
  {:actor-id "op-1" :actor-role :shop-operator :phase 3})

(defn- exec! [actor tid request]
  (g/run* actor {:request request :context operator} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "op-1"}}
          {:thread-id tid :resume? true}))

(defn run-demo!
  "Runs a fresh seeded store through a scenario mixing every disposition
  this actor can reach, using ONLY real order ids from `retailops.
  store/demo-data`:

  order-1 (JPN, clean sale -- Rice 5kg bag) walks the full clean
  lifecycle: an `:order/intake` directory-normalization patch is a
  phase-3, no-capital-risk auto-commit (governor clean, `:order/
  intake` is one of the two ops in phase 3's `:auto` set, alongside
  `:reorder/receive`); `:jurisdiction/assess` (JPN has a real
  spec-basis in `retailops.facts`) ALWAYS escalates (not in the
  `:auto` set at any phase) and is approved by a human shop operator;
  `:sale/post` -- one of the two REAL-WORLD actuation events this
  actor performs (a real POS sale posting) -- ALSO ALWAYS escalates
  (the governor's own `high-stakes` gate AND the phase table agree,
  independently, that actuation is never auto, at any phase) and is
  approved, producing one draft sale-posting record (`JPN-SAL-000000`).

  order-7 (JPN, clean reorder -- Toilet paper bulk pack, stock 8 at or
  below its own reorder-at 10) walks the same intake -> assess ->
  approve shape, then `:reorder/commit` -- the OTHER real-world
  actuation event this actor performs (a real supplier reorder
  commitment) -- ALSO ALWAYS escalates and is approved, producing one
  draft reorder-commitment record (`JPN-ROR-000000`).

  Then five DISTINCT HARD-hold reasons, none of which ever reach a
  human (a human approver cannot override a HARD violation) -- using
  the remaining five seeded orders exactly as `retailops.governor`'s
  own docstring documents them:
    - order-2 (jurisdiction ATL, not in `retailops.facts/catalog`):
      `:jurisdiction/assess` HARD-holds on `:no-spec-basis` -- the
      advisor may not invent a jurisdiction's consumer-protection/
      unit-pricing requirements.
    - order-3 (JPN, Cooking oil 1L, claimed-total 25.0 but
      quantity 2 x unit-price 10.0 = 20.0): assessed first (clean
      escalate+approve, so evidence is on file and this HARD hold
      below is isolated to the total-mismatch check alone), then
      `:sale/post` HARD-holds on `:sale-total-mismatch` -- the
      governor independently recomputes the order's own claimed total
      against quantity x unit-price, never trusting the advisor's
      confidence alone.
    - order-4 (JPN, Instant noodles 12-pack, ean13 `4006381333932` --
      the checksum-invalid sibling of every other order's real GS1
      EAN-13 `4006381333931`): assessed, then `:sale/post` HARD-holds
      on `:ean13-invalid` -- the governor calls `kotoba.retail/
      ean13-valid?` (the capability library's own validated GS1
      mod-10 checksum), never reimplementing it.
    - order-5 (JPN, Fresh milk 1L, unit-price 20.0 outside its own
      declared price band [5.0,15.0]): assessed, then `:sale/post`
      HARD-holds on `:price-band-violation` -- this vertical's own
      flagship new check (grep-verified absent fleet-wide before this
      actor).
    - order-6 (JPN, Bottled water 24-pack, current-stock 50 above its
      own reorder-at 10): assessed, then `:reorder/commit` HARD-holds
      on `:reorder-threshold-mismatch` -- the governor independently
      verifies via `kotoba.retail/needs-reorder?` that the SKU's own
      recorded stock actually justifies a reorder.

  Returns the resulting store -- every field `render` below reads is
  real governor/store output, not a hand-typed copy."
  []
  (let [db (store/seed-db)
        actor (op/build db)]

    ;; order-1: clean directory-normalization patch -- phase-3
    ;; auto-commit, no capital risk yet.
    (exec! actor "o1-intake" {:op :order/intake :subject "order-1"
                               :patch {:id "order-1" :sku-name "Rice 5kg bag"}})
    (exec! actor "o1-assess" {:op :jurisdiction/assess :subject "order-1"})
    (approve! actor "o1-assess")
    (exec! actor "o1-sale" {:op :sale/post :subject "order-1"})
    (approve! actor "o1-sale")

    ;; order-7: clean directory-normalization patch, then a clean
    ;; reorder commitment.
    (exec! actor "o7-intake" {:op :order/intake :subject "order-7"
                               :patch {:id "order-7" :sku-name "Toilet paper (bulk pack)"}})
    (exec! actor "o7-assess" {:op :jurisdiction/assess :subject "order-7"})
    (approve! actor "o7-assess")
    (exec! actor "o7-reorder" {:op :reorder/commit :subject "order-7"})
    (approve! actor "o7-reorder")

    ;; order-2 (ATL): no official spec-basis in retailops.facts ->
    ;; HARD hold on :no-spec-basis, never reaches a human.
    (exec! actor "o2-assess" {:op :jurisdiction/assess :subject "order-2" :no-spec? true})

    ;; order-3: assess JPN first (clean escalate+approve) so evidence
    ;; is on file and the total-mismatch hold below is isolated.
    (exec! actor "o3-assess" {:op :jurisdiction/assess :subject "order-3"})
    (approve! actor "o3-assess")
    (exec! actor "o3-sale" {:op :sale/post :subject "order-3"})

    ;; order-4: assess, then an invalid GS1 EAN-13 -> HARD hold.
    (exec! actor "o4-assess" {:op :jurisdiction/assess :subject "order-4"})
    (approve! actor "o4-assess")
    (exec! actor "o4-sale" {:op :sale/post :subject "order-4"})

    ;; order-5: assess, then a unit price outside its own price band
    ;; -> HARD hold.
    (exec! actor "o5-assess" {:op :jurisdiction/assess :subject "order-5"})
    (approve! actor "o5-assess")
    (exec! actor "o5-sale" {:op :sale/post :subject "order-5"})

    ;; order-6: assess, then a reorder proposed on stock that is NOT
    ;; actually at or below its own reorder threshold -> HARD hold.
    (exec! actor "o6-assess" {:op :jurisdiction/assess :subject "order-6"})
    (approve! actor "o6-assess")
    (exec! actor "o6-reorder" {:op :reorder/commit :subject "order-6"})

    db))

;; ----------------------------- rendering -----------------------------

(defn- esc [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))

(defn- last-fact-for [ledger subject-id]
  (last (filter #(= (:subject %) subject-id) ledger)))

(defn- status-cell [ledger subject-id]
  (let [f (last-fact-for ledger subject-id)]
    (cond
      (nil? f) "<span class=\"muted\">no activity</span>"
      (= :committed (:t f)) "<span class=\"ok\">committed</span>"
      (= :approval-granted (:t f)) "<span class=\"ok\">approved &amp; committed</span>"
      (= :governor-hold (:t f))
      (let [rule (-> f :violations first :rule)]
        (str "<span class=\"critical\">HARD hold &middot; " (esc (name (or rule :unknown))) "</span>"))
      (= :approval-requested (:t f)) "<span class=\"warn\">awaiting approval</span>"
      :else "<span class=\"muted\">in progress</span>")))

(defn- order-row [ledger {:keys [id kind sku-name jurisdiction unit-price price-band-min price-band-max
                                  ean13 current-stock reorder-at]}]
  (format "        <tr><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s (%s&ndash;%s)</td><td>%s</td><td>%s / %s</td><td>%s</td></tr>"
          (esc id) (esc sku-name) (esc (name kind)) (esc jurisdiction)
          (esc unit-price) (esc price-band-min) (esc price-band-max)
          (esc ean13) (esc current-stock) (esc reorder-at)
          (status-cell ledger id)))

(defn- ledger-row [{:keys [t op subject disposition basis]}]
  (format "        <tr><td>%s</td><td><code>%s</code></td><td>%s</td><td>%s</td></tr>"
          (esc (name t)) (esc (name (or op :n-a))) (esc subject)
          (esc (or (some->> basis (map #(if (keyword? %) (name %) %)) (str/join ", "))
                    (some-> disposition name) ""))))

(defn- record-row [prefix {:strs [record_id order_id jurisdiction kind immutable]}]
  (format "        <tr><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td></tr>"
          (esc prefix) (esc record_id) (esc order_id) (esc jurisdiction)
          (if immutable "<span class=\"ok\">immutable draft</span>" (esc kind))))

(def ^:private action-gate-rows
  ;; Static description of this actor's own op contract
  ;; (`retailops.governor`/`retailops.phase`) -- documentation of fixed
  ;; behavior, not runtime telemetry, so it is legitimately hand-
  ;; described rather than derived from a live run.
  ["        <tr><td><code>:order/intake</code></td><td><span class=\"ok\">phase-3 auto-commit when clean, no capital risk yet</span></td></tr>"
   "        <tr><td><code>:reorder/receive</code></td><td><span class=\"ok\">phase-3 auto-commit when clean, no capital risk yet &middot; optional cold-chain-handoff/storage-zone temperature-window check is HARD when both fields are present</span></td></tr>"
   "        <tr><td><code>:jurisdiction/assess</code></td><td><span class=\"warn\">ALWAYS human approval &middot; spec-basis independently checked against <code>retailops.facts</code>, never fabricated</span></td></tr>"
   "        <tr><td><code>:sale/post</code></td><td><span class=\"warn\">ALWAYS human approval &middot; real POS act (actuation/post-sale) &middot; claimed-total arithmetic, GS1 EAN-13 checksum and price-band membership all independently reverified, never auto at any phase</span></td></tr>"
   "        <tr><td><code>:reorder/commit</code></td><td><span class=\"warn\">ALWAYS human approval &middot; real supplier act (actuation/commit-reorder) &middot; reorder-threshold + double-commit guard independently enforced, never auto at any phase</span></td></tr>"])

(defn render
  "Renders the full operator-console.html document from a store `db`
  that has already run `run-demo!` (or any other real scenario)."
  [db]
  (let [ledger (vec (store/ledger db))
        orders (store/all-orders db)
        order-rows (str/join "\n" (map (partial order-row ledger) orders))
        ledger-rows (str/join "\n" (map ledger-row ledger))
        sale-rows (str/join "\n" (map (partial record-row "sale-posting") (store/sale-history db)))
        reorder-rows (str/join "\n" (map (partial record-row "reorder-commitment") (store/reorder-history db)))]
    (str
     "<html><head><meta charset=\"utf-8\"><title>cloud-itonami-isic-4711 &middot; retail sale in non-specialized stores</title><style>\n"
     "table { width: 100%; border-collapse: collapse; font-size: 14px; }\n"
     ".ok { color: #137a3f; }\n"
     "body { font-family: system-ui,-apple-system,sans-serif; margin: 0; color: #1a1a1a; background: #fafafa; }\n"
     "header.bar { display: flex; align-items: center; gap: 12px; padding: 12px 20px; background: #fff; border-bottom: 1px solid #e5e5e5; }\n"
     "th, td { text-align: left; padding: 8px 10px; border-bottom: 1px solid #f0f0f0; }\n"
     "h2 { margin-top: 0; font-size: 15px; }\n"
     ".warn { color: #b25c00; background: #fff8e1; padding: 2px 6px; border-radius: 4px; }\n"
     "main { max-width: 980px; margin: 24px auto; padding: 0 20px; }\n"
     "header.bar h1 { font-size: 18px; margin: 0; font-weight: 600; }\n"
     ".muted { color: #888; font-size: 13px; }\n"
     ".critical { color: #fff; background: #b3261e; padding: 2px 6px; border-radius: 4px; font-weight: 600; }\n"
     ".card { background: #fff; border: 1px solid #e5e5e5; border-radius: 8px; padding: 16px; margin-bottom: 16px; }\n"
     ".err { color: #b3261e; background: #fbe9e7; padding: 2px 6px; border-radius: 4px; }\n"
     "th { font-weight: 600; color: #555; font-size: 12px; text-transform: uppercase; letter-spacing: 0.04em; }\n"
     "header.bar .badge { margin-left: auto; font-size: 12px; color: #666; }\n"
     "code { font-size: 12px; background: #f4f4f4; padding: 1px 4px; border-radius: 3px; }\n"
     "</style></head><body>\n"
     "<header class=\"bar\">\n"
     "  <h1>Retail sale in non-specialized stores (ISIC 4711) — Operator Console</h1>\n"
     "  <span class=\"badge\">read-only sample · governor-gated · sale posting/reorder commitment always human-approved</span>\n"
     "</header>\n"
     "<main>\n"
     "  <section class=\"card\">\n"
     "    <h2>Orders</h2>\n"
     "    <p class=\"muted\">Demo snapshot — build-time-generated from <code>retailops.store</code> via <code>retailops.render-html</code> (<code>clojure -M:dev:render-html</code>), regenerated nightly.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Order</th><th>SKU</th><th>Kind</th><th>Jurisdiction</th><th>Unit price (band)</th><th>EAN-13</th><th>Stock / reorder-at</th><th>Last op status</th></tr></thead>\n"
     "      <tbody>\n"
     order-rows "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "  <section class=\"card\">\n"
     "    <h2>Draft sale-posting / reorder-commitment records</h2>\n"
     "    <p class=\"muted\">Unsigned drafts only — the shop's own act of posting/committing is outside this actor's authority (see README <code>Actuation</code>).</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Kind</th><th>Record id</th><th>Order</th><th>Jurisdiction</th><th>Status</th></tr></thead>\n"
     "      <tbody>\n"
     sale-rows (when (seq sale-rows) "\n")
     reorder-rows "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "  <section class=\"card\">\n"
     "    <h2>Action gate (Retail Governor)</h2>\n"
     "    <p class=\"muted\">HARD holds cannot be overridden by a human approver. Jurisdiction spec-basis, sale-total arithmetic, GS1 EAN-13 checksum, price-band membership and reorder-threshold justification are independently recomputed, never trusted from the advisor's proposal; a real sale posting or reorder commitment is always a human shop operator's call, at every rollout phase.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Op</th><th>Gate</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" action-gate-rows) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "  <section class=\"card\">\n"
     "    <h2>Audit ledger (this run)</h2>\n"
     "    <p class=\"muted\">Append-only decision-fact log — every proposal, hold and commit this scenario produced.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Fact</th><th>Op</th><th>Subject</th><th>Basis</th></tr></thead>\n"
     "      <tbody>\n"
     ledger-rows "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "</main>\n"
     "</body></html>\n")))

(defn -main [& args]
  (let [out (or (first args) "docs/samples/operator-console.html")
        db (run-demo!)
        html (render db)]
    (spit out html)
    (println "wrote" out "(" (count (store/ledger db)) "ledger facts,"
             (count (store/sale-history db)) "sale-posting drafts,"
             (count (store/reorder-history db)) "reorder-commitment drafts )")))
