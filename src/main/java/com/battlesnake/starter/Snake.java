package com.battlesnake.starter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

import static spark.Spark.*;

/**
 * GODMODE SNAKE v22.0 - MINIMAX ENGINE
 */
public class Snake {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Logger LOG = LoggerFactory.getLogger(Snake.class);

    // --- TUNING CONSTANTS ---
    private static final int TIMEOUT_MS = 350; // Leave 150ms buffer
    private static final int MAX_DEPTH = 12;   // Safety cap
    private static final int MIN_DEPTH = 3;    // Ensure we look at least this far

    // Evaluator Weights
    private static final double W_SURVIVAL = 1_000_000.0;
    private static final double W_SPACE = 10.0; // Per square of control
    private static final double W_FOOD = 200.0;
    private static final double W_HEALTH = 1.0;
    private static final double W_AGGRESSION = 50.0; // Per square closer to victim

    public static void main(String[] args) {
        String port = System.getProperty("PORT", "8082");
        port(Integer.parseInt(port));
        get("/", (req, res) -> JSON.writeValueAsString(index()));
        post("/start", (req, res) -> "{}");
        post("/move", (req, res) -> JSON.writeValueAsString(move(JSON.readTree(req.body()))));
        post("/end", (req, res) -> "{}");
    }

    static Map<String, String> index() {
        Map<String, String> r = new HashMap<>();
        r.put("apiversion", "1");
        r.put("author", "GODMODE-V22");
        r.put("color", "#00FF00"); // Matrix Green
        r.put("head", "smart-caterpillar");
        r.put("tail", "sharp");
        return r;
    }

    // --- GAME ENGINE ---

    static Map<String, String> move(JsonNode root) {
        long startTime = System.currentTimeMillis();
        GameState initialState = new GameState(root);
        
        String bestMove = "up";
        double bestScore = -Double.MAX_VALUE;
        
        // Iterative Deepening
        // Start at depth 1, go deeper until panic time
        int reachedDepth = 0;
        
        // Move ordering: Try likely good moves first to improve pruning
        // We will store the best move from previous depth to try first in next depth
        
        for (int depth = 1; depth <= MAX_DEPTH; depth++) {
            if (System.currentTimeMillis() - startTime > TIMEOUT_MS) break;
            
            try {
                // Root Search
                double maxVal = -Double.MAX_VALUE;
                String currentLevelBest = null;
                
                // My possible moves
                List<MoveScore> moves = new ArrayList<>();
                for (String m : new String[]{"up", "down", "left", "right"}) {
                    if (System.currentTimeMillis() - startTime > TIMEOUT_MS) throw new RuntimeException("Timeout");
                    
                    GameState next = initialState.simulate(m);
                    if (next == null) {
                        moves.add(new MoveScore(m, -Double.MAX_VALUE)); // Wall/Self-Hit instant death
                        continue;
                    }
                    
                    // Minimax call
                    double val = minimax(next, depth - 1, -Double.MAX_VALUE, Double.MAX_VALUE, false, startTime);
                    moves.add(new MoveScore(m, val));
                    
                    if (val > maxVal) {
                        maxVal = val;
                        currentLevelBest = m;
                    }
                }
                
                // If we found a valid move this depth, update global best
                if (currentLevelBest != null) {
                    bestMove = currentLevelBest;
                    bestScore = maxVal;
                    reachedDepth = depth;
                }
                
                // Verify: If we see inevitable death (-Infinity) at this depth, 
                // stop if we had a better move at shallower depth? 
                // No, a shallower "safe" move might be a trap detected only at this depth.
                // Trust the deepest complete search.
                
            } catch (RuntimeException e) {
                // Timeout during this depth, discard results of this partial depth
                break;
            }
        }
        
        LOG.info("Turn {}: Depth {} Best {} Score {}", initialState.turn, reachedDepth, bestMove, bestScore);
        
        Map<String, String> res = new HashMap<>();
        res.put("move", bestMove);
        return res;
    }
    
