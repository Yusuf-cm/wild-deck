package com.wilddeck.world;

import java.util.*;

/**
 * Noncommitting bridge between the durable sediment inbox and seasonal
 * snow/soil, river erosion and terrain overlay. A candidate includes the
 * exact receipt-consumption snapshot in the same world payload as both
 * evolved physical stores. The caller must still audit/activate changed
 * global drainage and route outlet water/sediment before committing it.
 *
 * This class does NOT authenticate sender journals or perform a distributed
 * transaction. Never persist its next checkpoint without route acceptance.
 */
public final class HydrologySeasonalSedimentCandidateV2 {
    private HydrologySeasonalSedimentCandidateV2() {}

    private record WaterOrigin(long epoch,
            HydrologyGlobalCatchmentsV2.Coordinate source) {}

    public record Candidate(HydrologyCheckpointV2.Checkpoint next,
                            HydrologySeasonalLandscapeStateV2.Prepared landscape,
                            HydrologySedimentReceiptInboxV2.Claimed claimed) {
        public Candidate {
            Objects.requireNonNull(next);
            Objects.requireNonNull(landscape);
            Objects.requireNonNull(claimed);
            var restored = HydrologySedimentCheckpointV2.decode(next.worldState());
            if (!restored.sediment().equals(claimed.snapshot()) ||
                !HydrologySeasonalLandscapeStateV2.decode(restored.world())
                    .equals(landscape.state()))
                throw new IllegalArgumentException("Candidate payload differs from staged state");
        }
    }

    /**
     * Prepare one epoch using already authenticated incoming water and the
     * receipt identities held in the sediment checkpoint. Future and unloaded
     * sediment remains pending. No receipt is consumed on disk by this call.
     */
    public static Candidate stage(
            HydrologyCheckpointV2.Checkpoint previous,
            List<GlobalHydrologyAddressV2.Sample> addresses,
            int width, int height, HydrologyGlobalSlopeRouterV2.Elevations seed,
            Map<GlobalHydrologyAddressV2.Sample,HydrologySnowSoilStateV2.Forcing> weather,
            double radiusMeters, double sedimentDensity,
            double erosionStrength, double maximumCut,
            double[] transportCapacity, Collection<Integer> verifiedOutlets,
            Map<GlobalHydrologyAddressV2.Sample,Double> authenticatedWater) {
        return stageInternal(previous, addresses, width, height, seed, weather,
            radiusMeters, sedimentDensity, erosionStrength, maximumCut,
            transportCapacity, verifiedOutlets, authenticatedWater, false);
    }

    /**
     * Stage BOTH previously persisted water receipts and sediment receipts
     * against the same physical window and in one next-epoch checkpoint.
     * This is NOT a sender-authenticated commit. The returned candidate must
     * still pass external outlet/export and global-topology acceptance.
     */
    public static Candidate stageWithWaterReceipts(
            HydrologyCheckpointV2.Checkpoint previous,
            List<GlobalHydrologyAddressV2.Sample> addresses,
            int width, int height, HydrologyGlobalSlopeRouterV2.Elevations seed,
            Map<GlobalHydrologyAddressV2.Sample,HydrologySnowSoilStateV2.Forcing> weather,
            double radiusMeters, double sedimentDensity,
            double erosionStrength, double maximumCut,
            double[] transportCapacity, Collection<Integer> verifiedOutlets) {
        return stageInternal(previous, addresses, width, height, seed, weather,
            radiusMeters, sedimentDensity, erosionStrength, maximumCut,
            transportCapacity, verifiedOutlets, Map.of(), true);
    }

