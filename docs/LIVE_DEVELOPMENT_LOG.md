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
- Last hosted version: one shared in-process demo session without accounts. The 2026-10-19 browser-save increment is on the development branch; hosted rollout remains blocked by the billing suspension.
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

## 2026-10-13 — Executable card actions and quick-match pacing

Implementation:
- Added new quick game [seed], using 55 cards filtered from the existing 200-card definitions by implemented capabilities/effects. The full seeded 200-card R&D mode remains available. All normal shuffling and private seven-card opening hands remain intact.
- Six producer structures now have explicit data-driven income: Gold Mine (+2 Gold), Treasury/Trading Post (+1 Gold), Granary (+1 Wealth), Mana Shrine/Arcane Tower (+1 Mana), per completed round. Only PRODUCE-capable cards earn this income. A provisional +1 baseline Wealth per surviving kingdom per completed round prevents an empty supply economy.
- Healer (+2 healing) and Herbalist (+1 healing) can move and treat a co-located owned living BIOLOGICAL unit, once per healer per round, for 1 Wealth or 2 Gold. Append with gold to choose Gold; insufficient Wealth can fall back to available Gold. All validation precedes costs/cooldown/mutation. Dead, enemy, and non-biological units cannot be treated. Bone Dragon and Undead Cavalry are marked UNDEAD rather than BIOLOGICAL.
- Healing Rain costs 2 Mana and the hand-card action; heals all wounded biological units at its region, including opponents, and extinguishes fire. It is a shared environmental effect, not an owner-only buff.
- Directly attach owned hand equipment to a living unit at the capital as the main card action; already-deployed equipment can attach through management at the same location. Iron Sword adds 1 attack; Reinforced Shield absorbs 2 incoming damage per battle; Fireproof Mail absorbs 1 and supplies FIREPROOF to its carrier.
- Equipment follows its carrier, cannot be double-attached, and drops at the battlefield if the carrier dies. Capture cannot steal an attached item independently. Conquest preserves attachment relationships.
- Armor-protected surviving defenders block capture. A completely killed attacking force cannot claim an asset even when its pre-exchange attack total was higher.
- Opponents equip available deployed gear and heal nearby eligible wounded units using their own resources. Quick opponents retain one-shot spells/events instead of deploying them as inert permanent cards. One-shot use always consumes the hand card; it cannot be activated repeatedly after deployment.
- cards shows owned cards, supported actions, traits and human-readable effects; header/deck count identifies the selected mode.

Coverage: 131 local tests pass, including equipment action cost, ownership/range, movement linkage, fire resistance, armor/casualties, dropping equipment, treatment limits and costs, Gold payment, biological restrictions, both-side rain healing, invalid-action atomicity, and quick-mode web/deck selection.

Headless scenarios prepared for live verification: quick seed 1 reached a human victory at round 11; quick seed 192 equipped Fireproof Mail, deployed a Healer and Trading Post, survived enemy raids, and healed Sand Serpent at round 13. These are natural seeded matches without injected runtime cards/resources. Live retest pending.

Design limits: 55 cards have at least one implemented action, not every printed capability; 145 other definitions remain outside the quick slice. Combat/economy values are provisional. Gold has healing-supply use, but no full market/trade settlement. No resurrection, permanent character growth, general magic or free-form combination executor. Read-only declarations of unsupported capabilities remain development data. This is a pacing/interaction slice, not a claim that the full 200-card game is finished.

## 2026-10-13 — Quick deck, equipment and recovery live VERIFIED

Build 550156e2398feaa6021e1cead029e407d81a4488 tested through the browser. Detailed guide: docs/QUICK_MATCH.md. Combined chronological evidence: docs/playtests/2026-10-13-quick-card-actions.md (88 recorded verified responses, plus terminal/reload and duplicate-attachment checks).

