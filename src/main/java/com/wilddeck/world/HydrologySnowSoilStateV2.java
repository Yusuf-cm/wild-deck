package com.wilddeck.world;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Opt-in, single-writer checkpoint payload for seasonal snow and soil stores
 * at globally canonical geographic samples. Every previously stored sample
 * must be forced on each step so its cell area and water budget are known.
 * Runoff volumes are the ONLY precipitation-derived source to inject into
 * river routing for these cells; do not inject raw precipitation again.
 *
 * This is a degree-day bucket, not a calibrated aquifer or weather model.
 */
public final class HydrologySnowSoilStateV2 {
    private HydrologySnowSoilStateV2() {}
    private static final String MAGIC="WD-SNOW-SOIL-1";
    public static final int MAX_CELLS=100_000;

    public record Cell(double snowDepth,double soilDepth) {
        public Cell {
            finite(snowDepth);
            finite(soilDepth);
        }
    }

    public record State(String world,Map<GlobalHydrologyAddressV2.Sample,Cell> cells) {
        public State {
            Objects.requireNonNull(world);
            Objects.requireNonNull(cells);
            var ordered=new TreeMap<GlobalHydrologyAddressV2.Sample,Cell>(
                HydrologyFlowGraphV2.ADDRESS_ORDER);
            for(var entry:cells.entrySet()) {
                var address=Objects.requireNonNull(entry.getKey());
                if(!HydrologySphericalTopologyV2.isCanonical(address))
                    throw new IllegalArgumentException("Noncanonical snow/soil address");
                ordered.put(address,Objects.requireNonNull(entry.getValue()));
            }
            if(ordered.size()>MAX_CELLS)
                throw new IllegalArgumentException("Snow/soil state budget exceeded");
            cells=Collections.unmodifiableMap(ordered);
        }
    }

    public record Forcing(double precipitationDepth,double temperatureC,
                          double potentialEvaporationDepth,double soilCapacityDepth,
                          double days,double degreeDayMeltFactor,double cellArea) {
        public Forcing {
            finite(precipitationDepth);
            if(!Double.isFinite(temperatureC))
                throw new IllegalArgumentException("Nonfinite temperature");
            finite(potentialEvaporationDepth);
            finite(soilCapacityDepth);
            if(!Double.isFinite(days)||days<=0)
                throw new IllegalArgumentException("Invalid season length");
            finite(degreeDayMeltFactor);
            if(!Double.isFinite(cellArea)||cellArea<=0)
                throw new IllegalArgumentException("Invalid cell area");
        }
    }

    public record Step(State state,List<HydrologyGlobalSlopeRouterV2.Source> runoff,
                       double initialVolume,double precipitationVolume,
                       double evaporationVolume,double runoffVolume,
                       double storedVolume) {
        public Step {
            Objects.requireNonNull(state);
            runoff=List.copyOf(runoff);
        }
    }

    public static Step advance(State previous,
            Map<GlobalHydrologyAddressV2.Sample,Forcing> forcing) {
        Objects.requireNonNull(previous);
        Objects.requireNonNull(forcing);
        if(forcing.isEmpty()||forcing.size()>MAX_CELLS||
           !forcing.keySet().containsAll(previous.cells().keySet()))
            throw new IllegalArgumentException("Missing forcing for persisted snow/soil cells");
        var sorted=new TreeMap<GlobalHydrologyAddressV2.Sample,Forcing>(
            HydrologyFlowGraphV2.ADDRESS_ORDER);
        for(var entry:forcing.entrySet()) {
            var address=Objects.requireNonNull(entry.getKey());
            if(!HydrologySphericalTopologyV2.isCanonical(address))
                throw new IllegalArgumentException("Noncanonical seasonal forcing");
            sorted.put(address,Objects.requireNonNull(entry.getValue()));
        }
        var cells=new TreeMap<GlobalHydrologyAddressV2.Sample,Cell>(
            HydrologyFlowGraphV2.ADDRESS_ORDER);
        var runoff=new ArrayList<HydrologyGlobalSlopeRouterV2.Source>();
        double initial=0,precipitation=0,evaporation=0,runoffVolume=0,stored=0;
        for(var entry:sorted.entrySet()) {
            var address=entry.getKey();
            var f=entry.getValue();
            var old=previous.cells().getOrDefault(address,new Cell(0,0));
            var period=new SeasonalSnowSoilCycleV2.Period(
                new double[]{f.precipitationDepth()},
                new double[]{f.temperatureC()},
                new double[]{f.potentialEvaporationDepth()},
                new double[]{f.soilCapacityDepth()},
                f.days(),f.degreeDayMeltFactor());
            var step=SeasonalSnowSoilCycleV2.simulate(
                new SeasonalSnowSoilCycleV2.State(
                    new double[]{old.snowDepth()},new double[]{old.soilDepth()}),
                List.of(period));
            var now=new Cell(step.finalState().snowDepth()[0],
                             step.finalState().soilDepth()[0]);
            cells.put(address,now);
            double volume=volume(step.totalRunoffDepth()[0],f.cellArea());
            runoff.add(new HydrologyGlobalSlopeRouterV2.Source(address,volume));
            initial=add(initial,volume(add(old.snowDepth(),old.soilDepth()),f.cellArea()));
            precipitation=add(precipitation,volume(f.precipitationDepth(),f.cellArea()));
            evaporation=add(evaporation,volume(step.totalEvaporationDepth()[0],f.cellArea()));
            runoffVolume=add(runoffVolume,volume);
            stored=add(stored,volume(add(now.snowDepth(),now.soilDepth()),f.cellArea()));
        }
        double budget=add(initial,precipitation);
        double accounted=add(add(evaporation,runoffVolume),stored);
        double tolerance=Math.max(1e-10*budget,1e-10);
        if(Math.abs(budget-accounted)>tolerance)
            throw new IllegalArgumentException("Seasonal global water budget violated");
        return new Step(new State(previous.world(),cells),runoff,
            initial,precipitation,evaporation,runoffVolume,stored);
    }

