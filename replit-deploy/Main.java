import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

import static spark.Spark.*;

/**
 * GODMODE SNAKE v16.0 - DOMINANT PREDATOR
 * Optimized for Replit deployment
 */
public class Main {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Logger LOG = LoggerFactory.getLogger(Main.class);
    private static final int[][] DIRS = {{0,1},{0,-1},{-1,0},{1,0}};
    private static final String[] DIR_NAMES = {"up", "down", "left", "right"};

    public static void main(String[] args) {
        // Replit uses PORT environment variable
        String portStr = System.getenv("PORT");
        if (portStr == null) portStr = "8080";
        int portNum = Integer.parseInt(portStr);
        
        port(portNum);
        get("/", (req, res) -> {
            res.type("application/json");
            return JSON.writeValueAsString(index());
        });
        post("/start", (req, res) -> {
            res.type("application/json");
            return "{}";
        });
        post("/move", (req, res) -> {
            res.type("application/json");
            return JSON.writeValueAsString(move(JSON.readTree(req.body())));
        });
        post("/end", (req, res) -> {
            res.type("application/json");
            return "{}";
        });
        
        System.out.println("GODMODE Snake running on port " + portNum);
    }

    static Map<String, String> index() {
        Map<String, String> r = new HashMap<>();
        r.put("apiversion", "1");
        r.put("author", "GODMODE");
        r.put("color", "#FF0000");
        r.put("head", "evil");
        r.put("tail", "sharp");
        return r;
    }