- Quick seed 32: Fireproof Mail attached to Royal Lancers and moved with its carrier W1 -> C1 -> N1. world explicitly showed N1 BURNING when entry succeeded. Duplicate attachment rejected without mutation.
- Quick seed 192: real enemy combat killed equipped War Chariots and injured Sand Serpent. A previously deployed Healer restored Sand Serpent from 1/11 to 3/11 for 1 Wealth. Trading Post had earned 10 Gold; Wealth became 12 after treatment. Changing payment to Gold did not bypass the healer cooldown and spent nothing. The carrier's dropped Fireproof Mail was reattached to the survivor at the same location through management, after the main card action had already been spent.
- Quick seed 1: 50 verified match commands reached player victory at round 11. Defenders broke the first occupation attempt. Final deck 17; Gold 14, Wealth 37; all three opponents conquered. Post-win drawing rejected; reload retained the outcome in process.

131 automated tests pass. Live validation covers equipment-derived properties, movement linkage, supply income, healing, cooldown, salvaging gear, conquest and match closure. Area healing and successful explicit Gold payment have meaningful automated checks but were not separately activated in the live runs.

WD-LIVE-005 is MITIGATED in the optional quick slice, not closed as a general balance issue. The sampled quick opening produced immediate troop/deployment choices; 145 unresolved definitions are excluded rather than falsely presented as complete. The old full pool still has incomplete abilities. Further playtests should check strength distribution, armor/healing balance, conquest snowballing and idle turns across many seeds.

No permanent storage, accounts, multiplayer isolation, general AI command translation or live contract acceptance was added. No proof of overall fun/balance is claimed from these three scenarios. Original deployment and waypoint-db remain untouched.

## 2026-10-13 — Recovered session audit and mode-exit fix

- Recovered branch at 248086f; Render reports its documentation deployment live. Previous quick-match live verification is recorded above; the interrupted chat did not imply a failed deployment.
- WD-LIVE-004 source-confirmed: active R&D/quick match routing intercepted reset sandbox. Replay screens also remained selected after the old reset handler.
- Moved reset sandbox ahead of mode dispatch and clear all alternate display modes. The basic sandbox state is retained; the old match is closed. Added visible match exit instructions.
- Regression exercises quick/full matches, historical scenario, both replay screens, restored world routing, and opening a new match after exit. Full Maven suite: 132 tests, zero failures/errors/skips.
- Remaining product gaps: one shared in-memory session, no durable saves/accounts/multiplayer isolation; standing orders mainly record text; proposals/trades lack acceptance and settlement; deterministic opponents; most printed capabilities unresolved outside the supported quick slice.
- Live exit retest pending deployment.

### Mode-exit live retest VERIFIED

Deployment dep-db7725a0p4hs73f65dj0 (5e6a8e7) reached live at 17:59:49 UTC. Browser reproduced the pre-fix unsupported reset command. After final deployment, new quick game 42 displayed exit instructions; reset sandbox returned the basic Kingdom Treasury/hand/management screen with the message "Previous match closed; sandbox state retained." WD-LIVE-004 is VERIFIED FIXED for quick-match exit; all five mode exits are covered by the 132-test automated suite. Other mode exits were not separately browser-tested.

## 2026-10-16 — Executable diplomacy and breakable promises

Implemented in RndDiplomacy using the live RndMatchEngine's seats and turn state:
- `offer`, `accept`, `reject`, `cancel`, `deals` and `betray` commands.
- Atomic single-resource barter for Gold/Wealth/Mana; optional paid ceasefire or
  peace-only offer. Resources remain unreserved until acceptance and are rechecked.
- Participant-only pending terms; public accepted settlements, ceasefires and
  broken-promise counts. No card/inventory or hidden-hand exchange.
- Offers expire after two world-round boundaries or conquest. One pending offer
  per directed pair, three proposals per sender turn. No repeated settlement.
- Explicit betrayal ends a mutual promise without refunding completed barter;
  normal attacks still require legal troops, location and action allowance.
