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
