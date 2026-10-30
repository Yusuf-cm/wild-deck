package com.wilddeck.world;

import java.util.*;

/**
 * Deterministic, noncommitting seasonal erosion/sediment preview.
 *
 * Converts one already-conserved snow/soil runoff step into owned physical
 * water volumes on a finite contiguous terrain window, then computes
 * incision, sediment transport/deposition and rebuilt spill drainage.
 * No water is injected into the durable river inbox and no checkpoint or
 * seeded terrain is changed. The caller must separately audit and activate
 * changed global routing before treating this preview as authoritative.
 */
public final class HydrologySeasonalErosionPreviewV2 {
    private HydrologySeasonalErosionPreviewV2() {}

    public record Preview(HydrologyTerrainOverlayV2.Evolved terrain,
                          double runoffVolume,double terminalVolume) {
        public Preview {
            Objects.requireNonNull(terrain);
            if(!Double.isFinite(runoffVolume)||!Double.isFinite(terminalVolume)||
               runoffVolume<0||terminalVolume<0)
                throw new IllegalArgumentException("Invalid preview water budget");
        }
    }

    public static Preview calculate(
            HydrologyTerrainOverlayV2.State previous,
            List<GlobalHydrologyAddressV2.Sample> addresses,
            int width,int height,
            HydrologyGlobalSlopeRouterV2.Elevations seed,
            HydrologySnowSoilStateV2.Step season,
            double radiusMeters,double sedimentDensity,
            double erosionStrength,double maximumCut,
            double[] transportCapacity,
            Collection<Integer> verifiedOutlets) {
        Objects.requireNonNull(previous);
        Objects.requireNonNull(addresses);
        Objects.requireNonNull(season);
        Objects.requireNonNull(verifiedOutlets);
        HydrologySphericalCellAreaV2.planetArea(radiusMeters);
        if(width<1||height<1||(long)width*height!=addresses.size())
            throw new IllegalArgumentException("Invalid seasonal terrain window");
        var runoff=new HashMap<GlobalHydrologyAddressV2.Sample,Double>();
        for(var source:season.runoff())
            if(runoff.putIfAbsent(source.address(),source.localWater())!=null)
                throw new IllegalArgumentException("Duplicate seasonal runoff owner");
        if(runoff.size()!=addresses.size())
            throw new IllegalArgumentException("Seasonal runoff does not cover window");
        if(!season.state().cells().keySet().equals(runoff.keySet()))
            throw new IllegalArgumentException("Seasonal stores and runoff ownership differ");
        double[] rain=new double[addresses.size()];
        double[] areas=new double[addresses.size()];
        double sourceTotal=0;
        for(int i=0;i<rain.length;i++) {
            var address=Objects.requireNonNull(addresses.get(i));
            var volume=runoff.remove(address);
            if(volume==null)
                throw new IllegalArgumentException("Missing owned seasonal runoff");
            rain[i]=volume;
            areas[i]=HydrologySphericalCellAreaV2.areaAt(address,radiusMeters);
            sourceTotal+=volume;
        }
        if(!runoff.isEmpty()||!Double.isFinite(sourceTotal))
            throw new IllegalArgumentException("Unknown seasonal runoff source");
        double tolerance=Math.max(1e-8,sourceTotal*1e-10);
        if(Math.abs(sourceTotal-season.runoffVolume())>tolerance)
            throw new IllegalArgumentException("Inconsistent seasonal runoff budget");
        var evolved=HydrologyTerrainOverlayV2.evolveWindow(
            previous,addresses,width,height,seed,rain,areas,
            sedimentDensity,erosionStrength,maximumCut,
            transportCapacity,verifiedOutlets);
        double terminal=0;
        var downstream=evolved.landscape().drainage().spillTo();
        var flow=evolved.landscape().discharge().flow();
        for(int i=0;i<downstream.length;i++)
            if(downstream[i]<0)terminal+=flow[i];
        if(!Double.isFinite(terminal)||
           Math.abs(terminal-sourceTotal)>tolerance)
            throw new IllegalArgumentException("Erosion preview lost runoff");
        return new Preview(evolved,sourceTotal,terminal);
    }
}