- Peace blocks direct hostility, conquest, original-capital entry and ignition
  at protected capital/unit regions. Rejects peace during bilateral invasion or
  occupation, revalidating at acceptance. Natural expiry costs no trust.
- NPCs accept affordable non-losing trades at provisional Gold=1/Wealth=2/Mana=2
  values, reject peace from known promise-breakers, and can originate peace after
  R5. They respect active promises when selecting attack targets.
- Old free-text trade/negotiate messages now explicitly say they are messages,
  not executable terms. These promises are not canonical Official Contracts.

Validation: local Maven could not access dependencies (unavailable configured
proxy/DNS). Used an isolated `feature/diplomacy-v1` branch and enabled Java CI for
that branch and the live playtest branch. First run caught a variable-scope
compile error; fixed before promotion. GitHub Actions run 38877623291 on commit
a68189270f75b81087b622679e8b80621fca80d5 passed **149 tests, 0 failures, 0 errors,
0 skipped**, including 17 new diplomacy tests and existing victory mutation locks.
Promoted the tested commit to feature/live-world-playtest, preserving its previous
head with an expected-SHA lease. Live-browser verification pending below.

Limits: deterministic transparent NPC pricing, no NPC betrayal planning, no
future deliveries/mutual defense/proxy clauses, no reputation repair, no escrow,
no card/equipment trades and no claim of balanced diplomacy or general creativity.
The shared in-process session, lack of durable saves/accounts and full-pool card
coverage remain unchanged. Original deployment and paid waypoint-db untouched.

### Live diplomacy VERIFIED — quick seed 42

Render deployment dep-db8uge17lnhs73ak1b1g reached live at 09:03:42 UTC / 12:03:42
EAT. Normal `new quick game 42`, no injected cards/resources. Eighteen recorded
form responses after match creation are in
`docs/playtests/2026-10-16-diplomacy-seed-42.md`.

- Trading Post earned normal Gold income; Royal Lancers deployed and reached C1.
- Asha accepted D1 peace. The ceasefire rejected both N1 entry and an attack;
  rejected commands left troops/resources unchanged.
- Asha rejected D2 (1 Gold for 1 Wealth) under its disclosed valuation policy.
- D3 (2 Gold for 1 Wealth) settled once. Starting R2 Gold 2 / Wealth 2 became
  R3 Gold 1 / Wealth 4 after barter and the normal completed-round production.
- Explicit betrayal registered one public broken promise and removed the pact.
  A new D4 peace offer, made before any invasion, was rejected by Asha.
- Repeating acceptance of settled D3 rejected with balances unchanged.
- After betrayal, C1 -> N1 entry succeeded. Reload retained the ledger in process.

Final observed state: round 4, deck 27, Gold 2 / Wealth 5 / Mana 0. This verifies
live negotiation decisions, actual settlement, ceasefire enforcement and trust
consequences, not a completed-match or balanced-diplomacy claim. NPC-originated
offers and human acceptance of one were not exercised in this browser run.
Expiry, cancellations, privacy, insufficient funds, overflow and conquest cleanup
have automated coverage rather than separate browser proof in this cycle.

## 2026-10-16 — First executable combined world order, VERIFIED

Implemented `combine <fire-source> with <troop> to flush <forest-id>` and
`interactions` in the active live match, without borrowing sandbox state.

- Composition requires distinct owned FIRE/IGNITE and deployed adjacent mobile
  ATTACK cards, plus FLAMMABLE FOREST terrain. It uses capabilities/properties,
  not particular card names or narrative text saved as a fake outcome.
- Costs: main action, hostile action, 1 Wealth and normal source/card/Mana costs.
  All validation precedes mutation; pending pressure is public.
- The immediate ignition is ordinary terrain fire. Forced retreat waits for
  every initially threatened surviving kingdom's actual next completed turn.
