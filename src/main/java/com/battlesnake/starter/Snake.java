package com.battlesnake.starter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

import static spark.Spark.*;

/**
 * GODMODE SNAKE v20.0 - APEX SERPENT
 * 
 * Features:
 * - Advanced Flood Fill with Hazard Awareness
 * - Dynamic Hunger & Starvation Prevention
 * - Enemy Head Prediction & Avoidance
 * - Aggressive Area Control
 * - Trap Detection
 */
public class Snake {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Logger LOG = LoggerFactory.getLogger(Snake.class);
    
    // Direction constants
    private static final int[][] DIRS = {{0, 1}, {0, -1}, {-1, 0}, {1, 0}};
    private static final String[] DIR_NAMES = {"up", "down", "left", "right"};

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
        r.put("author", "GODMODE-V2");
        r.put("color", "#FF0000"); // Blood Red
        r.put("head", "evil");
        r.put("tail", "sharp");
        return r;
    }

    static Map<String, String> move(JsonNode root) {
        long startTime = System.currentTimeMillis();
        
        // --- PARSE INPUT ---
        int W = root.get("board").get("width").asInt();
        int H = root.get("board").get("height").asInt();
        int turn = root.get("turn").asInt();
        
        JsonNode you = root.get("you");
        int myHealth = you.get("health").asInt();
        int myLen = you.get("body").size();
        String myId = you.get("id").asText();
        int headX = you.get("head").get("x").asInt();
        int headY = you.get("head").get("y").asInt();
        
        // Game Mode & Rules
        String gameMode = "standard";
        int hazardDamage = 15;
        JsonNode game = root.get("game");
        if (game != null) {
            JsonNode ruleset = game.get("ruleset");
            if (ruleset != null) {
                JsonNode n = ruleset.get("name");
                if (n != null) gameMode = n.asText().toLowerCase();
                JsonNode s = ruleset.get("settings");
                if (s != null) {
                    JsonNode h = s.get("hazardDamagePerTurn");
                    if (h != null) hazardDamage = h.asInt();
                }
            }
        }
        boolean isRoyale = gameMode.contains("royale");
        boolean isConstrictor = gameMode.contains("constrictor");
        boolean isWrapped = gameMode.contains("wrapped");

        // --- BUILD BOARD ---
        boolean[][] blocked = new boolean[W][H];
        boolean[][] hazards = new boolean[W][H];
        int[][] snakeMap = new int[W][H]; // Stores ID of snake at pos
        
        List<Point> foods = new ArrayList<>();
        List<Enemy> enemies = new ArrayList<>();
        
        // Mark Snakes
        for (JsonNode s : root.get("board").get("snakes")) {
            String id = s.get("id").asText();
            boolean isMe = id.equals(myId);
            int len = s.get("body").size();
            
            // Fix: Mark ALL body parts as blocked, including head
            for (JsonNode b : s.get("body")) {
                int x = b.get("x").asInt();
                int y = b.get("y").asInt();
                if (isValid(x, y, W, H)) {
                    blocked[x][y] = true;
                }
            }
            
            // Tail Logic: Tail might be free if snake eats
            // If they just ate, tail grows (stays blocked). If not, tail moves (freed).
            // We assume worst case (they eat) unless we are very sure.
            // For safety, keep tail blocked.
            
            if (!isMe) {
                Enemy e = new Enemy();
                e.id = id;
                e.len = len;
                e.head = new Point(s.get("head").get("x").asInt(), s.get("head").get("y").asInt());
                enemies.add(e);
            }
        }

        // Hazards
        if (root.get("board").has("hazards")) {
            for (JsonNode h : root.get("board").get("hazards")) {
                int x = h.get("x").asInt();
                int y = h.get("y").asInt();
                if (isValid(x, y, W, H)) {
                    hazards[x][y] = true;
                    // In Constrictor or deep Royale, treat hazard as blocked or heavily penalized
                    if (isConstrictor || (isRoyale && hazardDamage >= 50)) {
                        blocked[x][y] = true;
                    }
                }
            }
        }

        // Food
        for (JsonNode f : root.get("board").get("food")) {
            foods.add(new Point(f.get("x").asInt(), f.get("y").asInt()));
        }

        // --- GEN MOVES ---
        Map<String, Double> moveScores = new HashMap<>();
        
        // Critical Status
        boolean hungry = myHealth < 40 || (myLen < 10 && turn < 50);
        boolean starving = myHealth < 20;
        int maxEnemyLen = 0;
        for (Enemy e : enemies) maxEnemyLen = Math.max(maxEnemyLen, e.len);
        boolean smallest = myLen <= maxEnemyLen;

        for (int i = 0; i < 4; i++) {
            String move = DIR_NAMES[i];
            int nx = headX + DIRS[i][0];
            int ny = headY + DIRS[i][1];
            
            if (isWrapped) {
                nx = (nx + W) % W;
                ny = (ny + H) % H;
            }

            // 1. Basic Validity
            if (!isValid(nx, ny, W, H)) {
                moveScores.put(move, -999999.0); // Out of bounds
                continue;
            }
            if (blocked[nx][ny]) {
                moveScores.put(move, -999999.0); // Collision
                continue;
            }

            double score = 0.0;
            
            // 2. Head Collision Prediction (Lookahead)
            // Fix: Increase penalty for being near larger heads to verify cutoff
            for (Enemy e : enemies) {
                int dist = Math.abs(nx - e.head.x) + Math.abs(ny - e.head.y); // Manhattan (ignoring walls for verify)
                if (isWrapped) {
                    // Simple wrapped dist
                     dist = Math.min(Math.abs(nx - e.head.x), W - Math.abs(nx - e.head.x)) +
                            Math.min(Math.abs(ny - e.head.y), H - Math.abs(ny - e.head.y));
                }
                
                if (dist == 1) { // They could move to nx, ny
                    if (e.len >= myLen) {
                        score -= 500000; // HUGE penalty involved to ensure we don't start a head-to-head
                    } else {
                        score += 20000; // KILL OPPORTUNITY
                    }
                }
            }

            // 3. Flood Fill (Space)
            // Fix: Pessimistic Flood Fill - assume larger enemies cut us off
            int space = floodFill(nx, ny, W, H, blocked, hazards, isWrapped, myHealth > 50, enemies, myLen);
            
            if (space < myLen) {
                score -= 10000000; // DEAD END - Avoid at ALL costs
            } else if (space < myLen * 2) {
                score -= 50000; // Tight spot - only if necessary
            } else {
                if (isConstrictor) score += space * 50; // In Constrictor, space is EVERYTHING
                else score += space * 10; // More space is better
            }

            // 4. Hazards
            if (hazards[nx][ny]) {
                score -= hazardDamage * 1000; // Heavy hazard penalty
                int healthBuffer = isConstrictor ? 10 : hazardDamage + 10;
                if (myHealth < healthBuffer) score -= 10000000; // Don't die to hazard
            }

            // 5. Food
            // Starvation Fix: Check ALL foods, not just closest
            if (!foods.isEmpty()) {
                double bestFoodScore = -1;
                for (Point f : foods) {
                    // BFS distance from HEAD (not next move nx,ny) + 1 step
                    int dist = bfsDist(nx, ny, f.x, f.y, W, H, blocked, isWrapped);
                    
                    if (dist == -1) continue; // Unreachable
                    
                    // Fix: Clamp distance to avoid division by zero or Infinity
                    // If dist is 0 (we are ON food), treat as 1 (immediate reward)
                    double safeDist = Math.max(1.0, (double)dist);

                    double foodVal = 0;
                    if (isConstrictor) {
                         // CONSTRICTOR MODE: No food exists, or if it does, it's irrelevant. 
                         // Just in case some variant has it, we ignore it completely.
                         foodVal = 0; 
                    } else {
                        // STANDARD / ROYALE
                        if (starving) foodVal = 50000.0 / safeDist;
                        else if (hungry || smallest) foodVal = 2000.0 / safeDist;
                        else foodVal = 500.0 / safeDist;
                    }
                    
                    // Safety: Is this food close to a big enemy?
                    for (Enemy e : enemies) {
                        int eDist = Math.abs(f.x - e.head.x) + Math.abs(f.y - e.head.y); // Approx
                        if (eDist < dist && e.len >= myLen) {
                             foodVal -= 1000; // Dangerous food
                        }
                    }
                    
                    if (foodVal > bestFoodScore) bestFoodScore = foodVal;
                }
                if (bestFoodScore > 0) score += bestFoodScore;
            }

            // 6. Center Bias (for early game / standard)
            // Constrictor mode: Center is dangerous, stay away or use freely. No bias needed.
            if (!isRoyale && !isConstrictor && turn < 100) {
                 int distCenter = Math.abs(nx - W/2) + Math.abs(ny - H/2);
                 score -= distCenter * 10;
            }

            moveScores.put(move, score);
        }

        // Select Best Move
        String bestDir = "up";
        double maxScore = -Double.MAX_VALUE;
        
        for (Map.Entry<String, Double> entry : moveScores.entrySet()) {
            if (entry.getValue() > maxScore) {
                maxScore = entry.getValue();
                bestDir = entry.getKey();
            }
        }
        
        LOG.info("Turn {}: Best move {} score {}", turn, bestDir, maxScore);
        
        Map<String, String> result = new HashMap<>();
        result.put("move", bestDir);
        return result;
    }

    // --- HELPERS ---

    static boolean isValid(int x, int y, int W, int H) {
        return x >= 0 && x < W && y >= 0 && y < H;
    }

    static int floodFill(int sx, int sy, int W, int H, boolean[][] blocked, boolean[][] hazards, boolean isWrapped, boolean avoidHazards, List<Enemy> enemies, int myLen) {
        boolean[][] visited = new boolean[W][H];
        Queue<Point> q = new LinkedList<>();
        q.add(new Point(sx, sy));
        visited[sx][sy] = true;
        int count = 0;
        
        while (!q.isEmpty()) {
            Point p = q.poll();
            count++;
            if (count > 300) return 300; // Cap to save time
            
            for (int[] d : DIRS) {
                int nx = p.x + d[0];
                int ny = p.y + d[1];
                
                if (isWrapped) {
                    nx = (nx + W) % W;
                    ny = (ny + H) % H;
                }
                
                if (isValid(nx, ny, W, H) && !visited[nx][ny] && !blocked[nx][ny]) {
                    if (avoidHazards && hazards[nx][ny]) continue;
                    
                    // PESSIMISTIC CHECK: Is this cell adjacent to a larger enemy head?
                    boolean dangerous = false;
                    for (Enemy e : enemies) {
                        if (e.len >= myLen) {
                            int distToHead = Math.abs(nx - e.head.x) + Math.abs(ny - e.head.y);
                            if (isWrapped) {
                                distToHead = Math.min(Math.abs(nx - e.head.x), W - Math.abs(nx - e.head.x)) +
                                             Math.min(Math.abs(ny - e.head.y), H - Math.abs(ny - e.head.y));
                            }
                            if (distToHead <= 1) { // Checks adjacency (1 step away)
                                dangerous = true;
                                break;
                            }
                        }
                    }
                    if (dangerous) continue; // Treat as blocked

                    visited[nx][ny] = true;
                    q.add(new Point(nx, ny));
                }
            }
        }
        return count;
    }
                

    
    // Simple BFS for distance
    static int bfsDist(int sx, int sy, int tx, int ty, int W, int H, boolean[][] blocked, boolean isWrapped) {
        if (sx == tx && sy == ty) return 0;
        boolean[][] visited = new boolean[W][H];
        Queue<int[]> q = new LinkedList<>();
        q.add(new int[]{sx, sy, 0});
        visited[sx][sy] = true;
        
        while (!q.isEmpty()) {
            int[] curr = q.poll();
            int x = curr[0], y = curr[1], d = curr[2];
            
            if (x == tx && y == ty) return d;
            
            for (int[] dir : DIRS) {
                int nx = x + dir[0];
                int ny = y + dir[1];
                 if (isWrapped) {
                    nx = (nx + W) % W;
                    ny = (ny + H) % H;
                }
                if (isValid(nx, ny, W, H) && !visited[nx][ny] && !blocked[nx][ny]) {
                    visited[nx][ny] = true;
                    q.add(new int[]{nx, ny, d+1});
                }
            }
        }
        return -1;
    }

    static class Point {
        int x, y;
        public Point(int x, int y) { this.x = x; this.y = y; }
    }
    
    static class Enemy {
        String id;
        int len;
        Point head;
    }
}
