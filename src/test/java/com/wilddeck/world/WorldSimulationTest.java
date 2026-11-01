package com.wilddeck.world;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.wilddeck.world.WorldSimulation.Trait.*;

class WorldSimulationTest {
    private void yes(WorldSimulation.Result r){assertTrue(r.success(),r.message());}
    private void unchanged(WorldSimulation w,java.util.function.Supplier<WorldSimulation.Result> action){
        var before=w.snapshot();assertFalse(action.get().success());assertEquals(before,w.snapshot());
    }
    @Test void fireReactionAndDeliberateResponsesChangeTheWorld(){
        var w=WorldSandboxDemo.sample();yes(w.ignite("ember","trees"));
        // Ignition opens a response opportunity; exposure occurs only on a tick.
        assertEquals(3,w.state("scout").health());assertEquals(3,w.state("trees").fuel());
        w.advance();assertEquals(2,w.state("scout").health());assertEquals(2,w.state("trees").fuel());
        assertEquals(2,w.snapshot().smoke().get("forest"));
        unchanged(w,()->w.move("worker","forest"));
        yes(w.move("scout","river")); // A chosen retreat, not an automatic AI decision.
        yes(w.collectWater("worker","water"));unchanged(w,()->w.collectWater("worker","water"));
        yes(w.douse("worker","trees"));
        assertFalse(w.burningAt("forest"));assertEquals(2,w.state("trees").wetTicks());
        assertEquals(0,w.state("worker").water());assertEquals(2,w.state("trees").fuel());
        yes(w.move("worker","forest"));w.advance();
        assertEquals(3,w.state("worker").health());assertEquals(2,w.state("scout").health());
    }
    @Test void wettingPreventsIgnitionUntilMaterialDries(){
        var w=WorldSandboxDemo.sample();yes(w.collectWater("worker","water"));
        yes(w.douse("worker","trees"));
        unchanged(w,()->w.ignite("ember","trees"));
        w.advance();unchanged(w,()->w.ignite("ember","trees"));
        w.advance();yes(w.ignite("ember","trees"));assertTrue(w.burningAt("forest"));
    }
    @Test void materialPropertiesWorkAcrossNamesPlacesAndSources(){
        var w=WorldSandboxDemo.sample();
        yes(w.ignite("ember","gate"));assertTrue(w.state("gate").burning());
        unchanged(w,()->w.ignite("ember","wall"));
        w.add(new WorldSimulation.Thing("odd","Unfamiliar dry object","river",Set.of(FLAMMABLE),2,0,0));
        w.add(new WorldSimulation.Thing("heat","Different heat source","forest",Set.of(HEAT_SOURCE),0,0,1));
        yes(w.ignite("heat","odd"));assertEquals(0,w.state("heat").heatCharges());
        unchanged(w,()->w.ignite("heat","trees"));
    }
    @Test void fireExhaustsFuelAndSmokeClearsWithoutRegrowingMaterial(){
        var w=WorldSandboxDemo.sample();yes(w.move("scout","river"));yes(w.ignite("ember","trees"));
        w.advance();w.advance();w.advance();
        assertFalse(w.state("trees").burning());assertEquals(0,w.state("trees").fuel());
        unchanged(w,()->w.ignite("ember","trees"));
        yes(w.collectWater("worker","water"));unchanged(w,()->w.douse("worker","trees"));
        w.advance();assertTrue(w.snapshot().smoke().containsKey("forest"));
        w.advance();assertFalse(w.snapshot().smoke().containsKey("forest"));
    }
    @Test void fireproofActorsCanEnterAndSurviveWhileExposureDoesNotStack(){
        var w=WorldSandboxDemo.sample();
        w.add(new WorldSimulation.Thing("second","Dry brush","forest",Set.of(FLAMMABLE),3,0,0));
        w.add(new WorldSimulation.Thing("protected","Protected unit","river",Set.of(MOBILE,FIREPROOF),0,3,0));
        yes(w.ignite("ember","trees"));yes(w.ignite("ember","second"));yes(w.move("protected","forest"));
        w.advance();assertEquals(3,w.state("protected").health());assertEquals(2,w.state("scout").health());
        w.advance();w.advance();assertEquals(0,w.state("scout").health());
        unchanged(w,()->w.move("scout","river"));
        unchanged(w,()->w.collectWater("scout","water"));
    }
    @Test void distanceAndWaterRequirementsRejectActionsWithoutPartialCosts(){
        var map=new WorldMap(List.of(
            new WorldLocation("a","A",Set.of()),new WorldLocation("b","B",Set.of()),
            new WorldLocation("c","C",Set.of())),Map.of("a",Set.of("b"),"b",Set.of("c")));
        var w=new WorldSimulation(map);
        w.add(new WorldSimulation.Thing("heat","Heat","a",Set.of(HEAT_SOURCE),0,0,1));
        w.add(new WorldSimulation.Thing("fuel","Fuel","c",Set.of(FLAMMABLE),2,0,0));
        w.add(new WorldSimulation.Thing("person","Person","a",Set.of(MOBILE),0,3,0));
        w.add(new WorldSimulation.Thing("water","Water","a",Set.of(WATER_SOURCE),0,0,0));
        unchanged(w,()->w.ignite("heat","fuel"));unchanged(w,()->w.move("person","c"));
        unchanged(w,()->w.move("person","missing"));unchanged(w,()->w.douse("person","fuel"));
        yes(w.collectWater("person","water"));unchanged(w,()->w.douse("person","fuel"));
        yes(w.move("person","b"));yes(w.douse("person","fuel"));assertEquals(0,w.state("person").water());
    }
    @Test void invalidSourcesFixedObjectsAndRepeatedIgnitionCannotChangeState(){
        var w=WorldSandboxDemo.sample();
        unchanged(w,()->w.ignite("worker","trees"));unchanged(w,()->w.ignite("ember","missing"));
        unchanged(w,()->w.ignite("ember","ember"));unchanged(w,()->w.move("gate","river"));
        unchanged(w,()->w.collectWater("worker","gate"));
        yes(w.ignite("ember","trees"));unchanged(w,()->w.ignite("ember","trees"));
        unchanged(w,()->w.move("worker","forest"));
    }
    @Test void snapshotsAreDetachedAndRepeatedExperimentsAreDeterministic(){
        var a=WorldSandboxDemo.sample();var b=WorldSandboxDemo.sample();var before=a.snapshot();
        for(var w:List.of(a,b)){yes(w.ignite("ember","trees"));w.advance();yes(w.move("scout","river"));}
        assertEquals(a.snapshot(),b.snapshot());assertEquals(0,before.tick());
        assertFalse(before.things().stream().anyMatch(WorldSimulation.State::burning));
        assertThrows(UnsupportedOperationException.class,()->before.events().add("fake"));
        assertThrows(IllegalArgumentException.class,()->a.add(new WorldSimulation.Thing("trees","Duplicate","forest",Set.of(),0,0,0)));
    }
}
