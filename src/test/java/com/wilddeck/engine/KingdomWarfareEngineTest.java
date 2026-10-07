package com.wilddeck.engine;

import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class KingdomWarfareEngineTest {

    @Test
    void occupationMustSurviveIntoLaterRoundBeforeCapture() {
        PlayerState attacker = new PlayerState("a","Attacker");
        PlayerState defender = new PlayerState("d","Defender");

        CardInstance cavalry = deployed(attacker, card(
                "cavalry","Cavalry",
                Set.of("ARMY","HUMAN","MOUNTED"),
                Set.of("ATTACK","OCCUPY","CHARGE"),6
        ));
        CardInstance foundry = deployed(defender, card(
                "foundry","Foundry",
                Set.of("STRUCTURE","INDUSTRIAL"),
                Set.of("SMELT","FORGE","PROCESS METAL"),null
        ));

        GameState game = game(attacker,defender);
        KingdomWarfareEngine warfare = new KingdomWarfareEngine();

        Occupation occupation = warfare.beginOccupation(game,"a",cavalry.id(),foundry.id());

        assertEquals(OccupationStatus.ACTIVE,occupation.status());
        assertTrue(cavalry.hasState("OCCUPYING"));
        assertTrue(foundry.hasState("OCCUPIED"));

        assertThrows(IllegalStateException.class,
                () -> warfare.completeOccupation(game,occupation.id()));

        game.nextRound();
        CardInstance captured = warfare.completeOccupation(game,occupation.id());

        assertEquals(OccupationStatus.COMPLETED,occupation.status());
        assertEquals("a",captured.ownerId());
        assertEquals("a",captured.controllerId());
        assertTrue(attacker.kingdom().stream().anyMatch(c -> c.id().equals(foundry.id())));
        assertFalse(defender.kingdom().stream().anyMatch(c -> c.id().equals(foundry.id())));
    }

    @Test
    void occupationFailsIfOccupierDiesDuringCounterattackWindow() {
        PlayerState attacker = new PlayerState("a","Attacker");
        PlayerState defender = new PlayerState("d","Defender");

        CardInstance cavalry = deployed(attacker, card(
                "cavalry","Cavalry",Set.of("ARMY"),Set.of("ATTACK","OCCUPY"),6));
        CardInstance mine = deployed(defender, card(
                "mine","Gold Mine",Set.of("STRUCTURE"),Set.of("PRODUCE GOLD"),null));

        GameState game = game(attacker,defender);
        KingdomWarfareEngine kingdom = new KingdomWarfareEngine();
        Occupation occupation = kingdom.beginOccupation(game,"a",cavalry.id(),mine.id());

        cavalry.addDamage(cavalry.vitality());
        cavalry.addState("DEAD");
        attacker.moveToGraveyard(cavalry);

        game.nextRound();

        assertThrows(IllegalStateException.class,
                () -> kingdom.completeOccupation(game,occupation.id()));
        assertEquals(OccupationStatus.BROKEN,occupation.status());
        assertEquals("d",mine.controllerId());
    }

    @Test
    void conquestRequiresBrokenDefenseAndCompletedOccupation() {
        PlayerState attacker = new PlayerState("a","Attacker");
        PlayerState defender = new PlayerState("d","Defender");

        CardInstance cavalry = deployed(attacker, card(
                "cavalry","Cavalry",Set.of("ARMY"),Set.of("ATTACK","OCCUPY"),6));
        CardInstance foundry = deployed(defender, card(
                "foundry","Foundry",Set.of("STRUCTURE"),Set.of("FORGE"),null));
        CardInstance guard = deployed(defender, card(
                "guard","Royal Guard",Set.of("ARMY","HUMAN"),Set.of("ATTACK","DEFEND","GUARD"),7));

        GameState game = game(attacker,defender);
        KingdomWarfareEngine kingdom = new KingdomWarfareEngine();

        assertThrows(IllegalStateException.class,
                () -> kingdom.conquer(game,"a","d"));

        guard.addState("INCAPACITATED");
        assertTrue(kingdom.defenseBroken(game,"d"));

        Occupation occupation = kingdom.beginOccupation(game,"a",cavalry.id(),foundry.id());
        game.nextRound();
        kingdom.completeOccupation(game,occupation.id());

        defender.resources().add(ResourceType.GOLD,3);
        defender.resources().add(ResourceType.WEALTH,2);

        KingdomWarfareResult result = kingdom.conquer(game,"a","d");

        assertEquals(KingdomStatus.CONQUERED,defender.kingdomStatus());
        assertEquals("a",defender.overlordId().orElseThrow());
        assertEquals(3,attacker.resources().get(ResourceType.GOLD));
        assertEquals(2,attacker.resources().get(ResourceType.WEALTH));
        assertEquals(0,defender.resources().get(ResourceType.GOLD));
        assertTrue(result.transferredCards() >= 1);
    }

    @Test
    void annihilationDestroysRemainingKingdomInsteadOfTransferringIt() {
        PlayerState attacker = new PlayerState("a","Attacker");
        PlayerState defender = new PlayerState("d","Defender");

        CardInstance cavalry = deployed(attacker, card(
                "cavalry","Cavalry",Set.of("ARMY"),Set.of("ATTACK","OCCUPY"),6));
        CardInstance mine = deployed(defender, card(
                "mine","Mine",Set.of("STRUCTURE"),Set.of("EXTRACT"),null));
        CardInstance farm = deployed(defender, card(
                "farm","Farm",Set.of("STRUCTURE","BIOLOGICAL"),Set.of("PRODUCE FOOD"),null));

        GameState game = game(attacker,defender);
        KingdomWarfareEngine kingdom = new KingdomWarfareEngine();

        Occupation occupation = kingdom.beginOccupation(game,"a",cavalry.id(),mine.id());
        game.nextRound();
        kingdom.completeOccupation(game,occupation.id());

        KingdomWarfareResult result = kingdom.annihilate(game,"a","d");

        assertEquals(KingdomStatus.ANNIHILATED,defender.kingdomStatus());
        assertTrue(defender.kingdom().isEmpty());
        assertEquals(1,result.destroyedCards());
        assertTrue(farm.hasState("DEAD"));
        assertTrue(farm.hasState("CORPSE"));
    }

    @Test
    void voluntaryVassalageDoesNotRequireMilitaryDefeat() {
        PlayerState overlord = new PlayerState("o","Overlord");
        PlayerState vassal = new PlayerState("v","Vassal");
        deployed(vassal, card(
                "guard","Royal Guard",Set.of("ARMY"),Set.of("ATTACK","DEFEND"),7));

        GameState game = game(overlord,vassal);
        KingdomWarfareResult result = new KingdomWarfareEngine()
                .acceptVassalage(game,"o","v");

        assertEquals(KingdomStatus.VASSAL,result.targetStatus());
        assertEquals("o",vassal.overlordId().orElseThrow());
        assertFalse(vassal.isIndependent());
        assertEquals("o",game.winner().orElseThrow().id());
    }

    @Test
    void forcedVassalageRequiresDefeatAndFoothold() {
        PlayerState overlord = new PlayerState("o","Overlord");
        PlayerState target = new PlayerState("t","Target");

        CardInstance cavalry = deployed(overlord, card(
                "cavalry","Cavalry",Set.of("ARMY"),Set.of("ATTACK","OCCUPY"),6));
        CardInstance farm = deployed(target, card(
                "farm","Farm",Set.of("STRUCTURE"),Set.of("PRODUCE FOOD"),null));

        GameState game = game(overlord,target);
        KingdomWarfareEngine kingdom = new KingdomWarfareEngine();

        assertThrows(IllegalStateException.class,
                () -> kingdom.imposeVassalage(game,"o","t"));

        Occupation occupation = kingdom.beginOccupation(game,"o",cavalry.id(),farm.id());
        game.nextRound();
        kingdom.completeOccupation(game,occupation.id());

        KingdomWarfareResult result = kingdom.imposeVassalage(game,"o","t");

        assertEquals(KingdomStatus.VASSAL,result.targetStatus());
        assertEquals("o",target.overlordId().orElseThrow());
        assertEquals("o",game.winner().orElseThrow().id());
    }

    @Test
    void nonAggressionBlocksOccupation() {
        PlayerState a = new PlayerState("a","A");
        PlayerState b = new PlayerState("b","B");
        CardInstance cavalry = deployed(a, card(
                "cavalry","Cavalry",Set.of("ARMY"),Set.of("ATTACK","OCCUPY"),6));
        CardInstance farm = deployed(b, card(
                "farm","Farm",Set.of("STRUCTURE"),Set.of("PRODUCE FOOD"),null));

        GameState game = game(a,b);
        game.addContract(new Contract(
                Set.of("a","b"),Set.of(ContractTerm.NON_AGGRESSION),1,4));

        assertThrows(IllegalStateException.class,
                () -> new KingdomWarfareEngine().beginOccupation(game,"a",cavalry.id(),farm.id()));
    }

    private static GameState game(PlayerState... players) {
        return new GameState(GameRules.alphaV1(),List.of(players),List.of(),players[0].id());
    }

    private static CardInstance deployed(PlayerState player, CardDefinition definition) {
        CardInstance instance = new CardInstance(
                definition,player.id(),Zone.KINGDOM,Visibility.PUBLIC);
        player.deploy(instance,false);
        return instance;
    }

    private static CardDefinition card(
            String id,String name,Set<String> props,Set<String> caps,Integer strength
    ) {
        return new CardDefinition(id,name,"Test",props,caps,strength,Map.of(),"");
    }
}
