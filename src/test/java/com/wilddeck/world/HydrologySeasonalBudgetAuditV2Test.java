package com.wilddeck.world;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class HydrologySeasonalBudgetAuditV2Test {
    private static final GlobalHydrologyAddressV2.Sample A =
        new GlobalHydrologyAddressV2.Sample(1,2,0,0);
    private static final GlobalHydrologyAddressV2.Sample B =
        new GlobalHydrologyAddressV2.Sample(1,2,7,2);

    private static Map<GlobalHydrologyAddressV2.Sample,
            HydrologySnowSoilStateV2.Forcing> winter() {
        return Map.of(
            A,new HydrologySnowSoilStateV2.Forcing(0.3,-4,0.02,0.15,3,0.01,100),
            B,new HydrologySnowSoilStateV2.Forcing(0.2,5,0.01,0.10,3,0.01,200));
    }

    private static Map<GlobalHydrologyAddressV2.Sample,
            HydrologySnowSoilStateV2.Forcing> spring() {
        return Map.of(
            A,new HydrologySnowSoilStateV2.Forcing(0.05,10,0.01,0.15,5,0.02,100),
            B,new HydrologySnowSoilStateV2.Forcing(0.15,6,0.03,0.10,5,0.02,200));
    }

    @Test void independentVolumeModelAgreesAcrossTwoPersistedSeasons() {
        var initial = new HydrologySnowSoilStateV2.State("world",Map.of());
        var winter = HydrologySnowSoilStateV2.advance(initial,winter());
        var first = HydrologySeasonalBudgetAuditV2.verify(initial,winter(),winter);
        assertEquals(2,first.cells());
        assertEquals(winter.runoffVolume(),first.runoffM3(),1e-9);
        var spring = HydrologySnowSoilStateV2.advance(winter.state(),spring());
        var second = HydrologySeasonalBudgetAuditV2.verify(
            winter.state(),spring(),spring);
        assertEquals(2,second.cells());
        assertEquals(spring.storedVolume(),second.storedM3(),1e-9);
        assertEquals(first.initialM3()+first.precipitationM3()+
            second.precipitationM3(),
            first.evaporationM3()+first.runoffM3()+
            second.evaporationM3()+second.runoffM3()+second.storedM3(),1e-8);
    }

    @Test void tamperedRiverRunoffIsRejectedEvenWhenReportedTotalsStaySame() {
        var initial = new HydrologySnowSoilStateV2.State("world",Map.of());
        var actual = HydrologySnowSoilStateV2.advance(initial,winter());
        var sources = new ArrayList<>(actual.runoff());
        var original = sources.get(0);
        sources.set(0,new HydrologyGlobalSlopeRouterV2.Source(
            original.address(),original.localWater()+1));
        var forged = new HydrologySnowSoilStateV2.Step(actual.state(),sources,
            actual.initialVolume(),actual.precipitationVolume(),
            actual.evaporationVolume(),actual.runoffVolume(),actual.storedVolume());
        assertThrows(IllegalArgumentException.class,() ->
            HydrologySeasonalBudgetAuditV2.verify(initial,winter(),forged));
    }

    @Test void tamperedStorageAndDoubleCountedPrecipitationFailClosed() {
        var initial = new HydrologySnowSoilStateV2.State("world",Map.of());
        var actual = HydrologySnowSoilStateV2.advance(initial,winter());
        var forgedStorage = new HashMap<>(actual.state().cells());
        forgedStorage.put(A,new HydrologySnowSoilStateV2.Cell(1,1));
        var forged = new HydrologySnowSoilStateV2.Step(
            new HydrologySnowSoilStateV2.State("world",forgedStorage),
            actual.runoff(),actual.initialVolume(),actual.precipitationVolume(),
            actual.evaporationVolume(),actual.runoffVolume(),actual.storedVolume());
        assertThrows(IllegalArgumentException.class,() ->
            HydrologySeasonalBudgetAuditV2.verify(initial,winter(),forged));
        var doubleRain = new HydrologySnowSoilStateV2.Step(actual.state(),
            actual.runoff(),actual.initialVolume(),actual.precipitationVolume(),
            actual.evaporationVolume(),
            actual.runoffVolume()+actual.precipitationVolume(),
            actual.storedVolume());
        assertThrows(IllegalArgumentException.class,() ->
            HydrologySeasonalBudgetAuditV2.verify(initial,winter(),doubleRain));
    }

    @Test void changedAreaAndDuplicateSourceFailClosed() {
        var initial = new HydrologySnowSoilStateV2.State("world",Map.of());
        var actual = HydrologySnowSoilStateV2.advance(initial,winter());
        var changed = new HashMap<>(winter());
        changed.put(B,new HydrologySnowSoilStateV2.Forcing(
            0.2,5,0.01,0.10,3,0.01,201));
        assertThrows(IllegalArgumentException.class,() ->
            HydrologySeasonalBudgetAuditV2.verify(initial,changed,actual));
        var duplicated = new ArrayList<>(actual.runoff());
        duplicated.add(actual.runoff().get(0));
        var forged = new HydrologySnowSoilStateV2.Step(actual.state(),duplicated,
            actual.initialVolume(),actual.precipitationVolume(),
            actual.evaporationVolume(),actual.runoffVolume(),actual.storedVolume());
        assertThrows(IllegalArgumentException.class,() ->
            HydrologySeasonalBudgetAuditV2.verify(initial,winter(),forged));
    }
}
