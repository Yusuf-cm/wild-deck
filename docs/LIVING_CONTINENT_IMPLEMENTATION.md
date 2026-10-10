# Wild Deck: Living Continent implementation notes

## The intended game
One persistent continent hosts four distinct kingdoms. The human controls only `player`; the engine or an AI pilot proposes actions for Asha, Brian and Mira. Each ruler sees their own hand, inventories, resources, deployed cards, relations and local orders. All four share the same authoritative match state and event history. A loss of one structure or entire battlefield does not automatically eliminate a ruler.

## Implemented in this increment
- `RndContinent`: six named regions, including four kingdom capitals, two neutral destinations and adjacency metadata. Geography is informational until movement/ownership change has a resolver.
- `RndContinentCli`: one continent overview with four public panels and the human's private hand, inventory, treasury and standing orders. Opponent-hand inspection does not reveal private cards. One human command loop; opponent turns advance when the human passes.
- Tests cover capital layout, view privacy, and the four-seat turn cycle.
- Existing 200-card seeded catalog and match remain separate from the historical Cycle 18 replay.

### Launch
```bash
mvn -q -DskipTests compile exec:java -Dexec.mainClass=com.wilddeck.app.RndContinentCli
```

From the CLI: `look`, `atlas`, `kingdom asha`, `draw`, `deploy Arcane Tower`, `pass`, `history`, `negotiate asha <terms>`, `trade brian <terms>`, `attack mira`, `scry asha`, and `assign Arcane Tower to <order>`.

## Not yet implemented / do not simulate as working
- Region movement, discovery resolution, territorial conquest, fog of war and travel time
- Full trade acceptance and atomic transfer; treaties with consent, expiry and betrayal consequences
- Mines, buildings and timed production for arbitrary resources; economy sourced from territories
- Creature taming, specialist professions, crafting, recipes, equipment slots, spell duration/counters
- Proper battle phases, reactions, damage, casualties, sieges and victory/elimination adjudication
- State persistence, per-user credentials and remotely accessible independent dashboards
- Truly agentic NPC decision-making: current randomized legal-command policy is a placeholder, not human-level reasoning

## Future technical constraints
1. The Java referee owns all state changes and verifies cost, capability, target, ownership and timing.
2. An AI pilot may inspect **only its own private dashboard** and permitted public information, then submit proposed actions to the referee.
3. All significant actions must be replayable from the seed, versioned catalog and event log. Do not retcon a live match to make it entertaining.
4. The same `MatchState` must back CLI and web clients. Do not create separate disconnected simulations.
5. The 200-card catalog is breadth, **not 200 working effects**. Add resolvers and tests in mechanic families rather than one-off narrative exceptions.
