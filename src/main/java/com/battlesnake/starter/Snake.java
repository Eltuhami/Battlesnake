package com.battlesnake.starter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

import static spark.Spark.*;

/**
 * GODMODE SNAKE v22.0 - STRATEGIC APEX
 * Voronoi Territory + 2-ply Minimax (1v1) + Aggressive Cutoffs
 */
public class Snake {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Logger LOG = LoggerFactory.getLogger(Snake.class);

    private static final int[][] DIRS = {{0, 1}, {0, -1}, {-1, 0}, {1, 0}};
    private static final String[] DIR_NAMES = {"up", "down", "left", "right"};

    // --- SCORING CONSTANTS ---
    private static final double SCORE_IMPOSSIBLE    = -1_000_000_000.0;
    private static final double SCORE_CERTAIN_DEATH = -10_000_000.0;

    // Strategy weights
    private static final double W_TERRITORY  = 15.0;
    private static final double W_SPACE      = 5.0;
    private static final double W_AGGRESSION = 10.0;

    // ============================================================
    // MAIN + ROUTES
    // ============================================================

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
        r.put("author", "GODMODE-V22-STRATEGIC");
        r.put("color", "#FF0000");
        r.put("head", "evil");
        r.put("tail", "sharp");
        return r;
    }

    // ============================================================
    // MOVE DECISION
    // ============================================================

    static Map<String, String> move(JsonNode root) {
        GameState state = new GameState(root);
        boolean isDuel = (state.aliveEnemies.size() == 1);

        String bestDir = "up";
        double maxScore = -Double.MAX_VALUE;

        for (int i = 0; i < 4; i++) {
            Point next = state.myHead.add(DIRS[i]);
            if (state.isWrapped) next = state.wrap(next);

            if (!state.isValid(next) || state.isBlocked(next)) {
                // Track impossible moves so we still pick one if all blocked
                if (SCORE_IMPOSSIBLE > maxScore) {
                    maxScore = SCORE_IMPOSSIBLE;
                    bestDir = DIR_NAMES[i];
                }
                continue;
            }

            double score;
            if (isDuel) {
                score = minimaxEval(state, next);
            } else {
                score = evaluate(state, next);
            }

            if (score > maxScore) {
                maxScore = score;
                bestDir = DIR_NAMES[i];
            }
        }

        LOG.info("Turn {}: Best move {} score {}", state.turn, bestDir, maxScore);
        Map<String, String> res = new HashMap<>();
        res.put("move", bestDir);
        return res;
    }

    // ============================================================
    // MINIMAX (1v1 only, depth 2)
    // ============================================================

    private static double minimaxEval(GameState state, Point myNext) {
        // Basic safety first — if we'd die, no need to minimax
        double safety = basicSafety(state, myNext);
        if (safety <= SCORE_CERTAIN_DEATH) return safety;

        SnakeData enemy = state.aliveEnemies.get(0);
        double worstCase = Double.MAX_VALUE;
        boolean anyValidEnemy = false;

        for (int j = 0; j < 4; j++) {
            Point eNext = enemy.head.add(DIRS[j]);
            if (state.isWrapped) eNext = state.wrap(eNext);
            if (!state.isValid(eNext)) continue;

            // Head-to-head collision
            if (myNext.equals(eNext)) {
                double cs = (state.myLen > enemy.len) ? 50000 : SCORE_IMPOSSIBLE;
                worstCase = Math.min(worstCase, cs);
                anyValidEnemy = true;
                continue;
            }

            // Simulate both moves on a copy of blocked grid
            boolean[][] sim = copyGrid(state.blocked, state.W, state.H);

            // Our move: head occupies new cell, tail frees (unless eating)
            sim[myNext.x][myNext.y] = true;
            boolean myEat = state.foodSet.contains(myNext);
            if (!myEat && !state.myBody.isEmpty()) {
                Point mt = state.myBody.get(state.myBody.size() - 1);
                sim[mt.x][mt.y] = false;
            }

            // Check if enemy move is valid on simulated board
            Point eTail = enemy.body.get(enemy.body.size() - 1);
            boolean eEat = state.foodSet.contains(eNext);
            if (sim[eNext.x][eNext.y]) {
                // Might be enemy's own tail (which will free up)
                if (eNext.equals(eTail) && !eEat) { /* ok, tail moves */ }
                else continue; // blocked
            }

            // Enemy move
            sim[eNext.x][eNext.y] = true;
            if (!eEat) sim[eTail.x][eTail.y] = false;

            anyValidEnemy = true;

            // Evaluate simulated position
            int myNewLen = state.myLen + (myEat ? 1 : 0);
            int eNewLen = enemy.len + (eEat ? 1 : 0);
            double score = evalSimulated(state, sim, myNext, eNext, myNewLen, eNewLen);
            worstCase = Math.min(worstCase, score);
        }

        if (!anyValidEnemy) return 100000; // enemy trapped = we win
        return worstCase;
    }

    // ============================================================
    // EVALUATE — FFA (multi-enemy)
    // ============================================================

    private static double evaluate(GameState state, Point myNext) {
        double score = basicSafety(state, myNext);
        if (score <= SCORE_CERTAIN_DEATH) return score;

        // Voronoi territory
        Point[] heads = buildHeadArray(myNext, state);
        int[] areas = computeVoronoi(state.W, state.H, state.isWrapped, state.blocked, heads);
        int myArea = areas[0];
        int maxEA = 0;
        for (int i = 1; i < areas.length; i++) maxEA = Math.max(maxEA, areas[i]);
        score += (myArea - maxEA) * W_TERRITORY;

        // Food
        score += foodScore(state, myNext);

        // Aggression
        score += aggressionScore(state, myNext);

        // Edge avoidance
        score += edgeScore(state, myNext);

        return score;
    }

    // ============================================================
    // EVALUATE SIMULATED (for minimax)
    // ============================================================

    private static double evalSimulated(GameState state, boolean[][] sim,
                                        Point myHead, Point eHead, int myLen, int eLen) {
        double score = 0;

        // Voronoi on simulated board
        int[] areas = computeVoronoi(state.W, state.H, state.isWrapped, sim, myHead, eHead);
        score += (areas[0] - areas[1]) * W_TERRITORY;

        // Space (flood fill on simulated board)
        int space = floodFillGrid(state.W, state.H, state.isWrapped, myHead, sim, myLen * 3);
        if (space < myLen) {
            score += SCORE_CERTAIN_DEATH + space * 1000;
        } else if (space < myLen * 2) {
            double t = (double) space / (myLen * 2);
            score += space * W_SPACE - (1 - t) * 5000;
        } else {
            score += space * W_SPACE;
        }

        // Food proximity
        score += foodScore(state, myHead);

        // Length advantage / distance to enemy
        int dist = manhattan(myHead, eHead, state.W, state.H, state.isWrapped);
        if (myLen > eLen) {
            score += (myLen - eLen) * 200;
            score -= dist * 30; // chase
        } else if (myLen < eLen) {
            score += dist * 30; // evade
        }

        // Edge
        score += edgeScore(state, myHead);

        return score;
    }

    // ============================================================
    // BASIC SAFETY (shared by all evaluation paths)
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
                score += (e.len >= state.myLen) ? -500000 : 5000;
            }
        }

        // Dangerous nodes for flood fill (bigger enemy head moves)
        Set<Point> dangerNodes = new HashSet<>();
        for (SnakeData e : state.aliveEnemies) {
            if (e.len >= state.myLen) {
                for (int[] dir : DIRS) {
                    Point p = e.head.add(dir);
                    if (state.isWrapped) p = state.wrap(p);
                    dangerNodes.add(p);
                }
            }
        }

        // Tiered flood fill
        Set<Point> safeAvoid = new HashSet<>(dangerNodes);
        safeAvoid.addAll(state.hazardPoints);
        int safeSpace = floodFill(state, next, safeAvoid);
        int totalSpace = floodFill(state, next, dangerNodes);

        int space = safeSpace;
        if (safeSpace < state.myLen && totalSpace >= state.myLen) {
            space = totalSpace; score -= 1000;
        } else if (safeSpace < state.myLen) {
            space = totalSpace;
        }

        if (space < state.myLen) {
            return SCORE_CERTAIN_DEATH + space * 1000;
        } else if (space < state.myLen * 2) {
            double t = (double) space / (state.myLen * 2);
            score += space * W_SPACE - (1 - t) * 5000;
        } else {
            score += space * W_SPACE;
        }

        // Corridor detection
        int open = 0;
        for (int[] d : DIRS) {
            Point nb = next.add(d);
            if (state.isWrapped) nb = state.wrap(nb);
            if (state.isValid(nb) && !state.isBlocked(nb)) open++;
        }
        score += open * 100;

        return score;
    }

    // ============================================================
    // VORONOI TERRITORY
    // ============================================================

    private static int[] computeVoronoi(int W, int H, boolean isWrapped,
                                        boolean[][] blocked, Point... heads) {
        int[][] dist = new int[W][H];
        int[][] owner = new int[W][H];
        for (int[] r : dist) Arrays.fill(r, Integer.MAX_VALUE);
        for (int[] r : owner) Arrays.fill(r, -1);

        Queue<int[]> q = new LinkedList<>();
        for (int i = 0; i < heads.length; i++) {
            if (heads[i] == null) continue;
            int hx = heads[i].x, hy = heads[i].y;
            if (isWrapped) { hx = (hx % W + W) % W; hy = (hy % H + H) % H; }
            if (hx >= 0 && hx < W && hy >= 0 && hy < H && !blocked[hx][hy] && dist[hx][hy] == Integer.MAX_VALUE) {
                dist[hx][hy] = 0;
                owner[hx][hy] = i;
                q.add(new int[]{hx, hy, 0, i});
            }
        }

        while (!q.isEmpty()) {
            int[] c = q.poll();
            int cx = c[0], cy = c[1], cd = c[2], co = c[3];
            if (cd > dist[cx][cy]) continue;
            if (cd == dist[cx][cy] && owner[cx][cy] != co) continue;

            for (int[] d : DIRS) {
                int nx = cx + d[0], ny = cy + d[1];
                if (isWrapped) { nx = (nx + W) % W; ny = (ny + H) % H; }
                if (nx < 0 || nx >= W || ny < 0 || ny >= H) continue;
                if (blocked[nx][ny]) continue;
                int nd = cd + 1;
                if (nd < dist[nx][ny]) {
                    dist[nx][ny] = nd;
                    owner[nx][ny] = co;
                    q.add(new int[]{nx, ny, nd, co});
                } else if (nd == dist[nx][ny] && owner[nx][ny] != co) {
                    owner[nx][ny] = -1; // contested
                }
            }
        }

        int[] areas = new int[heads.length];
        for (int x = 0; x < W; x++)
            for (int y = 0; y < H; y++)
                if (owner[x][y] >= 0 && owner[x][y] < heads.length)
                    areas[owner[x][y]]++;
        return areas;
    }

    // ============================================================
    // FOOD SCORING (length-aware)
    // ============================================================

    private static double foodScore(GameState state, Point next) {
        if (state.isConstrictor) return 0;

        int maxELen = 0;
        for (SnakeData e : state.aliveEnemies) maxELen = Math.max(maxELen, e.len);

        // Urgency: need food more if smaller, less if already big
        double urgency = 1.0;
        if (state.myLen > maxELen + 3) urgency = 0.3;
        else if (state.myLen < maxELen) urgency = 2.0;
        if (state.myHealth < 20) urgency = Math.max(urgency, 3.0);
        else if (state.myHealth < 50) urgency = Math.max(urgency, 1.5);

        double best = 0;
        for (Point f : state.foods) {
            int d = state.bfsDist(next, f);
            if (d == -1) continue;
            double val = 1000.0 * urgency;

            // Denial bonus
            for (SnakeData e : state.aliveEnemies) {
                int ed = state.dist(e.head, f);
                if (d <= ed && e.len < state.myLen) val += 1500;
            }

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
                // HUNTER: chase and cut off
                score -= d * W_AGGRESSION;
                int eDist = Math.abs(e.head.x - cx) + Math.abs(e.head.y - cy);
                int myDist = Math.abs(next.x - cx) + Math.abs(next.y - cy);
                if (myDist < eDist) score += 200; // between enemy and center
            } else if (state.myLen < e.len) {
                score += d * 5; // evade
            }
        }
        return score;
    }

    // ============================================================
    // EDGE AVOIDANCE
    // ============================================================

    private static double edgeScore(GameState state, Point next) {
        if (state.isConstrictor) return 0;
        double score = 0;
        int cx = state.W / 2, cy = state.H / 2;
        score -= (Math.abs(next.x - cx) + Math.abs(next.y - cy)) * 5;
        boolean xEdge = (next.x == 0 || next.x == state.W - 1);
        boolean yEdge = (next.y == 0 || next.y == state.H - 1);
        if (xEdge || yEdge) score -= 200;
        if (xEdge && yEdge) score -= 500; // corners are death traps
        return score;
    }

    // ============================================================
    // HELPERS
    // ============================================================

    private static Point[] buildHeadArray(Point myNext, GameState state) {
        Point[] heads = new Point[1 + state.aliveEnemies.size()];
        heads[0] = myNext;
        for (int i = 0; i < state.aliveEnemies.size(); i++)
            heads[i + 1] = state.aliveEnemies.get(i).head;
        return heads;
    }

    static int floodFill(GameState state, Point start, Set<Point> avoid) {
        boolean[][] visited = new boolean[state.W][state.H];
        for (int x = 0; x < state.W; x++)
            System.arraycopy(state.blocked[x], 0, visited[x], 0, state.H);
        for (Point p : avoid)
            if (state.isValid(p)) visited[p.x][p.y] = true;

        Queue<Point> q = new LinkedList<>();
        if (state.isValid(start) && !visited[start.x][start.y]) {
            q.add(start); visited[start.x][start.y] = true;
        }
        int count = 0;
        while (!q.isEmpty()) {
            Point p = q.poll(); count++;
            if (count >= state.myLen * 3) return count;
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
                int nx = p.x + d[0], ny = p.y + d[1];
                if (isWrapped) { nx = (nx + W) % W; ny = (ny + H) % H; }
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
        String myId;
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
        Set<Point> hazardPoints = new HashSet<>();

        GameState(JsonNode root) {
            JsonNode board = root.get("board");
            W = board.get("width").asInt();
            H = board.get("height").asInt();
            turn = root.get("turn").asInt();

            JsonNode you = root.get("you");
            myId = you.get("id").asText();
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
                    if (isValid(p)) {
                        hazards[p.x][p.y] = true;
                        hazardPoints.add(p);
                        if (isConstrictor) blocked[p.x][p.y] = true;
                    }
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
                        // TAIL: free for ANY snake if not about to eat
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
