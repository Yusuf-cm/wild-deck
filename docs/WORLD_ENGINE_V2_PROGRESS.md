# World Engine 2.0 — verified progress

Date: 2026-10-29. Development branch: `feature/world-engine-v2`.

## M0 / M1

- Parent: `feature/diplomacy-v1`. Existing `PlanetSurfaceGenerator` v1 source left unchanged.
- New `WorldSeedChannels` provides stable, independently derived 64-bit layer seeds.
- New `PlanetRegionGrid` provides hierarchical streaming addresses; coordinates remain continuous and are not tied to atlas pixel size.
- New `PlanetSurfaceV2` defines a separate version and a deterministic terrain sampling entry point. Its initial elevation is intentionally delegated to v1. **This is not new geology, erosion, or climate yet.**
- New `PlanetSurfaceV2Test` checks v1 equivalence, adjacent edges, parent/child alignment, seed channels, invalid inputs, and the longitude seam.
- Full baseline CI passed at commit `55d968a2494edf28681aa3d5d1c77818c9089d68`: https://github.com/Yusuf-cm/wild-deck/actions/runs/40877957640 . Includes Maven tests, world demo, browser-save checks, atlas export, Chromium atlas verification.

## M3 geometry prototype (partial)

- New `LocalTerrainMesh` generates a local triangle heightfield using authoritative planet samples and immutable vertex arrays.
- New `LocalTerrainMeshTest` checks triangle counts, indices, shared tile boundaries, parent/child samples, immutability and input limits.
- **Pending:** CI result for commit `fe41a6b3cbf6fca47ec8762dfabc7f801b616525`: https://github.com/Yusuf-cm/wild-deck/actions/runs/40878147894 .
- **Not yet implemented:** renderer, true fine-scale terrain, stitched different-LOD edge topology, caves, climate, hydrology, erosion, finite deposits, mutable terrain, card interactions on the planet, persistent world modifications, or photorealistic imagery.

## Next gated work

1. Obtain green CI for local terrain meshes; repair any failures.
2. Implement independently seeded high-frequency local detail with exact boundary and parent/child consistency, retaining v1 macro terrain.
3. Add geology and drainage validation before promoting M2.
4. Create a browser 3D viewer only after stable geometry and performance benchmarks.
5. Integrate materials, persistence, and Wild Deck card actions incrementally; do not introduce another match engine.

## Safety

No production deployment, Render billing change, or paid database modifications. CI runs are ordinary repository test workflows.

## 2026-10-29 — Finite resources and climate

- `WorldResourceDeposits` converts the existing mineral suitability signal to finite per-site stock using stable derived seeds. This is a prototype allocation model; geological deposit shapes and mining constraints remain future work.
- `WorldResourceLedger` provides bounded extraction, immutable snapshots, ordered events, and deterministic replay. It is an in-memory overlay, **not durable persistence across process restarts**.
- `ResourceExtractionDemo` is now exercised in CI; full workflow passed at `02790f6061fcd0510e85b7ee102d0ca0ffdb5209`: https://github.com/Yusuf-cm/wild-deck/actions/runs/40878849660 .
- `PlanetClimateV2` adds a seeded first-pass temperature/moisture/biome classification with latitude, elevation lapse rate and coarse spherical moisture noise. It does **not** claim physical atmospheric circulation, river routing, erosion, seasonal weather, or high-resolution ecology.
- `PlanetClimateV2Test` covers deterministic sampling, seam/pole consistency, polar temperatures, moisture bounds and seed variation.
- Climate CI run: https://github.com/Yusuf-cm/wild-deck/actions/runs/40882816987 (check result before marking verified).

### Remaining high-priority acceptance gaps

1. Climate and geography overlays in the atlas, including reference images and statistical validation.
2. Physically motivated geology, river basins, lake routing and erosion-inspired terrain.
3. Finer-scale terrain detail and mixed-LOD crack-free rendering.
4. Site identity tied to spatial coordinates and safe stock initialization from authoritative world objects.
5. Durable world overlay persistence and save schema.
6. Integration into the existing Wild Deck card/match command path and human playtest.

## Regional drainage prototype

- `RegionalDrainageV2` routes each cell to its steepest strictly downhill D8 neighbor and accumulates nonnegative rainfall in descending elevation order. No routing cycles are possible because downstream elevation must decrease.
- Local sinks and tile-edge outlets are left explicit, not artificially filled. This avoids pretending that isolated regional grids are complete planetary watersheds.
- `RegionalDrainageV2Test` checks mass accumulation in a simple chain, basin accumulation, flats, repeatability, bounds and invalid inputs.
- CI for drainage test commit: https://github.com/Yusuf-cm/wild-deck/actions/runs/40883742540 . Confirm success before marking verified.
- Climate CI already passed: https://github.com/Yusuf-cm/wild-deck/actions/runs/40882868544 .

### Drainage limitations

- Accumulation is a unitless moisture proxy, not calibrated discharge in cubic metres per second.
- The current algorithm is regional and cannot route across region boundaries.
- No depression filling, lake overflow, globally consistent river topology, sediment transport or erosion yet.

## Connected hydrology and river-channel milestone

- `ConnectedDrainageV2` samples a contiguous rectangular mosaic of equal-level regions into a common grid, removing artificial internal tile barriers for downhill D8 routing. CI passed: https://github.com/Yusuf-cm/wild-deck/actions/runs/40884063021 .
- `RiverNetworkV2` identifies channel cells using an accumulation threshold, follows downhill chains to a terminal outlet and groups cells by that outlet. It is deterministic for a **fixed mosaic** and resolution. CI for its tests: https://github.com/Yusuf-cm/wild-deck/actions/runs/40884355136 .
- Remaining: global catchment IDs independent of mosaic extent, off-mosaic river continuation, depression filling and lake overflow, erosion, calibrated flow and river persistence in authoritative world saves. No claims of planet-wide completed hydrology.

## 2026-10-29 — Lake spill and composed regional hydrology

- `LakeSpillV2` uses priority-flood over finite regional grids to compute depression spill elevations and acyclic paths toward the mosaic boundary. It does not assume that every basin contains a permanent lake.
- `RegionalHydrologyV2` exposes a combined result containing the connected terrain mosaic, lake spill geometry and river channel extraction.
- `LakeSpillV2Test` and `RegionalHydrologyV2Test` cover depressions, spill termination, input validation and deterministic composition.
- Full workflow for latest implementation: https://github.com/Yusuf-cm/wild-deck/actions/runs/40884912314 (verify outcome).
- **Important**: Current river discharge still follows raw downhill D8 routing and is not yet redirected along lake spill paths. Global river identities, catchment exchange, erosion, persistence and gameplay integration remain outstanding.

## 2026-10-29 — Lake-aware authoritative regional river extraction

- `SpillDischargeV2` accumulates nonnegative rainfall over the acyclic priority-flood spill forest, conserves total rainfall at the mosaic's boundary outlets and exposes mosaic-local terminal outlet indices. Verified CI: https://github.com/Yusuf-cm/wild-deck/actions/runs/40885814735 .
- `RiverNetworkV2.extract(LakeSpillV2.Result, SpillDischargeV2.Discharge, double)` extracts channels from conserved lake-spill flow. `RegionalHydrologyV2.generate` now uses this overload instead of the legacy raw-D8 channel extraction.
- The old D8 extraction overload is retained for compatibility. Integration tests for the lake-aware river flow were added. Latest CI: https://github.com/Yusuf-cm/wild-deck/actions/runs/40886028435 (check status).
- Next limitations: stable IDs across different mosaics, boundary exchange with unloaded regions, realistic seasonal lake storage/evaporation, river incision/erosion, and runtime gameplay persistence.

## 2026-10-29 — Stable sample IDs and boundary export

- `GlobalHydrologyAddressV2` supplies global grid sample coordinates (level, divisions, x, y), independent of the finite mosaic's origin or extent. Tests passed: https://github.com/Yusuf-cm/wild-deck/actions/runs/40886772041 .
- `HydrologyBoundaryV2` exports interior-to-perimeter river links with stable global sample endpoints and their conserved flow. This is an interface for future cross-region transfer; **no receiving-tile injection or unloaded-region routing exists yet**.
- Next: design incoming boundary flux and topological transfer without double-counting shared vertices, enforce conservation between mosaics, then establish stable catchment identities at planet scale. Do not confuse sample-address stability with global river identity.

## 2026-10-29 — Incoming boundary flux and seasonal water budget prototypes

- `SpillDischargeV2.accumulate(lakes, rainfall, incoming)` supports nonnegative injected boundary discharge and propagates it to finite mosaic outlets.
- `HydrologyFluxTransferV2.incoming` matches globally addressed crossing endpoints to receiving mosaic perimeter samples, aggregates incoming discharge, and ignores crossings addressed to other mosaics. Tests added. IMPORTANT: there is **no end-to-end cross-region routing scheduler**, no shared-vertex rainfall ownership, and no automatic integration into `RegionalHydrologyV2.generate` yet.
- `SeasonalWaterBalanceV2.step` provides deterministic rainfall + previous-storage = evaporation + stored + runoff accounting with finite nonnegative inputs. Tests added. IMPORTANT: evaporation fraction and capacity are caller-supplied; no seasonal climate integration, lake-volume geometry or time-stepping orchestration yet.
- CI for new additions: https://github.com/Yusuf-cm/wild-deck/actions/runs/40888086920 and https://github.com/Yusuf-cm/wild-deck/actions/runs/40888126404 (check final status).
- Remaining water work: connected multi-mosaic flux solver, double-count-free rainfall sampling, stable global catchments, ocean/tidal boundary conditions, climate-calibrated seasons, lake dynamics, sediment and erosion, long-run numerical stability and gameplay persistence. Water subsystem is **NOT complete**.

### River incision prototype (same date)

- `RiverErosionV2.erode` produces a bounded, deterministic elevation overlay from spill-route descent and discharge. It does not modify the seed terrain, and has no sediment transport, deposition, re-routing, or persistent state. Tests added; CI pending at time of writing.

### 2026-10-29 — Multi-region hydrology integration

- `HydrologyRainfallOwnershipV2` partitions shared rainfall vertices across independently generated adjacent tiles using west/north ownership, preventing duplicate source contributions. Tests cover shared edges and four-tile corners.
- `ConnectedHydrologyV2` runs a single connected lake-aware hydrology solve across a rectangular region mosaic and exports internal channel links in globally stable sample coordinates. This is a working bounded multi-region calculation, **not** a streaming unloaded-tile solver. Cross-tile links are informational because water is already accumulated in the shared solve and must not be injected a second time.
- Added deterministic, rainfall-conservation integration test. Latest CI is pending; inspect workflow for commit `6702d5955cf664d135f1bb512b8d9fca22adbc5c`.
- Outstanding: independent asynchronous tile-to-tile routing with cycles handled, planet-wide catchment identity, global ocean routing, realistic lake storage and seasons, erosion feedback, and persistence. Do not mark water subsystem complete.

## 2026-10-29 17:49 EAT — Water continuation: global graph, lakes, sediment

### Implemented
- `HydrologyFlowGraphV2`: deterministic topological routing over unique globally addressed samples. Each source is counted once, confluences aggregate, cycles and duplicate sources are rejected, and discharge reaching a currently unloaded downstream address is retained as **pending frontier outflow**. Only nodes reaching an explicitly declared terminal get resolved catchment anchors; unknown frontiers do **not** masquerade as stable planet-wide catchments. Graph code + tests: commits `b7d7d5de995b0b14c50e480673650616b9dd73af`, `a47b7148f9a05ee8af538e02075f5c0d4bc51893`; both CI passed.
- `HydrologyGraphAdapterV2`: converts finite priority-flood mosaics into globally addressed nodes, distinguishes known sea-level outlets from unresolved land boundaries, and permits validated one-step boundary continuation. Test covers conservation for seeded connected terrain and resuming into a neighboring sample. Commits `10c980720ca2f9482cee743b7a10dda354af557a`, `d24180d1dcff9a85dff34633c0c45d7e33f35ee1`; CI passed.
- `HydrologyMosaicJoinV2`: merges overlapping shared-vertex samples without counting rainfall twice, continues a route when one tile has a known downstream and another has an unresolved boundary, and rejects conflicting known routing. Commit `82dd081401e303e07d174ac3109ef3ae4f159f05`; tests in `8368c946af8bff8e19e115ea7b4e0cb8d13fb758`. CI status must be checked.
- `LakeReservoirV2`: finite-volume storage with bed-elevation/area stage curve, spill capacity, bounded evaporation, and conservation. `LakeBasinExtractorV2`: finds connected flooded depressions in finite priority-flood mosaics and builds reservoir geometry. Tests cover separated and connected basins, spill overflow and geometry validation. Commits `bf8821b4930115ea047aa2f943a90fa616625e02`, `1958b49ece26c36529ad5418d554f47f881c68b1`, `89c768d717535a7caa94529af4d78e1efde4d2fc`, `b721fdeec0efa7827d0585064c0187ed03c9ff07`; CI passed for all.
- `SedimentBudgetV2`: erosion volume to mass conversion, capacity-limited downstream sediment routing, deposition, and explicit outlet export, with mass conservation and cycle rejection. Tests include incision-to-sediment integration. Commits `2422bc454f3ea62ba3884474dbbf5d2e7fc139ff`, `d36ea679658a1b10e9025b2bbfb963623694d629`, `b62221c9e3a5225a5183a9dc7c19f6e40bb56a7b`. CI status must be checked.

### Acceptance gaps (NOT COMPLETE)
1. Unloaded tile continuation requires a previously known external one-step route. No on-demand planet-wide terrain oracle, global priority-flood, or persistent frontier queue exists. Pending flux is conserved but does not magically choose an unknown downstream.
2. Overlapping independently solved mosaics can disagree about spill routes. The join rejects such conflicts; it does not repair them. A connected mosaic solve is still needed for authoritative local topology.
3. Resolved catchment IDs are global sample anchors only when the full downstream chain reaches a known terminal. A stable planet-wide basin map and coastline/ocean routing are not yet available.
4. Lake extraction/volume calculations are finite-grid approximations. Reservoirs are not yet driven by persistent seasonal climate, and cross-mosaic lake identities/capacities are provisional. No tides, groundwater, floodplain or ice/snow storage.
5. Sediment deposition is not fed back into terrain and river rerouting; sediment transport capacity is caller supplied. No multi-season erosion simulation or runtime save/load integration.
6. No global physical-unit calibration or large-world performance benchmarks. These systems remain testable foundations, not a completed planetary hydrology engine.

### CI
- Branch: `feature/world-engine-v2` only; never merged into `main`.
- Workflow history: https://github.com/Yusuf-cm/wild-deck/actions?query=branch%3Afeature%2Fworld-engine-v2
- The prior corrected integration CI passed: https://github.com/Yusuf-cm/wild-deck/actions/runs/40888556437
- Newest sediment, merge and integration workflows may still be running; check exact latest commit before marking the entire change set verified.

### Follow-up: frontier snapshot format and route validation
- `HydrologyFrontierCodecV2` serializes pending boundary discharge in deterministic global-address order and rejects malformed/duplicate/invalid snapshots. This supports a future unload/reload persistence adapter, but **does not itself save anything to disk or coordinate asynchronous region loads**. Commits `f4f8f67b5e064efdf7a65d57cc524b231740de53`, `75c116a30cd5fdf584427b28df8c89ddad005a32`. CI pending.
- Global graph routes now reject nonlocal sample teleportation, with regression coverage. Commits `9b1b1467b5feb1471cd8269af0ebd68c0a897a18`, `bb40d7085b242d5d452eeb01d4a891520da56c7e`. This intentionally does not yet support seam-wrapped or pole-normalized planetary neighbors.
- The shared-vertex merge test passed CI: https://github.com/Yusuf-cm/wild-deck/actions/runs/40896018996 .
- The erosion-to-sediment integration test passed CI: https://github.com/Yusuf-cm/wild-deck/actions/runs/40896144502 .

### End-to-end regression
- `HydrologyEndToEndV2Test` checks seeded terrain + climate + lake-aware flow, global-address graph water conservation, pending frontier snapshot roundtrip, finite lake reservoir storage, river incision and downstream sediment conservation in one integration scenario. Commit `53d2a13fd6ad7029138fe4cf76d714601ad1abaa`; CI must pass before acceptance. This test does not prove planet-wide streaming or multi-year stability.

### Duplicate boundary-delivery protection
- `HydrologyTransferLedgerV2` accepts a directed adjacent-sample transfer at most once per epoch, ignores exact replays, rejects conflicting replays, aggregates independent incoming confluences, and supports an immutable in-memory snapshot/restore. Tests cover epoch separation and replay. Commits `a6bf3bb3fdace6ef9c4bca28e5500e54213624ee`, `5e1a769ad6d0063e81ee91388b510420d6232fee`. CI pending.
- **Still required**: an atomic durable transfer store and a scheduler to reconcile region loading, route discovery and hydrologic time. The ledger alone does not make asynchronous streaming transactional.

### 2026-10-29 — Merged graph epoch orchestration

- Added `HydrologyEpochSolverV2`: merges adjacent/overlapping globally addressed flow-node collections using `HydrologyMosaicJoinV2`, applies explicitly supplied prior-epoch incoming frontier water at loaded addresses, then uses `HydrologyFlowGraphV2.solve` to produce terminal and unresolved frontier outflow. Unit tests cover shared rainfall deduplication, unresolved flow, prior-epoch injection and cycle rejection.
- Important: callers must persist and consume prior-epoch incoming exactly once, using `HydrologyTransferLedgerV2` and their durable runtime. The helper does not schedule asynchronous region loads or atomically consume snapshots. Passing same previous-epoch water repeatedly will repeat it.
- The global solver follows explicit known graph links only; it does not infer terrain beyond the loaded mosaic, nor provide a planet-wide physically calibrated watershed simulation.

### 2026-10-29 — One-time incoming epoch consumption

- Added `HydrologyEpochInboxV2`, an in-memory inbox built on the idempotent transfer ledger. It rejects late deliveries to consumed epochs, prevents re-claiming an epoch, and snapshots/restores both receipts and consumption markers.
- Added tests for duplicate receipt, one-time claim, snapshot restore, conflicting replay and epoch isolation.
- Important: the snapshot and world update **must be committed atomically by a future persistent runtime**. This class does not guarantee crash-safe exactly-once delivery on its own.

### 2026-10-29 — Durable-state serialization contracts

- Added `HydrologyInboxCodecV2`, a deterministic versioned text codec for boundary transfer receipts and consumed epoch markers. Tests cover stable roundtrip, consumed-state replay rejection, malformed input and duplicate receipts.
- Added `HydrologyCheckpointV2` immutable checkpoint value pairing an opaque serialized world state with a validated inbox snapshot, plus restoration tests.
- These classes provide **serialization and checkpoint contracts, not actual crash-safe database persistence**. The production adapter must atomically write the checkpoint, recover after failures, and coordinate exactly-once acknowledgement with world-state advancement. No production storage was modified.

### 2026-10-29 — Local checkpoint rollback protection

- `HydrologyCheckpointStoreV2` now rejects overwriting a newer checkpoint with an older epoch and rejects conflicting contents at an already committed epoch. An identical checkpoint retry is a no-op.
- Added tests for idempotent retries, epoch regression and same-epoch conflicts.
- The store is single-writer and local-filesystem scoped. It relies on atomic rename support and does not coordinate concurrent writers, databases or multiplayer world processes.

### 2026-10-29 18:53 EAT — Hydrology checkpoint, cross-region dispatch and seasonal lake continuation

**Implemented on `feature/world-engine-v2` only:**

- `HydrologyCheckpointStoreV2` persists one checksummed binary checkpoint containing both the world overlay and the consumed-water inbox. It writes and fsyncs a sibling temporary file, atomically renames it over the prior checkpoint, then fsyncs the directory. The reader rejects corruption, truncation, unsupported versions and invalid inbox payloads. Tests cover replacement, restart restoration, orphan temporary files and corrupted payloads. The store also rejects epoch regression and conflicting same-epoch rewrites while permitting idempotent retry. This is **single-writer local-file persistence only**, not a multi-process/distributed transaction.
- `HydrologyRiverIdentityV2` provides stable, resolution-specific reach IDs based on adjacent global sample endpoints and catchment IDs anchored to *verified terminal outlet samples*. Unresolved frontiers intentionally receive no fabricated catchment ID. Tests verify stability across rainfall changes and node ordering.
- `HydrologyFrontierDispatchV2` exports individual source-to-unloaded-destination boundary receipts rather than collapsing a confluence into one ambiguous transfer. It leaves routes with no known downstream unresolved, and validates its graph/result pairing. Integration tests verify two upstream branches crossing an unloaded boundary, idempotent delivery, restored inbox, one-time consumption and downstream conservation. Shared samples are deduplicated by `HydrologyMosaicJoinV2` before dispatch.
- `HydrologyEpochInboxV2.claim` now aggregates/validates incoming water before marking an epoch consumed, so overflow does not irreversibly consume an epoch. Regression test added.
- `SeasonalLakeCycleV2` runs multiple finite-volume lake steps with caller-supplied rainfall depth, contributing catchment area, runoff fraction, upstream inflow and evaporation depth. Tests check wet/dry periods, storage, spill, mass conservation and invalid inputs. This is **not** calibrated seasonal climate or a coupled global river/lake runtime.

**CI verification status:** prior checkpoint/identity commits passed, including https://github.com/Yusuf-cm/wild-deck/actions/runs/40905755089 and https://github.com/Yusuf-cm/wild-deck/actions/runs/40905506937 . Frontier dispatch integration initially failed because the graph/result mismatch regression exposed missing validation; fixed at `26fe3b2e7faf5a5f7a0561f2ed07886f8518209d`. **Full workflow PASSED** at that fix: https://github.com/Yusuf-cm/wild-deck/actions/runs/40906052449 (Maven tests, resource replay, browser save, atlas export and browser verification all passed). This includes the seasonal lake and overflow regression tests committed earlier. Check the newest documentation-only workflow separately: https://github.com/Yusuf-cm/wild-deck/actions?query=branch%3Afeature%2Fworld-engine-v2 .

