package com.wilddeck.world;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.net.*;
import java.net.http.*;
import static org.junit.jupiter.api.Assertions.*;

class PlanetAtlasServerTest {
    private HttpResponse<String> get(int port,String path)throws Exception{
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(
            URI.create("http://127.0.0.1:"+port+path)).GET().build(),HttpResponse.BodyHandlers.ofString());
    }
    @Test void viewerAndGeneratedWorldUseTheSameJavaSeedAndVersion()throws Exception{
        try(var server=new PlanetAtlasServer(0)){
            server.start();
            var page=get(server.port(),"/");assertEquals(200,page.statusCode());
            assertTrue(page.body().contains("An uncharted world."));
            var response=get(server.port(),"/api/world?seed=-9223372036854775808&width=180");
            assertEquals(200,response.statusCode());
            var data=new ObjectMapper().readTree(response.body());
            assertEquals("-9223372036854775808",data.get("seed").textValue());
            assertEquals(180,data.get("width").intValue());assertEquals(90,data.get("height").intValue());
            assertEquals(16200,data.get("elevation").size());
            var expected=new PlanetSurfaceGenerator(Planet.emptyEarthSized(),Long.MIN_VALUE)
                .sample(new Planet.SurfacePoint(89,-179));
            assertEquals(expected.elevationMeters(),data.get("elevation").get(0).intValue());
            assertTrue(response.headers().firstValue("Cache-Control").orElseThrow().contains("no-store"));
        }
    }
    @Test void invalidVersionsSeedsSizesAndDuplicateParametersAreRejected()throws Exception{
        try(var server=new PlanetAtlasServer(0)){
            server.start();
            for(String query:new String[]{"seed=9223372036854775808","seed=no","width=1000000",
                "width=360&width=720","version=unknown","extra=1","seed"}){
                assertEquals(400,get(server.port(),"/api/world?"+query).statusCode(),query);
            }
            assertEquals(404,get(server.port(),"/missing").statusCode());
            var post=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+server.port()+"/api/world"))
                .POST(HttpRequest.BodyPublishers.noBody()).build();
            assertEquals(405,HttpClient.newHttpClient().send(post,HttpResponse.BodyHandlers.ofString()).statusCode());
        }
    }
}
