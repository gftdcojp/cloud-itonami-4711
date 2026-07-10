(ns retailops.facts
  "Per-jurisdiction consumer-protection/fair-trading AND unit-pricing/
  price-marking regulatory catalog -- the G2-style spec-basis table
  the Retail Governor checks every `:jurisdiction/assess` proposal
  against ('did the advisor cite an OFFICIAL public source for this
  jurisdiction's requirements, or did it invent one?').

  This blueprint's own text (docs/business-model.md's already-written
  `:retail-governor` Decision Rule) names 'unit price vs. the
  catalog's price band' as one of the two things the governor's DMN
  check clears a sale against -- a real, distinct regulatory concern:
  most jurisdictions require retail unit pricing to be transparent and
  non-deceptive, with a dedicated legal regime independent of general
  consumer-protection law. Each jurisdiction entry below therefore
  cites BOTH the general consumer-protection/fair-trading law AND a
  SEPARATE unit-pricing/price-marking law.

  Coverage is reported HONESTLY (see `coverage`), the same discipline
  every sibling actor's `facts` namespace uses: a jurisdiction not in
  this table has NO spec-basis, full stop -- the advisor must not
  fabricate one, and the governor holds if it tries. As with
  `leathergoods`/9523's own brand-authenticity sub-citation and
  `ictrepair`/9511's own media-sanitization sub-citation, ALL EIGHT
  seeded jurisdictions actually have a real unit-pricing/price-marking
  enforcement regime here, reported honestly rather than forcing an
  artificial gap -- including MEX, whose closest statutory analog is a
  net-content/quantity declaration (NOM-030-SCFI-2006) rather than a
  literal per-unit shelf price, reported as such rather than
  overstating the regime, and SAU, whose general consumer-protection
  citation is the currently-ENACTED Anti-Commercial Fraud Law rather
  than the still-in-draft 'Consumer Protection Law' (public
  consultation ongoing as of 2024) -- the not-yet-enacted draft is
  never cited as if it were live law.")