    static double minimax(GameState state, int depth, double alpha, double beta, boolean maxPlayer, long startTime) {
        if (System.currentTimeMillis() - startTime > TIMEOUT_MS) throw new RuntimeException("Timeout");
        
        // Terminal or Leaf
        if (depth == 0 || state.gameOver) {
            return evaluate(state);
        }
        
        if (maxPlayer) {
            double maxEval = -Double.MAX_VALUE;
            // My moves
            for (String move : new String[]{"up", "down", "left", "right"}) {
                GameState next = state.simulate(move);
                double eval = (next == null) ? -Double.MAX_VALUE : minimax(next, depth - 1, alpha, beta, false, startTime);
                maxEval = Math.max(maxEval, eval);
                alpha = Math.max(alpha, eval);
                if (beta <= alpha) break; // Prune
            }
            return maxEval;
        } else {
            // Enemy moves
            // SIMPLIFICATION: Paranoid. 
            // Assume the world moves to minimize our score.
            // Model enemies as one logical "Enemy" entity that plays the worst move for us.
            // (Simulating all permutations of 3 enemies is too expensive O(3^N * 4^N)).
            
            // To be accurate but fast: Advance closest enemy towards us/food, others randomly?
            // "Paranoid Minimax": We assume the board state evolves in the worst way.
            // For now, let's just step the world forward.
            // Since we can't control enemies, we have to account for their BEST move (which is BAD for us).
            // But checking ALL enemy moves is crazy.
            // Strategy: Simulate a "Turn" where all enemies move roughly towards food or us.
            
            // BETTER: We don't branch on enemy moves. We just "Simulate World Step" deterministically 
            // or semi-deterministically to get to the next "Max" node.
            // Taking 1 probable outcome for opponents allows us to search deeper (Depth 10 vs Depth 3).
            // Given the heuristic nature, Depth is king.
            
            GameState next = state.simulateWorldStep();
            // After enemies move, it's our turn again (depth - 1)
            // But wait, standard Minimax decreases depth on *our* move. 
            // Let's treat (Us Move + World Move) as 1 Depth layer.
            
            // So: minimax(next, depth - 1 ...) 
            // Wait, the previous block called simulate(move) which creates a state where WE moved but enemies haven't.
            // So this 'else' block represents the enemies reacting.
            
            return minimax(next, depth, alpha, beta, true, startTime); 
            // Note: Depth not decremented here because we treat (Our Move -> Enemy Move) as 1 turn.
            // The recursions is:
            // Root (Max) -> call minimax(stateAfterMyMove)
            //   Node (Min/World) -> call minimax(stateAfterEnemyMoves)
            //     Node (Max) -> depth decrements.
        }
    }

    // --- EVALUATION ---
    
    static double evaluate(GameState state) {
        if (!state.alive) return -1_000_000_000.0; // Dead
        
        double score = 0;
        
        // 1. Survival Bonus
        score += W_SURVIVAL; 
        
        // 2. Health
        // We want to be healthy but not obsessed.
        if (state.myHealth < 20) score -= (20 - state.myHealth) * 1000; // Panic starvation
        
        // 3. Space (Flood Fill)
        // Crucial for not getting trapped.
        // NOTE: state.myHead is the START of flood fill.
        int space = floodFill(state, state.myHead);
        if (space < state.myBody.size()) {
            return -10_000_000.0 + (space * 100); // Almost dead, delay it
        }
        score += space * W_SPACE;
        
        // 4. Food & Hazards (Old Heuristics)
        int minFoodDist = 1000;
        Point bestFood = null;
        for (Point f : state.food) {
            int d = dist(state.myHead, f);
            if (d < minFoodDist) { minFoodDist = d; bestFood = f; }
        }
        
        // Hazard Penalty
        if (state.isHazard(state.myHead)) {
            score -= 1000 * state.hazardDamage; 
        }

        // Danger Penalty (Head to Head)
        for (Enemy e : state.enemies) {
             int d = dist(state.myHead, e.head);
             if (d <= 1) { // Adjacent! check sizes
                 if (e.body.size() >= state.myBody.size()) {
                     score -= 50_000.0; // DANGER
                 } else {
                     score += 10_000.0; // KILL CHANCE
                 }
             }
        }
        
        // Food Score
        double foodScore = 0;
        if (bestFood != null) {
            double greed = W_FOOD;
            
            // Hunger Logic
            if (state.myHealth < 40) greed *= 4; // Starving
            else if (state.myHealth < 70) greed *= 2; // Hungry
            
            foodScore = greed / (minFoodDist + 1);
            
            // Food Denial (Old Logic)
            Enemy closestE = null;
            int closestEDist = 1000;
            for (Enemy e : state.enemies) {
                int ed = dist(e.head, bestFood);
                if (ed < closestEDist) { closestEDist = ed; closestE = e; }
            }
            if (closestE != null && closestE.body.size() < state.myBody.size() && minFoodDist <= closestEDist) {
                 foodScore += 1000; // DENIAL BONUS
            }
        }
        score += foodScore;
        
        // 5. Aggression / Duel
        if (state.enemies.size() == 1) {
            Enemy e = state.enemies.get(0);
            int distToEnemy = dist(state.myHead, e.head);
            if (state.myBody.size() > e.body.size()) {
               // Crowd them (Hunter Mode)
               score -= distToEnemy * W_AGGRESSION;
            } else {
               // Run / Survival Mode (but handled by Space check mostly)
               score += distToEnemy * W_AGGRESSION;
            }
        }
        
        return score;
    }

