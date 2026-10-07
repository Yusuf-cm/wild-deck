# Wild Deck — Development Process and Engine Documentation

## 1. Purpose of this document

This document records the design journey, playtesting discoveries, rule decisions, engine architecture, and implementation strategy behind **Wild Deck**.

It exists for one reason: **we should never have to rediscover the game from scratch.**

Wild Deck was not designed from a fixed rules document and then implemented. The process worked in the opposite direction:

1. We created a broad card ecosystem.
2. We played live prototype matches.
3. We observed what was fun, broken, boring, exploitable, confusing, or unexpectedly powerful.
4. We changed the rules only when play exposed a real design problem.
5. We converted the strongest discoveries into engine rules.
6. We implemented the engine so that the game supports emergent interactions instead of hard-coded recipes.

The central product idea that survived every successful playtest is:

> **Cards define what exists. Players define what they attempt. AI translates the command. Java decides whether the world permits it.**

Wild Deck is therefore not a traditional fixed-combo trading card game and not an AI storytelling game. It is a **warfare sandbox expressed through cards**.

---

## 2. Original design direction

The early goal was to create a strategy card game with more tension and creativity than conventional party/card games.

The desired experience included:

- warfare
- betrayal
- bluffing
- temporary alliances
- revenge
- negotiation
- contracts
- hidden information
- creatures
- armies
- magic
- disasters
- specialists
- infrastructure
- unexpected combinations
- multiple viable paths to power

The game was deliberately moved away from:

- fixed player classes
- fixed starting objectives
- predetermined deck archetypes
- rigid combo recipes
- a map-heavy civilization simulator
- action-point micromanagement
- guaranteed scripted counters

The design principle became:

> **Wild Deck gives you random pieces. Winning comes from discovering what those pieces can do together.**

A good card should therefore be useful by itself, but become much more dangerous when combined intelligently with the rest of the world.

---

## 3. The interaction model: cards as game physics

The biggest breakthrough was to stop treating cards as isolated instructions and instead treat each card as an object with **properties** and **capabilities**.

A card answers two core questions:

### What am I?

These are its properties/tags.

Example:

```text
Cavalry
Properties:
- ARMY
- HUMAN
- MOUNTED
```

### What can I do?

These are its capabilities.

```text
Capabilities:
- ATTACK
- DEFEND
- OCCUPY
- CHARGE
```

The engine then reasons about interaction between objects.

Examples:

```text
BIOLOGICAL + DISEASE
→ infection may be possible

STONE + DISEASE
→ infection is not possible

ELECTRICAL + CONDUCTIVE
→ electrical propagation may be possible

FIRE + FLAMMABLE
→ ignition may be possible

DEAD + NECROMANCY
→ reanimation may be possible

FLYING
→ aerial movement may be possible
```

This became the foundation of Wild Deck.

---

## 4. The Conservation Rule

The Conservation Rule exists to prevent the creativity system from becoming arbitrary.

> **A combination may rearrange, amplify, transfer, suppress, redirect, or combine properties that already exist, but it may not invent an unrelated capability from nowhere.**

Example:

A player owns:

- Hydra with `REGENERATIVE`
- Mutagen with `ALTER BIOLOGY`
- Bio-Architect with `ENGINEER TRAITS`
- Dragon Egg with `BIOLOGICAL`

The player attempts:

> “Use Hydra biology, Mutagen, and the Bio-Architect to make the dragon regenerative.”

The attempt has a legitimate chain:

```text
Hydra
→ REGENERATIVE exists

Mutagen
→ ALTER BIOLOGY exists

Bio-Architect
→ ENGINEER TRAITS exists

Dragon Egg
→ BIOLOGICAL target exists
```

This can pass the Conservation Rule.

By contrast:

> “Use the same cards to make the dragon teleport.”

This fails unless some legitimate teleportation/spatial capability exists in the available ingredients.

The engine must never contain a special recipe like:

```text
HYDRA + MUTAGEN + BIO-ARCHITECT + DRAGON EGG = REGENERATING DRAGON
```

That would defeat the point.

The engine instead validates the physical/legal basis of the requested transformation.

---

## 5. Action-resolution pipeline

Creative commands are resolved through a structured pipeline:

```text
IDEA
  ↓
REQUIREMENTS
  ↓
LEGALITY
  ↓
TARGET / ACCESS
  ↓
SUCCESS
```

### IDEA

What is the player actually trying to do?

Example:

> “Smuggle poison into Brian's food.”

