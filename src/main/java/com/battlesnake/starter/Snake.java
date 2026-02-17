package com.battlesnake.starter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

import static spark.Spark.*;

/**
 * GODMODE SNAKE v23.0 - SMART PREDATOR + CONSTRICTOR
 * Features:
 * 1. Predictive Minimax (Depth 3) - Solves "Dead Ends"
 * 2. Constrictor Mode Logic - Solves "Cluelessness"
 * 3. Boredom System - Solves "Spinning"
 */
public class Snake {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Logger LOG = LoggerFactory.getLogger(Snake.class);

    private static final int[][] DIRS = {{0, 1}, {0, -1}, {-1, 0}, {1, 0}};
    private static final String[] DIR_NAMES = {"up", "down", "left", "right"};

    // --- SCORING CONSTANTS ---
    private static final double SCORE_IMPOSSIBLE    = -1_000_000_000.0;
    private static final double SCORE_CERTAIN_DEATH = -10_000_000.0;
    private static final double SCORE_WIN           =  100_000_000.0;

    // Strategy weights (V23 Standard)
    private static final double W_SPACE      = 20.0;
    private static final double W_AGGRESSION = 30.0;
    private static final double W_CENTER     = 10.0;

    // History for Boredom System
    private static final LinkedList<Point> HISTORY = new LinkedList<>();
    private static final int HISTORY_SIZE = 10;

    // ============================================================
    // MAIN + ROUTES
    // ============================================================

    public static void main(String[] args) {
        String port = System.getProperty("PORT", "8082");
        port(Integer.parseInt(port));
        get("/", (req, res) -> JSON.writeValueAsString(index()));
        post("/start", (req, res) -> {
            HISTORY.clear();
            return "{}";
        });
        post("/move", (req, res) -> JSON.writeValueAsString(move(JSON.readTree(req.body()))));
        post("/end", (req, res) -> "{}");
    }

    static Map<String, String> index() {
        Map<String, String> r = new HashMap<>();
        r.put("apiversion", "1");
        r.put("author", "GODMODE-V23-PREDATOR");
        r.put("color", "#8B0000"); // Dark Red (Predator)
        r.put("head", "fang");
        r.put("tail", "hook");
        // Constrictor overrides (visual only, server overrides this)
        return r;
    }

    // ============================================================
    // MOVE DECISION
    // ============================================================

    static Map<String, String> move(JsonNode root) {
        long startTime = System.currentTimeMillis();
        GameState state = new GameState(root);
        
        // Update History
        if (!HISTORY.isEmpty() && !HISTORY.getLast().equals(state.myHead)) {
            HISTORY.add(state.myHead);
        } else if (HISTORY.isEmpty()) {
            HISTORY.add(state.myHead);
        }
        if (HISTORY.size() > HISTORY_SIZE) HISTORY.removeFirst();

        String bestDir = "up";
        double maxScore = -Double.MAX_VALUE;

        // PREDICTIVE MINIMAX (Depth 2-3)
        // We use Depth 3 if few snakes, Depth 2 if many (timeout protection)
        int depth = (state.aliveEnemies.size() > 2) ? 2 : 3;
        
        // Debugging Constrictor
        if (state.isConstrictor) {
             LOG.info("CONSTRICTOR MODE ACTIVE");
             depth = 2; // Safety for constrictor which fills board
        }

        for (int i = 0; i < 4; i++) {
            Point next = state.myHead.add(DIRS[i]);
            if (state.isWrapped) next = state.wrap(next);

            if (!state.isValid(next) || state.isBlocked(next)) {
                if (SCORE_IMPOSSIBLE > maxScore) {
                    maxScore = SCORE_IMPOSSIBLE;
                    bestDir = DIR_NAMES[i];
                }
                continue;
            }

            // RECURSIVE SEARCH
            double score = search(state, next, depth, startTime);

            if (score > maxScore) {
                maxScore = score;
                bestDir = DIR_NAMES[i];
            }
        }
        
        long timeTaken = System.currentTimeMillis() - startTime;
        LOG.info("Turn {}: Best move {} score {} ({}ms)", state.turn, bestDir, maxScore, timeTaken);
        
        Map<String, String> res = new HashMap<>();
        res.put("move", bestDir);
        return res;
    }