    private static Candidate stageInternal(
            HydrologyCheckpointV2.Checkpoint previous,
            List<GlobalHydrologyAddressV2.Sample> addresses,
            int width, int height, HydrologyGlobalSlopeRouterV2.Elevations seed,
            Map<GlobalHydrologyAddressV2.Sample,HydrologySnowSoilStateV2.Forcing> weather,
            double radiusMeters, double sedimentDensity,
            double erosionStrength, double maximumCut,
            double[] transportCapacity, Collection<Integer> verifiedOutlets,
            Map<GlobalHydrologyAddressV2.Sample,Double> authenticatedWater,
            boolean consumeWaterReceipts) {
        Objects.requireNonNull(previous);
        Objects.requireNonNull(addresses);
        Objects.requireNonNull(seed);
        Objects.requireNonNull(authenticatedWater);
        long nextEpoch = Math.addExact(previous.epoch(), 1);
        var stored = HydrologySedimentCheckpointV2.decode(previous.worldState());
        var sediment = stored.sediment();
        if (sediment.lastClaimedEpoch() != previous.epoch() &&
            !(previous.epoch() == 0 && sediment.lastClaimedEpoch() == -1))
            throw new IllegalArgumentException("Sediment receipt epoch disagrees with checkpoint");
        var waterInbox = HydrologyInboxCodecV2.decode(previous.inboxState());
        if (!consumeWaterReceipts &&
            !waterInbox.delivered().containsAll(waterInbox.receipts().keySet()))
            throw new IllegalStateException("Pending water needs coupled water receipt settlement");

        var previousLandscape = HydrologySeasonalLandscapeStateV2.decode(stored.world());
        var owners = new HashSet<HydrologyGlobalCatchmentsV2.Coordinate>();
        for (var address : addresses) {
            Objects.requireNonNull(address);
            if (!HydrologySphericalTopologyV2.isCanonical(address) ||
                !owners.add(HydrologyGlobalCatchmentsV2.coordinate(address)))
                throw new IllegalArgumentException("Duplicate or noncanonical sediment window owner");
        }
        var oldHeights = HydrologyTerrainOverlayV2.elevations(
            seed, previousLandscape.terrain());
        Map<GlobalHydrologyAddressV2.Sample,Double> incomingWater = authenticatedWater;
        String nextInbox = previous.inboxState();
        // Any outstanding boundary receipt may land after this terrain change,
        // including a future arrival that is not claimed during this epoch.
        // Keep its in-window route stable until an explicit migration exists.
        var protectedLandings = new HashSet<Integer>();
         if (consumeWaterReceipts) {
            var indices = new HashMap<HydrologyGlobalCatchmentsV2.Coordinate,Integer>();
            for (int i = 0; i < addresses.size(); i++)
                indices.put(HydrologyGlobalCatchmentsV2.coordinate(addresses.get(i)), i);
            var loaded = Set.copyOf(addresses);
            var uniqueSources = new HashMap<WaterOrigin,HydrologyTransferLedgerV2.Key>();
            for (var key : waterInbox.receipts().keySet()) {
                if (!HydrologySphericalTopologyV2.isCanonical(key.from()) ||
                    !HydrologySphericalTopologyV2.isCanonical(key.to()))
                    throw new IllegalArgumentException("Noncanonical water transfer");
                var source = new WaterOrigin(key.epoch(),
                    HydrologyGlobalCatchmentsV2.coordinate(key.from()));
                var prior = uniqueSources.putIfAbsent(source, key);
                if (prior != null && !prior.equals(key))
                    throw new IllegalArgumentException(
                        "Multiple water transfers from one physical source and epoch");
                if (waterInbox.delivered().contains(key)) continue;
                var landing = HydrologyGlobalCatchmentsV2.coordinate(key.to());
                if (!owners.contains(landing)) continue; // unloaded destination
                if (!loaded.contains(key.to()))
                    throw new IllegalArgumentException("Cross-resolution water receipt needs explicit remapping");
                if (owners.contains(HydrologyGlobalCatchmentsV2.coordinate(key.from())))
                    throw new IllegalArgumentException("Water source already owned by receiver");
                if (!(oldHeights.at(key.from()) > oldHeights.at(key.to())))
                    throw new IllegalArgumentException("Pending water lacks external downhill landing");
                protectedLandings.add(indices.get(landing));
            }
            var active = HydrologyEpochInboxV2.restore(waterInbox);
            incomingWater = active.claimAvailable(nextEpoch, loaded);
            var consumed = active.snapshot();
            for (var key : consumed.delivered()) {
                if (waterInbox.delivered().contains(key)) continue;
                var landing = indices.get(HydrologyGlobalCatchmentsV2.coordinate(key.to()));
                if (landing == null ||
                    !(oldHeights.at(key.from()) > oldHeights.at(key.to())))
                    throw new IllegalArgumentException("Incoming water lacks external downhill landing");
            }
            nextInbox = HydrologyInboxCodecV2.encode(consumed);
        }
        var claimed = HydrologySedimentReceiptInboxV2.claimAvailable(
            sediment, nextEpoch, addresses);
        var indices = new HashMap<HydrologyGlobalCatchmentsV2.Coordinate,Integer>();
        for (int i = 0; i < addresses.size(); i++)
            indices.put(HydrologyGlobalCatchmentsV2.coordinate(addresses.get(i)), i);
        for (var receipt : sediment.receipts().keySet()) {
            if (sediment.delivered().contains(receipt)) continue;
            var landing = HydrologyGlobalCatchmentsV2.coordinate(receipt.to());
            if (!owners.contains(landing)) continue; // unloaded destination
            if (!addresses.contains(receipt.to()))
                throw new IllegalArgumentException("Cross-resolution sediment receipt needs explicit remapping");
            if (owners.contains(HydrologyGlobalCatchmentsV2.coordinate(receipt.from())) ||
                !(oldHeights.at(receipt.from()) > oldHeights.at(receipt.to())))
                throw new IllegalArgumentException("Pending sediment lacks external downhill landing");
            protectedLandings.add(indices.get(landing));
        }
        var prepared = HydrologySeasonalLandscapeStateV2.prepareWithIncoming(
            previousLandscape, addresses, width, height, seed, weather,
            radiusMeters, sedimentDensity, erosionStrength, maximumCut,
            transportCapacity, verifiedOutlets, incomingWater,
            claimed.imported());
        var newHeights = HydrologyTerrainOverlayV2.elevations(
            seed, prepared.state().terrain());
        for (var receipt : sediment.receipts().keySet()) {
            if (sediment.delivered().contains(receipt) ||
                !owners.contains(HydrologyGlobalCatchmentsV2.coordinate(receipt.to()))) continue;
            if (!(newHeights.at(receipt.from()) > newHeights.at(receipt.to())))
                throw new IllegalArgumentException("Erosion reversed pending sediment landing");
        }
        for (var key : waterInbox.receipts().keySet()) {
            if (waterInbox.delivered().contains(key) ||
                !owners.contains(HydrologyGlobalCatchmentsV2.coordinate(key.to()))) continue;
            if (!(newHeights.at(key.from()) > newHeights.at(key.to())))
                throw new IllegalArgumentException("Erosion reversed pending water landing");
        }
        if (!protectedLandings.isEmpty()) {
            double[] oldBed = new double[addresses.size()];
            for (int i = 0; i < oldBed.length; i++)
                oldBed[i] = oldHeights.at(addresses.get(i));
            var oldRoute = LakeSpillV2.fillToVerifiedOutlets(
                width, height, oldBed, verifiedOutlets).spillTo();
            var newRoute = prepared.preview().erosion().terrain()
                .landscape().drainage().spillTo();
            if (!HydrologyRouteMigrationV2.verify(
                oldRoute, newRoute, verifiedOutlets, protectedLandings)
                .changedVertices().isEmpty())
                throw new IllegalStateException(
                    "Pending boundary water/sediment path changed during erosion; migration required");
        }
        String world = HydrologySedimentCheckpointV2.encode(
            HydrologySeasonalLandscapeStateV2.encode(prepared.state()),
            claimed.snapshot());
        var next = new HydrologyCheckpointV2.Checkpoint(
            nextEpoch, world, nextInbox);
        var candidate = new Candidate(next, prepared, claimed);
        if (consumeWaterReceipts)
            HydrologySeasonalReceiptAuditV2.verify(previous, candidate, addresses);
        return candidate;
    }
}
