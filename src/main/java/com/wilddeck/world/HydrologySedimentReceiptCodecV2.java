package com.wilddeck.world;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/**
 * Canonical, checksummed, bounded persistence for the sediment receipt ledger.
 * Retains delivered identities indefinitely: this is NOT a replay-watermark
 * or permission to compact old sender records.
 */
public final class HydrologySedimentReceiptCodecV2 {
    private HydrologySedimentReceiptCodecV2() {}
    private static final String HEADER = "WD-SEDIMENT-INBOX-1";
    private static final int MAX_BYTES = 8 * 1024 * 1024;

    public static String encode(HydrologySedimentReceiptInboxV2.Snapshot snapshot) {
        Objects.requireNonNull(snapshot);
        var out = new StringBuilder(HEADER).append('\n')
            .append(snapshot.lastClaimedEpoch()).append('\n');
        for (var entry : snapshot.receipts().entrySet()) {
            var key = entry.getKey();
            var from = key.from();
            var to = key.to();
            out.append("R|").append(key.sourceEpoch()).append('|')
                .append(key.arrivalEpoch()).append('|')
                .append(from.level()).append('|').append(from.divisions()).append('|')
                .append(from.x()).append('|').append(from.y()).append('|')
                .append(to.x()).append('|').append(to.y()).append('|')
                .append(Double.toHexString(entry.getValue())).append('|')
                .append(snapshot.delivered().contains(key) ? '1' : '0')
                .append('\n');
        }
        String body = out.toString();
        String encoded = body + digest(body) + "\n";
        if (encoded.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES)
            throw new IllegalArgumentException("Sediment receipt ledger exceeds size limit");
        return encoded;
    }

    public static HydrologySedimentReceiptInboxV2.Snapshot decode(String encoded) {
        Objects.requireNonNull(encoded);
        if (encoded.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES)
            throw new IllegalArgumentException("Sediment receipt ledger exceeds size limit");
        String[] lines = encoded.split("\\n", -1);
        if (lines.length < 4 || !HEADER.equals(lines[0]) ||
            !lines[lines.length - 1].isEmpty())
            throw new IllegalArgumentException("Invalid sediment receipt ledger header");
        String body = encoded.substring(0, encoded.lastIndexOf('\n',
            encoded.length() - 2) + 1);
        if (!digest(body).equals(lines[lines.length - 2]))
            throw new IllegalArgumentException("Sediment receipt ledger checksum mismatch");
        try {
            long last = Long.parseLong(lines[1]);
            var receipts = new HashMap<HydrologySedimentReceiptInboxV2.Receipt,Double>();
            var delivered = new HashSet<HydrologySedimentReceiptInboxV2.Receipt>();
            for (int i = 2; i < lines.length - 2; i++) {
                String[] f = lines[i].split("\\|", -1);
                if (f.length != 11 || !f[0].equals("R") ||
                    !(f[10].equals("0") || f[10].equals("1")))
                    throw new IllegalArgumentException("Invalid sediment receipt ledger row");
                int level = Integer.parseInt(f[3]);
                int divisions = Integer.parseInt(f[4]);
                var from = new GlobalHydrologyAddressV2.Sample(level,divisions,
                    Long.parseLong(f[5]),Long.parseLong(f[6]));
                var to = new GlobalHydrologyAddressV2.Sample(level,divisions,
                    Long.parseLong(f[7]),Long.parseLong(f[8]));
                var key = new HydrologySedimentReceiptInboxV2.Receipt(
                    Long.parseLong(f[1]),Long.parseLong(f[2]),from,to);
                double mass = Double.parseDouble(f[9]);
                if (receipts.putIfAbsent(key,mass) != null)
                    throw new IllegalArgumentException("Duplicate sediment receipt ledger row");
                if (f[10].equals("1")) delivered.add(key);
            }
            var snapshot = new HydrologySedimentReceiptInboxV2.Snapshot(
                receipts,delivered,last);
            if (!encode(snapshot).equals(encoded))
                throw new IllegalArgumentException("Noncanonical sediment receipt ledger");
            return snapshot;
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("Invalid sediment receipt ledger number",ex);
        }
    }

    private static String digest(String body) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(body.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable",ex);
        }
    }
}
