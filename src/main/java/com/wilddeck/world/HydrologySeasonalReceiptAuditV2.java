package com.wilddeck.world;

import java.math.BigDecimal;
import java.util.*;

/**
 * Independent, read-only audit of a coupled seasonal receipt candidate.
 * Verifies that water and sediment receipt identities and amounts survive
 * unchanged, and that only due, uniquely owned boundary receipts are marked
 * consumed. It also reconciles imported quantities against the erosion
 * preview. It does NOT authenticate sender inventories, settle outlet exports,
 * approve changed global drainage, or authorize a durable commit.
 */
public final class HydrologySeasonalReceiptAuditV2 {
    private HydrologySeasonalReceiptAuditV2() {}

    private record Origin(long epoch, HydrologyGlobalCatchmentsV2.Coordinate location) {}

    public record Audit(long epoch, BigDecimal waterCubicMeters,
                        BigDecimal sedimentKilograms,
                        int waterReceipts, int sedimentReceipts) {
        public Audit {
            Objects.requireNonNull(waterCubicMeters);
            Objects.requireNonNull(sedimentKilograms);
            if (epoch < 0 || waterCubicMeters.signum() < 0 ||
                sedimentKilograms.signum() < 0 ||
                waterReceipts < 0 || sedimentReceipts < 0)
                throw new IllegalArgumentException("Invalid receipt audit");
        }
    }

    public static Audit verify(HydrologyCheckpointV2.Checkpoint previous,
            HydrologySeasonalSedimentCandidateV2.Candidate candidate,
            Collection<GlobalHydrologyAddressV2.Sample> owned) {
        Objects.requireNonNull(previous);
        Objects.requireNonNull(candidate);
        Objects.requireNonNull(owned);
        var next = candidate.next();
        if (next.epoch() != Math.addExact(previous.epoch(), 1))
            throw new IllegalArgumentException("Invalid candidate epoch");
        var physical = new HashMap<HydrologyGlobalCatchmentsV2.Coordinate,
            GlobalHydrologyAddressV2.Sample>();
        for (var address : owned) {
            Objects.requireNonNull(address);
            if (!HydrologySphericalTopologyV2.isCanonical(address) ||
                physical.putIfAbsent(HydrologyGlobalCatchmentsV2.coordinate(address), address) != null)
                throw new IllegalArgumentException("Duplicate or noncanonical receipt owner");
        }
        var oldWater = HydrologyInboxCodecV2.decode(previous.inboxState());
        for (long epoch : oldWater.consumedEpochs())
            if (epoch > previous.epoch())
                throw new IllegalArgumentException("Previous checkpoint consumed a future water epoch");
        for (var key : oldWater.delivered())
            if (key.epoch() > previous.epoch())
                throw new IllegalArgumentException("Previous checkpoint delivered future water");
        var newWater = HydrologyInboxCodecV2.decode(next.inboxState());
        var expectedEpochs = new HashSet<>(oldWater.consumedEpochs());
        expectedEpochs.add(next.epoch());
        if (!oldWater.receipts().equals(newWater.receipts()) ||
            !newWater.consumedEpochs().equals(expectedEpochs) ||
            !newWater.delivered().containsAll(oldWater.delivered()))
            throw new IllegalArgumentException("Water receipt history changed or was dropped");
        var oldSediment = HydrologySedimentCheckpointV2.decode(previous.worldState()).sediment();
        var newSediment = HydrologySedimentCheckpointV2.decode(next.worldState()).sediment();
        if (!oldSediment.receipts().equals(newSediment.receipts()) ||
            !newSediment.delivered().containsAll(oldSediment.delivered()) ||
            newSediment.lastClaimedEpoch() != next.epoch())
            throw new IllegalArgumentException("Sediment receipt history changed or was dropped");

        BigDecimal water = BigDecimal.ZERO;
        int waterCount = 0;
        var waterOrigins = new HashSet<Origin>();
        for (var key : newWater.delivered()) {
            if (oldWater.delivered().contains(key)) continue;
            requireExternalLanding(key.from(), key.to(), key.epoch(),
                key.epoch(), next.epoch(), physical, waterOrigins);
            water = water.add(BigDecimal.valueOf(oldWater.receipts().get(key)));
            waterCount++;
        }
        BigDecimal sediment = BigDecimal.ZERO;
        int sedimentCount = 0;
        var sedimentOrigins = new HashSet<Origin>();
        for (var receipt : newSediment.delivered()) {
            if (oldSediment.delivered().contains(receipt)) continue;
            requireExternalLanding(receipt.from(), receipt.to(),
                receipt.sourceEpoch(), receipt.arrivalEpoch(), next.epoch(),
                physical, sedimentOrigins);
            sediment = sediment.add(BigDecimal.valueOf(oldSediment.receipts().get(receipt)));
            sedimentCount++;
        }
        var preview = candidate.landscape().preview();
        double importedWater = water.doubleValue();
        double importedSediment = sediment.doubleValue();
        if (!Double.isFinite(importedWater) || !Double.isFinite(importedSediment))
            throw new IllegalArgumentException("Receipt budget overflow");
        double expectedTerminal = HydrologyWaterVolumeAdditionV2.add(
            preview.erosion().runoffVolume(), importedWater);
        requireClose(expectedTerminal, preview.erosion().terminalVolume(),
            "Water receipts differ from terminal flow");
        requireClose(importedSediment, candidate.claimed().importedMass(),
            "Sediment receipt budget differs from claim");
        requireClose(importedSediment,
            preview.erosion().terrain().landscape().importedMass(),
            "Sediment receipts differ from erosion import");
        return new Audit(next.epoch(), water, sediment, waterCount, sedimentCount);
    }

    private static void requireExternalLanding(
            GlobalHydrologyAddressV2.Sample from,
            GlobalHydrologyAddressV2.Sample to,
            long originEpoch, long arrival, long nextEpoch,
            Map<HydrologyGlobalCatchmentsV2.Coordinate,
                GlobalHydrologyAddressV2.Sample> physical,
            Set<Origin> origins) {
        if (arrival > nextEpoch || !HydrologySphericalTopologyV2.isCanonical(from) ||
            !HydrologySphericalTopologyV2.isCanonical(to))
            throw new IllegalArgumentException("Invalid newly delivered receipt");
        var destination = physical.get(HydrologyGlobalCatchmentsV2.coordinate(to));
        if (!to.equals(destination) ||
            physical.containsKey(HydrologyGlobalCatchmentsV2.coordinate(from)) ||
            !origins.add(new Origin(originEpoch, HydrologyGlobalCatchmentsV2.coordinate(from))))
            throw new IllegalArgumentException("Invalid or duplicated physical receipt ownership");
    }

    private static void requireClose(double expected, double actual, String reason) {
        if (!Double.isFinite(expected) || !Double.isFinite(actual) ||
            Math.abs(expected - actual) > Math.max(1e-8, Math.abs(expected) * 1e-10))
            throw new IllegalArgumentException(reason);
    }
}
