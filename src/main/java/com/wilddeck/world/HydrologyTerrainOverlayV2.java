package com.wilddeck.world;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Opt-in persistent terrain overlay for finite erosion/sediment windows.
 * Stores absolute modified elevations at global sample addresses while the
 * immutable seeded planet remains untouched. Encoding can be supplied as the
 * opaque world state in an atomic hydrology checkpoint.
 *
 * A caller must retain/reload the state and provide a verified outlet and
 * geometrically valid window. This is not an automatic planet-wide terrain
 * mutation system or a multi-writer persistence protocol.
 */
public final class HydrologyTerrainOverlayV2 {
    private HydrologyTerrainOverlayV2() {}
    private static final String MAGIC="WD-TERRAIN-1";
    private static final int MAX_SAMPLES=100_000;

    public record State(String world,
            Map<GlobalHydrologyAddressV2.Sample,Double> elevations) {
        public State {
            Objects.requireNonNull(world);
            Objects.requireNonNull(elevations);
            var ordered=new TreeMap<GlobalHydrologyAddressV2.Sample,Double>(
                HydrologyFlowGraphV2.ADDRESS_ORDER);
            for(var entry:elevations.entrySet()){
                var address=Objects.requireNonNull(entry.getKey());
                double height=Objects.requireNonNull(entry.getValue());
                if(!Double.isFinite(height))
                    throw new IllegalArgumentException("Non-finite terrain overlay");
                ordered.put(address,height==0?0.0:height);
            }
            if(ordered.size()>MAX_SAMPLES)
                throw new IllegalArgumentException("Terrain overlay budget exceeded");
            elevations=Collections.unmodifiableMap(ordered);
        }
    }

    public record Evolved(State state,SedimentLandscapeV2.Step landscape) {
        public Evolved {
            Objects.requireNonNull(state);
            Objects.requireNonNull(landscape);
        }
    }

    public static String encode(State state) {
        Objects.requireNonNull(state);
        var out=new StringBuilder(MAGIC).append('\n')
            .append(Base64.getEncoder().encodeToString(
                state.world().getBytes(StandardCharsets.UTF_8))).append('\n')
            .append(state.elevations().size()).append('\n');
        for(var entry:state.elevations().entrySet()){
            var id=entry.getKey();
            out.append(id.level()).append(',').append(id.divisions()).append(',')
                .append(id.x()).append(',').append(id.y()).append(',')
                .append(Double.toHexString(entry.getValue())).append('\n');
        }
        return out.toString();
    }

    public static State decode(String payload) {
        Objects.requireNonNull(payload);
        var lines=payload.split("\\n",-1);
        if(lines.length<4||!MAGIC.equals(lines[0])||
           !lines[lines.length-1].isEmpty())
            throw new IllegalArgumentException("Unsupported terrain state");
        try {
            String world=StandardCharsets.UTF_8.newDecoder()
                .decode(ByteBuffer.wrap(Base64.getDecoder().decode(lines[1])))
                .toString();
            int count=Integer.parseInt(lines[2]);
            if(count<0||count>MAX_SAMPLES||lines.length!=count+4)
                throw new IllegalArgumentException("Invalid terrain state length");
            var heights=new TreeMap<GlobalHydrologyAddressV2.Sample,Double>(
                HydrologyFlowGraphV2.ADDRESS_ORDER);
            for(int i=0;i<count;i++){
                var fields=lines[i+3].split(",",-1);
                if(fields.length!=5)
                    throw new IllegalArgumentException("Invalid terrain record");
                var address=new GlobalHydrologyAddressV2.Sample(
                    Integer.parseInt(fields[0]),Integer.parseInt(fields[1]),
                    Long.parseLong(fields[2]),Long.parseLong(fields[3]));
                double height=Double.parseDouble(fields[4]);
                if(!Double.isFinite(height)||heights.putIfAbsent(address,height)!=null)
                    throw new IllegalArgumentException("Invalid terrain elevation");
            }
            var state=new State(world,heights);
            if(!encode(state).equals(payload))
                throw new IllegalArgumentException("Noncanonical terrain state");
            return state;
        }catch(NumberFormatException|CharacterCodingException ex){
            throw new IllegalArgumentException("Malformed terrain state",ex);
        }
    }

    /** Seeded terrain with any previously persisted changes applied. */
    public static HydrologyGlobalSlopeRouterV2.Elevations elevations(
            HydrologyGlobalSlopeRouterV2.Elevations seed,State state) {
        Objects.requireNonNull(seed);
        Objects.requireNonNull(state);
        return address->state.elevations().containsKey(address)
            ?state.elevations().get(address):seed.at(address);
    }

    /**
     * Evolve one row-major, contiguous global-address window. Only the
     * supplied cells change; all other persisted terrain is retained.
     * The resulting overlay is not durable until the caller commits it in
     * a world checkpoint. A failed solve leaves the previous state intact.
     */
    public static Evolved evolveWindow(
            State previous,List<GlobalHydrologyAddressV2.Sample> addresses,
            int width,int height,HydrologyGlobalSlopeRouterV2.Elevations seed,
            double[] rainfall,double[] cellArea,double density,
            double strength,double maximumCut,double[] transportCapacity,
            Collection<Integer> verifiedOutlets) {
        Objects.requireNonNull(previous);
        Objects.requireNonNull(addresses);
        Objects.requireNonNull(seed);
        if(width<1||height<1||(long)width*height!=addresses.size())
            throw new IllegalArgumentException("Invalid terrain window dimensions");
        var first=Objects.requireNonNull(addresses.get(0));
        double[] current=new double[addresses.size()];
        var elevation=elevations(seed,previous);
        for(int i=0;i<current.length;i++){
            var address=Objects.requireNonNull(addresses.get(i));
            if(address.level()!=first.level()||
               address.divisions()!=first.divisions()||
               address.x()!=Math.addExact(first.x(),i%width)||
               address.y()!=Math.addExact(first.y(),i/width))
                throw new IllegalArgumentException("Noncontiguous terrain window");
            current[i]=elevation.at(address);
            if(!Double.isFinite(current[i]))
                throw new IllegalArgumentException("Invalid current terrain");
        }
        var result=SedimentLandscapeV2.advanceToVerifiedOutlets(
            width,height,current,rainfall,cellArea,density,
            strength,maximumCut,transportCapacity,verifiedOutlets);
        var updated=new TreeMap<GlobalHydrologyAddressV2.Sample,Double>(
            HydrologyFlowGraphV2.ADDRESS_ORDER);
        updated.putAll(previous.elevations());
        double[] changed=result.terrain();
        for(int i=0;i<changed.length;i++)updated.put(addresses.get(i),changed[i]);
        return new Evolved(new State(previous.world(),updated),result);
    }
}
