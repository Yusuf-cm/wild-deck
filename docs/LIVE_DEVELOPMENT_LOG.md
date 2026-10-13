# Wild Deck — Live Development and Playtest Log

This is the running record for browser playtests, verified findings, implementation decisions, and retests. Append a dated entry after each development/test cycle. Do not mark a feature complete solely because its unit tests pass or deployment is live.

## Current objective

Make the card-driven world playable with meaningful freedom, deception, and creative interactions. First prove a coherent quick-match loop. Story mode, persistent kingdoms, player-created cards, daily creation slots, and collections remain later work.

Cards define what exists; players define what they attempt; the engine validates and changes authoritative state.

## Environment

- Repository: Yusuf-cm/wild-deck
- Branch: feature/live-world-playtest
- Live test URL: https://wild-deck-world-playtest.onrender.com
- Render service: wild-deck-world-playtest (srv-db709s0u01pc73br3iug)
- Runtime: Java 17, Maven, Docker; entry point com.wilddeck.app.HumanWebServer
- Scope: separate development deployment; preserve original deployment and paid waypoint-db.
- One shared in-process demo session, without accounts. Reload survival does not establish durable persistence or isolation.
- Render auto-deploys commits on this branch.

## 2026-10-13 — Live browser test, approximately 13:30–13:33 EAT

### Method and starting state

Interacted with the deployed command form in the browser. This was live UI testing, not a unit-test run or a completed match.

The page was already in "Wild Deck R&D — Four Player Table". Existing session was retained; no reset was issued. Initial match round: 0; current player: player; deck: 172. Player board: Underground Vault. Hand: Mind Veil, Blood Moon, Wildfire, Arcane Tower, Scrying, Solar Eclipse. Asha, Brian, and Mira each had seven hidden hand cards and no board cards. All player resources were zero. Initial visible message: "unsupported action: UNSUPPORTED".

### Executed commands and evidence

| Command/check | Observed result | Assessment |
| --- | --- | --- |
| world | unsupported action: UNSUPPORTED; no map shown | Unavailable through current four-player mode |
| draw (initial turn) | main action already spent this turn; kingdom management only | Existing action restriction enforced; prior action history unknown |
| pass | Three opponents deployed Mercenaries, Healer, Necromancer; match advanced to round 1 and returned to player | Basic turn progression works |
| deploy Arcane Tower | Card removed from hand, added to board; message describes +1 Mana per completed round | Deployment works |
| move Mercenaries to forest | unsupported action: UNSUPPORTED | Four-player mode rejects command; not a valid movement legality test |
| pass | Opponents deployed Forest Guardian, Fireproof Mail, Grappling Hook; match reached round 2; player Mana became 1 | Passive resource production works |
| draw | Drew Gemstone [WD-148]; deck 172 -> 171; card appeared in hand | Drawing works |
| Browser reload | Round 2, hand, board, resources, and last message retained | In-process state survives page reload only |

Important movement-test limit: Mercenaries belonged to Asha, not the human, and the sandbox syntax is move <deployed-card-index> <location-id>, not a card-name phrase. This test does not establish whether legal movement in the sandbox works.

### Verified issues and source diagnosis

**WD-LIVE-001 — Four-player match has no reachable world command path. Status: OPEN.**

In HumanWebServer.act, once rndMatch is non-null, all commands are dispatched to RndMatchEngine.command and the method returns. The world/move handlers appear later and operate on PlayableSession.state, the separate basic sandbox. Thus a rejected four-player world command does not prove WorldEngine itself is broken.

Required fix: inspect the R&D match state model, then integrate world state and commands into that same authoritative match, or make a clearly separated world-enabled match mode. Never execute four-player movement against the unrelated sandbox state.

**WD-LIVE-002 — Header displays wrong state's round. Status: OPEN.**

HumanWebServer.render always reads session.state().round() for "World round", including when it renders rndMatch. Live header stayed at World round 1 while R&D match advanced from round 0 to round 2.

Required fix: render the active mode's round and mode name.

**WD-LIVE-003 — Turn feedback is misleading. Status: OPEN.**