### REQUIREMENTS

Does the player possess objects capable of performing the requested action?

Possible requirements:

- a Smuggler
- Poison
- a route or access method
- a valid target

### LEGALITY

Does a rule or contract prohibit the action?

For example:

- an active non-aggression agreement may forbid sabotage
- the player may not control the required source card
- a card still in hand cannot act in the world

### TARGET / ACCESS

Can the action physically reach the intended target?

Having Poison does not mean a player can poison any unit anywhere.

Access matters.

### SUCCESS

Only after the action is legal and physically possible do success, reactions, resistance, consequences, and uncertainty get resolved.

A crucial principle is:

> **Creating something and successfully using it are separate questions.**

A legal invention does not automatically guarantee a successful attack.

---

## 6. Card state model

Every physical card/object can exist with:

- owner
- controller
- zone/location
- visibility
- properties
- capabilities
- strength where applicable
- damage
- temporary/permanent states

Typical states include:

```text
HEALTHY
WOUNDED
CRITICALLY_WOUNDED
POISONED
INFECTED
BURNING
PETRIFIED
CURSED
FROSTBOUND
HIDDEN
OCCUPIED
DEAD
```

State changes matter because Wild Deck interactions can chain.

One playtest sequence demonstrated this clearly:

```text
Royal Guard
→ petrified
→ shattered/trampled
→ dead
→ corpse becomes accessible
→ Necromancer raises corpse
→ Undead Royal Guard
```

The game should preserve these world-state transitions rather than replace every transformation with a completely unrelated object.

---

## 7. Zones and hidden deployment

One of the major rule improvements came from distinguishing **hand secrecy** from **hidden Kingdom assets**.

Earlier play allowed specialists to secretly perform actions while apparently remaining safely in hand.

That created an exploit:

> A player could gain the benefit of a deployed card without exposing that card to discovery or attack.

The corrected rule is:

```text
HAND
→ HIDDEN KINGDOM
```

If a card is secretly used inside the player's own Kingdom, it must be privately deployed.

It is hidden from opponents, but it exists in the world.

It can therefore potentially be:

- discovered
- infiltrated
- sabotaged
- attacked
- affected by world events

This preserves secrecy without creating invulnerable invisible assets.

Public deployment reveals the card normally.

Covert deployment inside another player's Kingdom requires legitimate access such as:

- infiltration
- stealth
- tunnels
- disguise
- smuggling
- concealment
- hidden routes

---

## 8. Information model

Wild Deck uses the principle:

> **Players know what they witness, not the game's underlying truth.**

This means:

- public cards/actions are known
- private hands are hidden
- hidden Kingdom assets are unknown unless discovered
- observed consequences may allow inference
- exact causes may remain unknown
- one opponent does not automatically share another opponent's knowledge
- lies and misinformation are allowed
- scouting does not produce omniscience

A Night Raven may see a battlefield.

It does not automatically know:

- hidden cards
- contract terms
- stored resources
- the true cause of every effect
- another player's intentions

This information model became one of the strongest parts of Match #4.

---

## 9. Turn structure

The current alpha turn structure is intentionally light.

A player receives one main card action:

```text
DRAW
OR
PLAY A CARD
```

Cards already deployed may generally be managed continuously, subject to their capabilities, timing, location, resources, reactions, and conflict state.

Examples:

- a deployed army can be ordered to move
- a deployed specialist can continue an ongoing project
- a defending creature can react
- infrastructure can continue its established function

Playing a card means placing it into the world with immediate relevance.

Examples:

- play War Elephants → they may immediately attack if legal
- play Assassin → assassination may be attempted that round
- play equipment → it can be attached immediately
- play Disease → infection attempt is part of the play
- play infrastructure → ongoing effect begins normally

Continuous management does **not** mean unlimited manipulation during combat.

Once conflict is engaged, participating assets are treated as committed/snapshotted unless a valid reaction changes the situation.

---

## 10. Negotiation and contracts

Negotiation is free-form and does not consume generic action points.

There are two different agreement types.

### Verbal agreements

Examples:

- “I won't attack you.”
- “Help me against Michelle.”
- “I'll pay you later.”

These can be lied about or betrayed.

### Official Contracts

Official Contracts are mechanically binding.

Possible terms include:

- Non-Aggression
- Mutual Defense
- Intelligence Sharing
- Alliance Secrecy
- No Proxy Attacks
- No Hostile Cooperation
- Optional Offensive Support

