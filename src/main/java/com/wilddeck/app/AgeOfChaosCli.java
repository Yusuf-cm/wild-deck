package com.wilddeck.app;
import java.util.*;

/** Local interactive two-human-kingdom and two simulated-opponent test shell. */
public final class AgeOfChaosCli {
 private final AgeOfChaosEngine game=AgeOfChaosEngine.newGame();
 private final Scanner console;
 private final Random opponentChoices=new Random(20261011L);
 public AgeOfChaosCli(Scanner console){this.console=console;}
 public static void main(String[] args){new AgeOfChaosCli(new Scanner(System.in)).run();}
 private void runAiTurns(){
  while(AgeOfChaosEngine.AI_KINGDOMS.contains(game.turn())){
   String id=game.turn();
   AgeOfChaosEngine.Kingdom k=game.kingdom(id);
   String move="studies the board";
   if(opponentChoices.nextInt(5)!=0 && !k.hand.isEmpty()){
    var affordable=k.hand.values().stream()
      .filter(c->c.wealthCost()<=k.wealth && c.manaCost()<=k.mana).toList();
    if(!affordable.isEmpty()){
     var chosen=affordable.get(opponentChoices.nextInt(affordable.size()));
     var result=game.play(id,chosen.name());
     move=result.message();
    }
   }
   System.out.println("[RIVAL] "+move);
   game.pass(id);
  }
 }
 public void run(){
  System.out.println("WILD DECK — AGE OF CHAOS | Two kingdoms per side, seven opening cards each");
  System.out.println("Commands: hand, board, play <exact card name>, pass, quit");
  System.out.println("No card drawn or deployed yet. This CLI currently supports opening-card deployment and round production.");
  while(true){
   runAiTurns();
   String id=game.turn();
   System.out.println("\n"+game.dashboard(id));
   System.out.print(id+"> ");
   if(!console.hasNextLine())break;
   String command=console.nextLine().trim();
   if(command.equalsIgnoreCase("quit"))break;
   if(command.equalsIgnoreCase("hand")||command.equalsIgnoreCase("board"))continue;
   if(command.equalsIgnoreCase("pass")){System.out.println(game.pass(id).message());continue;}
   if(command.regionMatches(true,0,"play ",0,5)){
    System.out.println(game.play(id,command.substring(5).trim()).message());
    continue;
   }
   System.out.println("Unsupported command (feature not yet implemented)");
  }
 }
}
