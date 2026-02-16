package com.battlesnake.starter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

import static spark.Spark.*;

/**
 * INTELLIGENT SNAKE v2.0 - MINIMAX
 * Fixes:
 *  - Fixed infinite recursion bug in minimax (depth not decrementing)
 *  - Safety fallback: pick random safe move if search times out completely
 *  - Tail-chase: consider own tail as reachable in flood fill (it moves away)
 *  - Hazard zones only block flood fill for LARGER enemies, not equal
 *  - Better wall-avoidance and corner-avoidance scoring
 *  - Enhanced logging: per-move scores at root
 */
public class Snake {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Logger LOG = LoggerFactory.getLogger(Snake.class);

    private static final int[][] DIRS = {{0, 1}, {0, -1}, {-1, 0}, {1, 0}};
    private static final String[] DIR_NAMES = {"up", "down", "left", "right"};

    // --- MINIMAX SETTINGS ---
    private static final int MAX_DEPTH_LIMIT = 10;
    private static final long TIME_LIMIT_MS = 280; // Leave 120ms buffer for network

    // --- SCORING WEIGHTS ---
    private static final double W_SPACE      = 12.0;
    private static final double W_FOOD       = 30.0;
    private static final double W_HEALTH     = 3.0;
    private static final double W_CENTER     = 3.0;
    private static final double W_LENGTH     = 800.0;
    private static final double W_WALL       = 5.0;

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
        r.put("author", "AntiGravity-v2");
        r.put("color", "#00AAFF");
        r.put("head", "smart-caterpillar");
        r.put("tail", "curled");
        return r;
    }

    static Map<String, String> move(JsonNode root) {
        long startTime = System.currentTimeMillis();
        GameState state = new GameState(root);
        String bestMove = getBestMove(state, startTime);

        long elapsed = System.currentTimeMillis() - startTime;
        LOG.info("Turn {}: {} ({}ms)", state.turn, bestMove, elapsed);

        Map<String, String> res = new HashMap<>();
        res.put("move", bestMove);
        return res;
    }

    // ============================================================
    // MINIMAX ENGINE
    // ============================================================

    private static String getBestMove(GameState rootState, long startTime) {
        List<String> moves = rootState.getLegalMoves();
        if (moves.isEmpty()) {
            // Desperate: try ALL 4 directions including hazard zones
            moves = rootState.getAllMoves();
            if (moves.isEmpty()) {
                LOG.warn("Turn {}: TRULY NO MOVES!", rootState.turn);
                return "up";
            }
            LOG.warn("Turn {}: No safe moves, trying hazard moves: {}", rootState.turn, moves);
        }

        // Safety Fallback: start with first legal move
        String bestMove = moves.get(0);
        double bestScore = -Double.MAX_VALUE;

        // Evaluate each move at depth 0 first (pure evaluation, instant)
        Map<String, Double> rootScores = new LinkedHashMap<>();
        for (String move : moves) {
            GameState next = rootState.advance(move);
            double score = evaluate(next);
            rootScores.put(move, score);
            if (score > bestScore) {
                bestScore = score;
                bestMove = move;
            }
        }

        // Iterative Deepening
        for (int depth = 1; depth <= MAX_DEPTH_LIMIT; depth++) {
            if (System.currentTimeMillis() - startTime > TIME_LIMIT_MS) break;

            String depthBestMove = bestMove;
            double depthBestScore = -Double.MAX_VALUE;
            boolean completed = true;
            StringBuilder sb = new StringBuilder();

            for (String move : moves) {
                if (System.currentTimeMillis() - startTime > TIME_LIMIT_MS) {
                    completed = false;
                    break;
                }

                GameState next = rootState.advance(move);
                double score = minimax(next, depth - 1, -Double.MAX_VALUE, Double.MAX_VALUE, startTime);

                sb.append(move).append("=").append((long) score).append(" ");

                if (score > depthBestScore) {
                    depthBestScore = score;
                    depthBestMove = move;
                }
            }

            if (completed) {
                bestMove = depthBestMove;
                bestScore = depthBestScore;
                LOG.info("Turn {} D{}: {} -> {}", rootState.turn, depth, sb, bestMove);
            } else {
                break;
            }
        }

        return bestMove;
    }

    /**
     * Single-agent Minimax (no explicit opponent modeling).
     * We search OUR moves at each depth level.
     */
    private static double minimax(GameState state, int depth, double alpha, double beta, long startTime) {
        if (System.currentTimeMillis() - startTime > TIME_LIMIT_MS) return 0;

        if (depth == 0 || state.isGameOver()) {
            return evaluate(state);
        }

        double maxEval = -Double.MAX_VALUE;
        List<String> moves = state.getLegalMoves();

        if (moves.isEmpty()) return -1_000_000; // No moves = death

        for (String move : moves) {
            if (System.currentTimeMillis() - startTime > TIME_LIMIT_MS) return 0;

            GameState next = state.advance(move);
            double eval = minimax(next, depth - 1, alpha, beta, startTime);
            maxEval = Math.max(maxEval, eval);
            alpha = Math.max(alpha, eval);
            if (beta <= alpha) break; // Pruning
        }
        return maxEval;
    }

    // ============================================================
    // EVALUATION
    // ============================================================

    private static double evaluate(GameState state) {
        if (state.myHealth <= 0) return -1_000_000;
        // Note: do NOT check isBlocked(myHead) here — myHead is in obstacles
        // after advance() for future collision detection, but it's our valid position.

        double score = 0;

        // 1. Flood Fill (reachable space from head)
        int space = floodFill(state, state.myHead, state.myLen * 3);
        if (space < state.myLen) {
            // Trapped! Severe penalty, but proportional to how trapped
            return -500_000 + (space * 1000);
        }
        score += space * W_SPACE;

        // 2. Food proximity (urgency-based)
        double foodUrgency = 1.0;
        if (state.myHealth < 25) foodUrgency = 8.0;      // Critical!
        else if (state.myHealth < 50) foodUrgency = 4.0;  // Hungry
        else if (state.myHealth < 75) foodUrgency = 2.0;  // Getting there

        int distToFood = getClosestFoodDist(state);
        if (distToFood != Integer.MAX_VALUE) {
            score += (500.0 / (distToFood + 1)) * foodUrgency * W_FOOD;
        }
        // Starving and no food nearby? Extra penalty
        if (state.myHealth < 20 && distToFood > 5) {
            score -= 5000;
        }

        // 3. Health
        score += state.myHealth * W_HEALTH;

        // 4. Center control (avoid edges and corners where traps happen)
        if (!state.isWrapped) {
            int distEdgeX = Math.min(state.myHead.x, state.W - 1 - state.myHead.x);
            int distEdgeY = Math.min(state.myHead.y, state.H - 1 - state.myHead.y);

            // Heavy penalty for being directly on an edge
            if (distEdgeX == 0 || distEdgeY == 0) score -= W_WALL * 3;
            // Extra penalty for corners
            if (distEdgeX == 0 && distEdgeY == 0) score -= W_WALL * 10;

            // General center preference
            int distCenter = Math.abs(state.myHead.x - state.W / 2) + Math.abs(state.myHead.y - state.H / 2);
            score -= distCenter * W_CENTER;
        }

        // 5. Length dominance
        for (List<Point> enemy : state.enemies) {
            if (state.myLen > enemy.size()) score += W_LENGTH;
            else if (state.myLen < enemy.size()) score -= W_LENGTH * 0.5;
        }

        // 6. Head-to-head avoidance: penalize being adjacent to a larger snake's head
        for (int i = 0; i < state.enemies.size(); i++) {
            List<Point> enemy = state.enemies.get(i);
            if (enemy.isEmpty()) continue;
            Point eHead = enemy.get(0);
            int dist = state.dist(state.myHead, eHead);
            if (dist <= 2 && enemy.size() >= state.myLen) {
                score -= 3000.0 / (dist + 1); // Closer = more dangerous
            }
            if (dist <= 2 && state.myLen > enemy.size()) {
                score += 1500.0 / (dist + 1); // Aggression bonus
            }
        }

        return score;
    }

    // ============================================================
    // UTILS
    // ============================================================

    static int floodFill(GameState state, Point start, int max) {
        boolean[][] visited = new boolean[state.W][state.H];
        // Mark obstacles except own tail (it will move away next turn)
        for (Point p : state.obstacles) {
            if (state.isValid(p)) {
                visited[p.x][p.y] = true;
            }
        }
        // Unblock our own tail — it will move away, so it's reachable
        if (state.myBody.size() > 1) {
            Point tail = state.myBody.get(state.myBody.size() - 1);
            if (state.isValid(tail)) visited[tail.x][tail.y] = false;
        }

        if (state.isValid(start)) visited[start.x][start.y] = true;

        Queue<Point> q = new LinkedList<>();
        q.add(start);

        int count = 0;
        while (!q.isEmpty()) {
            Point p = q.poll();
            count++;
            if (count >= max) return count;

            for (int[] d : DIRS) {
                Point next = p.add(d);
                if (state.isWrapped) next = state.wrap(next);

                if (state.isValid(next) && !visited[next.x][next.y]) {
                    visited[next.x][next.y] = true;
                    q.add(next);
                }
            }
        }
        return count;
    }

    static int getClosestFoodDist(GameState state) {
        int min = Integer.MAX_VALUE;
        for (Point f : state.foods) {
            int d = state.dist(state.myHead, f);
            if (d < min) min = d;
        }
        return min;
    }

    // ============================================================
    // GAME STATE
    // ============================================================

    static class GameState {
        int W, H, turn;
        int myHealth, myLen;
        Point myHead;
        List<Point> myBody;
        Set<Point> foods = new HashSet<>();
        List<List<Point>> enemies = new ArrayList<>();

        Set<Point> hazardZones = new HashSet<>(); // Cells adjacent to larger enemy heads
        Set<Point> obstacles = new HashSet<>();

        boolean isWrapped;

        // Root Constructor
        GameState(JsonNode root) {
            JsonNode board = root.get("board");
            W = board.get("width").asInt();
            H = board.get("height").asInt();
            turn = root.get("turn").asInt();
            JsonNode rules = root.get("game").get("ruleset");
            isWrapped = rules.has("name") && rules.get("name").asText().contains("wrapped");

            JsonNode you = root.get("you");
            myHealth = you.get("health").asInt();
            myBody = new ArrayList<>();
            for (JsonNode b : you.get("body")) myBody.add(new Point(b.get("x").asInt(), b.get("y").asInt()));
            myHead = myBody.get(0);
            myLen = myBody.size();

            for (JsonNode f : board.get("food")) foods.add(new Point(f.get("x").asInt(), f.get("y").asInt()));

            for (JsonNode s : board.get("snakes")) {
                if (s.get("id").asText().equals(you.get("id").asText())) continue;
                List<Point> enemyBody = new ArrayList<>();
                for (JsonNode b : s.get("body")) enemyBody.add(new Point(b.get("x").asInt(), b.get("y").asInt()));
                enemies.add(enemyBody);
                obstacles.addAll(enemyBody);

                // Hazard zones: cells adjacent to STRICTLY LARGER enemy heads
                Point eHead = enemyBody.get(0);
                if (enemyBody.size() > myLen) {  // STRICTLY larger, not equal
                    for (int[] d : DIRS) {
                        Point next = eHead.add(d);
                        if (isWrapped) next = wrap(next);
                        hazardZones.add(next);
                    }
                }
            }

            obstacles.addAll(myBody);
        }

        // Copy Constructor
        private GameState(GameState other) {
            this.W = other.W;
            this.H = other.H;
            this.turn = other.turn;
            this.isWrapped = other.isWrapped;
            this.myHealth = other.myHealth;
            this.myLen = other.myLen;
            this.myHead = other.myHead;
            this.myBody = new ArrayList<>(other.myBody);
            this.foods = new HashSet<>(other.foods);
            this.enemies = other.enemies; // Shared (enemies don't move in our sim)
            this.obstacles = new HashSet<>(other.obstacles);
            this.hazardZones = other.hazardZones;
        }

        // Advance my snake
        GameState advance(String moveDir) {
            GameState next = new GameState(this);
            Point moveVec = new Point(0, 0);
            switch (moveDir) {
                case "up":    moveVec = new Point(0, 1); break;
                case "down":  moveVec = new Point(0, -1); break;
                case "left":  moveVec = new Point(-1, 0); break;
                case "right": moveVec = new Point(1, 0); break;
            }

            Point nextHead = myHead.add(moveVec);
            if (isWrapped) nextHead = wrap(nextHead);

            next.myHead = nextHead;
            next.myBody.add(0, nextHead);
            next.obstacles.add(nextHead);
            next.turn++;

            if (next.foods.contains(nextHead)) {
                next.myHealth = 100;
                next.foods.remove(nextHead);
                next.myLen++;
            } else {
                next.myHealth--;
                if (!next.myBody.isEmpty()) {
                    Point tail = next.myBody.remove(next.myBody.size() - 1);
                    next.obstacles.remove(tail);
                }
            }

            return next;
        }

        /**
         * Legal moves: not blocked by obstacles AND not out of bounds.
         * Hazard zones (adjacent to larger enemy heads) are EXCLUDED from legal moves
         * but are still available via getAllMoves() as a fallback.
         */
        List<String> getLegalMoves() {
            List<String> moves = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                Point p = myHead.add(DIRS[i]);
                if (isWrapped) p = wrap(p);

                if (isValid(p) && !isBlocked(p) && !hazardZones.contains(p)) {
                    moves.add(DIR_NAMES[i]);
                }
            }
            // If no safe moves, allow hazard zone moves (risky but better than dying)
            if (moves.isEmpty()) {
                for (int i = 0; i < 4; i++) {
                    Point p = myHead.add(DIRS[i]);
                    if (isWrapped) p = wrap(p);
                    if (isValid(p) && !isBlocked(p)) {
                        moves.add(DIR_NAMES[i]);
                    }
                }
            }
            return moves;
        }

        /**
         * Emergency: ALL valid moves ignoring obstacles too (last resort)
         */
        List<String> getAllMoves() {
            List<String> moves = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                Point p = myHead.add(DIRS[i]);
                if (isWrapped) p = wrap(p);
                if (isValid(p)) {
                    moves.add(DIR_NAMES[i]);
                }
            }
            return moves;
        }

        boolean isGameOver() {
            // Only check health — myHead is always in obstacles after advance()
            return myHealth <= 0;
        }

        boolean isValid(Point p) {
            if (isWrapped) return true;
            return p.x >= 0 && p.x < W && p.y >= 0 && p.y < H;
        }

        boolean isBlocked(Point p) {
            return obstacles.contains(p);
        }

        Point wrap(Point p) {
            return new Point((p.x % W + W) % W, (p.y % H + H) % H);
        }

        int dist(Point a, Point b) {
            int dx = Math.abs(a.x - b.x);
            int dy = Math.abs(a.y - b.y);
            if (isWrapped) {
                dx = Math.min(dx, W - dx);
                dy = Math.min(dy, H - dy);
            }
            return dx + dy;
        }
    }

    static class Point {
        int x, y;
        public Point(int x, int y) { this.x = x; this.y = y; }
        public Point add(int[] d) { return new Point(x + d[0], y + d[1]); }
        public Point add(Point p) { return new Point(x + p.x, y + p.y); }
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Point)) return false;
            Point p = (Point) o;
            return x == p.x && y == p.y;
        }
        @Override public int hashCode() { return Objects.hash(x, y); }
        @Override public String toString() { return "(" + x + "," + y + ")"; }
    }
}
