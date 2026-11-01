# Live-match diplomacy v1

This is a bounded addition to both quick and full R&D matches. It uses the active
`RndMatchEngine` state; it does not operate on the separate basic sandbox.

## Commands

```text
deals
offer asha peace 3
offer asha give 2 gold for 1 wealth
offer asha give 4 gold for 2 mana peace 3
accept D1
reject D1
cancel D1
betray asha
```

Use the actual deal ID returned by the game. All mutations require your active
turn, a surviving kingdom, and an unfinished match. Deals do not consume the main
card action. `deals`, like `events`, remains readable after elimination/victory.

## Resource settlement

- Resources are Gold, Wealth and Mana. One positive amount per side, different
  resource types, amounts 1..1,000,000. No card, equipment or goods exchange yet.
- Proposing checks that the sender can fund its side. Funds are **not reserved**.
  Acceptance rechecks both sides and integer overflow before changing anything.
- Only the recipient can accept/reject; only the proposer can cancel.
- An accepted exchange settles both sides once. Repeating acceptance cannot pay
  twice. Cancelling, rejection and expiry transfer nothing.
- Offers expire at `proposal round + 2`, at the completed-round boundary, or when
  either party is conquered. One pending offer per sender/recipient pair; maximum
  three proposals per sender turn. Passing resets that limit.
- Pending terms are visible only in the two participants' deal views. Public
  events announce that an offer exists; accepted terms and settlements are public.
  This is view-level redaction, **not user authentication or session isolation**.

## Peace and betrayal

These are **breakable promises**, not the older engine's mechanically enforced
Official Contracts. The distinction matters: a player can explicitly betray a
promise without a magic penalty preventing the subsequent attack.

Acceptance creates a mutual ceasefire. Duration is 1..10 completed world rounds,
ending at `acceptance round + duration` (exclusive). For example, a promise
accepted during R2 for three rounds is active until the start of R5, regardless
of which seat accepted it. Exact end round is displayed. Natural expiry does not
damage trust.

An active ceasefire blocks attacks/counterattacks and conquest against that
partner, entering its original capital, and igniting its original capital or a
region containing its deployed cards. Ordinary movement elsewhere and beneficial
water/healing effects are not forbidden. Partners can still fight third parties.
New peace is refused while either party has surviving armed forces at the other's
capital, or while a bilateral occupation is pending. Acceptance rechecks this.

`betray <kingdom>` removes the active pact and increments the actor's public
broken-promise count. It does not consume a card action, restore a spent raid,
move troops, or refund a settled trade. Repeating it after the pact is gone has
no effect. Combat still needs legal location, troops and action allowance.

## Opponents

Opponents respond on their actual turns using owned resources and visible game
state. Their provisional valuations are Gold=1, Wealth=2, Mana=2. They accept an
affordable offer whose received value is at least the value they pay, and reject
unaffordable/invalid/losing offers. They refuse peace from a ruler with any public
broken promise, for the rest of this match; pure resource barter remains possible.
After R5, some deterministic diplomacy choices can originate three-round peace
offers. Active partners are excluded from offensive target selection when another
target exists; engine checks still block hostility when all rivals are partners.

This is transparent deterministic policy, not an LLM negotiator or a balanced
economic simulation. NPCs do not yet plan their own betrayals. Free-text
`negotiate`, `trade` and `assign` remain messages/saved instructions, not arbitrary
enforceable clauses; their feedback now says so explicitly. No mutual defense,
secret alliances, promised future deliveries, proxy attacks or reputation recovery.

## Verification

Regression coverage in `RndDiplomacyTest` exercises settlement, stale balances,
recipient funds, overflow, replay prevention, authority/timing, malformed offers,
private terms, spam limits, expiry, ceasefire movement/fire protection, explicit
betrayal, non-refunds, invasion/occupation checks, elimination and NPC decisions.
Existing final-victory regression also rejects diplomacy mutations after victory.
Live-browser evidence and test outcomes belong in `LIVE_DEVELOPMENT_LOG.md`.

The original deployment and paid database are outside this increment. State is
still one shared in-process development session with no durable saves.
