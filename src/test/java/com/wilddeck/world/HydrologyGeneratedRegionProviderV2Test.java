package com.wilddeck.world;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class HydrologyGeneratedRegionProviderV2Test {
    @Test void rejectsInvalidParameters(){
        assertThrows(NullPointerException.class,()->
            new HydrologyGeneratedRegionProviderV2(null,null,1,4,1));
    }
}
