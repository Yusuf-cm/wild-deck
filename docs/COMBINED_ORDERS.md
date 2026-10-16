# Combined orders v1: forest flush

```text
combine <fire-source-name-or-id> with <troop-name-or-id> to flush <forest-id>
interactions
```

The live match resolves one bounded combination: **FIRE + IGNITE**, an adjacent
**ATTACK** unit, and **FLAMMABLE FOREST** terrain. Names are not recipes: any
distinct owned cards meeting these capabilities can supply the roles. This is
not a natural-language interpreter or an arbitrary combination engine.

## Requirements and costs

- Active surviving actor in an unfinished match; main and hostile actions unused.
- Fire source in the actor's hand or on its board. One-shot sources must be used
  from hand, and local sources must actually reach the target under normal rules.
  WORLD_EFFECT sources have global reach; this never makes troops remote.
- A different owned living mobile ATTACK troop must be deployed at an adjacent
  region with positive effective strength. Its region blocks one forced exit.
- Target is a flammable forest, is not already burning, has no pending flush,
  and contains at least one vulnerable enemy mobile unit. Fixed structures and
  fireproof-only defenses do not justify a flush.
- Cost: one main action, one hostile action, 1 Wealth for coordination, plus the
  normal source consumption and Mana cost (2 for a MAGIC source).
- Ceasefires apply to the affected region, including partners' deployed cards.
  Invalid orders change nothing: no fire, card loss, fees or action expenditure.

## What happens

Ignition changes the actual terrain immediately, with ordinary fire access and
strength effects. A public pending interaction records the supporting unit,
blocking region, target and each initially threatened kingdom's completed-turn
counter. The defender can respond before displacement: water, fireproof gear,
movement, diplomacy, or removing the supporting troop can matter.

At a completed-round boundary, the engine first checks support/fire/ceasefires,
then waits until each originally threatened surviving kingdom has actually
completed another turn. A late-round order cannot skip a defender's turn just
because the round number advanced. Flush settlement precedes conquest checks.

Each remaining vulnerable mobile unit belonging to those originally threatened
kingdoms retreats through one eligible neighboring region:

1. Not the supporting troop's blocking region and not burning.
2. Not a peace partner's capital, and no effective hostile ATTACK/DEFEND force
   currently deployed there.
3. Prefer the defender's own original capital, then initially neutral regions,
   then other regions; break ties by region ID. This is deterministic defender
   routing, not an attacker choosing where to teleport enemies.

Equipment follows its carrier. Forced retreat spends that unit's movement in
the resolution round. A region can be locally empty but belong to another ruler;
retreat into it does not grant ownership, a raid win or an occupation foothold.
The blockade applies only to this forced-retreat resolver, not all normal moves.

If no escape is legal, the unit is **PINNED** and stays alive at its location.
There is no automatic damage, destruction, capture, or conquest. Fireproof units
and fixed assets remain. New third-party entrants are not forced out without
their own response window; the flush affects only the named threatened kingdoms.

## Counters and risks

- Extinguishing clears both ordinary fire and the pending flush immediately.
  Reigniting later is not a revival of the cancelled order.
- Losing/moving the supporting unit or reducing it to ineffective strength
  cancels pressure at settlement. Newly accepted peace can also cancel pressure.
- A defender can move away during its turn; it is not moved a second time.
- Already paid supplies/cards are not refunded on a counter or cancellation.
- Fire persists after a flush. It blocks the attacker's ordinary troops too;
  extinguish it before advancing, or use genuinely fireproof troops/equipment.
- Last-kingdom victory clears pending combined orders and locks new mutations.

## Current limits

No automatic fire spread, burn damage, suppression damage, reaction subturn UI,
player-selected retreat path, siege physics, arbitrary free-form effect, or NPC
planning of combined offensives. NPCs can use their existing owned water counters
and normal movement/defense logic. Full-match and browser evidence is recorded in
`LIVE_DEVELOPMENT_LOG.md`, not inferred from unit-test success.