    public static String encode(State state) {
        Objects.requireNonNull(state);
        var out=new StringBuilder(MAGIC).append('\n')
            .append(Base64.getEncoder().encodeToString(
                state.world().getBytes(StandardCharsets.UTF_8))).append('\n')
            .append(state.cells().size()).append('\n');
        for(var entry:state.cells().entrySet()) {
            var id=entry.getKey();
            var cell=entry.getValue();
            out.append(id.level()).append(',').append(id.divisions()).append(',')
                .append(id.x()).append(',').append(id.y()).append(',')
                .append(Double.toHexString(cell.snowDepth())).append(',')
                .append(Double.toHexString(cell.soilDepth())).append('\n');
        }
        return out.toString();
    }

    public static State decode(String payload) {
        Objects.requireNonNull(payload);
        var lines=payload.split("\\n",-1);
        if(lines.length<4||!MAGIC.equals(lines[0])||
           !lines[lines.length-1].isEmpty())
            throw new IllegalArgumentException("Unsupported snow/soil payload");
        try {
            String world=StandardCharsets.UTF_8.newDecoder()
                .decode(ByteBuffer.wrap(Base64.getDecoder().decode(lines[1])))
                .toString();
            int count=Integer.parseInt(lines[2]);
            if(count<0||count>MAX_CELLS||lines.length!=count+4)
                throw new IllegalArgumentException("Invalid snow/soil payload length");
            var cells=new TreeMap<GlobalHydrologyAddressV2.Sample,Cell>(
                HydrologyFlowGraphV2.ADDRESS_ORDER);
            for(int i=0;i<count;i++) {
                var fields=lines[i+3].split(",",-1);
                if(fields.length!=6)
                    throw new IllegalArgumentException("Invalid snow/soil record");
                var address=new GlobalHydrologyAddressV2.Sample(
                    Integer.parseInt(fields[0]),Integer.parseInt(fields[1]),
                    Long.parseLong(fields[2]),Long.parseLong(fields[3]));
                var cell=new Cell(Double.parseDouble(fields[4]),
                                  Double.parseDouble(fields[5]));
                if(cells.putIfAbsent(address,cell)!=null)
                    throw new IllegalArgumentException("Duplicate snow/soil sample");
            }
            var result=new State(world,cells);
            if(!encode(result).equals(payload))
                throw new IllegalArgumentException("Noncanonical snow/soil payload");
            return result;
        }catch(NumberFormatException|CharacterCodingException ex) {
            throw new IllegalArgumentException("Malformed snow/soil payload",ex);
        }
    }

    private static double finite(double amount) {
        if(!Double.isFinite(amount)||amount<0)
            throw new IllegalArgumentException("Invalid snow/soil water");
        return amount;
    }
    private static double add(double a,double b) {
        return finite(a+b);
    }
    private static double volume(double depth,double area) {
        return finite(depth*area);
    }
}
