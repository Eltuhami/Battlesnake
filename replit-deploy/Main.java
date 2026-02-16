import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

import static spark.Spark.*;

/**
 * INTELLIGENT SNAKE v1.1 - MINIMAX (Replit Version)
 * - Improved fallback logic
 * - Detailed logging
 */
public class Main {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Logger LOG = LoggerFactory.getLogger(Main.class);

    private static final int[][] DIRS = {{0, 1}, {0, -1}, {-1, 0}, {1, 0}};
    private static final String[] DIR_NAMES = {"up", "down", "left", "right"};

    // --- MINIMAX SETTINGS ---
    private static final int MAX_DEPTH_LIMIT = 12; 
    private static final long TIME_LIMIT_MS = 300; 

    // --- SCORING WEIGHTS ---
    private static final double W_SPACE      = 10.0;
    private static final double W_FOOD       = 25.0; 
    private static final double W_HEALTH     = 2.0;

    public static void main(String[] args) {
        String portStr = System.getenv("PORT");
        if (portStr == null) portStr = "8080";
        port(Integer.parseInt(portStr));
        
        get("/", (req, res) -> JSON.writeValueAsString(index()));
        post("/start", (req, res) -> "{}");
        post("/move", (req, res) -> JSON.writeValueAsString(move(JSON.readTree(req.body()))));
        post("/end", (req, res) -> "{}");
    }

    static Map<String, String> index() {
        Map<String, String> r = new HashMap<>();
        r.put("apiversion", "1");
        r.put("author", "AntiGravity-Intel");
        r.put("color", "#00AAFF"); 
        r.put("head", "smart-caterpillar"); 
        r.put("tail", "curled");
        return r;
    }

    static Map<String, String> move(JsonNode root) {
        long startTime = System.currentTimeMillis();
        GameState state = new GameState(root);
        
        String bestMove = getBestMove(state, startTime);

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
            LOG.warn("No legal moves at root!");
            return "up"; 
        }

        // Safety Fallback: Pick ANY safe move to ensure we don't return default "up" into a wall
        String bestMove = moves.get(new Random().nextInt(moves.size()));
        double bestScore = -Double.MAX_VALUE;
        
        // Iterative Deepening
        for (int depth = 1; depth <= MAX_DEPTH_LIMIT; depth++) {
            if (System.currentTimeMillis() - startTime > TIME_LIMIT_MS) {
                LOG.info("Time limit reached at start of depth {}", depth);
                break;
            }

            String currentBestMove = bestMove;
            double currentBestScore = -Double.MAX_VALUE;
            boolean validSearch = true;
            StringBuilder logMsg = new StringBuilder("D").append(depth).append(": ");

            for (String move : moves) {
                GameState next = rootState.advance(move);
                double score = minimax(next, depth - 1, -Double.MAX_VALUE, Double.MAX_VALUE, false, startTime);
                
                logMsg.append(move).append("=").append((int)score).append(" ");
                
                if (score > currentBestScore) {
                    currentBestScore = score;
                    currentBestMove = move;
                }
                
                if (System.currentTimeMillis() - startTime > TIME_LIMIT_MS) {
                    validSearch = false; 
                    break;
                }
            }

            if (validSearch) {
                bestMove = currentBestMove;
                bestScore = currentBestScore;
                LOG.info("{} -> Best: {}", logMsg, bestMove);
            } else {
                LOG.info("Depth {} aborted due to timeout.", depth);
                break; 
            }
        }
        
