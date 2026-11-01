package com.wilddeck.world;

import java.util.*;

/**
 * Versioned, deterministic text snapshot of pending boundary discharge.
 * A caller can persist this payload when unloading a region and restore it
 * later. It is a format contract, not a transactional storage subsystem.
 * Snapshots are scoped to the world seed/epoch by the caller.
 */
public final class HydrologyFrontierCodecV2 {
    private HydrologyFrontierCodecV2() {}
    public static final String HEADER="wilddeck-hydrology-frontier-v2";

    public static String encode(Map<GlobalHydrologyAddressV2.Sample,Double> pending) {
        Objects.requireNonNull(pending);
        var sorted=new TreeMap<GlobalHydrologyAddressV2.Sample,Double>(
            HydrologyFlowGraphV2.ADDRESS_ORDER);
        sorted.putAll(pending);
        var result=new StringBuilder(HEADER).append('\n');
        for(var entry:sorted.entrySet()){
            var s=Objects.requireNonNull(entry.getKey());
            var flow=Objects.requireNonNull(entry.getValue());
            if(!Double.isFinite(flow)||flow<0)
                throw new IllegalArgumentException("Invalid pending flow");
            result.append(s.level()).append(',').append(s.divisions()).append(',')
                  .append(s.x()).append(',').append(s.y()).append(',')
                  .append(Double.toString(flow)).append('\n');
        }
        return result.toString();
    }

    public static Map<GlobalHydrologyAddressV2.Sample,Double> decode(String encoded) {
        Objects.requireNonNull(encoded);
        var lines=encoded.split("\\n",-1);
        if(lines.length<2||!HEADER.equals(lines[0])||!lines[lines.length-1].isEmpty())
            throw new IllegalArgumentException("Invalid frontier header or terminator");
        var result=new TreeMap<GlobalHydrologyAddressV2.Sample,Double>(
            HydrologyFlowGraphV2.ADDRESS_ORDER);
        for(int i=1;i<lines.length-1;i++){
            var fields=lines[i].split(",",-1);
            if(fields.length!=5)throw new IllegalArgumentException("Invalid frontier row");
            try {
                var sample=new GlobalHydrologyAddressV2.Sample(
                    Integer.parseInt(fields[0]),Integer.parseInt(fields[1]),
                    Long.parseLong(fields[2]),Long.parseLong(fields[3]));
                double flow=Double.parseDouble(fields[4]);
                if(!Double.isFinite(flow)||flow<0||result.putIfAbsent(sample,flow)!=null)
                    throw new IllegalArgumentException("Invalid or duplicate frontier");
            } catch(NumberFormatException ex) {
                throw new IllegalArgumentException("Invalid frontier number",ex);
            }
        }
        return Collections.unmodifiableMap(result);
    }
}
