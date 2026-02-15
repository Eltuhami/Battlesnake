package com.battlesnake.starter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

import static spark.Spark.*;

/**
 * GODMODE SNAKE v22.3 - PREDATOR LOGIC
 * - Reverted to Heuristic-Only (No Minimax)
 * - Features: Aggression, Boredom (Anti-Loop), Space Awareness
 */
public class Snake {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Logger LOG = LoggerFactory.getLogger(Snake.class);

    private static final int[][] DIRS = {{0, 1}, {0, -1}, {-1, 0}, {1, 0}};
    private static final String[] DIR_NAMES = {"up", "down", "left", "right"};

    // --- SCORING WEIGHTS ---
    private static final double W_SPACE      = 20.0;
    private static final double W_AGGRESSION = 30.0;
    private static final double W_CENTER     = 10.0; // Weak pull to center
    private static final double W_FOOD_BASE  = 1000.0;

    // History for Boredom System (Anti-Spin)
    private static final LinkedList<Point> HISTORY = new LinkedList<>();
    private static final int HISTORY_SIZE = 10; // Keep it simple

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
        r.put("author", "GODMODE-V22.3-PREDATOR");
        r.put("color", "#FF0000"); // Red
        r.put("head", "fang");
        r.put("tail", "sharp");
        return r;
    }

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

        // 1. Identify Safe Moves
        List<String> safeMoves = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            Point next = state.myHead.add(DIRS[i]);
            if (state.isWrapped) next = state.wrap(next);
            
            if (state.isValid(next) && !state.isBlocked(next)) {
                // Safety Check: Don't move into a smaller snake's head?
                // Basic Collision Check done by isBlocked (includes bodies)
                safeMoves.add(DIR_NAMES[i]);
                
                double score = evaluate(state, next);
                if (score > maxScore) {
                    maxScore = score;
                    bestDir = DIR_NAMES[i];
                }
            }
        }
        
        // Fallback if no moves
        if (safeMoves.isEmpty()) {
            LOG.info("Turn {}: NO SAFE MOVES! Choosing UP.", state.turn);
        } else {
             LOG.info("Turn {}: Best move {} score {}", state.turn, bestDir, maxScore);
        } 

        Map<String, String> res = new HashMap<>();
        res.put("move", bestDir);
        return res;
    }

    // ============================================================
    // EVALUATION (HEURISTIC)
    // ============================================================
    
    private static double evaluate(GameState state, Point myNext) {
        double score = 0;

        // 1. SPACE (FloodFill)
        // Ensure we don't get trapped.
        int space = floodFillGrid(state.W, state.H, state.isWrapped, myNext, state.blocked, state.myLen * 3);
        if (space < state.myLen) {
            return -1_000_000.0 + space * 1000; // Panic
        }
        score += space * W_SPACE;

        // 2. FOOD
        score += foodScore(state, myNext);

        // 3. AGGRESSION
        score += aggressionScore(state, myNext);
        
        // 4. BOREDOM (Anti-Loop)
        if (HISTORY.contains(myNext)) {
            score -= 50_000.0;
        }
        
        // 5. CENTER BIAS
        int cx = state.W / 2, cy = state.H / 2;
        int dist = Math.abs(myNext.x - cx) + Math.abs(myNext.y - cy);
        score -= dist * W_CENTER;

        return score;
    }

    private static double foodScore(GameState state, Point next) {
        if (state.isConstrictor) return 0;

        int maxELen = 0;
        for (SnakeData e : state.aliveEnemies) maxELen = Math.max(maxELen, e.len);

        // Urgency: 
        // - Health < 40: High
        // - Smaller than biggest enemy: Medium
        // - Else: Low
        double urgency = 1.0;
        if (state.myHealth < 40) urgency = 10.0;
        else if (state.myLen < maxELen + 2) urgency = 3.0; // Keep growing to be dominant
        else urgency = 0.5; // We are big, chill

        double best = 0;
        for (Point f : state.foods) {
            int d = state.dist(next, f);
             // dist is BFS/Manhattan
            double val = W_FOOD_BASE * urgency;
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
                // Chase smaller snakes!
                score += (2000.0 / (d + 1)) * W_AGGRESSION;
            } else {
                // Avoid larger heads (Safety)
                if (d <= 2) score -= 100_000.0; // DANGER ZONE
                else score += d * 5.0; // Keep slightly away/neutral
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
        
        // Fix: Use correct coords and check against grid bounds
        int sx = start.x, sy = start.y;
        if (isWrapped) { sx = (sx % W + W) % W; sy = (sy % H + H) % H; }
        
        if (sx >= 0 && sx < W && sy >= 0 && sy < H && !v[sx][sy]) {
             v[sx][sy] = true;
             q.add(new Point(sx, sy));
        }

        int count = 0;
        while (!q.isEmpty()) {
            Point p = q.poll(); count++;
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
    // GAME STATE
    // ============================================================

    static class GameState {
        int W, H, turn;
        int myHealth, myLen;
        Point myHead;
        boolean[][] blocked;
        boolean isConstrictor, isWrapped;
        List<Point> foods = new ArrayList<>();
        List<SnakeData> aliveEnemies = new ArrayList<>();

        GameState(JsonNode root) {
            JsonNode board = root.get("board");
            W = board.get("width").asInt(); H = board.get("height").asInt();
            turn = root.get("turn").asInt();
            JsonNode you = root.get("you");
            String myId = you.get("id").asText();
            myHealth = you.get("health").asInt();
            myLen = you.get("body").size();
            myHead = new Point(you.get("head").get("x").asInt(), you.get("head").get("y").asInt());
            
            JsonNode game = root.get("game");
            String rules = "standard";
            if (game != null && game.has("ruleset")) {
                rules = game.get("ruleset").get("name").asText().toLowerCase();
            }
            isConstrictor = rules.contains("constrictor");
            isWrapped = rules.contains("wrapped");
            
            blocked = new boolean[W][H];
            
            // Hazards? We can treat as blocked or just ignore for V22.3 simplicity
             if (board.has("hazards")) {
                for (JsonNode h : board.get("hazards")) {
                    Point p = new Point(h.get("x").asInt(), h.get("y").asInt());
                    // In V22.3 we treated hazards as 'risky' but not 'walls'.
                    // For safety now, let's just ignore them in 'blocked' but maybe penalize later?
                    // Actually, simple V22.3 treated them as open.
                }
            }
            
            for (JsonNode f : board.get("food")) {
                foods.add(new Point(f.get("x").asInt(), f.get("y").asInt()));
            }
            
            // Bodies are walls
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
        
        void markBlocked(Point p) {
            if (isWrapped) p = wrap(p);
            if (isValid(p)) blocked[p.x][p.y] = true;
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
            this.body = body; this.health = health;
        }
    }
}
