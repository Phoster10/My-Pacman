import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.AlphaComposite;
import java.awt.Composite;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Random;
import java.util.Base64;
import javax.imageio.ImageIO;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

// ─────────────────────────────────────────────────────────────────────────────
//  DIRECTION
// ─────────────────────────────────────────────────────────────────────────────
enum Direction {
    UP(0, -1), DOWN(0, 1), LEFT(-1, 0), RIGHT(1, 0), NONE(0, 0);
    final int dx, dy;

    Direction(int dx, int dy) {
        this.dx = dx;
        this.dy = dy;
    }

    Direction opposite() {
        return switch (this) {
            case UP    -> DOWN;
            case DOWN  -> UP;
            case LEFT  -> RIGHT;
            case RIGHT -> LEFT;
            default    -> NONE;
        };
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  GHOST MODE (behavioral state, independent of who is controlling the ghost)
// ─────────────────────────────────────────────────────────────────────────────
enum GhostMode { SCATTER, CHASE, FRIGHTENED, EATEN }

// ─────────────────────────────────────────────────────────────────────────────
//  WHO IS DRIVING A GHOST
// ─────────────────────────────────────────────────────────────────────────────
enum ControlMode { AI, HUMAN1, HUMAN2 }

// ─────────────────────────────────────────────────────────────────────────────
//  TILE TYPE
// ─────────────────────────────────────────────────────────────────────────────
enum Tile { WALL, EMPTY, DOT, ENERGIZER, GHOST_HOUSE }

// ─────────────────────────────────────────────────────────────────────────────
//  WHICH GAME MODE IS BEING PLAYED
//    NORMAL - the classic game: the human plays Pac-Man, all four ghosts are AI.
//    GHOST  - Pac-Man plays himself (AI); the human(s) each control a ghost.
// ─────────────────────────────────────────────────────────────────────────────
enum GameMode { NORMAL, GHOST }

// ─────────────────────────────────────────────────────────────────────────────
//  POWER-UP TYPE  (Pac-Man only picks these up; they affect the whole match)
//    MAGNET     - pulls in nearby dots/energizers automatically
//    PEPPER     - Pac-Man turns invisible to the AI ghosts (they lose track of him)
//    SNOWFLAKE  - freezes every ghost (AI and human controlled) in place
//    CHERRY, STRAWBERRY, BANANA - classic bonus fruit; an instant score bonus
// ─────────────────────────────────────────────────────────────────────────────
enum PowerUpType { MAGNET, PEPPER, SNOWFLAKE, CHERRY, STRAWBERRY, BANANA }

// ─────────────────────────────────────────────────────────────────────────────
//  MAIN ENTRY POINT
// ─────────────────────────────────────────────────────────────────────────────
public class PacMan {
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            JFrame frame = new JFrame("PAC-MAN");
            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            frame.setResizable(false);

            GamePanel panel = new GamePanel();
            frame.add(panel);
            frame.pack();
            frame.setLocationRelativeTo(null);
            frame.setVisible(true);
            panel.requestFocusInWindow();
        });
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  GAME PANEL
// ─────────────────────────────────────────────────────────────────────────────
class GamePanel extends JPanel implements ActionListener, KeyListener {

    static final long serialVersionUID = 1L;

    // ── Constants ─────────────────────────────────────────────────────────
    static final int TILE       = 16;
    static final int COLS       = 28;
    static final int ROWS       = 31;
    static final int HUD_HEIGHT = 56;
    static final int W          = COLS * TILE;
    static final int H          = ROWS * TILE + HUD_HEIGHT;
    static final int FPS        = 60;
    static final int SPEED      = 2;
    static final int FRIGHT_DURATION = FPS * 8;
    static final int FRIGHT_FLASH_AT = FPS * 3;
    static final int[] PHASE_DURATIONS = {
        FPS * 7, FPS * 20, FPS * 7, FPS * 20,
        FPS * 5, FPS * 20, FPS * 5, Integer.MAX_VALUE
    };
    static final int DOT_PTS       = 10;
    static final int ENERGIZER_PTS = 50;
    static final int GHOST_BASE    = 200;

    // ── Ghost-mode scoring ───────────────────────────────────────────────
    static final int BLOCK_POINTS   = 300; // a ghost player earns this by blocking Pac-Man
    static final int EATEN_PENALTY  = 150; // a ghost player loses this if eaten while frightened

    /** Where the high-score file lives (Pac-Man's own maze-clearing performance). */
    static final File HIGH_SCORE_FILE =
            new File(System.getProperty("user.home", "."), ".pacman_highscore.dat");

    // ── Maze (28 × 31) ────────────────────────────────────────────────────
    static final String[] MAP = {
    "############################",
    "#............##...o........#",
    "#.####.#####.##.#####.####.#",
    "#o####.#####.##.#####.####o#",
    "#.####.#####.##.#####.####.#",
    "#..........................#",
    "#.####.##.########.##.####.#",
    "#.####.##.########.##.####.#",
    "#......##....##....##......#",
    "######.##### ## #####.######",
    "     #.##### ## #####.#     ",
    "     #.##          ##.#     ",
    "     #.## ###--### ##.#     ",
    "######.## #  GG  # ##.######",
    "      .   #  GG  #   .      ",
    "######.## ######## ##.######",
    "     #.##          ##.#     ",
    "     #.## ######## ##.#     ",
    "     #.## ######## ##.#     ",
    "######.## ######## ##.######",
    "#............##............#",
    "#.####.#####.##.#####.####.#",
    "#.####.#####.##.#####.####.#",
    "#o..##.......  .......##..o#",
    "###.##.##.########.##.##.###",
    "###.##.##.########.##.##.###",
    "#......##....##....##......#",
    "#.##########.##.##########.#",
    "#.##########.##.##########.#",
    "#............ p............#",
    "############################"
};
    // ── Grid ──────────────────────────────────────────────────────────────
    Tile[][] grid = new Tile[ROWS][COLS];
    int totalDots;

    // True for every tile actually reachable from Pac-Man's start position.
    boolean[][] reachable = new boolean[ROWS][COLS];

    // ── Game state ────────────────────────────────────────────────────────
    enum State { TITLE, SELECT_MODE, SELECT_PLAYERS, SELECT_GHOST, READY, PLAYING, DEAD, LEVEL_CLEAR, GAME_OVER }
    State state = State.TITLE;
    int stateTimer = 0;
    boolean paused = false;

    // Which overall game mode is active: classic Pac-Man, or ghost mode.
    GameMode gameMode = GameMode.NORMAL;

    int score, highScore, lives, level, dotsEaten;
    boolean newHighScore;
    long levelTimer; // frames elapsed on the current level, for the efficiency bonus

    int phaseIndex, phaseTimer;
    int frightTimer;
    int ghostEatCombo;

    // ── Ghost-mode player setup ───────────────────────────────────────────
    int numPlayers = 1;
    int selectedGhostIndex = 0;   // used while on the SELECT_GHOST screen (0=red, 3=orange)
    int player1GhostIndex = -1;   // index into ghosts[] controlled by Player 1 (arrow keys)
    int player2GhostIndex = -1;   // index into ghosts[] controlled by Player 2 (WASD), or -1
    int player1Score = 0;
    int player2Score = 0;
    Direction p1QueuedDir = Direction.NONE;
    Direction p2QueuedDir = Direction.NONE;

    // ── Pac-Man ─────────────────────────────────────────────────────────────
    // In NORMAL mode Pac-Man is driven by the human's arrow-key input.
    // In GHOST mode Pac-Man is fully autonomous (BFS-driven AI).
    float px, py;
    Direction pDir, pNext;
    float pSpeed;
    int mouthAngle;
    int mouthDir;
    int pStartCol, pStartRow;

    // ── Ghost colours / scatter corners ───────────────────────────────────
    static final Color[] GHOST_COLORS = {
        new Color(255,   0,   0),   // 0 blinky - red
        new Color(255, 184, 255),   // 1 pinky  - pink
        new Color(  0, 255, 255),   // 2 inky   - cyan
        new Color(255, 184,  82),   // 3 clyde  - orange
    };
    static final int[][] SCATTER_TARGETS = {
        {25, 0}, {2, 0}, {27, 30}, {0, 30}
    };
    static final int HOUSE_EXIT_COL = 14;
    static final int HOUSE_EXIT_ROW = 11;

    transient Ghost[] ghosts = new Ghost[4];

    int dotFlashTimer;
    int titleBlink;

    // ── Power-ups (Pac-Man only) ───────────────────────────────────────────
    static final int POWERUP_LIFETIME   = FPS * 10;        // on-map duration once spawned
    static final int POWERUP_MIN_GAP    = FPS * 8;         // fastest possible re-spawn
    static final int POWERUP_MAX_GAP    = FPS * 15;        // slowest possible re-spawn
    static final int MAGNET_DURATION    = FPS * 8;
    static final int PEPPER_DURATION    = FPS * 8;
    static final int SNOWFLAKE_DURATION = FPS * 5;
    static final int SPEED_BOOST_DURATION  = FPS * 8;  // cherry: 3x speed
    static final int REVERSE_DURATION      = FPS * 8;  // banana: reversed controls
    static final int SPEED_BOOST_MULTIPLIER = 3;
    static final int MAGNET_RADIUS      = 3; // tiles

    BufferedImage magnetImg, pepperImg, snowflakeImg, cherryImg, strawberryImg, bananaImg;

    PowerUp activePowerup;      // null when nothing is currently on the map
    int powerupSpawnTimer;      // frames until the next spawn attempt
    int magnetTimer;            // frames remaining of the magnet effect
    int invisibleTimer;         // frames remaining of the pepper (invisibility) effect
    int freezeTimer;            // frames remaining of the snowflake (freeze) effect
    int speedBoostTimer;        // frames remaining of the cherry (3x speed) effect
    int reverseControlsTimer;   // frames remaining of the banana (reversed controls) effect
    PowerUpType lastPowerUpType; // the type most recently spawned, so we never repeat it back-to-back

    /** A power-up sitting on a tile, waiting to be eaten or to expire. */
    class PowerUp {
        PowerUpType type;
        int col, row;
        int lifeTimer;

        PowerUp(PowerUpType type, int col, int row) {
            this.type = type;
            this.col = col;
            this.row = row;
            this.lifeTimer = POWERUP_LIFETIME;
        }
    }

    // Cached, pre-rendered maze walls.
    transient BufferedImage wallBuffer;
    boolean wallDirty = true;

    Random rng = new Random();

    javax.swing.Timer gameLoop;

    // ══════════════════════════════════════════════════════════════════════
    //  GHOST DATA CLASS
    // ══════════════════════════════════════════════════════════════════════
    class Ghost {
        String   name;
        Color    color;
        int[]    scatterTarget;

        float    x, y, startX, startY;
        Direction dir = Direction.LEFT;
        Direction nextDir = Direction.LEFT;
        GhostMode mode = GhostMode.SCATTER;
        ControlMode control = ControlMode.AI;