Pass response says "Active: asha; round 1", while the post-opponent board correctly says "Round 2 | Current: player". HumanWebServer constructs the response from the pre-opponent pass result and then appends opponent actions. It also appends a literal backslash-n instead of a line break.

Required fix: show final round/current player after all opponent actions and use an actual newline.

**WD-LIVE-004 — Mode discovery and exit are unclear. Status: OPEN.**

The basic sandbox advertises world/move and several scenario commands. Once rndMatch is active, its early dispatch intercepts later commands, including reset sandbox. No four-player exit was tested. Review mode transitions and add visible help/controls without silently resetting the shared match.

### Other observations, not yet classified as bugs

- Live R&D Wealth remained zero at round 2. README describes +1 Wealth per round for the alpha configuration; this may reflect different engine rules. Check R&D rules before changing economy.
- Opponent hand counts remain hidden in the UI, but browser observations do not prove AI prompt redaction or server isolation.
- No combat, creative action, hidden deployment, negotiation, victory, server restart persistence, or simultaneous-user test was completed.
- Opponent moves occurred, but this test did not establish whether Groq or a deterministic fallback selected them.

## Next work, in order

1. Inspect RndMatchEngine and its command parser/state model; establish which world and interaction capabilities actually exist in this mode.
2. Connect world inspection, card location assignment, and owned-unit movement to the same match state. Keep ownership, movement capabilities, connectivity, and hidden knowledge authoritative.
3. Correct active-mode header, help, and final turn feedback.
4. Verify the integration with meaningful automated checks: commands reach the intended match; rejected moves do not mutate state; wrong-owner movement is rejected; turn feedback matches final state.
5. Deploy on the existing playtest branch and repeat live browser tests using documented syntax, owned deployed units, and real locations.
6. Test a creative interaction that changes observable world state, then complete a match through an actual victory condition.
7. Only after that expand the world or introduce progression systems.

## Acceptance gate for world-playable quick matches

- Player can inspect the permitted world view and see their deployed card locations.
- Legal owned-unit movement changes location in the active match.
- Wrong-owner, impossible, and invalid-target moves are rejected without mutation.
- Turn/round/resource state stays consistent between feedback and board.
- At least one creative card/world interaction resolves with visible consequences.
- One complete match reaches its victory condition through the live interface.

## Entry template

For each cycle record:
- Date/time and branch/commit when known
- Active mode and seed when established
- Starting round/cards/resources
- Commands or actions executed
- Expected versus actual result
- Evidence and limits of the check
- Issue IDs and classification (engine, rule, balance, AI, or UI)
- Fix and validation performed
- Live retest outcome
- Remaining work

Keep "observed", "source-confirmed", "planned", and "verified fixed" separate. A successful deployment is not a successful gameplay test.

## 2026-10-13 — Four-player geography integration

Implemented in RndMatchEngine rather than borrowing the unrelated sandbox GameState:
- Reused RndContinent's existing six-region topology through WorldMap.
- Deployment records a card's capital region; the player's board view includes IDs and locations.
- Added world/map/help and move <card-name-or-id> to <region-id> (or move <id> <region-id>).
- Movement requires an owned deployed mobile Military/Creature/Specialist, adjacency, the active turn, and no prior move that round. Fixed assets cannot move. Validation precedes mutation; movement resets at completed round boundaries.
- Fixed active match header and final turn response, including real line breaks.
- Added engine integration and web routing/header regression checks.

Validation uncovered an existing single-player fixture in WildDeckEngineTest.drawingDoesNotPreventPlayingCards that failed when deployment created geography requiring at least two kingdoms. Updated that fixture to include a second player; draw/play assertions remain unchanged.

Limitations: this is geography integration, not the full creative world engine. Raids still use kingdom-wide strength, orders remain saved instructions, terrain interaction resolvers and a match-ending victory mechanic are not implemented here. Region ownership is initial atlas metadata, not capture/control state. NPC movement strategy remains future work.

WD-LIVE-001, WD-LIVE-002, WD-LIVE-003: implementation complete; live retest pending. WD-LIVE-004: visible help added; safe mode exit remains pending.

Local full Maven suite: 105 tests, 0 failures, 0 errors, 0 skipped. Live retest pending.