The engine has already begun implementing this distinction.

Immediate transfers such as payment or barter are binding because the assets actually change hands.

Future promises are only mechanically binding if represented by an Official Contract.

---

## 11. Kingdom model

Wild Deck deliberately avoids a full tile-grid civilization system.

The guiding rule is:

> **Your Kingdom is everything you have successfully put into play.**

Cards define relationships.

Examples:

- Walls protect specified assets.
- Watchtowers detect threats.
- Armies defend infrastructure.
- Underground structures create access.
- Equipment attaches to units.
- Tunnels create routes.
- Contracts create social relationships.

A major heuristic is:

> **If a new subsystem can be represented by card effects and world relationships, do not create a whole new management system for it.**

This prevents Wild Deck from drifting into a city-building simulator.

---

## 12. Warfare and victory model

The intended final victory condition is:

> **Last surviving independent Kingdom wins.**

Major warfare concepts identified through playtesting include:

### Destroy / Raid

Eliminate or damage an asset without gaining ownership.

### Capture

Take a specific asset intact.

Earlier prototype rule:

1. attacker declares the asset
2. a successful unit occupies it
3. defender receives a counterattack window
4. if occupation survives, control changes

### Conquest

Take over a weakened Kingdom and inherit surviving assets.

### Annihilation

Destroy the Kingdom so little or nothing remains to inherit.

### Vassalage

A defeated player remains alive and manages their Kingdom but becomes politically subordinate under binding terms.

Match #3 demonstrated that these options create very different strategic incentives.

---

## 13. Resource design

Match #4 exposed the largest economic problem in the prototype.

The entire board effectively reached:

```text
Gold: 0
Wealth: 0
Mana: 0
```

Players had useful cards but could not activate many economic or magical possibilities.

This led to a critical simplification.

Wild Deck has only **three universal resources**:

### Gold

Liquid money.

Used for:

- hiring
- bribery
- bounties
- contracts
- trade
- purchases

### Wealth

General Kingdom economic capacity.

Represents things such as:

- ordinary labour
- supplies
- construction capacity
- administration
- maintenance
- routine development

Used for:

- repairs
- upgrades
- infrastructure
- development
- large projects where generic economic capacity is appropriate

### Mana

Magical energy.

Used only by actions/cards that logically require magical energy.

---

## 14. Materials are not currencies

Wild Deck intentionally separates universal resources from physical things.

The following are **not universal resources**:

- Iron
- Water
- Oil
- Herbs
- Bones
- Corpses
- Heat
- Poison
- Biological tissue
- Stone

These are world objects/materials with properties.

Examples:

```text
Iron Vein
→ provides IRON

Underground Spring
→ provides WATER

Volcanic Shard
→ provides HEAT

Hydra sample
→ provides biological material with regenerative traits
```

An Iron Vein therefore does not automatically generate Wealth.

It generates iron.

That iron may become valuable if the player has a Foundry, Engineer, trade partner, or another legal use.

---

## 15. Current alpha economy

The first Java alpha makes the economy configurable.

The current test configuration is:

```text
Starting Gold: 0
Starting Wealth: 0
Starting Mana: 0

Base passive income:
+1 Wealth per round
```

This is a **test value**, not a permanently locked balance rule.

The purpose is to ensure a game does not become completely economically dead just because no player happened to draw a resource-producing card.

Gold and Mana remain specialized resources that players may need to discover, generate, capture, steal, or trade for.

---

# 16. Playtest history

## Match #1 — Proof that the game can be fun

Match #1 established the core emotional target.

Important moments included:

- Farmland + Village → Settlement
- Spy revealing another player's hand
- Militia + Black Plague suicide attack
- bounty politics
- Iron Shield blocking an attack
- Brian paying Michelle to attack Yusuf
- Michelle accepting payment and betraying the promise
- Blacksmith + Archers creating Forged Archers
- Assassin killing a specialist
- Cavalry capturing assets
- Alchemist + Herbalist crafting engine
- Thunderstorm damaging Walls
- Town being Blighted
- Arcane Antidote removing Frostbind
- late conquest chains

The key lesson:

> The game becomes exciting when meaningful standalone cards collide in ways nobody completely predicted.

---

## Match #2 — Failure caused by boring ingredients

Match #2 overcorrected toward generic materials.

Hands became filled with things such as mundane ingredients that only became interesting after several steps.

The game technically supported creativity, but the individual cards did not feel exciting.

The test was stopped.

Lesson:

