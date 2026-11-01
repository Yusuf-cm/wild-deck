package com.wilddeck.world;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class HydrologyLakeSedimentSnapshotV2Test {
    private static GlobalHydrologyAddressV2.Sample at(long x, long y) {
        return new GlobalHydrologyAddressV2.Sample(1, 2, x, y);
    }
    private static final GlobalHydrologyAddressV2.Sample A = at(1, 2),
        B = at(2, 2), C = at(3, 2), D = at(4, 2);

    private static HydrologySphericalLakeBasinsV2.Plan baseline() {
        var routes = new ArrayList<>(List.of(
            new HydrologyFlowGraphV2.Node(A, 0, B, false),
            new HydrologyFlowGraphV2.Node(B, 0, C, false),
            new HydrologyFlowGraphV2.Node(C, 0, D, false),
            new HydrologyFlowGraphV2.Node(D, 0, null, true)));
        for (long y = 0; y <= 4; y++)
            for (long x = 0; x < (y == 0 || y == 4 ? 1 : 8); x++) {
                var sample = at(x, y);
                if (!Set.of(A, B, C, D).contains(sample))
                    routes.add(new HydrologyFlowGraphV2.Node(sample, 0, null, true));
            }
        return new HydrologySphericalLakeBasinsV2.Plan(routes, List.of(
            new HydrologySphericalLakeBasinsV2.Lake(A, List.of(A), B, D,
                new LakeReservoirV2.Basin(new double[]{0}, new double[]{1}, 5))));
    }

    private static HydrologyLakeSedimentNetworkV2.Result infill(
            HydrologySphericalLakeBasinsV2.Plan baseline) {
        return HydrologyLakeSedimentNetworkV2.step(baseline,
            new HydrologyLakeStateV2.State("world", Map.of(A, 4.0)),
            Map.of(A, new HydrologyLakeNetworkStepV2.Forcing(0, 0, 1)),
            Map.of(A, new HydrologyLakeSedimentNetworkV2.Sediment(4000, 2000)),
            SeasonalLakeEvolutionV2.EvaporationMode.START_OF_STEP);
    }

    @Test void restartRestoresChangedBedAndWaterForAnotherSeason() {
        var base = baseline();
        var changed = infill(base);
        assertEquals(3, changed.updatedPlan().lakes().get(0).reservoir().capacity(), 1e-12);
        assertEquals(3, changed.water().state().volumes().get(A), 1e-12);
        var snapshot = HydrologyLakeSedimentSnapshotV2.encode(
            base, changed.updatedPlan(), changed.water().state());
        var restarted = HydrologyLakeSedimentSnapshotV2.decode(base, snapshot);
        assertEquals(snapshot, HydrologyLakeSedimentSnapshotV2.encode(
            base, restarted.plan(), restarted.water()));
        assertEquals(3, restarted.plan().lakes().get(0).reservoir().capacity(), 1e-12);
        assertEquals(changed.water().state(), restarted.water());

        var runoff = new ArrayList<HydrologyFlowGraphV2.Node>();
        for (var node : restarted.plan().routes())
            runoff.add(new HydrologyFlowGraphV2.Node(node.address(),
                node.address().equals(A) ? 2 : 0,
                node.downstream(), node.terminal()));
        var resumed = HydrologyLakeSedimentNetworkV2.step(
            new HydrologySphericalLakeBasinsV2.Plan(runoff, restarted.plan().lakes()),
            restarted.water(),
            Map.of(A, new HydrologyLakeNetworkStepV2.Forcing(0, 0, 1)),
            Map.of(A, new HydrologyLakeSedimentNetworkV2.Sediment(0, 2000)),
            SeasonalLakeEvolutionV2.EvaporationMode.START_OF_STEP);
        assertEquals(3, resumed.budget().finalStorage(), 1e-12);
        assertEquals(2, resumed.budget().terminalOutflow(), 1e-12);
        assertEquals(0, resumed.budget().waterResidual(), 1e-12);
    }

    @Test void splitSeasonRestartMatchesUninterruptedSedimentAndWater() {
        var base = baseline();
        var initial = new HydrologyLakeStateV2.State("world", Map.of(A, 4.0));
        var initialSnapshot = HydrologyLakeSedimentSnapshotV2.encode(base, base, initial);
        var first = new HydrologyLakeSedimentSeasonsV2.Season(
            Map.of(), Map.of(A, new HydrologyLakeNetworkStepV2.Forcing(0, 0, 1)),
            Map.of(A, new HydrologyLakeSedimentNetworkV2.Sediment(4000, 2000)));
        var second = new HydrologyLakeSedimentSeasonsV2.Season(
            Map.of(A, 10.0), Map.of(A, new HydrologyLakeNetworkStepV2.Forcing(0, 0, 1)),
            Map.of(A, new HydrologyLakeSedimentNetworkV2.Sediment(0, 2000)));
        var uninterrupted = HydrologyLakeSedimentSeasonsV2.simulate(
            base, initial, List.of(first, second),
            SeasonalLakeEvolutionV2.EvaporationMode.START_OF_STEP);
        var one = HydrologyLakeSedimentSeasonsV2.resume(
            base, initialSnapshot, List.of(first),
            SeasonalLakeEvolutionV2.EvaporationMode.START_OF_STEP);
        var two = HydrologyLakeSedimentSeasonsV2.resume(
            base, one.snapshot(), List.of(second),
            SeasonalLakeEvolutionV2.EvaporationMode.START_OF_STEP);
        assertEquals(uninterrupted.get(1).water().state(), two.steps().get(0).water().state());
        assertEquals(uninterrupted.get(1).budget(), two.steps().get(0).budget());
        assertEquals(HydrologyLakeSedimentSnapshotV2.encode(
            base, uninterrupted.get(1).updatedPlan(),
            uninterrupted.get(1).water().state()), two.snapshot());
        var noOp = HydrologyLakeSedimentSeasonsV2.resume(
            base, two.snapshot(), List.of(),
            SeasonalLakeEvolutionV2.EvaporationMode.START_OF_STEP);
        assertEquals(two.snapshot(), noOp.snapshot());
        assertTrue(noOp.steps().isEmpty());
    }

    @Test void checkpointRestartRetainsInfilledGeometryAndStorage(
            @TempDir Path directory) throws IOException {
        var base = baseline();
        var initial = new HydrologyLakeStateV2.State("world", Map.of(A, 4.0));
        var initialSnapshot = HydrologyLakeSedimentSnapshotV2.encode(base, base, initial);
        var path = directory.resolve("lake-sediment.chk");
        var runner = HydrologyWorldRunnerV2.open(path, 0, initialSnapshot);
        var season = new HydrologyLakeSedimentSeasonsV2.Season(
            Map.of(), Map.of(A, new HydrologyLakeNetworkStepV2.Forcing(0, 0, 1)),
            Map.of(A, new HydrologyLakeSedimentNetworkV2.Sediment(4000, 2000)));
        var next = HydrologyLakeSedimentSeasonsV2.resume(
            base, runner.worldState(), List.of(season),
            SeasonalLakeEvolutionV2.EvaporationMode.START_OF_STEP);
        runner.advance(next.snapshot(), List.of(List.of(
            new HydrologyFlowGraphV2.Node(D, 0, null, true))));
        var reopened = HydrologyWorldRunnerV2.open(path, 0, "ignored");
        assertEquals(1, reopened.epoch());
        assertEquals(next.snapshot(), reopened.worldState());
        var restored = HydrologyLakeSedimentSnapshotV2.decode(
            base, reopened.worldState());
        assertEquals(3, restored.plan().lakes().get(0).reservoir().capacity(), 1e-12);
        assertEquals(3, restored.water().volumes().get(A), 1e-12);
    }

    @Test void canonicalAcrossRouteEnumerationOrder() {
        var base = baseline();
        var changed = infill(base);
        var reversed = new ArrayList<>(base.routes());
        Collections.reverse(reversed);
        var reordered = new HydrologySphericalLakeBasinsV2.Plan(
            reversed, base.lakes());
        assertEquals(
            HydrologyLakeSedimentSnapshotV2.encode(
                base, changed.updatedPlan(), changed.water().state()),
            HydrologyLakeSedimentSnapshotV2.encode(
                reordered, changed.updatedPlan(), changed.water().state()));
    }

    @Test void detectsTamperingAndStaleBaseline() {
        var base = baseline();
        var changed = infill(base);
        var snapshot = HydrologyLakeSedimentSnapshotV2.encode(
            base, changed.updatedPlan(), changed.water().state());
        assertThrows(IllegalArgumentException.class, () ->
            HydrologyLakeSedimentSnapshotV2.decode(base,
                snapshot.replace("0x1.0p1", "0x1.8p1")));
        var oldLake = base.lakes().get(0);
        var stale = new HydrologySphericalLakeBasinsV2.Plan(base.routes(), List.of(
            new HydrologySphericalLakeBasinsV2.Lake(A, oldLake.cells(),
                B, D, new LakeReservoirV2.Basin(
                    new double[]{0.5}, new double[]{1}, 5))));
        assertThrows(IllegalArgumentException.class, () ->
            HydrologyLakeSedimentSnapshotV2.decode(stale, snapshot));
    }

    @Test void refusesUnverifiedTopologyAndSedimentGeometry() {
        var base = baseline();
        var changed = infill(base);
        var altered = new ArrayList<>(changed.updatedPlan().routes());
        altered.set(1, new HydrologyFlowGraphV2.Node(B, 0, null, true));
        assertThrows(IllegalArgumentException.class, () ->
            HydrologyLakeSedimentSnapshotV2.encode(base,
                new HydrologySphericalLakeBasinsV2.Plan(
                    altered, changed.updatedPlan().lakes()), changed.water().state()));
        var lake = changed.updatedPlan().lakes().get(0);
        var wrongArea = new HydrologySphericalLakeBasinsV2.Plan(base.routes(), List.of(
            new HydrologySphericalLakeBasinsV2.Lake(A, lake.cells(), B, D,
                new LakeReservoirV2.Basin(new double[]{2}, new double[]{2}, 5))));
        assertThrows(IllegalArgumentException.class, () ->
            HydrologyLakeSedimentSnapshotV2.encode(
                base, wrongArea, changed.water().state()));
        var excavated = new HydrologySphericalLakeBasinsV2.Plan(base.routes(), List.of(
            new HydrologySphericalLakeBasinsV2.Lake(A, lake.cells(), B, D,
                new LakeReservoirV2.Basin(new double[]{-1}, new double[]{1}, 5))));
        assertThrows(IllegalArgumentException.class, () ->
            HydrologyLakeSedimentSnapshotV2.encode(
                base, excavated, changed.water().state()));
    }

    @Test void refusesStorageExceedingReducedCapacityOrUnknownLake() {
        var base = baseline();
        var changed = infill(base);
        assertThrows(IllegalArgumentException.class, () ->
            HydrologyLakeSedimentSnapshotV2.encode(base, changed.updatedPlan(),
                new HydrologyLakeStateV2.State("world", Map.of(A, 4.0))));
        assertThrows(IllegalArgumentException.class, () ->
            HydrologyLakeSedimentSnapshotV2.encode(base, changed.updatedPlan(),
                new HydrologyLakeStateV2.State("world", Map.of(B, 1.0))));
    }
}
