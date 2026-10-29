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
