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
        if (!waterInbox.delivered().containsAll(waterInbox.receipts().keySet()))
            throw new IllegalStateException("Pending water needs coupled water receipt settlement");

        var previousLandscape = HydrologySeasonalLandscapeStateV2.decode(stored.world());
        var owners = new HashSet<HydrologyGlobalCatchmentsV2.Coordinate>();
        var loaded = new HashSet<GlobalHydrologyAddressV2.Sample>();
        for (var address : addresses) {
            Objects.requireNonNull(address);
            if (!HydrologySphericalTopologyV2.isCanonical(address) ||
                !owners.add(HydrologyGlobalCatchmentsV2.coordinate(address)))
                throw new IllegalArgumentException("Duplicate or noncanonical sediment window owner");
            loaded.add(address);
        }
        var claimed = HydrologySedimentReceiptInboxV2.claimAvailable(
            sediment, nextEpoch, addresses);
        var oldHeights = HydrologyTerrainOverlayV2.elevations(
            seed, previousLandscape.terrain());
        for (var receipt : claimed.snapshot().delivered()) {
            if (sediment.delivered().contains(receipt)) continue;
            if (loaded.contains(receipt.from()) ||
                !loaded.contains(receipt.to()) ||
                !(oldHeights.at(receipt.from()) > oldHeights.at(receipt.to())))
                throw new IllegalArgumentException("Imported sediment lacks external downhill landing");
        }
        var prepared = HydrologySeasonalLandscapeStateV2.prepareWithIncoming(
            previousLandscape, addresses, width, height, seed, weather,
            radiusMeters, sedimentDensity, erosionStrength, maximumCut,
            transportCapacity, verifiedOutlets, authenticatedWater,
            claimed.imported());
        var newHeights = HydrologyTerrainOverlayV2.elevations(
            seed, prepared.state().terrain());
        for (var receipt : claimed.snapshot().delivered()) {
            if (sediment.delivered().contains(receipt)) continue;
            if (!(newHeights.at(receipt.from()) > newHeights.at(receipt.to())))
                throw new IllegalArgumentException("Erosion reversed imported sediment landing");
        }
        String world = HydrologySedimentCheckpointV2.encode(
            HydrologySeasonalLandscapeStateV2.encode(prepared.state()),
            claimed.snapshot());
        var next = new HydrologyCheckpointV2.Checkpoint(
            nextEpoch, world, previous.inboxState());
        return new Candidate(next, prepared, claimed);
    }
}
