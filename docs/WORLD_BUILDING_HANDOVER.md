# Wild Deck — World-building handover

Last updated: 29 October 2026  
Repository: Yusuf-cm/wild-deck  
Working branch: feature/diplomacy-v1

Read this document before continuing development. It records the current direction, what is actually implemented, the discussions that changed the approach, and what remains proposed. Use the linked implementation notes and development log for detailed evidence.

## 1. The game we intend to build

Wild Deck is a strategy card game about creative uses of available things, a world that responds consistently, discovery, negotiation, deception, and consequential decisions.

The original mock matches were enjoyable because players could invent plans and combine capabilities. A reliable conquest loop alone does not establish that experience.

The intended relationship is:

1. The world supplies space, materials, conditions, and shared rules.
2. Things interact according to their properties and circumstances.
3. Reactions change conditions and create opportunities for responses.
4. Cards eventually introduce things and capabilities into that world.
5. Players choose how to use those things.
6. AI interpretation and autonomous opponents come later, using the same authoritative world.

The user explicitly postponed AI during the current world-building phase. Do not restart AI integration or GUI expansion as the next task without discussing it.

## 2. Why development changed direction

The earlier implementation produced a working command-based conquest prototype. Its predefined actions, scripted opponents, and one combined forest-flush interaction offered much less freedom than the intended game.

A repository audit found two partially separate implementations:

| Path | Existing role |
| --- | --- |
| BrowserGameServer → HumanWebServer → RndMatchEngine | Browser quick/full matches, scripted opponents, diplomacy, combat, conquest, saves. |
| PlayableSession → GameState / PlayableActionExecutor | Older gameplay path with access, relationships, contracts, and optional WildDeckAiService connections. |

AI service classes existing in the repository do not mean the browser match calls them. Generic capability validation does not mean that capability has an executable outcome.

Some earlier free-form orders and negotiation messages are stored as text without producing the requested world changes. Those must not be described as completed mechanics.

The user rejected continuing by implementing isolated creative encounters first. The agreed direction became: establish the world, then build material interactions, reactions, and responses on that foundation.

Do not create a third full match implementation. The new world code is foundational geometry/generation or an explicitly separate experiment; integration into one authoritative gameplay path remains a future architectural decision.

## 3. Decisions and their status

| Topic | Decision / status |
| --- | --- |
| World shape | A globe, with a flat atlas available for viewing and control. |
| World size | Earth-sized for now: mean radius 6,371 km, diameter 12,742 km, approximately 510 million km² surface. |
| Visual style | Mythical geography is the direction. The atlas is an inspection tool, not final high-definition terrain or the playable GUI. |
| Surface construction | Generate continental shapes instead of hand-building every continent. |
| World identity | Use a signed 64-bit seed, explicit generator version, and planet settings. |
| Generation layers | Geography first; suitable distributions next; more complex climate, ecosystems, magic, civilization, and knowledge later. |
| Randomness | Seeded variation should create repeatable starting conditions. Interactions should remain understandable and consistent. |
| Distribution | Suitability + clustering + rarity + seed, rather than uniform random sprinkles. |
| Knowledge | Separate what exists from what each kingdom has discovered, understood, and learned to use. Agreed direction, not implemented. |
| Secrecy | Different kingdoms can know different methods; techniques can eventually be concealed, taught, traded, stolen, or misrepresented. |
| Independent discovery | A valid independently devised method should be possible when its actual requirements are satisfied; a research flag should not arbitrarily forbid it. |
| Materials | Properties and conditions should determine behaviour across different objects and card sources. |
| Mathematical detail | Use only enough mathematics and simulation detail to support reliable, interesting decisions. Full Earth physics is not an MVP requirement. |
| AI | Explicitly deferred by the user for this phase. |
| Cards | Future providers of objects/capabilities, rather than the sole location of bespoke interaction recipes. |
| Cost / hosting | Do not add paid services or change billing. Preserve the original deployment and paid waypoint-db. |
| Development record | Document implemented behaviour, decisions, tests, and limitations as work progresses. |

A proposal to handcraft one small region with seeded variations was discussed. The user subsequently chose layered procedural geography; the current generator follows that later direction.

## 4. Reference research and how to use it

