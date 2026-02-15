package com.battlesnake.starter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

import static spark.Spark.*;

/**
 * GODMODE SNAKE v22.3 - PREDATOR
 * Removed: Territory (Voronoi)
 * Added: Boredom System (History Tracking), Predator Aggression
 */
public class Snake {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Logger LOG = LoggerFactory.getLogger(Snake.class);

    private static final int[][] DIRS = {{0, 1}, {0, -1}, {-1, 0}, {1, 0}};
    private static final String[] DIR_NAMES = {"up", "down", "left", "right"};

    // --- SCORING CONSTANTS ---
    private static final double SCORE_IMPOSSIBLE    = -1_000_000_000.0;
    private static final double SCORE_CERTAIN_DEATH = -10_000_000.0;

    // Strategy weights (V22.3)
    private static final double W_SPACE      = 20.0;  // High freedom value
    private static final double W_AGGRESSION = 30.0;  // High hunter value
    private static final double W_CENTER     = 10.0;  // Anchor

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
            HISTORY.clear(); // New game, clear history
            return "{}";
        });
        post("/move", (req, res) -> JSON.writeValueAsString(move(JSON.readTree(req.body()))));
        post("/end", (req, res) -> "{}");
    }

    static Map<String, String> index() {
        Map<String, String> r = new HashMap<>();
        r.put("apiversion", "1");
        r.put("author", "GODMODE-V22.3-PREDATOR");
        r.put("color", "#FF4500"); // Orange/Red for Hunter
        r.put("head", "fang");
        r.put("tail", "sharp");
        return r;
    }

    // ============================================================
    // MOVE DECISION
    // ============================================================

    static Map<String, String> move(JsonNode root) {
        GameState state = new GameState(root);
        
        // Update History (Boredom)
        // Note: In a real serverless env, static history persists between requests *usually*. 
        // If the container restarts, it clears. This is acceptable for this fix.
        // We only add to history AFTER making a move, but here we are deciding.
        // We actually need to record where we ARE now (myHead from previous turn).
        // But wait, 'myHead' in state is where we are NOW.
        // So we add 'myHead' to history to say "We have been here".
        // BUT, we want to penalize moving BACK to where we were.
        // So we should track the *trail*.
        
        // Let's rely on adding the *chosen* move to history at the end, 
        // or just add the current head to history at start.
        if (!HISTORY.isEmpty() && !HISTORY.getLast().equals(state.myHead)) {
            HISTORY.add(state.myHead);
        } else if (HISTORY.isEmpty()) {
            HISTORY.add(state.myHead);
        }
        if (HISTORY.size() > HISTORY_SIZE) HISTORY.removeFirst();

        boolean isDuel = (state.aliveEnemies.size() == 1);

        String bestDir = "up";
        double maxScore = -Double.MAX_VALUE;

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

            double score;
            // Removed Minimax for V22.3 to ensure PURE BEHAVIOR first.
            // Complex search sometimes hides "personality".
            score = evaluate(state, next);

            if (score > maxScore) {
                maxScore = score;
                bestDir = DIR_NAMES[i];
            }
        }
        
        // Add chosen move to history (predictively)? No, 'start' handles current head.
        LOG.info("Turn {}: Best move {} score {}", state.turn, bestDir, maxScore);
        Map<String, String> res = new HashMap<>();
        res.put("move", bestDir);
        return res;
    }

    // ============================================================
    // EVALUATE — PREDATOR
    // ============================================================

    private static double evaluate(GameState state, Point myNext) {
        double score = basicSafety(state, myNext);
        if (score <= SCORE_CERTAIN_DEATH) return score;

        // 1. SPACE (Freedom) - Replaces Territory
        // Flood fill to see how much space we have.
        // We cap it at 3x length because beyond that is "open enough".
        int space = floodFillGrid(state.W, state.H, state.isWrapped, myNext, state.blocked, state.myLen * 3);
        if (space < state.myLen) {
             return SCORE_CERTAIN_DEATH + space * 1000;
        }
        score += space * W_SPACE;

        // 2. FOOD
        score += foodScore(state, myNext);

        // 3. AGGRESSION
        score += aggressionScore(state, myNext);

        // 4. BOREDOM (Anti-Loop)
        if (HISTORY.contains(myNext)) {
            score -= 50_000.0; // HUGE penalty for revisiting recent spots
        }

        // 5. CENTER BIAS (Anchor)
        int cx = state.W / 2, cy = state.H / 2;
        int distToCenter = Math.abs(myNext.x - cx) + Math.abs(myNext.y - cy);
        score -= distToCenter * W_CENTER;

        // 6. NOISE
        score += new Random().nextDouble() * 1.0;

        return score;
    }

    // ============================================================
    // BASIC SAFETY (Strict)
    // ============================================================

    private static double basicSafety(GameState state, Point next) {
        double score = 0;

        // Hazard
        if (state.isHazard(next)) {
            score -= 100000.0 * state.hazardDamage;
            if (state.myHealth <= state.hazardDamage + 5) return SCORE_IMPOSSIBLE;
        }

        // Head-to-head risk
        for (SnakeData e : state.aliveEnemies) {
            int d = state.dist(next, e.head);
            if (d == 1) {
                // STRICT SAFETY (V22.1 Rule)
                if (e.len >= state.myLen) {
                    return SCORE_IMPOSSIBLE; // Do not risk 50/50
                } else {
                    score += 50000; // BULLY THEM if we are bigger
                }
            }
        }
        
        return score;
    }

    // ============================================================
    // FOOD SCORING
    // ============================================================

    private static double foodScore(GameState state, Point next) {
        if (state.isConstrictor) return 0;

        int maxELen = 0;
        for (SnakeData e : state.aliveEnemies) maxELen = Math.max(maxELen, e.len);

        double urgency = 1.0;
        if (state.myLen > maxELen + 2) urgency = 0.5; // We are big boss
        else if (state.myLen < maxELen) urgency = 3.0; // Need to grow
        
        if (state.myHealth < 40) urgency = 10.0; // Hungry!
        
        // Prevent idle spinning: Always want food a little bit
        if (urgency < 0.5) urgency = 0.5;

        double best = 0;
        for (Point f : state.foods) {
            int d = state.bfsDist(next, f);
            if (d == -1) continue;
            
            double val = 1000.0 * urgency;
            // Denial bonus not needed for basic predator, speed is key
            
            double s = val / (d + 1);
            if (s > best) best = s;
        }
        return best;
    }

    // ============================================================
    // AGGRESSION SCORING
    // ============================================================

    private static double aggressionScore(GameState state, Point next) {
        double score = 0;
        int cx = state.W / 2, cy = state.H / 2;

        for (SnakeData e : state.aliveEnemies) {
            int d = state.dist(next, e.head);
            if (state.myLen > e.len) {
                // HUNTER: Pure chase
                // Closer = Better
                // 1000 points / distance
                score += (1000.0 / (d + 1)) * W_AGGRESSION;
            } else {
                // EVADE:
                // Further = Better
                score += d * 5.0; 
            }
        }
        return score;
    }

    // ============================================================
    // HELPERS
    // ============================================================

    static int floodFillGrid(int W, int H, boolean isWrapped, Point start,
                             boolean[][] blocked, int cap) {
        boolean[][] v = copyGrid(blocked, W, H);
        Queue<Point> q = new LinkedList<>();
        int sx = start.x, sy = start.y;
        if (isWrapped) { sx = (sx % W + W) % W; sy = (sy % H + H) % H; }
        if (sx >= 0 && sx < W && sy >= 0 && sy < H && !v[sx][sy]) {
            q.add(new Point(sx, sy)); v[sx][sy] = true;
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
    // DATA CLASSES
    // ============================================================

    static class GameState {
        int W, H, turn;
        int myHealth, myLen;
        Point myHead;
        List<Point> myBody = new ArrayList<>();

        boolean[][] blocked;
        boolean[][] hazards;
        boolean isRoyale, isConstrictor, isWrapped;
        int hazardDamage = 0;

        List<Point> foods = new ArrayList<>();
        Set<Point> foodSet = new HashSet<>();
        List<SnakeData> aliveEnemies = new ArrayList<>();

        GameState(JsonNode root) {
            JsonNode board = root.get("board");
            W = board.get("width").asInt();
            H = board.get("height").asInt();
            turn = root.get("turn").asInt();

            JsonNode you = root.get("you");
            String myId = you.get("id").asText();
            myHealth = you.get("health").asInt();
            myLen = you.get("body").size();
            myHead = new Point(you.get("head").get("x").asInt(), you.get("head").get("y").asInt());
            for (JsonNode b : you.get("body"))
                myBody.add(new Point(b.get("x").asInt(), b.get("y").asInt()));

            JsonNode game = root.get("game");
            String rules = "standard";
            if (game != null && game.has("ruleset")) {
                rules = game.get("ruleset").get("name").asText().toLowerCase();
                JsonNode settings = game.get("ruleset").get("settings");
                if (settings != null && settings.has("hazardDamagePerTurn"))
                    hazardDamage = settings.get("hazardDamagePerTurn").asInt();
                else hazardDamage = 14;
            } else { hazardDamage = 14; }
            isRoyale = rules.contains("royale");
            isConstrictor = rules.contains("constrictor");
            isWrapped = rules.contains("wrapped");

            blocked = new boolean[W][H];
            hazards = new boolean[W][H];

            if (board.has("hazards")) {
                for (JsonNode h : board.get("hazards")) {
                    Point p = new Point(h.get("x").asInt(), h.get("y").asInt());
                    if (isValid(p)) hazards[p.x][p.y] = true;
                }
            }

            for (JsonNode f : board.get("food")) {
                Point p = new Point(f.get("x").asInt(), f.get("y").asInt());
                foods.add(p);
                foodSet.add(p);
            }

            for (JsonNode s : board.get("snakes")) {
                String id = s.get("id").asText();
                int len = s.get("body").size();
                int health = s.get("health").asInt();

                List<Point> body = new ArrayList<>();
                for (JsonNode b : s.get("body"))
                    body.add(new Point(b.get("x").asInt(), b.get("y").asInt()));

                if (!id.equals(myId))
                    aliveEnemies.add(new SnakeData(id, len, body.get(0), body, health));

                // Mark body as blocked
                for (int i = 0; i < body.size(); i++) {
                    Point p = body.get(i);
                    if (!isValid(p)) continue;

                    if (i == body.size() - 1) {
                        // TAIL: free unless eating
                        boolean isEating = false;
                        for (int[] d : DIRS) {
                            Point n = new Point(body.get(0).x + d[0], body.get(0).y + d[1]);
                            if (isWrapped) n = wrap(n);
                            if (foodSet.contains(n)) { isEating = true; break; }
                        }
                        blocked[p.x][p.y] = isEating;
                    } else {
                        blocked[p.x][p.y] = true;
                    }
                }
            }
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

        boolean isHazard(Point p) {
            Point c = isWrapped ? wrap(p) : p;
            return isValid(c) && hazards[c.x][c.y];
        }

        Point wrap(Point p) {
            return new Point((p.x % W + W) % W, (p.y % H + H) % H);
        }

        int dist(Point a, Point b) {
            return manhattan(a, b, W, H, isWrapped);
        }

        int bfsDist(Point start, Point target) {
            if (start.equals(target)) return 0;
            boolean[][] v = new boolean[W][H];
            Queue<int[]> q = new LinkedList<>();
            q.add(new int[]{start.x, start.y, 0});
            v[start.x][start.y] = true;
            while (!q.isEmpty()) {
                int[] c = q.poll();
                for (int[] dir : DIRS) {
                    int nx = c[0] + dir[0], ny = c[1] + dir[1];
                    if (isWrapped) { nx = (nx + W) % W; ny = (ny + H) % H; }
                    if (nx == target.x && ny == target.y) return c[2] + 1;
                    if (isValid(new Point(nx, ny)) && !v[nx][ny] && !blocked[nx][ny]) {
                        v[nx][ny] = true;
                        q.add(new int[]{nx, ny, c[2] + 1});
                    }
                }
            }
            return -1;
        }
    }

    static class Point {
        int x, y;
        public Point(int x, int y) { this.x = x; this.y = y; }
        public Point add(int[] d) { return new Point(x + d[0], y + d[1]); }
        @Override public boolean equals(Object o) {
            if (this == o) return true;
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