(def catalog
  "iso3 -> requirement map. `:required-evidence` mirrors the generic
  SKU-registration/pricing-authorization/sale-record evidence set
  (PLUS a unit-pricing-disclosure record for every seeded
  jurisdiction); `:legal-basis` / `:owner-authority` / `:provenance`
  are the G2 citation the governor requires before any
  `:jurisdiction/assess` proposal can commit. `:price-owner-authority`
  / `:price-legal-basis` / `:price-provenance` are the SEPARATE
  unit-pricing/price-marking citation the governor's
  `price-band-violation?` check is grounded in."
  {"JPN" {:name "Japan"
          :owner-authority "消費者庁 (Consumer Affairs Agency, CAA)"
          :legal-basis "不当景品類及び不当表示防止法 (景品表示法, Act against Unjustifiable Premiums and Misleading Representations)"
          :national-spec "小売業の表示・価格提示に関する一般消費者保護基準"
          :provenance "https://www.caa.go.jp/policies/policy/representation/"
          :required-evidence ["SKU登録記録 (SKU-registration record)"
                              "価格承認記録 (pricing-authorization record)"
                              "販売記録 (sale record)"
                              "単価表示記録 (unit-pricing-disclosure record)"]
          :price-owner-authority "経済産業省 (METI) / 都道府県計量検定所"
          :price-legal-basis "計量法 (Measurement Act) 単価表示規定"
          :price-provenance "https://www.meti.go.jp/policy/economy/keiryo_kikaku/"}
   "USA" {:name "United States"
          :owner-authority "Federal Trade Commission (FTC)"
          :legal-basis "FTC Act Section 5 (15 U.S.C. §45, unfair or deceptive acts or practices)"
          :national-spec "FTC guidance on retail pricing and advertising practices"
          :provenance "https://www.ftc.gov/business-guidance/advertising-marketing"
          :required-evidence ["SKU-registration record"
                              "Pricing-authorization record"
                              "Sale record"
                              "Unit-pricing-disclosure record"]
          :price-owner-authority "National Institute of Standards and Technology (NIST) / state Weights and Measures offices"
          :price-legal-basis "NIST Handbook 130, Uniform Regulation for the Method of Sale of Commodities (unit-pricing provisions, adopted by most states)"
          :price-provenance "https://www.nist.gov/pml/owm/nist-handbook-130"}
   "GBR" {:name "United Kingdom"
          :owner-authority "Competition and Markets Authority (CMA)"
          :legal-basis "Consumer Protection from Unfair Trading Regulations 2008"
          :national-spec "CMA/Trading Standards fair-trading enforcement standards"
          :provenance "https://www.gov.uk/government/organisations/competition-and-markets-authority"
          :required-evidence ["SKU-registration record"
                              "Pricing-authorization record"
                              "Sale record"
                              "Unit-pricing-disclosure record"]
          :price-owner-authority "Department for Business and Trade / local Trading Standards"
          :price-legal-basis "Price Marking Order 2004"
          :price-provenance "https://www.gov.uk/guidance/pricing-information-for-consumers"}
   "DEU" {:name "Germany"
          :owner-authority "Bundeskartellamt / Landesbehörden für Verbraucherschutz"
          :legal-basis "Gesetz gegen den unlauteren Wettbewerb (UWG, Act Against Unfair Competition)"
          :national-spec "UWG lauterkeitsrechtliche Anforderungen an den Einzelhandel"
          :provenance "https://www.gesetze-im-internet.de/uwg_2004/"
          :required-evidence ["SKU-Registrierungsnachweis (SKU-registration record)"
                              "Preisfreigabenachweis (pricing-authorization record)"
                              "Verkaufsnachweis (sale record)"
                              "Grundpreisangabenachweis (unit-pricing-disclosure record)"]
          :price-owner-authority "Bundesministerium für Wirtschaft und Klimaschutz / Gewerbeaufsichtsämter"
          :price-legal-basis "Preisangabenverordnung (PAngV, Price Indication Ordinance, implementing EU Directive 98/6/EC)"
          :price-provenance "https://www.gesetze-im-internet.de/pangv_2022/"}
   "IND" {:name "India"
          :owner-authority "Central Consumer Protection Authority (CCPA), Department of Consumer Affairs, Ministry of Consumer Affairs, Food and Public Distribution"
          :legal-basis "Consumer Protection Act, 2019 (उपभोक्ता संरक्षण अधिनियम, 2019)"
          :national-spec "CCPA prohibition of unfair trade practices, misleading advertisements, and defective/deficient goods sold to consumers"
          :provenance "https://ccpa.doca.gov.in/"
          :required-evidence ["SKU-registration record"
                              "Pricing-authorization record"
                              "Sale record"
                              "Unit-pricing-disclosure record"]
          :price-owner-authority "Legal Metrology Division, Department of Consumer Affairs"
          :price-legal-basis "Legal Metrology Act, 2009 + Legal Metrology (Packaged Commodities) Rules, 2011"
          :price-provenance "https://consumeraffairs.gov.in/pages/legal-metrology-act"}
   "SAU" {:name "Saudi Arabia"
          :owner-authority "وزارة التجارة (Ministry of Commerce) -- الإدارة العامة لمكافحة الغش التجاري (General Administration for Combating Commercial Fraud)"
          :legal-basis "نظام مكافحة الغش التجاري (Anti-Commercial Fraud Law, implementing GCC Unified Law for Combating Commercial Fraud No. 20/2019) -- the currently-enacted regime; a dedicated 'Consumer Protection Law' remains in public-consultation draft, not cited here"
          :national-spec "Prohibition of fraudulent/deceptive representation of goods, false advertising, and unlicensed promotions/discounts"
          :provenance "https://mc.gov.sa/en/About/Departments/cp/Departments/Pages/01.aspx"
          :required-evidence ["SKU-registration record"
                              "Pricing-authorization record"
                              "Sale record"
                              "Unit-pricing-disclosure record"]
          :price-owner-authority "الهيئة السعودية للمواصفات والمقاييس والجودة (Saudi Standards, Metrology and Quality Organization, SASO) -- National Measurement and Calibration Center"
          :price-legal-basis "نظام القياس والمعايرة (Law of Calibration and Measurement) and its Executive Regulation"
          :price-provenance "https://www.saso.gov.sa/en/about/Systems_and_Regulations/System_Calibration_and_Standards/Pages/scs_3.aspx"}
   "ARE" {:name "United Arab Emirates"
          :owner-authority "Ministry of Economy and Tourism (وزارة الاقتصاد والسياحة, renamed from Ministry of Economy in June 2025)"
          :legal-basis "Federal Law No. 15 of 2020 on Consumer Protection, as amended by Federal Decree-Law No. 5 of 2023 (Executive Regulations effective 14 Oct 2023)"
          :national-spec "Supplier/advertiser/trade-agent obligations; goods/services must meet published quality and price"
          :provenance "https://www.moet.gov.ae/en/federal-law-no-15-of-2020-on-consumer-protection"
          :required-evidence ["SKU-registration record"
                              "Pricing-authorization record"
                              "Sale record"
                              "Unit-pricing-disclosure record"]
          :price-owner-authority "Ministry of Industry and Advanced Technology (MOIAT, absorbed ESMA's metrology mandate in 2020)"
          :price-legal-basis "Federal Decree-Law No. 20 of 2020 Concerning Specifications and Standards (Standardization and Metrology Law)"
          :price-provenance "https://uaelegislation.gov.ae/en/legislations/1982"}
   "MEX" {:name "Mexico"
          :owner-authority "Procuraduría Federal del Consumidor (PROFECO)"
          :legal-basis "Ley Federal de Protección al Consumidor (LFPC)"
          :national-spec "Total price (incl. taxes) must be displayed and honored (Art. 7/7 Bis); prohibits deceptive advertising"
          :provenance "https://www.profeco.gob.mx/juridico/pdf/l_lfpc_ultimo_camdip.pdf"
          :required-evidence ["Registro de SKU (SKU-registration record)"
                              "Autorización de precio (pricing-authorization record)"
                              "Registro de venta (sale record)"
                              "Declaración de contenido neto (unit-pricing-disclosure record)"]
          :price-owner-authority "Secretaría de Economía (Dirección General de Normas) / PROFECO (joint verification)"
          :price-legal-basis "Ley de Infraestructura de la Calidad (LIC, 2020, superseded the former Ley Federal sobre Metrología y Normalización) + NOM-030-SCFI-2006 (net-content/quantity declaration on labels)"
          :price-provenance "https://platiica.economia.gob.mx/normalizacion/nom-030-scfi-2006/"}})