## 2026-10-13 — Live retest of commit 77025f6f56bab751ce912734b9d0c25a7a9277ec

Render deployment dep-db70n9rm8hqs73f5l66g reached live at 10:46:01 UTC / 13:46:01 EAT. Tested the browser command form in a fresh four-player match with seed 20261010. Completed the movement checks by approximately 13:48 EAT.

| Check | Live result |
| --- | --- |
| world in four-player match | Passed: six-region atlas and current match round returned |
| deploy Arcane Tower | Passed: WD-073 placed at W1 / Runebank |
| move Arcane Tower to C1 | Rejected as fixed/nonmobile; location remained W1 |
| pass | Final feedback and header both show player and completed round; tower Mana increments |
| move Mercenaries to C1 | Rejected: source must be your deployed card |
| Draw through rounds | Gemstone, Magic Crystal, Beast Saddle, Siege Infantry drawn; deck reached 168 |
| deploy Siege Infantry at round 5 | Passed: WD-029 deployed at W1 |
| move Siege Infantry to N1 | Rejected as nonadjacent; remained W1 |
| move Siege Infantry to C1 | Passed: W1 -> C1 despite prior rejected move |
| move WD-029 S1 in round 5 | Rejected: already moved this round; remained C1 |
| pass to round 6; move WD-029 S1 | Passed: C1 -> S1; round movement allowance reset |
| Reload | Round 6 and S1 location retained within running process |

WD-LIVE-001, WD-LIVE-002, WD-LIVE-003 are VERIFIED FIXED for the covered commands. WD-LIVE-004 remains open for safe mode exit.

Browser testing note: rapid form submissions could race navigation and issue/reject redundant commands. Later tests waited for the expected new visible response or round before issuing the next command. No additional cards were drawn by the redundant requests; the one-main-action guard rejected them. Prefer explicit response waits in future browser test runs.

Current shared session after retest: round 6, current player, deck 168, Arcane Tower at W1, Siege Infantry at S1, Gold 0 / Wealth 0 / Mana 6. Document-only commits can trigger Render auto-deploy and restart the in-memory session; this snapshot is evidence, not durable storage.

Next increment: location-aware attacks/access and at least one property-based terrain interaction with observable consequences. A full match-ending victory condition remains absent from this R&D engine; no complete-match claim is made.

## 2026-10-13 — Location-aware raids and terrain effects

Implemented next quick-match slice:
- Raids now count only ATTACK-capable units physically at the target capital; defenders and captured assets must be there too. Failed access checks do not spend the hostile action.
- NPC raid attempts first move eligible forces one region along a deterministic shortest available route.
- R&D cards retain their properties. Explicit FIRE/IGNITE data added to Wildfire/Fireball and WATER/EXTINGUISH to Great Flood/Orb of Tides. No card-name recipes in the terrain resolver.
- Forest regions are FLAMMABLE. Fire marks a region BURNING, blocks entry for non-FIREPROOF units, and reduces each non-fireproof card's local strength by 1 (floor zero). Water clears that state and its effects.
- use <card> on <region-id> resolves only supplied capabilities. Hand events/spells consume the main action and go to a discard ledger; deployed sources must reach their region. WORLD_EFFECT sources have global reach; other sources are local. MAGIC sources cost 2 Mana. All validation occurs before costs or mutation.
- new four player game [seed] supports reproducible live matches; malformed seeds preserve the current match. Seed 836 naturally deals Cavalry, Wildfire, and Great Flood to the human for the upcoming browser test.

Raid fixtures updated to deploy and march through real commands instead of inserting unplaced board cards. New tests cover actual Wildfire consumption, invalid-target atomicity, water restoring access, FIREPROOF entry, property-based strength penalties, distant-raid rejection, and valid attacks after movement.

Design limits: fire persists until extinguished, with no spread/weather/damage-over-time model. No property transfer or equipment-attachment resolver yet supplies FIREPROOF to existing units. Raids still compare summed local strength and capture at most one zero-strength asset; they do not resolve full combat casualties or kingdom elimination. Saved orders remain instructions rather than arbitrary creative outcomes. This slice must not be described as a finished creativity engine or complete match.