**Remaining acceptance gaps:** (1) durable runtime scheduler and exactly-once acknowledgements across multiple independent processes, (2) authoritative planet-wide downstream routing, seam/pole wrapping and verified ocean outlets, (3) cross-mosaic stable lake geometry and catchments across unknown terrain, (4) coupled climate-derived rainfall, snow/groundwater, calibrated time steps, (5) sediment/erosion feedback into persistent terrain and rerouting, (6) performance and long-horizon conservation tests. The local checkpoint file is not wired into the game save pipeline. Do **not** claim global hydrology complete.

### 2026-10-29 18:56 EAT — Restarted cross-region water acceptance

- `HydrologyPersistentFrontierV2Test` now exercises the actual local checkpoint store, not just an in-memory codec: two independent upstream edges export water into an unloaded receiver, transfer receipts are saved to disk, a restored inbox rejects duplicate deliveries, the receiver consumes water once, and the resulting world state and consumed markers are committed together for the next epoch. A second restart rejects replay and preserves the downstream outlet volume. Commit `c863f9eca32ce1fe92c6db02f38ac7ec6f770cd9`.
- **Full CI passed:** https://github.com/Yusuf-cm/wild-deck/actions/runs/40906358337 . Prior full workflow after the dispatch validation fix also passed: https://github.com/Yusuf-cm/wild-deck/actions/runs/40906052449 .
- This proves a **single-process, single-writer, local-file** unload/reload scenario. It does NOT prove multi-process exactly-once delivery, arbitrary crash injection at every filesystem instruction, distributed persistence, or authoritative routing across unknown planetary terrain. The production game save/load pipeline is still not wired to this store.

### 2026-10-29 — Checkpointed water epoch coordinator

- Added `HydrologyDurableEpochV2.commit`: stages a cloned inbox, claims a simulation epoch, merges/solves loaded region graphs with incoming water, then atomically writes the paired world/inbox checkpoint using the existing local store. Returns the committed result and staged inbox for the caller to adopt.
- Integration tests cover water conservation across a boundary, disk reload and consumed-epoch replay rejection, cycle/solve failure without mutation, and checkpoint-write failure without mutation.
- Scope: local single-writer checkpoint only. Caller must adopt the returned inbox and restore both world and inbox from disk after restart. No production database, multiplayer concurrency, real world simulation loop, or automatic region scheduling is wired yet.

### 2026-10-29 — Sequential checkpointed world runner

- Added `HydrologyWorldRunnerV2`: opens an existing checkpoint or initializes one; exposes committed epoch/world state; accepts future-epoch water transfers; advances one epoch by staging water consumption, solving loaded graph nodes, and checkpointing world/inbox together. Reload restores committed state and consumed water receipts.
- Tests exercise multiple epochs, restart, incoming water consumed only in its target epoch, and failed-solve rollback.
- Explicit limits: single-writer local filesystem, no automatic region discovery/loading, no terrain-driven route updates, no wall-clock scheduler, no complete world-state serializer. The runner is a deterministic integration boundary, not a complete running planet.

### 2026-10-29 — Bounded deterministic region scheduling

- Added `HydrologyRegionSchedulerV2` with a provider interface, stable row-major order, deduplicated focus neighborhoods, configurable radius and maximum region budget, and overflow validation.
- Wired `HydrologyWorldRunnerV2.advanceRegions` to load selected regions before committing the next hydrology epoch.
- Added scheduler and runner tests for overlap, deterministic ordering, budget failures, arithmetic overflow, and focused-region advancement.
- The provider currently supplies prepared graph nodes; automatic terrain generation, region cache management, route reconciliation across unloaded tiles and player-interest scheduling are not yet implemented.


### 2026-10-29 — Durable receipts, cross-region frontier queue, terrain windows, seasonal lakes and erosion feedback

**Branch safety:** all implementation and test commits in this section target `feature/world-engine-v2` only. No PR/merge to `main`, production deployment or paid infrastructure change.

**Implemented and verified:**

- `HydrologyCheckpointStoreV2.saveIncoming` now allows *monotonic* future-epoch receipt additions to the existing world checkpoint without rewriting committed world state or consumed markers. Normal `save` still rejects conflicting same-epoch replacements. `HydrologyWorldRunnerV2.receive` stages the receipt and writes the updated checksum-protected atomic checkpoint **before acknowledging** it. Tests cover restart before advancing, duplicate receipt idempotence, conflicting replay, rejected rollback, rejected receipt deletion/consumption changes, and failed write leaving in-memory state unchanged.
- `HydrologyDurableEpochV2.commitAndQueueKnownFrontier` and `HydrologyWorldRunnerV2.advanceAndQueueKnownFrontier` export known one-step unloaded-boundary edges as **next-epoch** idempotent receipts and commit them with world state and the consumed incoming epoch. The solver's `prepare` method exposes exactly the merged/injected graph used for both solving and frontier conservation validation. Integration tests cover two-source confluence, disk restart, downstream consumption, no replay, and fail-closed behavior when a route has no known downstream.
- `HydrologyTerrainWindowV2` generates one connected seeded terrain/climate/lake-spill mosaic and its globally addressed flow nodes across a bounded rectangular region window. Shared vertices are sampled only once inside the window. Tests cover 2x2-region conservation (81 unique samples), deterministic regeneration, matching rainfall at overlapping global addresses, and bounds.
- `ErodingWatershedV2` performs a bounded incision overlay step, then recomputes priority-flood routing and discharge on the modified finite terrain. Tests check deterministic bounded cuts, source terrain immutability, zero-strength behavior and conservation of rainfall after rerouting. **No sediment deposition or persistent terrain mutation is claimed.**
- `SeasonalLakeCycleV2.simulateSubsteps` subdivides seasonal rainfall/inflow/evaporation into bounded equal increments while reporting one aggregate result per season. This reduces the dry-lake start-of-step evaporation bias. Tests check a filling lake, substep=1 equivalence, conservation and invalid budgets.
- `HydrologyCrossRegionAcceptanceV2Test` exercises shared-vertex rainfall deduplication, two-branch confluence, replay-safe unloaded boundary delivery, and stable verified outlet/reach identity when downstream terrain is known.

**CI (explicitly checked):**

- Initial commits `8961aebe47fc85ad718e953a8da7a9c20b7c48dc` and `3ace888a92037429e78672ce3016d78029005457` had failures: one test incorrectly expected an exact idempotent checkpoint retry to throw, and another test omitted a two-coordinate address helper. Both were corrected in `a6a1a8b446f08619051f641c3e9cf77a2ec88f1c` and `c898648d817a85405447476d89d0312c23f011b3`.
- **Full CI PASSED** after both corrections: https://github.com/Yusuf-cm/wild-deck/actions/runs/40915632220 (`c898648d817a85405447476d89d0312c23f011b3`).
- **Full CI PASSED** for connected terrain windows and finite erosion feedback: https://github.com/Yusuf-cm/wild-deck/actions/runs/40915799128 (`d4e6114e0e6ab2c76543def0f4b97f5678028c67`).
- **Full CI PASSED** for seasonal lake substeps: https://github.com/Yusuf-cm/wild-deck/actions/runs/40915880215 (`53c277762ffd73370be8141b567953bf568d1524`).
- Cross-region acceptance test commit `001052617fcdffe7ee38757d6802c4d7bf5575b1`: **Full CI PASSED**: https://github.com/Yusuf-cm/wild-deck/actions/runs/40915960178 . This includes the shared-vertex/confluence and stable outlet-identity acceptance tests.

**Unfinished acceptance gaps (hydrology NOT complete):**

1. Unknown unloaded-terrain downstream routes still cannot be inferred. `advanceAndQueueKnownFrontier` fails closed rather than dropping parked water; a durable route-discovery/parking queue is required.
2. Receipts target a specific next epoch. If the receiving region is unavailable then, advancement fails safely; there is no collision-free multi-epoch deferral or region-load orchestration for delayed receivers.
3. Connected finite windows may disagree at their outer edges; no global priority-flood, seam/pole normalization, stable planetary watershed catalog or verified ocean boundary conditions. Global catchment IDs remain outlet-anchored only for fully known paths.
4. Seasonal substeps are numerical approximations with caller-supplied climate inputs and areas. Lake volumes, cross-window lake identity, snowpack, groundwater, floodplain storage, infiltration and long-run calibration are missing.
5. Erosion reroutes a returned finite-grid overlay but does not persist terrain changes, conserve sediment through deposition, or couple evolving river/lake geometry to gameplay.
6. The runtime remains single-writer local-file scoped. Distributed locking, game save integration, full-region cache management, physical units/time calibration and large-world performance/long-horizon conservation tests are still outstanding.

### 2026-10-29 — Durable multi-epoch parking for incoming boundary water

**Branch:** `feature/world-engine-v2` exclusively. No main merge, PR, deployment or paid infrastructure changes.

**Implemented:**
- `HydrologyEpochInboxV2` now tracks **per-receipt delivery markers** separately from original source-epoch consumption. `claimAvailable(epoch, loadedAddresses)` consumes only receipts whose receiving global vertex is present in the loaded graph, while keeping receipts for unloaded destinations under their original (source epoch, source edge, destination) identity. Repeated later epochs cannot inject already-delivered water twice. It validates aggregation before mutating delivery markers.
- The versioned `HydrologyInboxCodecV2` now **writes v3 snapshots** containing deterministic `D` delivery rows, while still reading legacy v2 snapshots and inferring delivered receipts for already-consumed epochs. Invalid or duplicate markers are rejected. `HydrologyCheckpointStoreV2.saveIncoming` forbids receipt-only updates from altering delivery history.
- Both `HydrologyDurableEpochV2.commit` and `commitAndQueueKnownFrontier` now use loaded-address-aware receipt claims, so `HydrologyWorldRunnerV2.advance`, `advanceRegions`, and `advanceAndQueueKnownFrontier` can progress while other destination regions remain unloaded. A later restart and region load injects the deferred water exactly once.
- Added `HydrologyDeferredReceiptsV2Test`: multi-epoch disk restart and exactly-once delivery, normal advance delivery, legacy v2-to-v3 migration, aggregation overflow without consumption, delayed confluence conservation and receipt-only checkpoint mutation rejection.

**CI checked:**
- Earlier baseline `653bcb711194ccc6ca951a727a9253599248aa30`: **passed**, https://github.com/Yusuf-cm/wild-deck/actions/runs/40916166119 .
- First v3 codec edit `5129f8dafc89811bb16c631da8c87ca020b8d513` failed due to an escaped newline typo; fixed by `3037e02023361b99b2051d4cdb296e01d94d60d6`. The latter **passed**, https://github.com/Yusuf-cm/wild-deck/actions/runs/40925226771 .
- Deferred receipt implementation `20896fdb73c406696ce259161c2ec31e632a3f92`: **passed**, https://github.com/Yusuf-cm/wild-deck/actions/runs/40925258140 .
- Initial multi-epoch restart/legacy/overflow acceptance tests `3b8e65f7369588acc23f465f2c98eb6afc8bc3e9`: **passed**, https://github.com/Yusuf-cm/wild-deck/actions/runs/40925293302 .
- Additional tests and invariant enforcement at `4ccb5a594472868cb84895a29413aad1ddc9495e`, `64ba868203e077cfa516b98e3bcec21f11a1ff9a`, `42763d76d1c5038f675fd97a4115e821a74df778`: **CI in progress at documentation time**, check https://github.com/Yusuf-cm/wild-deck/actions?query=branch%3Afeature%2Fworld-engine-v2 before marking them passed.

**Still unfinished (do not mark hydrology complete):**
1. This solves **delayed delivery of already known edges**, not routing across unknown terrain. A frontier node with no verified downstream still causes `commitAndQueueKnownFrontier` to fail closed. There is no global terrain route oracle or persistent unknown-route reservoir.
2. No automatic policy to prioritize/load regions holding deferred water. Receipt history grows without pruning; safe garbage collection, bounded storage and long-horizon benchmarks are required.
3. Global longitude-seam/pole routing, authoritative planetary outlet catalog, multi-window lake and catchment identity, and consistent cross-window priority flood are not solved.
4. Seasonal rainfall, evaporation and erosion remain approximations; lake storage and sediment feedback are not coupled to durable changing terrain, snowpack/groundwater, or calibrated climate.
5. Persistence is a single-writer local-file checkpoint. No distributed transaction, concurrent-writer safety, production game-save integration, or multi-process exactly-once guarantees.

### 2026-10-29 — Deferred-water load priority hints

- `HydrologyRegionSchedulerV2.pendingRegions` derives deterministic row-major region hints from **undelivered** incoming receipts. Samples on shared boundaries are assigned to the west/north owner to avoid ambiguous ownership. Mixed sampling resolutions fail explicitly rather than silently combining incompatible region indices.
- `HydrologyWorldRunnerV2.pendingWaterRegions` exposes these hints after checkpoint restoration, allowing a caller to prioritize regions where parked water awaits delivery. Tests verify hints across a restart, boundary ownership, and removal once water has been delivered.
- This is a **hint API**, not an automatic terrain loader, verified downstream route finder, or memory/cache scheduler. The existing `HydrologyRegionSchedulerV2.load` still needs a caller-provided region node provider.
- Code/test commits: `400d4a0427fd55767729edb231a4065b5deedd43`, `529663e93e3887231065e6addc3c81106ee27cdb`, `922c81864ecb8827d22f7a6da8a11ee187f956f7`, `f7a470b931c66ef9e4bc2db9dbe7766278d3bc74`. **CI pending at writing**; inspect https://github.com/Yusuf-cm/wild-deck/actions?query=branch%3Afeature%2Fworld-engine-v2 .

### 2026-10-29 — Verification update for delayed discharge and region hints

- Full workflow **PASSED** for multi-epoch parking, legacy snapshot migration, and overflow rollback: https://github.com/Yusuf-cm/wild-deck/actions/runs/40925293302 (`3b8e65f7369588acc23f465f2c98eb6afc8bc3e9`).
- Full workflow **PASSED** for delayed two-branch confluence and receipt-only checkpoint anti-forgery: https://github.com/Yusuf-cm/wild-deck/actions/runs/40925435492 (`42763d76d1c5038f675fd97a4115e821a74df778`).
- Full workflow **PASSED** for deterministic pending-region ownership and runner hint integration: https://github.com/Yusuf-cm/wild-deck/actions/runs/40925581968 (`f7a470b931c66ef9e4bc2db9dbe7766278d3bc74`).
- Additional tests reject mixed-resolution region hints (`a90303a4f14495b9ccab480716514e8548fb0be8`) and verify that two different source epochs of the same river edge can both wait for a later loaded destination without collision (`d15cf91c9078e21ff1159e583b08cca500802da7`). **Their full CI workflows are pending at this writing**: https://github.com/Yusuf-cm/wild-deck/actions?query=branch%3Afeature%2Fworld-engine-v2 .
- The system now preserves known-edge incoming discharge across arbitrary numbers of unloaded epochs in the tested single-writer runtime. **Unknown downstream routing, world-scale physical calibration, bounded ledger retention, and actual automatic terrain loading remain unimplemented.**

### 2026-10-29 — Latest test verification

- Mixed-resolution pending-region hint rejection test: **full CI passed** for `a90303a4f14495b9ccab480716514e8548fb0be8`, https://github.com/Yusuf-cm/wild-deck/actions/runs/40925705905 .
- Same-edge multi-source-epoch delayed receipt test: **Maven test step passed** for `d15cf91c9078e21ff1159e583b08cca500802da7`; full workflow browser/atlas steps still running at this check, https://github.com/Yusuf-cm/wild-deck/actions/runs/40925750288 . Do not count the whole workflow as green until it finishes.

### 2026-10-29 — Terrain halo routing, pending-water load orchestration, river identities and numerical safety

**Scope:** All changes on `feature/world-engine-v2` only. No merges/PRs to main, production changes, or paid infrastructure.

**Implemented this run:**
- `HydrologyTerrainWindowV2.generateCoreWithHalo` generates one deterministic connected hydrology solve over a clipped surrounding terrain halo, then exports only globally addressed core samples. The halo supplies one-step downstream evidence for core-boundary water instead of falsely declaring a core-edge outlet. The larger window does not duplicate shared rainfall vertices. It is a bounded finite-window approximation, not a planetary routing oracle.
- `HydrologyWorldRunnerV2.advanceTerrainCoreAndQueue` connects seeded terrain/climate generation directly to checkpointed frontier dispatch. Known outward edges are queued as durable receipts; unknown routes still fail closed.
- `HydrologyRegionSchedulerV2.loadWithPending` combines explicit focus and parked-water destinations into a deterministic deduplicated load plan, checks the region budget before invoking the provider, and protects against pathological oversized radius loops. `HydrologyWorldRunnerV2.advancePendingRegionsAndQueue` loads that plan and atomically advances/queues water. A restart integration test follows a parked receipt into one loaded region, queues its known edge into the next unloaded region, reloads from disk and delivers the same water once.
- `SpillDischargeV2` rejects non-finite downstream accumulation and invalid discharge/outlet records instead of allowing infinite water to propagate. `RiverErosionV2` rejects non-finite incision and caps a single cut at the original downstream-bed descent to avoid manufacturing an uphill reach through over-incision.
- `HydrologyRiverIdentityV2.knownReaches` exposes stable globally addressed reach IDs for verified one-step edges even when the outlet is not yet resolved. Catchment IDs are still assigned **only** after a physically confirmed outlet is available; no speculative catchment ID is fabricated.

**Tests and verification (distinguish full CI from Maven-only):**
- Baseline before edits: full CI **PASSED** for `98137ead0705beda8190e1afd0e2a3379b49abfd`, https://github.com/Yusuf-cm/wild-deck/actions/runs/40925881445 .
- Deterministic halo core uniqueness, conservation, and real-planet-edge unresolved route tests: full CI **PASSED** for `976925cae5f1b4cb955009af72259286f1ae5803`, https://github.com/Yusuf-cm/wild-deck/actions/runs/40934661622 .
- Pending-water scheduling, budget rollback and two-region restart handoff tests: full CI **PASSED** for `e8a5e81074907acad29989bc727937171debe2a1`, https://github.com/Yusuf-cm/wild-deck/actions/runs/40934741474 .
- Seeded terrain-core checkpoint/restart integration: full CI **PASSED** for `a6fafad7ae48e02c19669af546e70e09ae3efab2`, https://github.com/Yusuf-cm/wild-deck/actions/runs/40934829812 .
- Downstream overflow and erosion numerical-guard tests (`a6b26cfbbb5f6fa369cce6c80c42d21261b3feb2`, `047ee84ebf6783a6ca9fcd65280265ea891caeeb`) had **Maven tests passed** while full browser/atlas CI was still running at documentation time. River reach identity tests (`33b43ed4fa026bee99a68162db86b6325ee9d898`) likewise require final full-CI confirmation. Check https://github.com/Yusuf-cm/wild-deck/actions?query=branch%3Afeature%2Fworld-engine-v2 .

**Hydrology acceptance gaps still unfinished:**
1. Halo-based routes are not authoritative outside the finite sampled window. Different independently generated windows can disagree at their edges; no planet-wide depression filling, guaranteed global river topology, automatic route reconciliation or full-world catchment catalog.
2. Region provider still supplies prepared graph nodes. The pending-water scheduler chooses load priorities but does not implement terrain cache eviction, dynamic neighbor exploration, seamless global longitude/polar geometry or distributed region ownership. The terrain-core method covers a bounded seeded window only.
3. Water with **no known downstream address** still fails closed; there is no durable unknown-route reservoir with eventual route discovery.
4. Lake volumes, wet/dry seasons and erosion overlays are not durably coupled to evolving terrain and sediment transport. Groundwater, snowpack, infiltration, physical calibration and multi-window lake identity remain open.
5. Receipt history remains unbounded; no safe garbage collection or large-scale long-horizon benchmark. Checkpointing remains single-writer local-file only, without concurrent-writer protection or production game-save integration.

### 2026-10-29 — Final CI confirmation for numerical guards and stable river reaches

The previously pending full workflows have now **PASSED** (not merely Maven tests):
- `a6b26cfbbb5f6fa369cce6c80c42d21261b3feb2`: https://github.com/Yusuf-cm/wild-deck/actions/runs/40934912883 .
- `047ee84ebf6783a6ca9fcd65280265ea891caeeb`: https://github.com/Yusuf-cm/wild-deck/actions/runs/40934918896 .
- `33b43ed4fa026bee99a68162db86b6325ee9d898`: https://github.com/Yusuf-cm/wild-deck/actions/runs/40934989499 .

These checks establish the finite-grid numerical and identity regression tests; they do **not** establish authoritative global routing, lake/sediment persistence, calibrated physical units, or multiplayer-safe checkpointing.

### 2026-10-29 — Per-epoch conservation audit, long-horizon delayed-water regression and seasonal catchment cycle

**Changes committed only to `feature/world-engine-v2`:**
- `HydrologyConservationV2.verify` independently checks each uniquely owned loaded graph's finite-volume accounting: local water (including delivered inflow prepared as node water) must equal verified outlet discharge plus parked/unloaded frontier discharge within a bounded floating-point tolerance. Internal graph discharge is intentionally not counted again. Both durable epoch commit paths now run this audit before writing their checkpoint. Audit tests deliberately inject lost/duplicated water and mismatched source sets.
- `HydrologyLongHorizonConservationV2Test` exercises six sequential epochs, three known boundary transfers, multiple unloaded waiting epochs, restart after every stage, duplicate receipt rejection, delivery markers and final downstream outlet conservation (5 + 2 + 3 + 1 = 11 units). This is a bounded integration regression, **not** a planet-scale endurance test.
- `SeasonalCatchmentCycleV2` carries per-cell storage between wet/dry seasons using `SeasonalWaterBalanceV2.step`; returns cumulative runoff, evaporation and final storage, with defensive array snapshots and per-cell conservation/validation tests. Evaporation remains a caller-supplied fraction, not climate-calibrated potential evapotranspiration.
- `HydrologyReceiptBudgetV2` audits durable parked versus already delivered receipt volumes and pending destinations, with deterministic ordering and explicit overflow rejection. Tests cover delayed confluence through serialized restoration, duplicate replay, and delivery accounting.