        int  frightTimer;
        boolean frightFlash;
        boolean frightStep;
        int  dotThreshold;
        boolean startsInHouse; // true if this ghost's spawn tile is inside the ghost house
        boolean inHouse, leavingHouse, returningToHouse;
        int targetCol, targetRow;

        Ghost(String name, Color color, int[] scatterTarget,
              float startX, float startY, int dotThreshold) {
            this.name          = name;
            this.color         = color;
            this.scatterTarget = scatterTarget;
            this.startX        = startX;
            this.startY        = startY;
            this.dotThreshold  = dotThreshold;
            // Whether a ghost starts penned up depends on where it actually
            // spawns (inside the house tiles), not on its release threshold.
            // Pinky has a threshold of 0 (same as Blinky) but still spawns
            // inside the house, so she must still start "inHouse" or she'll
            // be stuck: surrounded by GHOST_HOUSE tiles that only inHouse /
            // leavingHouse / returningToHouse ghosts are allowed to occupy.
            this.startsInHouse = GamePanel.this.grid[pixelToRow(startY)][pixelToCol(startX)] == Tile.GHOST_HOUSE;
            reset();
        }

        void reset() {
            x = startX;
            y = startY;
            dir = Direction.LEFT; nextDir = Direction.LEFT;
            mode = GhostMode.SCATTER;
            frightTimer = 0; frightFlash = false; frightStep = false;
            returningToHouse = false; leavingHouse = false;
            inHouse = startsInHouse;
        }

        int col() { return pixelToCol(x); }
        int row() { return pixelToRow(y); }

        boolean isCentered() {
            return GamePanel.this.isCentered(x, y);
        }

