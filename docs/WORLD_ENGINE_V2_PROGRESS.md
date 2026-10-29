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