    // ============================================================
    // PREDICTIVE SEARCH
    // ============================================================

    private static double search(GameState state, Point myMove, int depth, long startTime) {
        // 1. Simulate MY move
        GameState nextState = state.cloneState();
        nextState.advanceMySnake(myMove);
        
        if (nextState.amIDead()) return SCORE_CERTAIN_DEATH;
        if (nextState.hasWon()) return SCORE_WIN;
        
        // 2. Base Case
        if (depth == 0 || System.currentTimeMillis() - startTime > 400) {
            return evaluate(nextState, myMove); // Evaluate the resulting state
        }

        // 3. Simulate OPPONENTS (The "Prediction")
        // Instead of trying all combos, we move each enemy to their BEST heuristic spot.
        // This makes the world deterministic-ish but aggressive.
        nextState.advanceEnemiesPredictively();
        
        if (nextState.amIDead()) return SCORE_CERTAIN_DEATH; // They killed me!

        // 4. Recursive Step (Max Layer)
        // Now it's my turn again in this simulated future.
        double bestVal = -Double.MAX_VALUE;
        boolean canMove = false;
        
        for (int[] d : DIRS) {
            Point p = nextState.myHead.add(d);
            if (nextState.isWrapped) p = nextState.wrap(p);
            
            if (!nextState.isValid(p) || nextState.isBlocked(p)) continue;
            
            canMove = true;
            double val = search(nextState, p, depth - 1, startTime);
            bestVal = Math.max(bestVal, val);
        }
        
        if (!canMove) return SCORE_CERTAIN_DEATH; // Trapped
        
        // Decay score slightly with depth to prefer seeking NOW
        return bestVal * 0.99;
    }

    // ============================================================
    // EVALUATE
    // ============================================================

    private static double evaluate(GameState state, Point myHead) {
        // CONSTRICTOR OVERRIDE
        if (state.isConstrictor) {
            return evaluateConstrictor(state, myHead);
        }

        double score = 0;

        // 1. VORONOI TERRITORY (replacing simple flood fill)
        // This calculates how many cells I can reach BEFORE any enemy.
        // It prevents us from getting squeezed into large but dead-end pockets.
        int safeSpace = bfsVoronoi(state, myHead);
        
        // Survival Critical Check
        if (safeSpace < state.myLen) {
             // We are trapped in a space smaller than our body.
             // This is likely death unless we can chase our tail (which Voronoi doesn't perfectly capture, but is a good proxy).
             return SCORE_CERTAIN_DEATH + safeSpace * 1000;
        }
        
        score += safeSpace * W_SPACE;

        // 2. FOOD
        score += foodScore(state, myHead);

        // 3. AGGRESSION
        score += aggressionScore(state, myHead);

        // 4. BOREDOM (Anti-Loop)
        if (HISTORY.contains(myHead)) {
            score -= 50_000.0; 
        }

        // 5. CENTER BIAS (Anchor)
        int cx = state.W / 2, cy = state.H / 2;
        int distToCenter = Math.abs(myHead.x - cx) + Math.abs(myHead.y - cy);
        score -= distToCenter * W_CENTER;

        return score;
    }

