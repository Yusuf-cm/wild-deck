package com.wilddeck.app;

import com.wilddeck.ai.*;
import com.wilddeck.engine.*;

import java.util.*;

public final class PlayableAlphaCli {

    public static void main(String[] args) {
        long seed = parseSeed(args);
        WildDeckAiService groq = buildGroqIfConfigured();
        PlayableSession session = PlayableSession.standard(seed,groq);

        System.out.println("Wild Deck Playable Alpha");
        System.out.println("Seed: " + seed);
        System.out.println(groq == null
                ? "AI: offline deterministic fallback (set GROQ_API_KEY for Groq)"
                : "AI: Groq enabled");
        System.out.println("Type 'help' for commands.\n");

        Scanner scanner = new Scanner(System.in);

        while (session.state().winner().isEmpty()) {
            if (!session.humanTurn()) {
                PlayerState ai = session.state().player(session.currentPlayerId());
                System.out.println("\n--- " + ai.name() + "'s turn ---");
                ActionExecutionResult result = session.runCurrentAiTurn();
                System.out.println(result.message());
                session.endTurn();
                continue;
            }

            System.out.println("\n--- Your turn | Round " + session.state().round() + " ---");
            boolean end = false;
            while (!end && session.state().winner().isEmpty()) {
                System.out.print("> ");
                if (!scanner.hasNextLine()) return;
                String line = scanner.nextLine().trim();
                if (line.isBlank()) continue;

                try {
                    end = handle(session,line,groq);
                } catch (RuntimeException e) {
                    System.out.println("Error: " + e.getMessage());
                }
            }
        }

        PlayerState winner = session.state().winner().orElseThrow();
        System.out.println("\nWINNER: " + winner.name());
    }

    private static boolean handle(
            PlayableSession session,
            String line,
            WildDeckAiService groq
    ) {
        String[] parts = line.split("\\s+");
        String command = parts[0].toLowerCase(Locale.ROOT);
        PlayerState human = session.state().player(session.humanPlayerId());

        switch (command) {
            case "help" -> printHelp();
            case "status" -> printStatus(session.state(),human);
            case "hand" -> printCards("HAND",human.hand());
            case "board" -> printBoard(session.state(),human);
            case "events" -> printEvents(session.state(),human.id());
            case "draw" -> print(session.executor().draw(session.state(),human.id()));
            case "play" -> {
                require(parts.length >= 2,"play <hand-index> [hidden]");
                CardInstance card = at(human.hand(),integer(parts[1]));
                boolean hidden = parts.length >= 3 && parts[2].equalsIgnoreCase("hidden");
                print(session.executor().play(session.state(),human.id(),card.id(),hidden));
            }
            case "attack" -> {
                require(parts.length >= 4,"attack <your-board-index> <opponent-id> <target-index>");
                CardInstance attacker = at(List.copyOf(human.kingdom()),integer(parts[1]));
                PlayerState enemy = session.state().player(parts[2]);
                CardInstance target = at(List.copyOf(enemy.kingdom()),integer(parts[3]));
                print(session.executor().attack(session.state(),human.id(),attacker.id(),target.id()));
            }
            case "regen" -> {
                require(parts.length >= 2,"regen <your-board-index>");
                CardInstance card = at(List.copyOf(human.kingdom()),integer(parts[1]));
                print(session.executor().regenerate(session.state(),human.id(),card.id()));
            }
            case "heal" -> {
                require(parts.length >= 3,"heal <healer-index> <target-index>");
                List<CardInstance> board = List.copyOf(human.kingdom());
                print(session.executor().heal(
                        session.state(),human.id(),
                        at(board,integer(parts[1])).id(),
                        at(board,integer(parts[2])).id()));
            }
            case "occupy" -> {
                require(parts.length >= 4,"occupy <your-board-index> <opponent-id> <target-index>");
                CardInstance occupier = at(List.copyOf(human.kingdom()),integer(parts[1]));
                PlayerState enemy = session.state().player(parts[2]);
                CardInstance target = at(List.copyOf(enemy.kingdom()),integer(parts[3]));
                print(session.executor().occupy(
                        session.state(),human.id(),occupier.id(),target.id()));
            }
            case "route" -> {
                require(parts.length >= 4,
                        "route <tunnel|infiltration> <opponent-id> <your-board-index> [more-indexes]");
                AccessRouteType type = switch (parts[1].toLowerCase(Locale.ROOT)) {
                    case "tunnel" -> AccessRouteType.TUNNEL;
                    case "infiltration" -> AccessRouteType.INFILTRATION;
                    default -> throw new IllegalArgumentException("route type must be tunnel or infiltration");
                };
                List<CardInstance> board = List.copyOf(human.kingdom());
                List<String> ids = new ArrayList<>();
                for (int i=3;i<parts.length;i++) ids.add(at(board,integer(parts[i])).id());
                print(session.executor().route(
                        session.state(),human.id(),parts[2],type,ids,true));
            }
            case "attach" -> {
                require(parts.length >= 3,"attach <equipment-index> <target-index>");
                List<CardInstance> board = List.copyOf(human.kingdom());
                print(session.executor().attach(
                        session.state(),human.id(),
                        at(board,integer(parts[1])).id(),
                        at(board,integer(parts[2])).id()));
            }
            case "conquer" -> {
                require(parts.length >= 2,"conquer <opponent-id>");
                print(session.executor().conquer(session.state(),human.id(),parts[1]));
            }
            case "annihilate" -> {
                require(parts.length >= 2,"annihilate <opponent-id>");
                print(session.executor().annihilate(session.state(),human.id(),parts[1]));
            }
            case "say" -> {
                if (groq == null) {
                    System.out.println("Groq is not configured. Set GROQ_API_KEY.");
                } else {
                    String natural = line.substring(line.indexOf(' ') + 1);
                    AiActionProposal proposal = groq.translatePlayerCommand(
                            session.state(),human.id(),natural);
                    System.out.println("Translated: " + proposal.kind()
                            + (proposal.verb() == null ? "" : " / " + proposal.verb()));
                    print(session.executor().execute(session.state(),human.id(),proposal));
                }
            }
            case "end" -> {
                print(session.endTurn());
                return true;
            }
            case "quit", "exit" -> System.exit(0);
            default -> System.out.println("Unknown command. Type 'help'.");
        }
        return false;
    }

