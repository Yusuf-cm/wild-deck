# Live quick-card-actions playtest — 13 October 2026

Build: `550156e2398feaa6021e1cead029e407d81a4488`. Three natural quick-match seeds; no runtime cards, resources or wounds injected. Every recorded command was submitted through the live browser and its response verified before continuing.

## Seed 32 — Equipment supplies a terrain property

Royal Lancers equipped Fireproof Mail, carried it W1 -> C1 -> N1, and entered N1 while world showed Moonwood BURNING. Duplicate attachment was rejected.

## Seed 192 — Income, damage, healing and salvaged gear

Deployed War Chariots, Fireproof Mail, Healer and Trading Post. Enemy combat eventually killed the carrier and wounded Sand Serpent to 1/11. Healer restored it to 3/11 for 1 Wealth. Gold was 10 from Trading Post; Wealth was 12 after treatment. Retrying with Gold was rejected by the treatment cooldown without spending resources. Loose Fireproof Mail was then reattached to Sand Serpent at W1. This scenario ended at round 13, not a match victory.

## Seed 1 — Complete quick match

Reached player victory at round 11 after repeated battles and a broken initial occupation attempt. All other kingdoms were conquered. Final deck 17, Gold 14, Wealth 37; inherited equipment remained attached. A post-win draw was rejected and reload preserved the outcome in the running process.

## Verified command transcript

