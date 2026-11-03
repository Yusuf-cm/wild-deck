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
        var waterLandings = new HashSet<Integer>();
        var newlyDeliveredWater = new ArrayList<HydrologyTransferLedgerV2.Key>();
        if (consumeWaterReceipts) {
            var indices = new HashMap<HydrologyGlobalCatchmentsV2.Coordinate,Integer>();
            for (int i = 0; i < addresses.size(); i++)
                indices.put(HydrologyGlobalCatchmentsV2.coordinate(addresses.get(i)), i);
            var loaded = Set.copyOf(addresses);
            for (var key : waterInbox.receipts().keySet()) {
                if (waterInbox.delivered().contains(key) || key.epoch() > nextEpoch)
                    continue;
                if (!HydrologySphericalTopologyV2.isCanonical(key.from()) ||
                    !HydrologySphericalTopologyV2.isCanonical(key.to()))
                    throw new IllegalArgumentException("Noncanonical pending water transfer");
                var landing = HydrologyGlobalCatchmentsV2.coordinate(key.to());
                if (owners.contains(landing) && !loaded.contains(key.to()))
                    throw new IllegalArgumentException("Cross-resolution water receipt needs explicit remapping");
                if (owners.contains(landing) &&
                    owners.contains(HydrologyGlobalCatchmentsV2.coordinate(key.from())))
                    throw new IllegalArgumentException("Water source already owned by receiver");
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
                newlyDeliveredWater.add(key);
                waterLandings.add(landing);
            }
            nextInbox = HydrologyInboxCodecV2.encode(consumed);
        }
        var claimed = HydrologySedimentReceiptInboxV2.claimAvailable(
            sediment, nextEpoch, addresses);
        for (var receipt : claimed.snapshot().delivered()) {
            if (sediment.delivered().contains(receipt)) continue;
            if (owners.contains(HydrologyGlobalCatchmentsV2.coordinate(receipt.from())) ||
                !owners.contains(HydrologyGlobalCatchmentsV2.coordinate(receipt.to())) ||
                !(oldHeights.at(receipt.from()) > oldHeights.at(receipt.to())))
                throw new IllegalArgumentException("Imported sediment lacks external downhill landing");
        }
        var prepared = HydrologySeasonalLandscapeStateV2.prepareWithIncoming(
            previousLandscape, addresses, width, height, seed, weather,
            radiusMeters, sedimentDensity, erosionStrength, maximumCut,
            transportCapacity, verifiedOutlets, incomingWater,
            claimed.imported());
        var newHeights = HydrologyTerrainOverlayV2.elevations(
            seed, prepared.state().terrain());
        for (var receipt : claimed.snapshot().delivered()) {
            if (sediment.delivered().contains(receipt)) continue;
            if (!(newHeights.at(receipt.from()) > newHeights.at(receipt.to())))
                throw new IllegalArgumentException("Erosion reversed imported sediment landing");
        }
        for (var key : newlyDeliveredWater)
            if (!(newHeights.at(key.from()) > newHeights.at(key.to())))
                throw new IllegalArgumentException("Erosion reversed imported water landing");
        if (!waterLandings.isEmpty()) {
            double[] oldBed = new double[addresses.size()];
            for (int i = 0; i < oldBed.length; i++)
                oldBed[i] = oldHeights.at(addresses.get(i));
            var oldRoute = LakeSpillV2.fillToVerifiedOutlets(
                width, height, oldBed, verifiedOutlets).spillTo();
            var newRoute = prepared.preview().erosion().terrain()
                .landscape().drainage().spillTo();
            if (!HydrologyRouteMigrationV2.verify(
                oldRoute, newRoute, verifiedOutlets, waterLandings)
                .changedVertices().isEmpty())
                throw new IllegalStateException(
                    "Imported water path changed during erosion; migration required");
        }
        String world = HydrologySedimentCheckpointV2.encode(
            HydrologySeasonalLandscapeStateV2.encode(prepared.state()),
            claimed.snapshot());
        var next = new HydrologyCheckpointV2.Checkpoint(
            nextEpoch, world, nextInbox);
        return new Candidate(next, prepared, claimed);
    }
}