    private static double evaluateConstrictor(GameState state, Point myHead) {
         // In Constrictor:
         // - No food eating (it kills space).
         // - Maximize OPEN SPACE.
         // - Avoid self-traps strictly.
         
         double score = 0;
         
         // Use Voronoi here too? Yes, absolutely critical in constrictor.
         int space = bfsVoronoi(state, myHead);
         score += space * 100.0;
         
         // If space is tight, Panic
         if (space < state.myLen + 10) score -= 1_000_000;

         // Edge Avoidance (Don't hug walls too early)
         int cx = state.W / 2, cy = state.H / 2;
         int dist = Math.abs(myHead.x - cx) + Math.abs(myHead.y - cy);
         score -= dist * 10.0; 
         
         // History still applies to prevent loops
         if (HISTORY.contains(myHead)) score -= 50_000.0;

         return score;
    }

    // ============================================================
    // SCORING HELPERS
    // ============================================================
    
    // Multi-Source BFS to count "Guaranteed Space"
    static int bfsVoronoi(GameState state, Point myHead) {
        int[][] dist = new int[state.W][state.H];
        for(int[] r : dist) Arrays.fill(r, -1);
        
        // 0 = Me, 1 = Enemy
        int[][] owners = new int[state.W][state.H];
        
        Queue<Point> q = new LinkedList<>();
        
        // 1. Add Enemies to Queue FIRST (Pessimistic: Ties go to enemy)
        for (SnakeData e : state.aliveEnemies) {
            if (state.isValid(e.head)) {
                dist[e.head.x][e.head.y] = 0;
                owners[e.head.x][e.head.y] = 1;
                q.add(e.head);
            }
        }
        
        // 2. Add Me
        // Note: myHead is likely marked 'blocked' in blocked[][] by GameState init,
        // but we are "on" it now. In this specific BFS, we want to expand FROM it.
        // We must check if 'dist' is -1 to ensure we didn't collide with enemy head (head-to-head handled elsewhere, this is space).
        if (state.isValid(myHead) && dist[myHead.x][myHead.y] == -1) {
             dist[myHead.x][myHead.y] = 0;
             owners[myHead.x][myHead.y] = 0;
             q.add(myHead);
        }
        
        int myCount = 0;
        
        while(!q.isEmpty()) {
            Point p = q.poll();
            int d = dist[p.x][p.y];
            int owner = owners[p.x][p.y];
            
            if (owner == 0) myCount++;
            
            // Optimization: If space is HUGE, we stop.
            if (myCount > state.myLen * 2 && myCount > 100) return myCount; 
            
            for (int[] dir : DIRS) {
                 Point n = p.add(dir);
                 if (state.isWrapped) n = state.wrap(n);
                 
                 if (!state.isValid(n)) continue;
                 
                 // Key: We cannot walk through blocked cells.
                 // EXCEPTION: We can "chase" tail? 
                 // For Voronoi (General Space), treating bodies as walls is correct.
                 if (state.blocked[n.x][n.y]) continue; 
                 
                 if (dist[n.x][n.y] == -1) {
                     dist[n.x][n.y] = d + 1;
                     owners[n.x][n.y] = owner;
                     q.add(n);
                 }
            }
        }
        return myCount;
    }

    // Strategy weights (V23 Standard)
    private static final double W_SPACE      = 10.0;
    private static final double W_AGGRESSION = 50.0;
    private static final double W_CENTER     = 5.0;

    // ============================================================
    // SCORING HELPERS
    // ============================================================

    private static double foodScore(GameState state, Point next) {
        if (state.isConstrictor) return 0; // IGNORE FOOD

        int maxELen = 0;
        for (SnakeData e : state.aliveEnemies) maxELen = Math.max(maxELen, e.len);

        double urgency = 1.0;
        if (state.myLen > maxELen + 2) urgency = 0.5;
        else if (state.myLen < maxELen) urgency = 3.0;
        if (state.myHealth < 40) urgency = 10.0;
        if (urgency < 0.5) urgency = 0.5;

        double best = 0;
        for (Point f : state.foods) {
            int d = state.bfsDist(next, f);
            if (d == -1) continue;
            double val = 5000.0 * urgency;
            double s = val / (d + 1);
            if (s > best) best = s;
        }
        return best;
    }

