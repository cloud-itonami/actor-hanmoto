# Terms for hanmoto's directory answers

Last measured 2026-08-31. These terms are narrow, and the reason is in §2: this
service does not hold rights it could pass on, so it does not pretend to.

## 1. What is sold

Answers computed over a register of publishing hosts:

- `/x402/counts` — how many hosts fall in each declared category
- `/x402/host/{domain}` — what one host says about itself
- `/x402/unclassified` — the software values outside the category vocabulary

**Answers, not the register.** There is no bulk export, and buying an answer
does not buy the corpus it was computed from.

## 2. What rights come with an answer — and which do not

The register is built from two upstream directories, and their licensing was
read rather than assumed:

| source | rows | licence recorded upstream |
|---|---|---|
| NodeInfo 2.x server self-descriptions | 26,406 | **per-server** — each host publishes its own description under its own terms; no bulk export exists |
| `plc.directory` | 901 | **none published** — "published for replication (relays depend on it); no dataset licence attached" |

So **no licence covers the aggregate**, and this service cannot grant one. What
a buyer receives is the answer to the question it asked. A buyer may use that
answer — in a product, a report, a decision. A buyer may **not** treat repeated
answers as a licence to reconstitute and redistribute the register itself.

If you need redistribution rights over the underlying records, they are not
ours to give: they belong to each host that published its own NodeInfo, and to
whoever decides `plc.directory`'s terms.

## 3. What the answers are about, and what they are never about

**Hosts, never accounts.** The register holds publishing servers. The upstream
corpus enforces this with a policy ceiling that names the forbidden classes —
profile text, images, posts, social graph, contacts, keys, and any linkage to a
natural person — and fails its own build if one appears. Answering *how many*
must not require the ability to answer *who*, and here it does not.

## 4. What every answer tells you about itself

Not optional, and not a footnote:

- **`as-of`** — when the DATA last changed
- **`checked-at`** — when it was last re-derived from the corpus
- **`age-days`** — measured from the data, so a rebuild never shortens it
- **`unknown` and `unknown_ratio`** — the unclassified tail, beside every
  categorical count rather than rounded away

A rebuild that found nothing changed does not make an old snapshot new, and the
two dates are there so you can tell those apart.

## 5. What is not warranted

- **Completeness.** A host that never published NodeInfo is not in the register,
  and its absence is not evidence of its non-existence.
- **Currency.** The register is a snapshot with its age attached. Hosts appear,
  move and vanish between snapshots.
- **Correctness of self-reports.** A host's software and version are what that
  host says about itself. This service does not verify them, and
  `by_source` keeps self-reported and observed rows in separate columns rather
  than summing them into one number that would hide the difference.

## 6. Payment

Priced in USDC on Base through an x402 facilitator; prices are in
`pricing.edn`. A request that reaches the origin without arriving through the
facilitator is refused, not served — the posted price is not optional.

## 7. Changes

These terms live in the repository and change by commit, so their history is
`git log -p TERMS.md`. A change that narrows what a buyer may do applies to
answers bought after it lands, not retroactively.
