# Smallest world experiment

Three nearby sites: a forest clearing, river bank, and settlement. The experiment reuses WorldMap and adds a small card-independent WorldSimulation. It is a laboratory for shared world rules, not a second match implementation or a new continent.

Things have a location, properties, remaining fuel, health, stored heat charges, carried water, wetness and burning state. The sample contains trees, a wooden gate, stone wall, river water, heat supply, scout and worker.

## Rules under test

- A usable heat source spends one heat charge to ignite reachable dry FLAMMABLE material. Names and terrain labels do not select the effect.
- A world tick consumes one fuel from each burning object, produces smoke and deals one fire damage to exposed mobile actors at that site. Multiple fires do not multiply exposure damage in this first version.
- Fire blocks ordinary entry. Actors can deliberately leave; FIREPROOF actors can enter and resist fire exposure.
- An actor at a WATER_SOURCE collects one water portion. It can douse a combustible object at its site or an adjacent site, extinguishing fire and preventing ignition for two ticks. These small sites represent local reach; this range must not be applied to continent-scale regions without an explicit range model.
- Fuel consumed before extinguishing stays consumed. Burned-out material stays depleted. Smoke clears two ticks after its last emission.
- Invalid actions leave world state, resources and event history unchanged.

The source heat charges and unlimited river supply are explicit experiment inputs. There is no implicit energy creation, automatic resource generation, or instant tree regrowth.

## Run

    mvn test
    java -cp target/classes com.wilddeck.world.WorldSandboxDemo

The demo ignites the forest, advances environmental time, rejects unsafe entry, deliberately retreats the scout, brings water, extinguishes the trees, and reopens movement. It then applies the same ignition rule to a wooden gate and rejects stone.

WorldSimulationTest checks that sequence and alternative responses: preventative wetting/drying, fuel exhaustion, smoke persistence, fireproof movement, incapacitation, local reach, resource costs, invalid actions, detached snapshots and deterministic repeats.

## Verified result — 2026-10-29

[CI run 40841070706](https://github.com/Yusuf-cm/wild-deck/actions/runs/40841070706) passed for code commit 7642331: eight new world tests, 184 total Java tests, zero failures/errors/skips, six browser-script checks, and the executable world demo.

Observed demo: trees ignited; fire blocked entry; scout retreated with health 2/3; worker collected water, extinguished the trees and entered; trees retained 2/3 fuel and resisted immediate reignition while wet. The same ignition rule burned the wooden gate and rejected stone without charging a failed ignition.

## Boundary and remaining work

This increment has no cards, AI calls, opponent policy, match turns, browser UI, world persistence, automatic fire spread, flooding or smoke-based concealment. Smoke is an observable condition only. The demo's responses are chosen explicitly; it does not claim to demonstrate intelligent opponents. Existing match behaviour is not changed. These mechanisms can be evaluated before selecting their integration into the authoritative match state.
