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

**CI verification status:** prior checkpoint/identity commits passed, including https://github.com/Yusuf-cm/wild-deck/actions/runs/40905755089 and https://github.com/Yusuf-cm/wild-deck/actions/runs/40905506937 . New frontier dispatch integration initially failed because the graph/result mismatch regression exposed missing validation; fixed at `26fe3b2e7faf5a5f7a0561f2ed07886f8518209d`. Recheck the newest full workflow before marking the complete continuation verified: https://github.com/Yusuf-cm/wild-deck/actions?query=branch%3Afeature%2Fworld-engine-v2 .

**Remaining acceptance gaps:** (1) durable runtime scheduler and exactly-once acknowledgements across multiple independent processes, (2) authoritative planet-wide downstream routing, seam/pole wrapping and verified ocean outlets, (3) cross-mosaic stable lake geometry and catchments across unknown terrain, (4) coupled climate-derived rainfall, snow/groundwater, calibrated time steps, (5) sediment/erosion feedback into persistent terrain and rerouting, (6) performance and long-horizon conservation tests. The local checkpoint file is not wired into the game save pipeline. Do **not** claim global hydrology complete.
