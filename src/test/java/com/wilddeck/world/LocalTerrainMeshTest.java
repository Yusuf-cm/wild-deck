package com.wilddeck.world;

import org.junit.jupiter.api.Test;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;

class LocalTerrainMeshTest {
    private final PlanetSurfaceV2 world=new PlanetSurfaceV2(Planet.emptyEarthSized(),42);

    @Test void meshHasCorrectTriangleCountAndValidIndices() {
        var mesh=LocalTerrainMesh.generate(world,new PlanetRegionGrid.Region(3,2,4),8);
        assertEquals(81,mesh.vertexCount());
        assertEquals(128,mesh.triangleCount());
        int[] indices=mesh.triangleIndices();
        assertEquals(384,indices.length);
        assertTrue(Arrays.stream(indices).allMatch(i->i>=0&&i<mesh.vertexCount()));
    }
    @Test void adjacentTilesHaveIdenticalEdges() {
        for(int level=0;level<5;level++){
            var a=LocalTerrainMesh.generate(world,new PlanetRegionGrid.Region(level,0,0),8);
            var b=LocalTerrainMesh.generate(world,new PlanetRegionGrid.Region(level,1,0),8);
            for(int y=0;y<=8;y++)assertEquals(a.elevationAt(8,y),b.elevationAt(0,y));
        }
    }
    @Test void parentAndChildCoincideAtSharedVertices() {
        var parent=LocalTerrainMesh.generate(world,new PlanetRegionGrid.Region(2,1,1),8);
        var child=LocalTerrainMesh.generate(world,new PlanetRegionGrid.Region(3,2,2),8);
        for(int y=0;y<=8;y+=2)for(int x=0;x<=8;x+=2)
            assertEquals(parent.elevationAt(x/2,y/2),child.elevationAt(x,y));
    }
    @Test void generatedVertexArraysCannotBeMutatedExternally() {
        var mesh=LocalTerrainMesh.generate(world,new PlanetRegionGrid.Region(3,0,0),4);
        double original=mesh.elevationAt(0,0);
        Arrays.fill(mesh.elevationMeters(),Double.NaN);
        assertEquals(original,mesh.elevationAt(0,0));
    }
    @Test void invalidMeshSizesFailBeforeAllocation() {
        var region=new PlanetRegionGrid.Region(0,0,0);
        assertThrows(IllegalArgumentException.class,()->LocalTerrainMesh.generate(world,region,0));
        assertThrows(IllegalArgumentException.class,()->LocalTerrainMesh.generate(world,region,257));
    }
}