**Verification (full CI, not merely unit test execution):**
- Initial branch baseline `c3a5392b0480729458667427c38f2c208ff487d2`: **PASSED** https://github.com/Yusuf-cm/wild-deck/actions/runs/40935218169 .
- Conservation audit implementation `ca2799d76af5b1b7520833bbfdf01ddc7e89260c`: **PASSED** https://github.com/Yusuf-cm/wild-deck/actions/runs/40943441610 .
- Conservation rejection tests `17b2b5816dceb81b701a66c7f4d74c437ab3ffda`: **PASSED** https://github.com/Yusuf-cm/wild-deck/actions/runs/40943462048 .
- Durable-commit audit integration `7a718f8063053f1c6c7f3a8f928d003b9769445c`: **PASSED** https://github.com/Yusuf-cm/wild-deck/actions/runs/40943478522 .
- Six-epoch delayed-water regression `fb5d71579e86726bf1c713728a25ad2240ac03a3`: **PASSED** https://github.com/Yusuf-cm/wild-deck/actions/runs/40943516051 .
- Seasonal catchment implementation `63344bb093a2399671eee5028d977ed138a8068a`: **PASSED** https://github.com/Yusuf-cm/wild-deck/actions/runs/40943592663 .
- Seasonal catchment tests `b5f37151195ba2a4ab97e8b577cff515c48ab398` and receipt budget implementation/tests `2ab9ec1b8c16ad58609fff8425530d1b063ce770`, `7628039dac79f99988ee31a36a936b5b617c2742`: **full CI still running at this documentation update**. See https://github.com/Yusuf-cm/wild-deck/actions?query=branch%3Afeature%2Fworld-engine-v2 . Do not mark these as passed until completed.

**Still unfinished / blocked by architecture rather than these tests:**
1. Authoritative routing across independently loaded terrain windows, longitude seam and polar adjacency, and globally resolved outlets/catchments. Finite halos cannot establish a planet-wide drainage invariant.
2. Unknown downstream routes still fail closed; water parked on a known edge is supported, but arbitrary terrain exploration and a persistent unresolved-route reservoir are not.
3. Dynamic terrain loading/cache eviction and player-interest scheduling; region providers are still caller supplied. Full game-save integration and concurrent/multiplayer-safe checkpoint transactions remain missing.
4. Lake water volume, catchment storage, sediment and erosion overlays are not durably evolved together in the live world; calibrated infiltration, groundwater, snow, evaporation and time stepping remain open.
5. Receipt history is unbounded, and long-horizon stress/property testing at realistic world scale remains outstanding. Receipt summaries are observability, **not** safe history garbage collection.

### 2026-10-29 — Full CI follow-up and runtime receipt observability

- Seasonal catchment wet/dry conservation and defensive-array tests `b5f37151195ba2a4ab97e8b577cff515c48ab398`: **full CI PASSED** https://github.com/Yusuf-cm/wild-deck/actions/runs/40943620719 .
- Receipt budget implementation `2ab9ec1b8c16ad58609fff8425530d1b063ce770`: **full CI PASSED** https://github.com/Yusuf-cm/wild-deck/actions/runs/40943704011 .
- Receipt budget delayed-confluence serialization/overflow tests `7628039dac79f99988ee31a36a936b5b617c2742`: **full CI PASSED** https://github.com/Yusuf-cm/wild-deck/actions/runs/40943729837 .
- Exposed `HydrologyWorldRunnerV2.receiptBudget()` to inspect parked and already delivered receipt water without consuming it (`0b01ea5ff142fb0ce01b1de083224f618446dc42`). Extended six-epoch integration assertions (`1412779b624166b0544ee7b5a4bfb127b8bfb48f`) to distinguish 11 units of original water from 22 units of *cumulative boundary receipts* across three edges. The latter is **not** new rainfall and must never be added to a global water budget.
- The runner observability and new assertions require final full CI verification; see https://github.com/Yusuf-cm/wild-deck/actions?query=branch%3Afeature%2Fworld-engine-v2 . Global routing, lake persistence, calibrated erosion, and multiplayer-safe save integration remain unfinished.

### 2026-10-29 — Final full CI confirmation for this run

All previously pending workflows **PASSED**, including Maven tests, world experiment, resource replay, browser save controls and atlas checks:
- Documentation/complete integration baseline `c0ef7a1aacbe413438906ca1f888e14765444cfa`: https://github.com/Yusuf-cm/wild-deck/actions/runs/40943794813 .
- Runner receipt-budget accessor `0b01ea5ff142fb0ce01b1de083224f618446dc42`: https://github.com/Yusuf-cm/wild-deck/actions/runs/40943852002 .
- Six-epoch receipt-budget regression `1412779b624166b0544ee7b5a4bfb127b8bfb48f`: https://github.com/Yusuf-cm/wild-deck/actions/runs/40943871106 .
- Documentation update `7c4bdccf7e6ca6df05ac92142622f344cddf33e4`: https://github.com/Yusuf-cm/wild-deck/actions/runs/40943903423 .

**Verified scope:** finite loaded-graph water conservation, durable known-edge receipts through multiple unloaded epochs and restarts, deterministic pending/delivered receipt accounting, and a prototype multi-season catchment storage budget. **Not verified/unfinished:** planet-wide authoritative routes (including seams and poles), physically calibrated climate and lakes, persistent sediment/terrain feedback, receipt garbage collection, distributed transactions, and production gameplay integration.

### 2026-10-29 — Global-sample downhill routing and checkpointed lake storage (latest run)

**Scope:** Changes confined to `feature/world-engine-v2`. No merges, PRs, production deployments or paid services.

**Baseline:** Existing full CI for `0e09c802db9ec5dce559170aa3d6f03268d1f115` was **PASSED**: https://github.com/Yusuf-cm/wild-deck/actions/runs/40944092167 . No pre-existing CI failure required repair.

**Implemented:**
- `HydrologyGlobalSlopeRouterV2` chooses one strictly downhill D8 neighbor from a caller-supplied global elevation sampler, using deterministic tie-breaking independent of loaded window extent. Downhill edges to unloaded neighbors retain stable global addresses; flats and depressions remain unresolved rather than invented outlets. `planetElevations` samples canonical seeded planet terrain at global addresses. `seededOwnedInteriorRegion` emits unique moisture-derived water sources per interior tile with west/north shared-vertex ownership, rejecting seam/polar tiles pending authoritative geometry. Climate moisture requires a caller-supplied volume scale and is **not calibrated rainfall**.
- Added routing tests for independently loaded windows matching the combined solve, confluence water conservation, stable reach IDs, two frontier transfers through checkpoint restart and exactly-once delivery, flat sink fail-closed behavior, shared-boundary seeded elevations, and deduplicated independently seeded interior tiles.
- `HydrologyLakeStateV2` provides deterministic, versioned, explicitly opt-in lake-volume serialization within the existing checksummed atomic world+inbox checkpoint. Each lake key is a caller-verified globally addressed basin anchor. Existing opaque world-state payloads are **not** automatically migrated or reinterpreted.
- `HydrologyLakeEpochV2` advances caller-provided lake basins through seasonal substeps, retains lake storage across unloaded epochs and restarts, and injects verified lake spill **once** into a loaded outlet graph vertex before the durable routed-epoch commit. Missing outlets, changed basin capacities and invalid lake inputs abort before commit. Catchment runoff feeding a lake **must not also be supplied as graph local rainfall**, or the caller would double-count it.
- `LakeReservoirV2` now rejects overflowing potential evaporation instead of silently treating non-finite evaporation as valid water removal.
- Lake tests cover codec canonicalization/malformed input, wet/spill/dry conservation over multiple restarts, unloaded lake preservation, failed geometry/outlet rollback, and separate upstream receipt plus lake spill conservation.

**Verified full CI at documentation time:**
- Global-sample downhill routing implementation `0fd920684793253c328cf25195c7b0073566f24f`: **PASSED** https://github.com/Yusuf-cm/wild-deck/actions/runs/40952196331 .
- Independently loaded route/confluence/restart tests `84649866feba12eddecbef5711dc5683aedfab6d`: **PASSED** https://github.com/Yusuf-cm/wild-deck/actions/runs/40952237348 .
- Lake-state codec `e53a64c0034f597bc7501a41bb883d17dd6477df`: **PASSED** https://github.com/Yusuf-cm/wild-deck/actions/runs/40952328601 .
- Initial lake epoch implementation `e2ab76b18ba22cee26996159c63be6b6477b7ffd`: **FAILED** (Java compact-record constructor assignment); fixed in `15c63c19b56366dfa848ee0248b6be73f71fbe68`, **PASSED** https://github.com/Yusuf-cm/wild-deck/actions/runs/40952403729 .
- Lake storage, spill and restart acceptance `324468ba775c8bf90ad70c429f44a78e4c581619`: **PASSED** https://github.com/Yusuf-cm/wild-deck/actions/runs/40952473425 .
- Lake evaporation overflow guard and regression `ba7411071804cba2a8cbc348192ff75509b433cd`, `282350524bd4787f060a4a50406982650358eac7`: **PASSED** https://github.com/Yusuf-cm/wild-deck/actions/runs/40952501420 and https://github.com/Yusuf-cm/wild-deck/actions/runs/40952521589 .
- Seeded canonical terrain sampling `ed7f7e47939137d5f193d396c9aa8310af663aea`, boundary regression `88527447ee5419b483876306402afe1db7172274`, owned-source implementation `458cd23f61a625831c7d2b69cb38d386adc272ca` and ownership test `8f22e9de13a17cc8e39db5ee6567b9efe3a10f4a`: **PASSED** https://github.com/Yusuf-cm/wild-deck/actions/runs/40952587435 , https://github.com/Yusuf-cm/wild-deck/actions/runs/40952635834 , https://github.com/Yusuf-cm/wild-deck/actions/runs/40952717789 , https://github.com/Yusuf-cm/wild-deck/actions/runs/40952789819 .
- Additional lake mass-budget and separate river-receipt integration assertions `37e1bd1203706645863ebcd7a7b929842bea41fb`, `8ea391d83c6a56c8c0b06794271a3c01572dd29e`: **full CI pending** when this paragraph was written. Check https://github.com/Yusuf-cm/wild-deck/actions?query=branch%3Afeature%2Fworld-engine-v2 before claiming these passed.

**Unfinished acceptance gaps:**
1. Global D8 downhill routes are window-independent *where strict descent exists*, but cannot drain closed depressions, resolve equal-elevation flats, prove basin-wide outlet identity, or reconcile terrain edits. Planetary priority-flood/global depression filling and authoritative catchment topology remain unimplemented.
2. Longitude seam and pole geometry are still noncanonical; the new seeded ownership API explicitly rejects edge regions. World-scale water volume and ocean-boundary conservation remain unverified.
3. Lake IDs, basin geometry and outlet ownership remain caller-verified; no global multi-window lake extraction, sediment-changing basin geometry, lake-routing discovery or terrain erosion persistence. Lake payload is opt-in, not integrated into production world saves.
4. Climate moisture is not rainfall volume, no calibrated seasonal forcing, groundwater, infiltration, snow, evapotranspiration, or full watershed/river/lake coupling. Caller must prevent double-counting lake catchment inflow.
5. Pending receipt history is unbounded. Persistence remains local single-writer; no distributed transaction, automatic region cache eviction, large-scale stress test or multiplayer-safe production integration.

### 2026-10-29 — CI follow-up: lake/river accounting and numerical guards

- Lake mass-budget assertion `37e1bd1203706645863ebcd7a7b929842bea41fb`: **full CI PASSED** https://github.com/Yusuf-cm/wild-deck/actions/runs/40952843173 .
- Mixed lake spill and independent upstream receipt acceptance `8ea391d83c6a56c8c0b06794271a3c01572dd29e`: **full CI PASSED** https://github.com/Yusuf-cm/wild-deck/actions/runs/40952944194 . The test verifies 10 units of lake spill plus 4 units of separately delivered river water become 14 units at the outlet, with no later replay.
- Progress-document baseline `a77c127b53e2d5a67eb86b960dc1fd283537ee6d`: **full CI PASSED** https://github.com/Yusuf-cm/wild-deck/actions/runs/40953013888 .
- Additional strict-downhill overflow rejection `c05930861c77acb7b96a36e3cccf81b157e90b91`, extreme-elevation test `e58585665d3c1bd657edaae84aa44ba4bc499733`, and verified-outlet-only catchment identity test `0b5d874fddd0c9ba9357f0f1bd96b0323c88bbed`: **full CI pending** at this documentation update. These do not address seam/pole topology, depressions, calibrated lake physics, or global basin discovery.

### 2026-10-29 — Final CI verification for downhill routing and lake state run

The previously pending full workflows are now **PASSED**:
- Downhill elevation-difference overflow guard `c05930861c77acb7b96a36e3cccf81b157e90b91`: https://github.com/Yusuf-cm/wild-deck/actions/runs/40953060919 .
- Extreme finite-elevation overflow regression `e58585665d3c1bd657edaae84aa44ba4bc499733`: https://github.com/Yusuf-cm/wild-deck/actions/runs/40953089706 .
- Catchment IDs assigned only after a verified outlet is loaded `0b5d874fddd0c9ba9357f0f1bd96b0323c88bbed`: https://github.com/Yusuf-cm/wild-deck/actions/runs/40953157388 .
- Documentation follow-up `d5658747056ad8d79003d6a5050f0576af10ca5a`: https://github.com/Yusuf-cm/wild-deck/actions/runs/40953214806 .

**Verified boundaries:** globally addressed strictly downhill edges and independently owned interior rainfall sources, single-writer lake storage persistence with spill routed into a loaded outlet, and finite-grid water conservation across restart. **Not proven:** depression drainage, seam/pole geometry, globally reconciled basin IDs, terrain erosion persistence, climate calibration, bounded receipt history, distributed persistence or complete production integration.

### 2026-10-30 — Verified-outlet depression spill, erosion and sediment feedback

**Branch restriction:** all changes on `feature/world-engine-v2`; no main-branch writes, PRs, deployments, or paid infrastructure changes. Baseline `bd448453ecc492094215588edeac1c214487320d` full CI **PASSED**: https://github.com/Yusuf-cm/wild-deck/actions/runs/40953381691 .

**Implementation and acceptance:**
- `LakeSpillV2.fillToVerifiedOutlets` adds a bounded deterministic minimax/priority-flood spill plan seeded **only** from caller-verified outlet cells. No arbitrary window boundary becomes a fake ocean. Explicitly reports conditional spill surfaces and acyclic paths for flat or depressed cells. These paths are *not* active discharge until enough water is stored to reach the spill elevation. `6b191b1ceb17dd24dd3729fc407d41b27c823ae1` and tests `82a9c053678aea48408be13ec7e3cfc3649f0e45`: **full CI PASSED** https://github.com/Yusuf-cm/wild-deck/actions/runs/40960603980 .
- `ErodingWatershedV2.advanceToVerifiedOutlets` now runs bounded incision and post-erosion rerouting without inventing edge outlets. `b85fa4084ee88ab75942abe4f2b8d0f4a3692d38`: **full CI PASSED** https://github.com/Yusuf-cm/wild-deck/actions/runs/40960628359 . Conservation and deterministic-incision test `29633c02c93d0abc5450cb88b7efa07f0da77df2`: **full CI PASSED** https://github.com/Yusuf-cm/wild-deck/actions/runs/40960644527 .
- `SedimentLandscapeV2` adds an opt-in one-step mass-conservative sediment transport/deposition overlay. Incision produces sediment mass, finite transport capacity deposits excess mass, deposit height modifies terrain, and drainage is recalculated over the new terrain. Density, cell area and capacity are caller-supplied; no seed terrain is mutated. Implementation `a1e00978d2870c7ca8f1ac22bc2efe7be475e6f4` and conservation/rollback tests `8a8aee0d0a47e27063e9af32646bf359868326a5`: **full CI PASSED** https://github.com/Yusuf-cm/wild-deck/actions/runs/40960702206 .
- `VerifiedLakeBasinIntegrationV2Test` checks a bounded verified-outlet depression, extracted hypsometric lake capacity, wet/dry seasonal volume balance, evaporation, overflow and routing to a terminal outlet. `b844cdedc418ae9d7e93b379c598d68f9d66508c`: **full CI pending** at initial documentation.
- `HydrologyRiverIdentityV2.resolvedReaches` now independently re-solves supplied nodes and rejects catchment evidence from a different graph. This prevents mismatched solved results from fabricating stable reach/catchment assignments. `0002e937ee330c15d40d6af29bcce9ddaeef6fe1`, test `cec4bbc575de31363a5ab02f3bb8c082a02c6941`: **full CI pending** at initial documentation.

**Unfinished and deliberately not claimed:** priority-flood paths are conditional and only valid within the *supplied complete window and verified outlet assumptions*. Unknown terrain beyond the window can change true spill height; this is not a global basin oracle or automatic river routing through unfilled lakes. Seam/pole topology, globally reconciled basin IDs and outlet certification, seasonal climate calibration, snow/groundwater, durable evolving terrain/sediment/lake geometry, receipt garbage collection, global-scale performance and distributed multiplayer save integration remain outstanding. The new sediment overlay is in-memory only and has no production persistence adapter.

### 2026-10-30 — Final CI confirmation for verified-outlet and sediment run

All initially pending full workflows have now **PASSED**, including Maven tests, finite-resource replay, browser save controls, planet export and atlas browser checks:
- Verified lake basin, two-season storage/evaporation/overflow conservation and terminal routing `b844cdedc418ae9d7e93b379c598d68f9d66508c`: https://github.com/Yusuf-cm/wild-deck/actions/runs/40960758371 .
- Graph-solution validation before assigning resolved reach/catchment IDs `0002e937ee330c15d40d6af29bcce9ddaeef6fe1`: https://github.com/Yusuf-cm/wild-deck/actions/runs/40960801152 .
- Mismatched river graph identity rejection regression `cec4bbc575de31363a5ab02f3bb8c082a02c6941`: https://github.com/Yusuf-cm/wild-deck/actions/runs/40960815090 .
- Initial implementation/verification documentation `82b3b93697f91fd385b6029fbeaa7644cfc639f0`: https://github.com/Yusuf-cm/wild-deck/actions/runs/40960889804 .

**Acceptance is bounded:** finite known terrain with caller-verified outlets, conditional spill surfaces, deterministic sediment mass bookkeeping and in-memory erosion/deposition overlays. None of these CI results establish global outlet truth, a physically calibrated annual water cycle, or durable evolving terrain across independent windows.

### 2026-10-30 — Generated terrain hydrology region provider

- Added `HydrologyGeneratedRegionProviderV2` implementing the bounded region scheduler's provider interface. It generates one-region connected drainage from seeded `PlanetSurfaceV2` and `PlanetClimateV2`, adapts lake spill routing to global hydrology addresses, and applies shared-vertex rainfall ownership.
- Added reproducibility, valid node-count and invalid region-address tests.
- Caveat: independent one-region priority-flood routing can disagree on shared boundaries. Mosaic joining deliberately rejects contradictory routes. A future shared multi-region solve or authoritative boundary routing is required for robust adjacent region streaming; this provider does not claim to solve that.

### 2026-10-30 — Independent seeded D8 region loading and durable terrain evolution

**Scope and baseline:** changes exclusively on `feature/world-engine-v2`, no production or main-branch changes. The preceding source-formatting fix `47cee79b96ae72c8e328426a478e7c8fd21bbd7c` completed full CI **PASSED**: https://github.com/Yusuf-cm/wild-deck/actions/runs/40961390337 .

**Verified region-loading work:**
- `HydrologySeededSlopeRegionProviderV2` (`e87d6d60ad1c5a17b428599d11825c423327a183`) uses globally sampled seeded elevation and canonical east/south vertex ownership to provide strictly downhill, window-independent D8 routes for interior regions. No shared vertex rainfall is injected twice. **Full CI PASSED:** https://github.com/Yusuf-cm/wild-deck/actions/runs/40968142395 .
- Adjacent independently loaded seeded-region regression (`b6559f772be41a924de5484801143bd138a884c2`) verifies 32 unique samples from two adjacent 4x4 owned tiles, deterministic merge equality with a single global routing pass, conservation, caller-verified outlet ownership, and explicit failure at unsupported planet-edge regions. **Full CI PASSED:** https://github.com/Yusuf-cm/wild-deck/actions/runs/40968163054 .

**Terrain persistence work:**
- `HydrologyTerrainOverlayV2` (`6aa57893dab537e46a02a56e0aefd9b897acfebc`) adds an opt-in, canonical, versioned global-address elevation overlay codec and a bounded sediment evolution step that starts from persisted edited terrain and returns updated terrain. The original seeded terrain remains immutable. Its world-state string can be saved using the existing single-writer atomic hydrology checkpoint and can nest the opt-in lake state codec. **Full CI PASSED:** https://github.com/Yusuf-cm/wild-deck/actions/runs/40968229595 .
- Checkpoint/restart, repeated evolution, codec, lake-codec nesting, and invalid-grid tests (`8679cdb8215850260630a92581c5251c3637a390`): **Maven tests PASSED; full CI still running** at this documentation edit: https://github.com/Yusuf-cm/wild-deck/actions/runs/40968262483 .
- Persisted downstream rerouting after a terrain elevation change and 16-step deterministic sediment/water mass-budget regression (`28614506d3894f2b4664b0c40edf225407b5d9a4`): **full CI pending** at this documentation edit: https://github.com/Yusuf-cm/wild-deck/actions/runs/40968329220 .

**Acceptance limits and unfinished work:** The new D8 provider covers only *interior* regions and strictly descending routes; it does not discover routes through pits, flats, ocean/longitude seams or poles. The older per-tile priority-flood provider is retained and can still disagree at adjacent boundaries. There is no authoritative global depression spill, verified planetary ocean outlet model, or globally reconciled lake/catchment identity. The terrain overlay persists only when callers explicitly encode it into their world-state checkpoint; it is bounded to 100,000 modified samples, has no automatic cache eviction or migration into the production save format, and is not multi-writer safe. The sediment step is a simplified caller-calibrated finite-grid model, not a physically calibrated global sediment cycle. Climate moisture remains a rainfall proxy, and groundwater, snow, infiltration, lake geometry evolution, long-horizon global budgets, receipt-history compaction, and distributed multiplayer persistence remain unimplemented. The full planetary hydrology acceptance criteria have NOT been met.

### 2026-10-30 — Terrain persistence CI acceptance follow-up

