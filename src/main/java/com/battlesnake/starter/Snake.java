package com.battlesnake.starter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

import static spark.Spark.*;

/**
 * GODMODE SNAKE v30.0 - OMNISCIENT
 * Unified Minimax + Iterative Deepening + Paranoid Search
 */
public class Snake {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Logger LOG = LoggerFactory.getLogger(Snake.class);

    private static final int[][] DIRS = {{0, 1}, {0, -1}, {-1, 0}, {1, 0}};
    private static final String[] DIR_NAMES = {"up", "down", "left", "right"};

    // --- SCORING CONSTANTS ---
    private static final double SCORE_MAX           = 1_000_000_000.0;
    private static final double SCORE_MIN           = -1_000_000_000.0;
    private static final double SCORE_WIN           = 100_000_000.0;
    private static final double SCORE_LOST          = -100_000_000.0;
    private static final double SCORE_EATING        = 1_000_000.0; 
    
    // Weights
    private static final double W_SPACE             = 10.0;
    private static final double W_MOBILITY          = 50.0;
    private static final double W_CENTER            = 20.0;
    private static final double W_DIST_ENEMY        = 5.0;

    // Time Management
    private static final long MAX_TIME_MS = 350; // Leave buffer for network
    private static long startTime;

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
        r.put("author", "GODMODE-V30-OMNISCIENT");
        r.put("color", "#4B0082"); // Indigo for Deep Thought
        r.put("head", "smart-caterpillar");
        r.put("tail", "curled");
        return r;
    }

    static Map<String, String> move(JsonNode root) {
        startTime = System.currentTimeMillis();
        GameState state = new GameState(root);
        
        String bestMove = iterativeDeepening(state);
        
        Map<String, String> res = new HashMap<>();
        res.put("move", bestMove);
        return res;
    }

    // ============================================================
    // SEARCH ENGINE (Iterative Deepening)
    // ============================================================

    private static String iterativeDeepening(GameState rootState) {
        String bestDir = "up";
        double bestScore = SCORE_MIN;
        
        // Initial fallback: Valid random move
        for (int i=0; i<4; i++) {
            Point p = rootState.myHead.add(DIRS[i]);
            if (rootState.isWrapped) p = rootState.wrap(p);
            if (rootState.isValid(p) && !rootState.isBlocked(p)) {
                bestDir = DIR_NAMES[i];
                break;
            }
        }

        int maxDepth = 12; // Cap depth to avoid stack overflow or timeouts
        
        for (int depth = 1; depth <= maxDepth; depth++) {
            if (System.currentTimeMillis() - startTime > MAX_TIME_MS) break; // Time's up

            String currentBestDir = null;
            double currentMax = SCORE_MIN;
            boolean searchComplete = true;

            // Root Evaluation (Layer 1)
            for (int i = 0; i < 4; i++) {
                if (System.currentTimeMillis() - startTime > MAX_TIME_MS) {
                    searchComplete = false; break;
                }

                Point next = rootState.myHead.add(DIRS[i]);
                if (rootState.isWrapped) next = rootState.wrap(next);

                // Immediate safety check
                if (!rootState.isValid(next) || rootState.isBlocked(next)) continue;
                
                // Head-to-Head Instant Death Check (Strict Safety from V2)
                boolean instantDeath = false;
                for(SnakeData e : rootState.aliveEnemies) {
                    if (manhattan(next, e.head, rootState.W, rootState.H, rootState.isWrapped) == 1) {
                         if (e.len >= rootState.myLen) { instantDeath = true; break; }
                    }
                }
                if (instantDeath) continue;

                // 1. Simulate My Move
                GameState nextState = rootState.simulateMyMove(next);
                if (nextState == null || nextState.myHealth <= 0) continue; 

                // 2. Search deeper (Minimax)
                double score = minimax(nextState, depth - 1, SCORE_MIN, SCORE_MAX, false);
                
                if (score > currentMax) {
                    currentMax = score;
                    currentBestDir = DIR_NAMES[i];
                }
            }

            if (searchComplete && currentBestDir != null) {
                bestDir = currentBestDir;
                bestScore = currentMax;
                LOG.info("Depth {} done. Score: {} Move: {}", depth, bestScore, bestDir);
            } else {
                LOG.info("Depth {} incomplete (Timeout).", depth);
                break;
            }
        }
        
        LOG.info("Selected Move: {}", bestDir);
        return bestDir;
    }

    // ============================================================
    // MINIMAX (Recursive)
    // ============================================================

    private static double minimax(GameState state, int depth, double alpha, double beta, boolean maximizing) {
        if (depth == 0 || state.isGameOver()) {
            return heuristic(state);
        }

        if (System.currentTimeMillis() - startTime > MAX_TIME_MS) return 0; // Abort

        if (maximizing) {
            // MY TURN
            double maxEval = SCORE_MIN;
            for (int i = 0; i < 4; i++) {
                Point next = state.myHead.add(DIRS[i]);
                if (state.isWrapped) next = state.wrap(next);
                
                if (!state.isValid(next) || state.isBlocked(next)) continue;
                
                // Head-to-Head Check (Pruning bad branches early)
                boolean instantDeath = false;
                for(SnakeData e : state.aliveEnemies) {
                    if (manhattan(next, e.head, state.W, state.H, state.isWrapped) == 1) {
                         if (e.len >= state.myLen) { instantDeath = true; break; }
                    }
                }
                if (instantDeath) continue;

                GameState child = state.simulateMyMove(next);
                if (child == null || child.myHealth <= 0) continue;

                double eval = minimax(child, depth - 1, alpha, beta, false);
                maxEval = Math.max(maxEval, eval);
                alpha = Math.max(alpha, eval);
                if (beta <= alpha) break;
            }
            return maxEval == SCORE_MIN ? SCORE_LOST + depth * 100 : maxEval; // If no moves, we lost

        } else {
            // ENEMY TURN (Paranoid Mode)
            // Simplified Paranoid: Assume they make a move that survives/eats.
            // We simulate "All enemies have moved 1 step". 
            // This is a "Chance Node" but deterministic for our simplified model.
            
            GameState child = state.simulateEnemiesMoving();
            if (child.myHealth <= 0) return SCORE_LOST; // PREDICTION: We died.

            // After enemies move, it's our turn again
            return minimax(child, depth - 1, alpha, beta, true);
        }
    }

    // ============================================================
    // HEURISTIC EVALUATION (The Leaf Node)
    // ============================================================

    private static double heuristic(GameState state) {
        if (state.myHealth <= 0) return SCORE_LOST;
        
        double score = 0;

        // 1. SURVIVAL SPACE (Flood Fill)
        int space = floodFill(state, state.myHead, state.hazardPoints);
        if (space < state.myLen) {
            return SCORE_LOST + space * 1000; // Trapped
        }
        score += Math.min(space, state.myLen * 2) * W_SPACE;

        // 2. FOOD (V2 Logic: Extreme Hunger)
        double foodVal = 0;
        int maxELen = 0;
        for (SnakeData e : state.aliveEnemies) maxELen = Math.max(maxELen, e.len);
        
        double bestFoodScore = 0;
        for(Point f : state.foods) {
             int dist = manhattan(state.myHead, f, state.W, state.H, state.isWrapped);
             double val = (state.myHealth < 40 ? 10_000_000 : 1000);
             if (state.myLen <= maxELen) val *= 5; // Grow if small
             
             double s = val / (dist * dist + 1);
             if (s > bestFoodScore) bestFoodScore = s;
        }
        score += bestFoodScore;

        // 3. CENTER BIAS (Anti-Spin)
        int cx = state.W / 2, cy = state.H / 2;
        int distToCenter = Math.abs(state.myHead.x - cx) + Math.abs(state.myHead.y - cy);
        score -= distToCenter * W_CENTER;

        // 4. MOBILITY & AGGRESSION
        int validMoves = 0;
        for (int[] d : DIRS) {
            Point n = state.myHead.add(d);
            if (state.isWrapped) n = state.wrap(n);
            if (state.isValid(n) && !state.isBlocked(n)) validMoves++;
        }
        score += validMoves * W_MOBILITY;

        // 5. SAFETY (Head-to-Head)
        for(SnakeData e: state.aliveEnemies) {
             int d = manhattan(state.myHead, e.head, state.W, state.H, state.isWrapped);
             if (d <= 2) { 
                 if (e.len >= state.myLen) score -= 500_000; 
                 else score += 100_000; 
             }
        }

        return score;
    }

    // ============================================================
    // HELPERS & DATA
    // ============================================================
    
    static int floodFill(GameState state, Point start, Set<Point> avoid) {
        boolean[][] visited = new boolean[state.W][state.H];
        for(int x=0; x<state.W; x++) 
            System.arraycopy(state.blocked[x], 0, visited[x], 0, state.H);
        for(Point p : avoid) if(state.isValid(p)) visited[p.x][p.y] = true;
            
        Queue<Point> q = new LinkedList<>();
        if (state.isValid(start) && !visited[start.x][start.y]) {
            q.add(start); visited[start.x][start.y] = true;
        }
        int count = 0;
        int max = state.myLen * 3;
        while (!q.isEmpty()) {
            Point p = q.poll(); count++;
            if (count >= max) return count;
            for (int[] d : DIRS) {
                Point n = p.add(d);
                if (state.isWrapped) n = state.wrap(n);
                if (state.isValid(n) && !visited[n.x][n.y]) {
                    visited[n.x][n.y] = true; q.add(n);
                }
            }
        }
        return count;
    }

    static int manhattan(Point a, Point b, int W, int H, boolean wrapped) {
        int dx = Math.abs(a.x - b.x), dy = Math.abs(a.y - b.y);
        if (wrapped) { dx = Math.min(dx, W - dx); dy = Math.min(dy, H - dy); }
        return dx + dy;
    }

    static class GameState {
        int W, H;
        boolean isWrapped;
        Point myHead;
        int myLen, myHealth;
        List<Point> myBody;
        List<SnakeData> aliveEnemies;
        List<Point> foods;
        boolean[][] blocked;
        boolean[][] hazards;
        Set<Point> hazardPoints;
        
        GameState() {} 

        GameState(JsonNode root) {
            JsonNode board = root.get("board");
            W = board.get("width").asInt();
            H = board.get("height").asInt();
            JsonNode you = root.get("you");
            myHealth = you.get("health").asInt();
            myBody = new ArrayList<>();
            for (JsonNode b : you.get("body")) myBody.add(new Point(b.get("x").asInt(), b.get("y").asInt()));
            myHead = myBody.get(0);
            myLen = myBody.size();
            
            JsonNode game = root.get("game");
            String r = (game != null && game.has("ruleset")) ? game.get("ruleset").get("name").asText() : "";
            isWrapped = r.contains("wrapped");

            foods = new ArrayList<>();
            for (JsonNode f : board.get("food")) foods.add(new Point(f.get("x").asInt(), f.get("y").asInt()));

            aliveEnemies = new ArrayList<>();
            blocked = new boolean[W][H];
            String myId = you.get("id").asText();
            for (JsonNode s : board.get("snakes")) {
                if (!s.get("id").asText().equals(myId)) {
                    List<Point> b = new ArrayList<>();
                    for (JsonNode n : s.get("body")) b.add(new Point(n.get("x").asInt(), n.get("y").asInt()));
                    
                    // Simple prediction for enemy head: assume they might move to any adjacent
                    // But for initial state, just store current data.
                    aliveEnemies.add(new SnakeData(s.get("id").asText(), b.size(), b.get(0), b, s.get("health").asInt()));
                    
                    for (int i=0; i<b.size()-1; i++) {
                        Point p = b.get(i);
                        if (isValid(p)) blocked[p.x][p.y] = true;
                    }
                }
            }
            
            for (int i=1; i<myBody.size()-1; i++) { // Head is not blocked for moving purposes
                Point p = myBody.get(i);
                if (isValid(p)) blocked[p.x][p.y] = true; 
            }
            
            hazards = new boolean[W][H];
            hazardPoints = new HashSet<>();
            if (board.has("hazards")) {
                for (JsonNode h : board.get("hazards")) {
                    Point p = new Point(h.get("x").asInt(), h.get("y").asInt());
                    if (isValid(p)) { hazards[p.x][p.y]=true; hazardPoints.add(p); }
                }
            }
        }
        
        boolean isValid(Point p) {
            if (isWrapped) return true;
            return p.x >= 0 && p.x < W && p.y >= 0 && p.y < H;
        }

        boolean isBlocked(Point p) {
            if (!isValid(p)) return true;
            return blocked[p.x][p.y];
        }
        
        Point wrap(Point p) {
            return new Point((p.x % W + W) % W, (p.y % H + H) % H);
        }

        boolean isGameOver() {
            return myHealth <= 0;
        }
        
        GameState simulateMyMove(Point nextHead) {
            GameState s = copy();
            s.myHead = nextHead;
            s.myBody.add(0, nextHead);
            s.myBody.remove(s.myBody.size()-1); 
            s.myHealth--; 
            
            if (s.isBlocked(nextHead)) { s.myHealth = 0; return s; }
            s.blocked[nextHead.x][nextHead.y] = true; // occupy new cell

            boolean ate = false;
            for (int i=0; i<s.foods.size(); i++) {
                if (s.foods.get(i).equals(nextHead)) {
                    s.myHealth = 100;
                    s.myLen++;
                    s.myBody.add(s.myBody.get(s.myBody.size()-1)); 
                    s.foods.remove(i);
                    ate = true;
                    break;
                }
            }
            if(!ate && s.myHealth <= 0) s.myHealth = 0; 

            return s;
        }

        GameState simulateEnemiesMoving() {
            // "World Move": Advances all enemies 1 step.
            // Assumption: Enemies move to closest food or center (Naive/Greedy).
            GameState s = this; // Optimization: In minimax, we often clone. but here we are in a 'child' node already.
            // Wait, simulateMyMove returned a copy. So we can mutate 'this' or return copy.
            // But we called 'simulateEnemiesMoving' on the state returned by 'simulateMyMove', which is already a deep copy.
            // So we can mutate it!
            
            // To be safe and correct logic:
            // For each enemy, pick a move.
            // Update their head, block new head.
            // Unblock tail.
            
            for (SnakeData e : s.aliveEnemies) {
                Point best = null;
                double minD = 9999;
                
                for (int[] d : DIRS) {
                    Point np = e.head.add(d);
                    if (s.isWrapped) np = s.wrap(np);
                    if (s.isValid(np) && !s.blocked[np.x][np.y]) {
                        double dist = 0;
                        if (!s.foods.isEmpty()) dist = manhattan(np, s.foods.get(0), W, H, isWrapped);
                        if (dist < minD) { minD = dist; best = np; }
                    }
                }
                
                if (best != null) {
                    s.blocked[best.x][best.y] = true;
                    e.head = best;
                    // Note: We are not consistently updating enemy bodies/tails here for performance, 
                    // just heads (most critical for collision).
                    // Collision Check
                    if (best.equals(s.myHead)) {
                        if (e.len >= s.myLen) s.myHealth = 0; 
                    }
                }
            }
            return s;
        }

        GameState copy() {
            GameState s = new GameState();
            s.W=W; s.H=H; s.isWrapped=isWrapped;
            s.myHead=myHead; s.myLen=myLen; s.myHealth=myHealth;
            s.myBody = new ArrayList<>(myBody);
            s.foods = new ArrayList<>(foods);
            s.aliveEnemies = new ArrayList<>();
            for(SnakeData e: aliveEnemies) s.aliveEnemies.add(new SnakeData(e.id, e.len, e.head, e.body, e.health));
            s.blocked = new boolean[W][H];
            for(int x=0; x<W; x++) System.arraycopy(blocked[x], 0, s.blocked[x], 0, H);
            s.hazards = hazards; 
            s.hazardPoints = hazardPoints;
            return s;
        }
    }

    static class SnakeData {
        String id;
        int len;
        Point head;
        List<Point> body;
        int health;
        SnakeData(String id, int len, Point head, List<Point> body, int health) {
            this.id = id; this.len = len; this.head = head;
            this.body = body; this.health = health;
        }
    }
    
    static class Point {
        int x, y;
        Point(int x, int y) { this.x=x; this.y=y; }
        Point add(int[] d) { return new Point(x+d[0], y+d[1]); }
        @Override public boolean equals(Object o) {
             if(this==o) return true;
             if(!(o instanceof Point)) return false;
             Point p=(Point)o; return x==p.x && y==p.y;
        }
        @Override public int hashCode() { return Objects.hash(x,y); }
    }
}