| Seed | Round | Command | Observed response |
| --- | --- | --- | --- |
| 32 | 0 | `deploy Royal Lancers` | player deployed Royal Lancers for free |
| 32 | 1 | `pass` | Turn completed. Active: player; round 1 Opponent moves: [asha deployed Gold Mine for free (round production: +2 gold per round), brian deployed Rock Troll for free, mira deployed Pikemen for free] Match ongoing. |
| 32 | 1 | `attach Fireproof Mail to Royal Lancers` | Attached Fireproof Mail to Royal Lancers (armor 1, attack bonus 0, FIREPROOF) |
| 32 | 1 | `move Royal Lancers to C1` | Royal Lancers moved from W1 to C1 |
| 32 | 2 | `pass` | Turn completed. Active: player; round 2 Opponent moves: [asha deployed Sky Riders for free, brian deployed Arcane Tower for free (round production: +1 mana per round), mira deployed War Elephants for free] Match ongoing. |
| 32 | 2 | `use Wildfire on N1` | Wildfire ignited N1; non-fireproof entry blocked; local strength -1 |
| 32 | 2 | `move Royal Lancers to N1` | Royal Lancers moved from C1 to N1 |
| 32 | 2 | `world` | World — round 2  /  Current: player N1 Moonwood [Forest] owner=asha borders=[N2, C1] BURNING N2 Iron Peaks [Mountain] owner=brian borders=[N1, C1, E1] C1 Crossroads [Plains] owner=neutral borders=[N1, N2, W1, E1, S1] W1 Runebank [Highland] owner=player borders=[C1, S1] E1 Ash Coast [Coast] owner=mira borders=[N2, C1, S1] S1 Sunken Ruins [Ruins] owner=neutral borders=[W1, C1, E1] Your deployed cards (use IDs or full names): WD-030 Royal Lancers @ N1 WD-133 Fireproof Mail @ N1 |
| 192 | 0 | `deploy WD-038` | player deployed War Chariots for free |
| 192 | 1 | `pass` | Turn completed. Active: player; round 1 Opponent moves: [asha deployed Spearmen for free, brian deployed Undead Cavalry for free, mira deployed Crossbowmen for free] Match ongoing. |
| 192 | 1 | `attach WD-133 to WD-038` | Attached Fireproof Mail to War Chariots (armor 1, attack bonus 0, FIREPROOF) |
| 192 | 2 | `pass` | Turn completed. Active: player; round 2 Opponent moves: [asha deployed Shieldbearers for free, brian deployed Pikemen for free, mira deployed War Elephants for free] Match ongoing. |
| 192 | 2 | `deploy WD-058` | player deployed Healer for free |
| 192 | 3 | `pass` | Turn completed. Active: player; round 3 Opponent moves: [asha deployed Manticore for free, brian deployed Stoneburrower for free, mira deployed Herbalist for free] Match ongoing. |
| 192 | 3 | `deploy WD-071` | player deployed Trading Post for free (round production: +1 gold per round) |
| 192 | 4 | `pass` | Turn completed. Active: player; round 4 Opponent moves: [asha deployed Leviathan for free, brian deployed Bone Dragon for free, mira deployed Basilisk for free] Match ongoing. |
| 192 | 4 | `deploy WD-018` | player deployed Sand Serpent for free |
| 192 | 5 | `pass` | Turn completed. Active: player; round 5 Opponent moves: [asha deployed Rock Troll for free, brian deployed Mercenaries for free, mira deployed Iron Sword for free, mira: Attached Iron Sword to War Elephants (armor 0, attack bonus 1)] Match ongoing. |
| 192 | 5 | `deploy WD-040` | player deployed Temple Guards for free |
| 192 | 6 | `pass` | Turn completed. Active: player; round 6 Opponent moves: [asha: Proposal delivered to player: Shall we exchange information? (not accepted yet), asha deployed Gold Mine for free (round production: +2 gold per round), brian ordered Pikemen: guard our stores, brian deployed Cave Wyrm for free, mira: Crossbowmen moved from E1 to N2, mira: War Elephants moved from E1 to N2, mira: Basilisk moved from E1 to N2, mira: Attack repelled (strength 17 vs 44; casualties: [mira's Crossbowmen, mira's War Elephants, mira's Basilisk, brian's Undead Cavalry]), mira ordered Herbalist: investigate ruins, mira deployed Heavy Knights for free] Match ongoing. |
| 192 | 6 | `deploy WD-001` | player deployed Hydra for free |
| 192 | 7 | `pass` | Turn completed. Active: player; round 7 Opponent moves: [asha: Proposal delivered to player: Shall we exchange information? (not accepted yet), asha deployed Cavalry for free, brian: Pikemen moved from N2 to C1, brian: Stoneburrower moved from N2 to C1, brian: Bone Dragon moved from N2 to C1, brian: Mercenaries moved from N2 to C1, brian: Cave Wyrm moved from N2 to C1, brian ordered Cave Wyrm: prepare a surprise, brian drew one private card, mira: Proposal delivered to asha: Shall we exchange information? (not accepted yet), mira drew one private card] Match ongoing. |
| 192 | 7 | `draw` | Drew Ancient Titan [WD-002] |
| 192 | 8 | `pass` | Turn completed. Active: player; round 8 Opponent moves: [asha: Proposal delivered to player: I offer a temporary ceasefire; name your price. (not accepted yet), asha ordered Gold Mine: prepare a surprise, asha drew one private card, brian: Proposal delivered to mira: I offer a temporary ceasefire; name your price. (not accepted yet), brian deployed Dire Wolf for free, mira: Heavy Knights moved from E1 to C1, mira ordered Iron Sword: scout neighboring territory, mira deployed Sky Riders for free] Match ongoing. |
| 192 | 8 | `deploy WD-002` | player deployed Ancient Titan for free |
| 192 | 9 | `pass` | Turn completed. Active: player; round 9 Opponent moves: [asha deployed Mana Shrine for free (round production: +1 mana per round), brian drew one private card, mira: Proposal delivered to brian: I offer a temporary ceasefire; name your price. (not accepted yet), mira ordered Sky Riders: gather intelligence, mira deployed Moon Stag for free] Match ongoing. |
| 192 | 9 | `draw` | Drew Desert Scouts [WD-035] |
| 192 | 10 | `pass` | Turn completed. Active: player; round 10 Opponent moves: [asha: Spearmen moved from N1 to C1, asha: Shieldbearers moved from N1 to C1, asha: Manticore moved from N1 to C1, asha: Leviathan moved from N1 to C1, asha: Rock Troll moved from N1 to C1, asha: Cavalry moved from N1 to C1, asha drew one private card, brian: Pikemen moved from C1 to N1, brian: Stoneburrower moved from C1 to N1, brian: Bone Dragon moved from C1 to N1, brian: Mercenaries moved from C1 to N1, brian: Cave Wyrm moved from C1 to N1, brian: Dire Wolf moved from N2 to N1, brian: Raid succeeded: captured Gold Mine; asha remains in the game (strength 37 vs 0; casualties: []), brian: Occupation started at N1; asha has its next turn to defend or repel. Conquest settles at a completed round after that response., brian deployed River Serpent for free, mira: Heavy Knights moved from C1 to N2, mira: Sky Riders moved from E1 to N2, mira: Moon Stag moved from E1 to N2, mira: Won the raid; opponent survives, no asset available (strength 24 vs 9; casualties: [brian's River Serpent]), mira: Occupation started at N2; brian has its next turn to defend or repel. Conquest settles at a completed round after that response., mira ordered Moon Stag: investigate ruins, mira drew one private card, mira: Attached Iron Sword to Heavy Knights (armor 0, attack bonus 1)] Match ongoing. |
| 192 | 10 | `deploy WD-035` | player deployed Desert Scouts for free |
| 192 | 11 | `pass` | Turn completed. Active: player; round 11 Opponent moves: [asha: Spearmen moved from C1 to E1, asha: Shieldbearers moved from C1 to E1, asha: Manticore moved from C1 to E1, asha: Leviathan moved from C1 to E1, asha: Rock Troll moved from C1 to E1, asha: Cavalry moved from C1 to E1, asha: Raid succeeded: captured Herbalist; mira remains in the game (strength 45 vs 0; casualties: []), asha: Occupation started at E1; mira has its next turn to defend or repel. Conquest settles at a completed round after that response., asha ordered Mana Shrine: investigate ruins, asha drew one private card, brian: Pikemen moved from N1 to C1, brian: Stoneburrower moved from N1 to C1, brian: Bone Dragon moved from N1 to C1, brian: Mercenaries moved from N1 to C1, brian: Cave Wyrm moved from N1 to C1, brian: Dire Wolf moved from N1 to C1, brian drew one private card, mira ordered Sky Riders: gather intelligence, mira deployed Reinforced Shield for free] Match ongoing. |
| 192 | 11 | `draw` | Drew Royal Lancers [WD-030] |
| 192 | 12 | `pass` | Turn completed. Active: player; round 12 Opponent moves: [asha: Attached Reinforced Shield to Spearmen (armor 2, attack bonus 0), asha ordered Reinforced Shield: investigate ruins, asha drew one private card] Match ongoing. |
| 192 | 12 | `deploy WD-030` | player deployed Royal Lancers for free |
| 192 | 13 | `pass` | Turn completed. Active: player; round 13 Opponent moves: [asha: Spearmen moved from E1 to C1, asha: Shieldbearers moved from E1 to C1, asha: Manticore moved from E1 to C1, asha: Leviathan moved from E1 to C1, asha: Rock Troll moved from E1 to C1, asha: Cavalry moved from E1 to C1, asha: Heavy Knights moved from N2 to C1, asha: Sky Riders moved from N2 to C1, asha: Moon Stag moved from N2 to C1, asha: Pikemen moved from C1 to W1, asha: Stoneburrower moved from C1 to W1, asha: Bone Dragon moved from C1 to W1, asha: Mercenaries moved from C1 to W1, asha: Cave Wyrm moved from C1 to W1, asha: Dire Wolf moved from C1 to W1, asha: Attack repelled (strength 37 vs 44; casualties: [asha's Pikemen, asha's Mercenaries, asha's Stoneburrower, asha's Bone Dragon, asha's Cave Wyrm, asha's Dire Wolf, player's War Chariots, player's Temple Guards, player's Desert Scouts, player's Royal Lancers]), asha deployed Militia for free] Match ongoing. |
| 192 | 13 | `draw` | Drew Phoenix [WD-010] |
| 192 | 13 | `heal WD-058 to WD-018` | Healer healed Sand Serpent by 2; strength 3/11 (-1 Wealth) |
| 192 | 13 | `heal Healer to Sand Serpent with gold` | healer already treated a unit this round |
| 192 | 13 | `attach Fireproof Mail to Sand Serpent` | Attached Fireproof Mail to Sand Serpent (armor 1, attack bonus 0, FIREPROOF) |
| 1 | 0 | `deploy WD-032` | player deployed Sappers for free |
| 1 | 0 | `move WD-032 C1` | Sappers moved from W1 to C1 |
| 1 | 1 | `pass` | Turn completed. Active: player; round 1 Opponent moves: [asha deployed Fireproof Mail for free, brian deployed Temple Guards for free, mira deployed Ancient Titan for free] Match ongoing. |
| 1 | 1 | `deploy WD-031` | player deployed Shieldbearers for free |
| 1 | 1 | `move WD-032 N1` | Sappers moved from C1 to N1 |
| 1 | 1 | `move WD-031 C1` | Shieldbearers moved from W1 to C1 |
| 1 | 1 | `attack asha` | Raid succeeded: captured Fireproof Mail; asha remains in the game (strength 5 vs 0; casualties: []) |
| 1 | 1 | `conquer asha` | Occupation started at N1; asha has its next turn to defend or repel. Conquest settles at a completed round after that response. |
| 1 | 2 | `pass` | Turn completed. Active: player; round 2 Opponent moves: [asha: defensive reinforcement — asha deployed Sand Serpent for free, asha: Counterattack resolved at N1 (strength 11 vs 5; casualties: [player's Sappers]), brian deployed Stoneburrower for free, mira deployed Hydra for free] Match ongoing. |
| 1 | 2 | `deploy WD-039` | player deployed Crossbowmen for free |
| 1 | 2 | `move WD-031 N1` | Shieldbearers moved from C1 to N1 |
| 1 | 2 | `move WD-039 C1` | Crossbowmen moved from W1 to C1 |
| 1 | 3 | `pass` | Turn completed. Active: player; round 3 Opponent moves: [asha: defensive reinforcement — asha deployed War Elephants for free, asha: Counterattack resolved at N1 (strength 13 vs 4; casualties: [player's Shieldbearers]), brian deployed Ash Drake for free, mira deployed Reinforced Shield for free, mira: Attached Reinforced Shield to Ancient Titan (armor 2, attack bonus 0)] Match ongoing. |
| 1 | 3 | `deploy WD-014` | player deployed Manticore for free |
| 1 | 3 | `move WD-039 N1` | Crossbowmen moved from C1 to N1 |
| 1 | 3 | `move WD-014 C1` | Manticore moved from W1 to C1 |
| 1 | 4 | `pass` | Turn completed. Active: player; round 4 Opponent moves: [asha: defensive reinforcement — asha deployed Longbowmen for free, asha: Counterattack resolved at N1 (strength 15 vs 3; casualties: [asha's War Elephants, player's Crossbowmen]), brian deployed Thunder Roc for free, mira deployed War Chariots for free] Match ongoing. |
| 1 | 4 | `deploy WD-004` | player deployed Basilisk for free |
| 1 | 4 | `move WD-014 N1` | Manticore moved from C1 to N1 |
| 1 | 4 | `move WD-004 C1` | Basilisk moved from W1 to C1 |
| 1 | 5 | `pass` | Turn completed. Active: player; round 5 Opponent moves: [asha: defensive reinforcement — asha deployed Bone Dragon for free, asha: Counterattack resolved at N1 (strength 15 vs 7; casualties: [asha's Longbowmen, player's Manticore]), brian deployed River Serpent for free, mira deployed Herbalist for free] Match ongoing. |
| 1 | 5 | `deploy WD-012` | player deployed Cave Wyrm for free |
| 1 | 5 | `move WD-004 N1` | Basilisk moved from C1 to N1 |
| 1 | 5 | `move WD-012 C1` | Cave Wyrm moved from W1 to C1 |
| 1 | 6 | `pass` | Turn completed. Active: player; round 6 Opponent moves: [asha: Counterattack resolved at N1 (strength 8 vs 6; casualties: [asha's Sand Serpent, player's Basilisk]), asha: Proposal delivered to player: Shall we exchange information? (not accepted yet), asha deployed Gold Mine for free (round production: +2 gold per round), brian ordered Stoneburrower: guard our stores, brian deployed Griffin for free, mira: Ancient Titan moved from E1 to N2, mira: Hydra moved from E1 to N2, mira: War Chariots moved from E1 to N2, mira: Attack repelled (strength 18 vs 39; casualties: [mira's War Chariots, mira's Ancient Titan, mira's Hydra, brian's Temple Guards, brian's Stoneburrower]), mira ordered Reinforced Shield: investigate ruins, mira deployed Royal Guard for free] Match ongoing. |
| 1 | 6 | `draw` | Drew Spearmen [WD-028] |
| 1 | 6 | `move WD-012 N1` | Cave Wyrm moved from C1 to N1 |
| 1 | 6 | `attack asha` | Raid succeeded: captured Gold Mine; asha remains in the game (strength 5 vs 2; casualties: [asha's Bone Dragon]) |
| 1 | 7 | `pass` | Turn completed. Active: player; round 7 Opponent moves: [asha: Proposal delivered to player: Shall we exchange information? (not accepted yet), asha drew one private card, brian: Ash Drake moved from N2 to C1, brian: Thunder Roc moved from N2 to C1, brian: River Serpent moved from N2 to C1, brian: Griffin moved from N2 to C1, brian ordered Ash Drake: prepare a surprise, brian drew one private card, mira: Proposal delivered to asha: Shall we exchange information? (not accepted yet), mira drew one private card] Match ongoing. |
| 1 | 7 | `deploy WD-028` | player deployed Spearmen for free |
| 1 | 7 | `move WD-028 C1` | Spearmen moved from W1 to C1 |
| 1 | 7 | `attack asha` | Won the raid; opponent survives, no asset available (strength 3 vs 0; casualties: []) |
| 1 | 8 | `pass` | Turn completed. Active: player; round 8 Opponent moves: [asha: defensive reinforcement — asha deployed Royal Lancers for free, asha: Counterattack resolved at N1 (strength 3 vs 3; casualties: [asha's Royal Lancers, player's Cave Wyrm]), asha: Proposal delivered to player: I offer a temporary ceasefire; name your price. (not accepted yet), brian: Proposal delivered to mira: I offer a temporary ceasefire; name your price. (not accepted yet), brian deployed Undead Cavalry for free, mira: Royal Guard moved from E1 to C1, mira ordered Herbalist: scout neighboring territory, mira deployed Pikemen for free] Match ongoing. |
| 1 | 8 | `draw` | Drew Forest Guardian [WD-020] |
| 1 | 8 | `move WD-028 N1` | Spearmen moved from C1 to N1 |
| 1 | 8 | `attack asha` | Won the raid; opponent survives, no asset available (strength 10 vs 0; casualties: []) |
| 1 | 8 | `conquer asha` | Occupation started at N1; asha has its next turn to defend or repel. Conquest settles at a completed round after that response. |
| 1 | 9 | `pass` | Turn completed. Active: player; round 9 Opponent moves: [asha drew one private card, brian drew one private card, mira: Proposal delivered to brian: I offer a temporary ceasefire; name your price. (not accepted yet), mira ordered Pikemen: gather intelligence, mira drew one private card] Match ongoing. |
| 1 | 9 | `deploy WD-020` | player deployed Forest Guardian for free |
| 1 | 9 | `move WD-028 N2` | Spearmen moved from N1 to N2 |
| 1 | 9 | `move WD-020 C1` | Forest Guardian moved from W1 to C1 |
| 1 | 9 | `attack brian` | Won the raid; opponent survives, no asset available (strength 10 vs 9; casualties: [brian's Undead Cavalry]) |
| 1 | 9 | `conquer brian` | Occupation started at N2; brian has its next turn to defend or repel. Conquest settles at a completed round after that response. |
| 1 | 10 | `pass` | Turn completed. Active: player; round 10 Opponent moves: [brian: Ash Drake moved from C1 to E1, brian: Thunder Roc moved from C1 to E1, brian: River Serpent moved from C1 to E1, brian: Griffin moved from C1 to E1, brian: Raid succeeded: captured Herbalist; mira remains in the game (strength 21 vs 9; casualties: [brian's Ash Drake, brian's Thunder Roc, mira's Pikemen]), brian deployed Trading Post for free (round production: +1 gold per round), brian: Herbalist healed River Serpent by 1; strength 8/9 (-1 Wealth), mira: defensive reinforcement — mira deployed Frost Giant for free, mira: Counterattack resolved at E1 (strength 8 vs 13; casualties: [mira's Frost Giant, brian's River Serpent]), mira: Royal Guard moved from C1 to N2, mira ordered Royal Guard: investigate ruins, mira: Attached Reinforced Shield to Royal Guard (armor 2, attack bonus 0)] Match ongoing. |
| 1 | 10 | `draw` | Drew Rock Troll [WD-017] |
| 1 | 10 | `move WD-028 E1` | Spearmen moved from N2 to E1 |
| 1 | 10 | `move WD-020 E1` | Forest Guardian moved from C1 to E1 |
| 1 | 10 | `attack mira` | Won the raid; opponent survives, no asset available (strength 10 vs 0; casualties: []) |
| 1 | 10 | `conquer mira` | Occupation started at E1; mira has its next turn to defend or repel. Conquest settles at a completed round after that response. |
| 1 | 11 | `pass` | Turn completed. Active: player; round 11 Opponent moves: [mira ordered Royal Guard: gather intelligence, mira drew one private card] MATCH WON by player |

88 responses are recorded here, plus manually verified duplicate-attachment and completed-match/reload checks. Automated coverage separately exercises area healing of both sides, insufficient Mana, Gold payment, wrong-owner/construct/range rejection and invalid-action atomicity. These cases were not all replayed live.

One supported card action does not mean every printed capability is resolved. The old 200-card development pool remains available. This small sample does not establish overall balance or replay value.