    // --- FLOOD FILL ---
    static int floodFill(GameState state, Point start) {
        if (!state.isValid(start)) return 0;
        // Don't check isBlocked for start, since it's the snake's head
        
        boolean[][] visited = new boolean[state.width][state.height];
        Queue<Point> q = new LinkedList<>();
        q.add(start);
        visited[start.x][start.y] = true;
        
        int count = 0;
        int max = state.myBody.size() * 3; // Optimization cap
        
        while(!q.isEmpty()) {
            Point p = q.poll();
            count++;
            if (count >= max) return max;
            
            for (Point n : state.neighbors(p)) {
                if (!visited[n.x][n.y] && !state.isBlocked(n)) {
                    visited[n.x][n.y] = true;
                    q.add(n);
                }
            }
        }
        return count;
    }

    // --- DATA STRUCTURES ---

    static class GameState {
        int width, height, turn;
        boolean alive = true;
        boolean gameOver = false;
        
        Point myHead;
        List<Point> myBody;
        int myHealth;
        
        List<Enemy> enemies;
        List<Point> food;
        List<Point> hazards;
        
        // Board cache
        boolean[][] walls; // true if blocked

        // Parse from JSON
        GameState(JsonNode root) {
            JsonNode board = root.get("board");
            width = board.get("width").asInt();
            height = board.get("height").asInt();
            turn = root.get("turn").asInt();
            
            food = new ArrayList<>();
            for (JsonNode f : board.get("food")) food.add(new Point(f.get("x").asInt(), f.get("y").asInt()));
            
            hazards = new ArrayList<>();
            if (board.has("hazards")) {
                for (JsonNode h : board.get("hazards")) hazards.add(new Point(h.get("x").asInt(), h.get("y").asInt()));
            }
            
            enemies = new ArrayList<>();
            walls = new boolean[width][height];
            
            JsonNode you = root.get("you");
            myBody = parseBody(you.get("body"));
            myHead = myBody.get(0);
            myHealth = you.get("health").asInt();
            
            for (JsonNode s : board.get("snakes")) {
                if (s.get("id").asText().equals(you.get("id").asText())) continue;
                List<Point> body = parseBody(s.get("body"));
                enemies.add(new Enemy(s.get("id").asText(), body));
                // Mark enemies as walls
                for (Point p : body) if(isValid(p)) walls[p.x][p.y] = true; // Tail logic? Simplified: Blocked.
            }
            
            // Mark myself (except head, which moves)
            for (int i=1; i<myBody.size(); i++) { // Head not blocked for *move* checks usually, but here 'walls' is static obs
               Point p = myBody.get(i);
               if(isValid(p)) walls[p.x][p.y] = true;
            }
            // Actually, for flood fill, my head is a visited node.
        }
        
        // Copy Constructor for simulation
        GameState(GameState other) {
            this.width = other.width;
            this.height = other.height;
            this.turn = other.turn;
            this.alive = other.alive;
            this.gameOver = other.gameOver;
            this.myHead = other.myHead;
            this.myBody = new ArrayList<>(other.myBody);
            this.myHealth = other.myHealth;
            this.enemies = new ArrayList<>();
            for(Enemy e : other.enemies) this.enemies.add(new Enemy(e));
            this.food = new ArrayList<>(other.food);
            this.hazards = new ArrayList<>(other.hazards);
            this.walls = new boolean[width][height];
            for(int x=0; x<width; x++) System.arraycopy(other.walls[x], 0, this.walls[x], 0, height);
        }

        List<Point> parseBody(JsonNode bodyNode) {
            List<Point> body = new ArrayList<>();
            for (JsonNode b : bodyNode) body.add(new Point(b.get("x").asInt(), b.get("y").asInt()));
            return body;
        }

        // Simulate MY move: returns state where I moved, enemies haven't yet
        GameState simulate(String move) {
            GameState next = new GameState(this);
            Point d = moveDelta(move);
            Point newHead = new Point(myHead.x + d.x, myHead.y + d.y);
            
            // Wall Check
            if (!isValid(newHead)) return null; // Instant death
            if (isBlocked(newHead)) return null; // Hit snake or wall
            
            // Move Head
            next.myHead = newHead;
            next.myBody.add(0, newHead);
            
            // Food Check
            boolean ate = false;
            // Remove food if eaten
            for (int i=0; i<next.food.size(); i++) {
                if (next.food.get(i).equals(newHead)) {
                    next.food.remove(i);
                    ate = true;
                    next.myHealth = 100;
                    break;
                }
            }
            
            if (!ate) {
                // Remove Tail
                Point tail = next.myBody.remove(next.myBody.size()-1);
                // Unblock tail in 'walls'?
                // For simplified sim, walls array is static, but dynamic checks use Body lists.
                // We rely on isBlocked() checking lists.
                next.myHealth--;
            }
            
            if (next.myHealth <= 0) next.alive = false;
            
            return next;
        }
        
