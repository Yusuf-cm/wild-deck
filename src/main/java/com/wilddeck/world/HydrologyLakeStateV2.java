package com.wilddeck.world;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.Base64;

/**
 * Opt-in deterministic serialization of lake storage with opaque application
 * world state. This payload fits inside the existing atomic world+inbox
 * checkpoint. Callers must explicitly use this codec; legacy opaque world
 * strings are not silently reinterpreted or migrated.
 *
 * Lake IDs are globally addressed *verified basin anchors* supplied by the
 * caller. This codec cannot determine whether two windows describe one lake.
 */
public final class HydrologyLakeStateV2 {
    private HydrologyLakeStateV2(){}
    private static final String MAGIC="WD-LAKES-1";
    private static final int MAX_LAKES=100_000;

    public record State(String world,Map<GlobalHydrologyAddressV2.Sample,Double> volumes) {
        public State {
            Objects.requireNonNull(world);
            Objects.requireNonNull(volumes);
            var ordered=new TreeMap<GlobalHydrologyAddressV2.Sample,Double>(
                HydrologyFlowGraphV2.ADDRESS_ORDER);
            for(var entry:volumes.entrySet()){
                Objects.requireNonNull(entry.getKey());
                double value=Objects.requireNonNull(entry.getValue());
                if(!Double.isFinite(value)||value<0)
                    throw new IllegalArgumentException("Invalid lake volume");
                ordered.put(entry.getKey(),value==0?0.0:value);
            }
            if(ordered.size()>MAX_LAKES)
                throw new IllegalArgumentException("Too many lakes");
            volumes=Collections.unmodifiableMap(ordered);
        }
    }

    public static String encode(State state) {
        Objects.requireNonNull(state);
        var out=new StringBuilder(MAGIC).append('\n')
            .append(Base64.getEncoder().encodeToString(
                state.world().getBytes(StandardCharsets.UTF_8))).append('\n')
            .append(state.volumes().size()).append('\n');
        for(var entry:state.volumes().entrySet()){
            var id=entry.getKey();
            out.append(id.level()).append(',').append(id.divisions()).append(',')
                .append(id.x()).append(',').append(id.y()).append(',')
                .append(Double.toHexString(entry.getValue())).append('\n');
        }
        return out.toString();
    }

    public static State decode(String encoded) {
        Objects.requireNonNull(encoded);
        var lines=encoded.split("\\n",-1);
        if(lines.length<4||!MAGIC.equals(lines[0])||!lines[lines.length-1].isEmpty())
            throw new IllegalArgumentException("Unsupported lake state payload");
        try {
            byte[] bytes=Base64.getDecoder().decode(lines[1]);
            String world=StandardCharsets.UTF_8.newDecoder()
                .decode(ByteBuffer.wrap(bytes)).toString();
            int count=Integer.parseInt(lines[2]);
            if(count<0||count>MAX_LAKES||lines.length!=count+4)
                throw new IllegalArgumentException("Invalid lake state length");
            var volumes=new TreeMap<GlobalHydrologyAddressV2.Sample,Double>(
                HydrologyFlowGraphV2.ADDRESS_ORDER);
            for(int i=0;i<count;i++){
                var fields=lines[i+3].split(",",-1);
                if(fields.length!=5)throw new IllegalArgumentException("Invalid lake record");
                var id=new GlobalHydrologyAddressV2.Sample(
                    Integer.parseInt(fields[0]),Integer.parseInt(fields[1]),
                    Long.parseLong(fields[2]),Long.parseLong(fields[3]));
                double volume=Double.parseDouble(fields[4]);
                if(!Double.isFinite(volume)||volume<0||
                   volumes.putIfAbsent(id,volume)!=null)
                    throw new IllegalArgumentException("Invalid or duplicate lake volume");
            }
            var state=new State(world,volumes);
            if(!encode(state).equals(encoded))
                throw new IllegalArgumentException("Noncanonical lake state");
            return state;
        }catch(NumberFormatException|CharacterCodingException ex){
            throw new IllegalArgumentException("Malformed lake state",ex);
        }
    }
}