    private static double aggressionScore(GameState state, Point next) {
        double score = 0;
        for (SnakeData e : state.aliveEnemies) {
            int d = state.dist(next, e.head);
            if (state.myLen > e.len) {
                score += (1000.0 / (d + 1)) * W_AGGRESSION;
            } else {
                score += d * 5.0; 
            }
        }
        return score;
    }

    // ============================================================
    // UTILS
    // ============================================================

    static int floodFillGrid(int W, int H, boolean isWrapped, Point start,
                             boolean[][] blocked, int cap) {
        boolean[][] v = copyGrid(blocked, W, H);
        Queue<Point> q = new LinkedList<>();
        int sx = start.x, sy = start.y;
        if (isWrapped) { sx = (sx % W + W) % W; sy = (sy % H + H) % H; }
        
        // FIX: Do NOT check !v[sx][sy] for the start point. 
        // We are ON the start point, so we count it and explore from it, 
        // even if it's technically marked 'blocked' (which it is, by our own head).
        if (sx >= 0 && sx < W && sy >= 0 && sy < H) {
            q.add(new Point(sx, sy)); 
            v[sx][sy] = true;
        }
        
        int count = 0;
        while (!q.isEmpty()) {
            Point p = q.poll(); 
            // Don't count the start point itself as specific "free space" if current logic relies on it?
            // Actually, usually flood fill counts reachable nodes.
            // If we want space *available*, counting head is fine/negligible.
            count++;
            if (count >= cap) return count;
            
            for (int[] d : DIRS) {
                int nx = p.x + d[0];
                int ny = p.y + d[1];
                if (isWrapped) { nx = (nx % W + W) % W; ny = (ny % H + H) % H; }
                if (nx >= 0 && nx < W && ny >= 0 && ny < H && !v[nx][ny]) {
                    v[nx][ny] = true; q.add(new Point(nx, ny));
                }
            }
        }
        return count;
    }

    static boolean[][] copyGrid(boolean[][] src, int w, int h) {
        boolean[][] c = new boolean[w][h];
        for (int x = 0; x < w; x++) System.arraycopy(src[x], 0, c[x], 0, h);
        return c;
    }

    static int manhattan(Point a, Point b, int W, int H, boolean wrapped) {
        int dx = Math.abs(a.x - b.x), dy = Math.abs(a.y - b.y);
        if (wrapped) { dx = Math.min(dx, W - dx); dy = Math.min(dy, H - dy); }
        return dx + dy;
    }

    // ============================================================
    // GAME STATE & SIMULATION
    // ============================================================

    static class GameState {
        int W, H, turn;
        int myHealth, myLen;
        Point myHead;
        List<Point> myBody = new ArrayList<>(); // Added to track my body
        boolean[][] blocked;
        boolean[][] hazards;
        boolean isConstrictor, isWrapped;
        int hazardDamage = 0;
        List<Point> foods = new ArrayList<>();
        List<SnakeData> aliveEnemies = new ArrayList<>();