        return bestMove;
    }

    private static double minimax(GameState state, int depth, double alpha, double beta, boolean maximizingPlayer, long startTime) {
        if (System.currentTimeMillis() - startTime > TIME_LIMIT_MS) return 0; 
        
        if (depth == 0 || state.isGameOver()) {
            return evaluate(state);
        }

        if (maximizingPlayer) {
            double maxEval = -Double.MAX_VALUE;
            List<String> moves = state.getLegalMoves();
            if (moves.isEmpty()) return -1_000_000; 

            for (String move : moves) {
                GameState next = state.advance(move);
                double eval = minimax(next, depth - 1, alpha, beta, false, startTime);
                maxEval = Math.max(maxEval, eval);
                alpha = Math.max(alpha, eval);
                if (beta <= alpha) break;
            }
            return maxEval;
        } else {
            return minimax(state, depth, alpha, beta, true, startTime); 
        }
    }

    // ============================================================
    // EVALUATION
    // ============================================================

    private static double evaluate(GameState state) {
        if (state.myHealth <= 0) return -1_000_000; 
        if (state.isBlocked(state.myHead)) return -1_000_000;

        double score = 0;

        // 1. Flood Fill
        int space = floodFill(state, state.myHead, state.myLen * 3);
        if (space < state.myLen) {
            return -500_000 + (space * 100); 
        }
        score += space * W_SPACE;

        // 2. Food
        double foodUrgency = (state.myHealth < 40) ? 5.0 : 1.0;
        int distToFood = getClosestFoodDist(state);
        if (distToFood != Integer.MAX_VALUE) {
            score += (500.0 / (distToFood + 1)) * foodUrgency * W_FOOD;
        }

        // 3. Health
        score += state.myHealth * W_HEALTH;
        
        // 4. Center Control
        int distCenter = Math.abs(state.myHead.x - state.W/2) + Math.abs(state.myHead.y - state.H/2);
        score -= distCenter * 2;
        
        // 5. Length dominance
        for(List<Point> enemy : state.enemies) {
            if (state.myLen > enemy.size()) score += 1000;
            else if (state.myLen < enemy.size()) score -= 500;
        }

        return score;
    }
    
    // ============================================================
    // UTILS
    // ============================================================

    static int floodFill(GameState state, Point start, int max) {
        boolean[][] visited = new boolean[state.W][state.H];
        for(Point p : state.obstacles) {
            if (state.isValid(p)) visited[p.x][p.y] = true;
        }
        if (state.isValid(start)) visited[start.x][start.y] = true; 

        Queue<Point> q = new LinkedList<>();
        q.add(start);
        
        int count = 0;
        while(!q.isEmpty()) {
            Point p = q.poll();
            count++;
            if (count >= max) return count;
            
            for (int[] d : DIRS) {
                Point next = p.add(d);
                if (state.isWrapped) next = state.wrap(next);
                
                if (state.isValid(next) && !visited[next.x][next.y]) {
                    if (!state.hazardZones.contains(next)) {
                        visited[next.x][next.y] = true;
                        q.add(next);
                    }
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
        int W, H;
        int myHealth, myLen;
        Point myHead;
        List<Point> myBody;
        Set<Point> foods = new HashSet<>();
        List<List<Point>> enemies = new ArrayList<>(); 
        
        Set<Point> hazardZones = new HashSet<>(); 
        Set<Point> obstacles = new HashSet<>();   
        
        boolean isWrapped, isConstrictor;
        
        GameState(JsonNode root) {
            JsonNode board = root.get("board");
            W = board.get("width").asInt(); 
            H = board.get("height").asInt();
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
                
                Point eHead = enemyBody.get(0);
                if (enemyBody.size() >= myLen) {
                    for(int[] d : DIRS) {
                        Point next = eHead.add(d);
                        if(isWrapped) next = wrap(next);
                        hazardZones.add(next);
                    }
                }
            }
            
            obstacles.addAll(myBody);
            // Ignore tail removal at ROOT
        }
        
        private GameState(GameState other) {
            this.W = other.W; this.H = other.H;
            this.isWrapped = other.isWrapped;
            this.myHealth = other.myHealth;
            this.myLen = other.myLen;
            this.myHead = other.myHead; 
            this.myBody = new ArrayList<>(other.myBody);
            this.foods = new HashSet<>(other.foods);
            this.enemies = other.enemies; 
            this.obstacles = new HashSet<>(other.obstacles);
            this.hazardZones = other.hazardZones; 
        }

        GameState advance(String moveDir) {
            GameState next = new GameState(this);
            Point moveVec = new Point(0,0);
            if (moveDir.equals("up")) moveVec = new Point(0, 1);
            else if (moveDir.equals("down")) moveVec = new Point(0, -1);
            else if (moveDir.equals("left")) moveVec = new Point(-1, 0);
            else if (moveDir.equals("right")) moveVec = new Point(1, 0);
            
            Point nextHead = myHead.add(moveVec);
            if (isWrapped) nextHead = wrap(nextHead);
            
            next.myHead = nextHead;
            next.myBody.add(0, nextHead);
            next.obstacles.add(nextHead); 
            
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

        List<String> getLegalMoves() {
            List<String> moves = new ArrayList<>();
            for (int i=0; i<4; i++) {
                Point p = myHead.add(DIRS[i]);
                if (isWrapped) p = wrap(p);
                
                if (isValid(p) && !isBlocked(p)) {
                   moves.add(DIR_NAMES[i]);
                }
            }
            return moves;
        }
        
        boolean isGameOver() {
            return myHealth <= 0 || isBlocked(myHead); 
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
        @Override public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Point)) return false;
            Point p = (Point) o;
            return x == p.x && y == p.y;
        }
        @Override public int hashCode() { return Objects.hash(x, y); }
        @Override public String toString() { return "(" + x + "," + y + ")"; }
    }
}
