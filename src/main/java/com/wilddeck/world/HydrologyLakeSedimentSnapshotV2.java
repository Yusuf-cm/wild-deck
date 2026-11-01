package com.wilddeck.world;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/**
 * Canonical, checksummed snapshot of sediment-altered lake beds AND water
 * storage, bound to an immutable baseline lake geometry and river topology.
 * The baseline plan must be supplied independently on restart. Only bed
 * infill is permitted; changing outlets, cell areas or lake identities
 * requires a separately verified drainage transition.
 *
 * This is a payload codec, not an automatic checkpoint commit or terrain
 * reconstruction service. Callers must store the entire payload atomically.
 */
public final class HydrologyLakeSedimentSnapshotV2 {
    private HydrologyLakeSedimentSnapshotV2() {}
    private static final String MAGIC = "WD-LAKE-SEDIMENT-SNAPSHOT-1";
    private static final int MAX_BYTES = 16 * 1024 * 1024;

    public record Restored(HydrologySphericalLakeBasinsV2.Plan plan,
                           HydrologyLakeStateV2.State water) {
        public Restored {
            Objects.requireNonNull(plan);
            Objects.requireNonNull(water);
        }
    }

    public static String encode(HydrologySphericalLakeBasinsV2.Plan baseline,
                                HydrologySphericalLakeBasinsV2.Plan updated,
                                HydrologyLakeStateV2.State water) {
        Objects.requireNonNull(baseline);
        Objects.requireNonNull(updated);
        Objects.requireNonNull(water);
        String route = routeId(baseline);
        if (!route.equals(routeId(updated)))
            throw new IllegalArgumentException("Sediment snapshot changes river topology");
        var originals = lakes(baseline);
        var changes = lakes(updated);
        if (!originals.keySet().equals(changes.keySet()) ||
            !originals.keySet().containsAll(water.volumes().keySet()))
            throw new IllegalArgumentException("Sediment snapshot changes lake identities");
        var out = new StringBuilder(MAGIC).append('\n')
            .append(HydrologyLakePlanSnapshotIdV2.of(baseline)).append('\n')
            .append(route).append('\n')
            .append(Base64.getEncoder().encodeToString(
                HydrologyLakeStateV2.encode(water).getBytes(StandardCharsets.UTF_8)))
            .append('\n').append(originals.size()).append('\n');
        for (var entry : originals.entrySet()) {
            var old = entry.getValue();
            var next = changes.get(entry.getKey());
            if (!old.cells().equals(next.cells()) ||
                !old.spillTarget().equals(next.spillTarget()) ||
                !old.confirmedOutlet().equals(next.confirmedOutlet()) ||
                Double.compare(old.reservoir().spillElevation(),
                               next.reservoir().spillElevation()) != 0)
                throw new IllegalArgumentException("Unverified lake geometry change");
            double[] before = old.reservoir().bedElevation();
            double[] after = next.reservoir().bedElevation();
            double[] area = old.reservoir().cellArea();
            if (before.length != after.length ||
                !Arrays.equals(area, next.reservoir().cellArea()))
                throw new IllegalArgumentException("Changed lake sampling areas");
            double volume = water.volumes().getOrDefault(entry.getKey(), 0.0);
            if (volume > next.reservoir().capacity())
                throw new IllegalArgumentException("Stored water exceeds silted capacity");
            out.append(address(entry.getKey())).append('|');
            for (int i = 0; i < after.length; i++) {
                if (!Double.isFinite(after[i]) || after[i] < before[i] ||
                    after[i] > old.reservoir().spillElevation())
                    throw new IllegalArgumentException("Nonconservative lake bed change");
                if (i != 0) out.append(',');
                out.append(Double.toHexString(after[i]));
            }
            out.append('\n');
        }
        String body = out.toString();
        String encoded = body + sha256(body) + "\n";
        if (encoded.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES)
            throw new IllegalArgumentException("Lake sediment snapshot too large");
        return encoded;
    }