        // Helper for simple init
        GameState(JsonNode root) {
            JsonNode board = root.get("board");
            W = board.get("width").asInt(); H = board.get("height").asInt();
            turn = root.get("turn").asInt();
            JsonNode you = root.get("you");
            String myId = you.get("id").asText();
            myHealth = you.get("health").asInt();
            myLen = you.get("body").size();
            myHead = new Point(you.get("head").get("x").asInt(), you.get("head").get("y").asInt());
            
            // Init myBody
            for (JsonNode b : you.get("body")) {
                myBody.add(new Point(b.get("x").asInt(), b.get("y").asInt()));
            }

            JsonNode game = root.get("game");
            String rules = "standard";
            if (game != null && game.has("ruleset")) {
                rules = game.get("ruleset").get("name").asText().toLowerCase();
            }
            isConstrictor = rules.contains("constrictor");
            isWrapped = rules.contains("wrapped");
            
            blocked = new boolean[W][H];
            hazards = new boolean[W][H];
            
            // Hazards
             if (board.has("hazards")) {
                for (JsonNode h : board.get("hazards")) {
                    Point p = new Point(h.get("x").asInt(), h.get("y").asInt());
                    if (isValid(p)) hazards[p.x][p.y] = true;
                }
            }
            
            // Food
            for (JsonNode f : board.get("food")) {
                Point p = new Point(f.get("x").asInt(), f.get("y").asInt());
                foods.add(p);
            }
            
            // Enemies & Myself blocking
            // Important: We need to put myself in 'aliveEnemies' if I want to treat everyone same? No.
            // But we need to mark my body as blocked.
            for (JsonNode b : you.get("body")) markBlocked(new Point(b.get("x").asInt(), b.get("y").asInt()));
            
            for (JsonNode s : board.get("snakes")) {
                String id = s.get("id").asText();
                if (id.equals(myId)) continue;
                
                int len = s.get("body").size();
                List<Point> body = new ArrayList<>();
                for (JsonNode b : s.get("body")) {
                    Point p = new Point(b.get("x").asInt(), b.get("y").asInt());
                    body.add(p);
                    markBlocked(p);
                }
                aliveEnemies.add(new SnakeData(id, len, body.get(0), body, s.get("health").asInt()));
            }
        }
        
        // Constructor for cloning
        GameState() {}

        GameState cloneState() {
            GameState s = new GameState();
            s.W = W; s.H = H; s.turn = turn;
            s.myHealth = myHealth; s.myLen = myLen; s.myHead = myHead;
            s.myBody = new ArrayList<>(myBody); // Deep copy body
            s.blocked = copyGrid(blocked, W, H);
            s.hazards = copyGrid(hazards, W, H); // deep copy hazards? usually static but safe
            s.isConstrictor = isConstrictor; s.isWrapped = isWrapped;
            s.foods = new ArrayList<>(foods);
            s.aliveEnemies = new ArrayList<>();
            for (SnakeData e : aliveEnemies) {
                s.aliveEnemies.add(e.cloneData());
            }
            return s;
        }
        
        void markBlocked(Point p) {
            if (isWrapped) p = wrap(p);
            if (isValid(p)) blocked[p.x][p.y] = true;
        }

        void unmarkBlocked(Point p) {
            if (isWrapped) p = wrap(p);
            if (isValid(p)) blocked[p.x][p.y] = false;
        }

        void advanceMySnake(Point nextHead) {
            if (isWrapped) nextHead = wrap(nextHead);
            
            // 1. Determine if we are growing (eating food)
            boolean growing = foods.contains(nextHead);
            
            // 2. Optimistic Tail Unblocking:
            // If we are NOT growing, our tail spot will be free at the end of this turn.
            // We unmark it NOW so that 'nextHead' can validly be the current tail position.
            Point tail = null;
            if (!growing && !myBody.isEmpty()) {
                tail = myBody.get(myBody.size() - 1);
                unmarkBlocked(tail);
            }

            // 3. Check for death (Collision)
            // If we hit a blocked cell (body/wall/etc), we die.
            // Note: 'tail' is now unblocked, so hitting it is allowed.
            if (!isValid(nextHead) || blocked[nextHead.x][nextHead.y]) {
                myHealth = 0; // Mark as dead
                // Revert tail unblock if we died (clean state)
                if (tail != null) markBlocked(tail);
            } else {
                myHealth--;
                myBody.add(0, nextHead); // Add new head
                markBlocked(nextHead);   // Mark new head as blocked
                
                if (growing) {
                     myLen++;
                     myHealth = 100;
                     foods.remove(nextHead);
                     // If we grew, we DO NOT remove the tail.
                     // But we ALREADY unblocked it in Step 2! We must RE-BLOCK it.
                     if (tail != null) markBlocked(tail);
                } else {
                    // Not growing. Tail already unblocked. Just remove from list.
                    if (!myBody.isEmpty()) {
                        myBody.remove(myBody.size() - 1);
                    }
                }
            }
            myHead = nextHead;
        }

