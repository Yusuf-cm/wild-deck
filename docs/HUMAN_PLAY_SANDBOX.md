# Wild Deck: human-only playtest

This is the first playable **human-controlled** vertical slice. It works without an AI key and preserves actual game state for the running session. It is still terminal-based: persistence across restarting the program, a visual client, true negotiated trade between distinct human players, crafting, creature capture, and freeform complex actions remain future tasks.

## Run

```bash
mvn test
mvn -q exec:java -Dexec.args="42 --human-only"
```

Use the seeded opening hand to test these commands:

```text
hand
draw
deploy Gold Mine
dashboard
assign Militia to patrol my kingdom
deploy Beastmaster
explore
auto mine on
end
dashboard
hire Enchanter for 3 Gold
open market
list Copper Vein for 2 Gold
sell 1 Copper Vein
```

Not every example will apply to every random opening hand; the deck is seeded, and mining requires exploration plus sufficient Wealth. In human-only mode, Asha/Brian slots skip actions instead of invoking AI, while round advancement and production still occur.

### Rules implemented

- One draw per active-player turn, **independent** of playing cards.
- Any number of cards may be deployed from hand, without generic Gold/Wealth card costs.
- Gold pays for hired specialists and actual stock sales credit Gold.
- Wealth establishes discovered mines; mines produce *physical commodity stock* from the following round.
- Exploration discovers one deposit per new round when an appropriate explorer is deployed.
- Standing orders remain attached to individual deployed cards; automatic development consumes Wealth only when affordable.
- Caravan setup is free; unverified delivery commands cannot generate Gold.

### Limitations and design decisions

- Natural-language support is **phrase-based**, not general AI understanding.
- The initial discovery order is deterministic. Future exploration will use seeded world generation.
- Existing alpha Gold Mine and Mana Shrine production remains in the engine's production system.
- There is not yet an actual marketplace of independent buyers; `sell` models a local market buyer for explicitly listed stock. It does not represent a sale to Asha or Brian.
- Hired specialists are tracked in the management ledger, rather than added as physical deployable CardInstances.
- Human-only mode doesn't make the entire engine multiplayer-ready.
- Generic deck JSON still has legacy cost fields, but deployment no longer consumes those amounts.
- Some older tests may assert the old card-cost/draw-or-play rules and will need migration.
