(ns retailops.phase
  "Phase 0->3 staged rollout for the community-retail actor.

    Phase 0  read-only        -- no writes, still governor-gated.
    Phase 1  assisted-intake  -- order intake (+ reorder-receipt intake,
                                 same no-capital-risk logging tier)
                                 allowed, every write needs human
                                 approval.
    Phase 2  assisted-assess  -- adds jurisdiction assessment writes,
                                 still approval.
    Phase 3  supervised auto  -- governor-clean, high-confidence
                                 `:order/intake`/`:reorder/receive` (no
                                 capital risk yet) may auto-commit
                                 (a HARD cold-chain-handoff mismatch on
                                 the latter still always holds -- the
                                 governor's `hard?` always wins, see
                                 `verdict->disposition`). `:sale/post`/
                                 `:reorder/commit` NEVER auto-commit,
                                 at any phase.

  `:sale/post`/`:reorder/commit` are deliberately ABSENT from every
  phase's `:auto` set, including phase 3 -- a permanent structural
  fact, not a rollout milestone still to come. Posting a real sale and
  committing a real reorder are the two real-world legal/financial
  acts this actor performs; both are always a human shop operator's
  call. `retailops.governor`'s `:actuation/post-sale`/`:actuation/
  commit-reorder` high-stakes gate enforces the same invariant
  independently -- two layers, not one, agree on this. Phase 3's
  `:auto` set now has two members (`:order/intake`/`:reorder/receive`)
  -- both pure directory-upsert normalization of an OBSERVED fact (a
  patch, or an inbound delivery), no separate capital-risk 'file'
  lifecycle distinct from the order itself.")

(def read-ops  #{})
(def write-ops #{:order/intake :jurisdiction/assess :sale/post :reorder/commit :reorder/receive})

;; NOTE the invariant: `:sale/post`/`:reorder/commit` are members of
;; `write-ops` (governor-gated like any write) but are NEVER members
;; of any phase's `:auto` set below. Do not add them there.
(def phases
  "phase -> {:label .. :writes <ops allowed to write> :auto <ops allowed to
  auto-commit when governor-clean>}."
  {0 {:label "read-only"       :writes #{}                                                       :auto #{}}
   1 {:label "assisted-intake" :writes #{:order/intake :reorder/receive}                          :auto #{}}
   2 {:label "assisted-assess" :writes #{:order/intake :reorder/receive :jurisdiction/assess}       :auto #{}}
   3 {:label "supervised-auto" :writes write-ops
      :auto #{:order/intake :reorder/receive}}})

(def default-phase 3)

(defn gate
  "Adjust a governor disposition for the rollout phase. Returns
  {:disposition kw :reason kw|nil}.

  - a governor HOLD always stays HOLD (compliance wins).
  - a write op not yet enabled in this phase -> HOLD (:phase-disabled).
  - a write op enabled but not auto-eligible -> ESCALATE (:phase-approval),
    even if the governor was clean.
  - `:sale/post`/`:reorder/commit` are never auto-eligible at any
    phase, so they always escalate once the governor clears them (or
    hold if the governor doesn't)."
  [phase {:keys [op]} governor-disposition]
  (let [{:keys [writes auto]} (get phases phase (get phases default-phase))]
    (cond
      (= :hold governor-disposition)       {:disposition :hold :reason nil}
      (contains? read-ops op)              {:disposition governor-disposition :reason nil}
      (not (contains? writes op))          {:disposition :hold :reason :phase-disabled}
      (and (= :commit governor-disposition)
           (not (contains? auto op)))      {:disposition :escalate :reason :phase-approval}
      :else                                {:disposition governor-disposition :reason nil})))

(defn verdict->disposition
  "Map a Retail Governor verdict to a base disposition before the
  phase gate."
  [verdict]
  (cond (:hard? verdict) :hold
        (:escalate? verdict) :escalate
        :else :commit))
