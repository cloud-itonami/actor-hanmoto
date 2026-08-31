# hanmoto 版元 — the register of who publishes

`cloud-itonami/actor-hanmoto`. A governed, resident actor on the Itonami
operations plane.

**版元 (hanmoto)** was the Edo publishing house — the party that put a work into
the world. This actor keeps the register of them: **which hosts publish on the
open web, on what software, at what scale.**

## The boundary with kawaraban

`cloud-itonami/actor-kawaraban` (瓦版) is the neighbouring actor and the two are
easy to confuse, so the line is drawn here rather than left to be guessed:

| | carries |
|---|---|
| **kawaraban** 瓦版 | **what was said** — outlets, sections, headlines, bylines, links |
| **hanmoto** 版元 | **who can say it** — the publishing hosts themselves |

A headline belongs to kawaraban. The host that served it belongs here.

## What it does NOT hold

**No personal accounts.** The upstream corpus
(`kotoba-lang/global-accounts-datoms`) carries 174,592 account rows, and this
register carries none of them.

CLAUDE.md makes that repo-wide mandatory: an account joins only through
`:service/host` → `:service/domain`, and that join needs the service, not the
account. **A register of publishers is a register of organisations.** Answering
*how many* must not require the ability to answer *who*.

The same line runs through the meter (§ Metering): the subject is hashed, and
salted per month and per scope, so a usage export can be audited without being
a membership list.

## The register

27,307 hosts, from two directories with different provenance, which are kept
apart rather than summed:

| source | what it is | count |
|---|---|---|
| `:self-reported` | NodeInfo — the host's own declaration | 26,406 |
| `:observed` | counted from the account side; carries no software | 901 |

Categorised by a **declared vocabulary**, not a derivation — `software` cannot
tell you what kind of publishing it is any more than a repository can tell you
that 端末 and terminal are the same word:

```
blog    13,319   wordpress · ghost · writefreely · plume
social  10,012   mastodon · gotosocial · misskey · pleroma · akkoma · sharkey
media    1,730   peertube · owncast · pixelfed · funkwhale
forum      306   lemmy · piefed · kbin
events     232   books 80
unknown  1,628   not in the vocabulary — NOT pushed into the nearest box
```

The 6.0% unknown is reported on every answer. Rounding the tail into
neighbouring categories would make the table look finished and the answer false.

## Metering

`hanmoto.usage`. The shape is taken from `authn.usage` (ADR-2608110200), the one
meter in this workspace that actually supports a price book, and for the reason
that ADR gives:

> **A price can be decided later. A month that was not measured can never be
> measured afterwards.**

Two dimensions, both countable, and nothing else may be priced:

- `:query` — one register query answered. Describes load.
- `:mac` — **monthly active caller**, de-duplicated at write time. Describes
  customers. De-duplication is the part that cannot be reconstructed from a log
  afterwards: by the time you notice, the distinctness is gone.

The caller is `did:pkh:eip155:8453:0x…`, derived from the key that pays
(ADR-2608313700 — an account is derived, not issued), and it is **hashed with a
per-month, per-scope salt** before it is recorded.

`hanmoto.usage` is pure: the digest is an injected function, so this namespace
holds no crypto and performs no I/O.

## Pricing

`pricing.edn`, and it is the only place a number lives — a price change is a
one-file edit, never a deploy of the thing people call.

**Every plan is `:proposed` and the gates are not met.** Numbers exist so they
can be argued with, not because anyone may be billed from them.

## Run

```
clojure -M:test
```