    private static void printHelp() {
        System.out.println("""
            status
            hand
            board
            events
            draw
            play <hand-index> [hidden]
            attack <your-board-index> <opponent-id> <target-index>
            regen <your-board-index>
            heal <healer-index> <target-index>
            occupy <your-board-index> <opponent-id> <target-index>
            route <tunnel|infiltration> <opponent-id> <your-board-index> [more-indexes]
            attach <equipment-index> <target-index>
            conquer <opponent-id>
            annihilate <opponent-id>
            say <natural language command>     (Groq only)
            end
            quit
            """);
    }

    private static void printStatus(GameState state,PlayerState human) {
        System.out.println("Round " + state.round()
                + " | Deck " + state.deckSize()
                + " | Resources " + human.resources().snapshot());
        for (PlayerState player : state.players()) {
            System.out.println(player.id() + " - " + player.name()
                    + " | " + player.kingdomStatus()
                    + " | hand=" + (player.id().equals(human.id())
                        ? player.hand().size() : "?")
                    + " | kingdom=" + player.kingdom().size());
        }
    }

    private static void printBoard(GameState state,PlayerState human) {
        printCards("YOUR KINGDOM",List.copyOf(human.kingdom()));
        for (PlayerState player : state.players()) {
            if (player.id().equals(human.id())) continue;
            List<CardInstance> known = player.kingdom().stream()
                    .filter(c -> c.visibility() == Visibility.PUBLIC || human.knows(c.id()))
                    .toList();
            printCards(player.name().toUpperCase(Locale.ROOT) + " - KNOWN",known);
        }
    }

    private static void printCards(String title,List<CardInstance> cards) {
        System.out.println("\n" + title);
        if (cards.isEmpty()) {
            System.out.println("  (empty)");
            return;
        }
        for (int i=0;i<cards.size();i++) {
            CardInstance c = cards.get(i);
            System.out.printf(
                    "  [%d] %s | STR %s | dmg %d | %s | %s%n",
                    i,c.definition().name(),
                    c.definition().strength() == null ? "-" : c.definition().strength(),
                    c.damage(),c.definition().capabilities(),c.states());
        }
    }

    private static void printEvents(GameState state,String viewerId) {
        for (GameEvent event : state.visibleEvents(viewerId,15)) {
            System.out.println("R" + event.round() + " " + event.type() + " - " + event.summary());
        }
    }

    private static WildDeckAiService buildGroqIfConfigured() {
        GroqConfig config = GroqConfig.fromEnvironment();
        if (config.apiKey() == null || config.apiKey().isBlank()) return null;
        return new WildDeckAiService(new GroqClient(config),config);
    }

    private static long parseSeed(String[] args) {
        if (args.length == 0) return 42L;
        try { return Long.parseLong(args[0]); }
        catch (NumberFormatException e) { return 42L; }
    }

    private static int integer(String value) {
        try { return Integer.parseInt(value); }
        catch (NumberFormatException e) {
            throw new IllegalArgumentException("expected a numeric index");
        }
    }

    private static CardInstance at(List<CardInstance> cards,int index) {
        if (index < 0 || index >= cards.size()) {
            throw new IllegalArgumentException("card index out of range");
        }
        return cards.get(index);
    }

    private static void require(boolean condition,String usage) {
        if (!condition) throw new IllegalArgumentException("Usage: " + usage);
    }

    private static void print(ActionExecutionResult result) {
        System.out.println((result.success() ? "OK: " : "NO: ") + result.message());
    }
}