These are references for principles, not a decision to reproduce every fictional system. The suggested mechanics in discussion remain proposals unless specifically implemented below.

| Reference | Useful principle | Source |
| --- | --- | --- |
| The Stormlight Archive | A defining natural phenomenon shapes ecology and also supplies useful energy. | [Official Cosmere RPG setting](https://www.cosmererpg.com/stormlight) |
| Mistborn / Sanderson's Second Law | Precise limitations make powers useful for clever, contextual solutions. | [Author's essay](https://www.brandonsanderson.com/blogs/blog/sandersons-second-law) |
| Sanderson's Third Law | Deepen consequences and combinations before continually adding unrelated powers. | [Author's essay](https://www.brandonsanderson.com/blogs/blog/sandersons-third-law-of-magic) |
| Dune | Ecology, scarce resources, and dependence connect geography with power and trade. | [JSTOR Daily analysis](https://daily.jstor.org/the-ecological-prescience-of-dune/) |
| Avatar | Unusual materials help explain unusual geography, including floating formations. | [Official Hallelujah Mountains entry](https://www.avatar.com/pandorapedia/the-hallelujah-mountains) |
| Nausicaä of the Valley of the Wind | Hazardous environments can be functioning ecosystems connected to settlements and conflict. | [Ghibli Museum official film synopsis](https://www.ghibli-museum-shop.jp/i/VWBS1110) |
| Fullmetal Alchemist | Transforming the world has requirements, consequences, and limits. | [Official VIZ series page](https://www.viz.com/fullmetal-alchemist) |
| Noita | Shared material behaviours can combine: burning, freezing, melting, evaporation, and simplified other processes. | [Developer's site and FAQ](https://noitagame.com/) |
| Zelda: Tears of the Kingdom | Consistent object behaviour supports combinations and player-created solutions. | [Nintendo developers' GDC presentation](https://gdcvault.com/play/1034667/Tunes-of-the-Kingdom-Evolving) |
| Minecraft generation | A seed drives repeatable layered generation; terrain, biomes, structures and features are distinct passes. | [Official world-generation overview](https://learn.microsoft.com/en-us/minecraft/creator/documents/world-generation) |

Research-inspired examples such as charge-storing Stormglass and powered Liftstone were discussed. Only a fictional Stormglass deposit-potential field currently exists. Charge storage, lifting, magical energy, and their reactions are not implemented.

The intended spontaneity is surprising combinations of consistent rules. Random geography alone does not deliver emergent gameplay.

## 5. What has actually been built

### A. Earlier playable prototype

The existing quick/full match can resolve a complete conquest game. Implemented mechanisms include deployment, regional movement, combat and persistent wounds, equipment, healing, some production and terrain effects, resource trades, ceasefires and betrayal, occupations, elimination, and victory.

Quick mode uses a 55-card supported-action pool. Full mode loads a 200-card R&D pool, which includes capabilities that are not resolved.

The browser opponents are programmed routines. The intended AI referee/opponent service is not connected to this match path.

### B. Browser isolation and saves

Independent browser sessions and authenticated encrypted quick/full match snapshots were implemented. Saves are held in the browser and can restore a complete match after a fresh Java process starts with the same stable key.

This is not account-backed cloud persistence. Cookies/site storage and the stable server key are required. Sandbox/replay modes are temporary. Hosted verification of this increment was blocked at the last recorded hosting check.

See [Browser saves](BROWSER_SAVES.md).

### C. Figma visual direction

An editable Figma file contains:
- A war table with six regions, army markers, an illustrated hand and contextual controls.
- Card targeting.
- A ceasefire negotiation screen.

[Figma design](https://www.figma.com/design/NQf2IQbyLlbWG0VdFyhDjI?node-id=2-104)

These are designs, not an implemented replacement for the command-based match UI. The new planet atlas is a separate inspection tool.

### D. Small world-interaction laboratory

WorldSimulation and WorldSandboxDemo reuse WorldMap for three nearby sites: forest clearing, river bank, settlement.

Verified rules:
- A heat source spends a charge to ignite reachable dry combustible material.
- Fire consumes fuel, creates smoke, damages exposed actors, and blocks ordinary entry.
- Actors can deliberately retreat, collect water, extinguish material, or wet it preemptively.
- Wetness dries, consumed fuel stays consumed, smoke expires, and fireproof actors resist exposure.
- The same ignition rule works on trees and a wooden gate and rejects stone.
- Failed actions do not consume resources or change event history.

This experiment has no AI decisions, fire spread, flooding, smoke concealment, planet integration or world persistence. Its local reach must not be treated as continent-scale range.

See [World laboratory](WORLD_LAB.md).

### E. Empty planet geometry

Planet defines:
- An ideal Earth-sized sphere.
- Continuous latitude/longitude surface positions.
- Great-circle distances.
- Geocentric XYZ coordinates.
- Coordinate validation and longitude wrapping.

Planet itself holds geometry, not generated terrain.

See [Planet foundation](PLANET.md).

### F. Seeded surface generator

PlanetSurfaceGenerator supplies the first optional surface layer:
- Continental shapes, islands, ocean basins, elevation detail and uplands.
- Stylized spherical 3D value noise with domain warping and multiple frequency bands.
- Independently derived seed channels for terrain, detail, ridges, geology and resources.
- Terrain-dependent clustered iron, copper and fictional Stormglass potential.
- Signed 64-bit seeds without JavaScript number truncation.
- Generator identity planet-surface-v1 and a frozen reference-world fingerprint.
- Sampling that is independent of query order and preview resolution.

Resource values are potential signals from 0–100, not finite deposits or mining yields. The terrain algorithm does not simulate plate tectonics, erosion, weather, climate or hydrology. It does not guarantee a particular continent count or balanced kingdom starts.

### G. Visual atlas and portable review

PlanetAtlasServer provides:
- A read-only localhost interface.
- Arbitrary signed 64-bit seeds.
- Flat atlas and rotatable globe views.
- Elevation and deposit layers.
- Click-to-inspect surface samples.
- Inspection grids of 180×90, 360×180 or 720×360.
- JSON export and exact generator metadata.

PlanetAtlasExporter creates a self-contained HTML review containing three Java-generated worlds. JavaScript displays them; it does not independently regenerate terrain.

| Review seed | Area-weighted land coverage |
| --- | --- |
| 42 | approximately 47.2% |
| 1598 | approximately 32.9% |
| -7 | approximately 51.7% |

The portable file is named wild-deck-planet-atlas.html. It can compare those three worlds offline. Other seeds and resolutions need the Java atlas server.

The globe rendering projects coarse inspection data; it is not detailed ground-level terrain. Colours represent elevation, not generated forest/snow biomes. Atlas pixels do not define the world's minimum spatial resolution.

The atlas is an omniscient development view. A future kingdom must not automatically receive its hidden resource information.

See [Planet atlas](PLANET_ATLAS.md).

## 6. Key files

| File / location | Responsibility |
| --- | --- |
| src/main/java/com/wilddeck/world/Planet.java | Planet geometry and coordinates. |
| src/main/java/com/wilddeck/world/PlanetSurfaceGenerator.java | Deterministic surface and deposit sampling. |
| src/main/java/com/wilddeck/world/PlanetAtlasServer.java | Read-only local atlas HTTP server. |
| src/main/java/com/wilddeck/world/PlanetAtlasExporter.java | Portable review and JSON export. |
| src/main/resources/web/planet-atlas.html | Map/globe viewer, overlays and inspection controls. |
| src/main/java/com/wilddeck/world/WorldSimulation.java | Separate small interaction experiment. |
| src/main/java/com/wilddeck/world/WorldSandboxDemo.java | Executable interaction demonstration. |
| src/main/java/com/wilddeck/app/RndMatchEngine.java | Existing browser quick/full match mechanics and scripted opponents. |
| src/main/java/com/wilddeck/ai/WildDeckAiService.java | Existing AI service in the older state-model path. |
| src/test/java/com/wilddeck/world/PlanetSurfaceGeneratorTest.java | Repeatability, geometry, suitability, clustering and version fingerprint. |
| src/test/java/com/wilddeck/world/PlanetAtlasServerTest.java | Atlas HTTP behaviour and parameter validation. |
| src/test/js/planet-atlas-browser.cjs | Real-browser atlas and portable-preview checks. |

## 7. Verification and repository checkpoints

Latest implementation at the start of this handover: 4f09cdd on feature/diplomacy-v1.

| Checkpoint | Commit | Evidence |
| --- | --- | --- |
| Browser session/save implementation | 88dee56, 8046231 | [176 Java tests and six browser-script checks](https://github.com/Yusuf-cm/wild-deck/actions/runs/39256034986) |
| Small world laboratory | 7642331 | [184 total Java tests, eight new world tests, executable demo](https://github.com/Yusuf-cm/wild-deck/actions/runs/40841070706) |
| Empty planet | 8524243 | [190 total Java tests, six new planet tests](https://github.com/Yusuf-cm/wild-deck/actions/runs/40841583900) |
| Seeded surface and atlas | 4a79e7f | [200 Java tests, six existing browser-script checks, executable demo, portable export and real Chromium atlas checks](https://github.com/Yusuf-cm/wild-deck/actions/runs/40846413278) |
| Atlas documentation | adccddb | Scope, evidence and run instructions recorded. |
| Atlas whitespace cleanup | 4f09cdd | No behavioural changes. |

Atlas browser checks exercised different/repeated seeds, overlays, inspection, globe rotation, exact large seeds, rejected input, exported JSON, mobile layout, and offline rendering without HTTP requests. Flat/globe screenshots from that run were inspected.

Tests verify the implemented scope. They do not establish that the overall creative game, intelligent opponents, climate or future material systems are complete.

The source and exporter are in the repository. The generated portable HTML is an export, rather than a large generated file checked into Git. CI also creates preview artifacts; those artifacts have a retention period, while the exporter remains reproducible from source.

## 8. Run the current work

Requires Java 17 development tools and Maven.

### Atlas

    mvn test
    java -cp target/classes com.wilddeck.world.PlanetAtlasServer

Open http://127.0.0.1:8081. An optional command-line port argument can override 8081.

### Portable preview

    java -cp target/classes com.wilddeck.world.PlanetAtlasExporter target/planet-atlas

Open target/planet-atlas/wild-deck-planet-atlas.html in a browser.

### Separate interaction experiment

    java -cp target/classes com.wilddeck.world.WorldSandboxDemo

The atlas and world laboratory do not require an AI key or the browser-match save key.

## 9. What is not implemented in the generated planet

- Climate, weather, seasons, winds or rainfall.
- Rivers, lakes, drainage, erosion or tectonic simulation.
- Actual biome/ecosystem generation, plants, wildlife or populations.
- Finite resource inventories, extraction, processing or regeneration.
- Magical energy fields, material magical behaviour or research systems.
- Kingdom placement, discovery, private knowledge, teaching or secrecy.
- Material reactions attached to generated terrain or local generated objects.
- Mutable generated-world saves and replay.
- Integration of generated geography with a single authoritative match model.
- An implemented graphical card-game client.
- AI integration or intelligent autonomous responses.

Do not present deposit colours as completed magic, a green height tint as a forest biome, or a stored order as executed gameplay.

## 10. Direction for subsequent discussion

The user wants to proceed layer by layer. The current completed increment is geography plus deposit-potential distribution and inspection.

Before the next implementation, choose the next coherent layer together. Likely choices include basic water/climate foundations, material composition and quantities, or a small local surface model that can host the existing reaction experiment. No one option has been approved as the immediate next build.

Carry forward these principles:
- Seed + version describes initial generation.
- Saves preserve changes after generation.
- Discovery is separate from existence and practical capability.
- Materials and conditions supply shared behaviour across objects.
- New strange materials need limits and consequences.
- Scope should stay small enough to inspect and play with.
- AI remains deferred until the user brings it back.
- Keep the original deployment and paid database untouched.

Do not silently grow this into a full Minecraft-style simulator or add another disconnected match engine. Make each new layer compatible with the intended shared world and clearly state its current integration boundary.

## Related records

- [Live development and playtest log](LIVE_DEVELOPMENT_LOG.md): dated implementation/test history.
- [Planet atlas](PLANET_ATLAS.md): generator details, viewer, limits and verification.
- [Planet foundation](PLANET.md): geometry.
- [World laboratory](WORLD_LAB.md): material-reaction experiment.
- [Browser saves](BROWSER_SAVES.md): persistence behaviour and limitations.
- [Quick match](QUICK_MATCH.md): earlier playable prototype.