- Water cancels pressure; equipment-derived FIREPROOF can prevent displacement;
  moved/lost/ineffective support or a new ceasefire can cancel it. Costs remain
  spent. Fireproof/fixed assets stay, and units without a legal exit are pinned,
  not magically killed. Equipment follows retreats and movement is accounted for.
- Resolution never itself awards a raid, capture, foothold or conquest. Ordinary
  fire continues to block the attacker's own non-fireproof advance.
- Match victory clears pending pressure and locks further combined orders.

Validation: isolated test-branch Java CI run 38879694114 passed 165 tests with the
16 new interaction regressions. Adding the complete-match rehearsal produced
run 38879902611 on bf642c996727509348213bdd0e5c40a03650cf0f: **166 tests, zero
failures/errors/skips**. Local Maven dependencies remain unavailable, so these
are actual CI results, not claimed local execution.

The rehearsal selected normal quick seed 1598 using the human's own hand and
public board state. It reached a human victory at R10. The tested build was
promoted to the live branch and Render deployment dep-db8uq1k9v7es739b35kg reached
live at 09:25:57 UTC / 12:25:57 EAT.

### Live browser results

Detailed command/result evidence: `docs/playtests/2026-10-16-combined-orders.md`.
No runtime cards, resources or state were injected in either browser scenario.

1. Seed 105, seven verified responses: Spearmen + Wildfire prepared X1 at N1;
   Asha used her naturally held Great Flood on her response turn, cancelling the
   combination. `interactions` reported COUNTERED, with no retreat or refund.
2. Seed 1598, 47 verified responses: Mira accepted a ceasefire; War Chariots +
   Wildfire flushed Shieldbearers and Cave Wyrm from N1 to N2 after Asha's response.
   A direct entry attempt failed because fire persisted. The human's Great Flood
   then cleared it, enabling ordinary movement/raid/occupation.
3. Brian later fought the refugees at N2 and killed them. Further human advances
   also suffered actual losses; this was not an automatic victory combo. Mira
   exploited the wider war and took Brian before the human betrayed her.
4. The same browser match reached **player victory at R10**: deck 23, Gold 28,
   Wealth 24, Mana 0; all three rivals conquered; no pending occupations or
   combinations. Post-win combination rejected with exact match-view equality.
   Reload retained the completed game only within the running process.

Limits: one bounded interaction, not arbitrary natural-language creative orders;
no fire spread/burn damage, selectable retreat path, NPC combined-offense planning,
durable saves or multiplayer isolation. Broader balance/fun is unproven. The
original deployment and paid waypoint-db remain untouched. Next work should
stress-test this interaction across varied positions, then provide safe isolated
sessions before wider player testing; story and additional inert cards remain
deferred.

## 2026-10-19 — Independent browser matches and complete saves

Implemented on feature/diplomacy-v1, pending hosted rollout. Separate browser cookies now own separate match state; tabs within the same browser share their match. Per-session serialization and revision checks prevent stale tabs from submitting duplicate orders. The bounded session cache does not evict a session while requests are using it.

Quick/full matches now serialize complete versioned snapshots: all hands, ordered deck and discards, treasury and physical goods, boards/orders, locations, wounds, equipment, terrain, action/healer/movement limits, completed-turn counters, diplomacy offers/pacts/trust and ID counters, pending occupations and combined orders, events, eliminations and winner. Immutable geography and player identities are reconstructed. Saves use AES-256-GCM, are bound to a persistent HttpOnly browser cookie, and are stored in localStorage before reloading. Fresh server processes restore before enabling commands. Damaged saves and storage failures preserve the previous save and visibly stop controls.

### Verification

