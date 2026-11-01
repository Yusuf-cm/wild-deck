# Three Kingdoms: canonical chat playtest recreation

**Purpose:** preserve the actual rules and recorded state of the human-led Wild Deck playtest, and use them as executable acceptance criteria. This is **not** an assertion that every interaction is implemented. A special browser mode, `load cycle 18`, loads a partial reconstruction that should be compared against this record.

## Core gameplay agreement

- Cards are generally **free to deploy**, with no invented play costs.
- Gold is currency for hiring, trade, and bribes; Wealth develops physical deposits into mines; Mana powers substantial magic.
- Turn management actions do not advance time. A world production round advances after participating players' turns.
- Mines take time to extract stock and cannot fulfill orders for unproduced resources.
- Opponents **must actually draw and play cards they have**, or explicitly pass. Their private hands remain secret.
- An agreed treaty can be broken deliberately with reputation, political and military consequences. It is not automatically dismissed.
- Players can travel to other kingdoms' territory to explore, trade or fight, with access rules.
- Creatures, crafting, alliances and new situations respond to player intentions; random cards are independent of the current storyline.

## Historical milestones

| Time | Recorded development |
|---|---|
| Initial draws | Player gained Hydra, Necromancer, Militia, Black Plague, Gold Mine, Tunnel Map, Mirror, later Assassin, Soul Lantern, Mana Shrine |
| Early kingdom | Gold Mine and Mana Shrine established; Hydra and Militia deployed |
| Early warfare | Soulkeeper Necromancer created through Necromancer + Soul Lantern; Brian's Cavalry defeated and reanimated as Undead Cavalry |
| Border incident | Stormcaller struck an unidentified suspicious wagon; Brian denied ownership |
| Treaty #001 | Player and Asha non-aggression Cycles 11–16; expired after Cycle 16 |
| Armed expedition | Beastmaster led forces to explore caves, mountains, forest, river and underwater sites |
| Recruitment | Arcane Militia hired for 4 Gold with fire, ward, illusion, frost and restoration roles |
| Forge | Blacksmith deployed free; 10 Iron bought from Asha for 5 Gold; Militia 3→4 STR and Undead Cavalry 5→6 STR upgraded; Hydra plating and Arcane Militia equipment queued |
| Resource exploration | Copper, Crystal, Silver, Gemstone and Sapphire natural deposits developed using Wealth; Gold Mine deployed as a card |
| Creatures | Dire Wolf STR4, Stoneburrower STR2, Griffin STR7, River Serpent STR9 tamed |
| Treaty #002 | Tripartite trade peace accord between player, Asha, Brian for Cycles 16–25 |
| Cycle 17 | River Serpent tamed; underwater Mithril deposit discovered and mine queued pending 4 Wealth |
| Cycle 18 | Mithril mine established for 4 Wealth; starts producing Cycle 19 |
| Cycle 18 diplomacy | Asha export agreement: player gets 80%, Asha 20%; Brian non-exclusive purchases Copper and Crystals |
| Cycle 18 trade | Brian purchased 2 Copper, 1 Magic Crystal plus one paid caravan shipment; Asha sold 1 Silver; fractional Gold used in scenario accounting |
| Cycle 18 vault | Treasure Hunter found 5 Gold and an Ancient Command Tablet, abilities unidentified |
| Cycle 18 recruitment | Enchanter 3 Gold, Master Armorer 2 Gold, Geologist 2 Gold hired |
| Final draw | Ancient Titan STR12 added to the player's hand; not deployed |

## Player's starting checkpoint — after all Cycle 18 decisions

- **Gold: 21.4**, **Wealth: 2**, **Mana: 8**
- Active mines: Gold, Copper, Crystal, Silver, Gemstone, Sapphire, Mithril (seven)
- Mine output per completed cycle: Gold +1, Copper +2, Crystal +1, Silver +2, Gemstone +1, Sapphire +1; Mithril +1 starting Cycle 19
- Player hand: Black Plague, Tunnel Map, Mirror, Assassin, Mirror of Revelation, War Banner, Ancient Titan
- Key deployed units: Hydra, upgraded Militia, upgraded Undead Cavalry, Arcane Militia, Soulkeeper Necromancer, Stormcaller, Beastmaster, Treasure Hunter, four tamed beasts
- Kingdom assets include Mana Shrine, Gold Mine, Royal Trade Caravan, Blacksmith, Enchanter, Master Armorer, Geologist
- Standing orders: exploration, mineral search, treasure exploration, Blacksmith upgrades
- Peace treaty: seven cycles remain at end Cycle 18, terminating after Cycle 25 if honored

**Source-state caveats:** The chat record did not reconstruct exact private Gold/Wealth/Mana balances for Asha/Brian, so the prototype must not present them as historical facts. Not every opponent's prior drawn card or previous chronology is verifiable. The currently seeded opponent turns are temporary demonstration behavior, not the original decisions.

## Acceptance tests to implement incrementally

1. Drawing cannot silently choose a card to fit the narrative; deterministic/provisional mode must be clearly labeled until a shuffled seeded deck is wired.
2. Player may deploy cards without deducting Gold/Wealth; spend Gold only on real transactions.
3. Resource production occurs once per completed world cycle; stock is carried forward.
4. An order for 5 Copper fails unless five units exist; successful trade debits stock and credits money accurately.
5. Forge products require material stock, specialist ability, and processing time.
6. Beastmaster can attempt creature taming with location, defenses and success/failure rules.
7. Official treaties can be voluntarily breached, consequences persist, and hostility must be validated.
8. Asha/Brian draw from separate private hands and may play, pass, negotiate or defend; the player cannot see their hidden cards.
9. Cross-kingdom exploration respects physical access and target secrecy.
10. The game can replay event sequences deterministically and preserve independent player state.
11. Persistent saves survive restarts; players do not share a single global web session.
12. Card creation moves from manually authored prototype definitions to a versioned deck catalog with real seed-based shuffle.

## Implemented by the current scenario referee

- Historical Cycle 18 checkpoint / visible hand and board
- Simple card catalogue and provisional **non-random** draw sequence
- Free deployment, assignment of standing orders
- Three-kingdom bookkeeping, basic scripted opponent draw and play
- Mining at cycle boundaries and relative treaty termination
- Validated refusal to fabricate combat, crafting or trade outcomes

## Not yet implemented in scenario mode

- Exact re-enactment of Cycles 1–17 with all hidden decks, actions and checks
- Generic unit ability resolution, spells, crafting, taming and combination logic
- Physical world map, visiting servers, fog of war and multiplayer authentication
- Real opponent deliberation; the provisional AI-free policy is scripted
- Reconstructed opponents' private treasuries and contracts
- Persistent server data storage and separate accounts

The game should *never* claim these are complete until they become executable, tested mechanics.