(defn spec-basis
  "The jurisdiction's requirement map, or nil -- nil means NO spec-basis,
  and the governor must hold any proposal that tries to post a sale or
  commit a reorder on it."
  [iso3]
  (get catalog iso3))

(defn coverage
  "Honest coverage report: how many of the requested jurisdictions actually
  have a spec-basis entry. Never report a missing jurisdiction as covered."
  ([] (coverage (keys catalog)))
  ([iso3s]
   (let [have (filter catalog iso3s)
         missing (remove catalog iso3s)]
     {:requested (count iso3s)
      :covered (count have)
      :covered-jurisdictions (vec (sort have))
      :missing-jurisdictions (vec (sort missing))
      :note (str "cloud-itonami-isic-4711 R0: " (count catalog)
                 " jurisdictions seeded with an official spec-basis. "
                 "This is a starting catalog, not a survey of all ~194 "
                 "jurisdictions -- extend `retailops.facts/catalog`, "
                 "never fabricate a jurisdiction's requirements.")})))

(defn required-evidence-satisfied?
  "Does `submitted` (a set/coll of evidence keywords or strings) satisfy
  every evidence item listed for `iso3`? Missing spec-basis -> never
  satisfied."
  [iso3 submitted]
  (when-let [{:keys [required-evidence]} (spec-basis iso3)]
    (let [need (count required-evidence)
          have (count (filter (set submitted) required-evidence))]
      (= need have))))

(defn evidence-checklist [iso3]
  (:required-evidence (spec-basis iso3) []))

(defn price-spec-basis
  "The jurisdiction's unit-pricing/price-marking requirement map, or
  nil -- nil means this jurisdiction has NO formal statutory
  unit-pricing/price-marking regime this catalog is aware of. In this
  R0 catalog all eight seeded jurisdictions actually have one (unlike
  some prior siblings' own honest single-jurisdiction gap), reported
  honestly."
  [iso3]
  (when-let [sb (spec-basis iso3)]
    (when (:price-owner-authority sb)
      (select-keys sb [:price-owner-authority :price-legal-basis :price-provenance]))))
