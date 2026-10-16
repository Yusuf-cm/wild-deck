# Combined orders — live playtests, 2026-10-16

Build `bf642c996727509348213bdd0e5c40a03650cf0f`; Render deployment `dep-db8uq1k9v7es739b35kg` reached live at 09:25:57 UTC / 12:25:57 EAT. Java CI run 38879902611 passed 166 tests, zero failures/errors/skips.

All commands below were submitted through the public live browser form. Both games used normal seeded quick hands with no injected cards, resources or state. Each action waited for its expected new response before continuing. This is gameplay verification, not only source inspection or a simulation.

## Seed 105 — natural opponent water counter

| Step | Command | Observed result |
| --- | --- | --- |
| 1 | `new quick game 105` | Seven normal private cards; deck 27 |
| 2 | `deploy WD-028` | Owned Spearmen deployed at W1 |
| 3 | `move WD-028 C1` | Spearmen moved W1 -> C1 |
| 4 | `pass` | R1; Asha deployed Sand Serpent; human Wealth 1 |
| 5 | `combine WD-171 with WD-028 to flush N1` | X1 prepared; Wildfire consumed, N1 burning, Wealth 0; Sand Serpent not yet displaced |
| 6 | `pass` | R2; Asha used her owned Great Flood, extinguishing N1 during her actual turn |
| 7 | `interactions` | X1 COUNTERED; no forced retreat; no refund; Sand Serpent remained at N1 |

## Seed 1598 — complete live match

The 43 successful game commands were first rehearsed headlessly by `CombinedMatchPlaytestTest` using only the human's hand and public positions. The browser then replayed the plan through victory, plus match creation, an interaction inspection, a rejected burning-entry attempt and a post-victory mutation check: **47 verified form responses**. The table gives the live command/result sequence; round-pass summaries are shortened.

| Step | Round before | Command | Observed response |
| --- | --- | --- | --- |
| 1 | 0 | `new quick game 1598` | Normal seeded match created; deck 27 |
| 2 | 0 | `offer mira peace 10` | Offer D1 sent to mira: no resource exchange; peace 10 completed rounds; expires at round 2. Nothing transferred until acceptance; resources are not reserved. |
| 3 | 0 | `deploy WD-038` | player deployed War Chariots for free |
| 4 | 0 | `move WD-038 C1` | War Chariots moved from W1 to C1 |
| 5 | 0 | `pass` | Turn completed; active player; round 1; match ongoing |
| 6 | 1 | `combine WD-171 with WD-038 to flush N1` | X1: combined flush prepared at N1; FIRE + IGNITE from Wildfire, ATTACK pressure from War Chariots at C1. -1 Wealth; main and hostile actions spent. Awaiting actual defender turns: [asha]. Keep support in place; water cancels; fireproof units hold. Fire still blocks your ordinary advance. |
| 7 | 1 | `pass` | Turn completed; active player; round 2; match ongoing |
| 8 | 2 | `interactions` | X1 RESOLVED after defender response; Asha's Shieldbearers and Cave Wyrm both RETREATED N1 -> N2. Fire persisted; no automatic raid, capture or conquest. |
| 9 | 2 | `move WD-038 N1` | Rejected: destination is burning; source stayed C1, no movement spent |
| 10 | 2 | `use WD-165 on N1` | Great Flood extinguished N1; entry restored; strength penalty removed |
| 11 | 2 | `move WD-038 N1` | War Chariots moved from C1 to N1 |
| 12 | 2 | `attack asha` | Won the raid; opponent survives, no asset available (strength 11 vs 0; casualties: []) |
| 13 | 2 | `conquer asha` | Occupation started at N1; asha has its next turn to defend or repel. Conquest settles at a completed round after that response. |
| 14 | 2 | `pass` | Turn completed; active player; round 3; match ongoing |
| 15 | 3 | `deploy WD-029` | player deployed Siege Infantry for free |
| 16 | 3 | `move WD-038 N2` | War Chariots moved from N1 to N2 |
| 17 | 3 | `move WD-029 C1` | Siege Infantry moved from W1 to C1 |
| 18 | 3 | `pass` | Turn completed; active player; round 4; match ongoing |
| 19 | 4 | `deploy WD-026` | player deployed Arcane Militia for free |
| 20 | 4 | `move WD-029 N2` | Siege Infantry moved from C1 to N2 |
| 21 | 4 | `move WD-026 C1` | Arcane Militia moved from W1 to C1 |
| 22 | 4 | `pass` | Turn completed; active player; round 5; match ongoing |
| 23 | 5 | `deploy WD-011` | player deployed Thunder Roc for free |
| 24 | 5 | `move WD-026 N2` | Arcane Militia moved from C1 to N2 |
| 25 | 5 | `move WD-011 C1` | Thunder Roc moved from W1 to C1 |
| 26 | 5 | `pass` | Turn completed; active player; round 6; match ongoing |
| 27 | 6 | `deploy WD-019` | player deployed Bone Dragon for free |
| 28 | 6 | `move WD-011 N2` | Thunder Roc moved from C1 to N2 |
| 29 | 6 | `move WD-019 C1` | Bone Dragon moved from W1 to C1 |
| 30 | 6 | `attack brian` | Raid succeeded: captured Herbalist; brian remains in the game (strength 4 vs 0; casualties: []) |
| 31 | 6 | `pass` | Turn completed; active player; round 7; match ongoing |
| 32 | 7 | `betray mira` | Ceasefire broken with mira. Public broken promises: 1. No resource refund; hostile actions are now legal if normal combat rules allow. |
| 33 | 7 | `draw` | Drew Mercenaries [WD-034] |
| 34 | 7 | `move WD-011 E1` | Thunder Roc moved from N2 to E1 |
| 35 | 7 | `move WD-019 E1` | Bone Dragon moved from C1 to E1 |
| 36 | 7 | `attack mira` | Raid succeeded: captured Gold Mine; mira remains in the game (strength 7 vs 0; casualties: []) |
| 37 | 7 | `pass` | Turn completed; active player; round 8; match ongoing |
| 38 | 8 | `deploy WD-034` | player deployed Mercenaries for free |
| 39 | 8 | `move WD-034 C1` | Mercenaries moved from W1 to C1 |
| 40 | 8 | `attack mira` | Raid succeeded: captured Reinforced Shield; mira remains in the game (strength 4 vs 0; casualties: []) |
| 41 | 8 | `pass` | Turn completed; active player; round 9; match ongoing |
| 42 | 9 | `draw` | Drew Dire Wolf [WD-008] |
| 43 | 9 | `move WD-034 E1` | Mercenaries moved from C1 to E1 |
| 44 | 9 | `attack mira` | Raid succeeded: captured Granary; mira remains in the game (strength 11 vs 0; casualties: []) |
| 45 | 9 | `conquer mira` | Occupation started at E1; mira has its next turn to defend or repel. Conquest settles at a completed round after that response. |
| 46 | 9 | `pass` | Turn completed; active player; round 10; MATCH WON by player |
| 47 | 10 | `combine WD-171 with WD-038 to flush N1` | Rejected: Match finished. Winner: player. Full match view unchanged. |