        Color drawColor() {
            if (returningToHouse) return Color.WHITE;
            if (mode == GhostMode.FRIGHTENED) {
                return frightFlash ? Color.WHITE : new Color(0, 0, 200);
            }
            return color;
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  CONSTRUCTOR
    // ══════════════════════════════════════════════════════════════════════
    GamePanel() {
        setPreferredSize(new Dimension(W, H));
        setBackground(Color.BLACK);
        setFocusable(true);
        addKeyListener(this);

        loadHighScore();
        loadPowerUpImages();
        buildGrid();
        initGhosts();
        resetRound(true);

        gameLoop = new javax.swing.Timer(1000 / FPS, this);
        gameLoop.start();
    }

    // ══════════════════════════════════════════════════════════════════════
    //  TILE / PIXEL MATH HELPERS
    // ══════════════════════════════════════════════════════════════════════
    int pixelToCol(float x) {
        int c = (int) Math.floor((x + TILE / 2.0) / TILE);
        return Math.floorMod(c, COLS);
    }

    int pixelToRow(float y) {
        return (int) Math.floor((y + TILE / 2.0) / TILE);
    }

    boolean tileAligned(float v) {
        return Math.floorMod(Math.round(v), TILE) == 0;
    }

    boolean isCentered(float x, float y) {
        return tileAligned(x) && tileAligned(y);
    }

    // ══════════════════════════════════════════════════════════════════════
    //  MAP / GRID
    // ══════════════════════════════════════════════════════════════════════
    void buildGrid() {
        totalDots = 0;
        for (int r = 0; r < ROWS; r++) {
            String line = MAP[r];
            for (int c = 0; c < COLS; c++) {
                char ch = c < line.length() ? line.charAt(c) : ' ';
                grid[r][c] = switch (ch) {
                    case '#' -> Tile.WALL;
                    case '.' -> { totalDots++; yield Tile.DOT; }
                    case 'o' -> { totalDots++; yield Tile.ENERGIZER; }
                    case 'G' -> Tile.GHOST_HOUSE;
                    default  -> Tile.EMPTY;
                };
                if (ch == 'p') { pStartCol = c; pStartRow = r; }
            }
        }
        wallDirty = true;
        computeReachable();
    }

    void computeReachable() {
        reachable = new boolean[ROWS][COLS];
        if (grid[pStartRow][pStartCol] == Tile.WALL) return;

        ArrayDeque<int[]> queue = new ArrayDeque<>();
        reachable[pStartRow][pStartCol] = true;
        queue.add(new int[]{ pStartCol, pStartRow });

        int[][] dirs = { {0, -1}, {0, 1}, {-1, 0}, {1, 0} };
        while (!queue.isEmpty()) {
            int[] cur = queue.poll();
            int c = cur[0], r = cur[1];
            for (int[] d : dirs) {
                int nr = r + d[1];
                if (nr < 0 || nr >= ROWS) continue;
                int nc = Math.floorMod(c + d[0], COLS);
                if (grid[nr][nc] == Tile.WALL || grid[nr][nc] == Tile.GHOST_HOUSE) continue;
                if (!reachable[nr][nc]) {
                    reachable[nr][nc] = true;
                    queue.add(new int[]{ nc, nr });
                }
            }
        }
    }

    boolean isWall(int col, int row) {
        if (row < 0 || row >= ROWS) return true;
        col = Math.floorMod(col, COLS);
        return grid[row][col] == Tile.WALL;
    }

    // ══════════════════════════════════════════════════════════════════════
    //  GHOST INIT
    // ══════════════════════════════════════════════════════════════════════
    void initGhosts() {
        ghosts[0] = new Ghost("blinky", GHOST_COLORS[0], SCATTER_TARGETS[0],
                13 * TILE, 11 * TILE, 0);
        ghosts[1] = new Ghost("pinky",  GHOST_COLORS[1], SCATTER_TARGETS[1],
                13 * TILE, 14 * TILE, 0);
        ghosts[2] = new Ghost("inky",   GHOST_COLORS[2], SCATTER_TARGETS[2],
                11 * TILE, 14 * TILE, 30);
        ghosts[3] = new Ghost("clyde",  GHOST_COLORS[3], SCATTER_TARGETS[3],
                15 * TILE, 14 * TILE, 60);
    }

    // ══════════════════════════════════════════════════════════════════════
    //  START A BRAND NEW MATCH (after mode / player / ghost selection)
    // ══════════════════════════════════════════════════════════════════════
    void startNewGame() {
        score = 0;
        lives = 3; level = 1; dotsEaten = 0;
        newHighScore = false;
        paused = false;
        player1Score = 0;
        player2Score = 0;
        buildGrid();

        for (Ghost g : ghosts) g.control = ControlMode.AI;
        if (gameMode == GameMode.GHOST) {
            if (player1GhostIndex >= 0) ghosts[player1GhostIndex].control = ControlMode.HUMAN1;
            if (player2GhostIndex >= 0) ghosts[player2GhostIndex].control = ControlMode.HUMAN2;
        }

        resetRound(false);

        // Human-controlled ghosts must be able to move immediately, regardless
        // of their AI dot-release threshold.
        for (Ghost g : ghosts) {
            if (g.control != ControlMode.AI) {
                g.inHouse = false;
                g.leavingHouse = false;
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  RESET (used for a new life / new level; does not touch scores/control)
    // ══════════════════════════════════════════════════════════════════════
    void resetRound(boolean rebuildMazeUnused) {
        px = pStartCol * TILE;
        py = pStartRow * TILE;
        pDir = Direction.NONE; pNext = Direction.NONE;
        pSpeed = SPEED;
        mouthAngle = 45; mouthDir = -1;

        phaseIndex = 0;
        phaseTimer = PHASE_DURATIONS[0];
        frightTimer = 0; ghostEatCombo = 0;
        levelTimer = 0;

        activePowerup = null;
        magnetTimer = 0;
        invisibleTimer = 0;
        freezeTimer = 0;
        speedBoostTimer = 0;
        reverseControlsTimer = 0;
        powerupSpawnTimer = POWERUP_MIN_GAP + rng.nextInt(POWERUP_MAX_GAP - POWERUP_MIN_GAP);

        p1QueuedDir = Direction.NONE;
        p2QueuedDir = Direction.NONE;

        for (Ghost g : ghosts) g.reset();

        // Re-apply immediate freedom for human ghosts after the generic reset().
        for (Ghost g : ghosts) {
            if (g.control != ControlMode.AI) {
                g.inHouse = false;
                g.leavingHouse = false;
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  MAIN LOOP
    // ══════════════════════════════════════════════════════════════════════
    @Override
    public void actionPerformed(ActionEvent e) {
        update();
        repaint();
    }

    void update() {
        titleBlink = (titleBlink + 1) % (FPS * 2);
        switch (state) {
            case TITLE, SELECT_MODE, SELECT_PLAYERS, SELECT_GHOST -> { /* wait for input */ }

            case READY -> {
                stateTimer--;
                if (stateTimer <= 0) state = State.PLAYING;
            }

            case PLAYING -> {
                if (paused) return;
                levelTimer++;
                updatePhase();
                updatePowerUps();
                updatePacMan();
                updateGhosts();
                checkCollisions();
                checkWin();
            }

            case DEAD -> {
                stateTimer--;
                if (stateTimer <= 0) {
                    lives--;
                    if (lives <= 0) {
                        if (score > highScore) {
                            highScore = score;
                            saveHighScore();
                            newHighScore = true;
                        }
                        state = State.GAME_OVER;
                        stateTimer = FPS * 6;
                    } else {
                        resetRound(false);
                        checkUnlockGhosts();
                        state = State.READY;
                        stateTimer = FPS * 2;
                    }
                }
            }

            case LEVEL_CLEAR -> {
                stateTimer--;
                dotFlashTimer = (dotFlashTimer + 1) % (FPS / 4);
                if (stateTimer <= 0) {
                    level++;
                    dotsEaten = 0;
                    buildGrid();
                    resetRound(false);
                    state = State.READY;
                    stateTimer = FPS * 2;
                }
            }

            case GAME_OVER -> { /* wait for ENTER to return to the title screen */ }
        }
    }

    // ── Phase cycling ─────────────────────────────────────────────────────
    void updatePhase() {
        if (freezeTimer > 0) return; // time stands still for the ghosts while frozen
        if (frightTimer > 0) {
            frightTimer--;
            return;
        }

        phaseTimer--;
        if (phaseTimer <= 0) {
            phaseIndex = Math.min(phaseIndex + 1, PHASE_DURATIONS.length - 1);
            phaseTimer = PHASE_DURATIONS[phaseIndex];
            for (Ghost g : ghosts) {
                if (g.control == ControlMode.AI
                        && g.mode != GhostMode.FRIGHTENED && g.mode != GhostMode.EATEN
                        && !g.inHouse && !g.leavingHouse && !g.returningToHouse)
                    g.dir = g.dir.opposite();
            }
        }

        boolean scatter = (phaseIndex % 2 == 0);
        for (Ghost g : ghosts) {
            if (g.mode == GhostMode.CHASE || g.mode == GhostMode.SCATTER)
                g.mode = scatter ? GhostMode.SCATTER : GhostMode.CHASE;
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  PAC-MAN CONTROL
    //  In GHOST mode Pac-Man is BFS-driven and fully autonomous.
    //  In NORMAL mode Pac-Man's queued direction (pNext) comes straight from
    //  the player's arrow-key input (see keyPressed) - no AI runs at all.
    // ══════════════════════════════════════════════════════════════════════
    void updatePacMan() {
        // The speed-boost power-up is implemented as extra base-speed sub-steps
        // per frame, rather than a bigger single stride. Wall/turn checks only
        // happen at tile-aligned positions (multiples of SPEED, which evenly
        // divides TILE); stretching the stride instead would let Pac-Man skip
        // right over an alignment point and plow through a wall. Repeating the
        // normal, alignment-safe step is what keeps him boxed in correctly no
        // matter how many times per frame he moves.
        int steps = (speedBoostTimer > 0) ? SPEED_BOOST_MULTIPLIER : 1;
        for (int i = 0; i < steps; i++) {
            if (gameMode == GameMode.GHOST && isCentered(px, py)) {
                Direction d = bfsNextDirection();
                if (d != null && d != Direction.NONE) pNext = d;
            }
            stepPacMan();
        }

        if (pDir != Direction.NONE) {
            mouthAngle += mouthDir * 4;
            if (mouthAngle <= 0)  { mouthAngle = 0;  mouthDir =  1; }
            if (mouthAngle >= 45) { mouthAngle = 45; mouthDir = -1; }
        }
    }

    /** Moves Pac-Man by exactly one SPEED-sized step: takes the queued turn
     *  when centered and legal, advances if the way is clear, wraps the
     *  tunnel, and eats whatever is on the tile landed on. Always moves at
     *  the base SPEED — the speed-boost power-up calls this multiple times
     *  per frame instead of widening the stride, so wall checks never get
     *  skipped over (see updatePacMan). */
    void stepPacMan() {
        int speed = SPEED;

        if (pNext != Direction.NONE && isCentered(px, py)) {
            if (canMove(px, py, pNext)) {
                pDir  = pNext;
                pNext = Direction.NONE;
            }
        }

        if (pDir != Direction.NONE) {
            if (!isCentered(px, py) || canMove(px, py, pDir)) {
                px += pDir.dx * speed;
                py += pDir.dy * speed;
                if      (px < -TILE) px = W - TILE;
                else if (px >= W)    px = -TILE;
            }
        }

        // Eat dots / energizers
        int col = pixelToCol(px);
        int row = pixelToRow(py);
        if (row >= 0 && row < ROWS) {
            Tile t = grid[row][col];
            if (t == Tile.DOT) {
                grid[row][col] = Tile.EMPTY;
                score += DOT_PTS;
                dotsEaten++;
                checkUnlockGhosts();
            } else if (t == Tile.ENERGIZER) {
                grid[row][col] = Tile.EMPTY;
                score += ENERGIZER_PTS;
                dotsEaten++;
                activateFright();
                checkUnlockGhosts();
            }
        }

        // Power-up pickup — Pac-Man only.
        if (activePowerup != null && col == activePowerup.col && row == activePowerup.row) {
            applyPowerUp(activePowerup.type);
            activePowerup = null;
            powerupSpawnTimer = POWERUP_MIN_GAP + rng.nextInt(POWERUP_MAX_GAP - POWERUP_MIN_GAP);
        }
    }

    boolean canMove(float x, float y, Direction d) {
        float nx = x + d.dx * TILE;
        float ny = y + d.dy * TILE;
        int col = pixelToCol(nx);
        int row = pixelToRow(ny);
        if (row < 0 || row >= ROWS) return false;
        return grid[row][col] != Tile.WALL && grid[row][col] != Tile.GHOST_HOUSE;
    }

    /** True if the given tile currently hosts (or is one step in front of) a
     *  dangerous, non-frightened ghost — used so the Pac-Man AI (ghost mode
     *  only) steers around them. */
    boolean[][] computeHotTiles() {
        boolean[][] hot = new boolean[ROWS][COLS];
        for (Ghost g : ghosts) {
            boolean dangerous = g.mode != GhostMode.FRIGHTENED && g.mode != GhostMode.EATEN
                    && !g.inHouse && !g.leavingHouse && !g.returningToHouse;
            if (!dangerous) continue;
            int gc = g.col(), gr = g.row();
            if (gr >= 0 && gr < ROWS) hot[gr][Math.floorMod(gc, COLS)] = true;
            int ac = gc + g.dir.dx, ar = gr + g.dir.dy;
            if (ar >= 0 && ar < ROWS) hot[ar][Math.floorMod(ac, COLS)] = true;
        }
        return hot;
    }

    /** Breadth-first search from Pac-Man's current tile to the nearest
     *  uncollected dot/energizer/power-up, returning the first step to take.
     *  Tries to steer around dangerous ghosts first; if that leaves no path
     *  at all, falls back to ignoring ghost positions so Pac-Man never
     *  simply freezes. Only used in GHOST mode. */
    Direction bfsNextDirection() {
        int startCol = pixelToCol(px);
        int startRow = pixelToRow(py);
        boolean[][] hot = computeHotTiles();

        Direction d = bfsSearch(startCol, startRow, hot);
        if (d == null) d = bfsSearch(startCol, startRow, null);
        return d;
    }

    private Direction bfsSearch(int startCol, int startRow, boolean[][] hot) {
        boolean[][] visited = new boolean[ROWS][COLS];
        int[][] parentCol = new int[ROWS][COLS];
        int[][] parentRow = new int[ROWS][COLS];
        Direction[][] dirUsed = new Direction[ROWS][COLS];
        for (int[] rowArr : parentCol) java.util.Arrays.fill(rowArr, -1);

        ArrayDeque<int[]> queue = new ArrayDeque<>();
        visited[startRow][startCol] = true;
        parentCol[startRow][startCol] = -1;
        parentRow[startRow][startCol] = -1;
        queue.add(new int[]{ startCol, startRow });

        int[] goal = null;
        Direction[] order = { Direction.UP, Direction.DOWN, Direction.LEFT, Direction.RIGHT };

        while (!queue.isEmpty()) {
            int[] cur = queue.poll();
            int c = cur[0], r = cur[1];
            boolean isStart = (c == startCol && r == startRow);
            if (!isStart) {
                Tile t = grid[r][c];
                boolean isGoalTile = (t == Tile.DOT || t == Tile.ENERGIZER)
                        || (activePowerup != null && activePowerup.col == c && activePowerup.row == r);
                if (isGoalTile) { goal = cur; break; }
            }
            for (Direction dd : order) {
                int nr = r + dd.dy;
                if (nr < 0 || nr >= ROWS) continue;
                int nc = Math.floorMod(c + dd.dx, COLS);
                if (visited[nr][nc]) continue;
                Tile t = grid[nr][nc];
                if (t == Tile.WALL || t == Tile.GHOST_HOUSE) continue;
                if (hot != null && hot[nr][nc]) continue;
                visited[nr][nc] = true;
                parentCol[nr][nc] = c;
                parentRow[nr][nc] = r;
                dirUsed[nr][nc] = dd;
                queue.add(new int[]{ nc, nr });
            }
        }

        if (goal == null) return null;

        int cc = goal[0], rr = goal[1];
        while (!(parentCol[rr][cc] == startCol && parentRow[rr][cc] == startRow)) {
            int pc = parentCol[rr][cc];
            int pr = parentRow[rr][cc];
            cc = pc; rr = pr;
        }
        return dirUsed[rr][cc];
    }

    // ── Fright ────────────────────────────────────────────────────────────
    void activateFright() {
        frightTimer = FRIGHT_DURATION;
        ghostEatCombo = 0;
        for (Ghost g : ghosts) {
            // Every ghost that isn't already eaten (on its way home) turns
            // scared — including ones still waiting inside the ghost house,
            // so they come out frightened instead of hunting immediately.
            if (g.mode != GhostMode.EATEN) {
                g.mode = GhostMode.FRIGHTENED;
                g.frightTimer = FRIGHT_DURATION;
                g.frightFlash = false;
                g.frightStep = false;
                if (g.control == ControlMode.AI && !g.inHouse) g.dir = g.dir.opposite();
            }
        }
    }

    void checkUnlockGhosts() {
        for (Ghost g : ghosts) {
            if (g.inHouse && dotsEaten >= g.dotThreshold) {
                g.inHouse = false;
                g.leavingHouse = true;
            }
        }
    }

    // ── Power-ups (Pac-Man only) ────────────────────────────────────────────
    void updatePowerUps() {
        if (magnetTimer > 0) { magnetTimer--; magnetPull(); }
        if (invisibleTimer > 0) invisibleTimer--;
        if (freezeTimer > 0) freezeTimer--;
        if (speedBoostTimer > 0) speedBoostTimer--;
        if (reverseControlsTimer > 0) reverseControlsTimer--;

        if (activePowerup != null) {
            activePowerup.lifeTimer--;
            if (activePowerup.lifeTimer <= 0) {
                activePowerup = null;
                powerupSpawnTimer = POWERUP_MIN_GAP + rng.nextInt(POWERUP_MAX_GAP - POWERUP_MIN_GAP);
            }
        } else {
            powerupSpawnTimer--;
            if (powerupSpawnTimer <= 0) spawnPowerUp();
        }
    }

    void magnetPull() {
        int pc = pixelToCol(px), pr = pixelToRow(py);
        for (int dr = -MAGNET_RADIUS; dr <= MAGNET_RADIUS; dr++) {
            int nr = pr + dr;
            if (nr < 0 || nr >= ROWS) continue;
            for (int dc = -MAGNET_RADIUS; dc <= MAGNET_RADIUS; dc++) {
                int nc = Math.floorMod(pc + dc, COLS);
                Tile t = grid[nr][nc];
                if (t == Tile.DOT) {
                    grid[nr][nc] = Tile.EMPTY;
                    score += DOT_PTS;
                    dotsEaten++;
                    checkUnlockGhosts();
                } else if (t == Tile.ENERGIZER) {
                    grid[nr][nc] = Tile.EMPTY;
                    score += ENERGIZER_PTS;
                    dotsEaten++;
                    activateFright();
                    checkUnlockGhosts();
                }
            }
        }
    }

    void spawnPowerUp() {
        ArrayList<int[]> spots = new ArrayList<>();
        for (int r = 0; r < ROWS; r++)
            for (int c = 0; c < COLS; c++)
                if (reachable[r][c] && grid[r][c] != Tile.WALL && grid[r][c] != Tile.GHOST_HOUSE)
                    spots.add(new int[]{ c, r });
        if (spots.isEmpty()) return;

        int[] pick = spots.get(rng.nextInt(spots.size()));
        PowerUpType[] types = PowerUpType.values();
        PowerUpType chosen;
        do {
            chosen = types[rng.nextInt(types.length)];
        } while (chosen == lastPowerUpType);
        lastPowerUpType = chosen;
        activePowerup = new PowerUp(chosen, pick[0], pick[1]);
    }

    void applyPowerUp(PowerUpType type) {
        switch (type) {
            case MAGNET     -> magnetTimer = MAGNET_DURATION;
            case PEPPER     -> invisibleTimer = PEPPER_DURATION;
            case SNOWFLAKE  -> freezeTimer = SNOWFLAKE_DURATION;
            case CHERRY     -> speedBoostTimer = SPEED_BOOST_DURATION;
            case STRAWBERRY -> lives++;
            case BANANA     -> reverseControlsTimer = REVERSE_DURATION;
        }
    }

    // ── Ghost AI / human control ──────────────────────────────────────────
    void updateGhosts() {
        if (freezeTimer > 0) return; // snowflake: nobody's ghost moves

        for (Ghost g : ghosts) {

            if (g.mode == GhostMode.FRIGHTENED) {
                g.frightTimer--;
                g.frightFlash = (g.frightTimer < FRIGHT_FLASH_AT)
                              && ((g.frightTimer / (FPS / 4)) % 2 == 0);
                if (g.frightTimer <= 0) {
                    g.mode = (phaseIndex % 2 == 0) ? GhostMode.SCATTER : GhostMode.CHASE;
                    if (g.control == ControlMode.AI && g.isCentered()
                            && !g.inHouse && !g.leavingHouse && !g.returningToHouse) {
                        computeTarget(g);
                        g.nextDir = chooseBestDirection(g, g.targetCol, g.targetRow);
                    }
                }
            }

            if (g.inHouse) {
                g.y += (g.dir == Direction.UP ? -1 : 1);
                if (g.y <= 13 * TILE) g.dir = Direction.DOWN;
                if (g.y >= 15 * TILE) g.dir = Direction.UP;
                continue;
            }

            if (g.leavingHouse) { moveGhostTowardExit(g); continue; }
            if (g.returningToHouse) { moveGhostToHouse(g); continue; }

            if (g.control != ControlMode.AI) {
                moveHumanGhost(g);
            } else {
                computeTarget(g);
                if (g.isCentered())
                    g.nextDir = chooseBestDirection(g, g.targetCol, g.targetRow);
                moveGhost(g);
            }
        }
    }

    /** Applies the queued human input to a player-controlled ghost. Human
     *  ghosts may reverse direction at will (no AI turn restriction), may
     *  freely pass through the ghost-house tiles (so a ghost that starts
     *  inside the house is never trapped), but still cannot cross the
     *  side tunnels — same rule as the AI ghosts. */
    void moveHumanGhost(Ghost g) {
        Direction queued = (g.control == ControlMode.HUMAN1) ? p1QueuedDir : p2QueuedDir;

        if (g.isCentered()) {
            if (queued != Direction.NONE && canMoveHuman(g, queued)) {
                g.dir = queued;
            } else if (!canMoveHuman(g, g.dir)) {
                for (Direction d : new Direction[]{ Direction.UP, Direction.DOWN, Direction.LEFT, Direction.RIGHT }) {
                    if (canMoveHuman(g, d)) { g.dir = d; break; }
                }
            }
        }

        if (canMoveHuman(g, g.dir)) {
            g.x += g.dir.dx * SPEED;
            g.y += g.dir.dy * SPEED;
        }
    }

    /** Movement legality check for a human-controlled ghost: walls block,
     *  the side tunnels block (ghosts never use them), but the ghost-house
     *  interior is treated as ordinary floor. */
    boolean canMoveHuman(Ghost g, Direction d) {
        if (!g.isCentered()) return true;
        int nc = g.col() + d.dx;
        int nr = g.row() + d.dy;
        if (nc < 0 || nc >= COLS) return false;
        if (nr < 0 || nr >= ROWS) return false;
        return grid[nr][nc] != Tile.WALL;
    }

    void computeTarget(Ghost g) {
        if (g.mode == GhostMode.FRIGHTENED) return;
        int[] sc = g.scatterTarget;
        boolean chaseButBlind = (g.mode == GhostMode.CHASE && invisibleTimer > 0);
        if (g.mode == GhostMode.SCATTER || chaseButBlind) {
            g.targetCol = sc[0];
            g.targetRow = sc[1];
            return;
        }

        int pc = pixelToCol(px);
        int pr = pixelToRow(py);
        switch (g.name) {
            case "blinky" -> {
                g.targetCol = pc;
                g.targetRow = pr;
            }
            case "pinky" -> {
                int tc = pc + pDir.dx * 4;
                int tr = pr + pDir.dy * 4;
                if (pDir == Direction.UP) tc -= 4;
                g.targetCol = tc;
                g.targetRow = tr;
            }
            case "inky" -> {
                Ghost blinky = ghosts[0];
                int aheadC = pc + pDir.dx * 2;
                int aheadR = pr + pDir.dy * 2;
                if (pDir == Direction.UP) aheadC -= 2;
                int bc = blinky.col(), br = blinky.row();
                g.targetCol = aheadC + (aheadC - bc);
                g.targetRow = aheadR + (aheadR - br);
            }
            case "clyde" -> {
                double dist = Math.hypot(pc - g.col(), pr - g.row());
                if (dist > 8) { g.targetCol = pc; g.targetRow = pr; }
                else          { g.targetCol = sc[0]; g.targetRow = sc[1]; }
            }
        }
    }

    Direction chooseBestDirection(Ghost g, int targetCol, int targetRow) {
        if (g.mode == GhostMode.FRIGHTENED) {
            ArrayList<Direction> valid = validGhostDirs(g, false);
            return valid.isEmpty() ? g.dir : valid.get(rng.nextInt(valid.size()));
        }

        ArrayList<Direction> valid = validGhostDirs(g, false);
        if (valid.isEmpty()) return g.dir;

        Direction best = null;
        double bestDist = Double.MAX_VALUE;
        for (Direction d : valid) {
            int nc = g.col() + d.dx;
            int nr = g.row() + d.dy;
            double dist = Math.hypot(targetCol - nc, targetRow - nr);
            if (dist < bestDist) { bestDist = dist; best = d; }
        }
        return best != null ? best : valid.get(0);
    }

    ArrayList<Direction> validGhostDirs(Ghost g, boolean allowReverse) {
        ArrayList<Direction> list = new ArrayList<>();
        for (Direction d : new Direction[]{ Direction.UP, Direction.LEFT, Direction.DOWN, Direction.RIGHT }) {
            if (!allowReverse && d == g.dir.opposite()) continue;
            int nc = g.col() + d.dx;
            int nr = g.row() + d.dy;
            if (nc < 0 || nc >= COLS) continue;
            if (nr < 0 || nr >= ROWS) continue;
            Tile t = grid[nr][nc];
            if (t == Tile.WALL || t == Tile.GHOST_HOUSE) continue;
            list.add(d);
        }
        if (list.isEmpty() && !allowReverse) return validGhostDirs(g, true);
        return list;
    }

    boolean canMoveGhost(Ghost g, Direction d) {
        if (!g.isCentered()) return true;
        int nc = g.col() + d.dx;
        int nr = g.row() + d.dy;
        if (nc < 0 || nc >= COLS) return false;
        if (nr < 0 || nr >= ROWS) return false;
        Tile t = grid[nr][nc];
        if (t == Tile.WALL) return false;
        if (t == Tile.GHOST_HOUSE && !(g.inHouse || g.leavingHouse || g.returningToHouse)) return false;
        return true;
    }

    void moveGhost(Ghost g) {
        boolean frightened = g.mode == GhostMode.FRIGHTENED;

        if (frightened) {
            g.frightStep = !g.frightStep;
            if (!g.frightStep) return;
        }

        if (g.isCentered()) {
            g.dir = g.nextDir;
        }

        if (!canMoveGhost(g, g.dir)) {
            Direction fallback = null;
            for (Direction d : validGhostDirs(g, true)) {
                if (canMoveGhost(g, d)) { fallback = d; break; }
            }
            if (fallback == null) return;
            g.dir = fallback;
            g.nextDir = fallback;
        }

        float speed = SPEED;
        g.x += g.dir.dx * speed;
        g.y += g.dir.dy * speed;
    }

    void moveGhostTowardExit(Ghost g) {
        float exitX = HOUSE_EXIT_COL * TILE;
        float exitY = HOUSE_EXIT_ROW * TILE;
        float sp = 1f;
        if (Math.abs(g.x - exitX) > sp) {
            g.x += (g.x < exitX) ? sp : -sp;
        } else {
            g.x = exitX;
            if (Math.abs(g.y - exitY) > sp) {
                g.y += (g.y < exitY) ? sp : -sp;
            } else {
                g.y = exitY;
                g.leavingHouse = false;
                g.dir = Direction.LEFT;
            }
        }
    }

    void moveGhostToHouse(Ghost g) {
        float destX = g.startX, destY = g.startY;
        float sp = SPEED + 1f;
        if (Math.abs(g.x - destX) > sp) {
            g.x += (g.x < destX) ? sp : -sp;
        } else {
            g.x = destX;
            if (Math.abs(g.y - destY) > sp) {
                g.y += (g.y < destY) ? sp : -sp;
            } else {
                g.y = destY;
                g.returningToHouse = false;
                g.inHouse = false;
                g.leavingHouse = true;
                g.mode = (phaseIndex % 2 == 0) ? GhostMode.SCATTER : GhostMode.CHASE;
            }
        }
    }

    // ── Collisions ────────────────────────────────────────────────────────
    void checkCollisions() {
        float pcx = px + TILE / 2f;
        float pcy = py + TILE / 2f;

        for (Ghost g : ghosts) {
            if (g.inHouse || g.leavingHouse) continue;
            float gcx = g.x + TILE / 2f;
            float gcy = g.y + TILE / 2f;
            float dist = (float) Math.hypot(pcx - gcx, pcy - gcy);
            if (dist < TILE * 0.75f) {
                if (g.mode == GhostMode.FRIGHTENED) {
                    ghostEatCombo++;
                    int pts = GHOST_BASE * (1 << (ghostEatCombo - 1));
                    score += pts;
                    if (score > highScore) {
                        highScore = score;
                        newHighScore = true;
                        saveHighScore();
                    }
                    if (g.control == ControlMode.HUMAN1) player1Score = Math.max(0, player1Score - EATEN_PENALTY);
                    else if (g.control == ControlMode.HUMAN2) player2Score = Math.max(0, player2Score - EATEN_PENALTY);
                    g.mode = GhostMode.EATEN;
                    g.returningToHouse = true;
                } else if (g.mode != GhostMode.EATEN) {
                    if (g.control == ControlMode.HUMAN1) player1Score += BLOCK_POINTS;
                    else if (g.control == ControlMode.HUMAN2) player2Score += BLOCK_POINTS;
                    state = State.DEAD;
                    stateTimer = FPS * 3;
                    pDir = Direction.NONE;
                    return;
                }
            }
        }
    }

    void checkWin() {
        for (int r = 0; r < ROWS; r++)
            for (int c = 0; c < COLS; c++)
                if (grid[r][c] == Tile.DOT || grid[r][c] == Tile.ENERGIZER) return;

        // Efficiency bonus: the faster the maze is cleared, the bigger the reward.
        long framesAllowedForFullBonus = FPS * 45L;
        long over = Math.max(0, levelTimer - framesAllowedForFullBonus);
        int bonus = (int) Math.max(0, 5000 - over * 2);
        score += bonus;
        if (score > highScore) { highScore = score; newHighScore = true; saveHighScore(); }

        state = State.LEVEL_CLEAR;
        stateTimer = FPS * 3;
        dotFlashTimer = 0;
    }

    // ══════════════════════════════════════════════════════════════════════
    //  RENDERING
    // ══════════════════════════════════════════════════════════════════════
    @Override
    protected void paintComponent(Graphics g0) {
        super.paintComponent(g0);
        Graphics2D g = (Graphics2D) g0;
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        if (state == State.TITLE) {
            drawHUD(g);
            g.translate(0, HUD_HEIGHT);
            drawTitle(g);
            return;
        }
        if (state == State.SELECT_MODE) {
            drawHUD(g);
            g.translate(0, HUD_HEIGHT);
            drawMaze(g);
            drawSelectMode(g);
            return;
        }
        if (state == State.SELECT_PLAYERS) {
            drawHUD(g);
            g.translate(0, HUD_HEIGHT);
            drawMaze(g);
            drawSelectPlayers(g);
            return;
        }
        if (state == State.SELECT_GHOST) {
            drawHUD(g);
            g.translate(0, HUD_HEIGHT);
            drawMaze(g);
            drawSelectGhost(g);
            return;
        }

        drawHUD(g);
        g.translate(0, HUD_HEIGHT);
        switch (state) {
            case GAME_OVER -> { drawMaze(g); drawGhosts(g); drawPacMan(g); drawGameOver(g); }
            default -> {
                drawMaze(g);
                drawPowerUp(g);
                if (state != State.DEAD || stateTimer > FPS) drawGhosts(g);
                drawPacMan(g);
                if (state == State.READY)       drawReady(g);
                if (state == State.LEVEL_CLEAR) drawLevelClear(g);
                if (state == State.PLAYING && paused) drawPaused(g);
            }
        }
    }

    // ── HUD ─────────────────────────────────────────────────────────────────
    // Two clean rows, every element positioned from a measured text width so
    // nothing can ever overlap regardless of score length, level, or how many
    // power-ups happen to be active at once.
    void drawHUD(Graphics2D g) {
        g.setColor(Color.BLACK);
        g.fillRect(0, 0, W, HUD_HEIGHT);

        final int MARGIN = 8;
        final int row1Y  = 20;   // top row baseline
        final int row2Y  = 44;   // bottom row baseline

        // ---- Row 1, left: life icons in a fixed-width reserved zone ----
        final int livesZoneW = 58;
        int liveCount = Math.min(Math.max(lives - 1, 0), 3);
        for (int i = 0; i < liveCount; i++) {
            g.setColor(Color.YELLOW);
            g.fillArc(MARGIN + i * 18, row1Y - 12, 14, 14, 35, 290);
        }

        // ---- Row 1, left: score (starts after the reserved lives zone) ----
        g.setFont(new Font("Arial", Font.BOLD, 13));
        g.setColor(Color.WHITE);
        g.drawString("SCORE " + score, MARGIN + livesZoneW, row1Y);

        // ---- Row 1, right: high score, right-aligned by measured width ----
        g.setFont(new Font("Arial", Font.BOLD, 13));
        String hsText = "BEST " + highScore;
        FontMetrics hsFm = g.getFontMetrics();
        int hsX = W - MARGIN - hsFm.stringWidth(hsText);
        g.setColor(newHighScore ? Color.YELLOW : Color.CYAN);
        g.drawString(hsText, hsX, row1Y);

        // ---- Row 1, center: ghost-mode player scores (normal mode: none) ----
        if (gameMode == GameMode.GHOST && (player1GhostIndex >= 0 || player2GhostIndex >= 0)) {
            g.setFont(new Font("Arial", Font.BOLD, 12));
            FontMetrics fm = g.getFontMetrics();
            int centerX = W / 2;

            String p1Text = (player1GhostIndex >= 0)
                    ? ("P1(" + ghosts[player1GhostIndex].name.toUpperCase() + ") " + player1Score) : null;
            String p2Text = (player2GhostIndex >= 0)
                    ? ("P2(" + ghosts[player2GhostIndex].name.toUpperCase() + ") " + player2Score) : null;

            if (p1Text != null && p2Text != null) {
                g.setColor(new Color(255, 120, 120));
                g.drawString(p1Text, centerX - 6 - fm.stringWidth(p1Text), row1Y);
                g.setColor(new Color(255, 200, 120));
                g.drawString(p2Text, centerX + 6, row1Y);
            } else if (p1Text != null) {
                g.setColor(new Color(255, 120, 120));
                g.drawString(p1Text, centerX - fm.stringWidth(p1Text) / 2, row1Y);
            }
        }

        // ---- Row 2, left: level (only relevant once a match is underway) ----
        boolean showLevel = state == State.PLAYING || state == State.READY
                || state == State.DEAD || state == State.LEVEL_CLEAR;
        if (showLevel) {
            g.setFont(new Font("Arial", Font.BOLD, 11));
            g.setColor(new Color(190, 190, 190));
            g.drawString("LEVEL " + level, MARGIN, row2Y);
        }

        // ---- Row 2, right: active power-up timers, packed right-to-left ----
        g.setFont(new Font("Arial", Font.BOLD, 10));
        FontMetrics pfm = g.getFontMetrics();
        int cursorX = W - MARGIN;
        final int GAP = 10;

        if (freezeTimer > 0) {
            String t = "FREEZE " + (freezeTimer / FPS + 1) + "s";
            int w = pfm.stringWidth(t);
            cursorX -= w;
            g.setColor(new Color(150, 220, 255));
            g.drawString(t, cursorX, row2Y);
            cursorX -= GAP;
        }
        if (invisibleTimer > 0) {
            String t = "INVISIBLE " + (invisibleTimer / FPS + 1) + "s";
            int w = pfm.stringWidth(t);
            cursorX -= w;
            g.setColor(new Color(220, 90, 90));
            g.drawString(t, cursorX, row2Y);
            cursorX -= GAP;
        }
        if (magnetTimer > 0) {
            String t = "MAGNET " + (magnetTimer / FPS + 1) + "s";
            int w = pfm.stringWidth(t);
            cursorX -= w;
            g.setColor(new Color(130, 190, 255));
            g.drawString(t, cursorX, row2Y);
            cursorX -= GAP;
        }
        if (speedBoostTimer > 0) {
            String t = "SPEED x3 " + (speedBoostTimer / FPS + 1) + "s";
            int w = pfm.stringWidth(t);
            cursorX -= w;
            g.setColor(new Color(255, 210, 90));
            g.drawString(t, cursorX, row2Y);
            cursorX -= GAP;
        }
        if (reverseControlsTimer > 0) {
            String t = "REVERSED " + (reverseControlsTimer / FPS + 1) + "s";
            int w = pfm.stringWidth(t);
            cursorX -= w;
            g.setColor(new Color(230, 200, 90));
            g.drawString(t, cursorX, row2Y);
        }
    }

    // ── Themed selection screens ───────────────────────────────────────────
    // Shared "arcade marquee" look: a neon-yellow rounded border, a
    // multi-colored chomped-letter title, and card-style option panels sized
    // to always fit inside the 448×496 play field (no more clipped text).

    /** Draws the yellow marquee border used to frame every menu screen. */
    void drawMarqueeFrame(Graphics2D g) {
        g.setStroke(new BasicStroke(3f));
        g.setColor(Color.YELLOW);
        g.drawRoundRect(8, 8, W - 16, ROWS * TILE - 16, 14, 14);
        g.setStroke(new BasicStroke(1f));
        g.setColor(new Color(255, 255, 255, 90));
        g.drawRoundRect(13, 13, W - 26, ROWS * TILE - 26, 10, 10);
    }

    /** "PAC-MAN" rendered letter-by-letter in alternating arcade colors. */
    void drawMarqueeWordmark(Graphics2D g, int baselineY, int fontSize) {
        Font f = new Font("Arial Black", Font.BOLD, fontSize);
        g.setFont(f);
        FontMetrics fm = g.getFontMetrics();
        String word = "PAC-MAN";
        int totalW = fm.stringWidth(word);
        int x = (W - totalW) / 2;
        Color[] palette = {
            Color.YELLOW, GHOST_COLORS[0], GHOST_COLORS[1],
            Color.WHITE,  GHOST_COLORS[2], GHOST_COLORS[3], Color.YELLOW
        };
        for (int i = 0; i < word.length(); i++) {
            char ch = word.charAt(i);
            g.setColor(palette[i % palette.length]);
            g.drawString(String.valueOf(ch), x, baselineY);
            x += fm.charWidth(ch);
        }
    }

    /** Centers text within a sub-region [x, x+w) instead of the whole screen —
     *  used for the two-card layouts so long labels never bleed outside a card. */
    void drawCenteredIn(Graphics2D g, String s, int x, int w, int y, Color c) {
        FontMetrics fm = g.getFontMetrics();
        int tx = x + (w - fm.stringWidth(s)) / 2;
        g.setColor(c);
        g.drawString(s, tx, y);
    }

    void drawTitle(Graphics2D g) {
        drawMaze(g);
        g.setColor(new Color(0, 0, 0, 175));
        g.fillRect(0, 0, W, ROWS * TILE);
        drawMarqueeFrame(g);

        drawMarqueeWordmark(g, ROWS * TILE / 2 - 96, 32);

        // A little chase animation under the title: three ghosts fleeing Pac-Man.
        int iconY = ROWS * TILE / 2 - 74;
        int iconSize = 20;
        int chaseShift = (titleBlink / 6) % 12;
        int chaseX = W / 2 - 78 + chaseShift;
        drawGhostShape(g, chaseX,      iconY, iconSize, iconSize, GHOST_COLORS[2], false, false);
        drawGhostShape(g, chaseX + 24, iconY, iconSize, iconSize, GHOST_COLORS[1], false, false);
        drawGhostShape(g, chaseX + 48, iconY, iconSize, iconSize, GHOST_COLORS[0], false, false);
        g.setColor(Color.YELLOW);
        int mouth = ((titleBlink / 6) % 2 == 0) ? 30 : 5;
        g.fillArc(chaseX + 74, iconY, iconSize, iconSize, mouth, 360 - mouth * 2);

        g.setFont(new Font("Arial", Font.PLAIN, 12));
        drawCenteredString(g, "Two ways to run the maze.", ROWS * TILE / 2 - 34, Color.WHITE);

        g.setFont(new Font("Arial", Font.BOLD, 12));
        drawCenteredIn(g, "NORMAL", W / 2 - 100, 90, ROWS * TILE / 2 - 12, Color.YELLOW);
        drawCenteredIn(g, "GHOST",  W / 2 + 10,  90, ROWS * TILE / 2 - 12, GHOST_COLORS[0]);
        g.setFont(new Font("Arial", Font.PLAIN, 10));
        drawCenteredIn(g, "you = Pac-Man", W / 2 - 100, 90, ROWS * TILE / 2 + 2, new Color(220, 220, 220));
        drawCenteredIn(g, "you = a ghost",  W / 2 + 10,  90, ROWS * TILE / 2 + 2, new Color(220, 220, 220));

        if ((titleBlink / (FPS / 2)) % 2 == 0) {
            g.setFont(new Font("Arial", Font.BOLD, 15));
            drawCenteredString(g, "PRESS  ENTER", ROWS * TILE / 2 + 34, Color.WHITE);
        }

        g.setFont(new Font("Arial", Font.PLAIN, 10));
        drawCenteredString(g, "R: title screen anytime   ·   P: pause", ROWS * TILE - 18, new Color(180, 180, 180));
    }

    void drawSelectMode(Graphics2D g) {
        g.setColor(new Color(0, 0, 0, 175));
        g.fillRect(0, 0, W, ROWS * TILE);
        drawMarqueeFrame(g);

        drawMarqueeWordmark(g, 46, 18);
        g.setFont(new Font("Arial", Font.BOLD, 15));
        drawCenteredString(g, "CHOOSE A GAME MODE", 70, Color.WHITE);

        int cardW = 190, cardH = 210, gap = 14;
        int startX = (W - (cardW * 2 + gap)) / 2;
        int cardY = 92;

        drawModeCard(g, startX, cardY, cardW, cardH,
                "1", "NORMAL MODE", "Arrow keys drive Pac-Man.", "All 4 ghosts hunt you.", true, Color.YELLOW);
        drawModeCard(g, startX + cardW + gap, cardY, cardW, cardH,
                "2", "GHOST MODE", "You drive a ghost instead.", "Pac-Man plays himself.", false, GHOST_COLORS[0]);

        g.setFont(new Font("Arial", Font.PLAIN, 11));
        drawCenteredString(g, "R returns to the title screen", ROWS * TILE - 18, new Color(180, 180, 180));
    }

    /** One selectable option card, shared by the mode/players/ghost screens. */
    void drawModeCard(Graphics2D g, int x, int y, int w, int h,
                       String key, String title, String line1, String line2,
                       boolean isPac, Color accent) {
        g.setColor(new Color(15, 15, 45));
        g.fillRoundRect(x, y, w, h, 18, 18);
        g.setColor(accent);
        g.setStroke(new BasicStroke(2.5f));
        g.drawRoundRect(x, y, w, h, 18, 18);
        g.setStroke(new BasicStroke(1f));

        // Key badge.
        int badge = 30;
        g.setColor(Color.WHITE);
        g.fillOval(x + w / 2 - badge / 2, y + 12, badge, badge);
        g.setColor(new Color(20, 20, 20));
        g.setFont(new Font("Arial", Font.BOLD, 16));
        FontMetrics fm = g.getFontMetrics();
        g.drawString(key, x + w / 2 - fm.stringWidth(key) / 2, y + 33);

        // Icon.
        int iconY = y + 54;
        if (isPac) {
            g.setColor(Color.YELLOW);
            g.fillArc(x + w / 2 - 22, iconY, 44, 44, 25, 310);
        } else {
            drawGhostShape(g, x + w / 2 - 20, iconY, 40, 40, accent, false, false);
        }

        g.setFont(new Font("Arial", Font.BOLD, 14));
        drawCenteredIn(g, title, x, w, iconY + 62, Color.WHITE);
        g.setFont(new Font("Arial", Font.PLAIN, 11));
        drawCenteredIn(g, line1, x, w, iconY + 80, new Color(210, 210, 210));
        drawCenteredIn(g, line2, x, w, iconY + 96, new Color(210, 210, 210));
    }

    void drawSelectPlayers(Graphics2D g) {
        g.setColor(new Color(0, 0, 0, 175));
        g.fillRect(0, 0, W, ROWS * TILE);
        drawMarqueeFrame(g);

        drawMarqueeWordmark(g, 46, 18);
        g.setFont(new Font("Arial", Font.BOLD, 15));
        drawCenteredString(g, "GHOST MODE  ·  HOW MANY PLAYERS?", 70, Color.WHITE);

        int cardW = 190, cardH = 210, gap = 14;
        int startX = (W - (cardW * 2 + gap)) / 2;
        int cardY = 92;

        drawPlayersCard(g, startX, cardY, cardW, cardH, "1", "ONE PLAYER", "Arrow Keys", 1);
        drawPlayersCard(g, startX + cardW + gap, cardY, cardW, cardH, "2", "TWO PLAYERS", "Arrows + WASD", 2);

        g.setFont(new Font("Arial", Font.PLAIN, 11));
        drawCenteredString(g, "R returns to the title screen", ROWS * TILE - 18, new Color(180, 180, 180));
    }

    void drawPlayersCard(Graphics2D g, int x, int y, int w, int h,
                          String key, String title, String subtitle, int players) {
        Color accent = new Color(0, 220, 220);
        g.setColor(new Color(15, 15, 45));
        g.fillRoundRect(x, y, w, h, 18, 18);
        g.setColor(accent);
        g.setStroke(new BasicStroke(2.5f));
        g.drawRoundRect(x, y, w, h, 18, 18);
        g.setStroke(new BasicStroke(1f));

        int badge = 30;
        g.setColor(Color.WHITE);
        g.fillOval(x + w / 2 - badge / 2, y + 12, badge, badge);
        g.setColor(new Color(20, 20, 20));
        g.setFont(new Font("Arial", Font.BOLD, 16));
        FontMetrics fm = g.getFontMetrics();
        g.drawString(key, x + w / 2 - fm.stringWidth(key) / 2, y + 33);

        int iconY = y + 58;
        if (players == 1) {
            drawGhostShape(g, x + w / 2 - 20, iconY, 40, 40, GHOST_COLORS[0], false, false);
        } else {
            drawGhostShape(g, x + w / 2 - 40, iconY, 34, 34, GHOST_COLORS[0], false, false);
            drawGhostShape(g, x + w / 2 + 6,  iconY, 34, 34, GHOST_COLORS[3], false, false);
        }

        g.setFont(new Font("Arial", Font.BOLD, 14));
        drawCenteredIn(g, title, x, w, iconY + 62, Color.WHITE);
        g.setFont(new Font("Arial", Font.PLAIN, 11));
        drawCenteredIn(g, subtitle, x, w, iconY + 80, new Color(210, 210, 210));
    }

    void drawSelectGhost(Graphics2D g) {
        g.setColor(new Color(0, 0, 0, 175));
        g.fillRect(0, 0, W, ROWS * TILE);
        drawMarqueeFrame(g);

        drawMarqueeWordmark(g, 46, 18);
        g.setFont(new Font("Arial", Font.BOLD, 15));
        drawCenteredString(g, "PICK YOUR GHOST", 70, Color.WHITE);

        int cardW = 150, cardH = 190, gap = 16;
        int startX = (W - (cardW * 2 + gap)) / 2;
        int cardY = 100;

        drawGhostPickCard(g, startX, cardY, cardW, cardH, GHOST_COLORS[0], "BLINKY", "RED", selectedGhostIndex == 0);
        drawGhostPickCard(g, startX + cardW + gap, cardY, cardW, cardH, GHOST_COLORS[3], "CLYDE", "ORANGE", selectedGhostIndex == 3);

        g.setFont(new Font("Arial", Font.PLAIN, 12));
        drawCenteredString(g, "\u2190 / \u2192  to choose   ·   ENTER to confirm", ROWS * TILE - 18, Color.WHITE);
    }

    void drawGhostPickCard(Graphics2D g, int x, int y, int w, int h,
                            Color color, String name, String label, boolean selected) {
        g.setColor(selected ? new Color(30, 30, 60) : new Color(12, 12, 30));
        g.fillRoundRect(x, y, w, h, 18, 18);
        g.setColor(selected ? Color.YELLOW : new Color(90, 90, 90));
        g.setStroke(new BasicStroke(selected ? 3f : 1.5f));
        g.drawRoundRect(x, y, w, h, 18, 18);
        g.setStroke(new BasicStroke(1f));

        drawGhostShape(g, x + w / 2 - 26, y + 26, 52, 52, color, false, false);

        g.setFont(new Font("Arial", Font.BOLD, 15));
        drawCenteredIn(g, name, x, w, y + 104, selected ? Color.YELLOW : Color.WHITE);
        g.setFont(new Font("Arial", Font.PLAIN, 11));
        drawCenteredIn(g, label, x, w, y + 122, new Color(210, 210, 210));

        if (selected) {
            g.setFont(new Font("Arial", Font.BOLD, 11));
            drawCenteredIn(g, "\u25B2 SELECTED \u25B2", x, w, y + h - 14, Color.YELLOW);
        }
    }

    void drawReady(Graphics2D g) {
        g.setFont(new Font("Arial", Font.BOLD, 16));
        drawCenteredString(g, "READY!", ROWS * TILE / 2 - 2, Color.YELLOW);
    }

    void drawPaused(Graphics2D g) {
        g.setColor(new Color(0, 0, 0, 140));
        g.fillRect(0, 0, W, ROWS * TILE);
        g.setFont(new Font("Arial", Font.BOLD, 24));
        drawCenteredString(g, "PAUSED", ROWS * TILE / 2, Color.WHITE);
    }

    void drawGameOver(Graphics2D g) {
        g.setColor(new Color(0, 0, 0, 170));
        g.fillRect(0, 0, W, ROWS * TILE);

        boolean ghostVictory = gameMode == GameMode.GHOST;
        g.setFont(new Font("Arial", Font.BOLD, 28));
        drawCenteredString(g, ghostVictory ? "YOU  WIN!" : "GAME  OVER",
                ROWS * TILE / 2 - 60, ghostVictory ? Color.GREEN : Color.RED);

        g.setFont(new Font("Arial", Font.BOLD, 16));
        drawCenteredString(g, "Pac-Man score: " + score, ROWS * TILE / 2 - 20, Color.WHITE);
        if (gameMode == GameMode.GHOST && player1GhostIndex >= 0)
            drawCenteredString(g, "Player 1 (" + ghosts[player1GhostIndex].name.toUpperCase() + "): " + player1Score,
                    ROWS * TILE / 2 + 6, new Color(255, 120, 120));
        if (gameMode == GameMode.GHOST && player2GhostIndex >= 0)
            drawCenteredString(g, "Player 2 (" + ghosts[player2GhostIndex].name.toUpperCase() + "): " + player2Score,
                    ROWS * TILE / 2 + 30, new Color(255, 200, 120));

        if (newHighScore) {
            g.setFont(new Font("Arial", Font.BOLD, 14));
            drawCenteredString(g, "NEW HIGH SCORE FOR PAC-MAN!", ROWS * TILE / 2 + 56, Color.YELLOW);
        }
        g.setFont(new Font("Arial", Font.PLAIN, 13));
        drawCenteredString(g, "Press ENTER for the title screen", ROWS * TILE / 2 + 80, Color.WHITE);
    }

    void drawLevelClear(Graphics2D g) {
        boolean ghostFailure = gameMode == GameMode.GHOST;
        if ((dotFlashTimer / 2) % 2 == 0) {
            g.setColor(ghostFailure ? new Color(180, 0, 0, 60) : new Color(0, 0, 180, 60));
            g.fillRect(0, 0, W, ROWS * TILE);
        }
        g.setFont(new Font("Arial", Font.BOLD, 20));
        if (ghostFailure) {
            drawCenteredString(g, "YOU  FAILED!", ROWS * TILE / 2 - 12, new Color(255, 90, 90));
            g.setFont(new Font("Arial", Font.PLAIN, 12));
            drawCenteredString(g, "Pac-Man cleared the maze.", ROWS * TILE / 2 + 10, Color.WHITE);
        } else {
            drawCenteredString(g, "LEVEL  CLEAR!", ROWS * TILE / 2, Color.CYAN);
        }
    }

    void drawMaze(Graphics2D g) {
        g.setColor(Color.BLACK);
        g.fillRect(0, 0, W, ROWS * TILE);

        for (int r = 0; r < ROWS; r++) {
            for (int c = 0; c < COLS; c++) {
                int x = c * TILE, y = r * TILE;
                if (grid[r][c] == Tile.DOT) {
                    g.setColor(new Color(255, 200, 150));
                    g.fillRect(x + TILE / 2 - 1, y + TILE / 2 - 1, 3, 3);
                } else if (grid[r][c] == Tile.ENERGIZER) {
                    double pulse = Math.sin(System.currentTimeMillis() / 200.0) * 0.3 + 0.7;
                    int r2 = (int) (6 * pulse);
                    g.setColor(new Color(255, 200, 150));
                    g.fillOval(x + TILE / 2 - r2, y + TILE / 2 - r2, r2 * 2, r2 * 2);
                }
            }
        }

        if (wallDirty || wallBuffer == null) rebuildWallBuffer();
        g.drawImage(wallBuffer, 0, 0, null);
    }

    void rebuildWallBuffer() {
        wallBuffer = new BufferedImage(W, ROWS * TILE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D wg = wallBuffer.createGraphics();
        wg.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        wg.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        for (int r = 0; r < ROWS; r++)
            for (int c = 0; c < COLS; c++)
                if (grid[r][c] == Tile.WALL)
                    drawWallTile(wg, c, r);
        wg.dispose();
        wallDirty = false;
    }

    void drawWallTile(Graphics2D g, int c, int r) {
        int x = c * TILE, y = r * TILE, T = TILE;
        boolean U  = isWall(c,   r - 1);
        boolean D  = isWall(c,   r + 1);
        boolean L  = isWall(c - 1, r);
        boolean R  = isWall(c + 1, r);
        boolean UR = isWall(c + 1, r - 1);

        g.setColor(new Color(33, 33, 255));
        g.fillRect(x, y, T, T);
        g.setColor(new Color(0, 0, 160));
        g.fillRect(x + 2, y + 2, T - 4, T - 4);

        g.setColor(new Color(100, 100, 255));
        if (R) g.fillRect(x + T / 2, y + 2, T / 2 + 1, T - 4);
        if (L) g.fillRect(x,         y + 2, T / 2 + 1, T - 4);
        if (D) g.fillRect(x + 2, y + T / 2, T - 4, T / 2 + 1);
        if (U) g.fillRect(x + 2, y,         T - 4, T / 2 + 1);
        if (U && R && !UR) {
            g.setColor(Color.BLACK);
            g.fillArc(x + T / 2 - T / 4, y - T / 4, T / 2, T / 2, 270, 90);
        }
    }

    /** Draws the current power-up (if any) on the map, using the embedded
     *  cherry / strawberry / banana / magnet / ghost-pepper / snowflake
     *  icons, with a drawn fallback if decoding an icon ever fails. */
    void drawPowerUp(Graphics2D g) {
        if (activePowerup == null) return;

        boolean flashingOff = activePowerup.lifeTimer < FPS * 3
                && (activePowerup.lifeTimer / (FPS / 4)) % 2 == 0;
        if (flashingOff) return;

        int x = activePowerup.col * TILE;
        int y = activePowerup.row * TILE;
        int size = (int) (TILE * 0.9);
        int ox = x + TILE / 2 - size / 2;
        int oy = y + TILE / 2 - size / 2;

        BufferedImage img = switch (activePowerup.type) {
            case MAGNET     -> magnetImg;
            case PEPPER     -> pepperImg;
            case SNOWFLAKE  -> snowflakeImg;
            case CHERRY     -> cherryImg;
            case STRAWBERRY -> strawberryImg;
            case BANANA     -> bananaImg;
        };

        if (img != null) {
            g.drawImage(img, ox, oy, size, size, null);
        } else {
            Color c = switch (activePowerup.type) {
                case MAGNET     -> new Color(80, 140, 255);
                case PEPPER     -> new Color(200, 40, 40);
                case SNOWFLAKE  -> new Color(160, 220, 255);
                case CHERRY     -> new Color(200, 20, 40);
                case STRAWBERRY -> new Color(220, 60, 80);
                case BANANA     -> new Color(240, 210, 60);
            };
            String letter = switch (activePowerup.type) {
                case MAGNET     -> "M";
                case PEPPER     -> "P";
                case SNOWFLAKE  -> "S";
                case CHERRY     -> "C";
                case STRAWBERRY -> "B";
                case BANANA     -> "N";
            };
            g.setColor(c);
            g.fillOval(ox, oy, size, size);
            g.setColor(Color.BLACK);
            g.setFont(new Font("Arial", Font.BOLD, 11));
            g.drawString(letter, x + TILE / 2 - 3, y + TILE / 2 + 4);
        }
    }

    void drawGhosts(Graphics2D g) {
        for (Ghost gh : ghosts) {
            boolean scared = gh.mode == GhostMode.FRIGHTENED;
            boolean eaten  = gh.mode == GhostMode.EATEN || gh.returningToHouse;
            drawGhostShape(g, (int) gh.x, (int) gh.y, TILE, TILE, gh.drawColor(), scared, eaten);
            if (gh.control != ControlMode.AI) {
                g.setColor(gh.control == ControlMode.HUMAN1 ? new Color(255, 90, 90) : new Color(255, 200, 90));
                g.setFont(new Font("Arial", Font.BOLD, 9));
                String tag = gh.control == ControlMode.HUMAN1 ? "P1" : "P2";
                g.drawString(tag, (int) gh.x + 2, (int) gh.y - 2);
            }
        }
    }

    void drawGhostShape(Graphics2D g, int x, int y, int w, int h,
                        Color color, boolean scared, boolean eaten) {
        if (eaten) {
            drawGhostEyes(g, x, y, w, h, Direction.LEFT);
            return;
        }

        Color bodyColor = scared ? new Color(0, 0, 200) : color;
        g.setColor(bodyColor);
        g.fillRoundRect(x, y, w, h - 4, w, w);
        g.fillRect(x, y + h / 2, w, h / 2 - 4);
        int waves = 4, ww = w / waves;
        for (int i = 0; i < waves; i++)
            g.fillArc(x + i * ww, y + h - 8, ww, 8, 0, 180);
        if (!scared) {
            drawGhostEyes(g, x, y, w, h, Direction.RIGHT);
        } else {
            g.setColor(Color.WHITE);
            int ew = w / 5, eh = h / 6;
            g.fillOval(x + w / 4 - ew / 2, y + h / 3, ew, eh);
            g.fillOval(x + 3 * w / 4 - ew / 2, y + h / 3, ew, eh);
            int my = y + 2 * h / 3;
            int ml = x + w / 5, mr = x + 4 * w / 5, mw2 = mr - ml;
            int segs = 6;
            int[] xs = new int[segs + 1], ys = new int[segs + 1];
            for (int i = 0; i <= segs; i++) {
                xs[i] = ml + i * mw2 / segs;
                ys[i] = my + (i % 2 == 0 ? 0 : -3);
            }
            g.drawPolyline(xs, ys, segs + 1);
        }
    }

    void drawGhostEyes(Graphics2D g, int x, int y, int w, int h, Direction lookDir) {
        int ew = w / 4, eh = h / 4;
        int eyeY = y + h / 3 - eh / 2;
        int leftEyeX  = x + w / 4 - ew / 2;
        int rightEyeX = x + 3 * w / 4 - ew / 2;

        g.setColor(Color.WHITE);
        g.fillOval(leftEyeX,  eyeY, ew, eh);
        g.fillOval(rightEyeX, eyeY, ew, eh);

        int pd = Math.max(ew / 3, 1);
        int pdx = lookDir.dx, pdy = lookDir.dy;
        g.setColor(new Color(0, 0, 200));
        g.fillOval(leftEyeX  + ew / 2 - pd / 2 + pdx * 2, eyeY + eh / 2 - pd / 2 + pdy * 2, pd, pd);
        g.fillOval(rightEyeX + ew / 2 - pd / 2 + pdx * 2, eyeY + eh / 2 - pd / 2 + pdy * 2, pd, pd);
    }

    void drawPacMan(Graphics2D g) {
        if (state == State.DEAD && stateTimer < FPS * 2) {
            int closingAngle = (int) (360.0 * stateTimer / (FPS * 2));
            g.setColor(Color.YELLOW);
            g.fillArc((int) px, (int) py, TILE, TILE, 90, closingAngle);
            return;
        }

        Composite oldComposite = g.getComposite();
        boolean invisible = invisibleTimer > 0 && state == State.PLAYING;
        if (invisible) {
            boolean showFaint = (invisibleTimer / (FPS / 4)) % 2 == 0;
            float alpha = showFaint ? 0.35f : 0.15f;
            g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha));
        }

        g.setColor(Color.YELLOW);
        int arc = 360 - mouthAngle * 2;
        int startAngle = switch (pDir) {
            case RIGHT -> mouthAngle;
            case LEFT  -> 180 + mouthAngle;
            case UP    -> 90 + mouthAngle;
            case DOWN  -> 270 + mouthAngle;
            default    -> mouthAngle;
        };
        g.fillArc((int) px, (int) py, TILE, TILE, startAngle, arc);

        if (invisible) g.setComposite(oldComposite);
    }

    void drawCenteredString(Graphics2D g, String s, int y, Color c) {
        FontMetrics fm = g.getFontMetrics();
        int x = (W - fm.stringWidth(s)) / 2;
        g.setColor(c);
        g.drawString(s, x, y);
    }

    // ══════════════════════════════════════════════════════════════════════
    //  POWER-UP IMAGES
    // ══════════════════════════════════════════════════════════════════════
    // All six icons are embedded directly as Base64 PNGs (re-encoded from the
    // supplied artwork, cropped to their opaque bounds and scaled down to a
    // compact icon size) instead of being loaded from disk, so the game
    // always has its art no matter where or on what OS it runs.
    void loadPowerUpImages() {
        magnetImg     = decodeImage(MAGNET_PNG_BASE64,     "magnet");
        pepperImg     = decodeImage(PEPPER_PNG_BASE64,     "ghost pepper");
        snowflakeImg  = decodeImage(SNOWFLAKE_PNG_BASE64,  "snowflake");
        cherryImg     = decodeImage(CHERRY_PNG_BASE64,     "cherry");
        strawberryImg = decodeImage(STRAWBERRY_PNG_BASE64, "strawberry");
        bananaImg     = decodeImage(BANANA_PNG_BASE64,     "banana");
    }

    BufferedImage decodeImage(String base64, String label) {
        try {
            byte[] bytes = Base64.getDecoder().decode(base64);
            return ImageIO.read(new ByteArrayInputStream(bytes));
        } catch (Exception e) {
            System.out.println("[PacMan] Failed to decode embedded " + label
                    + " image — using a drawn placeholder instead.");
            return null;
        }
    }

    static final String SNOWFLAKE_PNG_BASE64 = "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";

    static final String MAGNET_PNG_BASE64 = "iVBORw0KGgoAAAANSUhEUgAAACEAAABACAYAAACUTB6QAAAF5klEQVR4nO2Yy49cRxXGf6fqdvc8E3tsMI84KIl5LDAorGAXISEWeIOCxJ6wQCgSUvgH2LBjkQ072CAhBYlZIBYEWIEmcmIJx8aWLDwPx5r3o2emH3Nv33ur6mRx753pHrpnhg3xoj+ppe6+Ved856uq71S3AMonDPNJE4AxiROMSVQYk6gwJlFhTKLCmESFMYkKYxIVov4P1lpE5L8GqSqqSghhaBARwVo79Fk1T3X0BU54Bq53ERSVqCo/eP11vnrzJiF4gg8oirWW1dU1Hj16xJ07dwbUqOZdvnyZH7/xBtMzM4Tgcbk7fra4uMiH9+6xtLR0/N0wqLVWAZ2fn9cKaZpqt9NR53J9//Zt/flbb2ktihRQKdRTY4wCeuPGDU3iuJgYgiZxrEkca7vd1vk/zuutW7cGxp9+RcAxu+XlZd5bWGBlZYVms4l3njTP2N3ZYXV1lTCiirTX4+7duzT39tjZ3SWOE0IItNotDvYP2NraOn85KlgEo4rPMvAevEezDAsQ9JjsaSpFSYJXwQfF+WLJfIDQN+9CJML0FN16ndVOh9rkJEGVNLL0ej1co1Zs4yEwApemDGuHm6StXWampvAhkNmcOHJYcxESJdPZ27e53twj291GjnoE5wh5zlonJtvaw6gSODlSVeg4E/58T9lJXqYTPo3sOpRAklyls7HMfrt+PgkpScz9/h2+cu0qX/rCZ8jvP8Z7pVGPeJjWOHAgpxaiknn/KPD2X4Xnv/FNsl6PZHGN2lQDDY7scUJ3Z6K/1hFKUJTna3X0+otk3/s28tpr+H98QOvBI7oTE7gkgB+cXCliBaYb8OVrjktXZmhdf4H/3N/Dpx5qjui85ThZZkGcQxp17Aufw3SO8JN1Mu/AgpxjaS7A7KThylyDLHGosQQE1dP6DSHRP0BREBAf6L39G3zzEJmYQNMRBtP/XpVGI2Jt5YD3//aEien6hb24r4EpiAHn0W4X9R7KjXgRiEA3U+KcM51xGE72hFKctSRBNzbQEBAjqBYeMKzdHjceEQRopUqWgzH9QauRozEYWwwkMfrkKeQOLTtq4dVDumsZvOiyULNCZE/GabiYGgNmJUaglxO2m2gIIKUSZZLTGFDCCEmmpJkvjrz2qXC2EIMkjAjkOWH/ADQcG1JAcYA7NVlLudXnEAIuKE6FKmtxNzn5PAoDy5GKkDuP7yZFIVKkEgPTBi5hiCjWX0QQMYitE03NETUsEgISQpm3StzvrWcoUQ35II14SeFrPiaEIllDhFcbnpdqwqvpNX7a22UZV8itnokXv8UXf/QOL399jo/+tUF7q42p2eK5gJhodPZ+EhVCgEzBlzJUBRlgWuCaMbxSq6E+cDR7hcPJWaY/e4PazBxx4shTR3CKrRvUO0LSxad7+Lx9JonjvWWAT2H5blTjl5NTTEaWOlV/EDyQBqV2eZbn0xa/++Ev+MP338RsxWyuJnQer51EtRbiFsmH/6T55Ldk3SdlouHLcqyEANt4loKw7+HzkWIAX043QN2AcZ4oy2mvt1m7v89Mq0PSyqA8QWIFdTkhTRGpFfJWRjjCwAZsRYCOKgtZxrbz9LQ4GdUGtwghSdE043CjxfqDPXYXt8j3O2UliiDoUZt0/THZ0VNU8zL+6M15TKKy5wfq+Znr8vc4ZSXzJKo4EYIKxdW38A4xQlQ32JoFY1BVRMDHbfzmU9z6Q/aWf02erJdVjiYxsDH7h/0q9PhJrnzH17GR4eZEhA+Cl8JPrLWoqSESFX4gglv8NxqndPfeo7P5F0QMqud3n6HnJwBrGnjX52z7wFQwJFrcjpwxPOccm80twspD0uyQIBZCTvzRu/gspdd5iOvtIHKxH3gjSQiwEBwLOK4G4blQuKYXw1TwLG2v4HsL9PwRobTm1tqf8C4uo8iFVIBzfoGZcoABJuTkciKqZKZOZqIBQw4+LcLp+S55YRL/L5zvqSVOtyA949v/Fc+EEs/E/xNjEhXGJCqMSVQYk6gwJlFhTKLCmESFjwFaKFztAeMB5gAAAABJRU5ErkJggg==";

    static final String PEPPER_PNG_BASE64 = "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";

    static final String CHERRY_PNG_BASE64 = "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";

    static final String STRAWBERRY_PNG_BASE64 = "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";

    static final String BANANA_PNG_BASE64 = "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";

    // ══════════════════════════════════════════════════════════════════════
    //  HIGH SCORE
    // ══════════════════════════════════════════════════════════════════════
    void loadHighScore() {
        if (!HIGH_SCORE_FILE.exists()) { highScore = 0; return; }
        try (BufferedReader br = new BufferedReader(new FileReader(HIGH_SCORE_FILE))) {
            String line = br.readLine();
            highScore = (line != null) ? Integer.parseInt(line.trim()) : 0;
        } catch (Exception ignored) {
            highScore = 0;
        }
    }

    void saveHighScore() {
        try (BufferedWriter bw = new BufferedWriter(new FileWriter(HIGH_SCORE_FILE))) {
            bw.write(String.valueOf(highScore));
        } catch (Exception ignored) {
            // Non-fatal: the game simply won't persist the high score this run.
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  INPUT
    // ══════════════════════════════════════════════════════════════════════
    @Override public void keyTyped(KeyEvent e)    {}
    @Override public void keyReleased(KeyEvent e) {}

    @Override
    public void keyPressed(KeyEvent e) {
        int k = e.getKeyCode();

        if (k == KeyEvent.VK_R) {
            state = State.TITLE;
            player1GhostIndex = -1;
            player2GhostIndex = -1;
            player1Score = 0;
            player2Score = 0;
            return;
        }

        switch (state) {
            case TITLE -> {
                if (k == KeyEvent.VK_ENTER) state = State.SELECT_MODE;
            }
            case SELECT_MODE -> {
                if (k == KeyEvent.VK_1) {
                    gameMode = GameMode.NORMAL;
                    player1GhostIndex = -1;
                    player2GhostIndex = -1;
                    startNewGame();
                    state = State.READY;
                    stateTimer = FPS * 2;
                } else if (k == KeyEvent.VK_2) {
                    gameMode = GameMode.GHOST;
                    state = State.SELECT_PLAYERS;
                }
            }
            case SELECT_PLAYERS -> {
                if (k == KeyEvent.VK_1) {
                    numPlayers = 1;
                    selectedGhostIndex = 0;
                    state = State.SELECT_GHOST;
                } else if (k == KeyEvent.VK_2) {
                    numPlayers = 2;
                    player1GhostIndex = 0; // red, arrows
                    player2GhostIndex = 3; // orange, WASD
                    startNewGame();
                    state = State.READY;
                    stateTimer = FPS * 2;
                }
            }
            case SELECT_GHOST -> {
                if (k == KeyEvent.VK_LEFT || k == KeyEvent.VK_RIGHT || k == KeyEvent.VK_A || k == KeyEvent.VK_D) {
                    selectedGhostIndex = (selectedGhostIndex == 0) ? 3 : 0;
                } else if (k == KeyEvent.VK_ENTER) {
                    player1GhostIndex = selectedGhostIndex;
                    player2GhostIndex = -1;
                    startNewGame();
                    state = State.READY;
                    stateTimer = FPS * 2;
                }
            }
            case GAME_OVER -> {
                if (k == KeyEvent.VK_ENTER) {
                    state = State.TITLE;
                    player1GhostIndex = -1;
                    player2GhostIndex = -1;
                }
            }
            case PLAYING -> {
                if (k == KeyEvent.VK_P) {
                    paused = !paused;
                    return;
                }
                if (paused) return;

                Direction arrowDir = switch (k) {
                    case KeyEvent.VK_UP    -> Direction.UP;
                    case KeyEvent.VK_DOWN  -> Direction.DOWN;
                    case KeyEvent.VK_LEFT  -> Direction.LEFT;
                    case KeyEvent.VK_RIGHT -> Direction.RIGHT;
                    default -> null;
                };

                if (gameMode == GameMode.NORMAL) {
                    // Normal mode: arrow keys drive Pac-Man directly.
                    // While reverseControlsTimer is active (banana power-up),
                    // every arrow key is flipped to its opposite direction.
                    if (arrowDir != null) {
                        pNext = (reverseControlsTimer > 0) ? arrowDir.opposite() : arrowDir;
                    }
                } else {
                    // Ghost mode: arrow keys drive Player 1's ghost, WASD drives Player 2's.
                    if (arrowDir != null) p1QueuedDir = arrowDir;

                    Direction wasdDir = switch (k) {
                        case KeyEvent.VK_W -> Direction.UP;
                        case KeyEvent.VK_S -> Direction.DOWN;
                        case KeyEvent.VK_A -> Direction.LEFT;
                        case KeyEvent.VK_D -> Direction.RIGHT;
                        default -> null;
                    };
                    if (wasdDir != null) p2QueuedDir = wasdDir;
                }
            }
            default -> { /* no input handling needed for other states */ }
        }
    }
}
