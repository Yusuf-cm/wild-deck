# Wild Deck Playable Alpha — Playtesting Guide

## Why this build exists

From this point forward, engine work should be tested inside a playable match as early as possible.

The rule is:

> **No major system should live only in unit tests. Every major system must eventually be reachable through the playable session.**

Unit tests protect invariants. Playtests expose bad game design, pacing problems and interactions we did not predict.

## Run the playable alpha

Requirements:

- Java 17+
- Maven 3.9+
- optional Groq API key

Run:

```bash
mvn test
mvn -q exec:java
```

A deterministic seed can be supplied:

```bash
mvn -q exec:java -Dexec.args="12345"
```

The same seed produces the same shuffled alpha deck.

## Groq opponents

Without a Groq key the game uses a deterministic local fallback opponent so the engine is still playable and CI remains offline.

To use Groq:

```bash
export GROQ_API_KEY="..."
mvn -q exec:java
```

Optional:

```bash
export GROQ_FAST_MODEL="openai/gpt-oss-20b"
export GROQ_STRATEGIC_MODEL="openai/gpt-oss-120b"
```

Never commit an API key.

## Current CLI commands

```text
status
hand
board
events
draw
play <hand-index> [hidden]
attack <your-board-index> <opponent-id> <target-index>
regen <your-board-index>
heal <healer-index> <target-index>
occupy <your-board-index> <opponent-id> <target-index>
route <tunnel|infiltration> <opponent-id> <your-board-index> [more-indexes]
attach <equipment-index> <target-index>
conquer <opponent-id>
annihilate <opponent-id>
say <natural language command>     # requires Groq
end
quit
```

## What is already being tested in real play

The executable loop currently reaches:

- seeded random hands
- Gold / Wealth / Mana pools
- per-round Wealth
- Gold Mine and Mana Shrine production
- public and hidden deployment
- combat
- damage
- wounds and death
- graveyards/corpses/wrecks
- regeneration
- healing
- protection interception
- occupation and automatic later-round capture
- tunnel/infiltration routes
- equipment attachments
- conquest
- annihilation
- hidden-information event visibility
- Groq natural-language translation
- Groq opponent planning
- persistent opponent memory
- tactical one-exchange rollouts
- Java validation of AI proposals

## AI memory

Each AI player maintains a compact persistent strategic memory:

- summary
- trust score by player
- threat score by player
- suspicions
- current plans

The AI receives only its own redacted game view and visible recent events.

The same strategic inference call returns both candidate moves and a memory update. This avoids an extra model call every turn.

## Tactical analysis

Before the strategic model acts, Java cheaply evaluates immediately legal attacks.

It supplies estimates such as:

- damage dealt
- expected retaliation
- whether the target dies
- whether the attacker dies
- access basis

These are hints, not authoritative outcomes. Java still resolves the actual action.

## Playtest discipline

When a playtest exposes a problem:

1. Record the seed and round.
2. Record the cards/players involved.
3. Decide whether the problem is:
   - engine bug
   - missing rule
   - balance issue
   - AI reasoning issue
   - UI/command problem
4. Write or update a failing automated test for engine bugs/invariants.
5. Fix the smallest responsible layer.
6. Re-run the same seed where possible.
7. Only then merge.

Avoid adding one-off card-name hacks. Prefer properties, capabilities, states, relationships, access and general rules.

## Known alpha limitations

These are deliberate and should not be hidden:

- The CLI is a test harness, not the final UI.
- Many card capabilities are represented in data but do not yet have an executable resolver.
- The Groq command translator can propose a valid capability that the alpha executor does not yet resolve; the action is rejected rather than guessed.
- AI opponents currently take one selected proposal per turn; multi-action continuous management will come later.
- Negotiation text can be generated, but full interactive offer/counteroffer UX is not built yet.
- Spells/world effects are not part of the first playable deck slice.
- Combat balance values are provisional.
- Structures without combat strength are currently fragile in direct combat and need further playtesting.
- Hidden-deployment plausibility rules need refinement.
- Conquest/vassalage AI strategy is not yet fully integrated into candidate generation.

These limitations are reasons to playtest—not reasons to paper over behavior with special cases.