Local suite: {'tests': 111, 'failures': 0, 'errors': 0, 'skipped': 0}
Live validation pending.

## 2026-10-13 — Live terrain/capture test VERIFIED

- Tested commit 7931920b5ffbf4126c3a182d2d8ff5fe0eccce8e.
- Render deployment dep-db70ugojo6nc7389umhg became live at 11:01:39 UTC / 14:01:39 EAT.
- Browser playtest completed by approximately 14:09 EAT.
- Fresh normal seeded match: new four player game 836. No extra cards or resources injected.
- Human opening hand: Cavalry, Protective Ward, Wildfire, Great Flood, Spirit Bell, Smoke Bomb, Flood.

| Round | Command | Actual result |
| --- | --- | --- |
| 0 | deploy Cavalry | WD-023 deployed at W1; main action consumed |
| 0 | attack asha | Rejected: no attacking forces at N1; no raid allowance spent |
| 0 | move Cavalry to C1 | Passed, W1 -> C1 |
| 0 -> 1 | pass | All opponents deployed; Asha deployed Arcane Tower |
| 1 | use Wildfire on N1 | N1 burning, ordinary entry blocked; Wildfire consumed from hand |
| 1 | move Cavalry to N1 | Rejected because burning; Cavalry remained C1 |
| 1 -> 2 | pass | Opponents acted; Asha deployed Royal Trade Caravan |
| 2 | use Great Flood on N1 | Fire cleared and entry restored; Great Flood consumed from hand |
| 2 | move Cavalry to N1 | Passed, C1 -> N1 |
| 2 | attack asha | Captured Arcane Tower; Asha remained in game |
| 2 | attack asha again | Rejected: one raid/attack per turn |
| 2 -> 3 | pass | Captured Arcane Tower produced 1 Mana for player |

Final observed state: round 3; player current; deck 172; human board Cavalry and captured Arcane Tower, both at N1; hand four remaining cards; Gold 0 / Wealth 0 / Mana 1. Asha retained Royal Trade Caravan and deployed Fishing Boat. Capture did not teleport the tower home.

Verified this cycle: terrain state affects access; water reverses fire; actual movement unlocks a raid; capture transfers economic benefit; action limits remain enforced. Local suite: 111 tests, zero failures/errors/skips.

Design lesson: igniting a destination also denies access to the caster's ordinary forces, so terrain manipulation requires timing rather than being an unconditional attack bonus. This test exercised both fire and water from the human hand; it did not establish that NPCs reason about or cast terrain counters. NPC movement toward raid destinations is implemented and covered by the full suite, but this short live run ended before the R5 strategic NPC phase.

Remaining gaps: terrain counter use in opponent decision-making, visible opposing unit locations, location-based interception/casualties, defense/counterattack windows, and a true match-ending victory condition. Generic unsupported abilities/orders, attachments and trait transfer remain unimplemented. The captured-tower message says "Victory", meaning a successful raid, not a won match.

Next priority: give opponents legal terrain reactions and meaningful local defense, then add a complete match endpoint. Do not expand story mode yet.

## 2026-10-13 — Defense, opponent counters, conquest and match endpoint

Implementation:
- Local battles snapshot both strengths and apply simultaneous damage. DEFEND-capable units absorb damage first. Wounds persist and lower effective strength; destroyed units leave boards/locations/orders and enter the discard ledger. This is an R&D combat slice, not the canonical corpse/wreck or regeneration system.
- repel <kingdom> lets local ATTACK/DEFEND forces counterattack invaders at their own capital.
- NPCs inspect public capital threats, use owned legal water counters, deploy strongest available defenders, and counterattack. This response runs from round 0 rather than waiting until the R5 strategic phase. It never supplies cards or mana they lack.
- conquer <kingdom> requires a successful local raid, a surviving OCCUPY-capable unit at the capital, and no effective local defenders. A public pending occupation gives the defender a response turn.
- Settlement checks the defender's actual completed-turn counter, not merely the next round number; late-round declarations cannot bypass the response window. A lost foothold or new defenders break occupation.
- Successful conquest eliminates the kingdom, transfers its surviving board/resources/goods, preserves asset locations and damage, discards its private hand, changes capital controller display, and skips its future turns.
- Last independent kingdom wins; state-changing commands are locked after victory, while status/world/events/help remain available. An eliminated human can pass to spectate.
- Public board views now show positions and remaining/base strength. events exposes the public audit history.
- Fixed use scrying on routing so it is not intercepted by the generic terrain-use parser.