- `HydrologyTerrainOverlayV2Test` at `8679cdb8215850260630a92581c5251c3637a390`: **full CI PASSED** https://github.com/Yusuf-cm/wild-deck/actions/runs/40968262483 . This includes an actual local-file checkpoint restart with modified terrain, deterministic second erosion/sediment step, unchanged seed terrain, canonical codec round-trip and lake-state nesting.
- Sixteen-step sediment conservation and edited-terrain D8 rerouting at `28614506d3894f2b4664b0c40edf225407b5d9a4`: **full CI PASSED** https://github.com/Yusuf-cm/wild-deck/actions/runs/40968329220 . This verifies 16 repeated 5x5 steps and stable replay at fixed synthetic forcing, not a 16-year global physical calibration.
- The progress-document CI run at `d2e0507d2e45196d41453f91a52e992b0e31ab97` was **pending** when this follow-up was prepared: https://github.com/Yusuf-cm/wild-deck/actions/runs/40968400116 .
- Remaining priority: authoritative global depression/lake spill and ocean boundary evidence, seam/pole geometry, multi-window edited-terrain conflict reconciliation, durable production save integration, physical seasonal climate and groundwater, bounded receipt histories, and distributed simulation. None are claimed complete.

### 2026-10-30 — Eroded terrain affects subsequent streamed-region routing

- Added an opt-in terrain-overlay constructor to `HydrologySeededSlopeRegionProviderV2` (`3be37f983c24bd73bf9c190bc9e021401b5ea54d`). The provider now reads a checkpoint-restored global elevation overlay for both loaded and *unloaded neighboring* samples before choosing deterministic D8 downstream edges. The original seed terrain and existing default constructor are unchanged. **Full CI pending:** https://github.com/Yusuf-cm/wild-deck/actions/runs/40968508823 .
- Added a regression test (`3def3c8c923e2f5f3c4e32685f35b1ff025ad281`) showing a persisted terrain edit changes a source's downhill destination across a region boundary, with stable repeat loading and unchanged original seeded routing. **Full CI pending:** https://github.com/Yusuf-cm/wild-deck/actions/runs/40968531591 .
- The previous documentation follow-up `d2e0507d2e45196d41453f91a52e992b0e31ab97` **full CI PASSED:** https://github.com/Yusuf-cm/wild-deck/actions/runs/40968400116 .
- This connects persisted sediment/erosion overlays to subsequent region routing only when callers restore and supply the same overlay snapshot. It does not solve unknown basin drainage, long-range flat/depression spill, multi-writer conflict resolution, seam/pole topology, global catchment reconciliation or climate calibration.

### 2026-10-30 — Final verification for seeded-region erosion persistence integration

All previously pending workflows in this run have now **fully PASSED**:
- Terrain-overlay documentation baseline `f7e38c8c423dd78592c1742896333f740a00a7b8`: https://github.com/Yusuf-cm/wild-deck/actions/runs/40968475148 .
- Overlay-aware seeded-region provider `3be37f983c24bd73bf9c190bc9e021401b5ea54d`: https://github.com/Yusuf-cm/wild-deck/actions/runs/40968508823 .
- Restored-edit unloaded-neighbor routing regression `3def3c8c923e2f5f3c4e32685f35b1ff025ad281`: https://github.com/Yusuf-cm/wild-deck/actions/runs/40968531591 .
- Documentation of overlay-aware provider `aee91d03050ec662a9a7f6357b21a48e60f9d741`: https://github.com/Yusuf-cm/wild-deck/actions/runs/40968564734 .

**Verified this run:** deterministic unique rainfall ownership across two interior regions, identical D8 routes across independent/combined loads, optional checkpointed sediment terrain overlays surviving restart and repeated epochs, conservative 16-step bounded sediment/water tests, and overlay-driven rerouting across an unloaded neighbor. **Not verified/unfinished:** globally authoritative depression and lake drainage, seam/polar ownership, lake geometry reconciliation, physically calibrated seasons, snow/groundwater, multi-window terrain-edit conflict resolution, automatic production save integration, distributed writes and bounded historical receipt storage. These remain the next water-layer priorities.

### 2026-10-30 — Canonical dateline/pole hydrology, durable seam receipts and snowmelt

**Scope:** only `feature/world-engine-v2`. Baseline full CI passed at `4460fe3588ecc44668be970808feab5048ae3c7c`: https://github.com/Yusuf-cm/wild-deck/actions/runs/40968727000 .

**Verified implementation and acceptance:**
- `HydrologySphericalTopologyV2` introduces opt-in canonical x=0 ownership of the +/-180-degree seam and a single global address at each pole, with deterministic great-circle steepest-descent neighbor routing. `HydrologyFlowGraphV2.Node` now validates canonical seam and polar edges in addition to existing local edges. Initial commit `52eab8efe06996729ffdffe10e1ff59408457660` failed CI **only** because a new assertion compared differently ordered node lists: https://github.com/Yusuf-cm/wild-deck/actions/runs/40975590297 .
- `HydrologySphericalSlopeRegionProviderV2` and corrected tests at `b9d0e0ba3b1ac865e55777ef21bd3f6636b35438` give each geographic rainfall vertex exactly one tile owner, including seam and polar samples, and route using seeded global elevations even across unloaded neighbors. A complete level-1, divisions-2 planet contains **26 unique source vertices** (8 longitude sites x 3 nonpolar latitude rings + 2 poles). Independent region loads match one combined routing pass. Synthetic seam discharge of 3+4+5 reaches a verified outlet as 12 with stable reach and resolved catchment identities. Polar ring selection and failure for noncanonical addresses are tested. **Full CI PASSED:** https://github.com/Yusuf-cm/wild-deck/actions/runs/40975667577 .
- `SeasonalSnowSoilCycleV2` at `edd822ab7a3b0e3d50c7557cd155913b91e6b3fe` adds a conservative, opt-in water-equivalent depth bucket for snowfall, degree-day melt, soil storage, potential evaporation and runoff. An initial test compilation error (Java compound `var`) failed CI: https://github.com/Yusuf-cm/wild-deck/actions/runs/40975794953 . The test was corrected in `b99098f1c6aefea177d7dbaf66e2d750ce6e1c59`; its three snow/soil tests passed in Maven, including a 9-meter-equivalent input/output budget and conversion of runoff depths to river volumes. That run then exposed a genuine cross-seam transfer-key validation gap: https://github.com/Yusuf-cm/wild-deck/actions/runs/40975865924 .
- `HydrologyTransferLedgerV2.Key` now validates canonical seam and polar one-step links. The fix and receipt codec/duplicate-delivery regression at `92af8c51e4b22b69eb947175676a4d2f61f170d3` passed **full CI:** https://github.com/Yusuf-cm/wild-deck/actions/runs/40975955452 . The previously failing durable dateline test now passes: 3 units cross an unloaded seam, wait through a checkpointed epoch, join 4 units at the receiving vertex, then join 5 units at the confirmed outlet to produce **12 units**, without receipt replay.
- `HydrologySphericalRegionSchedulerV2` at `5937193c58ce04aa0cfcf107d6737aa94242d61d` wraps longitude region neighborhoods, clamps latitude at poles, honors bounded loading budgets, and incorporates pending-water destination hints. Tests cover real seeded spherical tiles, duplicate-free loads, seam receipts and budget rejection before provider calls. **Full CI PASSED:** https://github.com/Yusuf-cm/wild-deck/actions/runs/40976005001 .
- `HydrologyWorldRunnerV2.advanceSphericalPendingRegionsAndQueue` at `06072d3371dc0cc53da816c12ccdb4f782cb1070` integrates the spherical scheduler into the checkpointed world runner. The new regression checks automatic dateline destination loading and durable exactly-once receipt consumption. **CI pending at this documentation update:** https://github.com/Yusuf-cm/wild-deck/actions/runs/40976091930 .

**Explicit remaining acceptance gaps:** Spherical routing is opt-in; the older bounded priority-flood and interior D8 providers are unchanged. A pole samples every neighboring longitude and is deliberately capped at 4,096 ring samples; higher-resolution polar routing fails closed rather than pretending to be solved. Closed depressions, flat terrain, globally authoritative ocean outlets, cross-window lake/catchment reconciliation, and dynamic lake-basin geometry are still unsolved. The new seasonal snow/soil bucket is caller-forced and not calibrated to weather, catchment cell areas, groundwater, or a durable global snowpack state. Spherical region loading and terrain overlays require callers to supply a consistent snapshot; they are not automatically integrated with production saves or multi-writer persistence. Sediment/erosion evolution remains finite-window and simplified. Receipt-history compaction, long-horizon global water/sediment budgets, mixed-LOD spherical topology and large-world performance are unfinished. **Do not claim planetary hydrology is complete.**

### 2026-10-30 — Spherical checkpoint-runner CI confirmation

- `HydrologyWorldRunnerV2.advanceSphericalPendingRegionsAndQueue` at `06072d3371dc0cc53da816c12ccdb4f782cb1070` completed **full CI PASSED**: https://github.com/Yusuf-cm/wild-deck/actions/runs/40976091930 . This verifies pending dateline water is loaded into its canonical owner region, delivered once, and recorded durably through checkpoint restart. The spherical scheduler and snow/soil tests are included in this full Maven regression run.
- Documentation commit `1c196a39252096dc184861a5627dafb256f7b907` passed Maven tests; its remaining full workflow was still running when this follow-up was recorded: https://github.com/Yusuf-cm/wild-deck/actions/runs/40976158037 .
- The remaining global depression/lake reconciliation, high-resolution polar-ring budget, physically calibrated climate/groundwater, and multi-writer persistence gaps listed above remain unresolved. No complete planetary hydrology claim.


### 2026-10-30 — Closed-sphere depression routing and durable seasonal snow/soil (follow-up)

