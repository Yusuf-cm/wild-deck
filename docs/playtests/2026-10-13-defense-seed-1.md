# Live full-match playtest — 13 October 2026

Deployment tested: `9f7ea8e94a17c0cfb1feeebad48a2934a80e66cb`. Normal match seed: **1**. Every action below was submitted through the live browser form and verified against visible output before the next command. No cards/resources were injected.

Result: **player won at round 15**; Asha, Brian and Mira were conquered. Post-win drawing was rejected and reloading retained round 15 and deck 147.

| Round | Command | Observed response |
| --- | --- | --- |
| 0 | `draw` | Drew Runed Obelisk [WD-116] |
| 0 | `pass` | Turn completed. Active: player; round 1 Opponent moves: [asha deployed Treasury for free, brian deployed Herbs for free, mira deployed Time Echo for free] Match ongoing. |
| 1 | `draw` | Drew Dire Wolf [WD-008] |
| 1 | `pass` | Turn completed. Active: player; round 2 Opponent moves: [asha deployed Black Plague for free, brian deployed Basilisk for free, mira deployed Desert Scouts for free] Match ongoing. |
| 2 | `deploy WD-008` | player deployed Dire Wolf for free |
| 2 | `move WD-008 C1` | Dire Wolf moved from W1 to C1 |
| 2 | `pass` | Turn completed. Active: player; round 3 Opponent moves: [asha deployed Magic Crystal for free, brian deployed Moon Stag for free, mira deployed Fishing Boat for free] Match ongoing. |
| 3 | `draw` | Drew Rebellion [WD-174] |
| 3 | `move WD-008 N1` | Dire Wolf moved from C1 to N1 |
| 3 | `attack asha` | Raid succeeded: captured Treasury; asha remains in the game (strength 10 vs 0; casualties: []) |
| 3 | `pass` | Turn completed. Active: player; round 4 Opponent moves: [asha: defensive reinforcement — asha deployed Sand Serpent for free, asha: Counterattack resolved at N1 (strength 11 vs 10; casualties: [player's Dire Wolf]), brian deployed Mirror of Revelation for free, mira deployed Temple Guards for free] Match ongoing. |
| 4 | `draw` | Drew Orb of Tides [WD-108] |
| 4 | `pass` | Turn completed. Active: player; round 5 Opponent moves: [asha deployed Chronicle Stone for free, brian deployed Airship for free, mira deployed Contract Seal for free] Match ongoing. |
| 5 | `draw` | Drew Mirror [WD-101] |
| 5 | `pass` | Turn completed. Active: player; round 6 Opponent moves: [asha: Proposal delivered to player: Shall we exchange information? (not accepted yet), asha deployed Lightning Chain for free, brian ordered Basilisk: guard our stores, brian deployed Migration for free, mira: Desert Scouts moved from E1 to N2, mira: Temple Guards moved from E1 to N2, mira: Attack repelled (strength 12 vs 14; casualties: [mira's Desert Scouts, mira's Temple Guards, brian's Basilisk]), mira ordered Fishing Boat: investigate ruins, mira deployed Shield of Dawn for free] Match ongoing. |
| 6 | `draw` | Drew Volcano [WD-162] |
| 6 | `pass` | Turn completed. Active: player; round 7 Opponent moves: [asha: Proposal delivered to player: Shall we exchange information? (not accepted yet), asha deployed Griffin for free, brian: Moon Stag moved from N2 to C1, brian ordered Migration: prepare a surprise, brian drew one private card, mira: Proposal delivered to asha: Shall we exchange information? (not accepted yet), mira drew one private card] Match ongoing. |
| 7 | `draw` | Drew Blood Moon [WD-084] |
| 7 | `pass` | Turn completed. Active: player; round 8 Opponent moves: [asha: Proposal delivered to player: I offer a temporary ceasefire; name your price. (not accepted yet), asha ordered Chronicle Stone: prepare a surprise, asha drew one private card, brian: Proposal delivered to mira: I offer a temporary ceasefire; name your price. (not accepted yet), brian deployed Earth Rift for free, mira ordered Contract Seal: scout neighboring territory, mira deployed Mithril Plate for free] Match ongoing. |
| 8 | `draw` | Drew Blacksmith [WD-041] |
| 8 | `pass` | Turn completed. Active: player; round 9 Opponent moves: [asha deployed Poison Mist for free, brian drew one private card, mira: Proposal delivered to brian: I offer a temporary ceasefire; name your price. (not accepted yet), mira ordered Mithril Plate: gather intelligence, mira deployed Mithril Ore for free] Match ongoing. |
| 9 | `draw` | Drew Cursed Harvest [WD-180] |
| 9 | `pass` | Turn completed. Active: player; round 10 Opponent moves: [asha: Sand Serpent moved from N1 to C1, asha: Griffin moved from N1 to C1, asha drew one private card, brian: Moon Stag moved from C1 to N1, brian: Raid succeeded: captured Black Plague; asha remains in the game (strength 2 vs 0; casualties: []), brian deployed Horn of Giants for free, mira ordered Time Echo: investigate ruins, mira drew one private card] Match ongoing. |
| 10 | `draw` | Drew Arcane Militia [WD-026] |
| 10 | `pass` | Turn completed. Active: player; round 11 Opponent moves: [asha: Sand Serpent moved from C1 to E1, asha: Griffin moved from C1 to E1, asha: Raid succeeded: captured Time Echo; mira remains in the game (strength 6 vs 0; casualties: []), asha ordered Chronicle Stone: investigate ruins, asha drew one private card, brian: Moon Stag moved from N1 to C1, brian deployed Wool for free, mira ordered Contract Seal: gather intelligence, mira deployed Thunderstorm for free] Match ongoing. |
| 11 | `deploy WD-026` | player deployed Arcane Militia for free |
| 11 | `move WD-026 C1` | Arcane Militia moved from W1 to C1 |
| 11 | `pass` | Turn completed. Active: player; round 12 Opponent moves: [asha ordered Poison Mist: investigate ruins, asha drew one private card, brian: Moon Stag moved from C1 to W1, brian: Won the raid; opponent survives, no asset available (strength 2 vs 0; casualties: []), brian drew one private card, mira: Proposal delivered to brian: I offer a temporary ceasefire; name your price. (not accepted yet), mira ordered Shield of Dawn: scout neighboring territory, mira drew one private card] Match ongoing. |
| 12 | `draw` | Drew Treasure Map [WD-193] |
| 12 | `move WD-026 N1` | Arcane Militia moved from C1 to N1 |
| 12 | `attack asha` | Raid succeeded: captured Magic Crystal; asha remains in the game (strength 8 vs 0; casualties: []) |
| 12 | `conquer asha` | Occupation started at N1; asha has its next turn to defend or repel. Conquest settles at the next completed round. |
| 12 | `pass` | Turn completed. Active: player; round 13 Opponent moves: [asha: Sand Serpent moved from E1 to C1, asha: Griffin moved from E1 to C1, asha deployed Soul Recall for free, brian: Moon Stag moved from W1 to C1, brian deployed Leather for free, mira deployed Alchemist for free] Match ongoing. |
| 13 | `draw` | Drew Underground Vault [WD-076] |
| 13 | `move WD-026 N2` | Arcane Militia moved from N1 to N2 |
| 13 | `move WD-018 N2` | Sand Serpent moved from C1 to N2 |
| 13 | `move WD-003 N2` | Griffin moved from C1 to N2 |
| 13 | `attack brian` | Raid succeeded: captured Herbs; brian remains in the game (strength 14 vs 0; casualties: []) |
| 13 | `conquer brian` | Occupation started at N2; brian has its next turn to defend or repel. Conquest settles at the next completed round. |
| 13 | `pass` | Turn completed. Active: player; round 14 Opponent moves: [brian ordered Earth Rift: gather intelligence, brian drew one private card, mira: Proposal delivered to brian: I offer a temporary ceasefire; name your price. (not accepted yet), mira ordered Contract Seal: guard our stores, mira drew one private card] Match ongoing. |
| 14 | `draw` | Drew Fireball [WD-081] |
| 14 | `move WD-026 E1` | Arcane Militia moved from N2 to E1 |
| 14 | `move WD-018 E1` | Sand Serpent moved from N2 to E1 |
| 14 | `move WD-003 E1` | Griffin moved from N2 to E1 |
| 14 | `move WD-015 E1` | Moon Stag moved from C1 to E1 |
| 14 | `attack mira` | Raid succeeded: captured Fishing Boat; mira remains in the game (strength 16 vs 0; casualties: []) |
| 14 | `conquer mira` | Occupation started at E1; mira has its next turn to defend or repel. Conquest settles at the next completed round. |
| 14 | `pass` | Turn completed. Active: player; round 15 Opponent moves: [mira ordered Contract Seal: gather intelligence, mira deployed Meteor Shower for free] MATCH WON by player |

## Findings

- Asha reinforced and counterattacked at round 3, killing the attacking Dire Wolf; Sand Serpent retained persistent damage at 1/11 strength.
- Asha was occupied at round 12 and conquered only after her response, at round 13; Brian followed at round 14 and Mira at round 15.
- Conquest preserved the locations and damage of inherited units.
- The full match had a long inactive rebuild stretch: draws in rounds 4–10 preceded the next usable military deployment. Most opening cards still lack executable effects.
- Winning this one seeded match verifies reachability and state transitions, not balance or replay value.
- Separate live seed 977 verified an opponent-owned Great Flood countering Wildfire on Moonwood during Asha's first turn.