Regression coverage: simultaneous casualties, persistent wounds, equal-force destruction, defender counterattack breaking occupation, late-round response fairness, conquest/victory/mutation lock, terrain counters and reinforcements.

Local suite: {'tests': 117, 'failures': 0, 'errors': 0, 'skipped': 0}
Headless normal seeded matches: seed 0 ended with Mira winning at round 27; seed 1 ended with the human winning at round 15. Human planning used own hand and public positions/strength, not opponents' hidden hands. Seed 977 naturally gives human Wildfire and Asha Great Flood, selected for an upcoming live reaction test. These headless results are not yet live-browser verification.

Limitations: aggregate local damage rather than individual target selection, no manual reaction subturn, no healing/equipment/trait-transfer resolver in this R&D engine, no vassalage/annihilation option, no persistent accounts. Combat values are provisional. Most free-form orders still only save text. Generic negotiation/trade acceptance remains unfinished.
Live retests pending.

## 2026-10-13 — Live opponent counter and complete match VERIFIED

Build 9f7ea8e94a17c0cfb1feeebad48a2934a80e66cb; Render deployment dep-db73ih7avr4c73c1uu90 became live at 14:00:38 UTC / 17:00:38 EAT.

### Opponent terrain counter, seed 977
1. new four player game 977 created a normal seeded match.
2. Human used owned Wildfire on N1; event left hand and marked Moonwood burning.
3. pass triggered Asha's owned Great Flood: fire cleared and her hidden hand count fell from 7 to 6. No extra cards, mana or commands were supplied to her.
4. world confirmed N1 was no longer BURNING.

### Complete live match, seed 1
Submitted and verified 48 commands through the browser, from round 0 to a player victory at round 15. The complete chronological command/result record is docs/playtests/2026-10-13-defense-seed-1.md.
- First invasion captured Treasury, then Asha deployed Sand Serpent and counterattacked: Dire Wolf died; Sand Serpent survived at 1/11 strength.
- Rebuilt with Arcane Militia, raided and declared occupation at N1 in round 12. Asha got her response turn before conquest settled at round 13.
- Brian conquered at round 14; Mira conquered at round 15.
- Final state: player winner, all three other kingdoms CONQUERED, deck 147, no pending occupations.
- Post-win draw rejected; deck and round unchanged. Reload retained the completed match inside the running process.

This verifies a reachable complete quick-match endpoint, not only a raid or headless simulation. It does not establish durable persistence, balance or broad card coverage.

WD-LIVE-005 — Pacing / executable card coverage. OPEN. After Dire Wolf died, the tested human plan spent rounds 4–10 drawing largely unresolved cards before the next military deployment. Opening hand included potentially interesting cards whose effects are still unimplemented; all three human resources remained zero. Next development should give existing hands more meaningful executable choices or use a smaller fully playable deck slice, instead of adding more inert content.

Presentation follow-up: replace raw Occupation Java-record fields with a readable pending-conquest summary; correct conquest timing text to say settlement follows an actual defender response. These wording changes do not alter the verified combat/turn state transitions.

Current limitations remain: simplified damage allocation, no manual reaction subturn, no vassalage, no general creative ability engine, and deterministic rather than provider-backed opponents in this mode. The original deployment and paid waypoint-db remain untouched.

### Presentation follow-up live verification

Build 60d4bf5651702aee194763594e7b2ff927760d5b; deployment dep-db73nb67bikc73frc5lg reached live at 14:11:00 UTC / 17:11:00 EAT. A fresh normal seed-836 game deployed Cavalry, marched W1 -> C1 -> N1, captured Arcane Tower, and declared occupation. The browser displayed the readable "player occupying asha at N1; awaiting response and settlement" summary and the corrected defender-response timing text. Seven affected local tests passed. The earlier seed-1 full-match proof remains the completed-match result; this presentation check intentionally ended with a pending occupation at round 1.
