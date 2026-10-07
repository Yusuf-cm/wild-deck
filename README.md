# Wild Deck

Wild Deck is an AI-refereed emergent strategy card game.

**Cards define what exists. Players define what they attempt. Java decides whether the world permits it.**

## Alpha engine rules implemented

- Properties, capabilities, strength, state, zones and visibility
- Gold / Wealth / Mana as the only universal resources
- Iron, water, oil, herbs, corpses, heat, etc. remain physical things rather than currencies
- Configurable alpha economy: +1 Wealth per round; no passive Gold or Mana
- Seeded deterministic deck
- One main action: **Draw OR Play**
- Public deployment and hidden deployment (`HAND -> KINGDOM`)
- Player-specific knowledge
- Binding contracts including non-aggression enforcement
- Structured legality pipeline:
  `IDEA -> REQUIREMENTS -> LEGALITY -> TARGET / ACCESS -> SUCCESS`
- Conservation Rule
- Generic biological trait engineering with no hard-coded combo recipe
- 105 established cards/assets from our Wild Deck playtests

## Why the engine does not hard-code combos

The engine never contains a rule saying:

`Hydra + Mutagen + Bio-Architect + Dragon Egg = Regenerating Dragon`

Instead:

- Hydra supplies `REGENERATIVE`
- Mutagen supplies `ALTER BIOLOGY`
- Bio-Architect supplies `ENGINEER TRAITS`
- Dragon Egg is `BIOLOGICAL`

The interaction engine checks whether the requested transformation is supported by existing properties/capabilities. That is the foundation for player-created combinations.

## Resources

- **Gold** — money: hiring, bribes, bounties, trade
- **Wealth** — labour, ordinary supplies, construction, repair, development
- **Mana** — magical energy for effects that explicitly require it

The exact economy numbers remain configurable; `Rules.alphaV1()` currently uses +1 Wealth per round so a match cannot become economically dead purely because nobody drew a resource producer.

## Run

```bash
mvn test
mvn -q exec:java
```

## Documentation

The complete design history, playtest lessons, rule evolution, engine architecture, and implementation roadmap are documented in:

`docs/WILD_DECK_DEVELOPMENT_PROCESS.md`

## Warfare engine v1

The first warfare layer now supports:

- attack declarations
- a reaction window
- GUARD / DEFEND interception
- deterministic simultaneous damage
- WOUNDED and CRITICALLY_WOUNDED states
- death and graveyards
- BIOLOGICAL -> CORPSE
- CONSTRUCT -> WRECK
- gradual REGENERATE recovery
- biological healing validation
- retreat before resolution
- contract-aware attack legality

Combat arithmetic is deliberately isolated in `WarfareRules` so playtesting can rebalance numbers without rewriting the combat lifecycle.

## Kingdom warfare v1

The Kingdom warfare layer now supports:

- delayed occupation and capture
- one-round counterattack windows
- ownership transfer after successful capture
- defense-broken checks
- conquest of surviving assets
- resource transfer on conquest
- annihilation into corpses / wrecks / ruins
- voluntary vassalage
- forced vassalage after military defeat
- vassals being unable to attack their overlord
- last-independent-Kingdom victory detection

Forced conquest, annihilation, and vassalage currently require:
1. the defender has no effective deployed military defense, and
2. the attacker has already completed at least one occupation against that Kingdom.

That second rule represents a real physical foothold instead of letting a player conquer a Kingdom from across the table.

## Access and relationships v1

The world model now supports explicit card relationships instead of adding large map subsystems:

- `ATTACHED_TO`
- `PROTECTS`
- `CONTAINS`

Enemy access can be established through:

- tunnels
- portals
- infiltration routes
- public surface access
- state-based physical breaches

Examples now represented by engine rules:

- Smuggler + Tunnel Map can establish mapped tunnel access.
- A Void Gate-style card needs both location-linking and gate-opening capability.
- A hidden enemy asset must first be discovered.
- A contained target cannot simply be selected from outside.
- A `BONE` construct with `REASSEMBLE` can exploit a `BREACHED` containing structure without hard-coding "Bone Colossus".

## Official Contract enforcement v1

Official Contracts are no longer just stored metadata.

Hard prohibitions can be mechanically rejected:

- `NON_AGGRESSION`
- `NO_PROXY_ATTACKS`
- `NO_HOSTILE_COOPERATION`
- `ALLIANCE_SECRECY`
- vassal hostility against the overlord

Positive promises become tracked obligations:

- `MUTUAL_DEFENSE`
- `INTELLIGENCE_SHARING`

An attack on a protected participant can create a Mutual Defense obligation. Confirmed threat intelligence can create an Intelligence Sharing obligation. Obligations can be fulfilled or become recorded breaches if their deadline passes.

The natural-language AI will eventually translate custom negotiated clauses into structured contract rules; the Java engine remains the enforcing authority.

## Next engine layers

Richer combat reactions, resource-producing structures, scouting/information events, parameterized custom contract clauses, and the AI natural-language translator.
