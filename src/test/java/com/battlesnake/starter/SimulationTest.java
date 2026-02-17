package com.battlesnake.starter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

public class SimulationTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    public static void main(String[] args) {
        try {
            System.out.println("Running Simulation Test...");
            
            // 1. Create a dummy GameState
            ObjectNode root = JSON.createObjectNode();
            
            ObjectNode game = root.putObject("game");
            game.putObject("ruleset").put("name", "standard");
            
            root.put("turn", 0);
            
            ObjectNode board = root.putObject("board");
            board.put("width", 11);
            board.put("height", 11);
            board.putArray("food");
            board.putArray("hazards");
            board.putArray("snakes"); // Enemies
            
            ObjectNode you = root.putObject("you");
            you.put("id", "me");
            you.put("health", 100);
            you.put("name", "Me");
            you.put("latency", "0");
            
            // Snake body: Head at (5,5), Body at (5,4), Tail at (5,3)
            ObjectNode head = you.putObject("head");
            head.put("x", 5);
            head.put("y", 5);
            
            ArrayNode body = you.putArray("body");
            addPoint(body, 5, 5);
            addPoint(body, 5, 4);
            addPoint(body, 5, 3);
            
            // Initialize State
            Snake.GameState state = new Snake.GameState(root);
            
            // Verify Initial State
            System.out.println("Initial Head: " + state.myHead.x + "," + state.myHead.y);
            System.out.println("Blocked (5,5): " + state.blocked[5][5]);
            System.out.println("Blocked (5,4): " + state.blocked[5][4]);
            System.out.println("Blocked (5,3): " + state.blocked[5][3]);
            
            if (!state.blocked[5][5] || !state.blocked[5][4] || !state.blocked[5][3]) {
                throw new RuntimeException("FAIL: Body parts not blocked initially");
            }

            // 2. Advance Snake (Move UP to 5,6)
            // Expectation: Head moves to 5,6. Tail (5,3) becomes unblocked.
            Snake.Point nextMove = new Snake.Point(5, 6);
            state.advanceMySnake(nextMove);
            
            System.out.println("--- After Move UP (5,6) ---");
            System.out.println("New Head: " + state.myHead.x + "," + state.myHead.y);
            System.out.println("Blocked (5,6): " + state.blocked[5][6]); // New Head
            System.out.println("Blocked (5,5): " + state.blocked[5][5]); // Old Head (now body)
            System.out.println("Blocked (5,4): " + state.blocked[5][4]); // Old Body
            System.out.println("Blocked (5,3): " + state.blocked[5][3]); // Old Tail (SHOULD BE FALSE)

            if (!state.blocked[5][6]) throw new RuntimeException("FAIL: New head not blocked");
            if (state.blocked[5][3]) throw new RuntimeException("FAIL: Old tail still blocked! Simulation logic is broken.");
            
            System.out.println("SUCCESS: Tail was removed correctly.");

        } catch (Exception e) {
            e.printStackTrace();
            System.exit(1);
        }
    }

    private static void addPoint(ArrayNode arr, int x, int y) {
        ObjectNode p = arr.addObject();
        p.put("x", x);
        p.put("y", y);
    }
}
