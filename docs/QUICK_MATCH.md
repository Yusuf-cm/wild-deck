# Wild Deck — Quick Match

Open https://wild-deck-world-playtest.onrender.com/ and enter:

```text
new quick game 1
```

This starts the 55-card supported-action pool. Use `new four player game 1` for the full 200-card R&D pool. The optional seed controls the shuffled deal. Both modes currently share one in-memory demo session across visitors.

## Commands

```text
cards
world
status
deploy <card-name-or-id>
attach <equipment-name-or-id> to <unit-name-or-id>
move <unit-name-or-id> to <region-id>
heal <healer-name-or-id> to <unit-name-or-id>
heal <healer-name-or-id> to <unit-name-or-id> with gold
use <event-or-spell> on <region-id>
scry <kingdom>
attack <kingdom>
repel <kingdom>
conquer <kingdom>
pass
events
deals
offer asha peace 3
offer asha give 2 gold for 1 wealth
accept D1
reject D1
cancel D1
betray asha
combine <fire-source> with <troop> to flush N1
interactions
```

Names may contain spaces. `cards` shows your owned cards, supported actions and effects; `world` shows region IDs and borders.

`deals` shows your executable offers and public ceasefires. Replace D1 with the
actual returned ID. See [DIPLOMACY.md](DIPLOMACY.md) for settlement, expiry,
opponent valuation and betrayal rules. Old free-text `trade`/`negotiate` commands
remain messages only; use `offer` for executable terms.

`combine` executes the first capability-based world interaction: fire plus
adjacent troop pressure can flush vulnerable defenders from a forest after their
actual response turn. See [COMBINED_ORDERS.md](COMBINED_ORDERS.md) for costs,
escape rules, water counters and the risk of blocking your own advance.

## Action and supply rules

One main card action per turn: draw, deploy, equip directly from hand, or use a hand spell/event. Management of already-deployed cards can continue subject to timing, position and resources.

- Move once per unit per round along one border.
- Attach only owned equipment to an owned active unit at the same location. Hand equipment starts at your capital; direct attachment consumes the main card action. Already-deployed loose gear can be attached through management.
- Equipment travels with its carrier and drops when the carrier dies.
- A deployed healer treats a co-located owned wounded biological unit once per healer per round. Cost is 1 Wealth or 2 Gold; `with gold` selects Gold. If Wealth is insufficient, available Gold can pay. Treatment restores up to the healer's amount, never above the original strength. It cannot resurrect dead units or heal undead/non-biological units.
- Healing Rain spends its hand card, main action and 2 Mana. It heals biological units of every player in the target region and extinguishes fire. It can benefit enemies too.
- Spells/events are one-shot hand cards; quick mode rejects deploying them as permanent assets.
- Each surviving kingdom receives +1 Wealth at a completed round. Producer structures add their printed income then. Values are provisional.
- Fireproof Mail grants its carrier fire resistance; it does not guarantee survival against an army.
- Raids need local forces. Conquest requires a cleared capital held through the defender's response. Last independent kingdom wins.

## Reproducible examples

- Seed 32: Fireproof Mail + Wildfire + Royal Lancers demonstrates entering burning Moonwood.
- Seed 192: medical/support build demonstrates actual wounds, paid treatment and dropped equipment.
- Seed 1: a recorded full quick match reached victory at round 11.

The transcripts are in `docs/playtests/2026-10-13-quick-card-actions.md`. These examples are tests, not guaranteed winning strategies.