**Branch safety:** work exclusively on `feature/world-engine-v2`; no main merges, PRs, deployments or paid infrastructure. Starting CI baseline at `83ab8501da5b4cc5cfb8813c94b8b3a5d7ecdb6c`: the GitHub Actions run [40976243627](https://github.com/Yusuf-cm/wild-deck/actions/runs/40976243627) was checked through its job/step records; the test job and every reported step were **successful**.

**Implemented (new code and tests committed, but full CI on the new commits is NOT independently confirmed yet):**
- `HydrologySphericalBasinRouterV2` (`af78553016477baa9b78f0437ab65b9b5305d576`) performs deterministic multi-source priority-flood routing over a *topologically closed*, canonical single-resolution spherical graph, seeded only by **caller-verified** outlets. It checks that every spherical neighbor is loaded, including seam/polar neighbors, and rejects unknown neighbors, missing outlets, duplicate rainfall sources, mixed resolutions and nonfinite elevations. Minimax spill level, then shortest route length, then global-address ordering breaks ties and prevents cycles. The result is a **potential spill routing graph**: actual lake outflow still requires verified stage/volume and must not be injected from an empty depression.
- `HydrologySphericalBasinRouterV2Test` (`d888ef6bad4366b3b6622b222fb0e031ba52c96a`) adds a 26-vertex complete small-planet regression with depression and flats, reversed-source determinism, verified catchment/reach identities, terminal water conservation, two-outlet partition, and fail-closed partial-window cases.
- `HydrologySnowSoilStateV2` (`12933b9bf2003ae10d966c32e51d1ebb7c466558`) adds an **opt-in** canonical-address snow/soil checkpoint payload with deterministic codec, defensive bounded storage, per-cell area conversion from water-equivalent depth to cubic-meter runoff, and an explicit per-step input/storage/evaporation/runoff volume audit. All previously stored cells must receive forcing each epoch; the original precipitation must **not** also enter the river graph.
- `HydrologySnowSoilStateV2Test` (`12a54984a4191af0810b8a5f946bedf95860d0c1`) adds wet/dry snowmelt conservation, dateline/polar runoff ownership, local-file checkpoint restart with continued seasonal storage, a conservative downstream confluence, nested terrain/lake payloads, and invalid-input/overflow rejection.

**Verification distinction:** the preceding baseline Actions workflow passed. New Java source and JUnit tests are committed, but the available GitHub connector exposes no branch-push workflow listing/check-run result for these new SHAs (commit status API returned no legacy statuses; commit-workflow endpoint filters to PR-triggered runs). Therefore **new tests/full CI remain UNVERIFIED** at this documentation checkpoint; do not report them as passing until the corresponding workflow jobs are inspected. No local Maven test runner is installed in the execution environment.

**Remaining acceptance gaps:** the priority-flood solve requires a closed, bounded *entire* canonical sphere and verified outlets; it does not resolve arbitrary unloaded windows, real ocean masks, unknown global basin boundaries, changing lake geometry, or conditional lake overflow automatically. The snow/soil model is a caller-forced degree-day bucket with user-provided cell areas and no groundwater or climate calibration; its codec is opt-in and does not automatically modify production saves. Multi-LOD spherical routing, high-resolution polar rings above 4,096 neighbors, multi-writer terrain reconciliation, receipt-history compaction, durable lake geometry, and long-horizon large-world water/sediment conservation remain unfinished. Continue hydrology before other layers.

- Additional whole-sphere region-partition acceptance test at `92a9e637ff4b788a41cdfce5514ddc2cd17df492`: the 26 canonical vertices are assigned to eight independently addressed level-1 regions; joining all region node lists must reproduce the global spill graph, preserve each rainfall source once, and conserve terminal discharge regardless of region iteration order. **JUnit/CI execution is not yet independently verified** through the available connector.


### 2026-10-30 — Spherical rainfall areas, closed-sphere lake geometry and conservative terrain transitions

**Branch and safety:** All writes exclusively to `feature/world-engine-v2`; no PR, merge, main-branch write, production deployment or paid infrastructure change. At run start, the latest push workflow for `83549ef06ac1929696f4692909f511decce95f04` was confirmed **full CI PASSED**: https://github.com/Yusuf-cm/wild-deck/actions/runs/40982438694 . The earlier closed-sphere and 8-region partition acceptance commits are also included in that green run, resolving the previous run's CI uncertainty.

**Implemented and independently checked:**
- `HydrologySphericalCellAreaV2` assigns a canonical global rainfall sample its physical equirectangular spherical surface area, including a single area-owning vertex at each pole and no duplicate longitude-seam vertex. Numerically stable polar-cap area avoids catastrophic subtraction at high resolution. `HydrologySnowSoilStateV2.Forcing.forSphericalCell` uses that area to convert precipitation and snow/soil runoff depths into actual cubic metres. New tests check 4πR² global coverage at several resolutions, positive high-resolution polar areas, invalid/noncanonical addresses and the full 26-vertex precipitation/runoff water budget. Source `1fcfbf964754af3b4b75846cf0c713fbc191c339`, tests `6f7bd70a96ff9f488545b8947da232fa4d052e4d`, factory `817a08db1f41b9a0f8e1453c1e60130b1d65b687`. **Full CI PASSED** for the corrected implementation: https://github.com/Yusuf-cm/wild-deck/actions/runs/40988431360 . The intermediate test-only commit failed compilation because the factory had not yet been committed: https://github.com/Yusuf-cm/wild-deck/actions/runs/40988422972 .
- `HydrologySphericalLakeBasinsV2` derives deterministic, globally addressed lake anchors, connected submerged cells, hypsometric storage geometry, a candidate spill edge and confirmed catchment outlet from a complete canonical spherical priority-flood solve. It rejects ambiguous multiple exits and unverified catchments instead of inventing drainage. The lake remains **dry until stored water actually reaches spill elevation**. Source `fa64161ae82079a05d09467a69f7a29fef4d708f`, acceptance tests `b8b71899b80529e148f931180432c8eae9b87135`, fail-closed invalid-radius correction `c0f5b8de860ca0d32948f5070133124b75daf461`. **Full CI PASSED** for corrected implementation: https://github.com/Yusuf-cm/wild-deck/actions/runs/40988543114 . The intermediate test commit failed because the no-lake case did not yet validate radius; corrected without weakening the assertion: https://github.com/Yusuf-cm/wild-deck/actions/runs/40988527187 .
- `HydrologyLakeGeometryTransitionV2` conservatively reconciles persisted lake storage after an explicitly supplied re-solve of changed terrain. Capacity expansion retains water, sediment-driven capacity contraction returns excess volume as a separate, once-only candidate spill flux, and wet lake disappearance/split or unverified previous storage fails closed. Source `978f985e4a8fc94660c4c83483c4a94776447275`; tests `79a1669debe609e21bb7c01d962362c4bc49711c` cover expansion, contraction, conservation, lake codec round-trip and missing identities. **Maven tests passed in CI; final browser/atlas workflow was still running at documentation time**: https://github.com/Yusuf-cm/wild-deck/actions/runs/40988589181 . Do not mark this final workflow fully green until completion.

**Scope and remaining acceptance gaps:** These features are opt-in, bounded to a closed single-resolution canonical sphere and caller-verified outlets. There is still no authoritative real-ocean mask, incremental global priority-flood solve through arbitrary unloaded regions, mixed-LOD lake reconciliation, automatic routing of displaced lake volume, or physically calibrated snow/soil/groundwater and evaporation forcing. The transition assumes unchanged lake anchor identity; changing anchors or wet lake split/merge requires explicit volume remapping rather than silently reallocating water. Surface area assumes an ideal sphere and horizontal cell areas, not terrain-slope area. Lake state, terrain overlays and seasonal stores remain caller-encoded into a single-writer checkpoint; they are not automatic production/multiplayer saves. Long-horizon planet-scale mass conservation, bounded receipt-history compaction, polar rings above 4096 samples and distributed terrain-edit conflict resolution remain unfinished. **Do not claim planetary hydrology complete.**


#### 2026-10-30 — Lake identity split/merge safety and CI follow-up

- `HydrologyLakeGeometryTransitionV2` implementation and its initial conservation/restart tests (`978f985e4a8fc94660c4c83483c4a94776447275`, `79a1669debe609e21bb7c01d962362c4bc49711c`) completed **full CI PASSED**: https://github.com/Yusuf-cm/wild-deck/actions/runs/40988589181 .
- Added membership-based fail-closed detection for wet lakes that split into multiple anchors or merge with another wet basin, even when one original anchor survives (`da787a1250c1dab8415ff14d77d9f0c5f625daf1`). Also rejects overlapping lake-cell ownership. Regression tests (`cb9857ca604f42705e6fec93d01a57438102c2a5`) exercise both cases. **New full CI pending**: https://github.com/Yusuf-cm/wild-deck/actions/runs/40988710625 . Do not claim these latest checks passed until their workflow finishes.
- Geometry transitions are explicitly opt-in and fail rather than remapping water through a wet split/merge; real basin-merger physics, lake-level equalization, multiple spillways, authoritative ocean masks, mixed LOD, and globally unloaded terrain remain unfinished.


#### 2026-10-30 — Final lake identity acceptance confirmation

The formerly pending wet-lake split/merge and overlapping-cell regression at `cb9857ca604f42705e6fec93d01a57438102c2a5` completed **full CI PASSED**, including Maven and browser/atlas checks: https://github.com/Yusuf-cm/wild-deck/actions/runs/40988710625 . The safety implementation `da787a1250c1dab8415ff14d77d9f0c5f625daf1` also passed full CI: https://github.com/Yusuf-cm/wild-deck/actions/runs/40988700806 . The preceding progress-document commit `d3bc43433d508213888bece0257179cb7a886a11` passed full CI: https://github.com/Yusuf-cm/wild-deck/actions/runs/40988666049 . This confirms only bounded, opt-in closed-sphere lake extraction, geometry transitions and fail-closed identity validation; it does **not** establish automatic planet-wide drainage, multi-spillway physics, global ocean truth, distributed saves, or climate calibration.


### 2026-10-30 — Lake-aware closed-sphere discharge routing (verification pending)

**Branch safety:** All new commits target only `feature/world-engine-v2` in `Yusuf-cm/wild-deck`. No PR, merge, production deployment, paid service, or other branch was touched. The **starting** Actions run `40988829186` was independently inspected through its job and step records and was fully successful: https://github.com/Yusuf-cm/wild-deck/actions/runs/40988829186 . This is a baseline check, **not** evidence that the commits below passed CI.

**Implemented and committed, but not yet independently CI-verified:**

- `HydrologyLakeNetworkStepV2` (`86a67fd3d1a44b7bba092145808c6591535d1281`) adds an opt-in, multi-lake, one-epoch routing step. It cuts river edges at verified lake cells, collects unique upstream discharge, applies hypsometric lake storage with bounded evaporation substeps, and releases only actual overflow. Spill follows the existing verified flow graph to a downstream lake or terminal, so an upstream lake cannot bypass a downstream lake. Per-lake storage, incoming flow, evaporation, overflow, terminal discharge and an independent whole-network volume residual are returned. Additional inflow is explicitly external to the graph's local rainfall/runoff and must never duplicate it.
- `HydrologyLakeNetworkStepV2Test` (`669f97566a02cfff44bdf5789ddb9cf0871f4652`) adds two chained-lake confluence, seasonal restart, full-sphere storage/overflow, independent terminal runoff, deterministic ordering and invalid identity/forcing tests.
- Closed-planet input validation (`4d58252c245be6f59319c13c6382594f62cd1e1a`) requires **every spherical neighbor of every supplied sample to be present**, preventing a partial loaded window from pretending its arbitrary local terminal is a verified global lake solve. The chain tests were expanded to all 26 canonical small-planet vertices, with an explicit partial-sphere rejection test (`dac6ddff56f1f35eac4fc5aa8970bbafb9ef122b`).
- Additional polar/dateline acceptance (`e4dc0683a1222395961da7191534dd3a2614d72c`) checks two lake spills converging on one river through polar and longitude-seam links, with 20 units original input partitioned as 10 stored plus 10 at the outlet, without duplicate geographic vertices.

**Verification boundary:** New Java and JUnit code is committed, but its Maven tests and new full CI **have not been independently observed**. The GitHub connector's commit-run lookup currently returns only PR-triggered workflows, while this branch uses push-triggered CI; combined legacy commit statuses are empty. The local execution environment has Java but no Maven or checked-out repository. Do not claim these new tests passed or that the new branch head is CI-green. Next run must obtain the new push-workflow jobs and fix any compilation or test failures before treating this feature as verified.

**Remaining hydrology acceptance gaps:** Authoritative real-ocean outlets; incremental/global depression and lake solves over arbitrary unloaded regions; multiple spillways and stage-dependent outlet hydraulics; automatic routing from the lake step into durable transfer/checkpoint state; wet lake split/merge redistribution; mixed-resolution and high-resolution polar topology; climate-calibrated rainfall, evapotranspiration, infiltration and groundwater; long-duration planet-scale conservation; receipt compaction; multi-writer terrain/lake reconciliation and production save integration. Lake forcing uses a start-of-substep wetted-area evaporation approximation, not calibrated lake energy balance. Only complete, single-resolution, caller-verified spherical plans are accepted. **Planetary hydrology remains unfinished.**

### 2026-10-30 — Lake geometry safety and high-substep conservation

**Branch:** `feature/world-engine-v2` only. No merges, PRs, production deployment, paid infrastructure, or other branch writes.

- `HydrologyLakeNetworkStepV2` now rejects a lake whose hypsometric reservoir cell count does not match its routed lake cells, whose anchor is not the minimum canonical sample, or whose purported lake cells are not connected by spherical neighbors. This prevents fabricated disconnected water bodies from sharing storage. Commit: `9fe5784bc56451d0aebff81d99c34aba6a41a916`.
- The lake-step substep partition now computes monotonic cumulative input targets, preventing a negative last substep from floating-point accumulation while avoiding intermediate multiplication overflow. Same commit.
- Added tests for a two-cell lake with rainfall from both cells and a checkpointed second season, rejecting disconnected geometry, mismatched cell counts, unstable anchors, and 10,000 fractional-volume substeps. Commit: `40a92bfd7491c37ee4d430a21377c085ac886dab`.

**Verification evidence and limits:** A standalone Java 17 smoke test compiled and passed locally, checking 49 combinations of input volumes/substep counts and connected versus disconnected sample groups. This validates the specific partition/connectivity algorithms, **not** compilation or JUnit execution of the repository classes. The last independently inspected complete GitHub Actions workflow remains the earlier baseline `40988829186` (PASSED): https://github.com/Yusuf-cm/wild-deck/actions/runs/40988829186 . The GitHub connector returns no push-triggered runs for the new commit and no combined legacy statuses; Maven is not installed locally and the repository cannot be cloned from this runtime. **Full CI, compilation and new JUnit results for `5e8c8c8f`, `9fe5784b`, and `40a92bfd` remain UNVERIFIED.** Do not promote them to accepted/green until Actions job records are inspected and any failures corrected.

**Next water priorities:** (1) obtain actual push-triggered CI and fix failures; (2) connect verified lake spill outflow to durable cross-region receipt queues without replaying original rainfall; (3) authoritative ocean outlets and incremental unloaded-region drainage; (4) stage-dependent multi-spillway hydraulics and wet lake split/merge redistribution; (5) climate-calibrated seasonal forcing, groundwater, snow/soil storage and long-duration sediment/water conservation. Full planetary hydrology remains incomplete.


### 2026-10-30 — Verified lake-aware receipt delivery and unloaded-region spill (follow-up)

**Repository safety:** All source, test and documentation changes were committed only to `feature/world-engine-v2` in `Yusuf-cm/wild-deck`. No main-branch writes, PRs, merges, production changes or paid infrastructure operations.

**Starting CI now independently verified:** The formerly unverified lake network implementation and geometry/numerical safeguards through `60a9b384b7ac3661321a0e1aaf8ca95970d3d428` passed the complete Java CI workflow, including Maven tests, world experiments, resource replay and browser/atlas checks: https://github.com/Yusuf-cm/wild-deck/actions/runs/40999313648 . Earlier documentation's "CI unverified" label described the state at that time, not the current verification result.

**New capability A — atomically persisted lake-network epochs with real incoming receipts:** Commit `ac250f1393ab1562088fbc8645fe8e9b1ece9ed0` adds `HydrologyWorldRunnerV2.advanceClosedSphericalLakeNetwork`. It stages and claims only receipts addressed to loaded canonical vertices, injects them **before** river/lake interception, runs the complete verified spherical lake network, and atomically saves updated lake volumes, the opaque world state and consumed receipt identities in one checksummed checkpoint. Invalid partial-sphere plans or failed writes do not mutate runner state. Regression tests verify two separate incoming edges delivering 4+3 units to a lake alongside 2 units of locally owned rainfall and 3 units at the outlet: **12 original units = 5 stored + 7 discharged**. Checkpoint recovery, a second season with 2 additional units spilling once, invalid partial-sphere rollback and polar/dateline receipt ownership are covered. **Full Java CI PASSED**, all job steps successful: https://github.com/Yusuf-cm/wild-deck/actions/runs/41004640263 .

**New capability B — verified lake overflow across unloaded region boundaries:** Commit `4d948aeceb8213ec6186ac617212cf90a74b28ee` adds `HydrologyLakeEpochV2.FrontierPeriod` and `advanceWithFrontier`. Each caller-verified spill edge must start at a loaded river vertex with the declared downstream neighbor, and distinct lakes cannot claim the same spill source. The existing seasonal reservoir and atomic frontier dispatch then persist lake storage and an idempotent next-epoch transfer **together**, even when the receiving tile is unloaded. Integration tests verify 30 units of inflow -> 20 stored + 10 parked downstream, restart and exact-once consumption at a receiver with 2 independent units (12 outlet), a two-lake 20-unit confluence using two distinct receipt keys, and fail-closed mismatched/shared/unknown spill edges without checkpoint mutation. **Full Java CI PASSED**, all job steps successful: https://github.com/Yusuf-cm/wild-deck/actions/runs/41004803463 .

**Precise acceptance boundary / unfinished work:** These are **opt-in** single-writer APIs. The closed lake-network epoch still requires a **complete, single-resolution, caller-verified spherical plan**; it does not incrementally solve unknown unloaded terrain or discover real ocean outlets. The unloaded-region spill adapter requires the caller to supply a physically verified lake-to-river spill edge and seasonal basin forcing; it does **not** infer that edge from arbitrary terrain or handle multiple spillways/stage-dependent hydraulics. Neither API detects duplicate rainfall if a caller supplies the same water as both graph localWater and external forcing. Wet basin split/merge water redistribution, multi-resolution global lake identity, physically calibrated precipitation/evaporation/groundwater, snow/soil and sediment coupling into this durable epoch, receipt-history compaction, long-duration planet-scale conservation, multi-writer checkpoints, and production save integration remain unfinished. **Do not claim planetary hydrology complete; prioritize water in subsequent runs.**


#### 2026-10-30 — Twelve-season unloaded-lake endurance acceptance

- `HydrologyLakeFrontierEpochV2Test` at `39da710bc14c46c88a27fa1d1928b663dc2cec4d` exercises **12 successive wet lake epochs**, with checkpoint reopen every three epochs and the receiving region deliberately unloaded throughout. Each epoch generates a distinct, durably persisted spill receipt. The test verifies **360 units original precipitation = 20 units final lake storage + 340 units parked water**, followed by a receiving epoch with 2 new local units: **342 units at the terminal**, all 12 receipts consumed once and no remaining parked volume. A subsequent epoch emits only its 2 units of new rainfall, demonstrating no replay. **Full CI PASSED** (Maven and all remaining job steps): https://github.com/Yusuf-cm/wild-deck/actions/runs/41004984630 .
- The preceding progress-document update at `35cb1b847492323b6a05f37830754443dd637c15` also passed full CI: https://github.com/Yusuf-cm/wild-deck/actions/runs/41004955298 .
- **Limitations unchanged:** This endurance test uses a deliberately small, caller-verified two-vertex spill route and a simplified reservoir; it is not proof of planet-scale drainage, multi-year climate realism, mixed-LOD correctness, global ocean truth, wet lake split/merge redistribution, bounded receipt-history storage, or multiplayer-safe persistence. All outstanding water acceptance gaps in the preceding section remain open.


### 2026-10-30 — Verified-plan lake spill dispatch and frontier safety

**Scope and branch:** Only `Yusuf-cm/wild-deck`, `feature/world-engine-v2`. No main merge, pull request, production deployment or paid infrastructure changes.

**Starting CI:** The branch HEAD `4efb36419c2f58e5957916414a9e6d3515c8d248` passed the complete Java CI workflow, including Maven tests, world experiment, resource replay and browser/atlas checks: https://github.com/Yusuf-cm/wild-deck/actions/runs/41005108411 .

**Changes:**
- `HydrologyLakeEpochV2.advanceWithFrontier` now rejects nonzero rainfall on lake spill-source vertices and unconsumed upstream receipts addressed to those vertices. Without this, the adapter could add local rainfall twice or route incoming water past lake storage. Source `1c81f9bccf455e9a3a259f0c86d7848e6fec8d39`; regression tests `b0c983b704a6ebf362e86275269975dc672c6117`. Both **full CI PASSED**: https://github.com/Yusuf-cm/wild-deck/actions/runs/41010367306 and https://github.com/Yusuf-cm/wild-deck/actions/runs/41010378347 .
- `HydrologyVerifiedLakeFrontierV2` (`8e5a973b0db9757a54551df591d1454d4dee0afe`) derives each lake's single verified spill-source edge from a complete, closed, caller-verified spherical lake plan, then persists overflow through the existing atomic unloaded-region receipt queue. It validates canonical unique zero-runoff vertices, global graph closure and outlet resolution, nonoverlapping connected basin cells, stable anchors, a unique spill source, and matching seasonal forcing. Pending receipts into lake cells and lake-to-lake spill bypass are rejected rather than silently lost. **Full CI PASSED for implementation-only commit**: https://github.com/Yusuf-cm/wild-deck/actions/runs/41010409544 .
- `HydrologyVerifiedLakeFrontierV2Test` (`5ee1a15f98053dfd480a10eff8fe8bcbb214c873`) exercises storage, overflow, delayed delivery, restart and replay, deterministic source ordering, and fail-closed rainfall, partial planet and receipt cases. The first CI run **FAILED** on a test expectation: a frontier receipt produced during epoch 1 is addressed to epoch **2**, not epoch 1. The implementation compiled and the other tests passed. Failure: https://github.com/Yusuf-cm/wild-deck/actions/runs/41010447456 . Corrected assertion in `45be0922e30e8426b43b079605de6c4501dc96fd`; strengthened nonzero-spill determinism assertion in `f007bc85aaf5686ec4cddf3ecb6f005aa8aaa5d4`. These latter commits require final CI confirmation before declaring the new acceptance test green.
- `HydrologyLakeEpochV2` now rejects one lake's overflow entering another lake's loaded spill-source vertex, because the simple frontier graph would bypass the downstream reservoir. Source `3b41cda51a3dd9eff09ea025914c0feeff25693c`, regression `184b58e32099d70019542a9471da75a22b76fbb9`. Intermediate CI runs still contained the earlier incorrect receipt-epoch test assertion and **FAILED**: https://github.com/Yusuf-cm/wild-deck/actions/runs/41010475746 and https://github.com/Yusuf-cm/wild-deck/actions/runs/41010484860 . Do not claim those SHAs passed.

**Acceptance boundary and remaining work:** This adapter only runs a lake-forcing epoch on a **complete, single-resolution, caller-verified spherical plan**, and deliberately requires zero graph local runoff. It does not discover real oceans, solve unknown unloaded terrain, accept river receipts into lake storage, route connected lake chains, or derive physically calibrated seasonal forcing. The full closed lake-network runner handles river receipts but is not an incremental frontier solver. Outstanding: globally authoritative incremental depression drainage, real ocean masks, coupled multi-lake frontier storage and stage-dependent multiple spillways, wet split/merge redistribution, snow/soil/groundwater and sediment coupling, climate calibration, long-duration large-planet conservation, receipt-history compaction, mixed LOD and multi-writer persistence. Do not claim planetary hydrology complete.


#### 2026-10-30 — Final corrected frontier regression CI

The corrected receipt-epoch assertion and all lake-frontier safety changes through `45be0922e30e8426b43b079605de6c4501dc96fd` completed **full Java CI PASSED**, including Maven tests and browser/atlas steps: https://github.com/Yusuf-cm/wild-deck/actions/runs/41010513599 . The strengthened test at `f007bc85aaf5686ec4cddf3ecb6f005aa8aaa5d4` verifies a **nonzero** deterministic lake spill receipt (rather than trivially comparing empty ledgers) and also completed **full Java CI PASSED**: https://github.com/Yusuf-cm/wild-deck/actions/runs/41010539644 . The earlier failures were the incorrect next-epoch test expectation, not a remaining water-conservation failure. The separate documentation-only workflow may still be in progress; verify the final branch HEAD independently.

This establishes only the bounded verified-plan lake-to-unloaded-frontier adapter and its fail-closed behavior. Coupled lake chains, incoming river receipts into lakes, automatic global ocean discovery, arbitrary unloaded-region depression solving, climate calibration and distributed persistence are still NOT accepted as complete.


### 2026-10-30 — Coupled snow/soil, durable river receipts and closed-lake storage (CI pending)

**Branch scope:** Only `feature/world-engine-v2` in `Yusuf-cm/wild-deck`. No PR, main merge, other-branch write, production deployment or paid infrastructure changes.

**Starting CI:** Branch head `9106ad7d3617f1a7fc1f43acdea0ea3e8b1f7998` passed the full Java CI workflow: https://github.com/Yusuf-cm/wild-deck/actions/runs/41010686611 .

**New implementation, pending CI acceptance:**

- `HydrologySeasonalLakeNetworkV2` (`679a89096dccc64714de6dacf4f8ea4e06144f0d`) explicitly nests the durable snow/soil codec inside the durable lake codec. It advances snow and soil depth stores, converts precipitation-derived runoff to canonical per-cell volumes, injects those volumes **once** as the only local river sources, and calls the existing lake-aware runner so incoming cross-region receipts are intercepted before lake storage. The runner atomically persists both water stores, receipt consumption and lake volumes. The adapter rejects nonzero raw graph runoff and independent lake additional-inflow, preventing duplicate precipitation ownership; external water must use durable receipt keys. A combined seasonal water budget exposes precipitation, initial/final stores, receipt inflow, evaporation and terminal discharge. The solver still requires a complete, single-resolution, verified spherical plan and caller-provided weather.
- `HydrologySeasonalLakeNetworkV2Test` (`b1912482faba6789173c6b628cf0954981a5bd88`) asserts winter snow storage, spring snowmelt and a single upstream receipt entering a lake, restart with no receipt replay, a coupled two-lake river chain, and fail-closed raw-water duplication, unverified partial planets and nonzero independent lake inflow. Expected acceptance cases include **10 units into a lake = 5 stored + 5 overflow** (7 seasonal runoff + 3 received) and **21 units through two lakes = 10 stored + 11 discharged**. These are assertions, **not verified passing results** until the new CI completes.
- `HydrologyLakeNetworkStepV2` (`c58e8af7b36835f4a98ac9f818419e8b64374c76`) now rejects two routed exits from one lake even when they share the same spill target. The one-outlet model cannot justify distributing discharge between two independent edges. Regression test: `74bcafbe1131b726ad5c3d7f6ab88de1e3e22069`. This deliberately fails closed rather than claiming multi-spillway hydraulics.

**Verification status at documentation write:** GitHub push CI for the new commits has been triggered but not yet confirmed complete. Java/Maven test results for these new changes remain **UNVERIFIED** until job records are inspected and failures repaired. The previous full workflow `41010686611` is green, but it does not validate the new code. CI URLs for early new commits: https://github.com/Yusuf-cm/wild-deck/actions/runs/41016338084 and https://github.com/Yusuf-cm/wild-deck/actions/runs/41016370979 .

**Still unfinished:** Automatic globally authoritative depression/ocean drainage across unknown unloaded regions; mixed-resolution and high-resolution polar reconciliation; globally reconciled wet lake splits/merges; real multi-spillway stage-dependent hydraulics; groundwater, infiltration and calibrated seasonal climate; terrain/sediment coupling into this durable seasonal epoch; large-world endurance and performance; receipt-history compaction; multi-writer persistence and production saves. The nested snow/soil adapter is opt-in and single-writer. No claim of complete planetary hydrology is warranted.


#### 2026-10-30 — Seasonal lake integration acceptance: full CI GREEN

The preceding "CI pending" label was accurate when written, but is now superseded by independently inspected push-triggered workflow records:

- Seasonal adapter implementation `679a89096dccc64714de6dacf4f8ea4e06144f0d`: **PASSED** https://github.com/Yusuf-cm/wild-deck/actions/runs/41016338084 .
- Winter/spring snowmelt, incoming river receipt, chained lakes, restart and failure rollback regression `b1912482faba6789173c6b628cf0954981a5bd88`: **PASSED** https://github.com/Yusuf-cm/wild-deck/actions/runs/41016370979 .
- Exactly-one-lake-spill-exit guard `c58e8af7b36835f4a98ac9f818419e8b64374c76` and its double-spillway regression `74bcafbe1131b726ad5c3d7f6ab88de1e3e22069`: **PASSED** https://github.com/Yusuf-cm/wild-deck/actions/runs/41016397720 and https://github.com/Yusuf-cm/wild-deck/actions/runs/41016417383 .
- Progress-document checkpoint `67c8db153300432d5a4e0af3c3ddf54fa828ea25`: **PASSED** https://github.com/Yusuf-cm/wild-deck/actions/runs/41016443897 .
- Canonical dateline/pole seasonal routing and deterministic reordered-plan regression `a9d5af690517888802e9ef46a057942e2cfc0e48`: **PASSED** https://github.com/Yusuf-cm/wild-deck/actions/runs/41016526385 . The job's individual steps were inspected: Maven tests, world experiment, finite resource replay, browser save controls, atlas export and Chromium atlas verification all **PASSED**.

**What the acceptance tests demonstrate:** Single-writer atomic persistence of snow/soil stores and lake volumes with exact-once receipt consumption, snowmelt entering lake-aware river routing, deterministic polar/dateline ownership on a 26-vertex canonical small sphere, a two-lake chain conserving 21 input units as 10 stored plus 11 discharged, and rejection of duplicated local precipitation or ambiguous multi-exit lake geometry. This is a controlled closed-plan acceptance test, **not** proof of physically realistic global hydrology.

**Outstanding water blockers remain:** Real ocean outlet truth; incremental authoritative drainage through arbitrary unloaded regions; mixed-resolution and high-resolution polar reconciliation; wet lake split/merge redistribution; hydraulic multiple spillways; physically calibrated climate/groundwater/infiltration; erosion and terrain evolution integrated into the durable seasonal epoch; large-planet long-horizon conservation and performance; receipt compaction and multi-writer checkpointing. No production-save integration. Continue hydrology work before other world layers.


### 2026-10-30 — Wet lake split/merge remapping and durable geometry-epoch overflow

**Branch-only scope:** All work here is confined to `feature/world-engine-v2` in `Yusuf-cm/wild-deck`. No main merge, PR, other-branch write, production deployment, or paid infrastructure change.

**Baseline:** Full Java CI passed on `ddba17941a80188d3421924726184dc0f01a7778`: https://github.com/Yusuf-cm/wild-deck/actions/runs/41016640833 .

**Implemented and verified:**
- `HydrologyWetLakeReconciliationV2` (`76c5717ac93e7577871be7cfd41bdbe4da844adb`) provides an **opt-in** conservative remap for wet lake splits and merges after terrain changes. It reconstructs water columns using the OLD reservoir's hypsometric stage and bed/area geometry, assigns each wet cell's volume to the NEW basin owning that cell, equilibrates the receiving basin by volume, and reports excess above new capacity as explicit overflow. It rejects missing wet cells, overlapping lake cells, invalid geometry and unknown stored identities. This is instantaneous finite-volume remapping, **not** a dynamic breach or surface-wave solver. Full CI **PASSED**: https://github.com/Yusuf-cm/wild-deck/actions/runs/41022398530 .
- `HydrologyWetLakeReconciliationV2Test` (`77b6ca37f024b8d977d5fdb97db9c1f3c0b4b6fe`) covers asymmetric wet split (10 = 6 + 4), wet merge (6 + 7 = 13), sediment-reduced capacity (16 = 5 stored + 11 released), disappearance rejection, overlapping geometry rejection, deterministic ordering and lake-state serialization. Full CI **PASSED**: https://github.com/Yusuf-cm/wild-deck/actions/runs/41022423501 .
- `HydrologyLakeGeometryEpochV2` (`46626805f98dfea79735c8d479e350ca6e602e0c`) connects that remap to the existing single-writer durable frontier mechanism. It requires both old/new closed, canonical, zero-runoff spherical plans with the same vertex set and verified single lake spill edges. It rejects pending receipts into changing lake cells, avoids treating other river receipts as geometry overflow, and commits new lake volumes together with outgoing next-epoch spill receipts atomically. A shared spill destination receives one deterministic aggregate receipt. Full CI **PASSED**: https://github.com/Yusuf-cm/wild-deck/actions/runs/41022522448 .
- `HydrologyLakeGeometryEpochV2Test` (`9d7b7f337d0dca3c06bf741e2dc37851bc696923`) verifies a wet split on a 26-sample canonical sphere: **18 initial = 10 retained + 8 queued**, two distinct next-epoch receipts, checkpoint recovery, delayed receipt delivery, no replay, dry unchanged-geometry advancement without fictitious receipts, and failure rollback for pending lake receipts/incomplete planets. Full CI **PASSED**: https://github.com/Yusuf-cm/wild-deck/actions/runs/41022561509 .
- Additional merge-and-sediment regression (`923a9e42fce5518d8542bfad7165df27c73c6294`) asserts **10 initial = 2 retained + 8 queued** through a single verified outlet, followed by durable delivery after restart. **CI PENDING** at this documentation write; do not claim it passed until inspected: https://github.com/Yusuf-cm/wild-deck/actions/runs/41022685484 .

**Exact acceptance boundary:** The new wet split/merge path works only when every previously wet cell still belongs to a new verified lake. If wet cells become ordinary river terrain, it rejects the transition rather than losing water. It does not solve arbitrary unloaded terrain or identify real ocean outlets; the closed spherical plans and spillways remain caller-verified. Overflow delivery is a separate geometry-only epoch, not automatically coupled to ongoing seasonal snow/soil or sediment physics. Multi-spillway stage hydraulics, incremental planetary drainage, mixed LOD, climate-calibrated groundwater/infiltration, large-world endurance, receipt-history compaction, and distributed multiplayer persistence remain unfinished. No claim that all planetary water features are complete.


#### 2026-10-30 — Wet merge overflow regression accepted

The merge/sediment-displacement integration regression `923a9e42fce5518d8542bfad7165df27c73c6294`, previously marked CI pending, completed **full Java CI PASSED**: https://github.com/Yusuf-cm/wild-deck/actions/runs/41022685484 . Its Maven tests and world experiment, resource replay, browser controls, and atlas verification all passed. The verified case remaps two wet lakes into one sediment-constricted basin: **10 units = 2 stored + 8 queued**, then receives the 8-unit receipt once after checkpoint recovery. The earlier wet-split acceptance at `9d7b7f337d0dca3c06bf741e2dc37851bc696923` also passed full CI: https://github.com/Yusuf-cm/wild-deck/actions/runs/41022561509 .

**Remaining before planetary hydrology acceptance:** Authoritative outlet discovery from ocean truth, incremental depression routing across arbitrary unloaded regions, mixed-LOD and high-resolution polar drainage, runoff from newly exposed wet lake cells, stage-dependent multiple spillways and transient split/merge hydraulics, seasonal erosion and sediment coupling into this checkpointed geometry workflow, climate-calibrated infiltration/groundwater, large-world endurance, bounded receipt-history compaction, and distributed multiplayer-safe persistence. The new geometry epoch is single-writer, opt-in, and deliberately excludes simultaneous rainfall/river inflow. These limitations are not test passes.


### 2026-10-30 — Verified-seed ocean connectivity and precomputed spherical route atlas

**Scope and baseline:** Only `Yusuf-cm/wild-deck` branch `feature/world-engine-v2`. Baseline head `6f9f0cc9a73683546c28fcba77d3e839a876dfce` completed full Java CI **PASSED**: https://github.com/Yusuf-cm/wild-deck/actions/runs/41022833794 . No production/paid infrastructure, main branch, other branches, merges or PRs were touched.

**Implemented:**
- `HydrologySphericalOceanOutletsV2` (`7f2b5172a7069e6ed9888ca13334d23e3b754f26`) classifies submerged vertices by connected components on a complete, canonical, single-resolution spherical grid. Only components containing caller-verified submerged ocean seed(s) become ocean outlets. Disconnected below-sea-level inland depressions remain inland water, rather than being mistaken for sea. Canonical seam/pole adjacency and deterministic component anchors are supported. The ocean mask can feed the existing closed-sphere priority-flood and potential lake extraction. The implementation CI **PASSED**: https://github.com/Yusuf-cm/wild-deck/actions/runs/41029484210 .
- `HydrologySphericalOceanOutletsV2Test` (`02082928618718388f39c3b0717d27e5a54c8f39`) checks dateline-connected ocean, isolated submerged inland pit, two ocean components, deterministic source ordering, polar seed/ring connectivity, conservation of 26 globally unique unit-water sources, conditional inland lake storage, and rejection of missing/unverified seeds, incomplete planet and duplicate vertices. Full Java CI **PASSED**: https://github.com/Yusuf-cm/wild-deck/actions/runs/41029511867 .
- `HydrologySphericalRouteAtlasV2` (`2b54f5931c38ef91a3239025388f3c0ceefe4845`) caches a bounded, authoritative closed-sphere drainage solve, resolved global outlet catchment IDs and stable reach IDs. It exposes deterministic uniquely owned region slices and validates that independently loaded regions form a complete, non-overlapping, unchanged partition of the same snapshot. It does NOT imply each partial slice contains all upstream discharge; durable frontier receipts remain necessary. Full Java CI **PASSED**: https://github.com/Yusuf-cm/wild-deck/actions/runs/41029559702 .
- `HydrologySphericalRouteAtlasV2Test` (`65537b9b25c36afb013dc0749ebe2fa135a50ad8`) covers 26 unique canonical samples partitioned across eight independently loadable regions, 25 stable reaches for one outlet, global catchment identity and deterministic reordering, ocean-connected outlet identities, and duplicate/missing/modified partition rejection. Full Java CI **PASSED**: https://github.com/Yusuf-cm/wild-deck/actions/runs/41029586044 .
- Seasonal atlas re-forcing (`4d7963e4d583938e56425b4c937f0eb02ccb3588`) accepts exactly one nonnegative water volume per globally owned vertex, preserves precomputed downstream edges and catchment topology, and audits conservation for the new seasonal flow. It rejects missing, duplicate and foreign water sources. Seasonal regression (`e5776da87bb82be7402b394a4b1251bdb141e60a`) checks 9 units = 9 outlet discharge and stable catchments, as well as invalid-source rejection. **CI PENDING** at this documentation write: https://github.com/Yusuf-cm/wild-deck/actions/runs/41029651707 and https://github.com/Yusuf-cm/wild-deck/actions/runs/41029667648 . Do not treat this last enhancement as accepted until those jobs finish successfully.

**Exact acceptance boundary / unfinished:**
1. Ocean *seeds* must be independently verified by the caller. This is connected-component coastline classification, **not automatic real-world ocean truth**, sea-level calibration, tides or shoreline dynamics. Isolated inland depressions stay inland; a wrong seed is still a wrong physical assumption.
2. The atlas requires a complete canonical spherical graph at one resolution and fits within the existing 100,000-sample priority-flood bound. It makes unloaded tile routes/identities queryable from a previously built snapshot, but **does not solve arbitrary unknown terrain incrementally**. Every terrain edit or shoreline/outlet topology change requires rebuilding it. Mixed-LOD and polar-ring limits remain.
3. Snapshot region slices contain local water ownership only. Their local graph solve does not include unreceived upstream transfers; cross-region discharge must still use the existing durable exactly-once ledger. Seasonal re-forcing is opt-in, not wired to the persistent lake/erosion runner.
4. Wet lake cells exposed as ordinary terrain, stage-dependent multiple spillways, real ocean/groundwater physics, infiltration and calibrated weather, coupled seasonal erosion/sediment, large-world endurance, bounded receipt compaction, multiplayer-safe persistence and production-save integration remain unfinished. No claim that the planetary water subsystem is complete.


#### 2026-10-30 — Ocean/atlas seasonal re-forcing CI acceptance

The preceding seasonal atlas re-forcing CI-pending note is now superseded: implementation `4d7963e4d583938e56425b4c937f0eb02ccb3588` **PASSED** full Java CI https://github.com/Yusuf-cm/wild-deck/actions/runs/41029651707 and the unique seasonal ownership/conservation regression `e5776da87bb82be7402b394a4b1251bdb141e60a` **PASSED** full Java CI https://github.com/Yusuf-cm/wild-deck/actions/runs/41029667648 . The preceding progress-document commit `da1c003f7f8f20ca44d99e22c8d0c075dcc24f5b` also **PASSED** full Java CI https://github.com/Yusuf-cm/wild-deck/actions/runs/41029726547 . The last workflow's individual Maven test, world experiment, resource replay, browser save controls, atlas export and real-browser atlas verification steps were inspected and all passed.

**Precisely verified:** Connected-ocean classification on a 26-vertex canonical test sphere with independently supplied ocean seeds, explicit inland-water exclusion, deterministic dateline/polar connectivity, lake storage conditional on fill stage; bounded complete-sphere route atlas with eight disjoint region ownership slices, stable global catchment/reach IDs across source reordering, and fail-closed incomplete/conflicting partition checks; seasonal re-forcing conserving nine units of new source water through unchanged globally verified routes while rejecting missing/duplicate sources.

**Not verified or implemented:** Unbounded/streaming priority-flood through unknown terrain; independently discovering ocean truth without trusted seeds; ocean dynamics or evolving shoreline; incremental recomputation after erosion; global mixed-resolution and high-resolution polar drainage; stage-dependent multiple lake spillways; seasonal runoff wired automatically to this atlas and durable lake/erosion runner; groundwater, infiltration, calibrated climate, large-planet endurance, receipt-history compaction and distributed persistence. No claim of completed planetary hydrology.


### 2026-10-30 — Closed-sphere terrain-change drainage audit (acceptance pending)

**Scope:** Exclusively `Yusuf-cm/wild-deck` branch `feature/world-engine-v2`; no main/other branch/PR, production or paid infrastructure changes. The baseline full Java CI for `8bcfdd932987d79348be7a6d2acf6ae24967d2e2` **PASSED**: https://github.com/Yusuf-cm/wild-deck/actions/runs/41030552298 .

**Implemented and committed (new CI must pass before acceptance):**
- `HydrologySphericalAtlasRebuildAuditV2` (existing implementation `f37c6d2`, now expanded at `a449964ce2c8ee14d5a1a350a2cf2917cf78860c`) compares the old atlas with an independently recomputed *complete* authoritative closed-sphere priority-flood atlas. It reports changed downstream/terminal edges, changed catchment assignments, the symmetric difference of river reach identities, and a conservative affected-sample set propagated upstream over BOTH old and new routes. Canonical source-set changes fail closed; a changed rainfall volume alone does not count as a route change. No incremental solve is claimed.
- `HydrologySphericalAtlasRebuildAuditV2Test` (`795c10b642029c6798de1594040f90c97a7a5240`) checks unchanged topology, a newly verified second outlet, direct-full-rebuild equivalence, deterministic source reordering, river reach and catchment invalidation, water conservation and incomplete-planet rejection. `ddfff7786cd364afe83bd83c633ab6d8b616d80b` adds a sediment-ridge terrain edit regression for changed routes with stable outlet identity and conservation of 26 unit sources.
- **CI state at this documentation write:** `a449964ce2c8ee14d5a1a350a2cf2917cf78860c` https://github.com/Yusuf-cm/wild-deck/actions/runs/41035693750 and `795c10b642029c6798de1594040f90c97a7a5240` https://github.com/Yusuf-cm/wild-deck/actions/runs/41035727769 were **IN PROGRESS** at inspection. The later sediment regression commit must also complete CI before acceptance. No claim that these tests passed yet.

**Explicit remaining acceptance gaps:** This is full-snapshot rebuild auditing, **not incremental drainage through unknown or arbitrary unloaded terrain**. The authoritative terrain and verified outlets must be available globally at one resolution within the bounded priority-flood budget. The affected set is conservative, not a proof of minimal recomputation. Lake geometry/wet-cell transitions, seasonal snow/soil and erosion/sediment checkpoints, queued cross-region receipt reconciliation, stage-dependent spillways, mixed-resolution and polar scalability, ocean seed truth, climate/groundwater, multiplayer-safe persistence, receipt compaction and endurance testing are NOT completed by this change. Do not activate a rebuilt atlas against unreconciled live lake/transfer state.


#### 2026-10-30 — Terrain-change drainage audit CI accepted after fixture repair

The preceding pending-CI note is superseded for the following commits:
- `a449964ce2c8ee14d5a1a350a2cf2917cf78860c`: full Java CI **PASSED** https://github.com/Yusuf-cm/wild-deck/actions/runs/41035693750 .
- `795c10b642029c6798de1594040f90c97a7a5240`: full Java CI **PASSED** https://github.com/Yusuf-cm/wild-deck/actions/runs/41035727769 . Verified unchanged atlas, new outlet/catchment invalidation, direct complete-rebuild equivalence, deterministic shuffled source ordering, conserved water and incomplete graph rejection.
- The initial sediment-ridge fixture `ddfff7786cd364afe83bd83c633ab6d8b616d80b` **FAILED** (asserted a reroute from an isolated ridge that did not actually alter the shortest spill path): https://github.com/Yusuf-cm/wild-deck/actions/runs/41035766676 . The interim documentation commit `fdf1c95a6aca21c4b3a2ff6553e8c76a8edbbc11` also **FAILED** because it contained the same test fixture: https://github.com/Yusuf-cm/wild-deck/actions/runs/41035809259 . These are historical failed runs, not outstanding failures on the current branch.
- Fixed the fixture using a three-cell blocking sediment ridge at `816d333518108d4a7236ff6a9bed8644ebcf2bb6`: full Java CI **PASSED** https://github.com/Yusuf-cm/wild-deck/actions/runs/41035888471 . The tested change reroutes a river without inventing a catchment, preserves all 26 unit sources and matches a fresh global solve.
- Added old/new upstream dependency closure regression `1b69bf80ea511ec60efd91c7d86a538996b745c1`: full Java CI **PASSED** https://github.com/Yusuf-cm/wild-deck/actions/runs/41036027727 . It asserts every node upstream of an invalidated downstream sample in either snapshot is also invalidated, including possible cross-region and seam dependencies.

**Accepted scope:** A bounded closed-sphere, whole-snapshot rebuild *audit* with deterministic changed route/catchment/reach detection, conservative transitive upstream invalidation, and conservation regression coverage. It does **not** implement an incremental priority flood or live drainage migration. In particular, changing river routing while lake volumes, cross-region receipts or erosion checkpoints are live still requires an explicit reconciliation protocol. Global unknown/unloaded terrain, multi-LOD, scalable polar routing, automatic ocean truth, stage-dependent lake spillways, groundwater/climate calibration, large-world endurance and distributed persistence remain unfinished. Continue water development before other world layers.


#### 2026-10-30 — Rebuilt drainage reach-catchment audit and region cache reuse gate

**Scope:** Only `Yusuf-cm/wild-deck`, branch `feature/world-engine-v2`. Baseline commit `38ea578ded60ce6cf16f8667003ee94625a24c67` full Java CI **PASSED**: https://github.com/Yusuf-cm/wild-deck/actions/runs/41036192636 .

**Changes committed (new CI verification pending at this documentation write):**
- `55fce8ff6b6a53ee06a74c5530bca615e4cceb33`: changed reach detection now compares the entire old/new `ReachId -> CatchmentId` mapping, not merely the presence of a reach ID. A geometrically unchanged edge whose downstream outlet changed must be invalidated. Added `verifyReusableSlice` to fail closed on affected samples, duplicate samples, foreign/modified nodes and mismatched old/new catchments.
- `371dee9a6f90276ecb0054f2343ca19b3d6e0639`: regression assertions for unchanged-region slice reuse, duplicate/empty rejection, changed catchment reach invalidation and affected slice rejection.
- `dc86bc478c1df577f06473e843371f26f56e9a90`: `verifyReusableRegion` requires a complete, uniquely owned region partition before permitting cache reuse. Partial slices cannot impersonate a complete unloaded region.
- `a08c03bbee81d0bd6a5e0e1662d1f39de55e3b54`: full-region reuse and missing owned vertex regression.

**Verification boundary:** Previous baseline CI passed; the new commits must not be described as accepted until their own GitHub Actions workflows pass. The cache gate validates *read-only* reuse of unchanged route snapshots; it does not migrate in-flight water, queued frontier receipts, wet lake storage, sediment, or erosion epochs. The underlying drainage solve still requires the complete canonical single-resolution sphere and caller-verified outlets. Incremental priority-flood across arbitrary unloaded terrain, stage-dependent spillways, mixed LOD, groundwater, climate calibration, distributed persistence, endurance and receipt compaction remain unfinished.


#### 2026-10-30 — Region reuse and changed-reach CI accepted

The preceding pending-CI note is superseded. All five implementation/test/documentation commits passed full Java CI:
- `55fce8ff6b6a53ee06a74c5530bca615e4cceb33`: **PASSED** https://github.com/Yusuf-cm/wild-deck/actions/runs/41044026016
- `371dee9a6f90276ecb0054f2343ca19b3d6e0639`: **PASSED** https://github.com/Yusuf-cm/wild-deck/actions/runs/41044042272
- `dc86bc478c1df577f06473e843371f26f56e9a90`: **PASSED** https://github.com/Yusuf-cm/wild-deck/actions/runs/41044068145
- `a08c03bbee81d0bd6a5e0e1662d1f39de55e3b54`: **PASSED** https://github.com/Yusuf-cm/wild-deck/actions/runs/41044077853
- `246a3f2cbce13f511b1a3d13cdf53cdcf04a8beb`: **PASSED** https://github.com/Yusuf-cm/wild-deck/actions/runs/41044115907

**Verified acceptance:** Rebuild audits now invalidate reach-to-catchment mappings when outlet identity changes despite a geometrically unchanged edge. Read-only reuse of a previously loaded region is permitted only for a complete uniquely owned unchanged snapshot outside conservative old/new upstream invalidation; missing vertices, duplicate vertices, affected routes and modified nodes fail closed. These checks are verified on the existing bounded canonical spherical test fixtures.

**Still not implemented:** Streaming/incremental global priority flood, live migration of lake storage and queued receipts after rerouting, automatic seasonal erosion/terrain/checkpoint coupling, unknown ocean truth, mixed-resolution drainage, stage-dependent lake spillways, climate-calibrated groundwater, large-world endurance, receipt compaction and multi-writer durability. Do not activate new routing while unreconciled water is in flight.


### 2026-10-30 — Drainage transition pending-receipt preflight (CI awaiting verification)

**Scope:** `Yusuf-cm/wild-deck`, `feature/world-engine-v2` only. Baseline head `c668b1f5029c317d4c140babb7e560997ec67259` passed full Java CI: https://github.com/Yusuf-cm/wild-deck/actions/runs/41044276212 .

**Implementation:** `HydrologyDrainageTransitionGateV2` checks a snapshot of the durable cross-region inbox before callers consider activating a fully rebuilt drainage atlas. For every **undelivered** receipt it requires a canonical, verified source-to-target edge in both old and new atlases, matching source and target catchments, and endpoints outside the conservative changed/upstream-affected set. It sums pending volume with finite-value checks, counts each distinct receipt once, and does not consume or mutate the inbox. Delivered receipts are excluded from pending totals; they remain in receipt history for replay detection. An incompatible sample ownership set, forged edge, affected route, or affected receiving vertex fails closed. This is a **preflight only** and does not itself activate or persist a new atlas.

**Commits:** `5c3362e386029f87387dcf3f5e8dae5694ba4d29` implementation; `a4101fec4a4a32f7c0987f2e63607653ec5b00e7` initial tests; `6bb7e891ade691587ee79434e83dc970385d7725` cross-region ownership/replay regression. Tests cover unchanged-topology parked water, changed outlet rejection without inbox mutation, delivered receipt exclusion, forged adjacent edges, and uniquely owned cross-region transfer replay. **New CI is not yet marked passed at this documentation write**; check the run for the latest branch HEAD before accepting this feature.

**Outstanding:** This preflight is not atomic atlas activation, does not migrate already delivered water or stored lake volume, and does not reconcile affected queued transfers. Terrain still requires a complete bounded global priority-flood rebuild; unknown unloaded terrain and mixed resolution are unsupported. Seasonal erosion and sediment are not yet wired into durable lake/route transitions. Real ocean seed discovery, stage-dependent spillways, groundwater, climate calibration, large-world endurance, receipt compaction, and distributed persistence remain unfinished. Never activate changed topology solely on the basis of this gate.


#### Checkpointed runner preflight extension (verification pending)

`HydrologyWorldRunnerV2.verifyDrainageTransition` (`014b416095161dd9bd5f2b40a012669e3947667c`) exposes the read-only gate against the runner's currently checkpointed inbox, without changing the world epoch or receipt state. The integration regression (`5848736892df45ad070fecc869cf00f8fe3eb20c`) restarts a local checkpoint with 11 units of parked cross-region water, verifies unchanged topology permits inspection, verifies a changed outlet is rejected, and checks the 11 units and world state remain intact. **CI PENDING at this writing.** This is not an atomic route-atlas switch or a lake-water migration; a future durable topology-version transaction is still required.


#### Drainage transition gate CI acceptance (supersedes pending notes above)

The gate implementation `5c3362e386029f87387dcf3f5e8dae5694ba4d29` passed full Java CI https://github.com/Yusuf-cm/wild-deck/actions/runs/41050825697 . Its initial receipt safety tests `a4101fec4a4a32f7c0987f2e63607653ec5b00e7` passed https://github.com/Yusuf-cm/wild-deck/actions/runs/41050850719 ; cross-region ownership/replay tests `6bb7e891ade691587ee79434e83dc970385d7725` passed https://github.com/Yusuf-cm/wild-deck/actions/runs/41050888907 . Runner preflight `014b416095161dd9bd5f2b40a012669e3947667c` passed https://github.com/Yusuf-cm/wild-deck/actions/runs/41050960249 ; durable restart integration `5848736892df45ad070fecc869cf00f8fe3eb20c` passed https://github.com/Yusuf-cm/wild-deck/actions/runs/41050979453 . The documentation/integration head `a774d6278a8afeeb52408cd8c14f4af0393eff86` passed full Java CI https://github.com/Yusuf-cm/wild-deck/actions/runs/41051002640 . Its Maven tests, world experiment, resource replay, browser controls, atlas export and real-browser atlas verification steps all concluded success.

**Accepted scope:** Read-only, checkpoint-recoverable validation that pending receipts remain on unchanged verified global edges and outside affected drainage; rejects changed outlet/forged receipt, prevents duplicate shared-boundary receipt accounting, and leaves rejected water durably parked. **Not accepted:** Atomic activation of changed route atlas, live lake storage migration, receipt rerouting through changed terrain, arbitrary unloaded terrain priority-flood, distributed writers, or complete planetary hydrology.


### 2026-10-30 — Content-addressed drainage snapshots and pending-water reconciliation inventory

**Scope:** Only `Yusuf-cm/wild-deck` branch `feature/world-engine-v2`. Baseline head `139a5754c8c9e8ddf620d029f04f7058237aa7c6`, full Java CI **PASSED**: https://github.com/Yusuf-cm/wild-deck/actions/runs/41051161555 . No PRs, merges, other branches, production or paid infrastructure touched.

**Implemented in this run:**
- `HydrologyDrainageSnapshotIdV2` (`ae9912c066bed19c227bab2d917beb297545e1be`) hashes globally owned canonical vertices, directed downstream edges, terminal flags and resolved catchment outlets using a stable SHA-256 wire representation. Seasonal water amounts and source enumeration order do not change the ID. Full Java CI **PASSED**: https://github.com/Yusuf-cm/wild-deck/actions/runs/41058000953 .
- `HydrologyDrainageSnapshotIdV2Test` (`47a37acdf7ba25ce35898b3d83b164e21c9176f4`) checks deterministic identity across reordered rainfall sources and detects changed verified outlets. Full Java CI **PASSED**: https://github.com/Yusuf-cm/wild-deck/actions/runs/41058015265 .
- `HydrologyDrainageTransitionReportV2` (`a9eef2258625ea1eabc944460cc95ae67e01ecab`) provides a deterministic, read-only inventory of undelivered receipts, separating unchanged-edge/catchment water from water blocked by the conservative terrain-change audit. The report includes both snapshot IDs, receipt keys and separate pending volume budgets. Delivered receipts are excluded, not reinjected. CI **PENDING** at documentation write: https://github.com/Yusuf-cm/wild-deck/actions/runs/41058063708 .
- `HydrologyDrainageTransitionReportV2Test` (`f3632a659721752133dad68defd9b1c322f5719f`) verifies safe and blocked receipt classification, 11-unit blocked-water accounting, delivered receipt exclusion and no inbox mutation. CI **PENDING**: https://github.com/Yusuf-cm/wild-deck/actions/runs/41058084631 .
- `HydrologyWorldRunnerV2.inspectDrainageTransition` (`e5cc53a366e1fb29e22bf201ad801731c944b749`) exposes the inventory against the runner's checkpointed inbox without modifying it. CI **PENDING**: https://github.com/Yusuf-cm/wild-deck/actions/runs/41058097812 .
- Checkpoint restart regression (`d6b4f04fc14ba5610d87f1f7ced719e2ee8175df`) verifies that 13 units of blocked water remain parked after inspection and restart; CI status must be checked independently before acceptance.

**Acceptance boundary:** The new IDs identify *verified topology snapshots*, not terrain heights, lake geometry or arbitrary world state. The inventory is diagnostic and read-only. A report with no blocked receipts is NOT authorization to activate the new atlas: stored lake water, settled runoff, world-state migration and atomic atlas/checkpoint switching are still unimplemented. The report reuses the conservative upstream invalidation from the full-sphere rebuild audit. No incremental priority flood, changed-edge water migration, lake reconciliation, seasonal erosion coupling, mixed-LOD solve, climate-calibrated groundwater, receipt-history compaction or distributed persistence is claimed. Full CI for the latest changes is pending and must be verified.

- Confluence and replay-order regression (`223d872dc899a2d73be7a8203773578c1b154e60`) verifies two independent receipts to the same downstream sample are counted once each, identical duplicate delivery is idempotent, and report order/budget is independent of receipt arrival order. CI pending at this write.
- `HydrologyDrainageTransitionGateV2` (`a0da65bd3da9b4ae61d9ab7caf9979d6b454ee2c`) now delegates its strict fail-closed verification to the same classification used by the read-only report, preventing diagnostic and activation preflight logic from drifting. This does NOT activate topology or migrate blocked water. CI pending at this write.


#### 2026-10-30 — Drainage transition inventory CI acceptance

The preceding CI-pending entries have now been verified in the complete branch workflow. `a9eef225` **PASSED** https://github.com/Yusuf-cm/wild-deck/actions/runs/41058063708 ; `f3632a65` **PASSED** https://github.com/Yusuf-cm/wild-deck/actions/runs/41058084631 ; `e5cc53a3` **PASSED** https://github.com/Yusuf-cm/wild-deck/actions/runs/41058097812 ; `d6b4f04f` **PASSED** https://github.com/Yusuf-cm/wild-deck/actions/runs/41058143348 ; `223d872d` **PASSED** https://github.com/Yusuf-cm/wild-deck/actions/runs/41058206692 . The documentation commit `132d4990a92672bc881c92528f01156bc74bc190`, which includes the unified transition gate and all preceding tests, **PASSED full Java CI**: https://github.com/Yusuf-cm/wild-deck/actions/runs/41058248742 .

**Verified boundary:** deterministic SHA-256 drainage snapshot identity, 11-unit blocked receipt inventory, 13-unit checkpoint-restart persistence, 3+5=8 unit confluence accounting with duplicate suppression, delivered receipt exclusion, and shared classification between strict gate and diagnostic report. These are bounded controlled-network tests, not large-world acceptance. A green run does not imply safe atomic topology activation, lake-water migration, arbitrary unloaded-terrain priority flood or completed planetary hydrology.


### 2026-10-30 — Precommit seasonal budget validation, old-edge water landing, and audit provenance

**Scope and baseline:** Only `Yusuf-cm/wild-deck` branch `feature/world-engine-v2`. Baseline `dd72e9e26c646b31ef9351bce6134e0858a7738d` passed full Java CI: https://github.com/Yusuf-cm/wild-deck/actions/runs/41058418636 . No changes to main, other branches, production or paid infrastructure.

**Verified changes and CI:**
- `086cf5ba673182a857b2741a5d3153608cf98064`: **full Java CI PASSED** https://github.com/Yusuf-cm/wild-deck/actions/runs/41066877373 . The coupled snow/soil + lake budget is now checked *before* the single-writer runner writes the lake/world/inbox checkpoint. A package-local, side-effect-free precommit validation hook stages receipt consumption and lake routing first, then permits the atomic checkpoint only if the combined budget passes. The regression forces validation failure after a valid lake solve with 3 units of pending water, verifies epoch/world/inbox remain unchanged after restart, then successfully delivers those same 3 units once in the next valid seasonal advance. Existing snowmelt, evaporation, two-lake-chain, seam and polar tests also ran.
- `5bef21b3210d18f6d80f95603a01e8a9c41f5b4b`: **full Java CI PASSED** https://github.com/Yusuf-cm/wild-deck/actions/runs/41067062882 . New `HydrologyDrainageLandingPlanV2` and `HydrologyWorldRunnerV2.planDrainageLandings` provide a read-only, deterministic inventory of **undelivered** old-topology receipts at their recorded receiving vertices. A receipt already dispatched over a verified old river edge is *not* teleported to the old source's new downstream edge. The plan records old/new destination catchments, next downstream route and conservative affected flag, aggregates confluences without shared-vertex double counting, rejects forged old edges, and never consumes the checkpointed inbox. Tests verify 11 units remain assigned to the original destination across a changed outlet, 3+5=8 confluence accounting independent of receipt order, delivered receipt exclusion, and forged-edge rejection after restart.
- `a42238991a91bb591c01c6a189e0a957c6b6a7f7`: **full Java CI PASSED** https://github.com/Yusuf-cm/wild-deck/actions/runs/41067242008 . The full-sphere rebuild audit now carries the SHA-256 identity of the *previous* topology it was computed from. Reuse checks, transition reports/gates, and landing plans reject audits presented with a different old atlas, even when both have exactly the same canonical vertices and there are no pending receipts. The provenance regression covers each rejection and a matching valid audit. Maven tests, world experiment, resource replay, browser controls, atlas export and real-browser verification completed successfully.

**Acceptance boundary:** The landing plan is **diagnostic, not receipt migration or topology activation**. Its physical assumption is that an already queued receipt was dispatched on the verified OLD edge and will eventually arrive at its recorded destination; no new topology is durably bound to the world checkpoint. The strict transition gate remains fail-closed for affected water. The precommit hook prevents a combined seasonal budget failure from committing the runner's state, but it does not implement distributed transactions or ensure callers provide climate-calibrated forcing.

**Still unfinished:** Atomic versioned drainage activation and safe delivery/reconciliation of affected pending receipts, persistent lake volumes and seasonal stores under changed routing; incremental/streaming depression filling through arbitrary unknown unloaded terrain; mixed-resolution and high-resolution polar drainage; stage-dependent multi-spillway and transient lake hydraulics; seasonal erosion/sediment coupling to durable terrain/lake checkpoints; trusted real ocean outlet discovery, climate-calibrated groundwater/infiltration, large-world endurance, bounded receipt-history compaction, multiplayer-safe persistence and production-save integration. None of these are claimed to have passed. Hydrology remains the priority before other world layers.


### 2026-10-30 — Opt-in receipt-only drainage activation and topology-bound receiving

**Scope:** Only `Yusuf-cm/wild-deck`, `feature/world-engine-v2`. Baseline `8ca1a8f05980e0c5c59e2629f0291e06b2178125` passed full Java CI https://github.com/Yusuf-cm/wild-deck/actions/runs/41067457532 . No main/other-branch changes, PRs, production or paid infrastructure.

**Implementation and verification:**
- `71f90f749bf0da1f29bb2cba1994b18aa12a7f93`: new `HydrologyDrainageActivationV2` with a canonical topology-bound checkpoint payload and a strictly limited receipt-only transition. It validates the old atlas SHA-256 identity, full old-edge landing inventory, absence of stored lake volumes, and no future old-topology receipts. A staged inbox claims each pending receipt at its **recorded destination**; only those receipt volumes (no seasonal rainfall) are routed through the rebuilt closed atlas, conserved, and atomically checkpointed together with the new topology ID. **Full Java CI PASSED** https://github.com/Yusuf-cm/wild-deck/actions/runs/41074825269 .
- `350e345ea8bf9fef241afb26ee294f3b6bc85403`: `HydrologyWorldRunnerV2.activateReceiptOnlyDrainage` swaps the in-memory inbox and epoch only after successful local checkpoint save; `verifyBoundDrainage` rejects mismatched atlases after restart. **Full Java CI PASSED** https://github.com/Yusuf-cm/wild-deck/actions/runs/41074857060 .
- `b19fa99d807d98743809157cfbf5d01012a6226a`: regression tests cover 11 units arriving on an old edge after the source reroutes, checkpoint recovery and no replay, 3+5=8 confluence receipts with duplicate suppression, rejection of future old-topology receipts, forged old edges, nonempty lake storage and invalid checkpoint payloads. **Full Java CI PASSED** https://github.com/Yusuf-cm/wild-deck/actions/runs/41074919781 .
- `12c3eb5d1f48a14af06e8ff275bbe01d13a261f5`: opt-in `receiveVerifiedDrainage` checks the checkpoint's current atlas identity and verifies the exact river edge before persisting a new receipt. **Full Java CI PASSED** https://github.com/Yusuf-cm/wild-deck/actions/runs/41074960893 .
- `8a41441b34a427269c137f44b54c4dacea98ebc7`: regression verifies stale old-edge rejection after activation, durable acceptance of a new-topology edge, and idempotent replay after restart. **CI pending** at this documentation write: https://github.com/Yusuf-cm/wild-deck/actions/runs/41074984345 .
- `04a1b600197b9bde41ab7b17f81d3607bb824b50`: defensive on-disk checkpoint freshness comparison before activation save, to reject a stale local runner that might otherwise overwrite receipts persisted by another instance. **CI pending** https://github.com/Yusuf-cm/wild-deck/actions/runs/41075065911 .
- `65bc098c00d14dfafe92f4de44e6a6a3c44534cb`: regression simulates two local runner instances and checks stale activation cannot erase nine units persisted by the other runner. **CI pending** https://github.com/Yusuf-cm/wild-deck/actions/runs/41075093352 .

**Exact acceptance boundary:** This is a deliberately restricted **single-writer, quiesced-producer, lake-free, receipt-only epoch**. It requires the world checkpoint to be explicitly initialized with `HydrologyDrainageActivationV2.bind`, a complete verified old/new spherical atlas, and a lake-state codec with no stored lakes. The atlas itself is not stored; the caller must reload and verify it after restart. Existing unverified `receive` remains available for compatibility, and the new verified receiving method is opt-in. The disk freshness check is not a cross-process compare-and-swap or distributed lock. The receipt-only solve routes water to terminals within one discrete epoch and does not simulate physical travel time. It must **not** be used for worlds with active lakes, snow/soil reservoirs, concurrent rainfall, unquiesced remote producers, or simultaneous erosion.

**Still unfinished:** General atomic live-world topology activation with lake/snow/soil/river-state migration and concurrent producers; authoritative routing through arbitrary unknown unloaded terrain without full-sphere rebuild; mixed LOD and large polar resolution; multi-spillway lake hydraulics; durable seasonal erosion/sediment coupling; real ocean outlet truth, climate-calibrated infiltration/groundwater, large-world endurance, bounded receipt compaction, distributed transactions, and production-save integration. Do not claim general planetary hydrology completion.


#### 2026-10-30 — Drainage activation verification follow-up

The preceding CI-pending code/test runs have now completed full Java CI **PASSED**: verified-edge receipt test `8a41441b34a427269c137f44b54c4dacea98ebc7` https://github.com/Yusuf-cm/wild-deck/actions/runs/41074984345 ; stale checkpoint pre-save guard `04a1b600197b9bde41ab7b17f81d3607bb824b50` https://github.com/Yusuf-cm/wild-deck/actions/runs/41075065911 ; stale-writer nine-unit water preservation regression `65bc098c00d14dfafe92f4de44e6a6a3c44534cb` https://github.com/Yusuf-cm/wild-deck/actions/runs/41075093352 . The preceding documentation commit `8bbca0bb1c864622be3e6cc68fc49d6d70f10cd8` was **CI pending** at this write: https://github.com/Yusuf-cm/wild-deck/actions/runs/41075170962 .

**Accepted narrowly:** Complete bounded spherical drainage snapshots can execute a zero-lake-storage, receipt-only, quiesced single-writer topology switch, consuming old-edge receipts once at their recorded landing vertices, conserving 11 units and 3+5=8 confluence units, and binding the new topology ID in the same local checkpoint as receipt consumption. The verified receipt entrypoint rejects stale edges after activation; an independent stale local runner cannot silently overwrite a receipt that has already been durably recorded before the freshness check. This does not provide distributed compare-and-swap or prove no concurrent writers exist.

**Not accepted:** Active-lake or snow/soil storage migration, general season/erosion + routing atomic transition, streaming unknown terrain, mixed LOD, multiple spillways, groundwater, global endurance, receipt compaction or multiplayer-safe coordination. The full acceptance boundary above remains in force.


### 2026-10-30 — Receipt-only drainage activation water-source safety

**Scope:** `Yusuf-cm/wild-deck`, `feature/world-engine-v2` only. Baseline `8f5b0ca22136301c4b3e40ca28cf41fdc832c014` passed full Java CI https://github.com/Yusuf-cm/wild-deck/actions/runs/41075268879 . No main/other-branch changes, PRs, production deployments or paid infrastructure changes.

**Implemented:** `HydrologyDrainageActivationV2.commit` now rejects a nested seasonal snow/soil checkpoint even if its stored cell map is empty. Previously the receipt-only path checked only lake volumes, allowing an encoded seasonal world payload to be advanced without the seasonal state machine or combined water budget. The same activation now rejects nonzero `localWater` in either the previous or rebuilt drainage atlas, because its receipt-only solve injects only durable pending receipts and would otherwise discard local rainfall/source water. Both checks happen before claiming receipts or writing the checkpoint.

**Tests:** `HydrologyDrainageActivationV2Test` now covers empty and nonempty nested seasonal stores with seven units of pending water, plus nonzero local source water in either atlas with five units of pending receipts. Rejection must preserve epoch, checkpoint state and undelivered receipts after restart. Commits `ccfc8a30f42404a042c1dd396c12f26343c80089` (initial guard), `390041476c370914131adea474baf9e035dfe665` (correct Java newline literal), `47f89fa5efe5aa7e18a4facb9f6159db2e5069dd` (seasonal tests), `ced46401ce34eaeb8d12cca8ae24c5fdcda34f97` (local-water guard), `f90de15094a9f9580a042d389b6b5d0502d53919` (local-water tests). **CI pending verification at this documentation write.** Do not treat these changes as accepted until the latest test run passes.

**Acceptance boundary:** These are fail-closed guards for the existing single-writer, quiesced, lake-free, zero-local-water, receipt-only transition. They do NOT implement migration of seasonal stores, stored lakes, live rain, groundwater, or sediment, nor authorize arbitrary terrain changes or distributed producers. Remaining hydrology blockers: full atomic lake/snow/soil migration, incremental unknown-terrain drainage, mixed LOD, stage-dependent multi-spillway lake hydraulics, seasonal erosion coupling, real ocean truth, calibrated groundwater/infiltration, large-world endurance, bounded receipt history and distributed checkpoint coordination.


#### 2026-10-30 — Receipt-only water-source safety CI acceptance

The preceding pending tests and guards are now verified. Seasonal codec guard `ccfc8a30f42404a042c1dd396c12f26343c80089` **PASSED** full CI https://github.com/Yusuf-cm/wild-deck/actions/runs/41083920832 ; corrected newline match `390041476c370914131adea474baf9e035dfe665` **PASSED** https://github.com/Yusuf-cm/wild-deck/actions/runs/41083944571 ; seasonal checkpoint recovery regression `47f89fa5efe5aa7e18a4facb9f6159db2e5069dd` **PASSED** https://github.com/Yusuf-cm/wild-deck/actions/runs/41083968649 ; local-source rejection `ced46401ce34eaeb8d12cca8ae24c5fdcda34f97` **PASSED** https://github.com/Yusuf-cm/wild-deck/actions/runs/41084028401 ; local-source regression `f90de15094a9f9580a042d389b6b5d0502d53919` **PASSED** https://github.com/Yusuf-cm/wild-deck/actions/runs/41084083575 . The initial progress-document commit `a2031fbf5fc55d21c4309b36b60fa66444375641` **PASSED** full CI https://github.com/Yusuf-cm/wild-deck/actions/runs/41084117079 .

**Accepted narrowly:** Receipt-only drainage activation now rejects both empty and nonempty nested seasonal snow/soil checkpoints and any nonzero atlas-local source water, preserving 7-unit and 5-unit pending receipt budgets respectively on failure and after checkpoint reopen. Full CI includes Maven tests, world experiment, resource replay, browser save controls, atlas export and real-browser verification. These safeguards prevent silent bypass of seasonal storage and loss of atlas-local water; they do not implement general lake/seasonal water migration, and all outstanding hydrology blockers listed above remain unfinished.


### 2026-10-30 — Optimistic stale-runner water conservation across epoch types

**Scope and baseline:** Only `Yusuf-cm/wild-deck` branch `feature/world-engine-v2`. Previous head `39774a7c6a4c689d911b9772fbfb639eedd1c460` passed full Java CI https://github.com/Yusuf-cm/wild-deck/actions/runs/41084367992 . No changes to main, other branches, production, paid infrastructure or PRs.

**Problem:** The local checkpoint store rejected an epoch regression but allowed a stale runner to advance from an outdated same-epoch inbox, replacing a newer persisted receipt with a higher-epoch checkpoint. Drainage activation already had a specific freshness check, but ordinary river advances, queued-frontier advances, and lake/seasonal advances did not.

**Implementation:** `HydrologyCheckpointStoreV2.saveNext` requires exactly one epoch advance and verifies the on-disk checkpoint equals the caller's expected prior world+inbox snapshot before the atomic replacement. `HydrologyDurableEpochV2` has overloads that accept this expected checkpoint, used by both ordinary and frontier-queueing `HydrologyWorldRunnerV2` advances. The lake-aware advance uses the same guard, and receipt ingestion checks runner freshness before acknowledging even a duplicate receipt. Receipt-only drainage activation now uses the shared guard instead of duplicating its preflight. Existing standalone `HydrologyDurableEpochV2` APIs remain available for compatibility, without expected-checkpoint protection; callers should prefer the runner-backed entrypoints.

**Regression:** `HydrologyStaleRunnerConservationV2Test` covers an independently received seven-unit river receipt before stale ordinary advance; a five-unit receipt before stale frontier advance; a nine-unit receipt before stale lake-aware advance on a complete canonical sphere; and a stale receipt writer reopening and conserving 3+5=8 units. Rejected stale attempts must not advance the in-memory epoch or erase the durable receipt; fresh reopen must deliver the water once. Code commits: `269ef5ba62335ed9e52285421d2f1632561b53e2`, `c0f4b3427b20fe541e6e206b8019406fdf1f6593`, `84e586aca19ea62cc16daf1f15c0a798a11af586`, `60b5ae17bdb079eec0dde33c19802dce5d372b94`; regression commit `0bc9f9a5b4871f80356f115137e100dcf4dd9d93`. **CI PENDING** at this write: https://github.com/Yusuf-cm/wild-deck/actions/runs/41093756241 . Do not mark this accepted until full CI passes.

**Exact limit:** This is optimistic same-process/local-file stale-writer detection, **not** an interprocess atomic compare-and-swap or distributed lock. Another process can write between the freshness read and rename. General live lake/snow/soil migration, receipt compaction, incremental arbitrary-unloaded-terrain drainage, mixed LOD, multiple spillways, erosion-coupled water state, climate/groundwater calibration and large-planet endurance remain unfinished.


#### 2026-10-30 — Stale-runner conservation regression CI accepted

The new `HydrologyStaleRunnerConservationV2Test` commit `0bc9f9a5b4871f80356f115137e100dcf4dd9d93` **PASSED full Java CI** https://github.com/Yusuf-cm/wild-deck/actions/runs/41093756241 . The preceding four implementation commits also **PASSED** full Java CI: `269ef5ba62335ed9e52285421d2f1632561b53e2` https://github.com/Yusuf-cm/wild-deck/actions/runs/41093651623 ; `c0f4b3427b20fe541e6e206b8019406fdf1f6593` https://github.com/Yusuf-cm/wild-deck/actions/runs/41093672709 ; `84e586aca19ea62cc16daf1f15c0a798a11af586` https://github.com/Yusuf-cm/wild-deck/actions/runs/41093694821 ; `60b5ae17bdb079eec0dde33c19802dce5d372b94` https://github.com/Yusuf-cm/wild-deck/actions/runs/41093708151 . Maven tests, world experiment, resource replay, browser save controls, atlas export and real-browser verification all passed on the regression commit.

**Verified narrow result:** Reopening a stale local runner after another instance persists water prevents ordinary river, frontier-queueing and lake-aware epoch advances from erasing 7, 5 and 9 units respectively. Reopening the authoritative checkpoint allows one delivery with no pending duplicate. The stale receipt-writer regression preserves 3+5=8 units after reopen. The prior section's CI-pending note is superseded for the regression and implementation commits; the initial documentation workflow is separately pending as of this write. These tests do not prove true cross-process atomicity, live reservoir migration, distributed locks, or unrestricted planetary drainage.


#### 2026-10-30 — Seasonal stale-writer conservation extension

Added a separate coupled snow/soil + river + lake regression at `e6334853c3273333dc8574e4d355379e6926e225`. A four-unit durable river receipt is persisted after a stale runner opens; a stale seasonal advance must fail without changing the epoch or receipt, while a fresh runner must advance through the combined seasonal budget with exactly four units of received river water, four units of terminal discharge, zero residual and no pending replay. The plan is a complete, canonical 26-vertex single-resolution sphere with no active lakes, and zero-precipitation weather forcing. **CI PENDING** at this write; do not claim this new seasonal regression passed until inspected. It does not test seasonal erosion or active lake migration.


#### 2026-10-30 — Seasonal stale-writer regression accepted

The seasonal four-unit receipt regression `e6334853c3273333dc8574e4d355379e6926e225` **PASSED full Java CI** https://github.com/Yusuf-cm/wild-deck/actions/runs/41094005282 . Its Maven test suite, world experiment, finite-resource replay, browser controls, atlas export and browser verification passed. The earlier test suite `0bc9f9a5b4871f80356f115137e100dcf4dd9d93` also **PASSED** https://github.com/Yusuf-cm/wild-deck/actions/runs/41093756241 ; initial progress-document commit `d60192caa87a54bd18431b2ff946583f6e6a763f` **PASSED** https://github.com/Yusuf-cm/wild-deck/actions/runs/41093825993 . The earlier seasonal CI-pending note is superseded.

**Precisely verified:** A stale seasonal runner cannot overwrite an independently persisted four-unit river receipt, and a reopened runner can consume it once through the complete canonical dry-lake spherical network with zero seasonal budget residual. This is an additional checkpoint-safety regression, not seasonal erosion, a physical wet-lake transition or a distributed transaction. The remaining hydrology blockers listed above are unchanged.


### 2026-10-30 — Opt-in wet-lake drainage activation with geometry-bound checkpoints

**Scope:** Exclusively `Yusuf-cm/wild-deck` branch `feature/world-engine-v2`. Baseline `389a0ea3b6e24f97b807538f29665ab3b7f87c77` **PASSED** full Java CI: https://github.com/Yusuf-cm/wild-deck/actions/runs/41094156582 . No main or other-branch changes, PRs, production or paid infrastructure.

**Implementation (new CI not yet accepted):** Added `HydrologyWetLakeDrainageActivationV2` and `HydrologyWorldRunnerV2.activateWetLakeDrainage`. For caller-verified complete spherical old/new lake plans, zero local rainfall, no snow/soil stores, and quiesced single-writer receipt delivery, the transition verifies old and new route edges against their respective atlases, reconstructs old wet lake columns, remaps storage to new basin cells, injects displaced volume at each NEW spill target, lands all verified old-edge receipts at their recorded destination, and runs one lake-aware downstream solve. It checks old storage + delivered receipts against final lake storage + terminal discharge, then checkpoints consumed receipts, lake storage and rebuilt drainage identity together with the existing local optimistic stale-writer guard. Missing wet lake cells, forged old edges, future receipts, mismatched plan routes, nonzero local sources and unsupported seasonal stores fail closed. This is a bounded **instantaneous** remap, not a physically timed spill wave.

**Durable geometry provenance:** Added `HydrologyLakePlanSnapshotIdV2` hashing canonical basin anchors, cell bed elevations and areas, spill elevations, targets and confirmed outlets, independent of lake list ordering. Extended the drainage bound-state codec with backward-compatible version 2 carrying the lake-plan SHA-256 identity. New `HydrologyWetLakeDrainageActivationV2.bind` requires both atlas and verified lake plan; `verifyBoundWetLakeDrainage` checks both identities after reopen. The older receipt-only transition rejects geometry-bound checkpoints rather than silently discarding the lake identity.

**Tests committed (CI QUEUED, NOT YET PASSED):** `HydrologyWetLakeDrainageActivationV2Test` covers a one-cell wet lake whose bed rises from -5 to -4 under sediment deposition, overflow plus seven old-edge receipt units, durable restart and exactly-once delivery; disappearing wet-cell rejection preserving nine pending units; future old-topology receipt rejection preserving six units; stale local runner rejection preserving eight units; 3+5 confluence idempotency; forged edge rejection; unbound/stale old lake-plan rejection; mismatched new plan and nested seasonal-state rejection. `HydrologyLakePlanSnapshotIdV2Test` covers order-independent geometry hashes, changed-bed identity invalidation, duplicate anchors and v1/v2 checkpoint codec round trips. **Do not mark these as accepted until full CI succeeds.** Relevant queued workflows include https://github.com/Yusuf-cm/wild-deck/actions/runs/41102569025 and https://github.com/Yusuf-cm/wild-deck/actions/runs/41102603969 . All were queued at this documentation write, so there is no new green acceptance evidence yet.

**Exact acceptance boundary:** Single-writer, quiesced producers, authoritative closed same-resolution terrain/lake plans, no concurrent rainfall, no active snow/soil/groundwater, no missing wet cells, no surface-water displacement outside new lakes, and one discrete routing epoch. The checkpoint persists geometry and topology **identities**, not the complete terrain or basin plan. The caller must reload and verify those plans after restart. No distributed CAS/locking, multi-process atomicity, transient lake hydraulics, verified erosion-to-terrain checkpoint, or live climate forcing is provided.

**Still unfinished:** CI acceptance of this run; general live seasonal snow/soil + lake reconciliation; safe displacement of water from disappearing wet lake cells; simultaneous erosion, sediment and rainfall persistence; arbitrary unloaded-terrain incremental priority flood; mixed-LOD drainage and high-resolution polar cases; multi-spillway hydraulics; trusted ocean masks, groundwater/climate calibration, long-horizon large-planet endurance, bounded receipt history and distributed persistence. Hydrology remains the priority before other layers.

## 2026-10-30 — Opt-in lake disappearance / exposed wet-cell displacement (tests verified)

- Baseline before this change: commit `c3677850caf93ffc4fc2aff47db9ee93828e745d`, GitHub Actions **PASSED**: https://github.com/Yusuf-cm/wild-deck/actions/runs/41102768862 . Earlier intermediate failures were superseded by that green baseline.
- `HydrologyWetLakeReconciliationV2.reconcileDisplaced` is an **explicit opt-in** finite-volume remap. Unlike the existing fail-closed `reconcile`, it releases the old water column of each formerly wet cell absent from every new basin **at that cell's original canonical address**. Water retained in new lakes still follows stage/volume hypsometry; excess above the new lake capacity still enters its verified spill target. The returned released-water budget includes both exposed-cell displacement and capacity overflow.
- `HydrologyWetLakeDrainageActivationV2.commitDisplaced` and `HydrologyWorldRunnerV2.activateDisplacedWetLakeDrainage` combine that remap with verified rebuilt routing, old-edge pending receipts, receipt consumption, and a single local checkpoint replacement. They reject a displaced source without a rebuilt route, nonzero atlas local water, unbound geometry, future/forged receipts, stale local checkpoints, and nested seasonal snow/soil state. The original strict activation remains unchanged.
- New unit tests cover partial lake contraction (5 stored + 5 displaced), complete lake disappearance (6 + 4 displaced at original cells), rejection of overlapping basins/unknown storage, and deterministic remapping. New integration tests cover a disappearing wet lake with 5 stored units plus a 9-unit pending receipt, exactly-once receipt consumption and restart verification, future-receipt rejection without mutation, and refusal of seasonal payloads.
- **CI ACCEPTED for implementation and regression tests:** all workflows through the additional forged-receipt and stale-writer tests passed: `711955a68fa823e6b58eb1149ef0ca3eec697fb4` https://github.com/Yusuf-cm/wild-deck/actions/runs/41110530320 (**success**); `7c4a67f7f4bf2ea78e3d8cb85a4da3d6be797d89` https://github.com/Yusuf-cm/wild-deck/actions/runs/41110652065 (**success**). The additional test verifies that forged old-edge receipts and stale writers cannot consume or overwrite pending water during displaced-lake activation. The progress-document workflow at `2e667d53c8021d3811952e22b649dee56840ff15` also passed: https://github.com/Yusuf-cm/wild-deck/actions/runs/41110574663 .

### Unfinished acceptance gaps (still open)

1. This displacement is **instantaneous**, not a transient overland-flow, floodplain, or hydraulic-wave solver. The caller must provide complete authoritative old/new closed spherical plans at the same sampling resolution and quiesce water producers.
2. No general atomic reconciliation of live **seasonal snow/soil** reservoirs with changing lake/terrain geometry; the new path explicitly rejects seasonal checkpoints.
3. No incremental arbitrary-unloaded-tile priority flood, mixed-resolution drainage, multiple simultaneous lake spillways, groundwater or climate-calibrated evaporation.
4. No coupled seasonal sediment erosion/deposition, geomorphic terrain persistence, or erosion-driven hydrology activation with a verified combined budget.
5. No distributed interprocess transaction/lock, receipt-history compaction, large-world endurance benchmark, or production deployment. Local optimistic freshness is not cross-process compare-and-swap.
6. No production, paid infrastructure, main branch, or other branch changes in this run. Hydrology remains incomplete.


## 2026-10-30 — Quiescent seasonal snow/soil preservation through wet-lake drainage transitions

**Scope:** Only `Yusuf-cm/wild-deck` branch `feature/world-engine-v2`. Previous accepted baseline `60e4c94af7582ada202ff1c6a4ae1b9eda8f8f84` passed full CI: https://github.com/Yusuf-cm/wild-deck/actions/runs/41110794872 .

**New opt-in behavior:** `HydrologyWetLakeDrainageActivationV2.commitSeasonalDisplaced` and `HydrologyWorldRunnerV2.activateSeasonalDisplacedWetLakeDrainage` preserve the exact serialized snow/soil depth map at unchanged canonical geographic vertices while reconciling lake storage, displaced former lake cells and verified pending old-edge river receipts. The transition epoch applies **no** rainfall, snowmelt, soil infiltration, evaporation or erosion. The caller must keep planetary radius, spherical cell areas and sampling resolution constant and supply complete authoritative old/new plans. Foreign snow/soil addresses and nonseasonal payloads fail closed. Existing nonseasonal wet-lake activation continues to reject seasonal state unless this new entrypoint is explicitly selected. This is **not** general live seasonal forcing during a terrain transition.

**Resume weather after topology change:** `HydrologySeasonalLakeNetworkV2.advanceBound` and the runner's bound lake advance validate the persisted drainage and lake-geometry IDs against the caller-supplied authoritative atlas and plan, verify the seasonal runoff plan has identical edges and canonical ownership, apply snow/soil runoff plus durable receipts, validate the combined seasonal water budget **before** checkpoint replacement, and preserve both IDs across the next weather epoch. Legacy unbound `advance` remains unchanged.

**Regression coverage:** `HydrologyWetLakeDrainageActivationV2Test` now exercises a disappearing five-unit wet lake, a nine-unit old-edge receipt, snow depth 2 and soil depth 3 retained at the original canonical cell, restart and once-only receipt consumption, then a subsequent one-unit rainfall epoch conserving the five-unit seasonal store. Additional tests reject a foreign-resolution snow/soil cell without losing seven units of pending water, reject nonseasonal use of the seasonal entrypoint, and reject a stale bound seasonal runner while preserving and subsequently delivering four units of independently persisted new-topology receipt water. These are bounded same-resolution canonical 26-vertex fixtures, not large-world benchmarks.

**Commits:** `76ebee1c8347166880b3bc4d066432b1c1a6435d` (initial seasonal opt-in, superseded), `d5185c860fd5b13f04f631f41bc59126e72f1f67` (correct seasonal marker), `62356ff6bd8de241e2c1a8209fa78056982e462e` (runner activation), `5b66150cbf9881d5ddd300535ee09396db479e8a` (migration tests), `41759d434235de81266f053275a01107311e4e84` (bound advance), `14e2e0e5e52331663f1c26780775d2b14108a3d0` (seasonal adapter), `484947ff7eea454eac169c742b8987f8bd95ea55` (restart/rainfall regression), `b8a15bbd6a6dd755b3647f4362f2ef4618902b72` (stale-writer regression). Initial commit `76ebee1c` **FAILED** CI https://github.com/Yusuf-cm/wild-deck/actions/runs/41118485649 because its seasonal marker did not match a newline; the corrected `d5185c86` **PASSED** full CI https://github.com/Yusuf-cm/wild-deck/actions/runs/41118499009 . Migration regressions `5b66150c` **PASSED** full CI https://github.com/Yusuf-cm/wild-deck/actions/runs/41118549962 . **The bound-advance and later tests are CI PENDING at this documentation write**: https://github.com/Yusuf-cm/wild-deck/actions/runs/41118654775 , https://github.com/Yusuf-cm/wild-deck/actions/runs/41118681233 , https://github.com/Yusuf-cm/wild-deck/actions/runs/41118715481 . Do not claim acceptance until their conclusions are inspected.

**Still unfinished:** No rainfall, snowmelt, soil flux or evaporation concurrent with the terrain-remap epoch; no explicit terrain/erosion persistence in the same transaction; no stored cell-area/radius provenance to independently verify the same-area assumption; no arbitrary unloaded-terrain incremental drainage or mixed LOD; no multi-spillway hydraulics, groundwater, climate calibration or transient flood travel; no distributed atomic CAS/lock, receipt-history compaction or large-world endurance acceptance. Hydrology remains the only development priority.


### 2026-10-30 — Seasonal transition and bound-weather CI acceptance

The CI-pending caveat in the preceding section is now **superseded** for all implementation and regression commits. `41759d434235de81266f053275a01107311e4e84` **PASSED** https://github.com/Yusuf-cm/wild-deck/actions/runs/41118634731 ; `14e2e0e5e52331663f1c26780775d2b14108a3d0` **PASSED** https://github.com/Yusuf-cm/wild-deck/actions/runs/41118654775 ; `484947ff7eea454eac169c742b8987f8bd95ea55` **PASSED** https://github.com/Yusuf-cm/wild-deck/actions/runs/41118681233 ; `b8a15bbd6a6dd755b3647f4362f2ef4618902b72` **PASSED** https://github.com/Yusuf-cm/wild-deck/actions/runs/41118715481 . The initial progress documentation commit `7b583ed9182bd95a37666ccdb65f2afc71cae72a` **PASSED** full CI https://github.com/Yusuf-cm/wild-deck/actions/runs/41118767843 . Maven tests, world experiment, finite-resource replay, browser save controls, atlas export and real-browser verification all completed successfully on the final implementation/test and documentation commits.

**Accepted narrowly:** Quiescent same-resolution seasonal snow/soil depth preservation during verified lake disappearance/terrain routing change; one-time old-edge receipt landing; durable lake+topology+snow/soil checkpoint; subsequent bound seasonal rainfall with a checked water budget; stale bound seasonal runner rejection and recovery of new-topology receipts. The five-unit seasonal store, five-unit displaced lake volume, nine-unit old receipt and four-unit new receipt are fixture-specific test quantities, not general planetary calibration. No erosion-coupled forcing during remap, distributed atomicity, incremental unloaded-tile routing, mixed LOD or large-world endurance has been demonstrated. This documentation acceptance commit has its own CI pending until separately inspected.


## 2026-10-30 — Spherical area safety and seasonal erosion preview (verified)

**Scope and baseline:** Only `Yusuf-cm/wild-deck`, branch `feature/world-engine-v2`. The previous baseline `b3ddf155b3c1ffd40db8cf41980688b4d9e8d477` passed full Java CI: https://github.com/Yusuf-cm/wild-deck/actions/runs/41118943315 . No merge, PR, production or paid infrastructure changes.

**Spherical geometry and water-budget guards:**
- Added `HydrologySphericalAreaContractV2` with explicit-radius validation for canonical spherical lake-cell horizontal areas and snow/soil forcing areas. Opt-in `advanceBound` and `activateSeasonalDisplaced` wrappers check these contracts before the existing durable receipt and topology workflows. Polar cell area and wrong-radius/incorrect-weather-area rejection are tested.
- Hardened `HydrologyWetLakeDrainageActivationV2`: shared old/new wet cells must retain **exactly the same horizontal area**, even through the older transition entrypoints. A changed radius or area convention cannot silently change the depth-to-volume conversion of a retained lake cell. The guard also rejects multiply owned wet cells.
- Integration regression verifies that wrong-radius transition and invalid seasonal forcing are rejected **without consuming a seven-unit or three-unit pending receipt**, respectively; a valid same-radius transition and a subsequent bound seasonal epoch can consume their receipts once, persist the checkpoint and preserve the combined water budget.
- The explicit radius is **caller-supplied**, not independently persisted in the checkpoint. A completely disappearing lake with no shared wet cells still needs the opt-in area-checked entrypoint for radius validation. This is not mixed-resolution remapping or a distributed transaction.

**Seasonal runoff-to-erosion coupling, deliberately preview-only:**
- Added `HydrologySeasonalErosionPreviewV2`. It maps a conserved `HydrologySnowSoilStateV2.Step` to exactly one owned water volume per contiguous global-address terrain sample, derives canonical spherical areas for sediment mass conversion, then invokes the existing verified-outlet `HydrologyTerrainOverlayV2.evolveWindow` incision/deposition solver.
- Checks runoff/source ownership, persisted snow/soil cell ownership, reported runoff totals, and equality of total runoff to terminal discharge after the recalculated drainage. A dry season must produce zero runoff and zero sediment. Tests verify deterministic overlay replay, finite terrain, mass conservation (`eroded = deposited + exported`), and rejection of missing, duplicate or forged water inventories.
- **Important:** This is a noncommitting planning step. It neither sends water a second time nor activates changed global river topology. The resulting erosion overlay is not persisted atomically with a live seasonal/lake checkpoint. Sediment capacity and incision remain uncalibrated; no erosion-driven water migration or flood travel timing is claimed.

**Exact CI acceptance:**
- `2577cef69525c9cca5747160a95564fc9fa5be4c` (area contract) **PASSED** https://github.com/Yusuf-cm/wild-deck/actions/runs/41125767789 .
- `eb224b8fd0ef22c5137fede58358bae1b175cb38` (retained-wet-cell area guard) **PASSED** https://github.com/Yusuf-cm/wild-deck/actions/runs/41125789637 .
- `01da97a909a45a57f8420b88f4dae65e4e576c9e` (radius and receipt integration regressions) **PASSED** https://github.com/Yusuf-cm/wild-deck/actions/runs/41125834298 .
- `ce2d8416446b88dff0d675249c75be146f5424b2` (seasonal erosion preview) **PASSED** https://github.com/Yusuf-cm/wild-deck/actions/runs/41125897589 .
- `85aa20630facb8dcc81d455d277b2791301cbef3` (initial preview tests) **FAILED** https://github.com/Yusuf-cm/wild-deck/actions/runs/41125925936 because a test incorrectly expected a radius change to be detected from a runoff step that contains no area provenance. This invalid expectation was removed; the implementation never claimed such detection.
- `57eeddbbda09eccbbaa3204f5b71f9a59584842d` (corrected test) **PASSED** https://github.com/Yusuf-cm/wild-deck/actions/runs/41125941357 .
- `2e67346488fa1c1574191c0d8dc2fb0b5169c789` (store ownership guard) **PASSED** https://github.com/Yusuf-cm/wild-deck/actions/runs/41125982011 .
- `8000c3d21ed8c979c4c05f1362143c860a5ffbb4` (forged-budget and missing-store tests) **PASSED** full Java CI https://github.com/Yusuf-cm/wild-deck/actions/runs/41125994331 . All regression tests and the repository's standard CI jobs passed at this implementation HEAD.

**Still unfinished (do not claim full hydrology acceptance):** No general atomic erosion + sediment + live seasonal lake/water transition; no durable radius/area provenance; no arbitrary unloaded-terrain incremental priority flood or mixed-LOD routing; no multi-spillway hydraulics, groundwater, climate calibration or transient flood routing; no large-world endurance benchmark, bounded receipt-history compaction, or interprocess atomic CAS/distributed locking. Existing closed, single-resolution global atlas and quiescent producer assumptions remain. Hydrology stays the priority before other world layers.


## 2026-10-31 — Checked seasonal erosion input and bounded unloaded-tile tracing

**Scope:** Only `Yusuf-cm/wild-deck` branch `feature/world-engine-v2`. Baseline `deef57457aea2c34629749f1b24512e7a19fda17` passed the repository workflow: https://github.com/Yusuf-cm/wild-deck/actions/runs/41126228957 . No merge, PR, production, paid infrastructure, or other branch operations.

**Seasonal water/erosion preflight (noncommitting):**
- `HydrologySeasonalErosionPreviewV2.calculate` now rejects cross-world snow/soil steps, negative/nonfinite reported budget components, and steps whose initial storage plus precipitation does not balance evaporation, runoff and final storage. This is an additional integrity check; a standalone Step still cannot prove its original forcing radius.
- `calculateFromForcing` is an opt-in checked entrypoint: it validates complete window/previous-store forcing ownership, checks each forcing cell's spherical area against the same supplied radius used for sediment mass, computes the snow/soil step from prior stores, and returns both the seasonal step and erosion preview. It does not persist terrain, receipts, snow/soil or lake storage. Tests exercise deterministic replay despite different map insertion order, budget forgery, wrong world, missing cells and radius drift.
- Implementation commit `d9a568fe97d5099e1d6dbcd9942e332050fb9663` **PASSED** CI: https://github.com/Yusuf-cm/wild-deck/actions/runs/41134350772 . Regression commit `c3c674015b15dbd0793afe3eb09d9348f6d77cfd` CI: https://github.com/Yusuf-cm/wild-deck/actions/runs/41134376539 (inspect conclusion before claiming acceptance).

**Deterministic unloaded-tile tracing (read-only):**
- New `HydrologyUnloadedSphericalTraceV2.follow` traverses a canonical, strictly downhill spherical route using the global elevation sampler, regardless of which region is loaded. A bounded result distinguishes a verified outlet, unresolved pit/flat, and exhausted visit budget. The next canonical vertex is returned for resumable batches. The routine never invents a catchment or outlet when terrain is unresolved.
- Tests cover deterministic stitching across two unloaded region boundaries, longitude seam ownership without duplicate vertices, verified outlet termination, pits/flats, budget exhaustion, invalid canonical samples and mixed-resolution outlet rejection.
- Implementation commit `f9cd0f6aa71ae35239e44e94fa94f98fb748443a` CI: https://github.com/Yusuf-cm/wild-deck/actions/runs/41134449957 ; regression commit `4316c05cfb7ac4c8cb0d0db270cb6aaca2bdef4b` CI: https://github.com/Yusuf-cm/wild-deck/actions/runs/41134468658 . These runs require conclusion inspection before acceptance.

**Still unfinished:** This downhill trace does not fill depressions or establish globally stable catchment/river identities for arbitrary unloaded terrain, and it is not wired to durable receipts or the scheduler. No general atomic erosion/sediment/terrain/lake/snow/soil checkpoint, no persisted planetary radius provenance, mixed-resolution routing, multi-spillway hydraulics, groundwater, calibrated climate, transient flood timing, large-world endurance or distributed CAS/locking. Seasonal preview is not an erosion activation transaction. Continue hydrology before other layers.