> **Physics is not enough. Cards must already be desirable.**

Materials should support powerful cards, not dominate the entire deck.

---

## Match #3 — The warfare breakthrough

Match #3 was the strongest proof of concept.

Major sequence:

1. Crimson Fever and poisoning became information weapons.
2. Covert access was created with Smuggler + Tunnel Map.
3. Brian's provisions were poisoned.
4. Weakened troops created corpses.
5. Necromancer converted battlefield losses into undead power.
6. Blood Moon amplified the undead engine.
7. Storm Drake was killed.
8. Storm Drake was reanimated.
9. Asha was forced into Official Vassalage.
10. Mercenary Assassins infiltrated Michelle.
11. Void Gate used established endpoints to create an army bypass.
12. Bone Colossus was cursed.
13. Instead of treating the curse as a simple penalty, the player used:
   - `BONE`
   - `CONSTRUCT`
   - `REASSEMBLE`
   to disperse the Colossus into bones and reform inside the fortress.
14. Michelle was annihilated.
15. Final combined assault destroyed Brian.

This match produced the clearest statement of what Wild Deck is:

> **A warfare sandbox where cards define the laws of physics and players invent tactics.**

The Bone Colossus maneuver is the flagship example of why Wild Deck cannot rely on pre-authored combo text.

---

## Match #4 — Hidden information, biotech, and economy stress test

Match #4 was slower and initially less exciting, but it exposed many important systems.

### What worked

The user manipulated multiple information states:

- Illusionist fooled Night Ravens into seeing a fake militarized Kingdom.
- Michelle believed the false intelligence.
- A real secret alliance was created with Brian.
- A fake coalition was created with Michelle and Asha.
- Yusuf publicly positioned Hydra as if attacking Brian.
- Brian and Yusuf coordinated a trap.
- Asha and Michelle committed to the false assault.
- Brian detonated Blast Powder under Asha's tunnel approach.
- Yusuf redirected Hydra into Asha's Burrower Queen.
- Brian dealt with Michelle.

The information game worked because opponents acted on different beliefs.

### Hidden biotechnology experiment

Match #4 also produced one of the most important emergent creations in the game.

Available pieces:

- Dragon Egg
- Hydra
- Roc
- Salamander
- Mutagen
- Bio-Architect
- Psionic Crystal
- Beastmaster
- Volcanic Shard

The player decided to combine biological templates during development.

Resulting juvenile creature:

```text
Engineered Psionic Dragon

Properties:
- BIOLOGICAL
- DRACONIC
- FLYING
- FIRE-ADAPTED
- REGENERATIVE
- PSIONIC

Emerging capabilities:
- ATTACK
- BITE
- CLAW
- FLY
- BREATHE FIRE
- RESIST HEAT
- REGENERATE
- PROJECT THOUGHT
- TRANSMIT THOUGHT
- AMPLIFY THOUGHT
- PSIONIC INFLUENCE
```

No card contained this creature.

It emerged from the game state.

That is exactly what the engine is being designed to support.

### What failed

Match #4 also exposed:

- long stretches of passive engine-building
- drawing could be too safe
- resource starvation
- little incentive for decisive conflict
- opponents sometimes behaving like city builders rather than competitors
- resources were assumed to exist without a guaranteed economy

The match was intentionally stopped without forcing a winner.

Lesson:

> A game can be strategically uncertain without being strategically active. Wild Deck must support uncertainty without encouraging stagnation.

---

# 17. Opponent AI design principles

Single-player opponents must follow the same rules as the human player.

They must not:

- receive hidden user information
- invent cards
- retroactively generate perfect counters
- intentionally make weak moves just to create drama
- act as assistants to the protagonist

They should evaluate:

> **Given everything I currently know, what is the strongest legal action I can attempt to improve my chance of being the last surviving Kingdom?**

AI opponents should:

- track what they personally know
- infer from observations
- bluff
- negotiate
- betray verbal agreements
- remember previous actions
- use their actual cards creatively
- exploit states and properties
- form temporary alliances when useful
- attack when strategically justified

This is necessary for the game to remain interesting once human players become highly creative.

---

# 18. AI role versus Java role

The engine follows a strict separation of responsibilities.

## AI responsibility

The AI interprets natural language.

Example player command:

> “Break the Bone Colossus apart, flood the damaged fortress with the bones, and reform inside.”

AI translation might become:

```text
Intent: INFILTRATE_FORTIFICATION

Sources:
- Bone Colossus
- Necromancer

Relevant properties:
- BONE
- UNDEAD
- CONSTRUCT
- MASSIVE

Capabilities:
- REASSEMBLE
- COMMAND UNDEAD

Transformation:
- solid structure -> dispersed bone state

Target:
- damaged fortress interior
```

## Java responsibility

Java remains authoritative.

Java checks:

- does the player control the source?
- is the card deployed?
- does the capability exist?
- is transformation supported?
- does Conservation Rule pass?
- is the target reachable?
- does a contract prohibit it?
- can a reaction interrupt?
- what state changes occur?

The architecture is:

> **AI proposes structured actions → Java validates → Java changes world state.**

AI must not directly mutate authoritative game state.

---

# 19. Java engine architecture

The first Java implementation lives in:

```text
src/main/java/com/wilddeck/engine/
```

Current major components:

## CardDefinition

Represents the immutable definition of a card.

Fields include:

- id
- name
- category
- properties
- capabilities
- strength
- universal resource cost
- notes

## CardInstance

Represents one actual card/object in a running match.

Tracks:

- unique instance ID
- owner
- controller
- zone
- visibility
- states
- damage

This separation allows two copies of the same definition to have completely different states.

## PlayerState

Tracks player-specific authoritative state:

- resources
- hand
- Kingdom
- personal knowledge of cards

This is important because the game must preserve different information states between players.

## ResourcePool

Stores only:

```text
GOLD
WEALTH
MANA
```

It supports:

- affordability checks
- spending
- resource addition
- snapshots

## GameRules

Stores configurable rule values.

Current alpha configuration is exposed through:

```java
GameRules.alphaV1()
```

## GameState

Stores:

- players
- deck
- active player
- current round
- contracts
- whether the main action is already used

## Contract

Stores:

- participants
- terms
- start round
- end round

## GameEngine

Implements turn-state operations such as:

- initialize resources
- start round
- advance round
- draw
- play from hand
- public/hidden deployment
- add contract

## ActionIntent

Structured representation of an attempted action.

Current fields include:

- acting player
- verb
- source cards
- target cards
- target player

This is the beginning of the AI-to-engine interface.

## InteractionEngine

Validates actions.

It already checks concepts such as:

- source ownership/control
- cards acting from hand
- required capabilities
- active non-aggression contracts
- target existence
- Conservation Rule for biological trait engineering

## Decision

Returns whether an attempted action is allowed and identifies the stage at which it failed.

Example:

```text
allowed = false
phase = REQUIREMENTS
reason = Conservation Rule: requested trait has no legitimate source
```

---

# 20. Example: biological engineering in the engine

The code does not care that the source card is specifically named Hydra.

The engine asks:

1. Is the target biological?
2. Does some legitimate deployed template contain the requested trait?
3. Is there a deployed mechanism that can alter biology?
4. Is there a deployed mechanism that can engineer/stabilize the change?
5. Does the player control the relevant pieces?

Conceptually:

```text
requestedTrait = REGENERATIVE

template card contains:
REGENERATIVE

tool contains:
ALTER BIOLOGY

specialist contains:
ENGINEER TRAITS
or
STABILIZE MUTATION

=> legal basis exists
```

This is the pattern that should eventually generalize into the rest of the physics engine.

---

# 21. Card library

The repository contains:

```text
data/wild-deck-card-library-v1.csv
```

It preserves **105 established cards/assets** from the playtests.

The library includes:

- creatures
- military
- specialists
- constructs
- structures
- artifacts
- chemicals
- diseases
- weather
- magic
- contracts
- tools
- materials
- emergent creations

Some entries deliberately contain notes such as:

```text
Exact final effect pending
Capabilities not final
```

This is intentional.

We should not pretend a rule was established when the playtests never actually defined it.

The card library is the canonical starting point for future card expansion.

---

# 22. Automated tests currently included

The current test suite validates important alpha assumptions.

Tests include:

- hidden deployment removes a card from hand
- hidden deployment places the card in the Kingdom
- hidden deployment does not automatically reveal it to opponents
- Draw and Play share the same main-action slot
- Conservation Rule accepts a legitimate regenerative-biological transfer
- Conservation Rule rejects a trait that has no source
- Official Non-Aggression blocks an attack
- passive alpha income provides Wealth only
- Gold remains zero without a source
- Mana remains zero without a source

These tests are important because Wild Deck has many emergent possibilities.

The game needs a stable core underneath that creativity.

---

# 23. Repository implementation history