    static Map<String, String> move(JsonNode r) {
        int W = r.get("board").get("width").asInt();
        int H = r.get("board").get("height").asInt();
        int turn = r.get("turn").asInt();
        JsonNode you = r.get("you");
        int hp = you.get("health").asInt();
        int len = you.get("body").size();
        String myId = you.get("id").asText();
        int hx = you.get("head").get("x").asInt();
        int hy = you.get("head").get("y").asInt();
        JsonNode myBody = you.get("body");
        int tailX = myBody.get(len - 1).get("x").asInt();
        int tailY = myBody.get(len - 1).get("y").asInt();
        
        String gameMode = "standard";
        int hazardDamage = 15;
        JsonNode game = r.get("game");
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
        
        boolean isMaze = gameMode.contains("maze");
        boolean isRoyale = gameMode.contains("royale");
        boolean isWrapped = gameMode.contains("wrapped");
        
        boolean[][] blocked = new boolean[W][H];
        boolean[][] hazard = new boolean[W][H];
        List<int[]> enemies = new ArrayList<>();
        List<int[]> smallerEnemies = new ArrayList<>();
        List<int[]> biggerEnemies = new ArrayList<>();
        int maxEnemyLen = 0;
        int aliveEnemies = 0;
        
        for (JsonNode snake : r.get("board").get("snakes")) {
            JsonNode bodyArr = snake.get("body");
            int sLen = bodyArr.size();
            int sHp = snake.get("health").asInt();
            boolean isMe = snake.get("id").asText().equals(myId);
            
            if (!isMe) {
                aliveEnemies++;
                int ex = snake.get("head").get("x").asInt();
                int ey = snake.get("head").get("y").asInt();
                enemies.add(new int[]{ex, ey, sLen, sHp});
                if (sLen > maxEnemyLen) maxEnemyLen = sLen;
                if (sLen < len) smallerEnemies.add(new int[]{ex, ey, sLen, sHp});
                else biggerEnemies.add(new int[]{ex, ey, sLen, sHp});
            }
            
            int startIdx = isMe ? 1 : 0;
            for (int j = startIdx; j < sLen - 1; j++) {
                int bx = bodyArr.get(j).get("x").asInt();
                int by = bodyArr.get(j).get("y").asInt();
                if (valid(bx, by, W, H)) blocked[bx][by] = true;
            }
            
            if (sLen >= 2) {
                int t1x = bodyArr.get(sLen-1).get("x").asInt(), t1y = bodyArr.get(sLen-1).get("y").asInt();
                int t2x = bodyArr.get(sLen-2).get("x").asInt(), t2y = bodyArr.get(sLen-2).get("y").asInt();
                if (t1x == t2x && t1y == t2y && valid(t1x, t1y, W, H)) blocked[t1x][t1y] = true;
            }
        }
        
        JsonNode hazards = r.get("board").get("hazards");
        if (hazards != null) {
            for (JsonNode h : hazards) {
                int hzx = h.get("x").asInt(), hzy = h.get("y").asInt();
                if (valid(hzx, hzy, W, H)) { 
                    hazard[hzx][hzy] = true;
                    if (isMaze || hazardDamage >= 100) blocked[hzx][hzy] = true;
                }
            }
        }
        boolean inHazard = hazard[hx][hy];
        
        List<int[]> foods = new ArrayList<>();
        for (JsonNode f : r.get("board").get("food")) {
            int fx = f.get("x").asInt(), fy = f.get("y").asInt();
            foods.add(new int[]{fx, fy, Math.abs(hx-fx) + Math.abs(hy-fy)});
        }
        foods.sort((a, b) -> a[2] - b[2]);
        
        boolean earlyGame = turn < 30;
        boolean lateGame = turn >= 100 || aliveEnemies <= 2;
        boolean endGame = aliveEnemies <= 1;
        boolean desperate = hp < 15;
        boolean critical = hp < 30;
        boolean hungry = hp < 60 || len <= maxEnemyLen;
        boolean largest = len > maxEnemyLen;
        boolean dominant = len > maxEnemyLen + 2;
        boolean beingHunted = !biggerEnemies.isEmpty();
        
        int myTerritory = flood(hx, hy, W, H, blocked, hazard, hp > 40 && !isMaze, isMaze);
        
        List<int[]> safeMoves = new ArrayList<>();
        List<int[]> riskyMoves = new ArrayList<>();
        List<int[]> desperateMoves = new ArrayList<>();
        
        for (int i = 0; i < 4; i++) {
            int nx = hx + DIRS[i][0], ny = hy + DIRS[i][1];
            if (isWrapped) { nx = (nx + W) % W; ny = (ny + H) % H; }
            if (!valid(nx, ny, W, H)) continue;
            
            desperateMoves.add(new int[]{i, nx, ny});
            if (blocked[nx][ny]) continue;
            
            if (hazard[nx][ny] && !isMaze) riskyMoves.add(new int[]{i, nx, ny});
            else safeMoves.add(new int[]{i, nx, ny});
        }
        
        List<int[]> moves;
        String tier;
        if (!safeMoves.isEmpty()) { moves = safeMoves; tier = "S"; }
        else if (!riskyMoves.isEmpty()) { moves = riskyMoves; tier = "R"; }
        else if (!desperateMoves.isEmpty()) { moves = desperateMoves; tier = "D"; }
        else {
            Map<String, String> res = new HashMap<>();
            res.put("move", "up");
            return res;
        }
        
        String bestMove = null;
        int bestScore = Integer.MIN_VALUE;
        
        for (int[] m : moves) {
            int i = m[0], nx = m[1], ny = m[2];
            int score = 0;
            
            if (tier.equals("D")) score -= 500000;
            if (tier.equals("R")) score -= 20000;
            
            int space = flood(nx, ny, W, H, blocked, hazard, hp > 40 && !isMaze, isMaze);
            if (space < len) score -= 999999;
            else if (space < len + 3) score -= 150000;
            else if (space < len * 2) score -= 50000;
            else score += space * 25;
            
            int escapes = 0;
            for (int[] d : DIRS) {
                int ex = nx + d[0], ey = ny + d[1];
                if (isWrapped) { ex = (ex + W) % W; ey = (ey + H) % H; }
                if (valid(ex, ey, W, H) && !blocked[ex][ey]) escapes++;
            }
            if (escapes == 0) score -= 100000;
            else if (escapes == 1) score -= 40000;
            else score += escapes * 4000;
            
            if (hazard[nx][ny] && !isMaze) {
                int hpAfter = hp - hazardDamage;
                if (hpAfter <= 0) score -= 999999;
                else if (hp > 70) score -= 60000;
                else if (hp > 50) score -= 40000;
                else score -= 15000;
                for (int[] f : foods) {
                    if (f[0] == nx && f[1] == ny) score += 55000;
                }
            }
            if (inHazard && !hazard[nx][ny]) score += 80000;
            
            for (int[] en : biggerEnemies) {
                int dist = Math.abs(nx - en[0]) + Math.abs(ny - en[1]);
                if (dist == 1) score -= 120000;
                else if (dist == 2) score -= 20000;
                else if (dist <= 4) score -= 5000;
            }
            for (int[] en : smallerEnemies) {
                int dist = Math.abs(nx - en[0]) + Math.abs(ny - en[1]);
                if (dist == 1) {
                    int killBonus = 60000;
                    if (endGame) killBonus += 50000;
                    if (en[3] < 25) killBonus += 20000;
                    score += killBonus;
                } else if (dist == 2 && dominant) {
                    score += 15000;
                }
            }
            
            if (desperate || critical || hungry || earlyGame) {
                if (!foods.isEmpty()) {
                    int[] closest = foods.get(0);
                    int dist = Math.abs(nx - closest[0]) + Math.abs(ny - closest[1]);
                    int urgency;
                    if (desperate) urgency = 2000;
                    else if (critical) urgency = 1000;
                    else if (earlyGame) urgency = 800;
                    else if (hungry) urgency = 500;
                    else urgency = 200;
                    score += (35 - dist) * urgency;
                    if (nx == closest[0] && ny == closest[1]) score += urgency * 5;
                }
            }
            
            int tailDist = Math.abs(nx - tailX) + Math.abs(ny - tailY);
            score += (25 - tailDist) * 200;
            
            int threats = 0;
            for (int[] d : DIRS) {
                int cx = nx + d[0], cy = ny + d[1];
                for (int[] en : biggerEnemies) {
                    if (Math.abs(cx - en[0]) + Math.abs(cy - en[1]) <= 1) {
                        threats++; break;
                    }
                }
            }
            if (threats >= 2) score -= 30000;
            
            int centerDist = Math.abs(nx - W/2) + Math.abs(ny - H/2);
            score += (W + H - centerDist) * (isRoyale ? 30 : 15);
            
            if (dominant && !smallerEnemies.isEmpty()) {
                blocked[nx][ny] = true;
                for (int[] en : smallerEnemies) {
                    int enSpace = flood(en[0], en[1], W, H, blocked, hazard, false, isMaze);
                    if (enSpace < en[2]) score += endGame ? 120000 : 70000;
                    else if (enSpace < en[2] * 2) score += 25000;
                }
                blocked[nx][ny] = false;
            }
            
            if (dominant && !smallerEnemies.isEmpty()) {
                for (int[] en : smallerEnemies) {
                    int dist = Math.abs(nx - en[0]) + Math.abs(ny - en[1]);
                    score += (40 - dist) * (endGame ? 1000 : 500);
                }
            }
            
            for (int[] en : smallerEnemies) {
                int enEdge = Math.min(Math.min(en[0], W-1-en[0]), Math.min(en[1], H-1-en[1]));
                if (enEdge <= 2) {
                    int myDist = Math.abs(nx - en[0]) + Math.abs(ny - en[1]);
                    if (myDist <= 5) score += 15000;
                }
            }
            
            if (largest && myTerritory > 0) {
                for (int[] en : enemies) {
                    blocked[nx][ny] = true;
                    int theirSpace = flood(en[0], en[1], W, H, blocked, hazard, false, isMaze);
                    blocked[nx][ny] = false;
                    if (theirSpace < myTerritory / 2) score += 20000;
                }
            }
            
            if (beingHunted) {
                int minBiggerDist = 999;
                for (int[] en : biggerEnemies) {
                    int dist = Math.abs(nx - en[0]) + Math.abs(ny - en[1]);
                    if (dist < minBiggerDist) minBiggerDist = dist;
                }
                score += minBiggerDist * 10000;
            }
            
            if (isRoyale && !hazard[nx][ny]) {
                int ring = Math.min(turn / 20, Math.min(W, H) / 2 - 1);
                int edge = Math.min(Math.min(nx, W-1-nx), Math.min(ny, H-1-ny));
                if (edge <= ring + 1) score -= 15000;
                else score += edge * 400;
            }
            
            if (score > -100000) {
                score += lookahead(nx, ny, W, H, blocked, hazard, enemies, len, hp, isMaze, isWrapped) / 3;
            }
            
            if (score > bestScore) {
                bestScore = score;
                bestMove = DIR_NAMES[i];
            }
        }
        
        if (bestMove == null && !moves.isEmpty()) bestMove = DIR_NAMES[moves.get(0)[0]];
        if (bestMove == null) bestMove = "up";
        
        String status = dominant ? "D" : (largest ? "L" : (beingHunted ? "H" : "-"));
        LOG.info("T{} HP:{} L:{} {}[{}]:{} -> {} ({})", turn, hp, len, tier, status, myTerritory, bestMove, bestScore);
        
        Map<String, String> result = new HashMap<>();
        result.put("move", bestMove);
        return result;
    }
    
