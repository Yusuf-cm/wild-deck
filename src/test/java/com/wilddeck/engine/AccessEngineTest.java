package com.wilddeck.engine;

import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class AccessEngineTest {

    @Test
    void publicSurfaceTargetIsReachableWithoutSpecialRoute() {
        PlayerState a = new PlayerState("a","A");
        PlayerState b = new PlayerState("b","B");

        CardInstance cavalry = deployed(a, card(
                "cavalry","Cavalry","Military",
                Set.of("ARMY"),Set.of("ATTACK"),6), false);
        CardInstance sentinel = deployed(b, card(
                "sentinel","Iron Sentinel","Construct",
                Set.of("CONSTRUCT","METAL"),Set.of("DEFEND"),7), false);

        GameState game = game(a,b);
        AccessDecision decision = new AccessEngine().canReach(game,"a",cavalry.id(),sentinel.id());

        assertTrue(decision.allowed());
    }

    @Test
    void hiddenEnemyTargetRequiresDiscoveryAndRoute() {
        PlayerState a = new PlayerState("a","A");
        PlayerState b = new PlayerState("b","B");

        CardInstance smuggler = deployed(a, card(
                "smuggler","Smuggler","Specialist",
                Set.of("HUMAN"),Set.of("SMUGGLE","INFILTRATE"),null), true);
        CardInstance secret = deployed(b, card(
                "lab","Hidden Lab","Structure",
                Set.of("STRUCTURE"),Set.of("RESEARCH"),null), true);

        GameState game = game(a,b);
        AccessEngine access = new AccessEngine();

        AccessDecision unknown = access.canReach(game,"a",smuggler.id(),secret.id());
        assertFalse(unknown.allowed());

        a.reveal(secret.id());
        AccessDecision discoveredButNoRoute = access.canReach(game,"a",smuggler.id(),secret.id());
        assertFalse(discoveredButNoRoute.allowed());

        AccessRoute route = access.establishRoute(
                game,"a","b",AccessRouteType.INFILTRATION,
                List.of(smuggler.id()),Visibility.HIDDEN);

        AccessDecision reached = access.canReach(game,"a",smuggler.id(),secret.id());
        assertTrue(reached.allowed());
        assertEquals(route.id(),reached.routeId());
    }

    @Test
    void smugglerAndTunnelMapCanUseMappedTunnelNetwork() {
        PlayerState a = new PlayerState("a","A");
        PlayerState b = new PlayerState("b","B");

        CardInstance smuggler = deployed(a, card(
                "smuggler","Smuggler","Specialist",
                Set.of("HUMAN"),Set.of("SMUGGLE"),null), true);
        CardInstance map = deployed(a, card(
                "map","Tunnel Map","Tool",
                Set.of("MAP","UNDERGROUND"),Set.of("NAVIGATE TUNNELS"),null), true);

        GameState game = game(a,b);
        AccessRoute route = new AccessEngine().establishRoute(
                game,"a","b",AccessRouteType.TUNNEL,
                List.of(smuggler.id(),map.id()),Visibility.HIDDEN);

        assertTrue(route.active());
        assertEquals(AccessRouteType.TUNNEL,route.type());
    }

    @Test
    void voidGateStylePortalRequiresLinkAndOpenCapabilities() {
        PlayerState a = new PlayerState("a","A");
        PlayerState b = new PlayerState("b","B");

        CardInstance gate = deployed(a, card(
                "gate","Void Gate","Magic/Spatial",
                Set.of("MAGIC","SPATIAL","PORTAL"),
                Set.of("LINK LOCATIONS","OPEN GATE","CLOSE GATE"),null), true);

        GameState game = game(a,b);
        AccessRoute route = new AccessEngine().establishRoute(
                game,"a","b",AccessRouteType.PORTAL,
                List.of(gate.id()),Visibility.HIDDEN);

        assertEquals(AccessRouteType.PORTAL,route.type());
    }

    @Test
    void containedTargetNeedsRouteOrPhysicalBreach() {
        PlayerState a = new PlayerState("a","A");
        PlayerState b = new PlayerState("b","B");

        CardInstance colossus = deployed(a, card(
                "colossus","Bone Colossus","Creature/Construct",
                Set.of("UNDEAD","BONE","CONSTRUCT","MASSIVE"),
                Set.of("ATTACK","SIEGE","REASSEMBLE"),9), false);

        CardInstance fortress = deployed(b, card(
                "fortress","Fortress","Structure",
                Set.of("STRUCTURE","STONE","FORTIFICATION"),
                Set.of("FORTIFY","SHELTER"),null), false);

        CardInstance engineer = deployed(b, card(
                "engineer","Engineer","Specialist",
                Set.of("HUMAN"),Set.of("REPAIR"),null), true);

        GameState game = game(a,b);
        new RelationshipEngine().contain(game,"b",fortress.id(),engineer.id());
        a.reveal(engineer.id());

        AccessDecision sealed = new AccessEngine().canReach(game,"a",colossus.id(),engineer.id());
        assertFalse(sealed.allowed());

        fortress.addState("BREACHED");
        AccessDecision breached = new AccessEngine().canReach(game,"a",colossus.id(),engineer.id());
        assertTrue(breached.allowed());
        assertTrue(breached.reason().contains("breach"));
    }

    @Test
    void explicitProtectionRelationshipFindsProtector() {
        PlayerState p = new PlayerState("p","P");

        CardInstance guard = deployed(p, card(
                "guard","Royal Guard","Military",
                Set.of("HUMAN","ARMY"),Set.of("ATTACK","DEFEND","GUARD"),7), false);
        CardInstance foundry = deployed(p, card(
                "foundry","Foundry","Structure",
                Set.of("STRUCTURE"),Set.of("FORGE"),null), false);

        GameState game = game(p);
        RelationshipEngine relationships = new RelationshipEngine();
        relationships.protect(game,"p",guard.id(),foundry.id());

        assertEquals(guard.id(),relationships.protectorsOf(game,foundry.id()).get(0).id());
    }

    private static GameState game(PlayerState... players) {
        return new GameState(GameRules.alphaV1(),List.of(players),List.of(),players[0].id());
    }

    private static CardInstance deployed(
            PlayerState player, CardDefinition definition, boolean hidden
    ) {
        CardInstance instance = new CardInstance(
                definition,player.id(),Zone.KINGDOM,hidden ? Visibility.HIDDEN : Visibility.PUBLIC);
        player.deploy(instance,hidden);
        return instance;
    }

    private static CardDefinition card(
            String id,String name,String category,Set<String> props,Set<String> caps,Integer strength
    ) {
        return new CardDefinition(id,name,category,props,caps,strength,Map.of(),"");
    }
}