    void advanceEnemiesPredictively() {
            // For each enemy, pick their BEST immediate move and execute it.
            for (SnakeData e : aliveEnemies) {
                // 1. Unblock tail optimistically (assuming no growth yet)
                Point tail = null;
                if (!e.body.isEmpty()) {
                    tail = e.body.get(e.body.size() - 1);
                    unmarkBlocked(tail);
                }

                // Heuristic choice for enemy
                Point bestEMove = null;
                double bestEScore = -Double.MAX_VALUE;
                
                for (int[] d : DIRS) {
                    Point en = e.head.add(d);
                    if (isWrapped) en = wrap(en);
                    
                    if (!isValid(en) || blocked[en.x][en.y]) continue;
                    
                    // Simple enemy evaluation
                    // 1. Distance to food (if hungry)
                    // 2. Space available
                    // 3. Distance to ME (Aggression)
                    
                    double sc = 0;
                    sc += floodFillGrid(W, H, isWrapped, en, blocked, 20) * 10;
                    // Anti-Collision heuristic
                    sc += 500; 
                    
                    if (sc > bestEScore) {
                        bestEScore = sc;
                        bestEMove = en;
                    }
                }
                
                if (bestEMove != null) {
                    e.head = bestEMove;
                    e.body.add(0, bestEMove); // Add new head
                    markBlocked(bestEMove);
                    
                    // Check food for enemy (simplified: they eat if they land on it)
                    if (foods.contains(bestEMove)) {
                        e.len++;
                        e.health = 100;
                        foods.remove(bestEMove); 
                        // Enemy grew. Re-block the tail we optimistically unblocked.
                        if (tail != null) markBlocked(tail);
                    } else {
                        // Not growing. Tail already unblocked. Just remove from list.
                        if (!e.body.isEmpty()) {
                            e.body.remove(e.body.size() - 1);
                        }
                    }
                } else {
                    // Enemy dies (trapped)
                    // We optimistically unblocked tail. Re-block it to maintain static obstacle.
                    if (tail != null) markBlocked(tail);
                }
            }
        }

        boolean amIDead() {
            return myHealth <= 0;
        }
        
        boolean hasWon() {
             return aliveEnemies.isEmpty();
        }

        boolean isValid(Point p) {
            if (isWrapped) return true;
            return p.x >= 0 && p.x < W && p.y >= 0 && p.y < H;
        }

        boolean isBlocked(Point p) {
            Point c = isWrapped ? wrap(p) : p;
            if (!isValid(c)) return true;
            return blocked[c.x][c.y];
        }

        Point wrap(Point p) {
            return new Point((p.x % W + W) % W, (p.y % H + H) % H);
        }
        
        int dist(Point a, Point b) {
            return manhattan(a, b, W, H, isWrapped);
        }

        int bfsDist(Point start, Point target) {
             // simplified dist for perf
             return manhattan(start, target, W, H, isWrapped);
        }
    }

    static class Point {
        int x, y;
        public Point(int x, int y) { this.x = x; this.y = y; }
        public Point add(int[] d) { return new Point(x + d[0], y + d[1]); }
        @Override public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Point)) return false;
            Point p = (Point) o;
            return x == p.x && y == p.y;
        }
        @Override public int hashCode() { return Objects.hash(x, y); }
    }

    static class SnakeData {
        String id;
        int len;
        Point head;
        List<Point> body;
        int health;
        SnakeData(String id, int len, Point head, List<Point> body, int health) {
            this.id = id; this.len = len; this.head = head;
            this.body = new ArrayList<>(body); this.health = health;
        }
        SnakeData cloneData() {
             return new SnakeData(id, len, head, body, health);
        }
    }
}
