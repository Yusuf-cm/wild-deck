package com.wilddeck.app;

import java.util.*;

/** Human-controlled Player kingdom; three other kingdoms belong to the match AI. */
public final class RndContinentCli {
 private final RndMatchEngine match;
 private final Scanner input;
 public RndContinentCli(long seed,Scanner input){this.match=RndMatchEngine.start(seed);this.input=input;}
 public static void main(String[] args){
  long seed=args.length>0?Long.parseLong(args[0]):20261010L;
  new RndContinentCli(seed,new Scanner(System.in)).run();
 }
 public String dashboard(){
  StringBuilder out=new StringBuilder();
  out.append("\n=============== WILD DECK | CONTINENT ===============\n");
  out.append("Round ").append(match.round()).append(" | Active: ").append(match.activeSeat())
    .append(" | Draw pile: ").append(match.deckSize()).append("\n");
  out.append(RndContinent.map()).append("\n");
  out.append("--------------- FOUR KINGDOMS -------------------------\n");
  for(String id:List.of("player","asha","brian","mira")){
   RndMatchEngine.Seat seat=match.seat(id);
   out.append(id.toUpperCase(Locale.ROOT)).append(" | Capital: ").append(RndContinent.capital(id))
     .append(" | Board: ").append(seat.board.values().stream().map(RndMatchEngine.Card::name).toList())
     .append(" | Hand: ").append(id.equals("player")?seat.hand.size()+" yours":seat.hand.size()+" hidden")
     .append(" cards\n");
   if(id.equals("player")){
    out.append("  Treasury: Gold=").append(seat.gold).append(" Wealth=").append(seat.wealth)
      .append(" Mana=").append(seat.mana).append(" | Goods=").append(seat.goods).append("\n");
    out.append("  Your hand: ").append(seat.hand.values().stream()
      .map(c->c.name()+" ["+c.id()+"]").toList()).append("\n");
    out.append("  Standing orders: ").append(seat.orders).append("\n");
   }
  }
  out.append("-------------------------------------------------------\n")
    .append("Commands: draw, deploy <card>, pass, look, atlas, history,\n")
    .append("  scry <kingdom>, attack <kingdom>, trade <kingdom> <terms>,\n")
    .append("  negotiate <kingdom> <terms>, assign <card> to <order>, quit\n");
  return out.toString();
 }
 public String submit(String command){
  String c=command.trim();
  if(c.equalsIgnoreCase("atlas"))return RndContinent.atlas();
  if(c.equalsIgnoreCase("history"))return String.join("\n",match.events());
  if(c.equalsIgnoreCase("look")||c.equalsIgnoreCase("status"))return dashboard();
  if(c.toLowerCase(Locale.ROOT).startsWith("kingdom ")){
   String id=c.substring(8).trim().toLowerCase(Locale.ROOT);
   if(!List.of("player","asha","brian","mira").contains(id))return "Unknown kingdom";
   RndMatchEngine.Seat s=match.seat(id);
   // Public inspection: do not route opponent requests through match.view(opponent).
   return id+" @ "+RndContinent.capital(id)+
     " | Board: "+s.board.values().stream().map(RndMatchEngine.Card::name).toList()+
     " | Cards held: "+s.hand.size()+" (hidden unless yours)";
  }
  if(!match.activeSeat().equals("player"))return "Wait for opponent turns to finish";
  RndMatchEngine.Result result=match.command("player",c);
  StringBuilder out=new StringBuilder(result.message());
  if(result.success() && (c.equalsIgnoreCase("pass")||c.equalsIgnoreCase("end turn"))){
   for(String event:match.takeOpponentTurns())out.append("\n > ").append(event);
   out.append("\nNew round: ").append(match.round());
  }
  return out.toString();
 }
 public void run(){
  System.out.println("WILD DECK: FOUR KINGDOMS | Seeded test continent");
  System.out.println("This is an R&D CLI, not a complete warfare simulator.");
  System.out.println(dashboard());
  while(input.hasNextLine()){
   System.out.print("\nYour command > ");
   String line=input.nextLine().trim();
   if(line.equalsIgnoreCase("quit")||line.equalsIgnoreCase("exit"))break;
   System.out.println(submit(line));
   System.out.println(dashboard());
  }
 }
 RndMatchEngine engine(){return match;}
}
