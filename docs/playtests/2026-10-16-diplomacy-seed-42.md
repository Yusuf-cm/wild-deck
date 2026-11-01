# Live diplomacy — quick seed 42, 2026-10-16

Build a68189270f75b81087b622679e8b80621fca80d5; Render deployment dep-db8uge17lnhs73ak1b1g reached live at 09:03:42 UTC / 12:03:42 EAT. Started with `new quick game 42`, a natural seven-card hand. No cards, resources or runtime state injected.

Commands below were submitted through the live browser form. Each result was checked before continuing. An early navigation race initially read the prior response for movement; the next accessibility observation confirmed C1 and corrected the evidence. Later commands waited on their specific new response. The attempted second pending offer was correctly rejected; the next offer was made only after settlement.

## 1. `deploy Trading Post`

```text
player deployed Trading Post for free (round production: +1 gold per round)
```

Round 0 | Current: player | Deck: 27; Your resources: GOLD 0, WEALTH 0, MANA 0

## 2. `offer asha peace 3`

```text
Offer D1 sent to asha: no resource exchange; peace 3 completed rounds; expires at round 2. Nothing transferred until acceptance; resources are not reserved.
```

Round 0 | Current: player | Deck: 27; Your resources: GOLD 0, WEALTH 0, MANA 0

## 3. `pass`

```text
Turn completed. Active: player; round 1
Opponent moves: [asha: D1 ACCEPTED; exchange settled once; ceasefire until round 3 (exclusive). Betray explicitly to break it, asha deployed Longbowmen for free, brian deployed Treasury for free (round production: +1 gold per round), mira deployed River Serpent for free]
Match ongoing.
```

Round 1 | Current: player | Deck: 27; Your resources: GOLD 1, WEALTH 1, MANA 0

## 4. `deploy Royal Lancers`

```text
player deployed Royal Lancers for free
```

Round 1 | Current: player | Deck: 27; Your resources: GOLD 1, WEALTH 1, MANA 0

## 5. `move Royal Lancers to C1`

```text
Royal Lancers moved from W1 to C1
```

Round 1 | Current: player | Deck: 27; Your resources: GOLD 1, WEALTH 1, MANA 0

## 6. `offer asha give 1 gold for 1 wealth`

```text
Offer D2 sent to asha: player gives 1 GOLD, asha gives 1 WEALTH; expires at round 3. Nothing transferred until acceptance; resources are not reserved.
```

Round 1 | Current: player | Deck: 27; Your resources: GOLD 1, WEALTH 1, MANA 0

## 7. `pass`

```text
Turn completed. Active: player; round 2
Opponent moves: [asha: D2 REJECTED; no resources moved, asha deployed Mercenaries for free, brian deployed Militia for free, mira deployed Rock Troll for free]
Match ongoing.
```

Round 2 | Current: player | Deck: 27; Your resources: GOLD 2, WEALTH 2, MANA 0

## 8. `move Royal Lancers to N1`

```text
ceasefire forbids entering the partner's capital; betray them explicitly first
```

Round 2 | Current: player | Deck: 27; Your resources: GOLD 2, WEALTH 2, MANA 0

## 9. `attack asha`

```text
ceasefire active; betray asha explicitly before hostile action
```

Round 2 | Current: player | Deck: 27; Your resources: GOLD 2, WEALTH 2, MANA 0

## 10. `offer asha give 2 gold for 1 wealth`

```text
Offer D3 sent to asha: player gives 2 GOLD, asha gives 1 WEALTH; expires at round 4. Nothing transferred until acceptance; resources are not reserved.
```

Round 2 | Current: player | Deck: 27; Your resources: GOLD 2, WEALTH 2, MANA 0

## 11. `betray asha`

```text
Ceasefire broken with asha. Public broken promises: 1. No resource refund; hostile actions are now legal if normal combat rules allow.
```

Round 2 | Current: player | Deck: 27; Your resources: GOLD 2, WEALTH 2, MANA 0

## 12. `offer asha peace 3 (while D3 pending)`

```text
cancel your pending offer to that kingdom first
```

Round 2 | Current: player | Deck: 27; Your resources: GOLD 2, WEALTH 2, MANA 0

## 13. `pass`

```text
Turn completed. Active: player; round 3
Opponent moves: [asha: D3 ACCEPTED; exchange settled once, asha deployed Leviathan for free, brian deployed Undead Cavalry for free, mira deployed Wyvern for free]
Match ongoing.
```

Round 3 | Current: player | Deck: 27; Your resources: GOLD 1, WEALTH 4, MANA 0

## 14. `accept D3`

```text
D3 is already ACCEPTED
```

Round 3 | Current: player | Deck: 27; Your resources: GOLD 1, WEALTH 4, MANA 0

## 15. `offer asha peace 3`

```text
Offer D4 sent to asha: no resource exchange; peace 3 completed rounds; expires at round 5. Nothing transferred until acceptance; resources are not reserved.
```

Round 3 | Current: player | Deck: 27; Your resources: GOLD 1, WEALTH 4, MANA 0

## 16. `pass`

```text
Turn completed. Active: player; round 4
Opponent moves: [asha: D4 REJECTED; no resources moved, asha deployed Cavalry for free, brian deployed Sappers for free, mira deployed Dire Wolf for free]
Match ongoing.
```

Round 4 | Current: player | Deck: 27; Your resources: GOLD 2, WEALTH 5, MANA 0

## 17. `move Royal Lancers to N1`

```text
Royal Lancers moved from C1 to N1
```

Round 4 | Current: player | Deck: 27; Your resources: GOLD 2, WEALTH 5, MANA 0

## 18. `deals`

```text
Diplomacy — breakable promises, not Official Contracts
Your offers (only sender/recipient see pending terms):
D1 player -> asha | no resource exchange; peace 3 completed rounds | ACCEPTED | offer expires R2
D2 player -> asha | player gives 1 GOLD, asha gives 1 WEALTH | REJECTED | offer expires R3
D3 player -> asha | player gives 2 GOLD, asha gives 1 WEALTH | ACCEPTED | offer expires R4
D4 player -> asha | no resource exchange; peace 3 completed rounds | REJECTED | offer expires R5
Public ceasefires:
Public broken promises: {player=1}
offer <kingdom> give <amount> <gold|wealth|mana> for <amount> <resource> [peace <1..10>] | offer <kingdom> peace <1..10>
accept D1 | reject D1 | cancel D1 | betray <kingdom>
NPC values: Gold=1, Wealth=2, Mana=2; accepts affordable non-losing trades; refuses peace from promise-breakers. Peace forbids entering each other's capital or igniting the partner's capital or deployed cards' region.

```

Round 4 | Current: player | Deck: 27; Your resources: GOLD 2, WEALTH 5, MANA 0

## Outcome and limits

Asha accepted D1 peace; D2 losing trade was rejected. Peace blocked N1 entry and an attack without spending resources. D3 exchanged 2 Gold for 1 Wealth exactly once. Betrayal removed D1 and publicly recorded one broken promise; D4 peace was subsequently rejected without an intervening invasion. Entry to N1 then succeeded. Final round 4, deck 27, Gold 2 / Wealth 5 / Mana 0. Reload retained the ledger within the running process.

This run did not re-prove a complete match or durable persistence. Automated tests cover expiry, participant privacy, cancellations, recipient authority, stale funds, overflow, conquest cleanup and terminal locks; those were not all separately browser-tested here. NPC-originated peace and a human accepting an NPC offer were not exercised in this live run.