        // Simulate WORLD Step: Enemies move, hazards hit
        GameState simulateWorldStep() {
            GameState next = new GameState(this);
            next.turn++;
            
            // Simplistic Enemy AI: Move towards closest food or random valid
            // We can't perfectly predict, but assuming they survive is better than assuming they die.
            for (Enemy e : next.enemies) {
                // Find a valid move for enemy
                // 1. Closest Food
                Point target = null;
                int minDist = 1000;
                for (Point f : next.food) {
                    int d = dist(e.head, f);
                    if (d < minDist) { minDist = d; target = f; }
                }
                
                // 2. Move towards target
                Point bestMove = null;
                // Try dirs
                for (Point n : neighbors(e.head)) {
                    if (!isBlocked(n) && !n.equals(next.myHead)) { // Don't run into me yet
                        if (bestMove == null) bestMove = n;
                        else if (target != null && dist(n, target) < dist(bestMove, target)) bestMove = n;
                    }
                }
                
                // If stuck, die
                if (bestMove == null) {
                    // Enemy dies (remove them? For now just keep them static or remove)
                    // Removing is cleaner
                    // e.alive = false;
                } else {
                    // Update enemy
                    e.head = bestMove;
                    e.body.add(0, bestMove);
                    boolean ate = false;
                    for (int i=0; i<next.food.size(); i++) {
                        if (next.food.get(i).equals(bestMove)) {
                            next.food.remove(i);
                            ate = true;
                            // Enemy grows
                            break;
                        }
                    }
                    if (!ate) e.body.remove(e.body.size()-1);
                }
            }
            
            // Check Collisions (Head to Head)
            // If my head hits enemy head
            // Or enemy head hits my body
            
            // 1. Me hitting enemy body
            for (Enemy e : next.enemies) {
                for (Point p : e.body) {
                    if (p.equals(next.myHead) && !p.equals(e.head)) { // Body hit
                        next.alive = false;
                    }
                }
                
                // Head to Head
                if (e.head.equals(next.myHead)) {
                    if (e.body.size() >= next.myBody.size()) {
                        next.alive = false; // I lost
                    } else {
                        // I win, enemy dies
                        // Remove enemy?
                        // e.alive = false;
                    }
                }
            }
            
            return next;
        }

        boolean isValid(Point p) {
            return p.x >= 0 && p.x < width && p.y >= 0 && p.y < height;
        }
        
        boolean isBlocked(Point p) {
            if (!isValid(p)) return true;
            // Check my body
            for (int i=0; i<myBody.size()-1; i++) { // Ignore tail? No, simulated state has handled tail removal.
                if (myBody.get(i).equals(p)) return true;
            }
            // Check enemies
            for (Enemy e : enemies) {
                for (Point b : e.body) {
                    if (b.equals(p)) return true;
                }
            }
            return false;
        }
        
        List<Point> neighbors(Point p) {
            List<Point> res = new ArrayList<>();
            int[][] dirs = {{0,1}, {0,-1}, {1,0}, {-1,0}};
            for (int[] d : dirs) {
                Point n = new Point(p.x+d[0], p.y+d[1]);
                if (isValid(n)) res.add(n);
            }
            return res;
        }
        
        Point moveDelta(String m) {
            if (m.equals("up")) return new Point(0, 1);
            if (m.equals("down")) return new Point(0, -1);
            if (m.equals("left")) return new Point(-1, 0);
            return new Point(1, 0); // right
        }
    }

    static class Point {
        int x, y;
        Point(int x, int y) { this.x=x; this.y=y; }
        @Override public boolean equals(Object o) {
            if (this==o) return true;
            if (!(o instanceof Point)) return false;
            Point p = (Point)o;
            return x==p.x && y==p.y;
        }
    }
    
    static int dist(Point a, Point b) {
        return Math.abs(a.x - b.x) + Math.abs(a.y - b.y);
    }

    static class Enemy {
        String id;
        List<Point> body;
        Point head;
        Enemy(String id, List<Point> body) { this.id=id; this.body=body; this.head=body.get(0); }
        Enemy(Enemy other) {
            this.id = other.id;
            this.body = new ArrayList<>(other.body);
            this.head = other.head;
        }
    }
    
    static class MoveScore {
        String move;
        double score;
        MoveScore(String m, double s) { move=m; score=s; }
    }
}
