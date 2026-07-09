(ns retailops.retailopsllm
  "RetailOps-LLM client -- the *contained intelligence node* for the
  community-retail actor.

  It normalizes order intake, drafts a per-jurisdiction consumer-
  protection/unit-pricing evidence checklist, drafts the sale-posting
  action, and drafts the reorder-commitment action. CRITICAL: it is a
  smart-but-untrusted advisor. It returns a *proposal* (with a
  rationale + the fields it cited), never a committed record or a real
  sale/reorder. Every output is censored downstream by `retailops.
  governor` before anything touches the SSoT, and `:sale/post`/
  `:reorder/commit` proposals NEVER auto-commit at any phase -- see
  README `Actuation`.

  Like every sibling actor's advisor, this is a deterministic mock so
  the actor graph runs offline and the governor contract is exercised
  end-to-end. In production this calls a real LLM (kotoba-llm or
  equivalent) with the same proposal shape.

  Proposal shape (all kinds):
    {:summary    str            ; human-facing draft / finding
     :rationale  str            ; why -- SCANNED by the spec-basis gate
     :cites      [kw|str ..]    ; facts/sources the LLM used -- SCANNED too
     :effect     kw             ; how a commit would mutate the SSoT
     :stake      kw|nil         ; :actuation/post-sale | :actuation/commit-reorder | nil
     :confidence 0..1}"
  (:require #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])
            [clojure.string :as str]
            [retailops.facts :as facts]
            [retailops.registry :as registry]
            [retailops.store :as store]
            [langchain.model :as model]))

(defn- normalize-intake
  "Directory upsert -- the LLM only normalizes/validates the patch; it
  does not invent the SKU, prices or jurisdiction. High confidence,
  low stakes."
  [_db {:keys [patch]}]
  {:summary    (str "受注記録更新: " (pr-str (keys patch)))
   :rationale  "入力 patch の正規化のみ。新規事実の生成なし。"
   :cites      (vec (keys patch))
   :effect     :order/upsert
   :value      patch
   :stake      nil
   :confidence 0.97})

