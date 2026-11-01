package com.wilddeck.app;

import com.wilddeck.engine.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class KingdomManagementTest {
    @Test void hiringConsumesGoldAndPersistsInDashboard() {
        PlayerState player = new PlayerState("human","Human");
        GameState game = new GameState(GameRules.alphaV1(),List.of(player),List.of(),"human");
        player.resources().add(ResourceType.GOLD,10);
        KingdomManagement management = new KingdomManagement();

        assertTrue(management.command("hire Enchanter for 3 Gold",game,"human"));
        assertEquals(7,player.resources().get(ResourceType.GOLD));
        assertTrue(management.dashboard(game,"human").contains("Enchanter=1"));
    }

    @Test void caravanNeedsGenuineShipmentBeforePayment() {
        PlayerState player = new PlayerState("human","Human");
        GameState game = new GameState(GameRules.alphaV1(),List.of(player),List.of(),"human");
        KingdomManagement management = new KingdomManagement();

        assertTrue(management.command("establish trade caravan",game,"human"));
        assertTrue(management.command("caravan trip",game,"human"));
        assertEquals(0,player.resources().get(ResourceType.GOLD));
    }
}
