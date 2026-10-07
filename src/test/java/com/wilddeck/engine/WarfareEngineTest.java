package com.wilddeck.engine;

import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class WarfareEngineTest {

    @Test
    void attackOpensReactionWindowThenDealsSimultaneousDamage() {
        PlayerState a = new PlayerState("a","Attacker");
        PlayerState b = new PlayerState("b","Defender");
        CardInstance hydra = deployed(a, card("hydra","Hydra",
                Set.of("BIOLOGICAL","REGENERATIVE"),
                Set.of("ATTACK","BITE","REGENERATE"), 8));
        CardInstance sentinel = deployed(b, card("sentinel","Iron Sentinel",
                Set.of("CONSTRUCT","METAL"),
                Set.of("ATTACK","DEFEND","GUARD"), 7));

        GameState game = game(a,b);
        WarfareEngine warfare = new WarfareEngine();

        CombatEncounter encounter = warfare.declareAttack(game,"a",hydra.id(),sentinel.id());
        assertEquals(CombatStatus.REACTION_WINDOW,encounter.status());

        CombatResult result = warfare.resolve(game,encounter);

        assertEquals(3,result.damageToDefender());
        assertEquals(3,result.damageToAttacker());
        assertEquals(3,hydra.damage());
        assertEquals(3,sentinel.damage());
        assertTrue(hydra.hasState("WOUNDED"));
        assertTrue(sentinel.hasState("WOUNDED"));
        assertEquals(CombatStatus.RESOLVED,result.status());
    }

    @Test
    void guardCanInterceptAttackDuringReactionWindow() {
        PlayerState a = new PlayerState("a","Attacker");
        PlayerState b = new PlayerState("b","Defender");

        CardInstance attacker = deployed(a, card("cavalry","Cavalry",
                Set.of("ARMY","HUMAN","MOUNTED"), Set.of("ATTACK","CHARGE"), 6));
        CardInstance target = deployed(b, card("engineer","Engineer",
                Set.of("HUMAN","SPECIALIST"), Set.of("REPAIR"), 2));
        CardInstance guard = deployed(b, card("guard","Royal Guard",
                Set.of("HUMAN","ARMY"), Set.of("ATTACK","DEFEND","GUARD"), 7));

        GameState game = game(a,b);
        WarfareEngine warfare = new WarfareEngine();

        CombatEncounter encounter = warfare.declareAttack(game,"a",attacker.id(),target.id());
        warfare.intercept(game,encounter,guard.id());

        assertEquals(guard.id(),encounter.defendingCardId());
        warfare.resolve(game,encounter);
        assertEquals(0,target.damage());
        assertTrue(guard.damage() > 0);
    }

    @Test
    void biologicalDeathCreatesCorpseInGraveyard() {
        PlayerState a = new PlayerState("a","Attacker");
        PlayerState b = new PlayerState("b","Defender");

        CardInstance hydra = deployed(a, card("hydra","Hydra",
                Set.of("BIOLOGICAL"), Set.of("ATTACK"), 8));
        CardInstance scout = deployed(b, card("scout","Scout",
                Set.of("BIOLOGICAL","HUMAN"), Set.of("OBSERVE"), 2));

        GameState game = game(a,b);
        CombatResult result = new WarfareEngine().resolve(
                game,
                new WarfareEngine().declareAttack(game,"a",hydra.id(),scout.id())
        );

        assertTrue(result.defenderDied());
        assertEquals(Zone.GRAVEYARD,scout.zone());
        assertTrue(scout.hasState("DEAD"));
        assertTrue(scout.hasState("CORPSE"));
        assertTrue(b.graveyard().stream().anyMatch(c -> c.id().equals(scout.id())));
    }

    @Test
    void constructDeathCreatesWreckNotCorpse() {
        PlayerState a = new PlayerState("a","Attacker");
        PlayerState b = new PlayerState("b","Defender");

        CardInstance attacker = deployed(a, card("giant","Siege Giant",
                Set.of("BIOLOGICAL","MASSIVE"), Set.of("ATTACK","SIEGE"), 9));
        CardInstance construct = deployed(b, card("drone","Metal Drone",
                Set.of("CONSTRUCT","METAL"), Set.of("DEFEND"), 2));

        GameState game = game(a,b);
        WarfareEngine warfare = new WarfareEngine();
        warfare.resolve(game,warfare.declareAttack(game,"a",attacker.id(),construct.id()));

        assertTrue(construct.hasState("WRECK"));
        assertFalse(construct.hasState("CORPSE"));
    }

    @Test
    void regenerationIsGradualNotInstant() {
        PlayerState p = new PlayerState("p","Player");
        CardInstance hydra = deployed(p, card("hydra","Hydra",
                Set.of("BIOLOGICAL","REGENERATIVE"),
                Set.of("ATTACK","REGENERATE"), 8));
        hydra.addDamage(4);
        hydra.refreshWoundState();

        int healed = new WarfareEngine().regenerate(hydra);

        assertEquals(1,healed);
        assertEquals(3,hydra.damage());
        assertTrue(hydra.hasState("WOUNDED"));
    }

    @Test
    void biologicalHealerCannotHealMetalConstruct() {
        PlayerState p = new PlayerState("p","Player");
        CardInstance healer = deployed(p, card("mender","Flesh Mender",
                Set.of("HUMAN","SPECIALIST"), Set.of("TREAT","HEAL BIOLOGICAL"), null));
        CardInstance sentinel = deployed(p, card("sentinel","Iron Sentinel",
                Set.of("CONSTRUCT","METAL"), Set.of("ATTACK","DEFEND"), 7));
        sentinel.addDamage(3);

        assertThrows(IllegalStateException.class,
                () -> new WarfareEngine().heal(healer,sentinel));
    }

    @Test
    void attackerCanRetreatBeforeCombatResolution() {
        PlayerState a = new PlayerState("a","Attacker");
        PlayerState b = new PlayerState("b","Defender");
        CardInstance attacker = deployed(a, card("roc","Roc",
                Set.of("BIOLOGICAL","FLYING"), Set.of("ATTACK","FLY"), 7));
        CardInstance defender = deployed(b, card("guard","Guard",
                Set.of("HUMAN"), Set.of("ATTACK","DEFEND"), 5));

        GameState game = game(a,b);
        WarfareEngine warfare = new WarfareEngine();
        CombatEncounter encounter = warfare.declareAttack(game,"a",attacker.id(),defender.id());

        warfare.retreat(game,encounter);

        assertEquals(CombatStatus.RETREATED,encounter.status());
        assertTrue(attacker.hasState("RETREATED"));
        assertThrows(IllegalStateException.class, () -> warfare.resolve(game,encounter));
    }

    @Test
    void nonAggressionContractPreventsAttackDeclaration() {
        PlayerState a = new PlayerState("a","Attacker");
        PlayerState b = new PlayerState("b","Defender");
        CardInstance attacker = deployed(a, card("hydra","Hydra",
                Set.of("BIOLOGICAL"), Set.of("ATTACK"), 8));
        CardInstance defender = deployed(b, card("guard","Guard",
                Set.of("HUMAN"), Set.of("ATTACK","DEFEND"), 5));

        GameState game = game(a,b);
        game.addContract(new Contract(Set.of("a","b"),Set.of(ContractTerm.NON_AGGRESSION),1,3));

        assertThrows(IllegalStateException.class,
                () -> new WarfareEngine().declareAttack(game,"a",attacker.id(),defender.id()));
    }

    private static GameState game(PlayerState... players) {
        return new GameState(GameRules.alphaV1(),List.of(players),List.of(),players[0].id());
    }

    private static CardInstance deployed(PlayerState player, CardDefinition definition) {
        CardInstance instance = new CardInstance(definition,player.id(),Zone.KINGDOM,Visibility.PUBLIC);
        player.deploy(instance,false);
        return instance;
    }

    private static CardDefinition card(
            String id,String name,Set<String> props,Set<String> caps,Integer strength
    ) {
        return new CardDefinition(id,name,"Test",props,caps,strength,Map.of(),"");
    }
}