(defn- assess-jurisdiction
  "Per-jurisdiction consumer-protection/unit-pricing evidence
  checklist draft. `:no-spec?` injects the failure mode we must defend
  against: proposing a checklist for a jurisdiction with NO official
  spec-basis in `retailops.facts` -- the Retail Governor must reject
  this (never invent a jurisdiction's requirements)."
  [db {:keys [subject no-spec?]}]
  (let [o (store/order db subject)
        iso3 (if no-spec? "ATL" (:jurisdiction o))
        sb (facts/spec-basis iso3)]
    (if (nil? sb)
      {:summary    (str iso3 " の公式spec-basisが見つかりません")
       :rationale  "retailops.facts に未登録の法域。要件を推測で作らない。"
       :cites      []
       :effect     :assessment/set
       :value      {:jurisdiction iso3 :checklist [] :spec-basis nil}
       :stake      nil
       :confidence 0.9}
      {:summary    (str iso3 " (" (:owner-authority sb) ") 向け必要書類 "
                        (count (:required-evidence sb)) " 件を提案")
       :rationale  (str "公式ソース: " (:provenance sb) " / 法的根拠: " (:legal-basis sb))
       :cites      [(:legal-basis sb) (:provenance sb)]
       :effect     :assessment/set
       :value      {:jurisdiction iso3
                    :checklist (:required-evidence sb)
                    :spec-basis (:provenance sb)
                    :legal-basis (:legal-basis sb)}
       :stake      nil
       :confidence 0.9})))

(defn- propose-sale-posting
  "Draft the actual SALE-POSTING action -- posting a real sale. ALWAYS
  `:stake :actuation/post-sale` -- this is a REAL-WORLD act (a real
  receipt is issued, inventory decrements), never a draft the actor
  may auto-run. See README `Actuation`: no phase ever adds this op to
  a phase's `:auto` set (`retailops.phase`); the governor also always
  escalates on `:actuation/post-sale`. Two independent layers agree,
  deliberately."
  [db {:keys [subject]}]
  (let [o (store/order db subject)
        matches? (and o (registry/sale-total-matches-claim? o))
        ean-ok? (and o (registry/ean13-valid? (:ean13 o)))
        price-ok? (and o (registry/price-within-band? o))]
    {:summary    (str subject " 向け販売計上提案"
                      (when o (str " (SKU=" (:sku-name o) ")")))
     :rationale  (if o
                   (str "claimed-total=" (:claimed-total o)
                        " independent-recompute=" (registry/compute-sale-total o)
                        " ean13-valid?=" ean-ok?
                        " price-within-band?=" price-ok?)
                   "orderが見つかりません")
     :cites      (if o [subject] [])
     :effect     :order/mark-sold
     :value      {:order-id subject}
     :stake      :actuation/post-sale
     :confidence (if (and matches? ean-ok? price-ok?) 0.9 0.3)}))

(defn- propose-reorder-commitment
  "Draft the actual REORDER-COMMITMENT action -- committing a real
  reorder to a supplier. ALWAYS `:stake :actuation/commit-reorder` --
  this is a REAL-WORLD act (real money is committed to a supplier
  order), never a draft the actor may auto-run. See README
  `Actuation`: no phase ever adds this op to a phase's `:auto` set
  (`retailops.phase`); the governor also always escalates on
  `:actuation/commit-reorder`. Two independent layers agree,
  deliberately."
  [db {:keys [subject]}]
  (let [o (store/order db subject)
        needed? (and o (registry/needs-reorder? o))]
    {:summary    (str subject " 向け発注確定提案"
                      (when o (str " (SKU=" (:sku-name o) ")")))
     :rationale  (if o
                   (str "current-stock=" (:current-stock o)
                        " reorder-at=" (:reorder-at o)
                        " needs-reorder?=" needed?)
                   "orderが見つかりません")
     :cites      (if o [subject] [])
     :effect     :order/mark-reordered
     :value      {:order-id subject}
     :stake      :actuation/commit-reorder
     :confidence (if needed? 0.9 0.3)}))

(defn infer
  "Route a request to the right proposal generator.
  request: {:op kw :subject id ...op-specific...}"
  [db {:keys [op] :as request}]
  (case op
    :order/intake               (normalize-intake db request)
    :jurisdiction/assess            (assess-jurisdiction db request)
    :sale/post                          (propose-sale-posting db request)
    :reorder/commit                         (propose-reorder-commitment db request)
    {:summary "未対応の操作" :rationale (str op) :cites []
     :effect :noop :stake nil :confidence 0.0}))

;; ----------------------------- Advisor protocol -----------------------------

(defprotocol Advisor
  (-advise [advisor store request] "store + request -> proposal map"))

(defn mock-advisor
  "The deterministic advisor (the `infer` logic above). Default everywhere."
  [] (reify Advisor (-advise [_ st req] (infer st req))))

(def ^:private system-prompt
  (str "あなたは地域小売店の販売計上・発注確定エージェントの助言者です。"
       "与えられた事実のみに基づき、提案を1つだけEDNマップで返します。"
       "説明や前置きは一切書かず、EDNだけを出力します。\n"
       "キー: :summary(人向けドラフト) :rationale(根拠/必ず事実から) "
       ":cites(使った事実キーのベクタ) "
       ":effect(:order/upsert|:assessment/set|:order/mark-sold|"
       ":order/mark-reordered) "
       ":stake(:actuation/post-sale か :actuation/commit-reorder か nil) :confidence(0..1)。\n"
       "重要: 登録されていない法域の要件を絶対に創作してはいけません。"
       "spec-basisが無い場合は :cites を空にし confidence を上げないこと。"
       "バーコード検証結果や価格帯適合状況を偽って報告してはいけません。"))

(defn- facts-for [st {:keys [op subject]}]
  (case op
    :jurisdiction/assess    {:order (store/order st subject)}
    :sale/post              {:order (store/order st subject)}
    :reorder/commit         {:order (store/order st subject)}
    {:order (store/order st subject)}))

(defn- parse-proposal
  "Parse the model's EDN proposal defensively. Any parse/shape failure
  yields a safe low-confidence noop so the Retail Governor escalates/
  holds -- an LLM hiccup can never auto-post a sale or auto-commit a
  reorder."
  [content]
  (let [p (try (edn/read-string (str/trim (str content)))
               (catch #?(:clj Exception :cljs :default) _ nil))]
    (if (map? p)
      (-> p
          (update :cites #(vec (or % [])))
          (update :confidence #(if (number? %) (double %) 0.0))
          (update :effect #(or % :noop)))
      {:summary "LLM応答を解釈できませんでした" :rationale (str content)
       :cites [] :effect :noop :stake nil :confidence 0.0})))

(defn llm-advisor
  "An advisor backed by a `langchain.model/ChatModel` (real inference)."
  ([chat-model] (llm-advisor chat-model {}))
  ([chat-model gen-opts]
   (reify Advisor
     (-advise [_ st req]
       (let [msgs [{:role :system :content system-prompt}
                   {:role :user :content (str "操作: " (:op req)
                                              "\n対象: " (:subject req)
                                              "\n事実: " (pr-str (facts-for st req)))}]
             resp (model/-generate chat-model msgs gen-opts)]
         (parse-proposal (:content resp)))))))

(defn trace
  "Decision-grounded audit record -- persisted to the :audit channel."
  [request proposal]
  {:t          :retailopsllm-proposal
   :op         (:op request)
   :subject    (:subject request)
   :summary    (:summary proposal)
   :rationale  (:rationale proposal)
   :cites      (:cites proposal)
   :confidence (:confidence proposal)})
