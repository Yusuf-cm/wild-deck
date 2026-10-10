package com.wilddeck.app;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.wilddeck.engine.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Minimal single-user web test harness. No authentication or multiplayer guarantees. */
public final class HumanWebServer {
    private final PlayableSession session = PlayableSession.standard(42L,null);
    private String lastMessage = "Welcome to your kingdom.";
    private final Object lock = new Object();

    public static void main(String[] args) throws Exception {
        int port=Integer.parseInt(System.getenv().getOrDefault("PORT","10000"));
        HumanWebServer app = new HumanWebServer();
        HttpServer server=HttpServer.create(new InetSocketAddress("0.0.0.0",port),0);
        server.createContext("/health",e->{
            byte[] bytes="ok".getBytes(StandardCharsets.UTF_8);
            e.sendResponseHeaders(200,bytes.length);
            try(OutputStream out=e.getResponseBody()){out.write(bytes);}
        });
        server.createContext("/",app::handle);
        server.start();
        System.out.println("Wild Deck Human Alpha listening on "+port);
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            synchronized(lock) {
                if("POST".equals(exchange.getRequestMethod()) && "/action".equals(exchange.getRequestURI().getPath())) {
                    byte[] request=exchange.getRequestBody().readNBytes(8192);
                    String body=new String(request,StandardCharsets.UTF_8);
                    String value="";
                    for(String field:body.split("&")) if(field.startsWith("command=")){
                        value=URLDecoder.decode(field.substring(8),StandardCharsets.UTF_8);
                    }
                    lastMessage=act(value.trim());
                    exchange.getResponseHeaders().set("Location","/");
                    exchange.sendResponseHeaders(303,-1);
                    exchange.close();
                    return;
                }
                if(!"GET".equals(exchange.getRequestMethod()) || !"/".equals(exchange.getRequestURI().getPath())) {
                    exchange.sendResponseHeaders(404,-1);exchange.close();return;
                }
                byte[] bytes=render().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type","text/html; charset=utf-8");
                exchange.getResponseHeaders().set("Cache-Control","no-store");
                exchange.sendResponseHeaders(200,bytes.length);
                try(OutputStream out=exchange.getResponseBody()){out.write(bytes);}
            }
        } catch(RuntimeException ex) {
            byte[] bytes=("Error: "+ex.getMessage()).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(400,bytes.length);
            try(OutputStream out=exchange.getResponseBody()){out.write(bytes);}
        }
    }

    private String act(String input) {
        if(input.isBlank()) return "Type an order.";
        GameState state=session.state();
        String id=session.humanPlayerId();
        PlayerState player=state.player(id);
        String lower=input.toLowerCase(Locale.ROOT);
        if(lower.equals("next round") || lower.equals("end") || lower.equals("pass")) {
            // Human-only: NPCs do not receive actions in the sandbox.
            do {session.endTurn();} while(!session.humanTurn());
            return "Advanced to round "+state.round()+".";
        }
        if(lower.equals("draw") || lower.equals("draw a card")) return session.executor().draw(state,id).message();
        if(lower.startsWith("deploy ") || lower.startsWith("play ")) {
            String search=input.substring(input.indexOf(' ')+1).trim();
            Optional<CardInstance> card=PlayableAlphaCli.findHandCardByName(player.hand(),search);
            if(card.isEmpty()) return "You do not have that card in hand.";
            return session.executor().play(state,id,card.get().id(),false).message();
        }
        // Management commands print to console; capture for display in browser.
        ByteArrayOutputStream buffer=new ByteArrayOutputStream();
        PrintStream original=System.out;
        try(PrintStream capture=new PrintStream(buffer,true,StandardCharsets.UTF_8)) {
            System.setOut(capture);
            if(!session.management().command(input,state,id))
                return "Unsupported command. Try draw, deploy <card>, explore, assign <unit> to <task>, auto mine on, develop <deposit>, hire <role> for <gold>, or next round.";
        } finally {System.setOut(original);}
        String output=buffer.toString(StandardCharsets.UTF_8).trim();
        return output.isBlank()?"Order completed.":output;
    }

    private String render(){
        GameState state=session.state();
        PlayerState player=state.player(session.humanPlayerId());
        StringBuilder html=new StringBuilder("""
            <!doctype html><html lang="en"><head><meta charset="UTF-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <title>Wild Deck — Human Alpha</title><style>
            :root{color-scheme:dark}body{margin:0;background:#10151c;color:#e9e5d8;font:16px system-ui, sans-serif}
            main{max-width:1040px;margin:30px auto;padding:0 18px}
            h1{font-family:Georgia,serif;font-size:40px;margin-bottom:5px;color:#e1bb71}
            h2{font-size:19px;margin:12px 0}p,small{color:#b5b5b5}
            .layout{display:grid;grid-template-columns:1fr 1fr;gap:16px}
            .panel{border:1px solid #414349;border-radius:12px;background:#1b222b;padding:18px}
            .wide{grid-column:1 / -1}.chips{display:flex;gap:12px;flex-wrap:wrap}
            .chip{background:#293340;padding:12px 16px;border-radius:9px}
            form{display:flex;gap:10px}input{flex:1;min-width:0;padding:14px;border-radius:8px;background:#111923;color:#fff;border:1px solid #626d7a}
            button{border:0;background:#d9b16c;color:#171b20;padding:14px 18px;font-weight:bold;border-radius:8px;cursor:pointer}
            li{margin:8px 0}pre{white-space:pre-wrap;overflow-wrap:anywhere;line-height:1.5}
            @media(max-width:720px){.layout{grid-template-columns:1fr}.wide{grid-column:auto}form{flex-direction:column}}
            </style></head><body><main><h1>Wild Deck</h1><p>Human-play alpha · World round
            """);
        html.append(state.round()).append(" · Single shared demo session (no accounts)</p>");
        html.append("<div class='layout'><section class='panel wide'><h2>Kingdom Treasury</h2><div class='chips'>");
        player.resources().snapshot().forEach((resource,value)->html.append("<div class='chip'>").append(esc(resource.name())).append(": <b>").append(value).append("</b></div>"));
        html.append("</div></section><section class='panel wide'><h2>Give an order</h2><form method='POST' action='/action'>")
            .append("<input name='command' maxlength='250' required placeholder='e.g. deploy Beastmaster, explore, auto mine on'>")
            .append("<button>Issue order</button></form><pre>").append(esc(lastMessage)).append("</pre>")
            .append("<small>Draw once per round; deploy any cards. Type 'next round' to advance production.</small></section>");
        html.append("<section class='panel'><h2>Your Hand (").append(player.hand().size()).append(")</h2><ul>");
        for(CardInstance c:player.hand()) html.append("<li>").append(esc(c.definition().name())).append("</li>");
        html.append("</ul></section><section class='panel'><h2>Deployed Cards (").append(player.kingdom().size()).append(")</h2><ul>");
        for(CardInstance c:player.kingdom()) html.append("<li>").append(esc(c.definition().name())).append("</li>");
        html.append("</ul></section>");
        html.append("<section class='panel wide'><h2>Kingdom Management</h2><pre>")
            .append(esc(session.management().dashboard(state,session.humanPlayerId())))
            .append("</pre></section></div></main></body></html>");
        return html.toString();
    }
    private static String esc(String text) {return text.replace("&","&amp;").replace("<","&lt;")
        .replace(">","&gt;").replace("\"","&quot;").replace("'","&#39;");}
}