    public static Restored decode(HydrologySphericalLakeBasinsV2.Plan baseline,
                                  String encoded) {
        Objects.requireNonNull(baseline);
        Objects.requireNonNull(encoded);
        if (encoded.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES)
            throw new IllegalArgumentException("Lake sediment snapshot too large");
        var lines = encoded.split("\\n", -1);
        var originals = lakes(baseline);
        if (lines.length != originals.size() + 7 ||
            !MAGIC.equals(lines[0]) || !lines[lines.length - 1].isEmpty() ||
            !HydrologyLakePlanSnapshotIdV2.of(baseline).equals(lines[1]) ||
            !routeId(baseline).equals(lines[2]))
            throw new IllegalArgumentException("Unrecognized or stale sediment snapshot");
        String body = encoded.substring(0, encoded.lastIndexOf('\n',
            encoded.length() - 2) + 1);
        if (!sha256(body).equals(lines[lines.length - 2]))
            throw new IllegalArgumentException("Corrupt sediment snapshot");
        try {
            if (Integer.parseInt(lines[4]) != originals.size())
                throw new IllegalArgumentException("Wrong lake count");
            String statePayload = new String(
                Base64.getDecoder().decode(lines[3]), StandardCharsets.UTF_8);
            var water = HydrologyLakeStateV2.decode(statePayload);
            var updated = new ArrayList<HydrologySphericalLakeBasinsV2.Lake>();
            int line = 5;
            for (var entry : originals.entrySet()) {
                var old = entry.getValue();
                var fields = lines[line++].split("\\|", -1);
                if (fields.length != 2 || !address(entry.getKey()).equals(fields[0]))
                    throw new IllegalArgumentException("Lake anchor mismatch");
                var values = fields[1].split(",", -1);
                if (values.length != old.cells().size())
                    throw new IllegalArgumentException("Lake bed count mismatch");
                var beds = new double[values.length];
                for (int i = 0; i < beds.length; i++)
                    beds[i] = Double.parseDouble(values[i]);
                updated.add(new HydrologySphericalLakeBasinsV2.Lake(
                    old.anchor(), old.cells(), old.spillTarget(),
                    old.confirmedOutlet(), new LakeReservoirV2.Basin(
                        beds, old.reservoir().cellArea(),
                        old.reservoir().spillElevation())));
            }
            var restored = new Restored(
                new HydrologySphericalLakeBasinsV2.Plan(baseline.routes(), updated),
                water);
            if (!encode(baseline, restored.plan(), restored.water()).equals(encoded))
                throw new IllegalArgumentException("Noncanonical sediment snapshot");
            return restored;
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Malformed sediment snapshot", ex);
        }
    }

    private static TreeMap<GlobalHydrologyAddressV2.Sample,
                           HydrologySphericalLakeBasinsV2.Lake> lakes(
            HydrologySphericalLakeBasinsV2.Plan plan) {
        var result = new TreeMap<GlobalHydrologyAddressV2.Sample,
            HydrologySphericalLakeBasinsV2.Lake>(HydrologyFlowGraphV2.ADDRESS_ORDER);
        for (var lake : plan.lakes())
            if (result.putIfAbsent(lake.anchor(), lake) != null)
                throw new IllegalArgumentException("Duplicate lake anchor");
        return result;
    }

    private static String routeId(HydrologySphericalLakeBasinsV2.Plan plan) {
        var routes = new TreeMap<GlobalHydrologyAddressV2.Sample,
            HydrologyFlowGraphV2.Node>(HydrologyFlowGraphV2.ADDRESS_ORDER);
        for (var node : plan.routes())
            if (routes.putIfAbsent(node.address(), node) != null)
                throw new IllegalArgumentException("Duplicate river source");
        if (routes.isEmpty()) throw new IllegalArgumentException("Empty river topology");
        var text = new StringBuilder("WD-LAKE-ROUTES-1\n");
        for (var node : routes.values())
            text.append(address(node.address())).append('|')
                .append(node.downstream() == null ? "-" : address(node.downstream()))
                .append('|').append(node.terminal()).append('\n');
        return sha256(text.toString());
    }

    private static String address(GlobalHydrologyAddressV2.Sample sample) {
        return sample.level() + "," + sample.divisions() + "," +
            sample.x() + "," + sample.y();
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }
}
