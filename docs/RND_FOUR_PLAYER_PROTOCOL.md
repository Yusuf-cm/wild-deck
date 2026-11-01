# Wild Deck R&D: four-player command experiments

This match is **a fresh game**, not the Cycle 18 historical fixture.

## Reproducibility
- Four seats: `player`, `asha`, `brian`, `mira`.
- Seed `20261010`; deck source `src/main/resources/data/rnd-200-card-pool.json`.
- 200 unique cards, 10 broad categories, 20 cards/category.
- Initial hands: 7 each; 172 in draw pile; board initially empty; Round 0.
- Each seat keeps an independent private hand. The player cannot inspect opponents' private cards.
- Player has first action, other three seats follow after pass.
- No AI key is required. Temporary opponent policy is deterministic deploy-one/draw/pass.

## Translator contract

The future AI translates a player's words **only** to structured proposals with these fields:

```json
{
  "actor": "player",
  "type": "PLAY",
  "cardId": "WD-076",
  "target": null,
  "intent": null,
  "costs": {}
}
```

Java checks hand ownership, active player, target, capabilities, payable costs, and legality. An AI cannot directly modify inventories or skip validation. This is a safe protocol even when a future AI proposes creative combinations.

Supported v1 proposal types: LOOK, DRAW, PLAY, ORDER, PASS. ABILITY checks the source and capability but **rejects application** pending a target-specific resolver. The command translator is intentionally minimal and does not claim to understand arbitrary natural language.

## Starting player hand (Java seeded test)

- Mind Veil — WD-087
- Blood Moon — WD-084
- Underground Vault — WD-076
- Wildfire — WD-171
- Arcane Tower — WD-073
- Scrying — WD-088
- Solar Eclipse — WD-166

## Human playtest protocol

1. The human describes a freeform attempt or invokes a card.
2. The referee translates it into an explicit proposed action; it is allowed to represent an unknown action rather than invent its effect.
3. Check card ownership, capabilities, resources, physical access, knowledge, contracts, target legality, cost and timing.
4. If needed, implement general rule behavior, not a card-specific forced outcome.
5. Add regression tests: normal case, invalid cost, hidden information, round timing, another player's use.
6. Apply the action and create an event. Separate *attempted*, *resolved* and *failed*.
7. On pass, Asha, Brian and Mira independently draw/play only from their own hidden hand.
8. Record the exact seed and action event sequence for replay.

## Development backlog

- [x] 200-card catalog, seeded shuffle, privacy, action proposal boundary
- [x] Four-seat turn-taking and first scripted opponent actions
- [x] Browser-accessible match mode
- [ ] Independent save slots and session persistence
- [ ] General resource and economy actions in R&D engine
- [ ] Creature deployment and taming with discovery and resistance
- [ ] Targeted spell and event resolution with Mana, duration and counters
- [ ] Equipment forging, mutations and multi-card combinations
- [ ] Scouting, geography, movement, other players' worlds
- [ ] Diplomacy, treaties, betrayals and consent
- [ ] Actual war with reactions and non-scripted damage
- [ ] General natural-language translation via optional AI, never given mutation authority
- [ ] Replay every supported freeform human command as an automated fixture

**Known limitation:** The shared Render instance is an R&D demo, not a private persistent multiplayer game. The demo resets on a process restart.
