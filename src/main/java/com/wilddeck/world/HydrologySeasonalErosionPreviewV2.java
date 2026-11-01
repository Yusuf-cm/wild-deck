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

    /**
     * Checked forcing-to-erosion preview. Recomputes the seasonal water step
     * from its previous stores, verifies every forcing area against the same
     * planetary radius used for sediment mass, and returns both results for
     * later (separate) checkpoint reconciliation. This does not commit either.
     */
    public record ForcedPreview(HydrologySnowSoilStateV2.Step season,
                                Preview erosion) {
        public ForcedPreview {
            Objects.requireNonNull(season);
            Objects.requireNonNull(erosion);
        }
    }

    public static ForcedPreview calculateFromForcing(
            HydrologyTerrainOverlayV2.State previous,
            List<GlobalHydrologyAddressV2.Sample> addresses,
            int width,int height,
            HydrologyGlobalSlopeRouterV2.Elevations seed,
            HydrologySnowSoilStateV2.State snowSoil,
            Map<GlobalHydrologyAddressV2.Sample,HydrologySnowSoilStateV2.Forcing> weather,
            double radiusMeters,double sedimentDensity,
            double erosionStrength,double maximumCut,
            double[] transportCapacity,
            Collection<Integer> verifiedOutlets) {
        return calculateFromForcing(previous,addresses,width,height,seed,
            snowSoil,weather,radiusMeters,sedimentDensity,erosionStrength,
            maximumCut,transportCapacity,verifiedOutlets,Map.of());
    }

    /**
     * Include verified prior-epoch boundary receipts in the water driving
     * incision, sediment transport and post-erosion discharge. This is a
     * NONCOMMITTING calculation: callers must authenticate receipt edges,
     * old/new drainage routes and atomically consume the durable inbox.
     */
    public static ForcedPreview calculateFromForcing(
            HydrologyTerrainOverlayV2.State previous,
            List<GlobalHydrologyAddressV2.Sample> addresses,
            int width,int height,
            HydrologyGlobalSlopeRouterV2.Elevations seed,
            HydrologySnowSoilStateV2.State snowSoil,
            Map<GlobalHydrologyAddressV2.Sample,HydrologySnowSoilStateV2.Forcing> weather,
            double radiusMeters,double sedimentDensity,
            double erosionStrength,double maximumCut,
            double[] transportCapacity,
            Collection<Integer> verifiedOutlets,
            Map<GlobalHydrologyAddressV2.Sample,Double> incoming) {
        Objects.requireNonNull(previous);
        Objects.requireNonNull(addresses);
        Objects.requireNonNull(snowSoil);
        Objects.requireNonNull(weather);
        if(!previous.world().equals(snowSoil.world()))
            throw new IllegalArgumentException("Seasonal and terrain worlds differ");
        if(weather.size()!=addresses.size() ||
           !weather.keySet().containsAll(addresses) ||
           !weather.keySet().containsAll(snowSoil.cells().keySet()))
            throw new IllegalArgumentException("Incomplete seasonal erosion forcing");
        HydrologySphericalAreaContractV2.verifyWeather(weather,radiusMeters);
        var season=HydrologySnowSoilStateV2.advance(snowSoil,weather);
        return new ForcedPreview(season,calculate(
            previous,addresses,width,height,seed,season,radiusMeters,
            sedimentDensity,erosionStrength,maximumCut,
            transportCapacity,verifiedOutlets,incoming));
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
        return calculate(previous,addresses,width,height,seed,season,
            radiusMeters,sedimentDensity,erosionStrength,maximumCut,
            transportCapacity,verifiedOutlets,Map.of());
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
            Collection<Integer> verifiedOutlets,
            Map<GlobalHydrologyAddressV2.Sample,Double> incoming) {
        Objects.requireNonNull(previous);
        Objects.requireNonNull(addresses);
        Objects.requireNonNull(season);
        Objects.requireNonNull(incoming);
        Objects.requireNonNull(verifiedOutlets);
        HydrologySphericalCellAreaV2.planetArea(radiusMeters);
        if(!previous.world().equals(season.state().world()))
            throw new IllegalArgumentException("Seasonal and terrain worlds differ");
        double initial=season.initialVolume(),precipitation=season.precipitationVolume();
        double evaporation=season.evaporationVolume(),reportedRunoff=season.runoffVolume();
        double stored=season.storedVolume();
        if(!Double.isFinite(initial)||!Double.isFinite(precipitation)||
           !Double.isFinite(evaporation)||!Double.isFinite(reportedRunoff)||
           !Double.isFinite(stored)||initial<0||precipitation<0||
           evaporation<0||reportedRunoff<0||stored<0)
            throw new IllegalArgumentException("Invalid seasonal water budget");
        double available=initial+precipitation;
        double accounted=evaporation+reportedRunoff+stored;
        if(!Double.isFinite(available)||!Double.isFinite(accounted)||
           Math.abs(available-accounted)>Math.max(1e-8,available*1e-10))
            throw new IllegalArgumentException("Unbalanced seasonal erosion input");
        if(width<1||height<1||(long)width*height!=addresses.size())
            throw new IllegalArgumentException("Invalid seasonal terrain window");
        var runoff=new HashMap<GlobalHydrologyAddressV2.Sample,Double>();
        for(var source:season.runoff())
            if(runoff.putIfAbsent(source.address(),source.localWater())!=null)
                throw new IllegalArgumentException("Duplicate seasonal runoff owner");
        if(runoff.size()!=addresses.size())
            throw new IllegalArgumentException("Seasonal runoff does not cover window");
        double importedTotal=0;
        for(var entry:incoming.entrySet()) {
            var address=Objects.requireNonNull(entry.getKey());
            var volume=Objects.requireNonNull(entry.getValue());
            if(!runoff.containsKey(address) || !Double.isFinite(volume) || volume<0)
                throw new IllegalArgumentException("Invalid imported runoff owner or volume");
            importedTotal+=volume;
            if(!Double.isFinite(importedTotal))
                throw new IllegalArgumentException("Imported runoff overflow");
        }
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
            rain[i]=volume+incoming.getOrDefault(address,0.0);
            if(!Double.isFinite(rain[i]))
                throw new IllegalArgumentException("Combined runoff overflow");
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
        double expected=sourceTotal+importedTotal;
        if(!Double.isFinite(expected))
            throw new IllegalArgumentException("Combined runoff overflow");
        if(!Double.isFinite(terminal)||
           Math.abs(terminal-expected)>Math.max(1e-8,expected*1e-10))
            throw new IllegalArgumentException("Erosion preview lost runoff");
        return new Preview(evolved,sourceTotal,terminal);
    }
}
