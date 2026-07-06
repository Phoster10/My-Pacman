import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
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
//  GHOST MODE
// ─────────────────────────────────────────────────────────────────────────────
enum GhostMode { SCATTER, CHASE, FRIGHTENED, EATEN }

// ─────────────────────────────────────────────────────────────────────────────
//  TILE TYPE
// ─────────────────────────────────────────────────────────────────────────────
enum Tile { WALL, EMPTY, DOT, ENERGIZER, GHOST_HOUSE }

// ─────────────────────────────────────────────────────────────────────────────
//  POWER-UP TYPE  (cherry / strawberry / banana — Pac-Man only, ghosts never
//  interact with these at all: there is no ghost-side pickup check anywhere
//  in the code, so a ghost walking over one has zero effect.)
// ─────────────────────────────────────────────────────────────────────────────
enum PowerUpType { CHERRY, STRAWBERRY, BANANA }

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
    static final int TILE  = 16;
    static final int COLS  = 28;
    static final int ROWS  = 31;
    static final int W     = COLS * TILE;
    static final int H     = ROWS * TILE + 40;
    static final int FPS   = 60;
    static final int SPEED = 2;
    static final int FRIGHT_DURATION = FPS * 8;
    static final int FRIGHT_FLASH_AT = FPS * 3;
    static final int[] PHASE_DURATIONS = {
        FPS * 7, FPS * 20, FPS * 7, FPS * 20,
        FPS * 5, FPS * 20, FPS * 5, Integer.MAX_VALUE
    };
    static final int DOT_PTS       = 10;
    static final int ENERGIZER_PTS = 50;
    static final int GHOST_BASE    = 200;

    /** Where the high-score file lives. Using the user's home directory keeps
     *  this working regardless of the current working directory the JVM was
     *  launched from, and avoids permission failures on read-only install
     *  locations. */
    static final File HIGH_SCORE_FILE =
            new File(System.getProperty("user.home", "."), ".pacman_highscore.dat");

    // ── Maze (28 × 31) ────────────────────────────────────────────────────
    // # wall  . dot  o energizer  G ghost-house  p Pac-Man start
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
    // The maze has two sealed-off alcove pockets beside the ghost house
    // (rows 10-12 / 16-18, columns 0-4 and 23-27) that are Tile.EMPTY but
    // walled off on every side with no connecting corridor — flood-filled
    // once per level in computeReachable() so power-ups never spawn there.
    boolean[][] reachable = new boolean[ROWS][COLS];

    // ── Game state ────────────────────────────────────────────────────────
    enum State { TITLE, READY, PLAYING, DEAD, LEVEL_CLEAR, GAME_OVER }
    State state = State.TITLE;
    int stateTimer = 0;
    boolean paused = false;

    int score, highScore, lives, level, dotsEaten;
    boolean newHighScore;

    int phaseIndex, phaseTimer;
    int frightTimer;
    int ghostEatCombo;

    // ── Pac-Man ───────────────────────────────────────────────────────────
    float px, py;
    Direction pDir, pNext;
    float pSpeed;
    int mouthAngle;
    int mouthDir;
    int pStartCol, pStartRow;

    // ── Ghost colours / scatter corners ───────────────────────────────────
    static final Color[] GHOST_COLORS = {
        new Color(255,   0,   0),
        new Color(255, 184, 255),
        new Color(  0, 255, 255),
        new Color(255, 184,  82),
    };
    static final int[][] SCATTER_TARGETS = {
        {25, 0}, {2, 0}, {27, 30}, {0, 30}
    };
    static final int HOUSE_EXIT_COL = 14;
    static final int HOUSE_EXIT_ROW = 11;

    transient Ghost[] ghosts = new Ghost[4];

    int dotFlashTimer;
    int titleBlink;

    // ── Power-ups (Pac-Man only) ──────────────────────────────────────────
    static final int POWERUP_LIFETIME   = FPS * 10;        // on-map duration once spawned
    static final int POWERUP_MIN_GAP    = FPS * 8;         // fastest possible re-spawn
    static final int POWERUP_MAX_GAP    = FPS * 15;        // slowest possible re-spawn
    static final int CHERRY_BOOST_TIME  = FPS * 7;         // 7s of 3x speed
    static final int BANANA_REVERSE_TIME = FPS * 8;        // 8s of reversed controls
    static final float CHERRY_SPEED_MULT = 3f;

    BufferedImage cherryImg, strawberryImg, bananaImg;

    PowerUp activePowerup;      // null when nothing is currently on the map
    int powerupSpawnTimer;      // frames until the next spawn attempt
    int speedBoostTimer;        // frames remaining of cherry's 3x speed
    int reverseControlsTimer;   // frames remaining of banana's reversed controls

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

    // Cached, pre-rendered maze walls. The maze geometry doesn't change
    // mid-level, so we paint the (relatively expensive) wall tiles to an
    // off-screen buffer once and simply blit it every frame, redrawing
    // only when the underlying grid changes (buildGrid()).
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

        int  frightTimer;
        boolean frightFlash;
        // Toggled every frame while FRIGHTENED so the ghost only actually
        // advances on every other frame. This gives frightened ghosts an
        // effective half-speed WITHOUT ever moving by a fractional/odd
        // pixel amount. Moving by half-pixel-equivalent steps was the root
        // cause of ghosts desyncing from the 16px tile grid and never being
        // "centered" again -- which meant they stopped re-checking walls
        // entirely and would drift straight through them after fright wore
        // off. Always stepping by the full integer SPEED (just skipping
        // every other frame) keeps the ghost's position an exact multiple
        // of SPEED at all times, so it always lands precisely back on a
        // tile boundary.
        boolean frightStep;
        int  dotThreshold;
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
            reset();
        }

        void reset() {
            x = startX;
            y = startY;
            dir = Direction.LEFT; nextDir = Direction.LEFT;
            mode = GhostMode.SCATTER;
            frightTimer = 0; frightFlash = false; frightStep = false;
            returningToHouse = false; leavingHouse = false;
            inHouse = (dotThreshold > 0);
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
    /** Converts a pixel x-coordinate to a wrapped column index. Uses floor
     *  (not truncation) so this behaves correctly for negative coordinates,
     *  which occur briefly while an entity is crossing the tunnel wrap
     *  boundary. NOTE: this wrapping is intentionally used for Pac-Man (and
     *  for cosmetic helpers like wall-corner rendering); ghost movement
     *  legality is checked separately in validGhostDirs()/canMoveGhost(),
     *  which do NOT wrap, so ghosts can never step into the tunnel. */
    int pixelToCol(float x) {
        int c = (int) Math.floor((x + TILE / 2.0) / TILE);
        return Math.floorMod(c, COLS);
    }

    /** Converts a pixel y-coordinate to a row index (no wrapping vertically). */
    int pixelToRow(float y) {
        return (int) Math.floor((y + TILE / 2.0) / TILE);
    }

    /** True if the given pixel coordinate sits exactly on a tile boundary.
     *  Uses Math.round + floorMod so it is correct for negative coordinates
     *  too (unlike a naive (int) cast + % check). */
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

    /** Flood-fills from Pac-Man's start tile so spawnPowerUp() can restrict
     *  itself to tiles a player can actually walk to (excludes the sealed
     *  alcove pockets beside the ghost house, and would also exclude any
     *  other accidentally-disconnected pocket in the map). */
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
                int nc = Math.floorMod(c + d[0], COLS); // allow the tunnel wrap too
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
    //  RESET
    // ══════════════════════════════════════════════════════════════════════
    void resetRound(boolean fullReset) {
        if (fullReset) {
            score = 0;
            lives = 3; level = 1; dotsEaten = 0;
            newHighScore = false;
            paused = false;
            buildGrid();
        }
        px = pStartCol * TILE;
        py = pStartRow * TILE;
        pDir = Direction.NONE; pNext = Direction.NONE;
        pSpeed = SPEED;
        mouthAngle = 45; mouthDir = -1;

        phaseIndex = 0;
        phaseTimer = PHASE_DURATIONS[0];
        frightTimer = 0; ghostEatCombo = 0;

        activePowerup = null;
        speedBoostTimer = 0;
        reverseControlsTimer = 0;
        powerupSpawnTimer = POWERUP_MIN_GAP + rng.nextInt(POWERUP_MAX_GAP - POWERUP_MIN_GAP);

        for (Ghost g : ghosts) g.reset();
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
            case TITLE -> { /* wait for input */ }

            case READY -> {
                stateTimer--;
                if (stateTimer <= 0) state = State.PLAYING;
            }

            case PLAYING -> {
                if (paused) return;
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
                        stateTimer = FPS * 4;
                    } else {
                        resetRound(false);
                        // The dots already eaten this level must keep unlocking
                        // ghosts immediately; otherwise Inky/Clyde would sit
                        // back in the house until an extra dot is eaten.
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

            case GAME_OVER -> {
                stateTimer--;
                if (stateTimer <= 0) state = State.TITLE;
            }
        }
    }

    // ── Phase cycling ─────────────────────────────────────────────────────
    void updatePhase() {
        if (frightTimer > 0) {
            frightTimer--;
            return;
        }

        phaseTimer--;
        if (phaseTimer <= 0) {
            phaseIndex = Math.min(phaseIndex + 1, PHASE_DURATIONS.length - 1);
            phaseTimer = PHASE_DURATIONS[phaseIndex];
            for (Ghost g : ghosts) {
                if (g.mode != GhostMode.FRIGHTENED && g.mode != GhostMode.EATEN
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

    // ── Pac-Man ───────────────────────────────────────────────────────────
    void updatePacMan() {
        // Cherry effect: instead of taking one bigger pixel step (which broke
        // tile alignment — 3x2=6px doesn't evenly divide the 16px tile, so
        // Pac-Man could never land exactly on a boundary again, and the
        // wall/turn checks below depend entirely on isCentered() being able
        // to become true). Instead we just run the *same* normal 2px step
        // multiple times in one frame. Every individual step is still exactly
        // SPEED pixels and still stops dead at a wall, so movement, turning,
        // and collision all keep working identically — Pac-Man just covers
        // three steps' worth of ground per frame instead of one.
        int steps = (speedBoostTimer > 0) ? Math.round(CHERRY_SPEED_MULT) : 1;
        for (int i = 0; i < steps; i++) {
            stepPacMan();
        }

        // Mouth animation runs once per frame regardless of how many
        // movement steps happened, so the chomp rate doesn't visually
        // triple along with the movement speed.
        if (pDir != Direction.NONE) {
            mouthAngle += mouthDir * 4;
            if (mouthAngle <= 0)  { mouthAngle = 0;  mouthDir =  1; }
            if (mouthAngle >= 45) { mouthAngle = 45; mouthDir = -1; }
        }
    }

    /** Moves Pac-Man by exactly one normal SPEED-sized step: takes a queued
     *  turn if centered and legal, advances if the way is clear, wraps the
     *  tunnel, and eats whatever is on the tile landed on. Called once per
     *  frame normally, or several times per frame during the cherry boost. */
    void stepPacMan() {
        // Try queued turn when tile-aligned
        if (pNext != Direction.NONE && isCentered(px, py)) {
            if (canMove(px, py, pNext)) {
                pDir  = pNext;
                pNext = Direction.NONE;
            }
        }

        if (pDir != Direction.NONE) {
            if (!isCentered(px, py) || canMove(px, py, pDir)) {
                px += pDir.dx * SPEED;
                py += pDir.dy * SPEED;
                // Tunnel wrap (Pac-Man only — ghosts are blocked from this,
                // see validGhostDirs()/canMoveGhost()/moveGhost() below).
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

        // Power-up pickup — Pac-Man only. There is no equivalent check inside
        // updateGhosts()/moveGhost(), so a ghost passing over a power-up tile
        // simply does nothing.
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
        return grid[row][col] != Tile.WALL;
    }

    // ── Fright ────────────────────────────────────────────────────────────
    void activateFright() {
        frightTimer = FRIGHT_DURATION;
        ghostEatCombo = 0;
        for (Ghost g : ghosts) {
            if (g.mode != GhostMode.EATEN && !g.inHouse) {
                g.mode = GhostMode.FRIGHTENED;
                g.frightTimer = FRIGHT_DURATION;
                g.frightFlash = false;
                g.frightStep = false;
                g.dir = g.dir.opposite();
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

    // ── Power-ups (Pac-Man only — ghosts have no pickup logic at all) ──────
    void updatePowerUps() {
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

    void spawnPowerUp() {
        ArrayList<int[]> spots = new ArrayList<>();
        for (int r = 0; r < ROWS; r++)
            for (int c = 0; c < COLS; c++)
                if (reachable[r][c] && grid[r][c] != Tile.WALL && grid[r][c] != Tile.GHOST_HOUSE)
                    spots.add(new int[]{ c, r });
        if (spots.isEmpty()) return;

        int[] pick = spots.get(rng.nextInt(spots.size()));
        PowerUpType[] types = PowerUpType.values();
        activePowerup = new PowerUp(types[rng.nextInt(types.length)], pick[0], pick[1]);
    }

    /** Applies the effect for whichever power-up Pac-Man just ate. Ghosts
     *  never call this — they simply have no code path that checks
     *  activePowerup at all, so they can walk right over one with zero
     *  effect. */
    void applyPowerUp(PowerUpType type) {
        switch (type) {
            case CHERRY -> speedBoostTimer = CHERRY_BOOST_TIME;
            case STRAWBERRY -> lives++;
            case BANANA -> reverseControlsTimer = BANANA_REVERSE_TIME;
        }
    }

    // ── Ghost AI ──────────────────────────────────────────────────────────
    void updateGhosts() {
        for (Ghost g : ghosts) {

            // Frightened countdown
            if (g.mode == GhostMode.FRIGHTENED) {
                g.frightTimer--;
                g.frightFlash = (g.frightTimer < FRIGHT_FLASH_AT)
                              && ((g.frightTimer / (FPS / 4)) % 2 == 0);
                if (g.frightTimer <= 0) {
                    g.mode = (phaseIndex % 2 == 0) ? GhostMode.SCATTER : GhostMode.CHASE;
                    // Force an immediate direction re-evaluation the instant
                    // fright ends, rather than waiting for the ghost to next
                    // report as "centered". Since movement now always keeps
                    // the ghost exactly tile-aligned (see moveGhost), this is
                    // mostly a safety net, but it also makes ghosts snap back
                    // to normal AI the moment the timer hits zero instead of
                    // finishing out whatever direction they were fleeing in.
                    if (g.isCentered() && !g.inHouse && !g.leavingHouse && !g.returningToHouse) {
                        computeTarget(g);
                        g.nextDir = chooseBestDirection(g, g.targetCol, g.targetRow);
                    }
                }
            }

            if (g.inHouse) {
                // Bounce inside house
                g.y += (g.dir == Direction.UP ? -1 : 1);
                if (g.y <= 13 * TILE) g.dir = Direction.DOWN;
                if (g.y >= 15 * TILE) g.dir = Direction.UP;
                continue;
            }

            if (g.leavingHouse) { moveGhostTowardExit(g); continue; }
            if (g.returningToHouse) { moveGhostToHouse(g); continue; }

            computeTarget(g);
            if (g.isCentered())
                g.nextDir = chooseBestDirection(g, g.targetCol, g.targetRow);
            moveGhost(g);
        }
    }

    void computeTarget(Ghost g) {
        if (g.mode == GhostMode.FRIGHTENED) return;
        int[] sc = g.scatterTarget;
        if (g.mode == GhostMode.SCATTER) {
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
                if (pDir == Direction.UP) tc -= 4; // faithful arcade overflow quirk
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
        // Frightened → random valid direction
        if (g.mode == GhostMode.FRIGHTENED) {
            ArrayList<Direction> valid = validGhostDirs(g, false);
            return valid.isEmpty() ? g.dir : valid.get(rng.nextInt(valid.size()));
        }

        ArrayList<Direction> valid = validGhostDirs(g, false);
        if (valid.isEmpty()) {
            // Every neighbouring tile (including behind) is blocked, which
            // should only happen transiently right as a ghost re-centers on
            // a dead end. Rather than blindly reversing into what may still
            // be a wall, fall back to whatever direction actually is open,
            // and only if truly none exists keep the current heading (the
            // guard in moveGhost will refuse to step into a wall regardless).
            return g.dir;
        }

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

    /** Returns all directions the ghost may move (no U-turns unless forced).
     *
     *  IMPORTANT (tunnel/portal fix): unlike isWall()/pixelToCol(), this does
     *  NOT wrap the column with floorMod. Stepping off the left edge (col -1)
     *  or right edge (col COLS) is treated exactly like walking into a wall,
     *  so a ghost standing next to the tunnel opening on row 14 simply has no
     *  valid direction that way and will never enter the portal. Only
     *  Pac-Man's own movement code (updatePacMan(), which uses the wrapping
     *  pixelToCol()) is allowed to cross that boundary. */
    ArrayList<Direction> validGhostDirs(Ghost g, boolean allowReverse) {
        ArrayList<Direction> list = new ArrayList<>();
        for (Direction d : new Direction[]{ Direction.UP, Direction.LEFT, Direction.DOWN, Direction.RIGHT }) {
            if (!allowReverse && d == g.dir.opposite()) continue;
            int nc = g.col() + d.dx;
            int nr = g.row() + d.dy;
            if (nc < 0 || nc >= COLS) continue; // block tunnel/portal for ghosts
            if (nr < 0 || nr >= ROWS) continue;
            Tile t = grid[nr][nc];
            if (t == Tile.WALL || t == Tile.GHOST_HOUSE) continue;
            list.add(d);
        }
        if (list.isEmpty() && !allowReverse) return validGhostDirs(g, true);
        return list;
    }

    /**
     * True if the ghost may advance one more step in direction d from its
     * current position. When the ghost is mid-tile (not centered), it is
     * already partway through a crossing that was validated as open when it
     * last left a tile centre, so it's safe to continue. When centered, this
     * checks the tile it is about to enter directly against the grid.
     *
     * This exists as a defensive guard so that -- regardless of how g.dir
     * was chosen -- a ghost can physically never advance into a wall tile,
     * and (tunnel/portal fix) can never step off the left/right edge of the
     * map into the wraparound tunnel. Column bounds are checked WITHOUT
     * floorMod wrapping for exactly that reason.
     */
    boolean canMoveGhost(Ghost g, Direction d) {
        if (!g.isCentered()) return true;
        int nc = g.col() + d.dx;
        int nr = g.row() + d.dy;
        if (nc < 0 || nc >= COLS) return false; // block tunnel/portal for ghosts
        if (nr < 0 || nr >= ROWS) return false;
        Tile t = grid[nr][nc];
        if (t == Tile.WALL) return false;
        if (t == Tile.GHOST_HOUSE && !(g.inHouse || g.leavingHouse || g.returningToHouse)) return false;
        return true;
    }

    void moveGhost(Ghost g) {
        boolean frightened = g.mode == GhostMode.FRIGHTENED;

        // Frightened ghosts run at half speed. Instead of moving by a
        // fractional pixel amount (SPEED * 0.5f), which can leave a ghost
        // sitting on an odd pixel offset the instant frightened mode ends
        // and permanently desync it from the 16px tile grid, we always step
        // by the full integer SPEED and simply skip every other frame. The
        // ghost's position therefore always stays an exact multiple of
        // SPEED, so it lands precisely on a tile boundary every time and
        // "isCentered()" keeps working -- meaning direction/wall checks keep
        // firing normally instead of the ghost drifting forever in a stale
        // heading.
        if (frightened) {
            g.frightStep = !g.frightStep;
            if (!g.frightStep) return;
        }

        if (g.isCentered()) {
            g.dir = g.nextDir;
        }

        // Defensive guard: never physically step into a wall, no matter how
        // g.dir ended up set. If the chosen direction is somehow blocked,
        // look for any open direction instead; if truly none exists, just
        // wait a frame rather than clipping through geometry. Since
        // canMoveGhost() also rejects the tunnel edges, this guard doubles
        // as the last line of defense against ghosts entering the portal.
        if (!canMoveGhost(g, g.dir)) {
            Direction fallback = null;
            for (Direction d : validGhostDirs(g, true)) {
                if (canMoveGhost(g, d)) { fallback = d; break; }
            }
            if (fallback == null) return; // boxed in for this frame; hold position
            g.dir = fallback;
            g.nextDir = fallback;
        }

        float speed = SPEED;
        g.x += g.dir.dx * speed;
        g.y += g.dir.dy * speed;
        // NOTE: deliberately no tunnel wrap here (tunnel/portal fix). Ghosts
        // are never allowed to reach x < 0 or x >= W in the first place,
        // since validGhostDirs()/canMoveGhost() refuse any step that would
        // cross the left/right map edge. Pac-Man's equivalent wrap lives in
        // updatePacMan() only.
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
                    g.mode = GhostMode.EATEN;
                    g.returningToHouse = true;
                } else if (g.mode != GhostMode.EATEN) {
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

        drawHUD(g);
        g.translate(0, 40);
        switch (state) {
            case TITLE     -> drawTitle(g);
            case GAME_OVER -> { drawMaze(g); drawGameOver(g); }
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

    void drawHUD(Graphics2D g) {
        g.setColor(Color.BLACK);
        g.fillRect(0, 0, W, 40);

        // Lives icons
        for (int i = 0; i < Math.max(lives - 1, 0); i++) {
            g.setColor(Color.YELLOW);
            g.fillArc(6 + i * 20, 10, 14, 14, 35, 290);
        }

        g.setFont(new Font("Arial", Font.BOLD, 14));
        g.setColor(Color.WHITE);
        g.drawString("SCORE  " + score, 80, 24);
        g.setColor(newHighScore ? Color.YELLOW : Color.CYAN);
        g.drawString("BEST  " + highScore, W - 160, 24);
        g.setColor(Color.WHITE);
        g.drawString("LVL " + level, W / 2 - 20, 24);

        // Active power-up effect indicators
        g.setFont(new Font("Arial", Font.BOLD, 10));
        if (speedBoostTimer > 0) {
            g.setColor(Color.ORANGE);
            g.drawString("SPEED x3 " + (speedBoostTimer / FPS + 1) + "s", 80, 36);
        }
        if (reverseControlsTimer > 0) {
            g.setColor(new Color(140, 220, 90));
            g.drawString("CONTROLS REVERSED " + (reverseControlsTimer / FPS + 1) + "s", W / 2 - 60, 36);
        }
    }

    void drawTitle(Graphics2D g) {
        drawMaze(g);
        g.setColor(new Color(0, 0, 0, 160));
        g.fillRect(0, 0, W, ROWS * TILE);

        g.setFont(new Font("Arial", Font.BOLD, 36));
        drawCenteredString(g, "PAC-MAN", ROWS * TILE / 2 - 60, Color.YELLOW);
        if ((titleBlink / (FPS / 2)) % 2 == 0) {
            g.setFont(new Font("Arial", Font.PLAIN, 14));
            drawCenteredString(g, "PRESS  ENTER  TO  START", ROWS * TILE / 2, Color.WHITE);
        }

        String[] labels = { "BLINKY  \u2013 SHADOW", "PINKY  \u2013 SPEEDY", "INKY  \u2013 BASHFUL", "CLYDE  \u2013 POKEY" };
        for (int i = 0; i < 4; i++) {
            int y = ROWS * TILE / 2 + 40 + i * 22;
            drawGhostShape(g, W / 2 - 110, y - 12, 14, 14, GHOST_COLORS[i], false, false);
            g.setFont(new Font("Arial", Font.PLAIN, 12));
            g.setColor(Color.WHITE);
            g.drawString(labels[i], W / 2 - 88, y);
        }

        g.setFont(new Font("Arial", Font.PLAIN, 11));
        drawCenteredString(g, "ARROWS / WASD MOVE   \u00b7   P PAUSE   \u00b7   R RESET", ROWS * TILE - 20, new Color(180, 180, 180));
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
        g.setColor(new Color(0, 0, 0, 140));
        g.fillRect(0, 0, W, ROWS * TILE);
        g.setFont(new Font("Arial", Font.BOLD, 28));
        drawCenteredString(g, "GAME  OVER", ROWS * TILE / 2 - 20, Color.RED);
        if (newHighScore) {
            g.setFont(new Font("Arial", Font.BOLD, 16));
            drawCenteredString(g, "NEW HIGH SCORE!", ROWS * TILE / 2 + 10, Color.YELLOW);
        }
        g.setFont(new Font("Arial", Font.PLAIN, 13));
        drawCenteredString(g, "Press ENTER to play again", ROWS * TILE / 2 + 36, Color.WHITE);
    }

    void drawLevelClear(Graphics2D g) {
        if ((dotFlashTimer / 2) % 2 == 0) {
            g.setColor(new Color(0, 0, 180, 60));
            g.fillRect(0, 0, W, ROWS * TILE);
        }
        g.setFont(new Font("Arial", Font.BOLD, 20));
        drawCenteredString(g, "LEVEL  CLEAR!", ROWS * TILE / 2, Color.CYAN);
    }

    void drawMaze(Graphics2D g) {
        g.setColor(Color.BLACK);
        g.fillRect(0, 0, W, ROWS * TILE);

        // Dots / energizers are dynamic (eaten over time), so they're drawn
        // fresh every frame.
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

        // Walls are static per level; reuse the cached buffer for performance.
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

    /** Draws the current power-up (if any) on the map. Uses the user's
     *  banana/cherry/strawberry PNGs when available; falls back to a simple
     *  drawn circle+letter if the image failed to load, so the game still
     *  works without the art files. Blinks during its last 3 seconds so the
     *  player can see it's about to disappear. */
    void drawPowerUp(Graphics2D g) {
        if (activePowerup == null) return;

        boolean flashingOff = activePowerup.lifeTimer < FPS * 3
                && (activePowerup.lifeTimer / (FPS / 4)) % 2 == 0;
        if (flashingOff) return;

        int x = activePowerup.col * TILE;
        int y = activePowerup.row * TILE;
        // Sized to fit inside a single corridor tile (corridors are exactly
        // TILE wide) rather than the earlier 1.6x size, which spilled over
        // into neighboring wall tiles.
        int size = (int) (TILE * 0.9);
        int ox = x + TILE / 2 - size / 2;
        int oy = y + TILE / 2 - size / 2;

        BufferedImage img = switch (activePowerup.type) {
            case CHERRY     -> cherryImg;
            case STRAWBERRY -> strawberryImg;
            case BANANA     -> bananaImg;
        };

        if (img != null) {
            g.drawImage(img, ox, oy, size, size, null);
        } else {
            Color c = switch (activePowerup.type) {
                case CHERRY     -> Color.RED;
                case STRAWBERRY -> new Color(220, 20, 60);
                case BANANA     -> Color.YELLOW;
            };
            String letter = switch (activePowerup.type) {
                case CHERRY     -> "C";
                case STRAWBERRY -> "S";
                case BANANA     -> "B";
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
            // Scared face
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
    // The three sprites are embedded directly as Base64 below instead of
    // being loaded from a file path. Loading from disk (e.g. "/bin/",
    // user.home, etc.) turned out to be unreliable across machines/OSes —
    // "/bin/" in particular doesn't exist at all on Windows, which silently
    // failed and fell back to the drawn placeholder circle. Embedding the
    // actual PNG bytes in the source removes that dependency entirely: the
    // art is always present no matter where or on what OS the game runs.
    void loadPowerUpImages() {
        cherryImg     = decodeImage(CHERRY_PNG_BASE64,     "cherry");
        strawberryImg = decodeImage(String.join("", STRAWBERRY_PNG_BASE64_PARTS), "strawberry");
        bananaImg     = decodeImage(String.join("", BANANA_PNG_BASE64_PARTS), "banana");
    }

    /** Decodes an embedded Base64 PNG. Returns null (never throws) if
     *  decoding somehow fails, in which case drawPowerUp() falls back to a
     *  simple drawn icon so the game still runs. */
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

    static final String CHERRY_PNG_BASE64 = "iVBORw0KGgoAAAANSUhEUgAAABAAAAAQCAYAAAAf8/9hAAAAAXNSR0IArs4c6QAAAARnQU1BAACxjwv8YQUAAAAJcEhZcwAADsMAAA7DAcdvqGQAAACkSURBVDhPYxh64N509/8gDOUyMEFpguD+FM//IAzlEg+QNZJsALoGggbck1f7D8PEamaE0mDNUCYDQ4UiA0PHfTBT6eEtRpBmxZztcLXIAHsgIjTDDYa5DCyBBFAMUHxwE8qCa4byEADdEBQD7iuooxgCA/+770JZmABuAMivIBpkCDLAZiAywBowMABzLsgQZINhloEAXgNAAN3PyJoHA2BgAADloFRo1L1ytAAAAABJRU5ErkJggg==";
    // Split into chunks for the same reason as BANANA_PNG_BASE64_PARTS above:
    // this image is large enough that a single string literal would exceed
    // the JVM's 65535-byte constant-pool limit.
    static final String[] STRAWBERRY_PNG_BASE64_PARTS = {
        "iVBORw0KGgoAAAANSUhEUgAAAWgAAAFoCAYAAAB65WHVAAAQAElEQVR4Aez9B7RlyVUlis4Zsfc555r05atUTiVTKqOy8qaAhsaKhxA0wn7ajP/f+GP88f4Y7zceCttAA0LeYBoaaJBAQOMb0w3dmEYg5JErmZItl/6aY/aO+HPGufvmzay0N+tmVWadXWeeFbEiYsVaKyLWjh375K2A2TXzwMwDMw/MPPCE9MAsQD8hh2Wm1MwDMw/MPADMAvRsFsw8MPPA+emBJ4HWswD9JBjkmYkzD8w8cH56YBagz89xm2k988DMA08CD8wC9JNgkGcmPhk9MLP5QvDALEBfCKM4s2HmgZkHLkgPzAL0BTmsM6NmHph54ELwwCxAXwijOLPhTD0wqz/zwHnhgVmAPi+GaabkzAMzDzwZPTAL0E/GUZ/ZPPPAzAPnhQdmAfq8GKZzq+Sst5kHZh54YnhgFqCfGOMw02LmgZkHZh54lAdmAfpRLpkxZh6YeWDmgSeGB2YB+kzHYVZ/5oGZB2YeOEcemAXoc+ToWTczD8w8MPPAmXpgFqDP1GOz+jMPzDww88A58sBjHKDPkdazbmYemHlg5oEngQdmAfpJMMgzE2cemHng/PTALECfn+M203rmgZkHHmMPPBHFzQL0E3FUZjrNPDDzwMwD8sAsQMsJs8/MAzMPzDzwRPTALEA/EUdlptPMA080D8z0eVw8MAvQj4vbZ53OPDDzwMwDp/bALECf2kezGjMPzDww88Dj4oFZgH5c3D7r9MLywMyamQe2xgOzAL01fp1JnXlg5oGZB87aA7MAfdYunAmYeWDmgZkHtsYDswC9NX6dST3igVlq5oGZBzbpgVmA3qTjZs1mHph5YOaBrfbALEBvtYdn8mcemHlg5oFNemAWoDfpuMeq2UzOzAMzD8w8cCIPzAL0iTwz4888MPPAzAOPswdmAfpxHoBZ9zMPzDww88CJPPDEDtAn0nrGn3lg5oGZB54EHpgF6CfBIM9MnHlg5oHz0wOzAH1+jttM65kHZh54YnvgMdFuFqAfEzfOhMw8MPPAzAOPvQdmAfqx9+lM4swDMw/MPPCYeGAWoB8TN86EPBE88La3ve0rfvu3f/v/9Rd/8Rcv+JM/+ZNv/KM/+qOnOv2Hf/iHlyn/HOWv+vM///Mv/dM//dMvd/rP/uzPXqT8pX/+x3/+fPFe+va3v/1f/N7v/N6/+a9v/68vlJyX/87v/M5tavsSpf8/qv+vJeMNf/VXf3Xvn/3Zn/27//E//se1TwSbz1cdZnqfngdmAfr0/DSr9QT3gILzFz7jGc/4rbvvvvuNN9xww9/ceOONv3bllVfe98xn3fg3T3/mMz5/403P+vurrn7Kp5X+46uvveYPb73t2Z++4elP+18XX3zxA9c/4/q/Vdu/FP7ssisv+/nLrrrsr6+44oq3q+xdon917bXXvnphbv4Xrrriyv9zcX7hB+pYveXSiy/5m3f89V/f9AR3y0y989wDswB9ng/gTP2pB2KM35lzHozHYxw4cABN0+Ciiy7C8vJySasMvV4PIUyn/OrqKpaWlrB9+/bCtxSX9ft9bNu2rbS99NJLIbmo6xq7d+/G3NxcwY4dO/DQQw9dMQFf5XYzzDywVR6Yztatkj6TO/PAOfLAnj17nrGysoK2bUvQ7c8NMByP4IBrdMHX6jjvoDsYDBCqiISMTMBtHJwXFxfhMsMB/LLLLsP84kLBwrZFVL3aYjA/N7i6JB7rr5m8mQfWPDAL0GuOmJHz2wPaES949zw/P192vCmlslt2YPYu2IHbu+jJRPteEiRL8Dbfu21b76BtOO16Rl87agd+y1UfcB+W5515DPGQ684w88BWeSBsleCZ3JkHzqUHdHyh+JvR5oRJ25SdNEk4yDpYq7AcV2TxjEYBfKLddqgqKJLD5a7XpLa0zwSCdtdLK8tgDHCQdvBWP9i7dy+279Qxx95H3nUubZz19eTzwCxAP/nG/Dy3+Pjq61jiH0uA1dlzpaDrXa53xqaGeSEExeJQds8kC3VgtkRymien1HXdzjto75oXFhZcrbTx0YfLFfzfVpizr5kHtsgDYYvkzsTOPHBOPbBv377VLqAqcJYXg36pZyUcuL1bXh2NCt/lXWD2EYfLkypmBWdFcDDGsqN2gHeZ5XgnPVnbmZvvgD8cDg+r2ewz88CWeWAWoLfMtTPB59ID2umuuD8HX3K6Cx4pIDvAOhg7eBs+pvDu12mScKB12m0drA3XJ2kW3N5wnY6v3Xo58hCvKZVmXzMPbJEHZgF6ixz7OIl90nar3ewhkgggmKGNcCggCV9Z581RaQdgB2gHXUMRGAZzRqUjENdx2sHY9Qy3sQynEYiDhw9hYdviRO0n5s8w88BWeSBsleCZ3JkHzrEHlh1ItasFyXKU4YBKsgTqLk1OAzZ5Yj7JIoOkYncqLxdJwpeCcvn1h14a1tp9z3bQdsoMW+aBWYDeMtfOBJ9LD2jHO/TxhV/oKV2CqM+Kne7g4OqdtFHS2jWbGg7uhut2epvvgG85JEugdh+HDx/Gzp07R0tLSy1m18wDW+iBJ1WA3kI/zkQ/zh5QMG11zFECs3fLTjuYil9e+Fk9B1/nHYhNnTecNs9pw3U7mG855jvtM27/q8KHHnqov7CwkLt6MzrzwFZ4YBagt8KrM5nn3AMKyo1//ubA7IDrX154Ny3++hHHxrTPm42O551ylyanxxk2QscY5R+nOECTLP8Ixr+DvvzyyyeH3IErzTDzwBZ5YBagt8ixM7Hn1gMKoBMHZwdpB1unvfMlWc6TrQ05TTsQk9M0yfUATrLsth3gvVvuYDmSX86jN+yg68WqmmB2zTywhR44EqC3sJOZ6JkHttoDmXnov5ExmozLvybs9/vlRWEXWB10nSangdn6mGeY73yHjme+g3l3Bu20g/XaGfQh1Ss/7evazejMA4+1B2YB+rH26Eze4+OBhEPe8Xr3TB75FYeVcaA1nCaPBGjzDPMdfI+FZbnclKSrlR22g//Kysr2AwcODAtz9jXzwBZ5YBagt8ixM7Hn1gPDyeSgA6mDbLfjdXC1Fqb+40ba8a7vqkmWX2WQLEcgruNyB3noshzDfPNMO1i+yj737d/+7bMALV89AT4XrAqzAH3BDu2Ty7BeLypuTv9Iki1XpgRgB20fS/hs2tS7X/McbP2Oz9T1O5DskmW3rEBcAjg55bu+kXLasV5xlph5YIs8MAvQW+TYmdhz7IEWq/7lhne7XSB2AHagNi+AaCcNkDKa8QSpaTFaHYLkSbHRCpLrLxRjiPsxu2Ye2GIPhC2WPxM/88A58UBLrvoYwzte73Cd9t9wducky9/c8BFGl+/rJaLhvOt3cJ0u3ZWZGuQ0mDutejtNzwfMdDx/PTAL0Ofv2M003+CBZjhcdED28YV30AsLC/AfS3KwJY8KrOXogpwGbZcr2MLwTttw2nAZOW1LTn+C53LvzPWScLyh+1ly5oEt8cAsQG+JW2dCz7UHJsC+hx9+uPyBfv+fTw4ePFiOIzo9HGy73bWDr48+TEmWeuQ0ELsOeYTndh2wdjnfNu0ja9kZmXlgyzwwC9Bb5tqZ4HPpgV2Lix+a067Zu2cfXezatavsip0nWf4PK/5XgQ7AHc95qozk+gtF78ANchqwHYwN6CKnPMuIVfg7sWafmQe21AOzAL2l7p0JP1ce+PIv//JDh4cH/3YyatAMm7KTLv9bqzTBqBlhaeUwxqLjRi8IkdEmIBMlcKfUlGMP6+pgbHh37eMMB+MAVUxEblGC/iQ3WM3j/+n6M8w8sJUeCFspfCZ75oFz6YFtly7+4yD2scB5tE1GrlCQYsLcjr7S4vUqTEJAo6CbIKpgq2xRkzmAVCDOCuBokUNGZCiBOaaIOvQBVR7mEUb9USqNZl8zD2yhB8IWyp6JPu89cH4Z0A7a/kQ75smkLYHWP7vzbpjk9I/4g+sGkdN00zTTf7yiLbV3zYrKyCrzztlHHd3f9HB6PBmhmqsRFwIOpL2XrwubJWYe2CIPzAL0Fjn2sRT7db/4dRd/2Wu+7KqvfPNXXn3Pa+656vk/8/wr73zVnZc/90efe+mdP3XnRcrvvu3e23be+eN37njuvc/d/tzXPHf7M37iGducvunem3bfee+dF5ka13/H9Ttc71n3PmvRUNm8ce291w4kZ068noF7tb3EaV5ZkU/177n3nsptLeeeV92288Wvuf3i5/7szZcqf+Vzf+LWq174s3defeePP+vqF/zU7ddIh0Lv/pG7n3L3T9/9lNvvvf0Kwzbd/mO3X/yi//CiXYba7v6i133Rnhe/5sUXf9HPftGl9/zkPZd98Wu/+IqX/PRLnvIlr/2S677qx77kmV/9H7/6uf/m1/7dN/zT/e/+tweag2hCUtBNWD60DE4CqkZoI2ILVG1GMPIEbBv0GLVLroA1cx3QHaingXuMUGlHTcljg7aaoK3H+PjDH8P9++9/6bf+p2/+7pe94WXf9rI3fcXLv/KNX/k1L3vzy77uq9/81d/4tT/3f3zz1/zC13zry3/+5d9ivOIXvuabXvaWl32Dy7/0NV/68i997Ze+7It/+ou//Atf84Vf8oWveskX3vOz97zo+T/x/Oe8+FUvvuNFP/2iZ9/zxntu/qLXfNGz/sXr/8WNhtNf8DNfcNOXvPFLbv6in33xrV/8+i++7Qte/QV3vvRnX3r3837yeS988Wte/NIX/sQLv+Clr37pFzz/J1/80i983Re+5MWv+oIXvvR1L32+67zo1S+68wWvuue2F7zqBbc9/2decstLhOf9xEtufP5/eP4NL/ipF1wjH19590/efdkL/uMLLnnxj7344nt+6p6LTCW3+Py5P/vcSyX/Cs+9F/74C69+zg8/57pn3/vsp9/xwzffqDn1rFt/9Mabb/mhW2557n+841aN6y0vee1LbrnnLV9829f+8tfe/m2v/sY75dzZZ5MeCJtsN2t2jjzwijd91Z9Pti89NPfU8OnRJcv3967np3c9e+EzF920/XM771584JKbdj2845mLe694ziX7L7pl14E9z9lxcOdTtx28/uarD+28e9vBa55z5d7Lnn/xw0+56/K9173g6r1Pf/H1By67/eID19x95eFrn/eUwwu3zy1f+tJLl5/5wqevxmvCyvXPu2b0rJc8c/Rlt35J+7Lf/vL8it97ef7a3/o/8ivf/nX5lb/59flb3vYN+Vvf+q/yt//mN+dX/tYr8lf/3pfkL/vjl6Yvu/sl7bYXxMn1z7t0tPtZg5XB07btX3jqtof2PH3PA8p/ZtdNOz6968Yd91988577d9+y65OX3LJw/86bdn3y4jt2feriG3d/6vLnXPLZi27f9dlL7trzwJ5n7Xxoz107921/9sK+Xc/cvrd3XXxk+9MWHuo9LT6wcMvg8/VTw2e337Twqbnreh/f/exLPti7qvrf+/jIr3/q8KfiweYwQj9gsDCPhblFxFQhCFWulQ5rO+kkKoQWdV2jqnol20CjFgAAEABJREFUSFNHHNBZM1LSR0E5tYi9GmMF81EaItUtxnGID3/2g3hw5XP/Mu1of7R/Tf1LvBpvx1Xpt9MV47elK5tfm1wx/pX2sskvTy5b/c/N5eP/PL5s9Kv5ivbXcVXztuq68Pb6Ov7X/tPrP5y/rvffBk8b/MXc03r/a8+zd/79tmfMv3PnTdvePX9N/31zT+99oH999c9GdR0/MPfM3vvj1Xhf/+nz74nX4F3zT+//4+Cp9Tt23bTzr7c/bdtfbrtx4b9ve8bif9/xzLm/nL9h7q8Wb+j99cL183+7/RmL79h2w+I/XnTj4rtE33Xxs7a/d3BD/70X3bL4z7uevf2jO5+17ZM7n7HtMxffvPvz229ceHD77QsPzT+r/7Dp4lPnHqpvCA/sumH7Aws3Dj678LTBp7fdPH//njt2fvzSOy768MV3XPLPT3nhpR+44rmXve8pL7jkvTtv2v6ei2/Z9t7BU+J75y4P78K29E+Ll8//wzlaKhdkN+GCtOrcGHVOern2Gdf+OGu9xIpj7NjVB7mKkIcYVC1Cu4peHVGFiEGvX+hcf4A6ViXfq+rCiwyFZ+q661Sjv2Ouj54CUCXs3jZQsGpwePkh1AsZvV1E01vGsL+E4eIyDs/tx9L2w9g3vx8Htx/C0uAwqrmeZA9Qs4/UEBV6CnY9LPS3YX6wDb0wkA599OPco2gde1AP6PeIoB3qrp0Lyk+wuNDDZLyM1A5RRQVSTBBDi5xGJW9K8cZYwcGwDysLS2i2jzHuD9FqhzuUX0ajVYxWRgj6r4b/qxD1n4MwSTACJEogTgrIOWf4CiEghhoxqrbQthNkJvgcewTJn2sxrFYwGqziMA9hNR3G2PwwQaoatKJtVDpqzBTQu3zWeLncx9joJcQBEeeECrBtvRqwTbbXdiOPi/11lYvN9pHrmbqO67vMcL6v9i7vqb59Wctvzgf5yXVSs4r5uQqmg34A5Mu6omQDpb7auX/Xdd+lrcZk22Ifru9+7Xf35TLzBv2oudhojDPSeIxt83OoCezcvog6RNR1jdFwQsyuTXsgbLrlrOE58cDHPnbfL6YqIFYV/PvequqhohbaqMVcPdCCa7WwEyaNghEzxpNhyTftuFCGvE6RW4SI9TxzQqVc1K6xWR1jTkG+0ZlsVGACE5ZXl5B8LtBPaOIYmAeGcRVpLUg1vVblGdDO03plySEjmonqN8mLEznriEB8IEAbUnS08HOG+5roqMFBcmllGe6/UUUv7oEWvMvrfk/tgN6gD5Lozw1Ku6on2f0J8rww1+jl3xAOhAytbjShBAjFXlivJD303hBtlm5ithBFljrE+qU6JOEg3UFVS5+MUPBtkEJC22sw1o0g9xuwhvqJ6PWrQuteRIxEUH3Tqg4lHxUMoT7tVwf9Nk1k61icFuoWk8kEjAFt28J2Vhpv22+/ILDwQxVLPecLH5BuskFSgm4sZFZfEUquU5LIGnf7M6UGvV6v5C3ffMP9Wl6lqO/+Y13Bl/vzv8gcjkeSGeB8v68xiEqHqa517AEpogoVWs3JyArNeOLmsm/aX8nMvjblgbCpVrNG58wD1z/tWf9Xwz5WtBGJ9TZUYRvasQJU3ga2c1qgBGMCQougXdBGan6mgop2UeYn0S5vaiPaSYV20itym3GtanPo19sRIFotAm2tmKJF3WpR6qgArbpSQA4Kup48w+EQTZ4gMaHSjooKRB0N2k7lkJFDEo5DCelcI6u3+UXZo0U+t7AIB9PMgNG4wUQv7/RRYFUcyCz5RmfITcpIUD6PVF+BWUcQzURPF2hQKaDpdgAHpJwVANV/wwyHw5HyE+kkdyrgAiQL4Et1TLybzonqkKhDLZoRQkAJZPLhhCOs5mVU2gGTLEcnoSXkamCS15HHSRvVtsBlRq1bYo81BrGPXtVHVfUQhcwAMBab0pqdY73s7PimDBWOorEqfpE7oPuhkAUIWXwKkLxQ8kF9rI7GMB03rbqqC9/ylClyj0f7c/Po9edUVMF6uW03HpQdSHpyGlfoh0WMRxl1NafALFrXiDGC8jVm16Y9oFmx6bYnbTgrfGw8cP/nPvcaBxXWPaz6j9GToCZ+RkSbsnZDnO6ulPfuM7LSAmkU8iK8K6KCqTXpaFA9ByDnvTjZq9BS4Xjbdu2YR4h1X/IyxgqO2uAqeAVkRYCKPSgOo8cBgl689XIPIQXUChJVpcWrrab7G40UMJVutRPE2uX+nDweHY+aYsPK8hBBAWhVxxK93gCttru1dKH0BQJ69aDUq2IPrfQJsjMETV9FizrEokcgAPmkabTbd2iKQKikfxQqgEH1lTaNsVZ/ES0yHMiTUskGAyCJSvINB+qIGpQf20kD6r/BfB++6azoaUUdqn0qsH0kETU+HXq9HuwfgyR82U/2T6MnDdvpX50E9Se3oVbQzgrQMdTFXnUk2YDLbbf5ppRfrJv5TgfZtpGShPPQZZmNnox68uFYRxGD/rxkplIe1K/76/x6lHzp4fGxXq3GI4QKpU9R62U0khs0/mM9BdV6whlqtw35fNRMb9pjHRFJhdlnkx7QjN1ky1mzc+KBwa75/2caQDu2VcRB1GN8gzEnaKsSUkAt1IgB2FZKDYAmFuo8U6/wj6Uh9xVca0BBZ6IdYVOtYqk5iDCflVtBCi2id785wUGnDhFsgCpHVE2FnttPInqSHyQD2j2aBpV7x2kapQUVwIPKg/jhBLSKRAyAqe4EhbYKsOZlPZITDiSyVbzADPO6ulSgljaITa3H6oRaNxHmjEoBY6K2LfRfSGjYwME3K4LTgSwHaCMNSu+k6OOjlVY0VNQOfQzzbHdOhPV2cGZDDOIctD9WXy3IgCBZMhGtnlzamAv1L0gmocWEguhId7UmJgyT5NaA80nU5VAb7zA7e2KAjoEmsJ1W0Hnb7/Ise5zu+M539cynDDoeDfKf27jc9Z13W+ct32XmJwVS511mar7lOW3awXW7NJiQNQ8nYYy2auTnETQESDp/12RBy6bkMbs27QFNiU23nTU8Bx7QwnmDd3heUN7ChjQBoYnvhbfeP9dSZ0YV7xCKrFaxollLOyA2pQ8vwOCQ5IpMKlc3ojiq7w28ruyMKGDx0HWmFNIoONrlGkE3C+eh4CtR0OZPUABhQpsVPISsIBa0s6x0w6h1ZBMVoHuxQqUz7qoOIImo3W9Q4HWApmQFCQqqG9qomEVAeXSBmwGPxcU8lXKmdNqq++50OVOKs/A/NALQ3EmFWv8O0EX5L+pGreR59nniqBueOKrMNDmeB5jafx20u+sJeg+GQQPMTQC9n4IOHbQwFITCBEnIwpnQoOBsOXrHhr52o0ZPRwZGzKksPKiOkbUjbCXfGFcTjOMEEyFpl5SZFLMSNkMd77Ks2CykJRJqIU6hoKn4iQ45ZLk1KQi16iVp10/5LWCgCnrHKR7hHXdSrUZBPMkWUz+6J/kcCjLUNpmiUdQIeinmfBAviFcpgFeboEWu2mVpcbZIILLkpDOkWW02C+tf64mqr3cY/UmNvtI9Pcn19ERT6YYW5afJiiarfDv7bM4DYXPNZq3OlQcUI95CLaKoXR8VEKjAAlRahpV2cxFAUjoBTGdMSxvLtGwtJgpQXx1KaAsJ5ZcLom2BjguCEPXILuoAS1cEsBnqNsRU9zOl0OX+7RJTZacf2TRNTL+9Iy6QhyhWSArWCbKUyHIwKaqjkVbn5glZ2mRM9KTiXbSql09QB2QEhcLQl3UPst2UuqGdKQ3qCUyg6Kax1j+kt/s/U7rpfqWz9Z/aT9A+l49grPlGBH4iMZ1hcx4Im2s2a3WuPNCE+M1Jj905RLQKzBNW2rn2MNLLsnF5fKwQU9gUmCo0rDEKfYxDXTBRfhwq8QU96qeyIw1adwpdCiZZUSCtwT6IkhF1zLA5SHcFtqid62ZANNJLAZVT3ayP4UkdE3XsEUDddJgrBNlEapetgOxA3GIMFcJXlB9JAoFQEtRZdNSRRxDN8jpiFp+SASGAJHwllSXfpDYJ6OljM3Yf1UZ2xLPBmfte7mgKAHkgJIzln3E0BRql/SuZRvk2tMg6n7evZticB8Lmms1anSsPJKafV3hQUMiICozuNysg6fgUistaIuZsDllxptUMUCxToNNyU9q8DkWqd0YloUIYRHAFqHHJ4/G7mJDYICsQAPZSlm5AkEFRzonSXcWKw1RxQFIdvwwc68XdWMG90bGNGsNHGcUyBd6yaw4Z9EtS0cRc7MvqC4GSkCGxcN5lLlV3pc6Z0tLofP6ST2xzDvIs5RfBT1lH8gnD8kuX89nIx1d3z8vHV4NZ7yf3QNt8M7RDCpgg5CEiRghC1JtzcAIvhkY73c2g9ehLBjlSuJ1sQKN0EqA+BZ0rhnX0ENoeqqYvWsE3iia22ByS2gfBcs4cWZ4AW+lp3RvlUtFXmzjt8AKCdvdBTx1Qjaybikox0c5OR6SYVECjAAwH3RaQI8EMeHftIE351C8X1wOxglGLFtK4BOdWeYlElvyce0jCmVK3aXnmdm9s04RKdmweG2WdaTrBEyjJu54va9ATEQXIU/bnfH9ezp19NusBe3izbWftzoEHUjv5JejMNGd1pu0KlXBcoXaA0CIQ9yw+XlxTQAGHlmeqvry4jKDtoidJQSbCOswhHMA2YqpTQkepxWo473oObIbzqqT2QNZC3wzcPkjXYL07SF8IFB/iMWYoiyS7pDqijosMikLBMQpUhagwUzMgpKzgDoVdICsgkxlJ1MHZfwe6UboEcJW6/w6ZLMkzoVlNOiT1bWT5AtKno0HpLr+xvONNqXUNRSO3s+mnR91miqmcIEGnn4d0rRNQt04pUXyckEJxBTze/heS09zsezMeWHPlZprO2pwLD/RQfat/h5xzH22e15KYVyyt4MdyKnRQq3HzcEA6DhQUSuCTbKiPdRzVkVYlXCGoRgDVZiOiVmmlLbp/KeFF7KZawhALmSj1tZkVVVoSii1nStV9pZuW+8nqoDxeK1BmBbtW58JJTxmTaohJGMlfjYJvi1pn0LV1a+bApod23KIZriIPx4hKLyg0z6k8KV9JUQdj31AaZN0Sk14eNtN/CCRDLEemKLQn2ZE3Re3bTN0CgiBhVJ9BYIbGGqisr25yruObDHQFIarcgNoWyHdnSjf63KPo/JlQj1+tJ6tKTyrWNxHazQf5GwXWN1pZ6Tv7bM4DM/dtzm/nrlVof8WdZQUOI5VluzZsIbuooEueKYXkJQW0o2kRCS84p47QpGwSf0qnAUEtFZxVAC9S0w5rWnbZdep61tMBxnS94AwTQf1SwQE5IiGi1W546qNK4TQgMaNFgoNbJkASVN47ehUhi5kVQapBH6wrsIrw1batdtoVKu2yqXJS7US9865CDSMgyt4guIXgQCmCM6Kp6APp5KaG/UEnCo6Uewx8nCSVS21Tt7MdtB9U/0ypmpz1p5VvkubQVB/A+nhcAiAuQD39YXZt2gP246Ybz9fVTPIAABAASURBVBpuvQfI/G1Ju6vEBm0UlG6ppRmIbCjtxeEF/HhQZqBAu07kgKxlaVifiWZX47Nc3QCyEFRet0FHCAbO+kqlrxot9XSBvgKuaS0dotATapAVMpVX5Mt6mZhiAuzHeoxGGNUNmrmAlSpjWAUsM+Nw26ANEY0Cf05RelZyeJTeNWr0ULHWbrwCWhWd5UdqwYjaJRssQlv43YKfCBLVjcdcyAr+yVC6EeRKqCKifL9ZWP3NzptGrhnKDas10ASUedCTUv0GMGoxY1QhZtdmPSC3brbprN258EAGfjm6IwWWTEUEIYdGoUmBxnztp6hKTj4e1H0a7t9w2nDamAYYpwDFmmkw6lTH2V9JAcuBC6KQLwy7wzo4aAUHL93RguDYR50x55wVzFt1npD8T6hV2dSyFBp1hGEFQ3lhqCK9DM0FbqtG6GxqXGjGpqHl558A6inAehboJrZRnPuCeA7inf+6ctvZpc+Gck3QmVL3WdxqxZQxsQz7PUqmrMNkNFbJ7LNZD9iHm207a3cOPKBJ/23TbrQtoSY7J3q4nigeJcRWSChBzwv4cQFSuVmENepdoBFKUNH0EoVABUrDfGkML2zvsEsAmhp45t9skONQ4lYQuAL/rezyixT7SDvSSr6pG6KaQAjgmEJAmFAI8l/GXAYGCtpGX3ReO/05HW30AJ3/Aj2KIsO/Pc6YIPkkWjeDiXbcjXbcZ6W//IKsHabeL4TU1zhGULv2INA7dwVvCFQ92+Lz/HImrXtLkN4G5IN0FrCMzSIm+WYNnQy5DfZJwnRG9PtzZs2wSQ+ETbabNXvMPXB8gUz8Ze/4EjNarwJX04LVJhBZQc/ZxxNSS0EF68CGS2sXVtGP0EZXlJhki/a6Cn4b+V35aVPJ0cM1qKDpm0K0U5ClSxLPAMrf3dDBBBV4Q6hARmQdX0DBFToiKn0pMJNUuwCqvPwVPfk4qNC75qhgT6W13Uar/5qsbwquYP5ZQULUF2B9E7LkJmbRXKTaP1m6l11pCkVHICCUAB5UD4LbnTncQSo+dOrMkei+pecGGdbXkjo6nuhuYsYMm/JA2FSrWaNz5oGU229xIG60KFvtnbOCTKszUGAeWYGn0RlvExMeD3hxBwUWBzBTrC3UrIXbhqlOE1HDeZ+ruk1WvekZajo7PyqwRR0PxLaHuumjamsEdZ4V0DJ09ix/0VRBN9cRrbbDTS9i0o8Y9lohYRyBYUpIsYeh2o4ls9WLwJGCfZLP/WsZJEoutIuGTMxwoO54OJuLsl+7fYQRwBFyGCIpnQsatPKdnzRUC8m6CFk2GU3oIZV5EMC8OchcGJueOwHyn30o/zFL3wxfliknSXaChsSsGTbpAbl4ky1nzc6JB3ITf0lHzvDvc4lU+gygFiVABR48AS4vSMUweHm2AVqoKGmrpqzCChBU6F9PBO9GSxrFBpzVFeQRRVgdCUBHARB1/40Cm95PwcFtklq06rNVzSStip4Kvq13wW0LaOfcKqCjqpGUnqgOouVm0JSAldd9EFNQTSibDPWNs7sSqH4TwFRo6xeY0h+6ggIvVG5M/eh6udQDlDZKHTxOV5IfYPes95+4niyJJF+XxOxrUx7wuG+q4Tlu9KTtbgB+ZU9npoMM1G2jM9RV9PRIb8R2DJ9Jej0/HvCgeJdX/nVegHbxChtaoFLVRWXH2dOK9Rv9MJnAv4f2WWpfpRyN0NcRg4O2spv6+MbgQDypiLGClXfqE3WwgiGangJYLX1SoyCSFUSSFJxoo5oQG2Kg6B3bjOUDS+W30GnSwH/VzvBfsiOzAvsYejuIXLeY5CG6l7ORUlfn/z7+cFK5zX0UXAMHaLXNTNrpM2rX7+MXRqQmS+8Auk6GwnSzhhGgnfb0vF32yG4xcCJQskKo1IeEQPKUz3JcK9sDouSrZdoc5GrNSQhB8xDQg5weLpL8pr58bCRif2J2bdoDYdMtZw3PiQcevv9zrx5oZ6gnci2ChEGlxduOkLP3eq108BAGBaDHg0K7VO02GQptRf3iz0hKSzn4f/vk3X+/clgWpyb864c6RkDnk0GLWNyz+ARQ57F9HUtQstgDkgJqVvRYHq+g0tFGrZ1wpagapVNPgXAOff3Xw7yOCHYvbsei/68noF4cKgg2LaIERdVHBEZppFeDY0Qdi0wU7LPrqaCnjqggBwVQbPoiym+upVfQTUahDf4rehPt+oPGPOrMOehGAt3kqICX0WASR/L1CEnpJM0S/GxgZPGOT32kRPlAkwQIhPPRv/smpXkoCMWOoCqnn3cb6+jADPklSZ7HPUtsKZPMtPb/J8Ts2pQHPBqbajhrdG48sP+ze//vutHOSjuqLECLYKTdW+NHYr3oarUakvD40Iis4JiTFv2aDhPpN1YQGGmRNiCoHSER4aC9rKByOLQ4LJp1FkwFDTXFZi8qgPXbCvXYO+Iacwq+i4M+tm0fYNsO5WoghITADH0QEHWT6yFqx1qnPnq5p+A8wEBnFz0H5qbROXaLOmdpngC22LFrEbEO6M8PsH3HTsxjAb0kGUPx0kC7RmLzV0ZPd6jACZp2VcF5FagTdNqCWmLzqF0LmIDjtJ8WxgEY6uYzFiZq6ycYB9wTYdyMYDRpovvhGKPJsOQTWqxOVuG5Y2xm/rQKwFljkEUb+XacAQ2FRjfIdfJ2QyxWc5t3z4XS8izsCGfRdtZ0iz3wb++54arnNju/966983jOQ4u44/MD3P65Odz1wDxue3Aedz20gDseqnHng/XjRt33XQ/UeM7np7h7PV3hbvHukn7PeAi4/JEWl6Y5DHxWLL81usmMdGSj5Fl9UlIgRULGBE0Y4hP7PoYPPfzP+ND+D+CTw4/h46OP4r7Rh/HR8Udw3+Qj+EhzHz4y/pjyHy348MqH8dHhfap3Hz45+gQ+Nfk4PqnyT4w+ho+t3ocPr3wE/3zog/jAvg/iw/vVXunVsISGQyBMkBz5N2mBF1+jY6qcFYi1g677PUS9/JsstxiszmHneAd2LO/EzuWLsGvpIuxY2lPSO5cuwe5DF2PP4YuxU/zty7txIlzcXI4940sLdo8uKdT5S9orcNG+Bdy4d27z8+eBntoKphr3Ox/q444HewXPfrjCjftq7Pzs8ia9M2tmD3iOmM6wCQ+87ou+4hO/c8fz8n+/6fYT4q9uvSv/5S13FvzFs27Lxp8989ZC/+Lm2/N/e/az8x/d9uz8+7fenv/k9rvz/7jjBfnPb747/9nNt+VX7Bt8+qX/c++/efovvh83/+JHcOvPfQzPfO2HcPtb7setr7sPN732n3Hzm94rvOfxwZvfhZuFZ7/xPbj99e/BXa97D5772iO4Q7yb3vBOXPvL78ZFf/AhXP+piQILscg5kFE70x4ywyY8P22StTtOOvvJCxmrvSH2h3344/f/AX7p734Jb/ir1+OX3vuf8MZ/eAPe8K7X4XXvfTVe84HX4DXvfw1e/b5X42ff+7N41XtejZ/6p1fj1e9+PV77vjfjTe//ObzhPW/GG979JrzpXW/Cm9/1Zvz0//wZ/NL7/jN+4Z2/gF/425/Hr//9f8H++QfRXjREM7eKHMZTZTbx7VtLVfeRQsQ4EcurLUaKZ9vzxfiym78C3/3y78W9X/Oj+OGX/Rh+9Kt+Ej/+lT+Fn/jyV+GnvuzVeNW/NF5VeD/6sp8s5cejP/Tl/wE/8KU/gnu/9Edx75f9KH7wy34MP/QV/wE/8i9/Aj/+gn+Pq/7TB3Hzm96zKdyqsb/lTe/Gs9/4btz2xnfh9je8G7drLtzylnfhab/4blzyq+/Cnr+6fxOemTXpPLD51dFJeBLT3ZN0xVNWVvHU5SXcsLR0XHrdwYO4fukwrtm/HzesLOOphw/jxskYz1hdxTNGIzxteYxr9h3GbVqkVzyyF5c++AiuemQfbh4l3HQ44fmrC7jxUw1u/Sxx50MD7aDrQu98eIBnfy7izgcGuFM7aO9izzl9oI87vXMSuh30Xd41C13+Dul240MVrngo48pmDvNt1LFAgF+C+f9qfbbTZ9w0GLZjTPII1fYaq4MhxttGWN62hJXtS1jefgiHth3AwR37sLTjIJZ2HirpQ9sP4NAO8Xfux4GOb97ifhxaPIDD21V312Gs7F5VuxXsnzuEQ73DeGD0INpBxjBJ/nhJN5izs6DVy0koUsdQoVfrWIYDpBWs7aB3IX1OhZ8NyEL6bIUkis8EhE8Lok7nz1Sl/Hg0akcbPt9Hpd1t9aDki9YPzWNweAG9T7S46ZE5bHbe3K7xv0Pyb39Qc1DpO5V+zud7esLrFZm37K1w0Wd1bHNSF80KT+aBcLLCWdnJPbBjbkErJ+utNctZ3vHO8fQkX8qhHSO0CDMCxk3CcDTBcHmIsNpgW66x8tA+bNNuaudcH7u2L+DAvgeRJiNMhqugXho1oxGQG+3YJpikZaTeCI1eGGWdl0JBj8LjQXOqdQ5dI+mlVqNzyIns7OB8jH05kWgZsNpmjHLWbjGhDgoaOjOmzi1VYdOffn9B57Q9UEcnEg2SiDozjjFgNF5GZIuoI4RahTreRb8B5puIOR2KD7RrVUtUsQV1RGJUipYRyut8VypryIjV8QiMFRYWdujMerteMA7Q880mLKjfzS8ht+yz1rFzQJ4kqFv0Yg+V5knNCnncIJDI0qWtMpp6gpEMGPfGMNpKxqisvNRklq2PRm4nMCgfGE4bQXaOVoegfADNHQpnSsvZcw7ynBGRUgXoTLqWb/vjiDlhOwaYXZv3gOfI5ls/2VvmSUUSpBfRiWlxkwJG27bwenBQDXqL3u/10FNAq/SCba7qlQU5XF3BUDvt7du3I0RoASVsX1hEjFzHqBmpzxbU4tS6VIAiAD4ulNId6rulfCCa1pBNpZZ/spZVJ8rWBMgmLWRk1DIuyx9ibf6j4NAqiFFO7ffm0TZZN74RhsMVxIqo6xo5ECQRWiIqeMQMhJR9a0CVA0peNw6KH6UXlSAJuVbBJiOE6RKx/0e6SRrtJKPWTZVtgKrjbK5GN9/IgJoR1qvVyzzPkwj954AHIKuTzEZzJ6u/JAqk0GASFaCRVOPEH5KIMaKqqkJJKpBO27gfygdB4wMQZ0qhy00NIEi3IBkoNGpMKH+PdRPA7Nq0B8KmW84aglrdOSdQC/5ECBnr5U5HLQTD6VYvuCYhwUhICIEIMWquR73Vz0ia+UmBeUVv3lWASetaxKAeIA8VJFIsCwLIeLwQ87TvVgYZckkJelN9AJKoY8BExzoOmhktbGfTjhQwiLO5FGoUFoCgANZqB0pGxF6FUEekpgUUJDKCvBP1rb2ydvkOdv6LdorXSOJSPqT4koLEUOA0FbiiyxSM+1G7Wd9MtNOOPaLIUF2ojutu1oakhq1kThRspTAqjb9u4aBu3mkSEVmrr4TW5apbS+l+U+spQPapf5mn7qkSIJdxAEgWQJd5JBGC7NJca3QcRBIO2F0asBYZwJkjqE+qfUFmmYvw4R3mAAAQAElEQVSUnMwW/sWJHlQQ9USI2bVpD4RNt5w1BNjgbK9uWWyUM+UFTXXjyNIpAcFBQQhC1A5lY7vHI00pS3Ws9QlDcRrRa1483ZngcifNNy1gV6GjhbupLzpASIf1xpZtrDEcxBI1zXUEQvksqWwKVxDfQVp85zbCdY9AJWoHYdq209vtVbbZj+T5pmaZFjH1o2XboKiBr+CyrHpAgvWptGs3IJ0Vr+FdsNs6CJs67+DbBeekwGw4b7gOyRLEuzbmbR7WNZfgvC5D+kpNWD//re11/ixxxh44yxl2xv1dUA088S8og2bGnHcecJAlua43SZgHXSTLzroLzGKVj/MdCmMLvyaTyRZKv/BFzwL0WYyxJ/lZNJ81PUsPzJqjHFd4Hnrn7A2Dg7OPMEy7vNOdr1zXcJnR8beKjsfjrRL9pJA7C9BnMcwBPIvWs6YzD5y9BxyYHXA7SU53MI8kyCmcN1y+kTq9Vej3+1sl+kkhNzwprNwiI0lukeSZ2JkHTs8D3gWTLL/SqKqqNDLPCe+kHYw7mEeyHIGQU4otvqzDFndxQYufBeizGF4thCfuAdtZ2DVrev54oDu+0FyEQbIEYAdln/+aGtBFTjcUJEsdcprHFl7WYQvFX/CiZwH6LIZYC2J0Fs1nTWceOGsPOEA7CK6srMC/0bZAkjBveXnZ2aOOOMgjQZk8ki4Vt+BLa2QLpD55RM4C9FmMtSbfLECfwn8k138K5qryWfllgens8dceOTkcgH3O7Fo+wuh+Qre0tFT86t2x+fPz8xgMBsW3ru9/pLNz5043W+fZ3/a72/jlnYN4qbCFX+5rC8Vf8KKfpAH6sRnXCE7on4E+NuIuWCkk13dxDjiGjXWwMJ3h5B4gWSo42Nl3DrS9Xq+cO7uAnJZvTJPTn9iRLL532w5dPctxeoYnrgdmAfosxoacue9M3UdOA4bbOWCYznBiD9hHJEsF74ydIFmCM8ly7uw6G+E6zvsGaGp0PFMHee+6Dee3El3fW9nHhSx7FmHOYnRzTvEsmj8pmuacyyP2RvqkMPwxMtJ+I6cB2mmLNXWQdcAmp2XmdyCP5rkuOeW5rQO34fZdm62is1362Xn2eAH67CQ+iVrreGMWoE8x3g4IrmLqoGA4TxIknZzhJB6w3zYWO+DZhyTLjY8kNgZgcprveOQ073bktI1l+iz7XJxBn4s+cAFfswB9FoPrSX8WzZ80TUmWIAJdDg6GkrMAbSecAp2vXI1k8aN3vg7AhssNB+0OzhvO+5cdDpJOQ5fbGD7eMMTa0o/72tIOLnDhswB9FgOsRXD2fy3pLPo/X5p6kfpmZmqd5TeTsgMsidnXCT1AHv2UYd854JqS0zKnDQduB2KnLdB0I8958z0WxrkI0O7vnOIC62wWoM9iQNN44r+qeBYS1poyAWug/2oZjlxegoY507/MBpg6f7awnPJXxzQLUlnsEcwEJdh/Rc1QsnxcbpRM+ZKmatMEohWFWrktkJXM5a+bMQeQUbu+HlhFOCiQlg40INoQcLZX91fypDYMIKnvJLFTmNdBTJXhuHDZiTGVNZV9dPsTtzn9EuuXCGQ1cU/OQ3ZQjIoVovyYc9QNjWhzwriZIE8axJbybZBfp9Tu9E2wozGy/E3sqgqqE0EJdJBOqSkvF5vm7P9ORpCORpJslMsWlIS+iFr6KzH7bNIDZ79CNtnxhdAsj0YJSTMUWl1rIAMoAETaUEbxDL0z00KD3sLXWFhYQNDE9h8VbXIDL6zYtvD//WNhcQ65zPyMft1Tm4yFbYvIdYR/25eDFqvqWkbs1Wi1vA2ne3MDhLo6Ia/RIi8BQQu3t2sBw+AXeeqjiRjEOQT9l0PC4u4F5JRK0AYVIMS3DXWsUfXUR6+PUawwCRWYAvxnRgnVV/iN6qNKQMoBkyZjdTjBeDJBk60lwP4AYkFGKmCmTUJyJJ8yJsSonlv4/y6S2kmh/jOdBQAUy+Qh6xhQSVdmwIiiRpAcCFk2Gli7ZHaRazlgkj8aUBxZjNymTeo9bQfJsVxDCsuPkt+LGMtHDrSQ76PGJLR9+XagfntoPS7bF9DLPfSaCmiSqkknZsRIOAinEoAbWaB+AgqvlU+Mfr/GaLSKyWSkdglnepHUPA2SSfji9G6ipPQI0z6ZIVfZ1xWq3Mfs2rwHwuabzlqi7i1DgRJa+lNvONAZ04mvuTxlbygniazg1Sq4+h8L5Oz64iFouXo4FFjVfKTFP1ZZ0iL1/5XC/1Jsebiq9diqXi4LpNfrKciN4Rc+rmdZfvy1XPOctwLuy3lT58viV8ILeXXlEHqyoYoRUKAbj0aSN4H/ry0HDx7QYkTpC9KvQDcd92V5k7YplqmZAlVAzKqLpHRSWkYAYKwVrgkJx5xuGot1xHi4JGm57O68vt0e4pwplVDpFiB3IukmAAXYIqMO4qtU/mMWLZ9Qvv1llvv104O17GAeQLiN7lmyAyUIm58U/LL8lIQsXaFrKjGg9CnemVKoTc1a/QX1o3nQSjP5l6SkZzjYKlE+7tM3mTa0CtItoJsF1q+pJpC8KetI3scYZISfXkKo4DkRNSbmmzetf+Jv13cpSZAs7T2PzKcKOlifcqORXvZXZkCSPp63qjb7bNIDYZPtZs3kgYOBy60WrienljI6mqEFpNAVoliawfRqV95UcxymKbXaxUyQkxYNamRqd6TFOhGG2pEuJ2KiCV7159Cfn8e2hW2IypeFQZbdj4OkYV6McX0BOYBC15FAnEpf5pOE+SRR6SYRl1ZQra4AzQhRW96o4NbrVRjUtfqjZGYwQFcqyFp2OU2Q8kS6T1SnQaUdWyhhuEWtG0pUYFRlZH2N2SDXRFIwj3o072nntk0Rdc7W5bFkq1aIyIzAGVK3yfJJkBZSHbXajxTgGgVRj0CQaPMN7+6ljoIbtFMFRpUQBdGJqDaqpczj6BuN2xhO218OjiMJdLtJJXvUFzRO1uFM9e7qR+kdJgm9ccCc5sF8rtFrAT9BZfknVy2aOBZGaOMUKTg/xkT8iYzKDGD5nxFUx6VZdyFtnpEa6SxM9NDn+kFzzscncjpO5yJZqnmudSgMfU1vaNYjQC7XDAEa7fSb2GB+20A1Zp/NeiBstuGsHfDgwQNX7o8BD/dqPDKosHfQL/SRfq9Q5x/uRzyknaNpV2+fjiD2ztXYq3YH5+awbzCPA3MLaj+HvfMD7He5HnWXRR9eHWJp1IA6SmjGEy20FpWCQwjToaMCkseCJMwjpwuJpILrFBsXFEkcuQLm5xe12IlD/YD9C308Ir0eqGRTjNIv4hHp+KD0f0T67O1X2K9H5L2ydZ/SB5Q+qEBuHBDdpx39PtU3fVh1jH3zczgsfgw91CsJOw+0uHq1j10PDHHx/gaX7AcuPgBcdCidMb3oIHDxwYzLlgKuWKpw0UpArSCUFORyIiIrIAcFLhRAVxK8w+toScsl2Xyh+1AMI4IIiMiq6F16b0IsrAI7pe+lS1H9n7nenb17iv4Blx+ucOVyD5cdIi7a1+Jiyd6+PMIOnTMv6oa23RiPsFMoaeUXdENdFLaPxyjQHDke3akbwA4dLZnu1s3T9CIAe+SfXVUPMSflTv3xHHItkuvzTC6CL7nGZB0+HoN20kGy29Xldf4sceYeCGfeZNai88DVT7m6eljnwp/YtRPGJ3fvQgfnO5j38Z07YJjn/BQ7cN/iAPct1PjYYq/Qj2/r4xOLfdw/18MjOxbRu+oyLGlP4iMP74ArBYyolREUhSoHlpy1FjLQaqEJ2uSVR/MSuNfyUW0qBhgud12XJwX6pdjDg/UAn921Ax+Y7+ODOxbwsT278MmLdkvfXfjYbtHdO0Wd3oWP794uW3fgE3t24FMq+/SO7fjMTvHMF+7bsxPGx+SLj6vdxxcX8GBvHhdddgPqvQE3Lu3CHft34LYH5/CiQ3tw18MD3HkWuOOhPm5/IOLmTydc80CDS5o++t6Ny3Y/ikOXA4ih5PrHPqzkskpBy2kApcz1vFvO8pcZPi7CpEWvBXamHq5s53DdoYhnPhRx+4PVWelv22+RjGd9Xn75TMbNnydu31vj7tECnq4gjb1L2HNodQ3LuOjgCi47OCq46PAIu5aWsVNPQDuXlrBzWTgO3b50GLsOH8YOle9wufKLyuPgAczpJWFfTza280QIaxsBzz0HaedjjDB1m+Q5qPmVdBtz3vCNLWhe9jVHr922c8W8GTbngbC5Zk/8Vq96wUvv/5Xb7s6/89x78m/f/aL8X5/zwvw7dzwv/97dL8y/e+fz8+/d9VLRF+b/etcLCn73TpULb79L9K7n5z+487n5D+66u/3du+4a/cpNNy//2q13HXjrLc995G1Pv+vhX7r2lvf/8m13HXzFv/vXe1756lfj6173Orzi1a/By1/7Wnzta5UWfcVrXlvyr3jd6/GK179edV6PV4j/ta73mtfga9fKX/7zb8LX/dwb8Q3CK9/8hpL++p97A77hza/Bl//8G/G0l7wIVBCcaNb3KwWfugf6xZB201G7Ga2PMhhePCWx9uW8sZYFyQLnzTeSFt/DwxGe/pJ78C/f/Ga88pd/Hl//+tfh6372Z/D1r30Dvv5NPw/r/3VKf8NrX49vkJ0vlz1fIzts39e+9tWy8XX4atn8MrX7qje8Hi97w+sKvvqNb8DLlX7lW96Cf/sTr8I3fsv/Gwc/+DAe+dMP4JG3vgPD338vDv/G32Pyu/9U0P7OmdP2d/8R7e+9E80fvBvtH78HzV9/GIODY/RYo9aNJ1YV9IRf4MBr2H65UscyWEcsQTq5CCXgEHCQTgyoegP06lrHQQF9PQH0PnMQ6Z2fxOhP3o3J772r6D6RDZvRf/K770T/v38E4U/fD/zx+5H+8D2S+U4ceNv/xN/99M/hr/7v78Eff+cPCj+AP/2O7xN+AH/wHT+A3/+OHyz0D75T+e/+bvyB8EffdXz6h9/xnfg9lZv+wXd9l9p+J37/3/97/Lf/6/+LP7r3BzFoWpzsIqmnh1zgeiTh4EwSvhyY7WOnkYNIKBsEnb6gr3k6euDz8//r5rvzH9z8nNHv3PnCQ79y610rv/vCLxj/xrPvmvz2nc8bvu3Zdw3feuudy7/17LtXfuv25yy/9eY7VpVfffttzxn95m13j8Sf/N5zX9SIn//ohfeUNaw2+bdvf27+Ha3T3737efnVd951vzq+ID/26AVp2NUIV9+gF0eX7j+AS/cdwhUHl3HFgSVcc2AF1+xdwXVKXyveVYcO44pDB/CUpQO4Wrhq+QCuEr3y0H5cdehguPLwwd4z2c4/dbyy4/rRyp6bwItuXZy/qb+ytB3bFwHtIoe7L8LKzl1Y3rYDI9Mdu7CyYydWtu/EUHRV+dGu3VgWLXzRoeqt7NiB5YUeDi5U2L9YY6/oIe2mlxcrLM9FYL4CLtmNQ6lBt4POWlBEgo85mFEWixeMQXJ9LEmWMnLKc0DGMVdLDf/cduzVeSS238HzwQAAEABJREFU78ABBbTD2j2vXHYFhtt34VDoY7xjT7HtoPQ9LL3HxradWDGUXtWTwfLuHTi4ZycOGPLHPtG9u3din2zeP5gDrnkasPta1I8Q1z08j7sP7sILly7BHZ/v4a4H+njO5yOeo13wmdMad34u4HbtQG/5TMaVnxnhorYPTjLKy1LtorPMl5vgwNuZ7x1znaAAMoXTfrKwX123CYDhwNOkFknHBHVD7Eo1rmrmcN3BiJseoHSPBWeu99Teux6o8LQPreKmjze47f4sP/Twoofn8IL9Azz1Uwex+/3347L7PldwxUc/jys++hlc+ZEHRD+Hy+/7vPBZ4ZO47GOfwKXC8egVn/wUrrr/07j8E/fjCuFKpz/+SVz80Y/h8r37saBjlM4vx6OeNySPurl3u2n71P6VK2Eqt8Fz0vDRSZ0SLtLx0OL+Q7h+gt51h4fbnrnczF10/wP1M4aorjm80r9+daX/tGbo9TV35aF98zeiGVw/Wh5cs3KopzXXu3b1cCV+FA+X7X8YykN8qBzX6Kng8sMruCH3rr73zq+aP57+5zvPPj3fbTiu/ttGI2zXI+Aenb/tacfY02bs0R1917jBTpUZu0x1rrdzMsbu0Rh7tCt1/ZIfj+HybcOhFn2LHaLbhW0rQ/QOr2Knjjbgl2s6dz7Y72Gvdrb7qohHqhqmzu/X2e1DIR6V7/im5Rx30MOBwQCH9CLwoM6jD+lIYP/8nM6h+wrQcziUM0Z6ZB8o0DkIJ7/YY0DUY6aDkBeQHUDSpOx0ugVUGPpynQ4kNyw2pyNWmgZYGMD67Ov18ZDk7616CtLbsS9W2Bsi9sWA/bVsqXUjEfbrReIBYa/SPqd+SGfSD/VqncdH+aIuZ9ePqPyg5EEysPcgFic1di8HXHIgY8eDQ1xxOOLyg8QVwmUKemdKfWZrWVcv11q4PexZIuYUENqUFTACKumTcfzLQcSTP6iCERNg2tV2wPEuGrJbRcWvQZvNviL3/ErGroOt9A9F9zPVu6t/uc6crxkOcPVoDlcOe8Uvux9pcamON3Ytj7BzuDqdl5qbF42GuHg4xiXCxcNG6ZGwgafyS1x2DN29soyLV0cwvUhz92KVO3+RZF+quVDLV53Nx6OeN+aTNCl+8PwyCoOpHG7Yn1jbQU9rQufbGV4jl2iN9A8fki0NLtHN7ikhlrW1fWUFe7Qmty0vY3F5FZdJYP/AIezW2tutY6Vt4pvuHA5xiQZk/tAhbJctF2sNeB3uUHqPzt7nV1Zx7zt/f0XNL7iP5+gFZ5QN6qUJFrT7qdMIvdwgN6sIFM0jRB0+ZowBtmDWREoKeFqNQZOFQtCkjeZpi1CjQjtqteyJAWstYgKhh1Wl0e9DW1mM0hhJ2zK9TId/p4qahW5MN2yPquOyFIgk+dnQ7gzsY5gjRkKrQC8FkXQMQbIsjKwdYYgRjVpJXeQYNgRbGQKUfFSdoHbQ5QVGsvCd7hYWSS2ghF4zwRwT0Kwg9ImhbGlrIClaTdRTK5orwHC6DamkbW+DFqauK8fAv06JoKyRbFFpJx0ThqtLgM63B9J7rp2Ux+qB2gYBurJuCBmhBNUzoVCbSjeSpCelKIf0dawx1K5tLP9n3UwmuvFQ8kMGHIApqqz6AVS9YBKBRBSeiLTGGpISslVM76T9y42RXp6Oq4gRgF5/DrmF2p253nnNXuSAJBtaBR+ZADkQdajQyIZUZ0z6E4yrKSbVGE3UiOgu0YampE0zM052kSxzJxwzH9zGvwAyPRm6dp43hueQ65P0kMsHCaHN0PQvfnN5kj0JLVomyCBMJkNUrpCGcpi9Nwa0LjVN4fVWa8YYbIC+1lZMQTIJ84IGyvk8ThjEPpxOWo8+xtIyxkC+0j4AF+oVLljD9AY5tyMF1kZLQCOv0c+aMB2SJnkWD5pWMVEDT024IDoFtXioqBREDU8MrwUja4FNWCFrMaWygL2QNSXDmVHNY0DaUf3EFEEBCs5JslsB6ierHNKRWd8CdCVDI6d5jKQddrdwvDg6dDxVPeGH8lGtm1jUDUydo1Uka0OCbUqFdjZlpKAFJ581RpzWyQTsT7ed6pdQSWalRVMLlpv0dDJQcIZ2QT1k1Ipqha8bAdVfUU4+3gy1O7J847bu337RBheG1nWxw3x3Y+p6hutZ966O7TXfcF3TDlkJl1umg3mBbqyt/O/2sO6G6pX0mdA13d3EOiWNNzQXguQ5+LbyeZa/PU8zJxqDRjYlZLZAB3SXFCrJM6Fd3dLwuF+1npCMSsdfvvGT04Dv+WVU0jnIDspRBSpnDIDWBmKWrqnoXPTFVG/PmSS73CH1Rc17ZmoaaQ2cCVWf0AYjazMmMRfkR568IO3CeDxEjJRxWRNbUMoLzYuqUYDxb0gnsl7zQSXVNAwq44kCBcmNoBaM5hroCeGaqpcRkQQlkTmdhGXiKX26FJLgPZTUgXcDlSJuFIIATXqoX1ugJaFes1hpHUrBF0mQRxBCOCrvOicDpQMxlW29rVOSDYbzmQ4KjUS4x4Q2ZLRUfbUTExSNCsqGg3JPu6m+HkFrnZv3FKTndfxzSGeH2DaHRkdN/v23Aw97AdoPWoT8Kv3lSJ4hoN5bBYhGAbOR3Ul+8PgWBJQrSm2PnbOU2qYuSIRsEUwF81xO2RNkj/MFxRcodSchoZGAcUShrdKuwzPUu6vvth5/qA+nofGOGnuu6ekbKHUD5tr4QLq5rsvtb9vldrnMDtcKqnkm1K1PDu+yW42ng7HhDYBbkARJVDEgyv/mGaTXBSCXoNVXkm05JPkvweuuXUt7jFRcKlJ2bwaAbI0B9se9d945O4PGeXTN6TzXE6qoXGZCUDJoMAUtAs0MQJMHuhyIkig80obSWQvfUHLtoxps4bqtgpSZa0SigrNnTBPhJVdkWoDlGdDES1p0IvA15UE7/Oys6hdS2jpFEuSj4bJTwa6ZSkUxH+rX/RW+fKGPOEl9Y+3SolBf0GMptbAgZNUw3NaVpmnZRmBZxxvbL9Hp4v595UwYWlAA9djbKlnDPpj2BZwphS5mfXWfozIdE2t2Yf0Kayn35/4NN3UwXys6hqRiv/3igo3UbS1nM7As6mYQFISdNihBUT4tfCR5CrBezC7Vl8ogfyvOya5puUvkvSmB6pTU6dJS+YRfDtDG+lpSTZIgiaCbonfVUICW2igA5Ct5WI7xTzlD0VfaUQVrH2r9RYEbykpb1TkjKnneLIy0i773ne9cUfaC+8iTF5xNxaBVvUBgtHnUBNddXhOi1m27VxDgdNAcTpr5yYHXj5NskBW0zWtFDachPvSImcMYbZjAj51EO10gmmQUPBHPlEoQvJMfawU2UqYNCZrXQob1gCcsoLwWohYypJNy4kw/tk63DDQqM45NOz+Vp1aSdWw6MyAhCrUqVFpYUb6qQD1mUzaxPHoGBPkursE6w+WpAnKNlj34b3E0eqQ1HYeIsZ5cJlq8xmD7Tjzy0EPARZdhddyi1RY0qP1cmIe20DKE8mneFDw2lWzvt1lPIEl6QuMKVAp4DnCAeII6Kd9e/E4XyD7TjheUMZiV0MdjbyiJKF5IKLL7eg/QV3+DBvBTj8vbsHn9iYwgG1DGFuoroNIcdZ8xofTptLoAPCGg8ZDSUfqbbxuT2m4WOMUVY0R3vBE0piRLC++kjVbHYwnt9KhNftdH5URMlH+C6BEE65xC4ff0+NFrAcjhmS02A/s96eZQL2ou4cK8wtab9fj00J9fwEQLC5oUWJvUVGCYgpoXnelJkyMhrU1yT3gjK3hpHawpn0odIKNVIE8K2H7E1NsXyYEWmGSpnyC4v9OlFp608tuo1S6ZZbFCq9IFBUpLr2A7Sn765eCjHqcZfZMEeTTEPuXHYn1EkOUfCFTQpYw2YFskwf0YVNqfJL/AdXUMRNfJlFtUQ9Ryit9Ubrlagzi0sqzY7B30foReH0lnmUk3hck4w79VtszNwn4x3F7dm6hnSU8QnYIZoH0qP2LD5fpZtYyO7bqdvI5nan6tIBolKyTJFTXPOF59tzkdTNtKoHSzPm5jnwb51bIpBl0gJGVEkDXOSXorMZ13quOPRsDEJadNuzalwQm+HISNrthpo8v7+GOiIxCFWFhH85mBID0jo9YHy/xICDCyKDQiMXNaBqBbe2dK1RLe2U9O8VNBdXHefk5njM5L41bGE6Cq4UDhSZH0SA5NfKezgoz5RyZMQC5WBn2vQXWxNplMOa2g8iRk7QJE9Sl8LTBsFuo5pgwHfCNoC+Kz3CiqIsQETec0xboOWL9IgjyCsLbLIae89YrHSSQC2tDCf4cCUAbQop9CSWgNiaQC3xRsY5adbidmqetdZK0qlWyodOYcFMiIJGmC9J3XUdO+fXuBHdsw1Ln0WGV+0YZepTPpvC7Dcs4UlBLabKLYoGFLgliI6tfjUiCGA6Fh/ZWVBii2ue1EbZLmg/kdLFfs6ejLHre1LM+Sqf0JvrFmVdRHcwGbgv0UpI1lerfuG5pURyr6BDid1UFWPgmes41oozF2OsnL1DwNwmYo1K6z+UTUxxsOwg6ExsZ6JOFfnJjXzTunYc9tkJ0IbWw6sNiXXKfALYK+NodKGwX/HZN777lnICEX3MdeueCMskEk0epRNGtyeIIACV06uYImhyd2zAAFKJ8FrF3lbr6WhiZb1sJwe6NjT2makk18e+FHBwDpZv2ARlKSll0qQcZsIosHuF8jSUfdHlxU4N3MyVAan+IruVz22QfqSbksTD8OBEYJSFOWvt0iFT09gajqlHK2R4XrHwfrdjzC4rweQUdDeBEjsJR7sTvPXLJrY4AzpupWN2HIFwFpKhq+PK6+uTmdYC0BdhEcKG1Eip87vS3LvKMxbdvxui6SGA6sIuWzGTuoeVV0E416eolFv2l/uegsm0RT6SEgK73ef9DBQlGcoBTvgLX0aVPJ7vx2POoxQgzTsRMlCdcjqZZAFK10w9AH5LTMxx6Gf2GUmUs9fzmVlDB8U20JZOhLvM18bH7WGg/y371/+ZfDzcg4VZvHu3w6Gx5vLbagf99Ze6wQy6RXcPbRRBgDNJJ2blRZnJ73aca1jFq0Po+1S6b1c9BU0uAD5k8BXVoD2rWpXgk2oto9Bk00I+uM0rSO1XQ5qcxz1DzD6Y3o6dglqq2PTprYAtKTqlC7k6zO9MnUYmRAU3T0U0Fd+m+9KlTuiWr4pQzUv6nzlmt6ciTpKSHFTlH1BUNJl3i3tkpg4l9dSFCWXrVb+KdNbCEPIWthZunXClk+KVB73zgq2ZaGWjvStdLNKCLL7xn9nGVqo1pepIL6yMrlM6KqLF2CGlUa55ioMQwCNa7QrjaoN0F6MYcy5sn6SEeIuvWcVOjbZ+o8AWgDoKS+lFBeoguvdWVlHAwKVOaP6zoQacZM20k2ZStEXaYmgPzjvKnzHX+ajuqrRr8xImr5xT9do574Emp4DjJkUHPQNvZ0nFD5LwlqbuaYocYqy7JNIOHo3IAAABAASURBVEo36rpQSiOZru8EUHWFVk8xKbcwP2n05DJYD9PSSEHY1PmEjCxhG8s7/iRp7P3EJL9rG42sYwZKbrCulbSqsuZrA290PJ8r2WV1NRqSmdDEhFZ1JEWWscBz14iyyOjmsUTKPtkmEzamoStUklhHpS7Mz3QWXoC2tZowjV4UHjEtIbNVVqOMrPkaCjxpWLhe2EGp7qNJrXoll8UXPFEpyqwJ6EYu5DSRNQENs0z9aFj+0I4YznvHuPFR0TwgoGkyGqmljYDOzDOSJue0D0KZMjElAupSJCgfFOCAKGU0x5UX+yw/tsciwpptPs6QxWIFaVPpnE+qSEe/MOqxBpROejnkhScnopXtjfINGj21tKqfAC1WSkLy76AH2kEPR6jkqyhLKMNy28h6WbXWuVKqrWbl+/RoUlsHTgcNSKabyi3I4pu2CmIu9/GBy6yrqYrlv1yCuPNJ0cr1siiKDwKCAg+VTkVLBQGVJRsknoOE67nMwTMoIFMosuSLMtYKXlnpLKapg7ip82LB1JhIWLkpiNqfDScY55GscSngNv5pohKAb/aGfNvqZtdIShJcpxV1v06b5/Q6lS7ukySsq8fR1CDF0yiTx6fQRalCEpEBbmvqtoiaHyGCoS7IjEAgOjAG+LJOTvlpsciSX2B9ZWVU39bVdaJeSBpOm0fSzU8K/4LD43zSSudxof12Hqt/YtU90Nu2bTtxhbWSpDmg+dfl1ijKWvZkiio07CjntY7gdNQChharamqqaQGb6wm6hpSpwKvGymeVOW9os6Z1xtIGKmtzjSb3hZ54gh51IV4oVNPXItSPdfAZb18CjHm9ATWV+jibq9i/QUhQf1H9OUD5TBjjBljVU8dwgjBugUmLVuf741GjpMqCeAoq2f8ysx0DCtQUYs6oZNFCb4AVn0EvbkPbNPDCk2tAnUFPkM5G9dK2e/Jo9KLVabCBF+xqnbAiTKoG0zL1zUZDNlFImGBOutQKcivagR3U08FQtCUR5ZBKqAWPcZAvoPGDlTYVkiQkBatMlIuyowMUPLN2qc47PpmaZzgdmGG+aas7nf914KiaYNRrsNKbYLUeYyhbGukKlVeSXam/Sp2VnbV0JDQvqFtdiIDyWAuKnApGhi7zVEbdOIxST3mX+WbRKmh7w0DpuxGw7sfwXG5+B+eNrDEeyg9D6TGWPg2CxpfI9pn8ByFLb5gvEBFRChh1Cz3hAISmTM5ql0CyIEk3B2kVnfQj8ajnB/D/yOKkFc/jwnAe635S1T3Ahw8fPnkdPcpPd1uaIEp3le2UAk0mraf1YB008ahKHa+sBC1yqK13UhuhOat3lAHmOX0szCeJoBeZIVaI2oUEHclQj7XQZIYvavKqjue4J2OB+NP+k2ppUrt/8Tb1kd5ulwpNSgpraS21spgGUny+6mFBu5ueeqykU/mXZX3lKoUOKRPZwnxVRQihgM4wYnl1iPlL/SuOAwi9HrJs9VGIdz6sFGTU69l8iATKB0GDEUSZLS0hy46sDJFhoOSTChOC2mANtr3zr6kqIGR/Z6i52qYCc6ZIhWTJc0JP+khqmCW1A2R3iDUMp83v6jjfgaT6SmrZSpsWlpnceQQ8P7DWR0AGyTVoTnGK4H4ViJNA+d00A6WevvSZtoEur4fjAZrTuUwseWkDpfguM+3Ki6164jOF64KSLF8rwNqfQZ1TCNpEMGWpL8szZOMUJEEKazzXlYApT3wHZoNUHcFlp4JvMr7hnKre+VoezlfFT6W3B3phYeFU1aC5Mp1rrqkZE5DgBa/kdGKJbyc53yFoYVB8T0poEVEBKoQkTqPdw6TAecWvwoMe/bs65jttZEwQJSh6wYUML0qqEy9UP/ZagXHMGMeEUWXkdTpW3r9AkCrqY3Mfa5y1A87ercmGFBokpXOYFGq9g3Tvq39tMsF2IpMbhCor2AJZtkedzdR6k16ACkGBOetGMwkVjLr8DnovcOmlOKyFuypVx67Tn8NYizhRDhBvMx+5DDEF1IJ/O1wlKA/tzIKOL4LOdSmgwHWhS+7UNzBRYiK7/BSyMEnwv3qkdqyt3gNMBPsC8kUsTwMNosqCYB7kK0CdSZL1Tyo1bPdGQDfdjfxj00F+W2h7WBgTvRFQN0RPPqzVLopCQdLBMWuQk30llCArP0o1qaCNhXabCpHSTGnkQpuc9ESWSroVr8ub5kBQN8ZQV6h0k7W+1pPq02lT51UJzofYQ5e3/uY7b34vVtK3wYAjDDSXe3qK6stftXo2ep4f8lVERtA4U/4iAsCAFKY0A6i0UKI2AMm2CE6bp6JTfir5pt/vn7Le+VohnK+Kn0rvWoM+GmnWn6Ji0qxxoAE9VaaVxdqYnTLXvjUf1lJHCOkWUGDWAtFLHN/VvZBIwpPO6SO1pynzDOiFD3TmaJqVTgqOWZO6VbAEE5LQSry1K3BaKHpL1PH0EfvMP5JpP1heUmtT3yTKos652FHO1XV2rJissE0tyVRsDmaoDbz4sqdUEJ9QHMHy8hAXXXY50r4DqKs+ghamg2XSMUl5kWqjStvNfQUpyrU+gUr+CoDy0VDgpsoBFh46/VTWBMJdR31FBTTFakwv2SSfA/aCoQoq8DisIyQkyieup+58Y1UVeLzgS/ycs+zXDW0tTxKu5zFXibkgqXuwbmptjdBGBB1rRR1vRd3gyAhIX49zonS1vgJJkFTJVC9ymofKoMvmGiSVAxzsDPudZOH5i5ymAxKoIw3rdSx1mfkbqdtG5jW5QW5qAbUvkKxWwTnpmCSLV9pO1VQfbgnVkPVq5rllkFyTZXtRLutrkFMdC/MEX37PQ5663gmaP+HZctUTXsdNKejA6EfxUzdO61WCJpPm3nq+JLSYXcOTvkDMVhUdzAC5T+VItWbeFAEKQgJzr/A66jpOG05PQTgwB4wULIbIHCNpN5LCWGlNfC10L44SQKSbd4hGTNRiDlDr9YmPTV1B/UcwSVft5pAV4BTkWkY0ChJGVlBtqwGyHtmreqDdzgJy6CHp2MNljbRoqGAFY02JRAWcrJ0ssE075eWH9yHMLyCOxujrHHugm9gigEpBmmqn5KY+SQszaQyaNX29M2+kt/WH7EmyZSRdjSZUslVQfRiyVRqiZZgidCoEMKsqp0jqo1V6iqDxmdY33/Mh6GYadKsKnIB5jCDqPHTTze1Q3hFvrdx8apeJNEIp143BZ7eTMIfMgXjaCSpIo4lIOUiviCS9kx6zsgIwdXOTOpDKU0gvkiCncBCW9jBIwvk6RKyfXYsHXQ6crcYg6UU6tOOF9O6Qi25j6XI0KBs6uE6Sba3qeuwnkjuJAUPp6F/8DKXvSGnz7d8Ezw0hZ7RImlsJEyZ4HSUCSbtmg2SxxWlDqp76U0dJ1ICduuZ5WSOcl1qfhtKVdtD+e8k8jbrUEGuaqGaCF2fYMN6eQF6Inmht8KSa1ixVNMkAwhPegC6SZWEELSasXSRBci2HkiZZFlIFwo9pUZTq3AvROwuUa6qPd3nqGkGKhDwdMiU17SFM86X6GX4FGRFTQBSFqWAepJl1aCR6QsDnxRPtkpP47rfV1nikxZ3Ue9ZiTFqMWfZk1bUKtiPKnpiANB5hYU7BZ7iKmpLAViWNCiYIaiuT3WTTyJwGzaT+p0KC/BQ0jkFZwro10g/q1SjjqDaUH41smwQov461vLRFUl0oH1QeMhFSgH3mtuZ73MuOUU8W3jVSQVfG6aNQpJ0kpge20kfe8q5S9Vzf/BYZY+ndeK6onyK/3AlUtwXUnQJaRoNcaIL/y2W+JQU7992qP1MDa0cgJbhpjLSFhwOx4XSpg+nldJZUBHkgAh4zKt3RqUp5nd/lIR3crtVxl5+o4F/1sC8Ne6AOPMJ6ukbWjTPZCPnP+vppLGGqv/s3z7p6nRZZmF5Om+eyKefE3yFK+RMXn/cl4by34AQGeFJuDJInqIZKHiCpU0RqEeGoS9MTtc7pVkdDeBfjhRL9R+AJDCdDoPLkaFBJSNbiK5ObWYtiIjkJUQHIMN+LsmnGMPVkN6g2ediiH/rqu0JOEdSOr1UQaJJEaJdHRkRWCnQTZC26oBuP/+8qkoRUBy2xrIpHPp74G3Gk5NEpmYHcTOBfhyAE6UEFURQevBB1zNLqjNrMJql/9RZkX2BCpcXsvxjYiJerAKmqc88GE+2qsup6Z+m/AVzr7eFw+RD896DH2nWlmPS8MEbbT5gEPTlI1qM1O8JJKcH2dBynO5iX1J4KgtSOsKdAUI8T+gqm/UT45gcFBDnXH1dHE4BWiAps/lsQUfWYo4JuB6pJREv5XGCsETU+darA5YRtYQH9SY1B6iPo7qWukRgQ657sVxdBbUE4D53RJqVbIehl8CRl0Ly1+ghEExNaPRZlheG+54vmSE+Bne0Y0gAhZiAASf/ZbvgiYRu8eyUJSK7nBjw/VN075iC+SjAZj6W/BCigB5XVIYKqb2RGeJ4N5eNG4z9Rm6T5tSpfjtWPeUaXHkmG86ZtjMg+n5YfYjuH0AxQNT30mr6ejCpocNGjRiTUUitrXrS2ALV2vNBNqhco+zJInhTdGu7mAUmYRxK+fMQRpYvTFyI0cheiWVObpkM4TZ/ouxt4xUpVCV4LotCkiYAm0VDn2L35OVS9qMncYmnlsCYIUH7Cp8nvRaMlBq0S1c+IWmTelbTaYayOVuCA3Oox0tT1HMi7fJuJ8UiBakzN2aAJXIPQpNbENgWkwyQrokDBs4daAUMCUamPbpI6+Kvz8iEp3UIBSZDEqa7FxcVpAFxZxfDwshZaghcPpY19wwg00n/cjOAbTEBWeZSJsqZpgEQtQFitIqeVuo0W8gRJIUfQYq8HfWBlCfOLi6qbUPd6GGsHTtknF+BkV2dnV4dksW/KB4L6iYo8PQDR+sQWYx0vjHWe35ZBTUVXSidId0Un1UxwgKbagrnknTaUkUyNo9jOjzT+HsdWAd12NOojySYZgp6c4xd6tY6BzO/pCMgbgy4P3SgcPLr8o8rVGXUTnDTLMF1e2o9+DbQKznUv6MllFYkNWsibuhG1SnlMOlgHSk+JgVxgAurbECkf+8mJQuUD6+n2Zd4qMIcqItZ9jV9C1RsUWutYym0QKszPz6M3mId3tK5HEnML05+vNk0rj0orzfWaACVvvqpRyVd+Kmw1xu43ax319RTltP0ZGco88084O71xgsu6ushtSZY5Zp5hG/qaS1k3Jte5EBEuRKOOtenkRh6/tAscQSvGj/jLKyvwnzDt1xUm4yHGK8vAZAKSWkRAKlM1w+0Us+AFPdZOwXlPUATN4BhAwfnC10RFvYAUF6TyvIToLFLn2dSupMo10Eb02NOEj+hrZ+1J70mtTRfySItWiJRcHLlIIgQtAIE8ugzHXF7b+w8dRpnf89uwOFjEXKwR9ZhNba16WmwOOBKFvp4celrMVOU8blAnYD4OUGlnWeklV9SZe+AAlJ6NZBijWGFcVRhCeigILI9aDPoCnZKTAAAQAElEQVTbMFrJ6IUFhLYCczhGq6OzMcZiT8clJWstQ+0Es4JDUEDuadfuc9Q0RwzngdE8kQdUz0nBS8oq0EGIutlUAtgCnAjaH4q2YQLDPGKMak3mwlyFai5iPABGg4RDsmZcOzA1kq3xVlCaDCVHg96MGlShRqtz9oAIiud7xInKoe13Hi9jXrL7wsKuPg63SxjFMZaS5tdcwEQB3LvsJmiGEcUXFQMG2n8OdLzgAGcQkD5CfjQgP5EESfgqPtU8rqoKkI5pkhCkr/U0tf4eF/OHKyOYb7tsn+uPV8dwOdAiVg1yOIy6P0KaHMB8X7LSEP1+RtYtepLGGOqGM5at6qr0Nxf7mEONhdDX2OCkVxeISa7r78DsRrZbj7Lw7+udvxARLkSjbFMZPCdOgRACAiswTl2RSv1Q7tRehsPhEHVdo9/vo52MAe0aFqoeFv14p90FtFiCZHjyeTI12j2QmkwKZl4AWYGZnOajgg1iAElNavHULs7XoI4qfESg2Qs2Cf5jSb0QISW0trJ2UhOs6tHXj7SKnUWfqgro1z10lyetQbLIN9950xOhDQD1dJDn5tSXApACvtaSAmeWHtC7rAmsQ5TevV4fdaynC0o7scoBotJuyfaDCEKltOvGaB0kg1nrOSPWleQAjbbXBw8to9+bV2yqUFfulzjZFWME+eg6ti0rQMgNpe9W6ZGCwUSBwP+rq9VmiJXJSIEERWedGsG+87zwDS6p0+lxh3QMLZrYIom2oll6ZybpnrC0uoSxbgITBZtlPUWwF9DzE4HaZ80F210r0GnYMTcYyK6MnuYLdAc2pbQ7Ufmg6mMePUQdcx3YdwCruuEPdbxRLQzg+ViFiKiJFaVsBYIkMqaXvIKgPKfZk3438gmpmoZrag6a2Iemfe2UZS7mqh6ybsyDMs6EnxBa34wR5ENiXjfZNGlRr+Xn6h6CbozzvQqrerKcm+/h4NI+9EX9xFXpqTNoHfS0dho9eZBEjBVaPYFMZKuBU1yk9FYd62ooWfwQtHYMr03PO/MvRIQL0agzskkDTbIMOsnSVOsBSUHIEyIqQDjwepuZ9cg2p3ylhdkcWgKWhtCzOuhFhIiQA+CNmfIVK/S1AJ3v4HpGl2+VGHMVKzyINh8C8zKoHZR3b1WWbO1EwgAI8xGjXotxn/DfxBhHwCcfaW25Wk/DypsaTp8KfmH2iILO8qCnqhlZgTRosfbjHObqARxAagXi3GQdxTRotNMiI2KoVT/oAWKi+C2DtdvUll68kXwwRmxFFemDbipB8rMo5hbQl8y67qNWgJ5I5uHVidoE4cQf+972bMR67RzkL0KbWAwdqfW4O9BTx87cx2KYx0D9MUW0OmMeMWIiUOVZvAkDRgXAKEBlUzoiMIwZ/kWCEXXzqiS3ls15nBUwK2Q5vxGi5cs/SUFtrj+P4coQtW7cK0srGsuARsEtaF6cqDwPE7a1ixiM5rGtvwcIC+htuwjDiVq1fc2vhEWddxs93001r7wB8E1o1Go8FOjymjNMCwjo3rDGBRhlHMQTWmQ9JUBHQC2Wx0P5JUFfsJ592dLpb1rJXqi4V/Vh/W3X6vIqbKfzLp9o/KgjuqAnowpBN5gWQ/VzSO81RvL1WGn/Q6WooGxZWX6q5J9Gayv1akykm0xSrRN/qkqStUa78XfNoLyp7Wl0ZzydQO/65yOmo3c+av4Y6OzJ4QCQNHE1ZySRICNIwpPA8AShHhHrEMvOgVp0See1lyxuA3bugqPDVVoZ11Y1rq17uE70GtX1/7n4Gk3MazfA+QKVm3+9Jt9Ta+KGCNyglyc39CpcGyOuVPmeUEl2RvPQw5gLBDXDs3Z2Ez2ej7XL8i6k0aN0thEASMJXN5E7at6JEHPCFTsW8dSdi9AqxZW9OVwMYrd2upc3wFXadl7HCk/VorpWtl0p2y7V4rhMfT1FO6Pr+j1cVVe4SnbY5utijWsYcK3bsFa7GtfNLWJueQSsrmKgY6FBSlhZPoyqFws2BhMc57Kdhu3ZWEwSHp+sIJ0UJCfScVH70Wvvn+DZH8+49VMZt3yKuOVzFW5+QPh8VdK3fK6HW5S++fM93Cx6q/LP/mwPt31mCudv/lwfz3rA5T08876MZ91H3PGZOdyybxt2PEIF30U8tDCPj22bw0e2DfCxHdvw0e0L+PjO7bhvxyI+ddFufHLPTnxi1w6Vz5+0/EPadX9y9y58aKfq7tqGf9YceHD7NhxSwMzVPNhE1NrqR0PzzP7KVQDlv6g5gxNcpR6hG6huvKrjYJaQQY0b5LssXty1Ew9sW8D9u3fi47Lhk9LX9FN7duETssW4T+Wmhvkb85/etQv3b9uJT+7cjfsvuhgfV7uPX7oH9+3Zjk/LlgcWFzDauRNLukEiDpB0kynjGAN6ekoIfc1x6XGyT9R6CEH2SueunmUYTFmmEIPBoCu64Gg47yx6jBT2BLYoxQvFWE1dRWgP+hRUkaDFD02CyIB2NETWS8FtOizcs7Adq/sP4W+/9/vwz9/7g3jPd30/3vfdP4APfM+9eK+o8T7x3vOd34f3q7zkVfb+NThvvO97fwDvEe89qvOB7/lhfNj47h/GB777R/CP3/vDeN/3/RD2/d0/oL93H3qrIwQ9JkYFzyjlF/rzZUcqLcskJZ1CWZC+6dgOcsqTMcf9zGuns+Mzn8aBP/lDvO97vwd/932y594fwsd/6EfwwR/8Ibzne78P7/ju78E/ff8P4n333ov3/8D3473f8/1493d/L/5Rdd917w/jn+79QfzT9wmy4X3f84P4iPT/yHf/EP75O1X/e34E7/j/fR8++FOvwoNvfCOu1k56+2QZu+eBcVpCqsZI1A78uNpNmbbFqWMXqXkAwaidZjVAoxvC7oMRN/33h/AFb/88Xvq2z+BFv/kZPO/tn8ELfuszeMnbPoeXKP+CtbzLzHvxWx/CPb/xEL7o1x8pcNq8F77tYbzorQ/jK35nCf/yN/bi+b/yWdz8+49g+7tX8NKXvAz/8sd/BHe+6Sdx40/9EO5446tw1Q9+F+5+y2tw5b3fiRf8pzfihp/4Qdz4Mz+GZ/zUj5yw/Jmv+jFc/TP34rrX/Sgu/6nvxm1v+Uk897U/hhf+hx/AnntejH0KahOd7aOtdVMIsjYAMSALUHCmgrlinnyIdWRAYfgIypNgICA4SLt9ikTo1wjXXInn/fgP4e43/Syuvve78Pyff906feqPfj9ukv43/NgP4I7X/zSu/aHvKeVXff934DlvfjVcfstrfgpP/8mfxM2veR0u+cEfwC1vfj0uvfd7cPcbfhY3u/6P3Iunf+3LcWB+EZP+nMaqJzsyWq2j4XhVR0erRW+c5PL4ey67CkmTMsedIIkog1eXV3ChXhrxC9W049ul2PaogiymJ4GRNL0NpzNatDp/DEiI2h0ONMlGozEOHziIPbr7h4cewvI7/xHjd7wD47//B4z+/u8x+t/vwOQf/gGTd0z5zrvctCs3df3J3/8jRn/3Dqz+9d9j+Lf/W/gHLP+d0mrbvPM9WPqn92H8qc9gWyIWdK7ZC7FM8OxH25zLEYONIQlyCudPF0E76O0E+g8/gkPv+Hs0//gOLP/9/8bBv/kbLP/N32HyD+9E/sd3Yfh3f4elv/7botvkH/8Bzbv+CRPZvPw3f71m9z9gKLvHsmX4v98pm/5JfFHxmn/4JxwS/yHR3vIyFqqIpaXDYHBgNk6uLUkE7aAMUsoC8KI1PEZ+EasjVlA+WhwGXPTxJVz3wSVc/8FlPP1Dq7jhI8KHV5VewTM+OMQNSj/1I6NCn/bhEZ7xoaH4Y8FUcF54mvCMD41xw/uXceOHJypfwRUfW0H+5AFcd91NWLz7OXjoKZdi/kXPx/iWG7HrX7wEjyjg7f7il+LAU69B/wV3YfmZN2D+xc89YfnhG6/H3D0vQHj+naifdwcOqV19123AzTdh4alPxWhQodXTCWU/GRWYqDPiFo2OjyY6Xx/p+EjuOOojNxyVJ4kYI0gWv2W95G10jtwoYGftgHGX9HzGDdgmPR5+yhWw/gefei3mXvQcDJ/1DGz/ghcWuvCS5+Ghqy7HJV/2RVhWfZcfftr1mH/Ji9Dcegu233MP9l93LXZ90T0YPvPp2Ck+bns2Fr70y7CfGcs6cw7a+UfdHKCr8WYj1Eqd/OPjC481OR37rjbJMi88B+Z0DIUL9LpgA7QXtAevGzenOyRkTfbcFSGAAhAB0ItBgOoEBUHNKdXVYZx202PNfv8yodZr93q4ip2TIXYMx9ihybdNgXtRLxR36Jx622iExeFonb9dO4bt4wnMdz3Xd35R9Xfqzf9une1uGyfMj0cKxkKzjB3Lh7BrtIq5bL0itHHGRGd4dYyIpBZpC8YgLSH9pvbYPqxdJEGuLUrZscY+qm5GwMg7UNbYow4uWlmRzkMsKgDs0E1gu3btO3Q0sXs4wS7pt0tHKtZ/u3i7VsfYI9136rhnl+zdLRtdNqfAMaed8oJ259v01LF7JFu0WzKvVn9Dv6mr51DpGATyJ/WEYt1IlgVHEhsv22R4kRpOHynPqEKSnWPUuplGRWo3l8tQZflJfdVNRq8giSZE2WnUDXV0kAuibGWegHms8ga1zncHes/Qkw2WnzFCxETaj9EbuKcMNEG8Pg7qSWp1aRUH9x1UPuKQ8qOVkW7ihxE0Zw4fOIyVwyvredfzryJcTxri4MHDOPjIPoSlCbjSYv+BVYA9AJqlOs5qqhHGcYTJ2pOGfdiT3CAVZLJ0ouyn+poiywFJIAAjq1KQrCRb+jo2iy2K70dK6y0KtAPR6dOo6AFJ27//IMY6xjtw4JCKMpxf1Tw4pHcuQW32+WWm8tY7E3opeEDYj6UDe1Fp7q/uX8KK3s08eHAJk9UhEInWxzExI+udyljvJ6pavtPxWdD2v9iBE18kS+HR4w44X+aDSieyT+R8+JyxjuGMW5wnDfzywINIEuTxYVMigiY3cGygCCVIQ5dXgog+WXUbhhIUawWDOS3ivhZ3XwFg09Rt19BT8OhZnnbt81pQc5If1wIYtCilQtG1m9TWzLsj62qQdJUC224cyycJ8wwwampXmu0B7mteffbVv3UwnJ6TDlNkrNupOubNaydm2sH1e7Khls5H2k8kewLzrLd9aNge50mecHzIaRl0kSx6k1MeOaVSXj7JsJ8MrF2W7V8/dKgVmLr0UTRDo5rUSgmmko56sqjW6o91k2l15k95ChrzsW7Kq7qxQjfLnKLaBeHEH4+B4XN0ksVWp80zupaVAlYUkDUenMpMoUGWTokKbpSlQpBu1o9r88EyDFUpokiCMSBo/npudHxLDBnyk6sFtAy6x4hL508MkkVn13A/pgY55Se0Ks+oFYijxE1tCOJWGEsHaKfeUi1kR1k5opAvqehO6QO4EJu+suQXMZuW8MRuKJc+sRXcrHaVHg3r+sgjFEmQRxA0eQyS5W7cTT6S6Pg4Ty6Sx9XUU1knhQAAEABJREFUNnmR2p6uAnmkrss7/uNFrUOHsiPSbr/Lm1ovkkeNHTnNu2yr4cdn+9C6uS+/kFrwX0nU3Op45p8M9r9tMTW69MnanG6ZZR2vLsnjsc+K574McirbaeOshJ5lY/dvnKWYJ2zzCzZA+4xroqMHLyLDg2h4JEiWBd8tFnKad5nrdPWdf6LDuhrW27C+5NQe2+fgQtLso+C6xlHMxzFjXY7F8dQhH23L8eo9VrxlnZvbv77Z25+rOt7x3xn3C2NvAk7Vj21yO9dz2nDavC7t/GZhOSTLfLYMyzSss2He2cCyOhwrp+Obui/DaXKqj9PHtjlZfjNlHoMYLtgwhgvXMo22Jww5nSzKlo8nTQeXGy7oJrrzfgQ1Nf+JDuvZodPV9nVplzm/EV3ZE4GS0/Gx/w2S5QmmSx+r98a809jia3FxsejT3ey9o962bRuoHbQ3AafqvtPR9nheGU67XVfm9NlioyyPeYezlev25HSMjk27D9tCTp9Cnd9YZ6NO5m8Fks69T2cctqLvcyHzgg3QvrP2er2ysyB5lC89kTp0BeS0jieVy0y7sicyJbluo3U+Fg4Ihvkb7SBZAg+eAJd1Ox6OVa2rcyx/K/NLS0vl1w+eSw5G6ztoPZ15jp2qbz/BeD6ZdnWdPpbXlZ0p7XyykZJH5sSZyju2PjmVtZFPTnnklNovtonkxmrnJG0/npOOHqdOLtgA7R2PJ+3p+rWrS3I94J1u28ezHjnV1zqQ0zRJZwu6CexFRB7hl8InwJf93sG6dumOkkd0Nu9cq+w/FuTg45uc++73+zDPO2jra97JQBL+A0Ekyw2xG4eOh9O9TlGP5FHzlpzmT9HstIrtd8M+sM1Odw3H43G5gZEs9rmsA0ls9dXTk8x8f7DV3Txu8sPj1vMWd+zJRE4fvTxhNnbXLZKOusx1DJLwgiS3fnLhMbqs97GiSK4vWNtpkCzVvMiM47UrFc7hF8mysEmu60se4UEXSX0/+kMen//ompvnOJB6LlmCfeaA5L/PkrWDNu9UcJuVlRX4Mdz+Npw2z2Wnan+qcpLFb8fWcz/H8jaTtxzDPjCsu/U2z7AvNm6GzHO5+yJpsqXweFivLe3kcRR+wQZov9TxZCH5qAls/kbY/yRLoIAuTzCXK1l2B12enMpymeHyrQbJdb26Pjt9/Njd8bB2kY/W0fU9iU1dnzxSZ63ZlhFy2tfGDsgjPOtjvTr9fGzgX0qYmufyY2FZ5rnc6Y0gp7JJFrbruG7J6Mtpg2S5EeMUl33sm5vbmDpv/byDJrkuw+UOVD4CMYUu89xm586d5ZdCJNfH0jxyqiPO4nIfxkYRJMucJ7mRffx0nv5IrduUeCxIlnnvl6HW3w1dbtsNksUe8/yLFvNcx3qQLD4hWWTgNC63c7+u6v4MkqUP804Gz5OTlZ/vZRdsgPbAeNA9+IbzG0FOJy/J9clMPjrtQO+JSE4nzPFkbZT7WKcdYDo7LJtkWeTWyRMZaxfJwienNkCXdSWneXJK3YakSlHsxhZf1qHT3+kOHc/6eJHZHpJlp+ldmu3uVOvaOO/6rmuQUzsAl0zR1TU1x+PnNuS0Ljml7t/9uM7JcKIdtBQtAcQy",
        "3Jf1caDyEYj7dJ6czhly2ic5pe6PPJJ2/mxhHTrYNmOjD08of+0XEK7v9l09j4lfiJpHTnUlp9R1yGm68y05zZNTm93OcN2Twe07kCxzkpxS80/W1mW2sRlPnLwgccEGaD36tF4k3ah5snQwr0tvpN0k3cjzRD12opDTCUTSorYU7tsgWSavO7N+ZWI2jbNHgWTJk1PqDMnSljyaumyrQR7d50ZbSJabinndWNku70A9FuS0nCR8kVyvT0559gU55ZNHeOZDl+U4baps8YP7I6d1zTsZHKSsW9feu2fvGqGzz47XtSdZ5DvvMtti6v7NM90Il5l/NiCnfXY2kdM8OaWnI9s6uR5Jk3LjISkT67IL7spNN8L628YOXVkRoi9yKk/JE34so2tnemz+hA03FPjGuCF7QSUv2ACthRQ82CTLoiGPUE+EDt1odvmN1GWWYZjvPDmV43THc3qrsLEPpztYJ+/eNvbblZnntOlGkNyYPWdpko8aA5Klf9thdPqSUz7JEoyhi2Rpr2QJHl19tyGnZeSUdnVMSZYA4/rOG25jOE3S5KQ41e+gyakMy3Q/htMWSk7Ljk07b5BHyp0/W5AsfiJZfOegjVNdOuLo9HV9w/mNdlgESZOjQB7NI1n67yqR7JInpe5vYwXnO2zkHy9dxwo+Vjpe2YXAu2ADtAZt4snWDRJ5ZLKY34FkmVQb8xvTDoKerJZDTut2k6fju2yr4D6Mrk+S64uPnOoDXa6zEWIVu3LOJah17c3v0qbObyXcR6fXseku3+3ASK7bhrXLbZ0kaYKurvnklOcCksXeY9MeSz8FGU67T8twe9c9FU7nd9CWaVgWyWKDd90GOc27b5LwRbLUIad5nMXlfg3b02Fj/ixEl6Yki64n0t9+tZ3GxjrWwcApLpJHySdZxpGc8nGKy33OdtCncNITsVg7aG6csN1kIacTwANrWHeXHQ8uswxTkiZHBTtyyisFW/TliW9YV3LaHzml1nljt84bG3kb0y4zNvLORZrk+qIjp+muX5LrPjWPnJZbT/vecNplhtOG0xt9Yp5BTtu7vAPJEgTIaZnb2ac+K8YprtP5HTQ5lUtOqfXwMY3Przv9zXO6g/PGKbo/ZbFt6UBO+3cjyzacPik2tOl0I7nuL/Msx3C6g/OGNzAdXGYedJEsMnCKy/U7uKrTnRynzTsRqAL7mHoKUPKC/FywO2idQU+8CEkP46PHrht80w4bJ0bHI1mCiyWY5zpOW/bpLHDXPRu4H8OLcKOcjbp0fPKIrh3vWOp2HY9kl9wySrL4j5xS20FO0ySP26993OnpHVrXxjyS6/Lc2DzXN5w2r4Pz5nvHbDjtMpLllwaWi1Nc/s2z/e/2ruqXgOb5VxyWR071cZmxsU8HLueP5ZtnmH+2IKf9k8enpyOfZKnW6UROb5q+yXQ8U9trPzhtuJHzttPU5eZ1ZeRUrnkngusaLje1DNMO5p8MXoOue7I653PZBRugNShHnUErXz4eTE8Cw5OKZLnTk1xf+OQRHjZcbmuY5cDhxer0VsI6TlKLRruEFlm7zbXeoobOcJap6B4iCqX/bBl0rfEhqhw2UpJI4rcSYySiXCaGM+Z1cN7o8h11XaMrMz0K0ttK228ngvUK0se01EULy7Q9dVUhVix52+V6plQnOeklaW5R2qRUzpvdh4pkGyUFYCWnAHBdI6me66S2RVtesiaVTj/MAQZElYAxGq2ibSfIzGjUdjIaYzQcovwOWv5LyOv9Wi7JEvz92K2nuJIOIZQ5tpHGGKe8jKP+3kKWTAhBOsRUwdp5fNqQkNii/ME7BkS181/lSylLv7SuA5SH9FQxfGX6G5D68JXVNq3xyjRRXfOtG7lWIIaDro4JT6m/7Syo+ujFHvxnZIN0T/aLIFHFPooHjWJWLgnQ5d6Cvrq+SWJjmlQhTn5ZTwfpk9c6f0vD+av6yTVXAG082K7lhdUtHpJw2iCnE8Bp1yOneafNM5w2SJbJ6oUFXX608u9ElQTJAqeTJrzhdNe/0x3II3XNo78EL5qs2RoZNX2DONZTgUWLOyk3yUCrtkmlCkkwz39WUmEGDrSG0x2cN5w33QjzjKxF39+5AMxNA0FuM/y3koOo6/svX7YBcD9ZepSbRE5IkUhVQEJGbhUkdQOx/ggsvvVNxXkvnEgJUNBo3E7G6iOjMlyOGNQkK3gkQDKoOkEyJR6mlt2MRzCcdrnhukZQ1Kl1A+gBiIyq10DjDvfVJokMESJlbIKCW2XZskO9KsAl0LqLnySHimRMESFX6jsgS2ZmQgwJbrs8WkF/boBKMrcvbof9F2rV0w0iK9Il1TVFVL9KN7mBeYbThsupm42p82ntBhNAGFIBbZ0hh4ByUI896VGhCcA4TjCpxphInwSgaolaCIKy5UM1DfKHNCh9t1TNHMB1UO2Bronrq2N0+rRo19PWc/uu7UWOdTVcz3xT561/lv3u3H3kCcExEORH9zGhFKL6myTEJD0mATnWQF3DN8dKBpMsc+bYtWaZp0JWhVhX+ga+Dl8XS+IC+woXmD3r5ujuH/13e0mCnGK9cEOimxjktE7QAia5XsN51/Gd2nDaPAcC7xwcjA3zSSJqZ+QyBycDaxdJuB3Jog90dW1IAoGYAqU8gCAJ/zEYp/tVjX6sNPlbsEmoQpz+k2PVoaJCPAHtVRUq7Wxq6RWotO3bUP/Q/gMYrw5BsgS3GKqStm7+v0FHtetQMcC6kITWlhYvZG+t+hEO5F6UWYGbVUQQsmxysLbdVU+LUgE4lQCCcukYqlAyAqqrL5SgL+H6qCyIRXCt/NGUaNIEozSGF2tvMAePEZlR9XuKA3VZ/EnB0oE9K5gk9e8+fNN2QFIn5WPZjBVyiEhCsUcBy2PY7/fRn5uH//WfA+LwwBKquo/xaATbFkEoRoO6ERlOG8VfUszp4wEIICmoT8gW+ae1JfJF1Bi5L4hCZRIOqMw3DSXKxzvVksihEFCdTVOP+g5Th0pCgJNuQdeSPzbq3NkSLEp3OdMTYdrcFRNIImicSEm1cNngQI5ehZ4wV/VUToybCVptYoLKN+qbpUcS39TAaV6j8Rit2v4mfrM9zSbnVTW78sQKn8clerxsyu9VZQOpSSPafUiWCbUx7zRJk6Pg38H2tUArBTpyWu6J5EDgAENyXZYnlssMBybXOUqYMiT1jaPauJ3hxendn/aXWosJ1IStVD+OG4ThCJXonFZXT4EAY+2oVoZgI3mKjPkEVPFLQV4SJxLZAO04I4uaD/EW4gDzoY8AKti1GDuISWQVAmpxc6MwpuMA2wRdlep55+PgY59oo6cwBjRaJBOhQUbZ8SnUjiXLkatVh61qTdDAi9b5OhJzvb5qR0A3Doa60KyAlBRwDPNbdXAijFWPiwM0vYiRokiKGa0MC+obCgTDlSVZQJBENnTzaGLQTjSi0Q2kVSD2DjeovgOfd5yTihhVwET1GvlgRUcah5dWUIUK/XpO/g4YLO4AxglzoS9tJV962NSNoHjBpaJOHx/A4aUhhiONpeJcAxY/QukkzbNs986zagN6QqU8JS8T5YaoDSmstyYKTvdS0w1VE7Ams6OU/MgKpht5zj8aEsUE65BCQlslTOqEJjbSr0HWeGP1IMbDJYxHy0CeoIoJdS8j1C1GkxUJSML04zVgTHOn900S9sfp1T7/aoXzT+XT03g0GlXe8TiweNA3whLII1PVZeaZGk538N8acCC2nBBC2TF1NMZY8iRBsmuiI0BNUp1vHhugLdtwRVO3YFYuZ4WvrN1ei6SUIW75OAjmoJo5wUGx1uNhXUc48I0UhKxLZIUTgTmUxWYaENHVqxQQa9bIw4SsoN02GVqrUAUEBefOU5sAABAASURBVC/rlScNkgK0ocgH6MZgfjBdQxJ1u8zpQpE0tNI16ebim1QVInIrrmyM8pfR6JG3FSgLS71MtZlCDwcw2jWelMGJQMlblY5jHRW0UmysY5J+v9amrVbw72Gh/L/q8nRs7EPpiDWaTdW/4rq+HSRs/9T/6lpjkYEcMNefl7w5PZJnBZkGA+3SDz/4MKDjjtFkDHVZ6no8j4Vtk/CTfgaDgWQOtMvsoQ4aH3C9Pr0jlRoxBR1phHJMoHsQfNnnYk/HTAzrLFI+oXwf/eX6HYeSaRTb5ROSINkVF9rZUjIn+SKn7exBj3vDVjM4IyOBds78glzVL+vEYjwnvIvOMSDoqWqj3iQfpYfbnAzUXN1o28nqno9lxxvL89GOR+msiZ98BNEVkAogChKeeOaZGiSdhRfTRphJEgr08KRy3nAd593WeVPDaZJlIkYFDgdWU/Lk8iOmbYJHwkEjKhEBB2AHk5VmjLFY3vkNNe0P64XVIW3VVsRr66DApuCnAAUhC8ejipCg2hxLrXdVDRCNulKfRKPg2mgXqgwcxKNsCUW5LE0zQivdFFyzdvOtgqNyCLFGrRuHbSaJqJpRutYK0nk4VnDJOi8NqHKUWUEBr4dYgg9Rx0p5844P23MiOPAjRfTqeVQ97aQV2R10RstLWNXuuRunEjLabPMR9aSh41w9jWT4vD1KVwerZF11I4EQJEebQfS98rVThuwNCtaDwTwOrSxj25WXY7J0CNW8+tSO0C/wdHSNjSi7+ZDgshPBwamvAAOdhTfDVSSdt1e64QEBJOErZsC6VIrGRpAeULk21NCDEyzD9QBVVIICSh0nHo2gagZFS10lrKvh4GpYX+cNp08E901EaRMBfUP9ZvmMZHn68vzB0gieTgw9jdE8qKcOTQmM1DhrzrSiagzPsY0wz/PT9ERQVwjy31BHTcepc0GwwgVhxXGM0IKrvPN1Ecn1Ce98h2MngPNGV27qIO/g40DlvOSWYO56JM0qcN4oGX05bSi5/nHeWGco4Ulm3hG06NJegM2gj/H8HFa1c1uqaizXPaxq97ainddhTfDlgfI6bz0RXZnrYygZXflSv8aKdi6He5VkVdivnc5hZDTUitWxgx9XfT4LBQoHT3JqYxAlWfwYICrdo6maBbWtGVABWq7UTg+ok9JafHM6FliYW1QcDVjWDfIRHc2s6GhjKdY4oPKDBPYjwzig9MFAGE4bK7LtRBj2emjqAUboYUn9NZQGChKDSjs26eZgqy5QLsmFQBIklRRw5KL8QAXp0GbdUKb6BzVuFU0W5Xuq6srKEhYH6m/vw6jlx7S0hL4Cel++GmiwTOcUSE37DRTgM0x7rtNAxxTpqHytG+p8APrtGHM6BtquY7SdtWxAi9QMsTpehlRAcufqX27WjRYFOI1rY9sj1RMsTt2WccKkRa2npE7P3iQfle/4x6O+AffUdgodX2jzMNCueSB/zCdgwQqsjnVTpzY6E70fSOhrvGqdR2fdKCfq+4heAMkC6CKp71N/vKHo1uapa59/NTxO55/Wp6GxBq2qFcA2ViVZJsCxd+quDjkt7/KmzdpRRbcbk1z0FBj6/T4Mkq62ji6Au77brhcoQbL0r2T5ZH1rLisW5vWgDAUJ70KyJvdEQW+vdlePKLDsVaDa2x/AODjYhv39OTwg/oNzPXx2oTohPjMf8em5ANNj8VmVHdi9DXt7AataaEEKDRQ8+3UN6zVSMO3sSQomLldkg2EfxhAQVNE7Tr39gRG126xkQ1CgCoosI5/hCnvHLVa3L+LgTvW3cxGf1U3mIeUf2jaHhxd6eGi+LnD6kcU+DKcfnKtwIjyim9PB3hwOar+2pLP0sH0nDi2PAAXpGOviU5IAI1rdfNoqoK1Y0o3oRDypiO4KpG4yRJQfbGtUkBnIHx5L29uPAT35oe8jJgXnqy+6GFf0F3Flf9s6vVz5K5zXGG3kXyH+sfkr+gvYLR0umou4RHbu6ROL7RDQmW0dWmzbuYA2NCjnuwR0D4CvTjdCCoqhqaLvE39kTimUiBLcQ06yMcmWDGhne0U1j8ul31WD7bhK9Arpf6XolaJXnIReofJLdYx06XwflwtXaTNwpcbkKq2PS/VEEzVHoWvPwgKqEDFaHSGNWlSaGwPW6LGCbVGV9Y83J8501OmTwfPTu+iT1Tmfy8L5rPzJdNfABQ+yaFmoXV2SCAosUY/uBtYukiBZykiiu1yHPJLvZPpH/D6fdj1yWk6ytLf8Dli7SJYykqUfdFcgEIN2nhF++RZAkFp6CjArdQ/Xv+TFuOnlX4m7v+2bcNu3fzNu+uZ/hWd9wytw67d8A+7419+KG0WNm775G0v6ePRZ3/pK3Pwt3wTTW771m3Hz/+ObcOu3fYvwTbjlX30NrnjObYjb57WAVjAersABiSQYtYDkKzLDelnlFhkTLfBWxwA+m46U7jmDepFI3UyoHVSlNkGAyxTgks4hF6+/Frd+5Vfgrm/6Btzyyq/HM77+/8Czv139f7PyBd+IW0RvlZ63fssrZV9XNuXfIvtcvpHepPq3yB+3fZt88Y1fh5u/6suRd+3CRHq3VhZEiWG6iUCg9PT4Ga3SSbYkWdZKTzIiCCRBsrTOslvhESsTHS7pBVdUcB4Mh/j4H/0JPv2bv4OP/NKv4mO/8Vu479ffho/++m/go//lrUr/Bu7rqPj3/YZ5b8Nx6W/8Jj74G2/DRyTrY7/7+/jI238X//zW38beP/xTPPyBD6A5uE+BNMMvMh2EO4AJlO7HBrei9Gl82TpqDOd0RLX3v/w6PvRrvyE73ir8Jj761rfh49LrPtGPmZ5U/7fKLrVRvY/8+tvxUfniI7/+m/iw7P+I8PlffyvwN3+D8YMPYrEGFvoKyoGI2vRkvz/RGFgX6PKYdGvVabFAdqXOHR/+RQirePzCC4AbLgAbjmsCdbmgBAolukE3bTRBHGBNVbQ+EVxmmNfBk6ZLn6isk+N6lut6lR5XnXfaqhhOd3DeCKDWW4bPU42g3V+r4DaKAQf6FXDz07Dr330L+q/4cmz/pq/Brm//Buz61/8Kl3zbK3HZt/4rXP4twje/EpcKlwunQy9TkLxEuPibXoEd/+e343oFy6EWzkA7H+tT9KhqZBIOYrbDcKBoAuBdKKWfAQUx2ySCqEAXpLuDX6sA4N/BrlJHGAoon4sZ/W/7Rsy9/Cuw45Vfjav+3bdh9yu/FhdJ543YI706bOQfL71Hti9++9dj/tu/FnPf/DJAflm+ZBd8fDOEFA0V6kwdLQT09BK01nmyH+e9w68VsKUSrKfPc1MgfMzTIKOgUr4OGNcam36QH1I5htil886H//rvcd+v/hY+9cu/js/9/C/goZ//eTz8C79Q4PTp4kG1feQXfg0Pvvm/4LNv+BV85k2/igd/+bfwvjf/Epb+4d24WDoP9A6ikq5ZPixnwYR0gbQEkpyedENsFOy0C9HxQQ9zesqqNfey7NaDjIdNRbJBdbNgS6ixicLn3/Nu3Peffxn7fumX8ODP/VyB7XjgLW8pNpl3Ult+Tja/5T/hkTdLhrDX+Ln/jAdl02d/8Vdx3y//Kv7pV38dl+sQujdc1gZkAuropgotDOqmJytAEp43JKe65oxTXSRLO2+gvKE4Vf3ztTycr4o/lno7wBxPnvnkdCJ4Ahkk16uS07T5Bkl4wjhtOG0ZDvKmG9HxgnYVUYGxp11fHaPCCqG1iEbP3iMG7I8BinzYR+BzetH36ckInxiO8LHRKu4TvV8v6z6lndBm8DmdAbZphANpiCWdg/ofg9TqJ4BI0mQCQGroWx8rJeLHbO84O/40n5BVvhFqWdq2ChYTPQk4aEJBb1X1HrYdzQifmkzwad0sPyv9N4NPacd+nxb/J9ni4VrKKaAe1jFGI19aR/s76IYXM8qjNEWNoKp2cpIusVeDCuTJvA7iZwUwnZqiBMWQkEKj3WyLBQXM3fL75ctDXHV4FVcuLwtLm8JVh1dw9eFlPEUwvebQapF5mY5ptuuFYV+2RbTFt9ZVMbdo2FHvoH3MVutIygXeHPi9S7dhCLpZmn8sbL/9MNCZ8cWrK5vS/Uq9iL1KxzxXLa3IBuMwnrIkHBZEr5RfLl0ZYbvmq/uh7ZAPbQeQpFIHlKDsIJsVmEmCpMqnfK8h22GQU74LXddw+kKGx+pCtu+Utm0cZPLIBOgakoQnh0EeXU6yTC7oshxDycJL2r4YJMuEI1nkkEfyWbu2kYKMfx420aQtO1ftluoW6GsV1qlCiH0gDPSErh1t2wPbPiph0MyhJ4ok/iYxZoWHdVN4RGeqEwIxBIQ1/ay7NmdaJUR3SSUFC7HEKGnRJiR4V214J2qUMjfLwXFQDQISIxBqLCHgACKWdAapPRUmOj/eNHIfY/QxZK/A8inZVY6InR0ApjeRUPS0fv79sGE+Yyjjo2rSNSsIJ1SZiEnq6oUhyqUMkjRPqHWUM68d6zYFHgfrqPGalkI1zgzqsOwq+7pJdrDsvgInOdHNoZHO8q+U8Q3HGlid6Q0ngeqxC2Dme8yy3iV4HjkfQijj1d1MHZSJDCoQRsEB3vWSvjYDuQlgI4wRPZLaEVdCTxOnLz9VokE3Ovt5ouH3U2HDgCTYHgO6vG46KAuSJusgj853BW7TpS9UGi5UwzZjF8lHTY5uEpDTMueNTn6XNjXMN/UuxgvGeXLaljyaAsREb/jHOttcr5uhRUvUWlVRExm+xMuZCvxBO8FKi0FQwKtDT0EjSopg2Soh1cdp0hQimn4fjXa4OQbJTupDQAtKPkn3DnDj8g3w5cUuFZGlo4Oe4VreRae1ZgEJlH1BQazYInlZu1sKlc7Ye3qRBLXP0iMH2XfGNCAG3bRCH0lypAzcFxUYqMBASqZ0tz6tdMqlToADU0ymAWXnppsppKNtcvCrM9FBsRHMttmQ5ZJHHYI4+NTuB0lMYK30jKj7s55B8mJuNO4NepoLldKdH6d+7aRDuuCoy/PMv2TI4pJEUFAmCVK2Z3PxqMv2U9xC16p0PZwpdfMkYa38Mt0dS7B8EhJgXzpnGybSy2hFW20MHJyNLI+RXNcburx+RMrH68IwzzCTZLHPtjp/IaMbjwvZxpPaRk4Hm2SpR7IMPjmlhakvTw5DSQWxXOC04YlC0smjJpr5bmN4khnHpmOMqGMPpq7P6arVQkxCW2T6y+0cREr4VPBpFBzG2ml5IeegU9NNwAsqNURSsMpZYScnLS0vOTjEo2LU8nHvU1Bkql5QKpdA58WplPJHPkFySuDJWYEO6In2WpULPZ2r9scZ/VFCT2fC1M0gaxe2GQS15ahBWJ3AvyCBjkyCjkwqB1wdo2QFviyFs4KHzJMCUNAI2gUH9FsUQO2hp5gIQCf+Kgeom0r57bG8EOSboABtG1sGNIL/laH/teHYjYDiI3VzxtRtpq3DVIr66W4G7i9ZonjuP0oPw2nXsT3GWLp7XpFcn7eeRxJNuqvNAAAQAElEQVRY5mgZHzo3hft01sG5QGz3bv6ZUjVFltcaPcEYE9MSgAOS/GT9k3yYBdNG86mjTYhonZcy1jdqHZh6ntsey3be6Q7mGa5jaqh5mYdOX4jwmFyIdm3apo2DbyHOb4R5pKcFpgvAwUBM1/FEUnL9Q07rmUFyfQGR07T50M7N8AIBUpGZFVxcRgU2ukCjZMqYobkOnQogVUo7imiVlbIiM6/1cXo0guU3qv7DO057kSTvZKFLenk3qtRRn5ChViiLggCiIkDMFA3iBd1UAo5ctqdVVlS2qbCkg9IxA0E3mUrS/DvqsBmqNlH+qWW7jrcBCR0oMFQKyAEtUmoApR2gkyjWrlCCHqGmghQRnySybE9KW9tWvp+OZ4QKxJXWkj3dAUq60jJbfEgGysWpqNPOu30rOa2CnH9SWdLKZwXm1rtMBTtowIuP5edK0HCXvjIhLyopvUlllOzmoKmyJ/1YV8OVNkuR7RP1rSOlVr5rA9BIZ9shrodjHV3e+nOqOTpHkYSDMelaWFsDGSSPgu06FrjAL7n0ArfwNMw7dtC7vJuSLBPGi9X8jkfSyTKxPLmc6eq4Xnl01iInuT7JXI88ko+QbL3k0zkHoGBS2iuQ+MWUGIBmc6tT1jaPFW4maJxmg0mYYFw1GMdUfvLWJOil4pnD94G5FDGns+5KQQJaZIYXf5BM6gyW2ZYdQVRe3UpzlPXVbwKMWnG4UhupDF8lKGp22ZYJZQW1y9Uuv1WlVGckC4mEz0tlElQFZ0rhnW4VgZrQ+QAcDYIEVQoAQXu7IGo/Kq7BNvncuWGQ34CxAomDCUR9g2jtYbUd6cY3VrQfB6DRWCUGJFHI4pCpIaHsJmIiQlIlBOSzQKNd5DhETLSDNKyT0arPrD6pPtxPJVq3AdYBupKgbJmbSpZP1nzrYAZJk4Jcvv2VpH+SxkkZQUE2K7cZSID0gVpDMrF2JdGkfIKfZPqaQ309NQ10Nu5zaZ+vm1Y6q446yglIxYYy96W/GoNkgW0hp+mNfJLOFoQM9YUL9vIMu2CNs2EaP5PjIBVe1uO4E1mTo5skTndw2UaQLJOHZGH7DXqv10PUAnMANshpmSt0ck5EKwWISqPgdkZWkLTOOSriMa0df9RgdCWViAdN6g4kpU8UzpyqFaggZ/iG0mohTZTPCkRQJI5SLGT1KUOyl2GWDkp7UYmsf2JCWahmTAO66xniaAU5WFskQEAJrVlZoJCoBUoSZJT0zVBql5zQNGOMJ/LXaILRZFjy1N2HJLorqQcY8p/1aQkYQf6H7PLYK1QA9r8CdJbtSTcQmVZEUAGSGTCVSSB8Tb+d2gyS9JM70EqHhKlXs3hYkw7xAo5ciUfSeS1tvamnHZeQBDmFx9Y862pqdG06at7Zwj4B7KV8RJR8fCSTQJXbuqi1pmmlqZUKOt1sg+E14nYkTdDlO1qYa18kQXItd+GSjeN/QVnJ6cwpNmXNyBbUgB8ZUD0Aa3GLu2GQSYIkvGg7kER3kUfS3aRpdOa5uroKB7gQgmSmUt2/g+5kuO7GCei8YXHremqRrevJACp4+QgA4wg0vTLFsyTbrKiAEhUwgiJMLHVb7US1Q9UuPKqCYgtM6SC1Bi8Qw3zDZUl95KrVTrFBCJWkax+da6WDduYjJA4R2MIBoNUjN3p99HoVdC8qwU29ouyQFaHLbk7uyd6J50qybIOADLFB6YwUwVQrmFfIYjpQHgsVwjA/oQWjRWXVT0jKt9LZ1MjK52JzhbqaB/qLCHNzkJKqG2B/JogKQTpV6rRmQqV2UkTlcq0ePyIierEHSsfF+QVRIGtcI6SKJeUsCZK0RslSACqAZyU3+/HNj5IfZdM0eGX1PUWl+VDp+MzHNPaFfy3jJ5FG+md3qGjtJ45aNmnIzQECNZbyigJhlq6RAZCcoBEg1ZN4vjnaGsrmIHtxllcunUuilHLSPrTcrL4b3Xg6tMobzqcgz1LzwHMua3ykVyq2pnVtrL95puvMDQnzDbO89kwvRIQL0ajOJg8gSawHykB0F8nCJwmS2Hh1E8O041uW86ZGxyd50vbktJxk1+Q4VLMbmqilStCSdZWkxWqY6WHSAnNSRV4IDtJeDJPRqPTvXXxVVbCO/j2sdXQ+KprafpKlnpoXWngqgxe8APVPLXboPBFaNKDCryIASVDqJfG0udaNqIFCgMUgUeu/IIundOGKAaqNdS4MfWXFXOUlgw7SomLqk1C6zBmtgop198tP0wR1qhrD8Ug7YvVJwLbEuirjhrWxtB2qJrv1LQUnCriWkan+FABIgiSC/iNFCTCkwoNkRFCBWvpLh3IjGutGJznQZR9iTY8s/bLqZFHr1yqgNG2rWmf3CZJ5BJCfNiKDlAYaAAfCAnVHEnWIBZXsjKC4qudvlVGBMWpse1G+yigySYIUVEbSNR9zhLW+oPFVcuo59yV0eVO7NydCryCKTh5DgzyiF0mYh1NdMcBz6FTVztdyzeLzVfWT65018b2QTLua5JEJUCa2JnFXdjrUsozTqbuxDnmkX/NJlonp9NnCf23PAdm2Gk77HyuYemdhfQ2nN8I843T7d123dx+G25E0OSuQUxmWb7mmhoWS1A1BNwqNpfOGywynyWlbpwtP9Qo1Yw3Ob8TGPswPClimhpvYb65DsgRukmWsXI+cpskj1G22EuS0r64Pcpq3Pob51r2D84bzHi+nDefJaVvnDfNMH09s1IHkuiq2zTeZdcYJEiSR9CRzguLznn1OA/S59JYH1+fDngCG+/Yd3tQgWXZkLjOw4fLk6LCBXRYqeWQSucxtDac7dG1NvdhdvhGuRx4tx7zNYE6P9N4pWz5JeCc9GAxgnuWZ3+nQ0Y081zkZXJc8oqvzJ6t/pmUki1/tq2NBEh5DjyWphahdq4OOYT1IliDqtNtqQMuui2RRw3wnTI+F+R1c5rRlWDbJo+Rg7SJZdCVZyl0fW3yRR/p0V9bV8FgaTpvfgWRJ+ujNNxvyiI9c13AFU3Ja1/mtgvsxNsq33zq4zNhY7jRJDWfEqa7WTzx+/3CqiudpeThP9T6l2l5onsBdRQdnT4SNcHmX7+qRPGoRutxl3YTqqHku6+C8QR7d3jyDPLJQnD+2nXmbgQOYdXJbyyR5VPAgj+Qd6AzXN0jiVJdlus7G+uSp27nN6cDySRafuw+D5HrTTl8zXNdjZjht3qlAct0f5NFpcjom5LQ/8ug8dLkfw3122JhXlS39uE/3t7ET5803NvqLnNrhuiSLT0k6qyMgHRrpCcMZtzc9F3BfRtfX/5+9P4G35TirQ/G1qrr3PsOdJWuWZRtIyHuQkTB7YDBgPCHZGBLikLyQmZB/hl/CgwcJmUhIXv4h4R8SAoHEQAYgEI/YludBsjV7kmWNlizJmu58hr13d9V/rerT5+57dccjXelKt1u99lf11VdVX33dtbq69r5HJDf9su+93tJ28/D4rD8RPKe1k41t+lbhRDbPdf3zmqB9cXwTkHRyE74JTOCGyw2S5caxkcuNY28Q8ogNSZseNfnJTue6Rl+f5GbbpdLGh202klsWXin17Vg675WTx9Y3Snb9z4+TZF98Uuk2SRb/yU66AtmRmdNPBW7fOLYN6xw/S8Pl9t+EbThvPdn5ZFvthxxFRCRttrnKtv08XOh6btd65w2SpR2nXdaDZImD9bY3nD6bcB893A95xAfn+zJLj8VwmmRZgZIs48fGQXIj9cyIk8XOvrqcPNon++/71ziVl67Z3w+nsn0ulj9vCdqv+MaxF4UkIrth+wbBxuGbYh4uMzaKN4VtnOml04bz83Bdw2U9SJYJ7puyR1+2Vdm345vU8JgNp10279Px0qfbL9lNdLfR1yHZJ7cs3V6PvpH5/Hza5SThcRmYO0j5IpCSc/q+fi99Tfq0JcmyjUJ243NVstv7JlmuF3liafuzCY+TPNJ/3xfZ6Twe62xnkN04PLa+zOUkC2HbxoAO20g8I6f7mod9M47tnGS5vtBxvHKpjzrXmxkOozlK93zKdEz1fBrRxlj85Zm/LPNN4QttuCiA5QbwTUoeuZltdywwd7jMbVgaLiJPXb+vY3uDZOnfBGrgKR72pV81uy+SpX3rvZImif6wrk9bkkfKnD8ZXNdwH5Ynsz2TMpIgO/T1yCN5x4js4uy+j7Xpdb6e2njXWRUisp3LSJZ4kF2bZCdtT3Zl89tEZFfuMdrGbXglZ2ldD5yN4zht+mFr2BcXH9u/8y7zGPq/bOe89b3PrkeyxKGPJ56hw36cCHbBZfbTcJ5k8dNjIIlTHeU7l9H4VGbP2fLwnPX8FI77yzNfZJuRLCTgdH9D6L2v/PzIOoPsbMgj0vpj4frGsXrySD2S5SYjuUkWfR1LE6rhiY9THCQh1tHbe/eTLrIjK08017d0E56gTvvBZL3zJIsfJmrHwsDG0eucJY+0aRtPFlL9qpDsyuw3ydIe2ZXZFmd4kF3d+WokS7vQYd/7vkgWvcdEditdl7lfkmUbwmnocD2srJSf5Dm21pvYbG/IpJwkN+8FK1y2urrq5KbesXH8XEYebW9Dkha6hfyjMRRpW6MU6MNpx9FS2c3T+Xn0BSQ3++91lh6L/Snjk4KkPrs+nfA43Z5t/D+QsOz1JItvZFfHbXnRcmxbtid53P7d9jxsa5As18Zl2DicnofVJC1K2ySPki6wvcfge9fSefvp2Ln8ZKAK/TvwvH8F/xAIeB4ez8tB+Tr5t5H9jej82UJ/Y/nmIrnZjW80wwryiN55w2WG0yeDJ5RYp5j0ffjm9UQ0cTntdnxT29ZjNpx3JU9ap21vaXvr+7Zcdx4umwfJMqnmdW7DdSzn9cdL225Tr7acJrs27YPzvb/2r/fT9Qzn3Y/LDKcN13G5dW7DZIzxuPyKxe1a77HbxuVk16fzfV3rnXd7htMkC/FAh3WG9cpuns67D5M4yWLvPEn0B8miJ5+s623Jrsx5g+zybh86yC6v5Fk93bdBdv3N99/rSW7eBy53XBxjksU3kpvjJVlsyU7XzxHyiB4bx7HtkyztWG/gFIfvD785iKDTKUyfk8XPW4I2WfnGeCauCsnjduMbuYcN+rSl86cDv8JpGV5uWtczfON6bL4xnTZRWJLdKtNlJLXwrgrchm2s72F7t2UfyM5/5w3rnk6QLBPWbbp9T+5e2h/7YmmSPV668z26ehmPbUkelS6FOW+uGN2W6xW9Pkjqs1t5um+jKDY++jzJ4qvzvZ82IWlR2neC5OY1wcZBdjbO9uMgj+isJ1nquZzsyshOR3Z5922QXR5n+SBZfCK7/ty3QR6dhw777fhbkl05yRIz8mgp8xIvt3U66OPd2zrvNk4Ev7/kQLTLoxOZPOf1z1uC9hbHqS7w03H13IfR31Ruk+xuVN/Evd6S7PTkEWn7k4Ek9C5fbnSv/Ize3gTkVUyfJzvScr+2M8hOR6odGdpX6+2PVx+27UF2NjIrp216FMXGR29vYqpgwAAAEABJREFUuaE6oSC7NslOzhuSLFs39oeknkNxkyjcbz82EwJJhSGVOLjMdSyhw2PyWDCZ6Nz4l4ci63n/eluSm31Ah20MsvPPdoaKnkQ61hkud5+9D85bPw+SZTzYOGxjkCztWu18L91enyc7mz5vm7MJ93Oi/h0bo++fZImfdQaOOUgepXG7PdyPYQOSJQ7O97C+h3V9+qQyBhw8dOikJs/lwvBcdv5kvvvvY4Rw9ofX33yWvT/zN5d96NGXk+yTp5Sl3ZTKZDche2Xo9qw3yCNtkSw3PcnNdm1jfyytJFls+nZcdix6O8u+zGmD7OqTLBMVpzhOVN9jMFxu2TfjNMmSJVl8nde5gOz0fdoE7thof0PnqMTK7ZrgXdd2Hr91ztve0nrrLOfz1jnvGJF0cXk4OEGy+OT23L51PVxvHrZxWa87Nu28YTuTvSXJzbg6j2fgcD8n6t9xdRwcD7tiW6O378dm6XLLeZBdvFy/B8kyxvk82dmRR8pI4lTHKEQsaPv5H0IfpzJ+DpaffQZ7loLyTK2gSZYJS3JzdTd/gx57EzocfblvdOdPBtd3Odm17zqub0my3Og5582+PXFcPl+PZCEYsvPVdaGD7PS2N6y3VNHm6XYMsqvrAtsYtnf+ZOjrFlutai0Nk5u3oVw3xlj8t875vu1e7zFZT7LYOe12bdfDOl0InZ2fLjfIro7tbEOyxIwkfFjfw3mDZLFx//bVsA10kCx9KFl86ftwvodt7bPhtPUkLUod63pY6fSxfVj/TOFk/XsMhv2bx/F8czvG8cqsc1mPvq35vNPH2jl/MvjNKYAQQQ970HgOHf4CzRPsbLtMEiRLNyRLmmTJ9x/9jdfne0kebdfr56Xrprb7exT9RHG59SYHp623JFmI2GVkl3YMbGedbQwToSeIdS7rbcjOH7KTLre94fQ8XN9w2clAdm31Nu7LKzJLw76Tna9O2x/b2keShdCst47kUeOznmSxsb32N3RO4EnrMtexzyTLdenz9tt650lalDacII/kbWedQR7Ruy7JQuI45iC5qe/rkzxu/yThg2QpJ+nspi8l8wx8kDxh/36IOpb9mMkjtmSX7l20TZ/upXWGY2F5MvQ2rtvbOX1S2IdxfVKT53JheC47fzLfD41H+PJohCcWFrB3YYx9+obfeGJxDOMxyceWxnh8sd7AGF1+XteVHdF3ZfsXIlZrwv8jzEQcd0KRLDe9b27feIZvut5nsisXvYBSMicwK6HTv0CZhUopYFvbIIigL6kiXjxewIvk9ws1lku1fXOpLC5RO5eq3hWscVU9guUVIRZ5Scq4MoxwOQMuyxFXxgrOu9zyJdUIF0wbXDxrMQoZSeXQly5qduMMZWxJHmboIWFoFexxGKpSfLb/YEJ3M+VS15+tCqZQu9qiqVPjQGGnjC7XWK7S9blK18Y+XqF+e79fWC/Cfl6iypepX/vZ+2u9x+HxXI4KF6unq0YVrhoFXFWpM63El9Vu0HU30Qc5QfkbJaNsnbff/itqbW6l0RkDSNVVsj9JlgeBiZ4kgmJtkOziofFAh3WlPeV9faUqbVlvkF27JIuepE2Ogv9PL5RjlXwPKqecmyq/b2GMR7Yt4qHlJTy4balIp7+8tIRj0evPVD6hPqaRxbeo/kmW8XlMdpKkxWZ5PyaXe7wFssmKT5JMnTksCN010rn9w3XE/sUFPLG0gL2S+5YWsW95jP3bFqQfbeKA5uP+xS7fp/ct1OjxhObqPDx3H1NsHlX/xdHn4Ud4Ho6pDOlL23f8+J0Xv+B3Hr7qhbfdf+EFdzxx1QvveuiSF3zxS1dc/MgDV16894EXXrL/oRdftv+LV75g35e/8or991958YEHrrz04KNfddXBh15y+f4HLr9w78NXXbrvi1detO9LV136xINXXvHg/ZdedNNdF+x4+93bFt42vWjXZ9frgIaa/aXHoz98E/foS8oNrcnsfCkTaVHEHHMjctMN7aZy0K0dMQmmFODiySpw203Azbdg9vHrMb3hBqx/4jqMPvs5jL5wJ9rrbsTC5+/C9PpPArd+BqPPfR6Tj38SlFy6426sqw4/fTtGn/kcptfdAEou3H4nZp+4AbjpFuD6G5A+8XFs10Q9mKeYajym4qBpFhk0YT3dAIqAIYI2oUCH/34x5SlFwLatNIKoJ4u4BdCYWtk0gVgVJnrI7NHDArfdBnz+c1h933vLeFY/ch2W5ePkY59AJb/GJX2d/LoNS3ffi9n1NyJI7/FNrvsEwqc+h4U75Lv0UfrlL9yFQx/5AMKnNY7Pqu1PC5M1TGPAmv//hJq4tXwaJaKST/bNcVcWWX4l5rLalqtl35rUaETovk4kEVQfOkqdjesWRWSG1OULTpeRLLa9vetbDx2WPXr9Zt4xjXqASeZW118PyiU9aPZP13HwJS/Cyjd9E1Ze8dINvBzrLxNe/lJMXvZSTF/aYV35deVPJKff9nKsfMs3w3L1W7+lyMnLX1Yk/+SfxKF6BPvT+yaXjzrJ7vrbpocNSOkVC/+N55nSDRQ7yVKmWFeKV6v75bDGc2D3ji/cf+Guj96+c/uH7r/k4lvuuXD3vXtf8sL7vnjBzs89cOGO6x64YPvv379n+Xfv2bnw23dvH/3OPdvGv3v3joW33rtr6e137hi/6949S++9a+fCRx66dM9ND1yy63MPXrbnvoevfMGXH3zhJQ/fcfGFN34K+CCep0d4no4LP/rBd/+LH/rY+974mve+/Y9e87EPfvV3v+cdX/W6D73/Ra99/7WXvO59115w9Xvfu/v173r37jdc+8E9r3nHu3dfc+21u65573t2fu/b377zde96z+6r3/+hC177nmv3vOHaD+y5+t3vufD1177rims++L6v+8GPX/faN9x8y+v+6HWf+JrJrp0PzjTRTxVDkiC5OYk9kQ1GEaAmpwkDGwclQw4wFpqM9/7qr+KjP/sv8c6f+ad45z/9Ofz+P/s5vP0f/yx+76f+Ed764z+Fd/6zn8U7/tE/xjv/yT/F23/mH+Ft/+Af4m3/+J/gnT/10/jfSr9dZe/4hz+Dt7nMeumcfofS7/qZf4yP/vRP49NvfQfW9+/FSCta/3SvovwSYaSmLX4n+eRVHqISGm/WWEiKkimKzuj+ljM00e09EERs1Ub5oghgp7B67324/p//C7zj7/8E3vOv/g3e/k9+Fm8T3iq/3m7f5aP9tL9vlc/v/AfdON6mcbxNZW8VLN8u/f92Hcm3/tQ/wLX/8l/jt//eT+Bdf/8ncd0//zlMHt0rMiaWtNL024tJpSefJMJIIuB5XVVVhZztM0n0R29DssSA3Lp0myRLXMij25np7SjpIZehWMq3RuS2/dKL8XVv+D58+z/7J/jOn/xxvPL/+Ql8p/BtP/XjeLnkyyRfugHnv+2nfqLojydf+hM/ju/8B/8PXqp63yG7l/7ffx+vUJuWf+xH/i+s620GT+HwQ0/PObUgKtF9qwQyUTCLAfHiCx/5lhs/8Qdf//GPvPTNn/z4K67+8LV//I0f+9BLvvOd73zx9330o//n6z5+3Te/7rrrX/X66z95zTWfvPH733DjzW+85qabrnnDDTe+/ppP3vDaN9148/dec/0N3/X9N9z0std+8CNf930f+tj/+boPfOTFr7n2g5e+/r3vu+zNH3zfn/yHt9zwbe73+QhF9fk4rGdmTF+K/NJEr+en6s2T3bAdSZAskxXaxvD/YWIWtBIXMgOou7vW6mOpSdgxTXghFrFn3wouevwwXnRgiq/YN8Ef2DvDVz0xxYsfW8VXHl7Dlfv24SWHD+OF+/fjqgMH8BUrK7j8iSdK3vor9u7Fiw4eLDa2td2LDx3CpUKWXVyfYsyIoBVcu7oOiJwr+VNrhQQdpHwWO1M2pNIFEUn+pirCK+WylYEsayAmoBYW9YDB/oPYvjrBFRxj+ZEDuGK1wUsmejN47DC+ar3FFSq3L/bJvtlf+2qfnbZ0WW8zn75i70G8aL/aW424fCVg9OUDuCBHcEUdaOsmyv+kB0pTUdtRRKu3BP+vrCCdrDwiPVQyTOSGrxFJBI2d7KR18+jJfl7Xp49XBh0kQT4ZQECoaqQQAclcV1hpGjy0fx8ePLAPmK1h3+phPLZ6EI+tCetKTzo8Pj2Mx6fSTQ7iEeEx4XjykbX9eHQm2/UDeETyUdk91qzgUJjh4Sce1rWTg1s8ox4ovlfHul8siYRElDbXqoBDI+Ke9UMXb7H5oZoiEKCP4dxaBJrlpZ0m2NOtPT+RPZkLGHRTC5qsbscrEsOviGOR9MJ0itHqGnaJ7HZrImwXge5cX8cLZg0u0urrgtkMO9dWcIG2EXatr2LXdIKLtb9qecFssqnfPZviBe0MOyfr2K1tgAu117lN5Zcubcf2ekGPgYgocgiadP7pErQlYf/s0zxIzUApspCcFkHDEOm1zkvvk2o/qL3tIp5axL9D7e3S6vCSUGHboVUs7z+MPdLvnkyKX/bX/tuvPXrFt58XNlPslt8eR6933uPY08ywZzLFjsMTXDwldorsX4AxFqctlkV4/l9WkSJluWuShvwjWYgygGWV7QeJH0YmZ3/J6PH218hj6NHr5mVf1sv5sj7dl81LkvPZ8oBoZqk8JBhrjBeXMV7ehrCwCGgvfSbSnoxqrI8j1vRl2Iq+/1jRvuzqQoXVhZH0Naal/PhyIpuZ9uVXqoBGe85ro6rYh9070aietyiOcugMM1H3i+MYdG2dBo6Q9DQGTIQzbHIwn4tAmEsPyTOMQJ6GJa94T1YthACDJEiWCekJbDIwSt1MCUHS+6LQStQyhxaJLSCEUdZKa4ZZXoeYVKuUday3h9Fq33iWZnp5b2HZaD87i+EtvQeYmDb1rax6G6cpIp8eUhtCLcIfM8DkTJFp1vZGVD5rAnYggIAsHzvIy5wRRLhkRBYQCCMR8hvI/l9LyRfvs7ezdaS1VTRrh1ApfcFSjbqd6o2h893+2jf71aftu/OG0y43nLYO8n9RxFCLqEfTGZa1QqZIP4YAuYB19ZOQ4Tgb0LiCHnqVCHHUAJURo8YQQLqGhqjT47W9QbKU99fQ0iBZ6pCdtM4gj7Z3Wz2go0/3skaFhVBrNR/RahzrK+uYSLb+nweCSICuGjGTxVRtzxTnqTBhwBQBjW10TdIJkGWj5zoat6d6lusTxTwBCoVaf2qnH9S6iGqkpBAlTNgxBRjjsKCy4dxqBMJWKw71gHE92naqOASRRQ+ym8zzdYIm1rEXwQQnNbLINZp0mEEhpSlYJdRj1RB5tyJjkohR05yVbKKmcSwkWikPTU7LIBJ1CRnhvGXIQavIiJ3jJSxqstcZiILJGTpMTsErY6X7syeVPm/pfWrrnTbURPE7BcJoApDUcA4ZY31rX40iIN+1yMVEhG1fpCh+H+un8/Y3KxhRFrYL8tt1qCA5rvZz2kzgdk3aSXQ206p7pJViXdd2CSQRQFSG2rJUOL4enEUAABAASURBVKVDxy0ASMLtkdxMV9qfhg6P73hQUTmPV2ZdKZz7sM7oVQoJZiJjiEErBtRxhJF8Hle1fAhIepCQlJ+xgLp+VByCQNT6jHCe1p8AUE3HL/ge8H1SjeC0dtGKxFM8/LD0fepmHFNLX6OoOAddq1YPTOvOEp73zYbn/QjP4gAZtbEKU9KJOzGBGJ6Yhi1JgqSTJ4GWOCp13SQiRmq0YJyJUJLqZi0GG8QYzX4injHQViLYEZhqoIn6klFp6eb1VD7kI/qQK9lnUNsjalzpVpMWqPw6DGLiyUX1gSBPCC1Wi4TKmIGYAMiGWoqZbKxrkdESMDHPtPc7UcFaSDicZ1jVm4DTxqG0jjxW//LefvV+WgaNIWz4aQmNh9JF6Zx3eZYOfgiJ9NcWiQOc4GC7DrrNmmjlbKPtoQC6B0TFm6TGF5CDHBSy8rbLWYNBd5AqU9JkbYJ22Tx8Pebz8+njlZFde2oStrWcx+K4hlwRGev66q2FciXa50zYd20Vo1/111OgQMQ90vcTcQZdO0C7SSdF1sOMiGhUDzkgasVedHO+YQuHXIXchG+Dvjqt3MhQhSNtn21kB7GFCIQt1BmqbEZAd/5m+vgJT9pj4YlquAYLOWQRKnSzZ8HaDtTN7vKgCRsZtGaqZBfQaqJ55TrSaivp1ZWMG5M/wEe3OnLasCaU8qwJ4xw09e0TSXgrgyRM9k1O2haYYqqtA8SgVWmNMsJA+HBr9kmcC2uC6iE1crxVPtmk9COXCkEmZMxE3vVoAfXiEmYqaOXDaGGpvF6TESRhf0tl+eW4zPtpfZ/nhj027JLk4dkqqP3YNkorsmv0MCOpGDVYGI3BrJhuwP74Z5EzDcI/J7QkCbKD+3JcWu3t9yCJoLcgsrMhOzmvm0+TR5dDB0l9Pvkk5ad9c5H8F4eWuE30YGkdV63gYwioGBBDJ2ulRemwtN59qxAnkn7IhFr3jepP9SD2uGzvcRp4ikfWNcjU/bU5RN8lXaNZYl3fiUgM5xYjcCSaW2zgvK5mNjnDAJCbd/JczaS0b2cJneIwEUsoCClg5JXuNKPOEZXyQZy4yAVkraJCJGba+shanSbBEjGLAKfIWrkm7VJaD6dV7rz1lE0rIlbToFbiM5FzjgEYVaohX5SeamkWYyUCVXv6ws/DjaCmJOGj5DU5g0iGYlnD5B2KTYSX0lWokfQFJycJomlUDRE0lgWtiCsttZP6sL/2r3XP8tH+eRzO21+Xz+c9Hvuf2CDKz0ZfJgbKZxFQAODfFFchqt8WSBkluoxyJ8Ar+nU5OamJpg7usTxUTFaFvFS/EjFKwP+KztLj7GE7p22zvLyMIOJz3nUtrV9YWNBbiOPmnlHad5nbIgmSTqLVf43GOw0tmpiQdI10iQH5lYJtGvmnMo3N18cPn1Z1kovUQla61QMpSSYmZKGUK2/Z6y1d1+VZY3cdlzMG1VFDpzjtu8dts6DxGiTLuKzLUNqQX753rfPIk/z29pbzA7YWgbC1akOtEoFc5n5JnujDN7NvcCOKCPs8SSzUI1VLImIJTS4IWUmfvtF7QJRoJER1GJElWxFOLvokc0F1oVK3cdpSdRoRZKN6RusJpdYbwSTg/lsRN8lCRMGTUGTsyWpAduaR4HKB7Oyi7KCD8lF8AINqzAiSIUVxbgBl051b9F9+U9CzBtFNZEAcB+dD1zDsp+FxmOTK76AJ1cqQK2Vc/XUhVaB6vlYSILu804avnW2d9q8+/Ae53LbzLrM0UbvM0vkeJEtfJDfbdT/2wcQJHSXmGkCvs0waSAryVYNyeYuMmbaLZoWYVcmnHkLUdaHHP5eH9EkP1lS2TrLuFiKq/wD5IHuXuw5OcnhcPUjVm4P1SflExVPIavfYpkgVHKsc8qcdgXDaloPhkyKQqdn0JO2TFWUiakKQ3c3qvK282vIFCKIL53toYYkjIGZxA1q9zAT/xtSYVIQnsetTRHqmUtMK1Az1qirLtxSIFESvWjWLTVSmFrUytl+V9FEPGJJl5SSh8m48bsPwKpZk0asViFdUlRsIYA7SdejT1NjVi8qSCCSdkYwa80iLZEPfnertAmpD3W2cmUDUl2JRX46ZTGKwD0RIBiRz8ZVUXmW2wcZBspQ5218vp3uYgE3ELiOpcKltSRP2bDaDJY5zkCxaspPi3c7vDPQPGV2SYuMPr0I9DnUAr0a92va94T3+rMpBMaiR9XaVUSld6Xr1eUsvAUYELJ2P2r4JftMQcVPSfZwMHofH2GM+36pf+2Z/oGvrdqhxqDvItQKqP+sHbC0CYWvVhlolAgkt4NsRJzx8Q5PU/OpC3d/olp7k5YbWTY2NQ9yxkeqEJ0BJbUyARLcTNDXCBrVt5Hv9GUrEgCBygsiZJLKkfUjq1JDYPKPGatugB4btC+QFN/wnj45FaWdD5VViVtqTuQ0KHDsAT81/963mROxdS72zve8kQbKo/cCoQG0ZESNduUqgVpm+Fj2KoT5IdnHBkcM2fY7kk8rJrh/bGWSXx8ZhneGsZVDCpBxTgH2z9WY6h25MDprsbJ91baIekr5mpYL0vh5RY1KyVxVJEtaTnYQOj1Vis9x1HT/rTgb3PV/ufI8+zvPlbrOMR/fFuLwlzpcO6TOJQDgT48H26AhocnmBc7TymJxvZE8qr5adNmF7Ytis3+OESSrrUgi+sedhEinQysiro1GbUKC8+lczhLc7tgaRfAtol6Osiu1bD3rFLwRPfpEY1K/LslZp0KTX3IP3MhNoilZ9FsghyQyvrpIeI1mztdWADO+z9phpyesfYjQina35HtVvVA8BmR2S4mi0yichKz9TnBq94kNfrIZZRmwz6hYYZ2rVKZ9VnjXOHvbfcN7jNUjC18zXETpcRhLOk2pD9W0HHbYzSCoHkJ10+Tygg6LQIHI2KddtQJ1QVp0KVyFRZGgE1AXKIuuMmKBVMgtCImYzRznA421AaHgFThspRKzNGqxr/C5zPvvtqKpBEaffLhQGnOwgWcbej4kkyA7WKfLyMQtJPqLzG93htlcmky4zfG4pAmFLtYZKfQRSnziZjFr1+Ga2Dclyw5PceA32JQggnnz0OssjSLIVRJRBFPXkWqevoWZQLjABACRFGSIABtjfSPklnQnDpNQTTKO+G+2BtjmbQzZR9nelw8bBvJEofiYkfSEGtJsSpWZvc+ZSrqutrhV35XzBRlPisPJlnR+OHo/V/Vjgh47IWSMsDxSPz+UGyaLzeHt9fw37dqx3OdnZOt/XJX21nDsatjHmtVkxTjJPlqI55KBiKWA4LcjAX3z2D01L7yu3It+kGLa6HsfCesPfMVgiEIxqS9J56/2rDnV20tP+9rCh0x63pWFdD0VCXifM35ejkTdXeotBnmkEdMXOtMpg30fAX9509NBrnixJln/G6+0Ml5K0KCA30lnS0O3tAs3HTeLxsiRTRDgHL9u9IrUMIsRKZLMVRNfVBPdrf0TUxDJEzjAoDUG1HeSXAR2t7D3B/TctOKqghZ98ZQFk109aT9Ion6PIo0ctQqxE7HVqtAoULLV8r9THVhBVDzo6HyDPDEpzBFVVIfgf3Iic7HOr7ZkmAJOQMNU2Swm7athvkmVVHEIA6TawKU1Khu2gw9J5JZ90usxKsmvDaYPkZnvO+zrbl0ak2YigG/Xbp1tqLIYNVU5GKMgFFIlXOcK/kCk65VmuVkRgdzUhXVYHVRwhhrronVe40Ta5w2nsD3ssBnRYesyWPeSQShKIXFbPfgAyo6S9D12W9LIYzq1FIGyt2lDLEcjU1zZ06sQgiUZfyMx/ceSb3DARlJq6o7MA3eSaUzBpGCVdDJI+k/RNgViz5IGkiZFKulR3XpOj6E9DMid4EhlaMpZmTcjQdoZXWI1/hicbqHHqThFPlIkX1GulL9/G40UE/QeRgWF/k8abFJPMKG/siaA8M1Q3uSnJXCRlYb0sSv5MJTXyY0+318Nl02aKiTBrZ/CYpips9eUq9FZjZBmZbCTK6WtiUp9fMbvc18+Yf9CSVNjcAkCywLY9MHeQhNsmOztsHmqDQBMbPewa+KHra98VU+8bERmVEABG+Agg/K8OFzb+EYi/5HXbUeOyDBEg1ageQB6L8/Yp6eFY/Jc+avyns7oleZTfbp9kaZ8k5o+kbGKQKshfCZ22lxjOLUbA0dxi1aHabDbRVOjikLUaPRZdCeCblGTJ2qYk9OG0piUareZQE3FBq526gmgMjW7xpNVe1hTNIsqEmcigBVRqBEmqzxBrQBPXi8mUqVqGSsUbjfZbM9SvykFdaknns0jVEpJRq6mg5Zp5OKt+0J4odPgndxM2yLEF2co0SwvUsqlFyEn7n+uHJ4ipQqU6QbosImm1gmtCLL88mVQBRq5rtCIE8T5i+S9oLNQuA5E3/Or9s+z9s5zPQ/5jwz5DdeWRHygxbbgn6T1mAx4QMhqRUhBxJSZQ/jQBiMuLgPZhtaB3CyC5iVarSn83YKnCcpJElP+Gr6VBEj6c9u+hXeaHrq+pSdE6Uj7mbLMCl5XExodXm/bd1zHFNcVoXSXyU1VK645prhWnMVpGtBqP7wWPWcPxbQBq1Z2V8TgNj3MeCjdM4K1qG0F3rKqoXkbSG4w6POlJdmOY950s3ulBC1A+Ghmyk1q3EhzjNgAl3U4xHFuPgMK49crnac3NYS8uLtYkQR4fNiSPX0YSnkiZstIE8wTwviJNmCkjqiCCIAl4VsUKWa/r1ET1hAgixajXXK/Ok+xlDjKgl4wBdT0qeXNEQoalywHC0mhFXo0eBKlWXdUhqUmHcpAs0nVNWCaHIF2lilT/9tm6jimSbI/AZSEDtXyPoUYUcVNjSAiauBUaBiSVNcgiCkHS/rUiVkv3aamu1C7hvMdpeUSPQj6OI0kEM0+QzwJJkNSYM6D3evuiBJIedl4Jl1hvlGc1aCQxtqXRp7FxzOuc3lDD8Xd7lq5jON3rejuSxT+y8ws61K0+g+CzBdgISdceIr8MatQkAY3HSsYEA5WiVmVkXbsM1YMOqowEJKFD0S1jT3p78LWjxt3dT2rXdoqJHwwyPa3TY/bYLI3jVVLL8PXKKrSUQD0aWQzYYgT6u2OL1c/valppbd59JEEegcnC6CNEHikjWSZrJUIJkF53dGgS/FfWqmmLkb5yHzdA1couV4AILocxMkaaslox5xoxCahAzYQcADEgLBOzVo2tpm0q0vlWtQynbdPb+p86r42ItYWAyShg5knPJI+gVTHKajnIN0/IRr0byoJB3YlIqV5ymCHFCVKYgJzIwwYjTf6FpsVY46inqYwr6CECLaumINaFWYxo9MAx+dinY32zzrC+h8dg9PlEQMOH3zQ6QOkO2aSmsNh/vyEoUqgT4N9Mh/UZqDcAvw0E+UISJOHrRRI+SBad0z0cB6PP99IE6LTrG073OqfnQbJkyU6WTPkIIuWg6wqUPXs00srPNJFc1zi1wg6raLTSXg8rOBxkSRRAAAAQAElEQVQPY4WH4dWxaiEe89+mjhUqIQrWQUETV8PQZVLbp3967Mbp19C10EPvTOwH26MjEI7ODrkziYBeZSuvKgzfuIbrkyyT25PVZYbLjPlyhMpZaJaJaHUptKRihiYoUGl1HDSZEqjJKTOtNqnVc5Yum5UQVAcgqYkNUARoFEJSG95HNiK4We4y53uQ1IoyQ88BqDmQhH0MmlRVQvGDOSC73yqI/Oj5LepQnQAdMlIObFW9RWRCUJo5weOwxFS0LrLO3m7JWXW6k4iIIukApzr0/lUMKj2is96IG7ZOG5g7EhVGIYmYPR7nXRzVFtVv5w9QK3ZRey16HwGlVLEk4WtFqgGg5EkWibmDfLLO9Xx9SZY2nCcVJ8XQacwdjq1hVS+h+MLXWmCKKtI9IV1ISgp0LMWkVHuUzG0Db034XxM2G1sU1Pig6581LkvD7VPhDiAMp623LJCPMQScyUE+efynqu+tnlPZDOUnjsCZXaETt7NZcj4lRNAgj75pPTF69BOX5GZY+jJLl7eaJAUxoJGdf2WQlU4iuxZaL0omzyhPTi97lPe2QlKZ9x4JzUJN2tx4tdVqtQSMVN/QG7DIOYnsckHQZI+yN5zW0wXjJmCxUR2vdjXBoyZ7EJkGTfiQfHsEMIo0qogkkp6JgKdo0IghUx0QNLSoNoNAEoUctWXSjYNISneDz2AAahKjDCwKSyKiqL4g/w1qjPPodcVX9ePx9LAuyL74qTaC9o6BVB4wjm2PoD5yYesAcRxqyGf1LQHH1degt+2ldUaft/9B12ke1rncsre1NKy3dBkpx5Vwfh5S6d6JQJZHaYzYjhEkYeSx9IbK9E4SUClmY2ERYyxhlBYLKi6AraKvXQ7fGknbY4bThgaIZqJrpbcyyCbqLvBqug617pERfP/iFIfHYpP5sTttXV/m9ImwsrJyoqJBfxoRCKdhM5icIAJN04BkwbzJ/EQkT16ek2qKuUxs3g9uRWhtJZ2uTFZhh1aTt+mgmcbQaO7NlGpQ1xFB5FkmjdiSVH8xFJ+0WFRDmutEyUPlWQQsGoOPoAkbRW6VJnYUwZnwwIRCqqrMJCtL+eeJXYEIAEyKboekaVkIQoSaQpZtlpWqqeOgFBFEiAY1HiABZo9WDxQ9VOC8/IKOUkcSypOEpbPH6p0n6SJQBBwFEzGS/NH4SoE+gvoVY+vMSGozzZV5jP49N9QOSQmqxpGTPDrvEpKbdiStKvk+Fv11Jzs/yM6mGOrDdoaS5fRztzxoFJegsHgs2gXSw09RcVVBV2PDNiAmI+ohM0YVFlFzXPoPQVdF42PsJDbSJDHTQ8x9ZumgcssWueh9/5bGT/ODZOnP5iQtTonTeQicspHz2EBX9Dwe/VMcevDEeEptUDd8VAsBJh3vCfv3uW0UodAztgFDKxJqEfRteC1SC7mRrkHWF0VNbOBVbf8riUb+TNXaRERkOD0jNeHDJpy33miR1X+ltqGVGAqRaUGNRnvR5UEhsgWCyok4S2VvfEF75QtaXRtBT5WWI0yEKWutq4Vcoc26rUQmBDDSwPwQyHodz3qkeNWfJNs0EUnMIKZBG+Mmkval5/Me0zzsv/OW2b4JMVExEuRXyEAPqn9xH2LUGPVlVSuSkinKnxzVGKfax8nSkVQcCF9PskuTXR7HHCY7q3rptOt5u4bs6jptncvmQXblmzq2iJig1q58xAzaGIev6bRqMau096y9/Wk1xSw08H+tYpk1gCzp2yNrrH7QtLpfck3MwzoD+m7B+lQB/dvPJM9gTEXeOMOjH3cvT1U9+bXlVEbnTPm550g491x67njkL4J8oxrzXntykiwT3mUG5o6+vGLQqggFtslaxRYS88yzfQ5oZWMkiviEHCJaEc4sBhhrOWmyJU0+YCrC7dGIeHJdFb3TxoxHbEpabTdqswlVR3Ca8P7SKamuJ754QC0GbQ0kQNsfXvlS/QVQ9nJQ6pwCOlRF2iypPIs4ZQqqD7g9+Z3kdyOfjOy8ykRDm37bp3l4LPbbsN55S8O6Ru1lvf4nrSRbjaNllF8sBE0AJOG4Um8Y1agG9ADLhI4E+2TD7lcjudjZ1jCp9JBxOXu9pWElyRIb50lutkF2abdhO4OkulfkJEmiPxQ9JeUPkq41MA1CTPCDDGhRhax6SWPJ0kF9EDkJukfcr+9B70lnvZUYUDuW1jVphsrbUBHIaivp4d5KB7F71F7RaFSp79M7s/pLKcHScC3yyDicPx78p1ePpx90pxcB3Q6nZzhYPTkCde3fBTxZ7xvY8A19bClJTTYWtcu1+IG3DPzrghECak0+rzirNmt1FdGGEda0xjqcR5hUS5iOd+IwFrESlrCqPci1DBEc0ZZVaFXkjAGNyGiKTt/nLW3XiBwtp1WNw9oiWVG/DJqs+jKvVllwvelMn1HTmpryQBsAuSYCyUKLPB5hvLykh0vAOEWM21i+2KRIslX/BfJpXata+9iOx1hBhbWN8TT1NqxpWdfIvlGfSStn+zcF9MCBHizUGKIQlA6bcqb4TcGSb1hhPdd6UI2wHmusJ6AajZG1yg+yMXkFxcFybbIO+EEhklEXsM5lTvtazYOkto5qLC0twQdJkB2ct61lj7HGVsn/0WhU7KbTKdx21Ph9jV1GyueNLTHXs54kkuLeUr63RBs1Dj2UE+oSS3gcqhO0R19p6wsi4SBibX1VqoAYPcoGMSctlKn7qFXNDGrraKyxLqi8L2s1/pF0zhtL8nf74gJO5yBZxkPyKIKGjqD4Shz1oLKOpNUY9qBLGLb8EbZcc6jo38CKEk4eiH4ykyyT13mTgyW0riESun3IDH9pF3PXXsuIVRHo5AUXAH/wK3Hoyivw5UsuwUMXXYInXngV9l/1Yjwh3f4XvhDG3i3Jy3DwhZeieclleFyvvXlcY6LJPZ2tlwnZE0umfUpITBB/iB4yksg8TdriuxZ8hSTsezcWhUW2iSKRxUXMhEc1/vySF+KJy16AA195Ffa+6ErsU37flvx+ITzeJ150GfZ+1RV48Ird+PJlu5D+4JV4RFsn7UKFqBWzicIPP/+EsUAPC5JgDBAfYpZTuSYeXQ+ShYR8jWazWUmbTH29jPm0865nW+/nWjof5kgrxgjDOsPlrme4rUkzAUW6dV3DcQyK63YQF67qgfLwY9jzwMO46oAeyZ+/Ey/UF371Zz+Pq9Ym2HnXvbjy0Udx8f0P4UUHDmLp83fgRZMpxp/9HF60uoadd9+DKx59DBfe/wAue+RRvHjfflwuedUTe3HV3n3YfuddmHz2s1jSA8A+nQz2tW3bEgtS8RNsb73H4LTHRnaxs85wucsGbD0CYetVh5q6AdPpRoFkMVWdcqNbFoVIGmjhVY0vBkXQrYhtVSucx5bGuOqaV+MP/4t/jG/8z7+Al/6P/4KX/Zf/iFf82n/Ey//rr+A7fuMt+NZf/WV8y5bxH/FNv/bv8LV/6y/iwJ4lHBTDrmOGaIITsXgl2Pl49GdMIt5pAiczBCR475ScaQwz+DfQtfY2qyS9yg7NpnhMRDj7iivwtT//z+X/v8dLf/Xn8Q2/9C/xjf/jV/BN/+U/bd1/1f3G//n/w0t/7xfxit/6d/iaf/szmH3lZdincRzUQ4bqt9abyLjJZf9cW7vyl0iB8H7/VP6l7rLg2MNka4ImCZKlmCR6IiK5qTcZ2daSPGLja2ydydvSeYPsbMzjUfvgATNgtoaxHi4XEdj12GO487/+Jj7w5/8iPvxjfxvv++G/hNv+9k/gI296M27/uz+Jj/7Am/GZv/63cP2f+yu44a/8GD785h/Bp//Wj5fyz6r8Qz/4w7j5b/xdfOT/+qv4xF/5m/iQ6n9Uth9681/ER374L+Ojwif+8o/hCz//77H7NP7anH3uAR2knJT02Y+L5GY8bOsyww95ywFbi4A5YWs1h1oIDGah04pEf9OS3Jzk0NGoiaSr0O059s1Rr/BRK+gKD2kLAi/Yif1aZe1lwhdV/14RzL2zFneurOIBZjygfcqt4KHQYm1bhb2LAXu1kgqxgl/X6xD1yqoXae1XUmQLrTw9EZMfJuo7VwEBEUWv/r2n6ZVz1FdZFPywidqz9IrQ7UWtoJ/QyhBLizgowv5SAO4eVfiiVuv3a/xb8d117tOXZ59dO4jPrx/GQya53dvwhFa93gZJkQhVLLGuQtDmCvUAQXk4QqwsDlc+lryvjcdnSbLUIVnKyI54SMIHyc1ykiA7O9eFDpL6PHK6XZO9pbW9NNFnm8agrY0s7xuY5KGY7AkVLl1vcMWBFbxo3yG88PG9eLFWyZc//jhedPAgXqhV8Ev278dVTzyBF+0/iCseP6DyVVz+2P4ir3zioPQrsHzxgVW8cO8hvOTwOl58aE31V3HZ4/tx0Zcfx1fkiLEeXjjFQXJzzNDRj1XJMn5L6wynSRb74LhX+jYbw7HVCIStVhzqabJTS99TBMI3qU08MX0DOx+1OrVsFf1ZDJiK6wy/dtvWMO9lkWCqRsBoCYc1kVZkMDPVcIxRXMK2pW0ikVa0Od0S/K3+QZHYfjHFeLSMapIR1mZIqxNkrYD9jwxIsYhI2dsbjR4QEz0MGj0s2trEEqGqSMVhf26ADaK+kIoKT7uyjjhttX2jwYZFxFQjzkZY5A6066oo8s/aRd8KvH2xJ+7C9nYb4loEmhq7q+3YVS9r64VYF9lNY1vi2+qhIl72swRRq+ptCv6OXGnFzyeRDMlCMCTha+ZrZzg9D+uggySCyMhQFtbbznpfa0uXWVrfl8/kx8FpgzVWSIu6lsvbsa7rMQXR6juAsYJrLOuL1dzOMNYb1SxPUC/JnlOEWv6FGtXCNkz1lB8t7SiyXtyux2QF1ou6NjVivQRWi5ilWOTCsraDOMIhXWeoL5zisO8xxhKT3n9Xsb6qqk29y3q9pfN6e6icHrC1CIStVRtqOQKp9W8WnDox+knpm9VWzvvGtnQemiBZ61Foc5cCdIgDRTBQSepWVSK6ejRCrCtQhd56WF09iOl0Ah+axxY4U+mtlDRawOo0YZwqjGbEUhhjsa4Lyayvr4MkIgNqgRmlDz0n4LoFiEgqK2nJTJTDhO4thqWlBQRV1KnKufwYZGV9Ai+oqzgutn2dM5VQvNoJRbpqR4SDSULWPm2j1aKIARxV8FaGH0QJCXIfJumIgJHIeUylND5fD6M4c5wPkkdpSZa4kNzUkyw6K3ytDZLOllg60fdhGTcIb9vysrzRw0RfLE4UlBkI6jpHyQWNz3+iNeutIwRgXdsg0BvVusg6B8Irc7frsbrNibYr3K5X4mRXbj9c7nvG9n3a9v2XoG7jZCAJ25OED7dpkIT7my+DDpf1cL9SDecWI6DLvsWaQ7WyujpVGHyjkgTJYup8v4KiGKnSSq5uYllhdq/dgNZFIuis1WbGtoUI6IukND2E2eo+jGKD5WVivJigpSgyA1KukVGfsYTqxTzCglbkiyLoZeUbkedUme1KkAAAEABJREFUZBG9MhLsb+W9XG1ZjLXaDVr1meWY3W9Ao9fxqVZxDaPSzhuwCVIADh4+AHEhqgVl2gkmmGG0QyvpxRHWUyOi12pQ/W7F/5YVZtUYa4rYqgDFAqK7hXqEsQAd/tlgpvyR/wo1soitsSvI0EvC5jUkCZIl7zGrakn35EN25cfmSdq02JbEMR++1obbJFn6cBtVVUGLelBfBo5EzIuxwkjkazsD2nIiEgzbz/R0YbWAaZbzGnPS9gQV84gWNVtQsR3rBrIchYSQpnrXasTnCZENxlXGru0LWKih2+kw0mwVWfcVFIdjXH5S1v70Y3Ah2Y3jeHqXW29JEh6n0wO2FgFd7a1VHGoBJHGqY/5mta3zvtmNkAEKvghOQ4fJBJqYQS+pUZisTeFlp1c7UTO6bdbRTFfRaELaLqkOREpJ2IrU/NaDICDplbqOAUREEFlU2sJo2imyJnoWQQcRA00KcjDKKkhHARtHor0JgOwgXxLVpsqWlhfQasXnVR+0mo51jYn2zw+vH0ZdV7LwaVvVdT2h1D8NmWXTIsOr96A3AYyXyxtHK+JnVtjaFoEVsq6T/XNPlsrCv3+eagukFUvnLGMXboDkRgrwdephu3lYb8N5HUmQtBrWBy19SW7qXMfwata/hGHWCOxv2wAiZShfKfZZD5Jkv0TC0ybBf397qgfnSFteM22LBOndDkm0zRS17o3ZdL2QfDObICqcWe26PaenKltZOaR7Z4KFhZG2xxb1BraO0zmSYmR4PLYnaQHnvVp3GdnpSoE+SCJo7IsLXklgOLYYAV3GLdYcqgFtyiaCMwmFb2qjq2OKSUomeHXX6h43sqSmHaIIcByWAC5ivQUa3/QiHOYAbw+0oAhe5OKJKJjmNE9hOJ1FjFEOOk1NfMNp6wyq/UK0EElUCStpHbmu0SDCBEImhJiRJRtWkmMEL4e14staCdeqU6lf/19SgAwflG/UajxIGpipNRUF1Yf2otFWGGttV9s4z+R/C/sCtdP7Z2ldKA+qhHlpvWEdNKYoP6L8L32JuMaLNRKnalf1tNo3eTjeIQNRofYjISiOEFIIMIm43CApNeG0QbKUk50eOqzv2yQpTXeShF/3vWK0JI9ux3UcU/fntLcawAhUNbJGqNryGfBKuhERt5GYxoApCeqBmbWb5i0ZKp41iaT/ECRF4gyVbkUgxBqNxuy87kxA7bttVS1lLndbzk/UTlWP0R8e17Hoy0iWpMtLQh8kS6xIKocSM91qZQzW2NbjXFldCcVg+NhSBIbgbSlsXaUQQtulnuqn2ENNeEvA5JyUDqJJikBhhQgtaSIk3/nwJdtADmWSmBRqEav8KRPFE4MkrPNEUXPFjuRmuW2CCuiJLiTNriQibm1T+nBhkn2LVl/4Neq8dX+IiAwqzFpdzuCHSBBJmAChdoL8NVhsgeIyPD7Vsc5fVEkqp14SyM6C5GYaOuz3sZC6nL0+qL+K0EMjid/Vx2yGqV7bvYK2YR1q+S+9MkG92e0gAkteOUsnVzfLSZb+HUOSKu1Ox9DxJTtd37elLcgjese01aq9L3O5QbK0TT5ZZvkFESlJRKg8ZVDIAHLQ1aGk0kGKeUhVomp5JlAzxbyXJEEeHzb0WDwuy3n0OtsYdIPlw7nkjwFPQwQ8T56GZs7PJjSZ+dRH/tRa8EQxTAyGV2aG09a7dZKQr2UiQocnmsRm3ulTYb4OyUJsffunqnuqcrdNHgkl2aVJFh/JTvbtkEfyrmu9V62oKp0VSlrKef9ISiOy08PEdYyi0AfZlZGdlGrzdByNY+1Jlphi43C57dynYTXJYtPHnuTmeLBxuJ6TJC2eNZDc9I3kcf0+2ThwgqMf3wmKB/UpIjAQ9CkCdLLilPS+fjKDZ6BsfnVHshCUV309SZEsXvQTheTm5MNpHJ6UBnmknfm2TqOJk5qQ1Eo8FXLoDckjOpJHlc3b2K95MtTAdIZN+95P1+nTJI+ygY6+zNKQavOcarvBDzzrySO+OO++LUlu2ju/mVHCPpI8yifbuK4hk3JaZzhDdvZ93rqzBftguC/D/ZBd//Z9Pk92erKTLjsV+vvwVHZD+fEjEI6vHrSnEwEtxurTsTubNv2XNO6D5ObK1pPOOsPpfnVHshAUdPQTUskTnr1NP1mdN5z3w+GEFU+zgGSxJDvpjNu3v5ZkNyanPQ7DadsZPQHYXt8Q6pyV/fO+zH463ddz3nUsrSe7fl0+j77Mdj2sM+btel9sQ3ZtkZ3sbW3TwzqD5FGk3bfpsr4t13H+bII84of7cZ897NN8us9bGi5znZPB9+fJyoeyk0fgXCfok3v/LJdqpfqsE/Ta2hq8wiM7IvOKz79fts7h8STyZDKBWTo/r3f6ZLB9j96OZCF5Ex2e4kF2fpMsLbkvJ3pfnTecPxbWG7YnVX/jSz9SaSldRnbtOy1VIcWeAJ2fh22Med28rcsMsntTGY1G5YFIsrRLdtL1ya7feZ+t70F2tn17JEuR8yXxDH2QLL7Pd3c8n8nOjjwi5+ucKO34nahs0J86AgNBnzpGJ7TQzRdPWPgMFYzHY+hBUQhT/pS0icO6nkBJlnJsHD0JkNzQnFiQnc2xdZz3RD5xzdMrcRt+mLg9w7U8jl72OpJlDCQ3CcVlrg8dpc7GHnRJS+cy25BPrmO9TI46ySN2fUHfxrw9ybLP3ccXOsiOkI+1U9EpT/vr62Xp+n2fJE9Z92wbkCzxJnlU/MlOj+E4qxEYCPophNer0qdQ/WmpaoL2pPbK2UTnSe7JTnJzZW2dycToOyVZJhxOcbguyaOsSJZ946fj9dU+G46lx0Gy+OV+e3/Jo3UuIzufehvXh/aLHYeSBko7blPJQjKWzpsADed7kCw2820fW0ayqNyGfZ4fv3WG27Ushvro27NUdvO0jWGFx+DtIkvn3YblsXWse7phH4z5dt0vyc34ufxEwCkOt3UKk+dv8dMwsoGgn0IQyW7CnqwJ39guJ1luePLUdXAGhyezJ0E/wZ03Qblf6513cyQLATltnWEb50+G49n19UierOpplZmU/I9wbGx/SZY9ZD9kTIDzfZEsWwq9T2SXJ1lWtHp90FmXNNmV4ZjD7RnzavfrNg2SJU62MUjOm5Yy21vZ29vOaevIrt8+b+lyoy8nj7Tp8Zvs+20pksV/6PB1lChjdn3DecNpt92nne9hneG82ya7/py33nC/jjvZlVl3LNy+x+p6TluSnb3Ttvd952tlSXZlZCddxzYDth6BgaC3HrvTqkmyTGry+PK0GjmJkSfKPMiuH1exnqSTm5PcGZLFJ6fPBZBH/LHPhv0iT09vIjDE7Drbsrp3vm/HbZ0ItjNcbnunLUmWX8RYb1jXlzltnUF2PpJPli7vQXLzAU0SPtxO36bzPaw3MZr4SJZ6zpNdPeggWfQky7UkOzlvR3ZjsM7AxuH2Tf7zD8CNok1hmz5Ddm2T3cOnLyO7h6nbOnYcJIt/1mM4thyBgaC3HDoU0jtVdd/MPXyz9ulenqr+mZSTRyYSyVK1n5h9fyTLxLGe7GzwLB4kj/Kn9xM67KOhZIm1y0geZU+yEBSOOUgWO5zicPteTVqS3LR23vpNhRLu31DySSfJ4gfZSdc3jjUkWVRkJ/v2yCfnXb8vdyWys3HaZb1/JI/qm+zytrONbZ0maVHge9EEXTJzH+QRG/dtuJg8one+h9vpYVujL7N0/5ZPxqA5nQgMBH06UTqxzWn/S0LfuMfixM2efkm/eulr9JPF0rq+T+f79Lze6WcTx/Np3te+fF5nf3s9yU1yUkLnXN6Gp4AJxARmkNy0dn/GpkIJkqV9JTdP2xi9Py4gOzu36XyPeRunrSc7W5LOFrjMbfraOm2UgrkPkptbIdg4yCNtbKjKg81tuQ2D5JPG0NuSJy5zXcO2ZGfnPEmrjoL1hpX+TsBywNYiMBD01uJWaukmnJXEST5IlgnhyWqQLCu7Po2neHiP0RPQzVh6Uvc6+bf5uu+y48H1nk0cz6cz1XmchthIZz4Kpxqb67k/x83S9tY5b3Jx2jpfr3lY5zKyu77OG9b1cHskrX7SdbCSZLk3nLatJdnpnPd1tK5H324vbdOne2nbPm1pmx4uI7v2STpb+ie5Ka0kj87Pt+Nyx4HstjvIztZ6skvb3nljYWHBYsAWIzAQ9BYDV6plnJKgbecb9nhw2VNFvwIkeRTxk12e7OS8HXSQLPZ4lo9+stsNksWneV/JJ+ugg2SxxcZBEmIZnSzAaR49efn6uIr9MUg6exRIbrZNspSR3NQVhT76Ni2V3Tzdh7GpUMI2Rq8nS3sq6bbQjueLbf0AMYrhxof1xkb2uOJk5ccrs4482qdjG7aNdWRnR3bSvvsh57IBW4vAQNBbi1uplYFJSZzkwzdvj34i9nnLk1Q9rSJ/kWRCI1nsPSn8jbp1VpAsROa84XKShVRI4tk+SG76Yt/so+E02ZU5bZ3hNNnpSZaxkcSxB8nS7rH6Y/O+BiRLO24bGwfJJ20hQIftJcoq3fJMQJ7YJ5KlKbdvkCw+4ZiD5Kbe9xOOOVy3V5FHr3JdNg/y6D7ny5zu23FcfJ9ZWu9+Lcmj6/f2li63dB3LAVuLwEDQW4vbRq24tpE4oSBZJhTJQhhkJ33jkoTWSUJ3bv4xMGVb1mhZIRdlIw1Q/qQmktKG8kqR3ST06/D8isoTxPl+Msm0kIr11hk5twgwuvbgPyVqQ6bSi5MGc0AOCZklp4+ApLTR6aB810aiHlvIsDCUcg6t7CG0oVU6obMOxSf7Ytg3Qx08Sd/rXG5bw+lWrTflr+3pZaZtkZoM/2nlnO0TYP+MNmjrQ420G+PwX+Czf40MW2SUsaAtWxGOm9u2TlXKqVaR1A8UMzknHUE6ei7p6tveQNS4AmHpvJE38paGdQZJhBBQMepaBDT6nMkf6IhB/tsn9dv4LxtKF1QebRsiSBZI/aST5KYua4ybGSWcJ1nqtuprHn1cZaZhKohKxBhRVxUsqbxtJIrfCqvuS+UUTJ1KyGcFvBUa5Vq4hhLDuaUI6BbYUr2hkiIwmaW9OSuEYpsQIpqmRVXVKiHIoBscmvgiUE3ARpOkVYnuck05YCoyQQxgRUyaCapYF3KJurFDjkhhUcvzKGiRHhvM1texOF6A/5dXRhUo+xb+f7pmzXSWP1YZ4XQP6yD/nO/tnLauQwumCUZVxnR1hvFoCT4a/4F2zpRMoD59mmD9N6up9iAfy/+VRPU6QoZVajJJJqhRxAxNXI2tBWahgv/3UxA5tlWLtbSGalRj1ubiryqqgVDS9q/3dV7f61ze61v7wax9pgb1CMDSCCGPMArLImnFR3GfRmCih8JM1GdybuqAVAUEEv7Towo8msDidzbbMEGXEhApJUDXSj5ar/oUkGeIROFpWQLqQ41JEr2UW2oPUDSKPFbv8l6X1B+lCFOqzQpNNcas0jjUrv/wvvtDVE+6TxDlt651nqllxYUNsZwAABAASURBVBW6OvNki43DOidJWqDPB7VpBdnpZwpqVjpJ77F6LG42paxkhP2KjGj9P1g4eBizicceUel6Wue23Lbj5ZggNUCbQClSHGE9E4nBZgO2GIEhelsMnKutsnpobWGMAwsVniDR7N6Bx0TEhxdHOFDXOLQ0xn6tPA4vjLBXk2BteRH7NPGfoCb9BRfgUU2QvWKyg+MaT8SA/WKZ/SO1JewTGa+PFrAgW2givkCTpnp8LxZXDmN5soaF1VXsEpHuamfYMvRA2SUfliZTLFYRM03ETKAaR4RapKoxhRw0UQVNVChPEiEERI2Dkpg7ktImHAkRZULMwLal7ajc6KpeNtbXsH2yjgtluHRgBRdpTLubBlv1f08zwyVqe8+0wfLKCjBrECcNluICRnpQJrVtXzsANNEGIgskyzigwyQjsXGmDQn4YVDrmvT7qA0yporTXhHRwVGNA+Mx9qqfLaOOOKB75LEKeLyqcHhxSfkF7Fcfh3UdovrIMYBBvsh3kroE1AMiICj2hn1Pug8s59HrcIKDJNoYcFh9HNL9enBpEQclV5aX4Pv2gO5f39e+f/frodbs3o21bYt4TO3tq4jZzp3YX0c8MYrYq/v1iYUK+1R/72LE47qf9y7WOLS0gFWNRVWGc4sRCFusN1RTBB6t80MPLtWr9y2P85cv2InPi5Ee3LVt/Qs1D92/ffHxexeqR7+0bfGhO0fc/9CubZ+6o8qf/eLy+DMP7d7+mZtWD33mge0Ln31gPL79oeXFO+5ZHN1+z46Fz96xXH/+84u8957luP+JKmFpTauShx/X6nknLljejQvrRexhhT2aQHtE/LtEGrtywlawEzoOTrHjsIhu2060Ivup9gemIjZP8BBEBG1GEJEGSejho7WbhJhXaUqvFrRK8qdvpR7OQ8SeMdl/AOO1dbxIfuPgmshzCS+olnFhG7EjVdi5Rd/LeNX/tkPruDgsYle1COw/hD1yrVk5hDyZYFnkWmm1Wcv3Sq8AQWADUKtQVYUJrW4yxi4XyVWKJeYOqi20EClWgFa2WQT6KICVC3fjkQt34G6R68M7d+Hhnbu3hAd37cYXti/g3ot24UuX7cG9e5Zx95j48vIYhxbHIrcaMxFpoz7ta6tYtXIoyU9vTzXabiEp/zr4epFdmjwiVf2o021Z4YfNwwsB9y2PmnsWqv13jcKX71qsHjTuGPFLdy/WD969EL90/7aFA59u12+/o8KdX9w2vuvhXdv2f47NHV+o4313bV+8//bt40e+sGPx0B27lhoBd+4Y495tdRnHQ7n5oPsasLUIhK1VG2o5An/7ve/4kR/8xMeWX3/bLeFVN17H7/vUrXzNLZ9cvOYzn9oh+QLpL37dbTdefvVtt+5+/a03/pE3fPpTX/N9t930td93681f+6fu/NzXXqP86z7z6f/j9Z/59Fd/36dv+j/e8OlbvuaNt33qD/3Qpz/zkh+88WO7/9wNH+MTt9952zt+5G/gf7zmarzvR/4yrv3r/x/83l/6a/idv/Kj+K2/8Jfxtr/+Y3jHj/6NIziT9F//m3jX3/i7eP9/+BUcePghTfsGY62SvP3QiNima1NN/oiIgEJWYjVP7pRbUCTOJiFoBQsQKAiAbPWxQdrAdm07jGczHL7rbtz8kz+N9/y1H8Nbf/hH8J4f/TF84K/+KH7/Kfj/TrXxnr/5d/Huv/pjeNuP/FVc97f/HtK+x7A4Iiq50kwn0DMOsSFCgsYQBIiYIZrTW4weMnqmIqrMUqUgCWXhI+gj661iHEY4vDrBqsY23bEd3/BX/hJe+f/9f/GGX/kPeM0v/PyW8bp/92/xpl/8Bbzh3/4rvOpf/jO8+t/8HP7Uf/kVXP2f/xO+/g3X4FAO2lSJyHqg5BJn+S6fEpN87L1E8ZnkkyROcpDEZDS65Xs+9Slec+NN9fffevPu77/ttkvfcMtNV7zhtluveONnb7vymk/fdsXVn7vtytfcdvOuN37hs//H1Z/91B9Q/qu+5+Ybdl9z+6e++o2f+9SLf+CWm6/60zfdesmbbrx5x/ffcFMtyR+88Ub+0Ceu51/4yIf4U9dd920ncWMoOkUEfA+ewmQofjYj8NgX7v6Plzy2H38kBVxy/0PY8bk78aIHH8dXP3YAl93zJVx675dwyb0PbgkvuO8hbPvCA1j+8l7srqImfYvJbB3rIjZvYSwtLCMyIrBChYgaYfPVugKlYwlNyCjEZ2kk2WUVZRFJK3JeisRFgYj3P4jlB76ECx58SHgYe+7/Ei66b+v+X6y6u+97ABd++TFc+Mhj4AMPaDU8007HOhotlYPGFORnzYBKYwjyi0qTBEn0K86gNEkUnwMBgVQ5CGiFXWs/ta7HaLU3fEBpXH45cNlFWFseYWXPdhy8cIu4YBue2LaM1YsvwuyKi7G6cxlrMQPaCmsWl7WdoiiHWpGPoK4DKX8CAMUzV5IKdouMnDP8xmMcm5ZVKbc8FpNR9AvBseohfw5FwJf7HHJncOXYCCw1zYMXabG0fPggdq+v4ooQsePQYcSHvowrxSgXrE9woVa6W8EL9MXg5VopX+j2R1FftIVN0qJWbTOtHvWdT5n8nvgUEVjO+1hW1lA98QrlT1/WMOjLQSCJTILq8fAKtk2n2LWyikvV6J7VFVyoB8FT8X/P2gwXaR99u+Kxe2VND4GA6dpBtKEBRwETbdcUfzUWPX2Ka16NbhKZyM1FLYEezYauGOtjLJJP01kh+LVJg3Y8BnQNsLyMvduW8Lj2Xh8fV3hCOHM5woHlJTyiraSHo9rQtsYT4xH0bS3WRMZJDxXIwVCcS4VolUWr//IGAF08+UkS5NGQevN0HEiWfJ9uquoFRTF8nLMROM8I+py9Did0bG1lpan0Dt5igrW0KtLTa/sSwTqhaSeql5G4NcScUOmLu2p1FdPJKtrcaL63iCLayFAIoVHbDVjSnthGq/1aS8NLZ6odmUELOmVlS4gkgUbE09ZqMmaACYuR2Cbdgr5kq/JMqolb3rL/ASKndg1huopFEfVIfiX1tS5HWn2xlfXQSepBOzPQkJD0kcoYs/rOZUyNZsBM/s5EXh5nq4EkZLWcIM9hLqa+SJVae/StVucJq+prTQ+ZiR5MSaC2IoKwFUl9oTkRAZftk9EiGj8ABJNzQAWTs0InCWTtlWc97KDD0iAJskP/RkB2ebKTMj/uGRm3HbdgUJ4zEdDtec74MjhyvAjEvG3WriOHGerFSlsQEzTa/12INZDy8Wqcvo4ZC1XAWGRGpWOM",
        "6L4QJGq/WocK0Aoyy+bI5M/IgRDXFRy/M8IrPZPfmkhzioRaX2pO9TAIIvNmfUV5avegURsmwuO3ciqtH0xBvozqCmPJiX/hsnMHGvW3LhJNdpIB0DhSiJIEo/MQqXVoID+k8oOolW9Zjpv4ekwm66gUn8iMxVENr779z5cz1ValawCIovWhU83o88zyJuCaY9SjBUDxX/fPL1NWPygPkKDWqxz1SYQQQFKIiHSJ0iLs3tfjSZzkIGN9kuKh6ByIQDgHfBhcOEkESC61/oYrhrKCG2kftEzYBvoCjIgilJCJrQA6ZqKzWWy0Mm8LYUbtddea/K0eAgkZU68e6wqjhRpVVRXSMElp0QeIMEKMWNSeaTWq5F+DCMLkkcpqTyQjG5NjI33Wa7zcRa5rrOkVvdWKOol6wpb9JyZaXvpBMFN/o2qMtckUoRqpVXmiduWk6FqEp7E0gh8HORBgIyindJY1c9BnRMVK/lcaWkQWKfu30bM0lbeqnWYIwtqhQxh5PFO1ASDJh62AOaGeNVgQIQdto0Cr8soPEiS3CnWPkJXcOP2rGXUFSxO7rxUR4cPk7Oti6XyPPh9CKNdO95OeAxG2jSG4o950kOdgBMKcT0PyHIyACK1qN65SUAaiEegboiDyCZuTt0+cmbR1ExIMNVdGb1KIImmxE9yd9Y1Iyav2tp3JJou8QimbIcH/yGYymUjORGJuUR5KVCGgEtm4PYjMUA4PpEPLAKOooQolcWbSvmW1nYSgjLcZ7HsnqYdXAEW88giZhLlMi1H0rOefqoleVdsqolagK30p6J8Umtg8/tHCGKiJrH1tDUfbwzWWtU9c6c1CUfDVgA/3uRUZE+CtpoAst7KaSIJPPR6ZkYRM54+AUlDjosyPKTpidJyUx3SUOuvpe5RiyJxrEQjnmkODP0dHIPvdWpMxpCiy0cou1TKIQgCp6alZmrSsSluQXpn7X9rN1FyhBfcjqPFyigdEEKmsrE3ESfOZJLwaI0Vaer2Gjkb7uqITMAblMjzvg758rLSqjSIRg2osivGCyN/kEvSQgehN6tLHVvwH1LjaCPLZxFyp/UpL+1oYtdAbBhQzlCOUT9UoK+ZOWuUtnUpOjNVGLbiNoLrQqtblh9YOY6YHFCuNly0m0xWsrh6ENu0RGUr77p+qe6bSdXK5do6+wAZQHszQs0IPMMBvB610zmt4KlKfgEjdCEqd2TlP0kqr0zOrP1g/sxE48yv8zPo39JZzQ7KbmCKBLDKBpGeWSc0TvAuSNU6dgfTEJ5A2SM4EYxJI6s/duDWSyLLIeh3v85Yh+xPlddk+5UBQq0prc86gEn4Vj3KHGVodouiCGuYGgvR+zUc5ZLgFydJGqVg+ovKVmjKc9uiMzi7DviWiSFfwBNDGDSr5VMlD26nQRUAglrdvQ9DbgP81YdJ++uLiIpa2b4c20ct3ATIq18YVmIPFaedtbOI1kq6F/ZJnUic47wdoBxSylosqU48aY+gzRXNmH46BoVp+FEk8D87n6RC6O+p5Orjnw7AqcurVoeFJmRA0eTWNu2Wehlg01p4xVFkrsYCYKiGIWIgskjJRmDRcHqSJgjgKfsUHxX5JKz0ZeS+ckiauJFLOqqAsSGpXgKiUd4tBZZbyGibAIEOjpGUTkM7Yd9cxuXt7oBAxE7IaTELpB1kty1d3pBS1v+tVvfd5C6yTn1DfkC0Dit/QQUSAAojDh1fh8Y31hWCU0WR1Davag/Z2D+tKNV17a9DLRFkhe5V8PL61zmg1hjI2qB/qQ6dGps+ndqptB+mpNTLUPqsR0G15VtsfGn+KEdCuwrQQkNpJojFNKjT0wkeEpH3RwkfSYwugGMLEX4lVDffTioRmYuOOALIINcF6UhQrlP70+l9WxyKwqH6DnKLqSaCRw16dkYRJTG4jiTy16wCXm2isczshO2UEfZw5So3SRtIKM6MNCWkDLZVWJ606yUorCW9nUKGjV7qGeiVZ/FMLaKFR6ymUhaTxJI1teWk7Iis00xbUlo3/8c7S9p2IowXMRPoeG9T+VqDe4LgYvn5yFVHXAlk5wQ+gTDgrT3V6EPKxmCgtEym3fqoJu7D1BoaaZz0CvsfPeidDB1uPQA1OA0wiQAooE9qEU34YW6aulCabLSDkCO/ZjrRcrgSqDRNCRwCdz5X6DtreYG5RSAjdQYkgY6+iK5GZtwEg20a2/6QoAAAQAElEQVS2ICGOh/00UbYiTcNfRlomMUMW3A/U51MCMtzWNCZMqgRLo1G+iQ00LCGLapNW9BTZEgQQ3K+0DVvM0GCWBeayonUd18ga3/rqOjAjRnkk8qwwW51hcniCVnvsiEEt+Uz62AKYUGIgPyh/Kl1gDaFc6Cj2detySW13p1TF3tI+OpZOd6Un+zx+mcb3VKofv9FB+7RGwPfA09rg0NjTG4EUtJehiaxZK2JphUbISCFD/FEAabYCT/6Yg4inqx3mpqvb7sgD8IrY8KqZJMgI17XOq2QtmtEfJLW6rOFfP/i3z6CJS21IJsHtZkno6PqjUgRdgDOVKIRlomoiCrmWZtRif/Z9aZh6aNBUiKhPqC9I6TFktGj1YEnaunEeG0dWABZGi4ixEqEDQSvpSivnWvvQqEeqrs0fbhg/ReHQGyUmSrAgKC6Aybpv3uMzOatYqiCc2cmjzbuLc7RuyJ1DETjzK3wOOX8+uBKJMhcrrfSqPMU4zVCLSBqRy4wmCCGLrAWSIqHukvZE00uSIDtAB9mlTZr+lYKoTgSUEZoGdaAIdgHlizAthZPWngE1qiBUI4QoNpRNgv6Tgyou+7TuK6psNpthZW1NOpQv30wwRoCPJD9y6YuqTw1PTSkPON1jXjefni8nlRPZJ/mQZJQQ9EYQ0L8R+Iu/rG0IMTL8QJvmGbLGF9QT4xhJ9aMKa9ej6krvtwUtvqWhxgtMZ+saRwv3MUPCSjvFIekO641iojqhWkAzCwhc8A871MIYhw5O0OqJQYyRU110B/avlTS0Ere+iktIWoV7Dz1oBe/V/lTLZ+9HqwIAtZlRyNn+2E7K8hDqtkQo8u4AVaB8yb5WGpPHZVgPHUG6rC84LaHtKeheCbJtmmlS8XCewxEI57Bvg2uKgFaAmqbQnMplFeeJWlZZmsDZzCgbzT+Qmqzs7HJO0kI6wF9wJS1xe5k1OSFStPRqLKkO6ohskpPe2dy28K8WZiLarImf1LbtNcfRqMxpiKygI1S6hWJAlF2Aa0up0zYGEhGTygWWkQRAvkNE4bxtim/qu6Q3pFe0ff6E5Vr5ug235Zj0/QT1FfQAc9+jWCFqbKEissahzuH2ctOKRLPjqjzgtwP3Z6hEK+oG3v4YjcdgqauYRmCslfOyVtGLflgpIO10Vtp3PEajCpaLi2OI/9R2W8rcf6+jHG7bmeK7Dv9+3H6rkk7FxT47NpnKP/kkEliuHyRR7gfoCO4sBnh4rppkB2hsASB8JLVK1UnKbIBJ/m3cKNIO57kZAV3Cc9OxwasuArmtNCX9a4EKLTuJLNJJETHTkwzQZPPktPTEnJdVJTsRTAhEiNCR0Ze3msjraNCIV6Zqo1E+hNBN5qbR6jerjywiyPCvNEzOM9mUfWZblU7VHKFcLrYxKa0ugsgmi5wtqbRhAg2IcFpWaolQ45tgxGZ6Xt+njy0niajl5FjLTqMu/RAQLSWVZQbkVg61gLgUPoL03jOPISEqLjlEpA1AYy//ulGErrCjjRkHJyuYYAbKNmqfOq+tIh88hNHKGra3GaFdR6xaZFkF7XmnvI7xAsEwKzrnjYVF+bJhY3vb1jXlUgWmkVb+sSCkAOQOqUQVSJSZzpABLbL1BpVLrKEewAyqPARKZlll3RPyRyt8PwyAJF0HugFDuqzyrPGocDjP4QiEc9i3wbWNCGTNr0bs1DBAazJNS4qzguemJMqRVeLVH5g0UTVZNRGd96QkCeuhYz6v1TmyJnbSKthS0xoMwRSKmKBNjYCglW6g6quuicI2ei4oB1B84NUoRIKhkFWGuoXt3R5iRFLdDNVXJTKCpHSAssjQkUPR0b2qbF7Cx0nKqTKA6pPyhW4BVB/yAl3bRG5EWLNGBJ3gg6T8AygSjtGkqbq0Xx30iaiBVXJQ/Ixt25agkJQVbytSW1wao962DFSVvr6dotIY1blIMSOp3PGwhA+143yjFXOUnaXzDFD1CgsLSwpEEKLiHRDLeAJAqTZQ9puVTgJ0BA3MUFJx9JiSxtoiadvLKSCBJHyds+8J3Q+27fJOAb7ukJ08ZqcZPs/VCOhuOFddG/xyBFIKIWtGz8QSU02nRtI/hUsuzN3ly1pje+IbVgfZkCIn6f0/QO3+mbYncSpEYhuSZSKHUQ3WWpmLpBM9bZMmu5acJt2UYeI1WbtNM5v4ATmobTci5LYBNhBUqCYgTtdeKVEeKCFqJdojdPoYpCOSSNK+Qx1nEbz4DdDALJ2H9FAeks5bD+UtS95p+dKK/KB+Erp2yypYeaisEvnWKZpD5S00/haN9vG9797/JDBr1Zo1Xgr+p961tmZrkfpIe8STgwcRtB3ivzWS5feByRpW1w6pHe0zL4ikwwhJe9DU3jLaSrtF3lsO6m+EqD3oqL1peh96o1xvRGh1IVvVaRvFgAFHjtSRZ0iYxQT/EqVVQI2MgFRsiQyi0dicL3pfrra7vqQqyM9eD9XLqpcy1Y376pCkQ4hRyuE8hyPgq3UOuze4FoMYRmHIml+JnqSaxJrAWfks2Wq6ZplQdJA8GRNhKRYtsoojhEpgVfLWt6qcbZcAMQ3SxuSWCk22EgghiGS68qBJTxIkgUD1iJKWJ/DeddZSM6qMKUOdCBlZjRXyBUo7VJHzbj0rY9KRCfqDZGmTPCL7MkvyiJ7s0hqGi46ASWvGFv7i07/scPtB/bIYBtj33jjp9b6VtZRagUN9B4GbUA4EsGNxWbGImImw25ywsLSIpR3bwfEI0yZhJp14HfA1CCbsSvEJBdYZIdbau8/wX6xz2rqMoFijO5hLX1StrDGUgCEVEWRhP4yoAQU9TJiDSgNaXQuoZtaDOCUgb4wzwrwbUA7Zliem+iv5IoNqRYF1pxs+z9UIhHPVscGvLgJtbrSVqNmnL/qoaVlmLTLAFpqSyCSohVAcjYq0ts25UI+YBaGuEfQ6DtnYLodQ6thOZuBMhLa+Dqh9v9qb4vw3lUNtO/XrPgVP5zLRTcLqOZGAMNPe7Ez73C2DsoTtKkiKuCphLPYaaQVa6yEQlaZ09q5AZOS+vNerxSdSBS9C0eePlceWm3vEbdqHbguCWMrI8jcJWS6avAy7bR/Lg0d+MwYExUJegySyyM5bPd6Pn234kaQ7rBVz22bZSpkj1tenWD28BujLwYoiSY9nHDBhU1a9h5s1tKK9aWhLvpcHpyuw3miqjDRSjGQX0Ghrw2gBpbvr2kiXtd+cMNZDYNwAo4baow7afw4qCyhHtgyi3IBYPoNuDw06ocigcioIlFQ4OgJXHsqTEWSUBxiOczgC4Rz27dx37RnwMIpqg9glipOjGLUWCfnLKsNkR+tjhFfKURKafEk2liag2bTV6m8G/z8Gvd2RE0ESgRWiCMZ/JGikSTtCQBUifLTIaHVneAvA+bIKlQ8Usto24Vmf1E5WPbcpN5CITSAo43L5bD+RkwozypaI2qHyQQ8Ft9VBvco2a//iTPJQHfvSIiFpH5aqT7eruFkX7IeQtYWDEDQyQXWynPV4gsgX8ifnJHrM8N+FLpBlEsb+HXSogVypeoVRPe72jqMeiBqf+y6/924bOL5rhw9ZBSif1U9Qu5aTVRG0HlLtdAJvOVE+UkFzHGjfRfBePXv1Dz24XC9IH2TTXXtAlwRyG63K/fZUbJFAElFcG0FVNfLmWwHKwfIJXWf7AtkZAXGM4TinI+Brfk47eL47F3MVtXZDpZWu90UXNWGNSgRQoxXJJqz7/4iyvgr///88qesYpc9ITYOsL6goohiJoKImspSASNarZRP+mBU4SWXhJm4r4c4itDURCbU3DaXLpFYbFQPGeo0PasfkZdQ5wICILpnoArVyBNqR9LuWyy9EmjqgCdKxhdsz6VTa311U37VII6aAoD0PNiqWdL5HpVWr08crdzuIAd5znoqogvbTTdKjOkJcrGFOtY2QYFJLyGg07uJjUWRQvjteFTIg0mu0N91QPqpNMiBrTBEVpmszUFIhx7q2OlYnE9kDjci3huzWGoy1WzAWiV+wfTdqPd2cr7zq1dicv3DHnqKvNZ5FjhA8VsULiivlbIMEv03M4AJi4oeNrmOSbyZ+uwy/rXCGSZhhVjXIQmKWRdJYlZc/iUl3RQKkNTQMzHQPIABOyxIaNtqQEcZ6ymA4zuUI6LKdy+4NvrEOYaLJtry8DeN6Af59sqMSNKlNnMHU4ZknG805FWVAWwlZk5UiABLinowYQ9GPtXfq3wAnreBmsxlWVlawpH3WqBmcRD6j0YIme8LS9m0YLS8iiXCzEN2PYGmQBEk0etV3P71O3CSKSVhrplhZWy3+uh/K3zheQLWgNhMQUwWKtZkDCGwNIvdGWwAJARA5r4uIolpq/Y9knNYDxvvjiRkeB/WoIynrgApRYLfSBMSTWTUTZFog71DLIk1aLI6XpAvdylm225aX9Cl7PdEoYvVql4r3mUr3BfkDxTcxqaWMytdS+dHiAmbSBMUsVxWSiDwLjEQ0RMMUiUc1kvTgqXR9vTLX7QJf36B4a1AoZarvv8LneyfGCIVNbURMplMFDsNxDkfgLF6gc3jUzyHX1uOoXhOpPrKyjnWtOMPidqxolbkuYmshegiipEqTTpM6HwNEXV4hiKjKilh2Uy0DK7XnFdloaQlxeRmrWmknBNT1CLPJtJCRyfXw+hq0LhNNZM31DBMxtKqUouQLYYjoYqzhPhgCkgiDpAgYEDtjQSvubSJ9yNfV9Rn2HV4X8YywuLALIdWA9H613wqgYyHqC7t2hGkb0WpQ2+MitoeFstK3f7MANFp5QrEhiSB/azGUUSUoRwBZMqOS9LZL1FaE/zaJV7v++xhevUf5efjQAbip6eoh1WmwKKIMbAE/GbeC2GLaTDBtFXPFTq6i0n49/Pc/5Kv/d2EHRMKrimkJe9Ndg0r+LWrralEPJz8Uqiqg1QNpYVyj1bUc6/uILNKOIntD1bG2sgqTtGNiIl/TQ2z3rl0ax3CeyxHwPXEu+3fe+/ZEzF+/b3mM2cUvwCN1xJc0cQ9qNb1XK6yD23fgCZHEgbrCEwT2BsLpfSLl/SJjpy1ddnBU43ER0CGRs6X1+1R3f0yYLo2xqklfa3+1ylopVjUoGUQa4jL4S0dPbMMkbfjCJH1wVCFohQb51WpFaZsgRqjlz0h0WGnF1jDiEW0frF9yAR7etYQH9mzHHdtG+Oy4wj27duDenTu3hHt278AXti/g9qUa9+xcwMGL9uDR2TomIjmKrFu9EZg35abGg4IAooxJ/rWCzOAj68OQKKtOrzy9am2QUY1qNLMJLtyxExfEGiMRI/YfwqWhxhXClRrjlqC6X7lrD15SL+Il2h65Sg+XF1Y7AH0H6f/hQKPrenAcsbJ9GWu61it6ozmoFfW+OmJ/CLrWAfsXKjymLaR9iyM8XAH7ti3gywr+3oUaj2qb6Ylxhf26vvulfwANDu/eXvD4QoU79j3+YY93wLkbgXDuujZ4XfrsKwAAEABJREFU5gi88fqP/J0v7tn+tV++YOcr1/7gV7zu8IuufN3hr7rq9Y9efNHVBy6/7Oq9l17yfYeuvPx1+y+/9LUHLr/0NYeuvOw1+y+75DUHL7v41Qcuv+R791560atXr7ryew9cevGr2q/6iu9+/KILv/PgC6946SN7dn/9/gv2/PH9e7Z9fXPRzh9dFVmvTKaotUqfHlrDiBH+WxE5EIy6TQSSCMpHgSQg2ejVfgKtBEXOs9RCSzgELfdqJb13vK4V3RMi7LWrLsMf/em/g+/4hX+Kb/mln8Uf/vmfwbe85Rfwx37hX+GP/rt/s0X8a/zJX/nX+IZf/Tl86y//LL7+n/49zP7Ai7C+vIAGUav4gGhftPKsDT1RknydhIw1ffNmtCLgVmNIkkkxaIUmBKyrfLXKaBYD9q0fwlir09VHH8bS3n249Rd/GXf+7L/BDT/5M7j1p/8Jbvupf7Ql3Kp6n/w7P4nr/t4/wCd+4p/htv/753DrT/wcbv+5X8Td19083X3xJX92duHFX/vE5S94yRMXXSpc9hX7v/Kyr97/4qv+8BNfedUfP/QVL/m6R190+Tc88hWXf/MXX3jxt+7/iitf+uBle16296uufPlDV1zw8se+4rKXPXzVpS994IqLvuX+yy/65sN/5A988xcu3PXN916884+v/YmvvvwHbrrpBzAcx43AuaIM54ojgx8njsAPfvCDn/me973j2le8/Xfe9h3vf9fbvvUdb33r937kg7/3sve86/de+ZEP/O+Xvv89b/vOj37w7d/+kQ+842UfuPYdli//8Afe+YoPvf9d3y75re9/z7u+7aMf/P1vfO+73qOy933bB6/96Ktu+PgNL7/uw7e84oYbbnhosvbJkVajsRphHEbYXi1irJViZHd7eBXp1aRXx9rbALS3bYh3kdoZ/HqdRM7iNWjHBQFJq9Us04xKq8N2PMbKtiXgj3wN8h/6Kqx91Uvw6JWX4rHLLsGBF78YB7eKl1yFh7Uq3/eiyzH5Ay8G/vAfwhP6jm2SIsiA5YVl+REEIiRJkXUZg8jYWyptSJCphpLhB03OshOxBw+EBASPvdYqVBa4WCv9nbMp+MADePyjH8P0Ezdj9ePXY22LWL3uejQ3fhLh1ltQ33Ib0nU34MAHPo5HPn4jDu878K9f+u53vuW7P/iez3z3e95z78s//LZ7X/rRd9zzrb//+3e8/NprP/3yaz98yze+5wM3fce73//JV/3++657/Xs/8LFX/v61H33N+z70ke9+53s+/Or3f/jD3/Pu933kVddK9/73f/y173vfdd/5u2+/7hrJN6rud/33tz6E4TjnIxDOeQ8HB896BPaPmB9fP4zV6QRrh1ZQJaBdnYh820KySYSmlNaeGa32Nk1yBnPGWCvtkYhPC07UIsUYtfIWt5Vy5ZtZUu0Kk1a32pTYt9Jg0lQYVTtRcRlaeCNrn3VLaIFx3oa62YbpegTystIVFjBCWs+Y6I0gi2y9jSHuhX2ynx6fV/jeRqjrGtR2UMgBldqzznB5zIrDrEHTzKBvNDFZPYCddcDCZBUXKQ4X6gvSFwgXaD94SxDZ71zdh52HH8POfV/GxesruErbE7s1lEcOPfYAhuO8j4BmzXkfg/M+AG/82CdvDNrvHGmluKjtAW9VVNofHbHSejKirCgDkYQsgAHWBRA1iSAiE5sXAsxtQhJ5tSJvkqAKW+1vBxE5tJqOYRFrq632dDMyVf5UIO/UFeQYmnUCegBU2g8fxQpZjxP7WMhZPnvFDCZQvsolmHzt/6iq4YeK05DxUeV6K4gVsby8XH5LDh2T1VVsi4rLRNtA0zWMm2bLWJjNsKT98h1yfVn+xukhcOUAkh4A09yO1d25eQ5ePWMRCM9YT0NH53QEvAqmdm5XwxSzkchTBBv0LZr3k4O3NJCQRSSNyM7/kIPSBdkkrZ5VAlQBUFnWnkGlFWwQ8TVaHpMtyClg5hN5h2mC97fF/5jpPxOn290aEmKd0eYZKLLGdIpGfR2a7kccRT0wEgCPJSmdlVZOfoiHpQ3SEasi3KRVMhjlv+qwG4efOpStxziZTBDiGAxjyQU0GjtJBI8XanOLgL4YDFxEKy72v8acxBZ5IcJbL6Na3waq3eE8vyOgu/H8DsAweuDGP/Enaq8ooVVcIyTtzZowHRv6YwOmOOsNq/oy65GDKBziNarIEHFZ0KVZeql1UnZWd8TcoG9LRWd+akXcpJnaSKiiCHY0Qq23ANbuO5eVPIpXqTwfcvHF3SQkEWwW/KCxhpRXgvfaG634s744BOy7SwX5DSFDU0ZSmqflZLKzUf4AjZtmLr2GHEdPSwdDI8/pCOiWeE77Pzj/NETgntGoyloNG09Dc89oE97GcIcpJTFco/3iZoOYoRXu6d/eJN2MVtUiyKcUi9LMlj7IzgeyyGpLjQyVnlcROP07+Hk17GEw8xF4wXgcn4vk7DGQhcwKsYqZdaYurUKyK1PylGc/fpKF2MnTr4un8SC7fun9k6ex3aGp52YEBoJ+bl63p9Xrpqq0P/C0NvmMNeYvIN1Z9BZHXcO/yihpKfsyJU94zq/ATdLOu77lCSudxQL7UJr3G0FJDB/ncwQGgj6fr/7G2A83DXVs5M6qeNob74m0EJtIzVsdJa2eTmdMtrG9oSpw3m1aOv9MovfBstWGzTPZ99DXuRmBgaDPzevyjHpVN8viIxZyekY7fho6k+OllbJabro96JKW1kQrcdLTZOg2DBs6P0/y1j2TcP8G2tQ8k/0OfZ2bERgI+ty8Ls+aVz1RPWsOnGHHJlNX8baE9jd01ihpKXuiVvKEZyFDlfbjdt5tGlI/e2f5beKz1/3Q87kRgYGgz43rULx4tj5m1Yp4qftJmRKbX7L1/pi8DOdNXIbTp7NCtd3J4Hbdp/+Bif8cpv/8abOxErbOZe7v8OHD5QtAp03Aruf0ydp2We+jbd2W21xYWChE7rxterjNY9Oub73Rl81L692O4bTtXe685akwb+dx2U+3kVOanaruUP78j8BA0M//a3zKEW6r/Cu77udlNjbRWBomDMNEYr1JxCTnL+MM2zwVuE3D7bvdpaUlmEDdj4naZe7HOkv3Zb3tbWMys66slmez8i/+SlpKl9t3JcsvMyxd5vqWzrudHrY9Nt3rLG3fw34Zzlv26Ov3esuTobe3tF3fTpuztqGtGXA+R2Ag6PP56m+MfXaoziaGjeymMGlYb1jpvInKMMGZ6Kx/KnBbPdyP4fbcl/tw2uiJ2OUum4fLTcaoKp3V5haH27Wd67i+pXX+HwhYup51PXqbeb3TJ4Pbd7nbsDxTuM++jtvq2xkW0H1Uzm/53CHo8/s6ndXR19tnNFGYHHq4QxPGvN46w+Rmcjacfyo4tg+3bQJ1v/2K2UTtvixNxC4zbGu4f/utZbLOsPllp9t2WY9io4z1hvM93J7TKi71nTas7+GyHq5vHC/vesZ8eW93rLRdr5u3b9rEXj/I8zcC4fwd+jDyPgLrTVObHHr0est5ncnEZGWS9HaEpW2eCtyG4Tb6tt2Pdc5bbzhv2essezvr21Y7AsfZ4rCNx9ATuesZ1rue9S6fh3WGdb2t7Q3XMVxm9GnbG873dfpy604G27ntXto2t0/jvyd3gwOekxEYCPo5edmeXqfbdXyfycUE0cOEYfQ99foT5Xv9mUq369Xx+vo6TLI9uTnt/y2T/XKbfiDYn2NtXd/lLtPyWWcoK2DrXGa9pWGd84bThtM9nO9hndPu33UN56037KdhvfOGy523PF307du+r7vRVm3dgOdFBLY8iLDlmkPF508Ech6ZFIyeJDw45w2ne5hQDBOoybLXb1W6LcNtWbp/t+20dX279sO6vtzSsM42JkttQOusCklb15c5PQ/Xc95tzsNtHJt3G4brzMP1e7iet2MsbdPbu63e5kSyt+ml7dyGMMxNB+M8x3ATnOc3gIdfETkGfcHGCv77zYkJma3SScUJATLQjmgORGQo8B9UZoTsskoBZiImKYDNv8zWqg4zUGk71RI5yA5CQEhOh9LWaDSCYZKDDhOctzTG4zG8cpaq/MROpFXytnN5Vrsx1ioOZfWNyRTNeoM8g/oAQrTM8l8yCCRaZCR5zKTPnNHkRroWSm5A5SqzDwZ0uMyF7j/L2mBSfAy1FhXAqg6whGKX1KZliKqsvNPHhYpTUGyroDhUCIqPmpMpgeSMDIbzvI6AbtvzevzD4BWB2WwVmGWRRARjwARTTMIa2jBFTg0ig3kDjQgpNw0oAgq1aCo0oqsWJEXCIpg2wn/jeCrrNZX5L2mGFljMNUIjpk4Bo6pCnraoZTvKI4iIkJoZavWbW5Gr4DTUVyRgHXNSn0l+ALYd12pDdiTFyQGxXkL5Vdq4xrZqG+o0Etmpssbm9gOo+qor4m3VltUh61Nj0zMJMzSYtVltUM+QIBBJxJplRPlLRvWbMKpr2bSg7KP6r7XvLUtMp+tYW1vBbDbpylkaL2kguaPjwn1MHEHFt66WwGmQ72M96Cr54/8FDc6LYxjkiSMQTlw0lJwvEdi2c08zmcyQxQniXlBkulCPxC0tRK1IswR6pRoigmDC9vZDCAEUieWcRYjQSjWKvABStCWCm7VTEWNCEGHVIlWIrRstbzkioBWny7XwRqZIcQtIqqPG4b/fLKeBZoqJyH7aJmStSqGD6lunUih+sfgcYIfVK4wofw1o0VpWsdAh/yH4FyVJxD7San59MkFcGIFqYzxalKwQQ40YIxwLS6/4nVYLcD2SXb8nkKNRRJumOHRwH+oYoMU4JpM17N69M2I4zvsIhPM+AkMA8Oj+A7Pl7btBaGW6mrAjLqOaAFynVnO1Vo+AGA9Z5ZbQihjTqDLZJ5bthVYrQYYsHbAgolvS0jSqRhhHrOZ1rcrX1MgKVtMBTOIhrI1W0Cyn8kfqE4LWmWeOrBbDQkbDNdWfAmwwrTPSQgRGWoVqtUxG+MiEvCFIAiLCLCBQK3/5q9V8lWDOBlVHo0KlClG6BT+Y1EATxf9iz4Prq1gD8ejhw2j1ENOzC82MaJtQkNRWThWgGFnOw2VGr6MebGhW9Daxit07aozqFtPmEBaXKqyu7levw3m+RyCc7wEYxg9s27V95bC2OQ6vrmD7zp2YTRp4y2PH4jJym1CHiNRC5BUKiYUmYUFkbVmHhBAbMGaIwqAKMNktiKhGIq2ciPVpg1rbAxiPsH3RNTPaySrCbIaFlLeO3GJhuoZtaYbtol/3P2snmMmntdkUU5WLZ5EZoG7QIoNkgV4WIK+hxbG2IuSPyNJvAgZ0BNtJ+p+fj7RHvroqf7VyvnDPC4BE7LrwYqxOM/z/bqwUHyOqnwCiRx0rxQwFkAPMT05T2z07l5ewf+9ezLTlErRCbyr5U4UpfAw4ryMQzuvRD4MvETi8vv8RjBPi9goH1vej0gpuLa/j4NohJNFYPQqgXsNr7X8YVTPDNpHVSLtuBbIAABAASURBVLLKE31xN0Wq1jHjRDw0Q2harcAzRrNKhL6AwEWMmjHw6CqWww5ctf1yvGTpElwWt+OyaoRLtc+7FVymVfBlItbLqhoX1pWIM2NRq9xYi5DFhuOFBfmvtGz8/1EsZK00RZZO6/mBidow2hgLgWfVUENK+53AuQyS2DFe0ngyDnzxEezUG8bagRlqjpA01qC4VExwC9TDIjcT9LDOcPk8rPMzbSEuYLrSYGl5N3K1gEk9xl4/XOp6FcNx3kcgnPcRGAKAH/r4DR9+aOfSNzxy6QWveeCS3e/89PbqBx667AX/8+5Ldl7z+Z316z+7VF9zx/b61+9cHr/mjqX6u27v8L2f27bwK7ctV995+/bFl35+of6mzy9U33j38uL/e+fy4p/43HL1jbfvXvzFW+rJqydXXnzt9p27bz/84U/i7t/4Xdzxn34DX3jLb+OB334r7v6t/4V7f+u3t4T7VPfB33k7Hv4f/wsP/ubvYu1/vQ3Nw4+Ca+uA9tTb6UwPllyucBbJlkTSp0jaK+UCBGStfElCxvCRlSwrbLaotaI9sG8vslb7I62IF3fuQNq1A19cDq9XTF7+qXH6ztsW+NrPjsN/u325uvqOpfGv3bFt4dWfXx794u3b6u+4JTbfdnNs/+2taF92E6ffejObf3NzyN9xW2xedXNMv3Z9mr3xU9sWfuB6TH/n1oi/cM+OpT//pW1L//0v3XDTz9uXAed3BAaCPr+v/+bor77uuk9+y8c/8o7vuOmWV3/PbZ/7ny+96dYf+I7bPvO7337HHW99xe23/e6rbv/Mm195+63veMWdn3nvK+69/b3f9IVPv+vb7vrCj7zqc/e/75W33fPRV95+1/Xffeedn/i2uz7zd19216du/vY7P/eJ77j9U3/tVffc/s5v/eh7Xold1R+5/td+FQ/+0q9i7ZffgkO/9J/x5X//H/DIL/0yHv5PW8Qv/Zrq/ibu+YW34L5f/i3c+ZbfxZ59a9gjdr0wLmJZ2zDebtHGjFa3sRuryVnbNlrqi5ojKDaOKWilj7JS1odWsgGp2oD2QHbu2IGFqsYhfXl3eNsI1609/j3fftctb/3u22/48Gvv+cL7Xn3nnW9/5Rc+/6e/8/bbf+87v/C5P/9dX7j9nd995x1/7bvvuOP9r73vvg++7r57/+ZrHrj3I6+///6Pve7++/7W67549/tffd99v/+6++7581d/6Yu/8z133PE/r77//je+5q47/vN33XD9r/25m2/+UxiOIQKKQBCGc4jAWY/A1/3SL82WtQq9UvvRlx08hMv3H8SLV9dxxaE1XH54fUu4bGUNl6wKa1Nc0QD1vkO4QHvcrbZempzQthnJK+NAZIGi6ZChLzKVEikTOlqUPWing+r4FydRGVYR1BeE/mnhTH637QzeR1/T9sMbP//5d6vmcA4ROOsRCGe9h6GDIQIbEZhKTrQyXdMO70hEmhqxJSok1FtEwJQz5IWMtbQKLBD7OcXaYsRKTFgfUXvMkI2IWn1mwfu+hlfWtciZciFrFQ19C6oqqGYJWdsjzWSKmVba2dsfIutk0tbe9aK+GHz7H//6l2I4hgg8AxEYCPoZCPKz1MU5122lLwP1VaKINGq7YAXUl4+ZSX4+BbABRNJB5Kt1MvyPPzpg8/BNbiI2NpUlQQR92UkVaEEN7Ywga4ltXWSFSsQdKRsQJFFHYlGr6kvY3oThGCLwDETA9+4z0M3QxRABoG3WwVHGqla5swVgBRMR6gzBBLtFVFr5jv2zP2FJq9/FWYCxNNNzQNw91jZHpZVvLcSc0TJjqiV0EuGakGOMyFrVTyKwVqusJhqTdg6IrYhZK2Y0LbzFMW2nyEo/MbuAw/UcIvBMRGAg6GciykMfJQKL2ssdiSRD1n6uSDFn7zE8hdUzNupqFS7ehXgX3rowvF1hBG1h9NJOtLrjk+jVcJ1aBF1BWyBqwzotmssXhkHkHOTeuF7AaDRCGNcIdYUqROxcPBDd1oAhAmc7Arpdz3YX52b7g1fPbAQ+8IpXVBc3FbYdmmJ3Q9SrE2yLIj6tVBOCqHZraELATLBsqDa0Mk4FQCpEnCQ3iBxJK2it5EM3dhN3nDWotRdea6Vda3U8mmV4b9okT/k21ReOq02D1TTBJE2RJuto9+ev61oYPocInN0IbNyqZ7eTofUhArgP1YK2C2qxZhIhBkRYQiQIEXTYokyFlAMyO0LO6I4+771lw/ketigraxnntpUfrSonUHnDk4JqN4QKgRWiVtmsIiphoa699XGj2xgwROBsR8D34tnuY2h/iECJwET7xe1CjVXdde14AespSU9tKVDkuDUZRfgGxb5BYCF6bK6UpxFaYWvlHAR2elVRn1CfAbOcMJNNWwc0FUt66hW5CLoV/AeP0rQB12f6LrKFf7432R7/KIZjiMAzEAFNleP0MqiGCDzNEVirvpShFWijfefR4ghrzRTV4rj0Qq1cnThT6TpBdXv09VFIWmwsA6oc0G0undOULojIuxU7kII0kRA3I3hrBAGtSNsQpaMScQf5nWMl24BcR6Sdo5vVzHAOETjrEdCde9b7GDoYIoDFK65ow6hGaKdoVw5jUcSHVitTEahXvScCRJjHQ9bq1vqgrRIkimkpywiS8D/hDi1Rp4CqCVgKC1geLWMcxsoDYSZzALkSkBC0sh9PE0YTgKqT1GbUgyTqy8wZppiEFpMYMNWXhSuQz9Pp12I4hgg8AxEYCPoZCPLQBfCKD36w3bt6CF5FLy4uFhL1/3NQi1n4t9Bniu63ztoiCVmk3GG+DUBlCrxXzUlf/vnvV7fab3ZeOxilz0YkrMqykltypBbpkxEUQXs1HWQcoUNStA5qFe7tlG2TyW3SDue5GYHnlVfheTWaYTDnbAQIcfKO7VgPESva020bYuf2XUgiv1Yr1GPRRO33CsfqZ9IZR+mrhN7eslXecNrwvzZcS+uwtD7VGZYNW7Tam06BmFXUFgaRTdBahQfTdAbE/xgnYnkWCrZpwzq2y8Me9Dl7pz2/HAvPr+EMozmXI7CWRYwiwMwaFEG36622JbTS5RkAsp1Dyg3U6lGA2qOZdUP25fN6pw1xr0ga5UtF7YaIkakQHjMtyj9yAeo2IwohLQwraEVpOM9+BMLZ72LoYYhAFwHtNCCESnvBNRZRI0wSQgqICU9C1QLz6G1q2c6j11faoggmT8G6+bTL1Jt7LP2wUb+yC1olU+Sb9eBIong1gVbSacuZVvdGIfGiB5ye1St/rBvRc+9z8Pi5FYHw3HJ38Pa5HIHx0iL8szX/X0pijFgYjVEWutrb5XEABFjfS6ePC63KRfMKzdH2WYxre9d3OWXnvPVm2qi9ZnFwIW1V1h5MhgmbWU8BKwQ1UUhbSRTiDsChtfpW5wcMETjbEdDtdra7GNofItBFYNbO4A2JFIGJUoen6yK9iCyi3DpqJP///7Q+zhgBNMZgGCttjEp5ylWRWXZpwx45ohYDVyLkKiX41xyWMQN+cGi9DIPMygtUTtsmo23p61UwnEMEznoEBoI+6yEeOugj4C/lUmiAWkw3DtBeB8SPKlZaq2XMIbPT9ZJaYbv8WJlaaOVLfXT2FNkHbaPEUCPGuui9Ys6Jxc7lXklD7VkPHaTK1AScFjlTjw0IJEFSWhwlIyfDvyTEcDwTEfBd/Uz0M/QxRADTPNEXci2SNpEP53XM/GuLkBSZDl6p9gjIMLzdUGDCzAkmz/l8FQnmFm0zRdYKPaemyGamvgTbR2b0ev90bnFcYzyq4D/WpC1weJ95KpuZZkOSP+JyWN9qcwNMqKRXo/BftAvK7D+89s1yejiHCJz1CPjWO+udDB0MEXAEqqrS4lm3XNOgnawjiVDbVmkT7BySyLhHVtpw/nhyYWGEsQh3NFLbdUQ0YYtsTfReBScTttoOwR6oFeUbkbnJNovwc84i6qOhvZANHdVExmzWgjEgaM98XfUvvPSi69zagCECZzsC5bY9250M7T8fIvDUxxCnLepJi52o4f9n4HZUGItUQx3ASmQY1YeQtQGctHI1tPmLk2F9tor12Rqm7bowQZOnmKVpka32uXNoAW0qa8ejyBazYmfpn+JF7bHU2hQfaT96JBklA6JW71Gf2h9vMipVnmVgTQ+TVT0w7nnwy8MetC7VcJ79CISz38XQwxCBLgLU3nASITYTrWT9z61FfklfzrVaySZkWB6LTOJkaFS/rIJlRxLQUtm/EAlarVeC65JdG0FlfT7JJefJCBqiYzKA/o22AKWz9KN6ETlGzGKFmVbqs8VFaNvjQxiOIQLPQATCM9DH0MUQgRKBdmkZ09ECDoNYTdR+9AhtqrSdoN1mfWlHVgihLqiqMYwku5Mhilgr1bM0KHtvHWeRv+G8ZZqJklvxt/uRjfWppXYzgswDGukabTx3kpggYCo/pyLpg2tTrCq9Nh7hUCTe9LnPTcuAho8hAmc5AuEst3++ND+M8zQi8GgFfFk4uGs7Vi/chX2LCziwNMYBy/EY+0SAe7XqfaKu8LhWu0YpU/khwWnLeexbqGEc0F605X6tcufloaUF7F8cweXuy/mDywuwPLA0wr6lWuW18iMcWqxxULr9lgsVDqmtw3VEumAnVncs4sts8Gh9GgMdTIYIPE0RGAj6aQrk0MypI/DonsXfeuTiHZ995PJdn3/okh0337dnfP3DF+38xMMX7fjkQxftuP7BC7d/9MELtn3gSxcuv/dLF2x71wN7lt5+13L1e3ctx9+5eyn+1p3L8b/ftS3+t7uW4m/2uHMp/Le7l+N/v3tb9T/v2V7/1j07Rr99747Rb923a/w/vrhr/Juq+xt3LYVfv2spvuWupfCWu7aFX1edX79rMfz6PdurX797Of/GXUv5N+82tuE37lnIv37fUnrLfQt4y/2L+b/endd+/dHl6rcf3RbfvW/3wrWzKy/8T6ce6WAxRODpicBA0E9PHIdWTiMCf/2d73nTD3/wuq95zbs/+Ie+59pr/8T3Xf/xb3r1Rz78ja/58Ee/4bUf/dg3vf5jH3/p66+//tuv/vgnvuvqT3zie6/55A2vfdPNN1/9pptveeMbbrnlTT9w8y1/6o033fKn33jLLT/U4/tv+dSffsPNt/2pa26+9QfecNOtb7rmplu+/5obb3nT1Tfe8oNX33DLD0n/Z77/1k+/+Y23ferPyu7PXn3jrW9+4823vfmaWwWl33Dzp//MG2/59A+9wbj5tj9z9advfPPVn7r5z9r+mls/88PXfOGON3/XJz/5/a//+Ce/54c+cuMr//Rb3/+XTmOog8kQgaclAs8IQT8tng6NDBEYIjBE4DyLwEDQ59kFH4Y7RGCIwHMnAgNBP3eu1eDpEIEhAs94BJ7dDgeCfnbjP/Q+RGCIwBCBE0ZgIOgThmYoGCIwRGCIwLMbgYGgn934D70PEXguR2Dw/SxHYCDosxzgofkhAkMEhghsNQIDQW81ckOrsGvZAAACFUlEQVS9IQJDBIYInOUIDAR9lgM8NH/+RmAY+RCBpxqBgaCfagSH+kMEhggMEThLERgI+iwFdmh2iMAQgSECTzUCA0E/1QgO9bcWgaHWEIEhAqeMwEDQpwzRYDBEYIjAEIFnJwIDQT87cR96HSIwRGCIwCkjMBD0KUP0bBgMfQ4RGCIwRAAYCHq4C4YIDBEYInCORmAg6HP0wgxuDREYIjBE4LlI0MNVGyIwRGCIwHkRgYGgz4vLPAxyiMAQgediBAaCfi5etcHnIQJDBJ6bEThDrweCPsOADeZDBIYIDBF4piIwEPQzFemhnyECQwSGCJxhBAaCPsOADeZDBIYInK0IDO0eG4GBoI+NyJAfIjBEYIjAORKBgaDPkQsxuDFEYIjAEIFjIzAQ9LERGfJDBM7NCAxenYcRGAj6PLzow5CHCAwReG5EYCDo58Z1GrwcIjBE4DyMwEDQ5+FFfz4OeRjTEIHnYwQGgn4+XtVhTEMEhgg8LyIwEPTz4jIOgxgiMETg+RiBgaCfj1f12DEN+SECQwSekxEYCPo5edkGp4cIDBE4HyIwEPT5cJWHMQ4RGCLwnIzAQNB4Tl63wekhAkMEzoMIDAR9HlzkYYhDBIYIPDcjMBD0c/O6DV4PERgicB5E4FQEfR6EYBjiEIEhAkMEzs0IDAR9bl6XwashAkMEhghgIOjhJhgiMETg+RmB58Go/v8AAAD//5G8YPIAAAAGSURBVAMAuZJTIfVwGCkAAAAASUVORK5CYII="
    };
    // Split into chunks: a single string literal this size (117KB) exceeds
    // Java's 65535-byte-per-constant limit in the class file's constant pool.
    // Each chunk individually is well under that limit; they're joined back
    // together at runtime in decodeImage() below, so there's no size issue.
    static final String[] BANANA_PNG_BASE64_PARTS = {
        "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"
    };

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

        // Hard reset to the title screen, available from anywhere.
        if (k == KeyEvent.VK_R) {
            resetRound(true);
            state = State.TITLE;
            return;
        }

        if (state == State.TITLE || state == State.GAME_OVER) {
            if (k == KeyEvent.VK_ENTER || k == KeyEvent.VK_SPACE) {
                resetRound(true);
                state = State.READY;
                stateTimer = FPS * 2;
            }
            return;
        }

        if (state == State.PLAYING) {
            if (k == KeyEvent.VK_P) {
                paused = !paused;
                return;
            }
            if (paused) return;
            Direction requested = switch (k) {
                case KeyEvent.VK_UP,    KeyEvent.VK_W -> Direction.UP;
                case KeyEvent.VK_DOWN,  KeyEvent.VK_S -> Direction.DOWN;
                case KeyEvent.VK_LEFT,  KeyEvent.VK_A -> Direction.LEFT;
                case KeyEvent.VK_RIGHT, KeyEvent.VK_D -> Direction.RIGHT;
                default -> null;
            };
            if (requested != null) {
                // Banana effect: invert every input while reverseControlsTimer
                // is running (UP<->DOWN, LEFT<->RIGHT).
                pNext = (reverseControlsTimer > 0) ? requested.opposite() : requested;
            }
        }
    }
}