### Observed opponent consequences

- Mira accepted the ten-round ceasefire on her R0 turn. The player explicitly betrayed it at R7 before attacking her.
- Asha deployed Cave Wyrm during the response window; it was also vulnerable and retreated with Shieldbearers when the flush resolved at R2.
- Ordinary War Chariots could not enter the forest until the human's Great Flood cleared the fire. The previously rejected move had not spent their movement allowance.
- At R2 Asha deployed Spearmen and counterattacked the occupying War Chariots. Spearmen died and the chariots were wounded. Brian deployed Stoneburrower and counterattacked the two refugees at N2, killing them.
- Brian later killed the advancing War Chariots, Siege Infantry and Arcane Militia in separate response turns. Mira took advantage, captured Brian's Trading Post and occupied N2.
- The human captured Herbalist; Mira subsequently conquered Brian. The human then betrayed Mira, raided her original capital, captured its economy/equipment assets, and declared occupation at R9.
- Mira counter-invaded W1 at R9, but the earlier occupation of her capital survived her actual response turn and settled at R10. The human became the last independent kingdom. This was not an instant-win side effect of fire.

### Final state and locks

- Round 10; winner player; deck 23; Gold 28 / Wealth 24 / Mana 0.
- Asha, Brian and Mira all CONQUERED; pending occupations and pending combinations empty.
- One public broken promise for the human; captured Fireproof Mail still attached to Moon Stag.
- Post-win combined order rejected, with exact before/after match-view equality.
- Browser reload retained the completed match in the running process; this is not durable persistence.

## Limits

Live coverage here: natural water counter, capability composition, real retreat, actual response timing, own-fire access risk, diplomacy, combat losses, capture/conquest, complete victory and terminal mutation lock. Automated tests additionally cover custom renamed cards, invalid inputs/atomicity, costs, range, fireproof gear, equipment linkage, no-escape pinning, moved/lost support, peace cancellation and late-round defender fairness. Those edge cases were not all separately browser-tested.

No claim of balance, general free-form creativity, durable saves, multiplayer isolation or NPC planning of combined offensives. Story/collections/card creation remain deferred.

