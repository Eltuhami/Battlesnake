package com.battlesnake.starter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static spark.Spark.*;

/**
 * GODMODE SNAKE v24.0 - SMARTER PREDATOR
 * Changes from v23:
 * 1. Real BFS pathfinding (not manhattan) for food scoring
 * 2. Per-game history (concurrency-safe, no cross-game pollution)
 * 3. Tail safety in Voronoi (own+enemy tails are passable)
 * 4. Food contest detection (avoid food enemy reaches first)
 * 5. Hazard damage penalty
 * 6. Graduated anti-trap (not just binary death)
 * 7. Smarter enemy prediction (food-seeking + aggression)
 * 8. Enhanced debug logging per move
 * 9. Larger history window (20 moves)
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

    // Strategy weights (V24 Tuned)
    private static final double W_SPACE      = 12.0;
    private static final double W_AGGRESSION = 50.0;
    private static final double W_CENTER     = 5.0;

    // FIX 3: Per-game history (concurrency-safe)
    private static final ConcurrentHashMap<String, LinkedList<Point>> GAME_HISTORIES = new ConcurrentHashMap<>();
    private static final int HISTORY_SIZE = 20; // FIX 4: Larger window

    // ============================================================
    // MAIN + ROUTES
    // ============================================================

    public static void main(String[] args) {
        String port = System.getProperty("PORT", "8082");
        port(Integer.parseInt(port));
        get("/", (req, res) -> JSON.writeValueAsString(index()));
        post("/start", (req, res) -> {
            JsonNode root = JSON.readTree(req.body());
            String gameId = root.get("game").get("id").asText();
            GAME_HISTORIES.put(gameId, new LinkedList<>());
            return "{}";
        });
        post("/move", (req, res) -> JSON.writeValueAsString(move(JSON.readTree(req.body()))));
        post("/end", (req, res) -> {
            JsonNode root = JSON.readTree(req.body());
            String gameId = root.get("game").get("id").asText();
            GAME_HISTORIES.remove(gameId); // Cleanup
            return "{}";
        });
    }

    static Map<String, String> index() {
        Map<String, String> r = new HashMap<>();
        r.put("apiversion", "1");
        r.put("author", "GODMODE-V24-PREDATOR");
        r.put("color", "#8B0000");
        r.put("head", "fang");
        r.put("tail", "hook");
        return r;
    }

    // ============================================================
    // MOVE DECISION
    // ============================================================

    static Map<String, String> move(JsonNode root) {
        long startTime = System.currentTimeMillis();
        GameState state = new GameState(root);
        String gameId = root.get("game").get("id").asText();

        // FIX 3: Per-game history
        LinkedList<Point> history = GAME_HISTORIES.computeIfAbsent(gameId, k -> new LinkedList<>());
        if (history.isEmpty() || !history.getLast().equals(state.myHead)) {
            history.add(state.myHead);
        }
        while (history.size() > HISTORY_SIZE) history.removeFirst();

        String bestDir = "up";
        double maxScore = -Double.MAX_VALUE;

        int depth = (state.aliveEnemies.size() > 2) ? 2 : 3;
        if (state.isConstrictor) depth = 2;

        // FIX 9: Track per-move scores for debug logging
        StringBuilder debugLog = new StringBuilder();

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

            // === TOP-LEVEL SAFETY: Head collision risk ===
            boolean headCollisionRisk = false;
            for (SnakeData e : state.aliveEnemies) {
                if (state.myLen > e.len) continue;
                for (int[] ed : DIRS) {
                    Point enemyNext = e.head.add(ed);
                    if (state.isWrapped) enemyNext = state.wrap(enemyNext);
                    if (enemyNext.equals(next)) {
                        headCollisionRisk = true;
                        break;
                    }
                }
                if (headCollisionRisk) break;
            }

            double score = search(state, next, depth, startTime, history);

            if (headCollisionRisk) {
                score -= 500_000.0;
            }

            debugLog.append(String.format(" %s=%.0f", DIR_NAMES[i], score));

            if (score > maxScore) {
                maxScore = score;
                bestDir = DIR_NAMES[i];
            }
        }

        long timeTaken = System.currentTimeMillis() - startTime;
        // FIX 9: Enhanced logging
        LOG.info("T{} {} sc={} ({}ms) [{}]",
                state.turn, bestDir, String.format("%.0f", maxScore), timeTaken, debugLog.toString().trim());

        Map<String, String> res = new HashMap<>();
        res.put("move", bestDir);
        return res;
    }

    // ============================================================
    // PREDICTIVE SEARCH
    // ============================================================

    private static double search(GameState state, Point myMove, int depth, long startTime,
                                  LinkedList<Point> history) {
        GameState nextState = state.cloneState();
        nextState.advanceMySnake(myMove);

        if (nextState.amIDead()) return SCORE_CERTAIN_DEATH;
        if (nextState.hasWon()) return SCORE_WIN;

        if (depth == 0 || System.currentTimeMillis() - startTime > 350) {
            return evaluate(nextState, myMove, history);
        }

        nextState.advanceEnemiesPredictively();

        if (nextState.amIDead()) return SCORE_CERTAIN_DEATH;

        double bestVal = -Double.MAX_VALUE;
        boolean canMove = false;

        for (int[] d : DIRS) {
            Point p = nextState.myHead.add(d);
            if (nextState.isWrapped) p = nextState.wrap(p);

            if (!nextState.isValid(p) || nextState.isBlocked(p)) continue;

            canMove = true;
            double val = search(nextState, p, depth - 1, startTime, history);
            bestVal = Math.max(bestVal, val);
        }

        if (!canMove) return SCORE_CERTAIN_DEATH;

        return bestVal * 0.99;
    }

    // ============================================================
    // EVALUATE
    // ============================================================

    private static double evaluate(GameState state, Point myHead, LinkedList<Point> history) {
        if (state.isConstrictor) {
            return evaluateConstrictor(state, myHead, history);
        }

        double score = 0;

        // FIX 5: Unblock tails before Voronoi (they move next turn)
        List<Point> unblockedTails = new ArrayList<>();
        // My tail
        if (!state.myBody.isEmpty()) {
            Point myTail = state.myBody.get(state.myBody.size() - 1);
            if (state.isValid(myTail)) {
                Point wt = state.isWrapped ? state.wrap(myTail) : myTail;
                if (wt.x >= 0 && wt.x < state.W && wt.y >= 0 && wt.y < state.H && state.blocked[wt.x][wt.y]) {
                    state.blocked[wt.x][wt.y] = false;
                    unblockedTails.add(wt);
                }
            }
        }
        // Enemy tails
        for (SnakeData e : state.aliveEnemies) {
            if (!e.body.isEmpty()) {
                Point eTail = e.body.get(e.body.size() - 1);
                Point wt = state.isWrapped ? state.wrap(eTail) : eTail;
                if (wt.x >= 0 && wt.x < state.W && wt.y >= 0 && wt.y < state.H && state.blocked[wt.x][wt.y]) {
                    state.blocked[wt.x][wt.y] = false;
                    unblockedTails.add(wt);
                }
            }
        }

        // 1. VORONOI TERRITORY
        int safeSpace = bfsVoronoi(state, myHead);

        // FIX 8: Graduated anti-trap instead of binary death
        if (safeSpace < state.myLen / 2) {
            score += SCORE_CERTAIN_DEATH + safeSpace * 1000;
        } else if (safeSpace < state.myLen) {
            score -= 500_000.0; // Heavy penalty but not certain death
            score += safeSpace * W_SPACE;
        } else if (safeSpace < state.myLen * 2) {
            score -= 50_000.0; // Warning
            score += safeSpace * W_SPACE;
        } else {
            score += safeSpace * W_SPACE;
        }

        // Re-block tails after Voronoi (restore state for other callers)
        for (Point t : unblockedTails) {
            state.blocked[t.x][t.y] = true;
        }

        // If already certain death, return early
        if (score < SCORE_CERTAIN_DEATH / 2) return score;

        // 1b. EDGE PENALTY
        if (!state.aliveEnemies.isEmpty() && !state.isWrapped) {
            int edgeDist = Math.min(Math.min(myHead.x, state.W - 1 - myHead.x),
                                    Math.min(myHead.y, state.H - 1 - myHead.y));
            if (edgeDist == 0) score -= 500.0;
            else if (edgeDist == 1) score -= 200.0;
        }

        // 2. FOOD (with contest detection)
        score += foodScore(state, myHead);

        // 3. AGGRESSION
        score += aggressionScore(state, myHead);

        // 4. BOREDOM (Anti-Loop) — uses per-game history
        if (history.contains(myHead)) {
            score -= 50_000.0;
        }

        // 5. CENTER BIAS
        int cx = state.W / 2, cy = state.H / 2;
        int distToCenter = Math.abs(myHead.x - cx) + Math.abs(myHead.y - cy);
        score -= distToCenter * W_CENTER;

        // FIX 7: HAZARD PENALTY
        if (state.hazards != null) {
            Point wh = state.isWrapped ? state.wrap(myHead) : myHead;
            if (wh.x >= 0 && wh.x < state.W && wh.y >= 0 && wh.y < state.H
                    && state.hazards[wh.x][wh.y]) {
                // Hazard sauce does 15 damage per turn (standard)
                double hazardPenalty = 2000.0;
                if (state.myHealth < 40) hazardPenalty = 10_000.0; // Critical if low health
                score -= hazardPenalty;
            }
        }

        return score;
    }

    private static double evaluateConstrictor(GameState state, Point myHead, LinkedList<Point> history) {
         double score = 0;

         int space = floodFillGrid(state.W, state.H, state.isWrapped, myHead, state.blocked, 1000);
         score += space * 100.0;

         if (space < state.myLen + 10) score -= 1_000_000;

         int cx = state.W / 2, cy = state.H / 2;
         int dist = Math.abs(myHead.x - cx) + Math.abs(myHead.y - cy);
         score -= dist * 10.0;

         if (history.contains(myHead)) score -= 50_000.0;

         return score;
    }

    // ============================================================
    // SCORING HELPERS
    // ============================================================

    private static double foodScore(GameState state, Point next) {
        if (state.isConstrictor) return 0;

        int maxELen = 0;
        for (SnakeData e : state.aliveEnemies) maxELen = Math.max(maxELen, e.len);

        double urgency = 1.0;
        if (state.myLen > maxELen + 2) urgency = 0.5;
        else if (state.myLen < maxELen) urgency = 3.0;
        if (state.myHealth < 25) urgency = 20.0;
        else if (state.myHealth < 40) urgency = 10.0;
        else if (state.myHealth < 60) urgency = 5.0;
        if (urgency < 0.5) urgency = 0.5;

        double best = 0;
        for (Point f : state.foods) {
            // FIX 1: Use real BFS distance instead of manhattan
            int myDist = state.bfsDist(next, f);
            if (myDist == -1) continue; // Unreachable

            // FIX 6: Food contest — check if enemy is closer
            boolean contested = false;
            for (SnakeData e : state.aliveEnemies) {
                if (e.len >= state.myLen) { // Only worry about equal/larger enemies
                    int eDist = state.dist(e.head, f); // Manhattan for enemies (fast approx)
                    if (eDist < myDist) {
                        contested = true;
                        break;
                    }
                }
            }

            double val = 5000.0 * urgency;
            double s = val / (myDist + 1);

            if (contested) {
                // Reduce value of contested food significantly
                // Unless we're starving, in which case we still need it
                if (state.myHealth > 40) {
                    s *= 0.2; // 80% discount
                } else {
                    s *= 0.6; // Still discount but less when hungry
                }
            }

            if (s > best) best = s;
        }
        return best;
    }

    private static double aggressionScore(GameState state, Point next) {
        double score = 0;
        for (SnakeData e : state.aliveEnemies) {
            int d = state.dist(next, e.head);
            if (state.myLen > e.len) {
                // Hunt smaller snakes
                score += (1000.0 / (d + 1)) * W_AGGRESSION;
            } else if (state.myLen == e.len) {
                // Equal size: moderate avoidance
                if (d <= 2) score -= 5_000.0;
            } else {
                // Smaller: graduated avoidance
                if (d <= 1) score -= 10_000.0;       // Very close = very bad
                else if (d <= 2) score -= 3_000.0;
                else score -= 1_000.0 / (d + 1);     // Soft gradient
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

        if (sx >= 0 && sx < W && sy >= 0 && sy < H) {
            q.add(new Point(sx, sy));
            v[sx][sy] = true;
        }

        int count = 0;
        while (!q.isEmpty()) {
            Point p = q.poll();
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

    // Multi-source BFS Voronoi
    static int bfsVoronoi(GameState state, Point myHead) {
        int W = state.W;
        int H = state.H;
        int[][] dist = new int[W][H];
        for (int[] row : dist) Arrays.fill(row, -1);
        int[][] owner = new int[W][H]; // 0=me, 1=enemy

        Queue<int[]> q = new LinkedList<>();

        // Seed enemies first (pessimistic)
        for (SnakeData e : state.aliveEnemies) {
            Point eh = e.head;
            if (state.isWrapped) eh = state.wrap(eh);
            if (state.isValid(eh) && eh.x >= 0 && eh.x < W && eh.y >= 0 && eh.y < H) {
                if (dist[eh.x][eh.y] == -1) {
                    dist[eh.x][eh.y] = 0;
                    owner[eh.x][eh.y] = 1;
                    q.add(new int[]{eh.x, eh.y});
                }
            }
        }

        // Seed me
        int mx = myHead.x;
        int my = myHead.y;
        if (state.isWrapped) {
            mx = (mx % W + W) % W;
            my = (my % H + H) % H;
        }
        if (mx >= 0 && mx < W && my >= 0 && my < H && dist[mx][my] == -1) {
            dist[mx][my] = 0;
            owner[mx][my] = 0;
            q.add(new int[]{mx, my});
        }

        int myCount = 0;

        while (!q.isEmpty()) {
            int[] cur = q.poll();
            int cx = cur[0];
            int cy = cur[1];
            int d = dist[cx][cy];
            int o = owner[cx][cy];

            if (o == 0) myCount++;

            if (myCount > state.myLen * 2 && myCount > 50) return myCount;

            for (int[] dir : DIRS) {
                int nx = cx + dir[0];
                int ny = cy + dir[1];
                if (state.isWrapped) {
                    nx = (nx % W + W) % W;
                    ny = (ny % H + H) % H;
                }
                if (nx < 0 || nx >= W || ny < 0 || ny >= H) continue;
                if (state.blocked[nx][ny]) continue;
                if (dist[nx][ny] != -1) continue;

                dist[nx][ny] = d + 1;
                owner[nx][ny] = o;
                q.add(new int[]{nx, ny});
            }
        }
        return myCount;
    }

    // ============================================================
    // GAME STATE & SIMULATION
    // ============================================================

    static class GameState {
        int W, H, turn;
        int myHealth, myLen;
        Point myHead;
        List<Point> myBody = new ArrayList<>();
        boolean[][] blocked;
        boolean[][] hazards;
        boolean isConstrictor, isWrapped;
        int hazardDamage = 15; // Standard hazard damage
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

            for (JsonNode b : you.get("body")) {
                myBody.add(new Point(b.get("x").asInt(), b.get("y").asInt()));
            }

            JsonNode game = root.get("game");
            String rules = "standard";
            if (game != null && game.has("ruleset")) {
                rules = game.get("ruleset").get("name").asText().toLowerCase();
                // Check for hazard damage override
                if (game.get("ruleset").has("settings")
                        && game.get("ruleset").get("settings").has("hazardDamagePerTurn")) {
                    hazardDamage = game.get("ruleset").get("settings").get("hazardDamagePerTurn").asInt(15);
                }
            }
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
            }

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

        GameState() {}

        GameState cloneState() {
            GameState s = new GameState();
            s.W = W; s.H = H; s.turn = turn;
            s.myHealth = myHealth; s.myLen = myLen; s.myHead = myHead;
            s.myBody = new ArrayList<>(myBody);
            s.blocked = copyGrid(blocked, W, H);
            s.hazards = copyGrid(hazards, W, H);
            s.isConstrictor = isConstrictor; s.isWrapped = isWrapped;
            s.hazardDamage = hazardDamage;
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

            boolean growing = foods.contains(nextHead);

            Point tail = null;
            if (!growing && !myBody.isEmpty()) {
                tail = myBody.get(myBody.size() - 1);
                unmarkBlocked(tail);
            }

            if (!isValid(nextHead) || blocked[nextHead.x][nextHead.y]) {
                myHealth = 0;
                if (tail != null) markBlocked(tail);
            } else {
                myHealth--;
                myBody.add(0, nextHead);
                markBlocked(nextHead);

                if (growing) {
                     myLen++;
                     myHealth = 100;
                     foods.remove(nextHead);
                     if (tail != null) markBlocked(tail);
                } else {
                    if (!myBody.isEmpty()) {
                        myBody.remove(myBody.size() - 1);
                    }
                }
            }
            myHead = nextHead;
        }

        // FIX 2: Smarter enemy prediction — enemies seek food + avoid us
        void advanceEnemiesPredictively() {
            for (SnakeData e : aliveEnemies) {
                Point tail = null;
                if (!e.body.isEmpty()) {
                    tail = e.body.get(e.body.size() - 1);
                    unmarkBlocked(tail);
                }

                Point bestEMove = null;
                double bestEScore = -Double.MAX_VALUE;

                for (int[] d : DIRS) {
                    Point en = e.head.add(d);
                    if (isWrapped) en = wrap(en);

                    if (!isValid(en) || blocked[en.x][en.y]) continue;

                    double sc = 0;

                    // Space awareness
                    sc += floodFillGrid(W, H, isWrapped, en, blocked, 20) * 10;

                    // Food seeking (enemies want food too!)
                    if (e.health < 50) {
                        for (Point f : foods) {
                            int fd = manhattan(en, f, W, H, isWrapped);
                            sc += 200.0 / (fd + 1);
                        }
                    }

                    // Aggression: if enemy is larger, they might hunt us
                    if (e.len > myLen) {
                        int distToMe = manhattan(en, myHead, W, H, isWrapped);
                        sc += 100.0 / (distToMe + 1); // move toward us
                    } else if (e.len <= myLen) {
                        int distToMe = manhattan(en, myHead, W, H, isWrapped);
                        if (distToMe <= 2) sc -= 200.0; // avoid us if smaller
                    }

                    sc += 500; // Base viability

                    if (sc > bestEScore) {
                        bestEScore = sc;
                        bestEMove = en;
                    }
                }

                if (bestEMove != null) {
                    e.head = bestEMove;
                    e.body.add(0, bestEMove);
                    markBlocked(bestEMove);

                    if (foods.contains(bestEMove)) {
                        e.len++;
                        e.health = 100;
                        foods.remove(bestEMove);
                        if (tail != null) markBlocked(tail);
                    } else {
                        if (!e.body.isEmpty()) {
                            e.body.remove(e.body.size() - 1);
                        }
                    }
                } else {
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

        // FIX 1: Real BFS pathfinding instead of manhattan
        int bfsDist(Point start, Point target) {
            if (start.equals(target)) return 0;

            int sw = start.x, sh = start.y;
            int tw = target.x, th = target.y;
            if (isWrapped) {
                sw = (sw % W + W) % W; sh = (sh % H + H) % H;
                tw = (tw % W + W) % W; th = (th % H + H) % H;
            }

            // Bounds check
            if (sw < 0 || sw >= W || sh < 0 || sh >= H) return -1;
            if (tw < 0 || tw >= W || th < 0 || th >= H) return -1;

            boolean[][] visited = new boolean[W][H];
            Queue<int[]> q = new LinkedList<>();

            visited[sw][sh] = true;
            q.add(new int[]{sw, sh, 0});

            int maxDist = 30; // Cap for performance

            while (!q.isEmpty()) {
                int[] cur = q.poll();
                int cx = cur[0], cy = cur[1], d = cur[2];

                if (d >= maxDist) continue;

                for (int[] dir : DIRS) {
                    int nx = cx + dir[0];
                    int ny = cy + dir[1];
                    if (isWrapped) {
                        nx = (nx % W + W) % W;
                        ny = (ny % H + H) % H;
                    }
                    if (nx < 0 || nx >= W || ny < 0 || ny >= H) continue;

                    if (nx == tw && ny == th) return d + 1; // Found!

                    if (!visited[nx][ny] && !blocked[nx][ny]) {
                        visited[nx][ny] = true;
                        q.add(new int[]{nx, ny, d + 1});
                    }
                }
            }
            return -1; // Unreachable
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
