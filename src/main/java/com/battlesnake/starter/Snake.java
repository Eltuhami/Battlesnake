package com.battlesnake.starter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

import static spark.Spark.*;

/**
 * GODMODE SNAKE v21.0 - REFACTORED APEX (RESTORED)
 */
public class Snake {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Logger LOG = LoggerFactory.getLogger(Snake.class);

    // --- CONSTANTS ---
    private static final int[][] DIRS = {{0, 1}, {0, -1}, {-1, 0}, {1, 0}};
    private static final String[] DIR_NAMES = {"up", "down", "left", "right"};

    // Scoring Weights
    private static final double SCORE_IMPOSSIBLE = -1_000_000_000.0; // Walls, own body, direct hazard death
    private static final double SCORE_CERTAIN_DEATH = -10_000_000.0; // Trapped in small space
    private static final double SCORE_HAZARD = -100_000.0;           // Step into hazard
    private static final double SCORE_DANGER = -50_000.0;            // Head-to-head risk with bigger snake
    private static final double SCORE_FOOD_STARVING = 10_000.0;
    private static final double SCORE_FOOD_HUNGRY = 3_000.0;
    private static final double SCORE_FOOD_NORMAL = 1_000.0;

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
        r.put("author", "GODMODE-V21-RESTORED");
        r.put("color", "#FF0000"); // Blood Red (Restored)
        r.put("head", "evil");
        r.put("tail", "sharp");
        return r;
    }

    static Map<String, String> move(JsonNode root) {
        // 1. Parse State
        GameState state = new GameState(root);
        
        // 2. Score Moves
        Map<String, Double> scores = new HashMap<>();
        for (int i = 0; i < 4; i++) {
            String dirName = DIR_NAMES[i];
            Point next = state.myHead.add(DIRS[i]);
            double score = scoreMove(state, next, dirName);
            scores.put(dirName, score);
        }

        // 3. Select Best
        String bestDir = "up";
        double maxScore = -Double.MAX_VALUE;
        for (Map.Entry<String, Double> entry : scores.entrySet()) {
            if (entry.getValue() > maxScore) {
                maxScore = entry.getValue();
                bestDir = entry.getKey();
            }
        }

        LOG.info("Turn {}: Best move {} score {}", state.turn, bestDir, maxScore);
        
        Map<String, String> res = new HashMap<>();
        res.put("move", bestDir);
        return res;
    }

    private static double scoreMove(GameState state, Point next, String dirName) {
        // --- 1. ABSOLUTE CHECKS (The "Impossible" Stuff) ---
        
        // Bounds
        if (!state.isValid(next)) return SCORE_IMPOSSIBLE;
        
        // Instant Collision (Body/Walls)
        // Is it blocked?
        if (state.isBlocked(next)) {
             return SCORE_IMPOSSIBLE;
        }

        // --- 2. SURVIVAL & TRAPS ---
        
        double score = 0.0;

        // Hazard Check
        if (state.isHazard(next)) {
            score += SCORE_HAZARD * state.hazardDamage; 
            // If low health, avoid like the plague
            if (state.myHealth <= state.hazardDamage + 5) {
                return SCORE_IMPOSSIBLE; 
            }
        }

        // Head-to-Head Risk logic
        // If 'next' is adjacent to a larger enemy head, we die.
        for (Enemy e : state.enemies) {
             if (e.id.equals(state.myId)) continue;
             int dist = state.dist(next, e.head);
             if (dist == 1) { // They COULD move to 'next'
                 if (e.len >= state.myLen) {
                     // DANGER! They are bigger or equal.
                     score += SCORE_DANGER * 10; 
                 } else {
                     // We are bigger. Opportunity!
                     score += 5000; 
                 }
             }
        }

        // Create a set of "Future Enemy Head Positions" to treat as blocked in flood fill
        Set<Point> dangerousNodes = new HashSet<>();
        for (Enemy e : state.enemies) {
            if (e.id.equals(state.myId)) continue;
            // If they can kill us or trade, assume they WILL cut us off.
            if (e.len >= state.myLen) {
                for (int[] d : DIRS) {
                   Point p = e.head.add(d);
                   if (state.isWrapped) p = state.wrap(p);
                   dangerousNodes.add(p);
                }
            }
        }

        // Flood Fill / Space
        // Tiered Flood Fill for Royale:
        // 1. Calculate space assuming hazards are walls (Safe Space)
        Set<Point> safeAvoid = new HashSet<>(dangerousNodes);
        safeAvoid.addAll(state.hazardPoints);
        int safeSpace = floodFill(state, next, safeAvoid);
        
        // 2. Calculate space allowing hazards (Total Space) (Fallback)
        // Only trigger this fallback if Safe Space is critically low
        int totalSpace = floodFill(state, next, dangerousNodes);
        
        int space = safeSpace;
        int requiredSpace = state.myLen;

        // If safe space is insufficient, but total space is enough, rely on total space
        if (safeSpace < requiredSpace && totalSpace >= requiredSpace) {
            space = totalSpace;
            score -= 1000; // Prefer fully safe paths
        } else if (safeSpace < requiredSpace && totalSpace < requiredSpace) {
             // Trapped even with hazards
             space = totalSpace;
        }

        if (space < requiredSpace) {
            // TRAP!
            score += SCORE_CERTAIN_DEATH + (space * 1000); 
        } else {
            // Plenty of space.
            if (state.isConstrictor) {
                score += space * 50; 
            } else {
                score += space * 5; 
            }
        }

        // --- 3. FOOD & OBJECTIVES ---
        
        if (!state.isConstrictor) { 
            double foodScore = 0;
            for (Point f : state.foods) {
                int dist = state.bfsDist(next, f);
                if (dist == -1) continue;
                
                double val = 0;
                if (state.myHealth < 20) val = SCORE_FOOD_STARVING;
                else if (state.myHealth < 50 || state.isSmallest) val = SCORE_FOOD_HUNGRY;
                else val = SCORE_FOOD_NORMAL;
                
                // Food Denial Logic
                Enemy closestE = null;
                int closestEDist = 1000;
                
                for (Enemy e : state.enemies) {
                    if (e.id.equals(state.myId)) continue;
                    int ed = state.dist(e.head, f); // Using manhattan for speed approximation
                    if (ed < closestEDist) {
                        closestEDist = ed;
                        closestE = e;
                    }
                }
                
                // Only chase/deny if we are closer or equal distance
                if (closestE != null && closestE.len < state.myLen && dist <= closestEDist) {
                     val += 1000; // Bonus for denial
                }

                double rawScore = val / (dist + 1); 
                if (rawScore > foodScore) foodScore = rawScore;
            }
            score += foodScore;
        }

        // --- 4. STRATEGIC BIAS ---
        if (!state.isRoyale && !state.isConstrictor && state.turn < 100) {
            int cx = state.W / 2;
            int cy = state.H / 2;
            int distCenter = Math.abs(next.x - cx) + Math.abs(next.y - cy);
            score -= distCenter * 10;
        }

        // --- 5. DUEL MODE (1v1) ---
        if (state.enemies.size() == 1) {
             Enemy e = state.enemies.get(0);
             int distToEnemy = state.dist(next, e.head);
             
             if (state.myLen > e.len) {
                 // HUNTER MODE: We are bigger. Crowd them.
                 score -= distToEnemy * 50; 
             } else {
                 // PREY MODE: We are smaller. Keep distance.
                 score += distToEnemy * 50; 
                 // Vital: Stay off the walls to avoid getting trapped
                 if (next.x == 0 || next.x == state.W - 1 || next.y == 0 || next.y == state.H - 1) {
                     score -= 5000; 
                 }
             }
        }

        return score;
    }

    // --- HELPERS ---

    static int floodFill(GameState state, Point start, Set<Point> avoid) {
        boolean[][] visited = new boolean[state.W][state.H];
        // Mark all known blocks
        for(int x=0; x<state.W; x++) {
            System.arraycopy(state.blocked[x], 0, visited[x], 0, state.H);
        }
        
        // Mark "Avoid" spots (Potential Enemy Moves) as visited/blocked
        for (Point p : avoid) {
            if (state.isValid(p)) {
                 visited[p.x][p.y] = true;
            }
        }
        
        Queue<Point> q = new LinkedList<>();
        if (state.isValid(start) && !visited[start.x][start.y]) {
            q.add(start);
            visited[start.x][start.y] = true;
        }
        
        int count = 0;
        
        while(!q.isEmpty()) {
            Point p = q.poll();
            count++;
            
            if (count >= state.myLen * 2) return count;

            for (int[] d : DIRS) {
                Point n = p.add(d);
                if (state.isWrapped) n = state.wrap(n);
                
                if (state.isValid(n) && !visited[n.x][n.y]) {
                    visited[n.x][n.y] = true;
                    q.add(n);
                }
            }
        }
        return count;
    }

    // --- DATA CLASSES ---

    static class GameState {
        int W, H, turn;
        String myId;
        int myHealth, myLen;
        Point myHead;
        
        boolean[][] blocked;
        boolean[][] hazards;
        boolean isRoyale, isConstrictor, isWrapped;
        int hazardDamage = 0;
        
        List<Point> foods = new ArrayList<>();
        List<Enemy> enemies = new ArrayList<>();
        Set<Point> hazardPoints = new HashSet<>();
        boolean isSmallest = false;

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

            JsonNode game = root.get("game");
            String rules = "standard";
            if (game != null && game.has("ruleset")) {
                rules = game.get("ruleset").get("name").asText().toLowerCase();
                JsonNode settings = game.get("ruleset").get("settings");
                if (settings != null && settings.has("hazardDamagePerTurn")) {
                    hazardDamage = settings.get("hazardDamagePerTurn").asInt();
                } else {
                    hazardDamage = 14; 
                }
            } else {
                hazardDamage = 14;
            }
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
                        if (isConstrictor) {
                            blocked[p.x][p.y] = true;
                        }
                    }
                }
            }
            
            for (JsonNode f : board.get("food")) {
                foods.add(new Point(f.get("x").asInt(), f.get("y").asInt()));
            }

            int maxLen = 0;
            for (JsonNode s : board.get("snakes")) {
                String id = s.get("id").asText();
                int len = s.get("body").size();
                if (!id.equals(myId)) maxLen = Math.max(maxLen, len);
                
                List<Point> body = new ArrayList<>();
                for (JsonNode b : s.get("body")) {
                    body.add(new Point(b.get("x").asInt(), b.get("y").asInt()));
                }
                
                if (!id.equals(myId)) enemies.add(new Enemy(id, len, body.get(0)));

                for (int i = 0; i < body.size(); i++) {
                    Point p = body.get(i);
                    if (!isValid(p)) continue;
                    
                    if (i == body.size() - 1) { 
                        // TAIL CHASING LOGIC
                        boolean isMe = id.equals(myId);
                        boolean isEating = false;
                        
                        List<Point> headNeighbors = new ArrayList<>();
                        for (int[] d : DIRS) {
                            Point n = new Point(body.get(0).x + d[0], body.get(0).y + d[1]);
                            if (isWrapped) n = wrap(n);
                            headNeighbors.add(n);
                        }
                        
                        for (Point f : foods) {
                            if (headNeighbors.contains(f)) {
                                isEating = true;
                                break;
                            }
                        }
                        
                        if (isMe && !isEating) {
                             blocked[p.x][p.y] = false;
                        } else {
                             blocked[p.x][p.y] = true;
                        }
                    } else {
                        blocked[p.x][p.y] = true;
                    }
                }
            }
            isSmallest = myLen <= maxLen;
        }

        boolean isValid(Point p) {
            if (isWrapped) return true; 
            return p.x >= 0 && p.x < W && p.y >= 0 && p.y < H;
        }

        boolean isBlocked(Point p) {
            Point check = isWrapped ? wrap(p) : p;
            if (!isValid(check)) return true;
            return blocked[check.x][check.y];
        }

        boolean isHazard(Point p) {
            Point check = isWrapped ? wrap(p) : p;
            return isValid(check) && hazards[check.x][check.y];
        }
        
        Point wrap(Point p) {
            int nx = (p.x % W + W) % W;
            int ny = (p.y % H + H) % H;
            return new Point(nx, ny);
        }
        
        int dist(Point a, Point b) {
            if (isWrapped) {
                 int dx = Math.abs(a.x - b.x);
                 int dy = Math.abs(a.y - b.y);
                 return Math.min(dx, W - dx) + Math.min(dy, H - dy);
            }
            return Math.abs(a.x - b.x) + Math.abs(a.y - b.y);
        }
        
        int bfsDist(Point start, Point target) {
            if (start.equals(target)) return 0;
            boolean[][] v = new boolean[W][H];
            Queue<int[]> q = new LinkedList<>();
            q.add(new int[]{start.x, start.y, 0});
            v[start.x][start.y] = true;
            
            while(!q.isEmpty()) {
                int[] c = q.poll();
                int x=c[0], y=c[1], d=c[2];
                if (x == target.x && y == target.y) return d;
                
                for(int[] dir : DIRS) {
                    int nx = x + dir[0];
                    int ny = y + dir[1];
                     if (isWrapped) {
                        nx = (nx + W) % W;
                        ny = (ny + H) % H;
                    }
                    if (isValid(new Point(nx, ny)) && !v[nx][ny] && !blocked[nx][ny]) {
                        v[nx][ny] = true;
                        q.add(new int[]{nx, ny, d+1});
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
            Point point = (Point) o;
            return x == point.x && y == point.y;
        }
        @Override public int hashCode() { return Objects.hash(x, y); }
    }

    static class Enemy {
        String id;
        int len;
        Point head;
        public Enemy(String id, int len, Point head) { this.id = id; this.len = len; this.head = head; }
    }
}