The repository was created as:

```text
Yusuf-cm/wild-deck
```

The initial implementation was developed on:

```text
alpha-engine-v1
```

The alpha branch introduced:

- Maven / Java 17 project
- engine source
- tests
- README
- card library
- resource system
- interaction validation
- contracts
- hidden information foundations

A pull request was created:

```text
Wild Deck alpha engine v1
```

The pull request was squash-merged into `main`.

The repository now represents the first permanent implementation of the Wild Deck rules discovered through live prototype play.

---

# 24. Systems intentionally not finished yet

The current engine is **foundation-level**, not a complete playable production game.

The following systems still need deeper implementation:

- combat resolution
- reaction windows
- damage and death rules
- corpses
- healing/regeneration timing
- capture and occupation
- conquest
- annihilation
- vassalage
- surrender
- equipment/attachments
- routes and physical access
- tunnels
- portals
- scouting
- observation/inference
- resource-producing infrastructure
- trade and barter
- Gold transactions
- Mana generation and spending
- complex contracts
- alliance obligations
- world effects
- weather interactions
- stealth/detection
- AI opponent decision-making
- natural-language command parser
- multiplayer synchronization
- persistence
- frontend

These should be implemented incrementally and tested through matches rather than all being designed in isolation.

---

# 25. Next development milestones

The preferred implementation order is:

### Milestone 1 — Warfare core

Implement:

- attack declarations
- defenders
- reactions
- wounds
- death
- corpses
- regeneration
- retreat

Goal:

Recreate simple Hydra, Salamander, Sentinel, Cavalry, and Assassin conflicts.

### Milestone 2 — Capture and Kingdom warfare

Implement:

- occupation
- capture
- asset ownership transfer
- conquest
- annihilation
- surrender
- vassalage

Goal:

Reproduce the strategic endpoints demonstrated in Match #3.

### Milestone 3 — World relationships and access

Implement:

- attachments
- containment
- tunnels
- portals
- infiltration
- routes
- protection relationships

Goal:

Make access physically meaningful.

### Milestone 4 — Information engine

Implement:

- player-specific observable events
- scouting
- revealed-to-player state
- false information
- inference-friendly event logs
- hidden deployment discovery

Goal:

Reproduce Match #4's information warfare without leaking authoritative state.

### Milestone 5 — Economy

Implement:

- resource production
- trade
- barter
- Gold payments
- Wealth development
- Mana generation
- resource capture
- upkeep only where useful

Goal:

Avoid Match #4's dead economy without making every action a currency tax.

### Milestone 6 — AI command translator

Input:

> natural-language player command

Output:

> structured ActionIntent / interaction request

The Java engine remains authoritative.

### Milestone 7 — AI opponents

Give each opponent:

- private hand
- personal knowledge state
- objectives
- memory of deals
- legal-action generation
- strategic evaluation

No protagonist advantage.

### Milestone 8 — Alpha Match #5

Run a complete match using the engine rules instead of changing rules manually during play.

The purpose is not merely to finish the game.

The purpose is to discover:

- where the engine disagrees with intuitive gameplay
- where the rules are exploitable
- where pacing stalls
- where cards are boring
- where players discover unexpectedly strong interactions

Only after that should visual card design become a major priority.

---

# 26. Card design philosophy going forward

When new cards are added, follow these rules:

1. A card should be meaningful alone.
2. Its properties should make physical sense.
3. Its capabilities should describe what it can do, not every combo it can join.
4. Avoid recipe text whenever the general physics engine can resolve the interaction.
5. Do not invent resources unnecessarily.
6. Avoid cards that exist only as boring prerequisites.
7. Strong cards are acceptable if other systems can interact with their properties.
8. Information, access, timing, and consequences are part of balance.
9. Randomness should create different strategic problems, not unwinnable non-games.
10. Preserve uncertainty.

---

# 27. Final product direction

Wild Deck should eventually feel like this:

A player looks at a strange random hand containing:

- a Hydra
- a Mirror
- Oil
- an Illusionist
- a ruined mine
- a strange artifact

The game does **not** tell the player the “correct combo.”

The player invents a plan.

They explain what they want to attempt.

The AI translates that plan into structured game language.

The engine checks the world.

Opponents react with their own hidden cards and their own creativity.

The consequences permanently change the game state.

That means two matches using the same deck may develop completely differently.

This is the intended identity of Wild Deck:

> **A card-driven strategy warfare sandbox where creativity is legal only when the world supports it.**
