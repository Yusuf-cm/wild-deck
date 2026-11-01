package com.wilddeck.world;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * Deterministic finite stock for a sampled site. Resource potential is a
 * geological signal, not itself a quantity or an inventory.
 */
public final class WorldResourceDeposits {
    private WorldResourceDeposits() {}
    public record Site(long id, Planet.SurfacePoint point) {
        public Site { Objects.requireNonNull(point); }
    }
    public static Map<PlanetSurfaceGenerator.Resource,Long> initialStock(
            PlanetSurfaceGenerator terrain, Site site) {
        Objects.requireNonNull(terrain);Objects.requireNonNull(site);
        var potential=terrain.sample(site.point()).potentialPercent();
        var result=new EnumMap<PlanetSurfaceGenerator.Resource,Long>(PlanetSurfaceGenerator.Resource.class);
        for(var resource:PlanetSurfaceGenerator.Resource.values()){
            int strength=potential.get(resource);
            long variation=Long.remainderUnsigned(WorldSeedChannels.derive(
                terrain.seed() ^ site.id(), resource.ordinal()+1000), 101);
            result.put(resource, strength==0 ? 0L : (long)strength*(100+variation));
        }
        return Map.copyOf(result);
    }
}
