package com.wilddeck.world;

import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** Read-only local inspection tool. It does not modify or host the playable match. */
public final class PlanetAtlasServer implements AutoCloseable {
    private final HttpServer server;
    private final ExecutorService workers=Executors.newFixedThreadPool(4);
    public PlanetAtlasServer(int port)throws IOException{
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",port),16);
        server.setExecutor(workers);server.createContext("/",this::handle);
    }
    public void start(){server.start();}
    public int port(){return server.getAddress().getPort();}
    @Override public void close(){server.stop(0);workers.shutdownNow();}
    public static String template()throws IOException{
        try(var in=PlanetAtlasServer.class.getResourceAsStream("/web/planet-atlas.html")){
            if(in==null)throw new IOException("Missing planet atlas viewer");
            return new String(in.readAllBytes(),StandardCharsets.UTF_8);
        }
    }
    private void handle(HttpExchange exchange)throws IOException{
        exchange.getResponseHeaders().set("Cache-Control","no-store");
        exchange.getResponseHeaders().set("X-Content-Type-Options","nosniff");
        exchange.getResponseHeaders().set("Content-Security-Policy","default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; connect-src 'self'; img-src data: blob:; frame-ancestors 'none'; base-uri 'none'; form-action 'none'");
        try{
            if(!exchange.getRequestMethod().equals("GET")){
                exchange.getResponseHeaders().set("Allow","GET");send(exchange,405,"text/plain","Read-only atlas");return;
            }
            if(exchange.getRequestURI().getPath().equals("/")){send(exchange,200,"text/html",template());return;}
            if(!exchange.getRequestURI().getPath().equals("/api/world")){send(exchange,404,"text/plain","Not found");return;}
            Map<String,String> query=new HashMap<>();
            String raw=exchange.getRequestURI().getRawQuery();
            if(raw!=null&&!raw.isEmpty())for(String pair:raw.split("&",-1)){
                String[] pieces=pair.split("=",2);
                if(pieces.length!=2)throw new IllegalArgumentException();
                String key=URLDecoder.decode(pieces[0],StandardCharsets.UTF_8);
                String value=URLDecoder.decode(pieces[1],StandardCharsets.UTF_8);
                if(!Set.of("seed","width","version").contains(key)||query.putIfAbsent(key,value)!=null)
                    throw new IllegalArgumentException();
            }
            if(!query.getOrDefault("version",PlanetSurfaceGenerator.VERSION).equals(PlanetSurfaceGenerator.VERSION))
                throw new IllegalArgumentException();
            long seed=Long.parseLong(query.getOrDefault("seed","42"));
            int width=Integer.parseInt(query.getOrDefault("width","360"));
            var atlas=new PlanetSurfaceGenerator(Planet.emptyEarthSized(),seed).atlas(width);
            send(exchange,200,"application/json",atlas.toJson());
        }catch(IllegalArgumentException invalid){
            send(exchange,400,"text/plain","Use a signed 64-bit seed, width 180/360/720 and the supported generator version");
        }finally{exchange.close();}
    }
    private static void send(HttpExchange e,int code,String type,String body)throws IOException{
        byte[] bytes=body.getBytes(StandardCharsets.UTF_8);
        e.getResponseHeaders().set("Content-Type",type+"; charset=utf-8");
        e.sendResponseHeaders(code,bytes.length);
        try(var out=e.getResponseBody()){out.write(bytes);}
    }
    public static void main(String[] args)throws Exception{
        int port=args.length==0?8081:Integer.parseInt(args[0]);
        var server=new PlanetAtlasServer(port);server.start();
        System.out.println("Wild Deck planet atlas: http://127.0.0.1:"+server.port());
    }
}