- [CI for 8046231](https://github.com/Yusuf-cm/wild-deck/actions/runs/39256034986) passed: 176 Java tests, zero failures/errors/skips, plus six browser-script checks.
- Two independent HTTP clients retained separate quick/full matches. Cross-browser tokens, tampering, wrong keys, stale revisions, malformed and oversized requests were rejected without changing existing state.
- A real JVM process was terminated. A second JVM with the same stable key restored both matches from their saved tokens/cookies, matched their complete snapshots, and continued one match identically while preserving the other.
- JSON round trips preserved deck/hand order and every mutable engine field, pending defender-response windows, diplomacy acceptance/betrayal/IDs, non-default combat/economy/occupation state and victory locks. Cache eviction recovery was verified.
- The shipped browser script ran against simulated browser APIs: save-before-reload, restart restoration, damaged-save retention, quota failure after an order, older-tab protection and stale-action reload. These are script/HTTP/process tests, not two live browser profiles or a hosted restart.

### Hosting blocker and limits

Render service wild-deck-world-playtest is suspended with suspender billing (read-only checks on 2026-10-19; suspension timestamp 2026-10-16T18:45:41Z). Its branch remains feature/live-world-playtest at the prior hosted version. No billing, service plan, original deployment, or paid waypoint-db changes were made, and no new resources were added.

Before rollout, provision one stable random 32-byte base64 WILD_DECK_SAVE_KEY as a hosting secret, resolve hosting availability, then deploy the verified branch and perform live browser/restart checks. Do not start with an ephemeral key. See [Browser saves](BROWSER_SAVES.md).

These are same-browser saves, without accounts or cross-device recovery. Clearing site data/cookies or losing the server key loses access. Sandbox and historical modes are temporary; reset sandbox closes the saved match. An order whose response never reaches browser storage can be lost on a subsequent restart. Older valid snapshots can be replayed after server memory disappears; preventing that requires durable authoritative server storage.

## 2026-10-29 — Smallest world-first interaction experiment

User redirected development toward world properties, interactions, reactions and deliberate responses before further cards or GUI work. Added a small WorldSimulation over the existing WorldMap, plus an executable WorldSandboxDemo with a forest clearing, river bank and settlement. This is a card-independent laboratory, not another match engine.

Heat spends a finite charge to ignite reachable dry combustible material. Ticks consume fuel, emit smoke and damage exposed actors. Fire blocks ordinary entry; a unit can retreat, collect water, extinguish material or wet it preemptively. Wetness dries, fuel remains consumed, fireproof actors resist exposure, and smoke expires. Nearby-site reach is explicitly local and must not be reused as continent-scale range.

[Code CI](https://github.com/Yusuf-cm/wild-deck/actions/runs/40841070706) passed for 7642331: eight new world tests, 184 total Java tests, no failures/errors/skips, six browser-script checks, and the actual executable demo. The scout retreated at health 2/3; extinguished trees retained 2/3 fuel; movement reopened; wet trees resisted reignition; the same rule burned a wooden gate and rejected stone. Tests also cover alternate response timing, depletion, drying, fireproof movement, incapacitation, distance, resource preservation on failed actions, snapshot isolation and deterministic repetition.

No AI decision-making, automatic fire spread, flooding, smoke concealment, graphical client, match integration or world-save format is claimed. No hosting or billing changes. See [World laboratory](WORLD_LAB.md) for exact rules, run instructions and evidence.

## 2026-10-29 — Define only the planet

User clarified that the immediate next step is an empty Earth-sized planet before continents, interactions or AI. Added Planet: an ideal sphere with mean radius 6,371 km, continuous latitude/longitude coordinates, great-circle distance and geocentric XYZ positions. No grid is allocated and no land, water, elevation, inhabitants or simulation processes are assigned to this sphere. The earlier three-site laboratory is not integrated into it.

[Code CI](https://github.com/Yusuf-cm/wild-deck/actions/runs/40841583900) passed for 8524243: six new planet tests, 190 total Java tests with no failures/errors/skips, and six browser checks. Tests verify planet scale, wrapping, poles, opposite-side distances, coordinate axes, invalid inputs and distance symmetry/scaling. No hosting changes. Exact scope and units are in [Planet foundation](PLANET.md).