    static int lookahead(int x, int y, int W, int H, boolean[][] blocked, boolean[][] hazard,
            List<int[]> enemies, int len, int hp, boolean isMaze, boolean isWrapped) {
        int best = Integer.MIN_VALUE;
        boolean[][] nb = new boolean[W][H];
        for (int i = 0; i < W; i++) System.arraycopy(blocked[i], 0, nb[i], 0, H);
        nb[x][y] = true;
        
        for (int[] d : DIRS) {
            int nx = x + d[0], ny = y + d[1];
            if (isWrapped) { nx = (nx + W) % W; ny = (ny + H) % H; }
            if (!valid(nx, ny, W, H) || nb[nx][ny]) continue;
            
            int s = 0;
            int space = flood(nx, ny, W, H, nb, hazard, hp > 50 && !isMaze, isMaze);
            if (space < len) s -= 80000;
            else s += space * 12;
            if (!isMaze && hazard[nx][ny]) s -= 15000;
            for (int[] en : enemies) {
                int dist = Math.abs(nx - en[0]) + Math.abs(ny - en[1]);
                if (dist <= 1 && en[2] >= len) s -= 40000;
            }
            if (s > best) best = s;
        }
        return best == Integer.MIN_VALUE ? -20000 : best;
    }
    
    static boolean valid(int x, int y, int W, int H) { return x >= 0 && x < W && y >= 0 && y < H; }
    
    static int flood(int sx, int sy, int W, int H, boolean[][] blocked, boolean[][] hazard, boolean avoidHaz, boolean isMaze) {
        if (!valid(sx, sy, W, H) || blocked[sx][sy]) return 0;
        if (!isMaze && avoidHaz && hazard[sx][sy]) return 0;
        
        boolean[][] seen = new boolean[W][H];
        int[] qx = new int[W*H], qy = new int[W*H];
        int h = 0, t = 0;
        qx[t] = sx; qy[t++] = sy; seen[sx][sy] = true;
        int c = 0;
        while (h < t && c < 500) {
            int x = qx[h], y = qy[h++]; c++;
            for (int[] d : DIRS) {
                int nx = x+d[0], ny = y+d[1];
                if (valid(nx,ny,W,H) && !seen[nx][ny] && !blocked[nx][ny]) {
                    if (!isMaze && avoidHaz && hazard[nx][ny]) continue;
                    seen[nx][ny] = true; qx[t] = nx; qy[t++] = ny;
                }
            }
        }
        return c;
    }
}
