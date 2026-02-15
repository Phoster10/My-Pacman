import java.awt.*;
import java.awt.event.*;
import java.util.HashSet;
import java.util.Random;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
enum Direction {
    UP, DOWN, LEFT, RIGHT, NONE
}

public class PacMan extends JPanel implements ActionListener, KeyListener {
static final int ROWS = 21;
static final int COLS = 19;
static final int FPS = 20;
static final int DELAY = 1000 / FPS;
static final int TILE_SIZE = 32; 
static final int PACMAN_SPEED = TILE_SIZE / 4;
static final int GHOST_SPEED = TILE_SIZE / 4;
boolean gameStarted = false;
boolean showReady = false;
int readyTimer = 0;
static final int READY_DURATION = 60; // ~3 seconds at 20 FPS
boolean mouthOpen = true;
int mouthTimer = 0;
static final int MOUTH_SPEED = 1;
boolean scaredMode = false;
int scaredTimer = 0;
static final int SCARED_DURATION = FPS * 8; 
int highScore = 0;
int titleAlpha = 255;
int alphaDirection = -5;
int randomModeTimer = 0;
static final int START_RANDOM_DURATION = FPS * 7; 
static final int POST_SCARED_RANDOM_DURATION = FPS * 4; 

    class Block {

        int x;
        int y;
        int width;
        int height;
        Image image;
        int startX;
        int startY;
        Direction direction = Direction.NONE;
        int velocityX = 0;
        int velocityY = 0;
        boolean isEnergizer = false;
        String ghostType = ""; // "red", "pink", "blue", "orange"


        Block(Image image, int x, int y, int width, int height) {
            this.image = image;
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
            this.startX = x;
            this.startY = y;
        }

        void updateDirection(Direction direction) {
        this.direction = direction;
        updateVelocity();
        }

        void updateVelocity() {
    switch (direction) {
        case UP -> {
            velocityX = 0;
            velocityY = -PACMAN_SPEED;
        }
        case DOWN -> {
            velocityX = 0;
            velocityY = PACMAN_SPEED;
        }
        case LEFT -> {
            velocityX = -PACMAN_SPEED;
            velocityY = 0;
        }
        case RIGHT -> {
            velocityX = PACMAN_SPEED;
            velocityY = 0;
        }
        default -> {
            velocityX = 0;
            velocityY = 0;
        }
    }

}

        void reset() {
            this.x = this.startX;
            this.y = this.startY;
        }
    }

    private int boardWidth = COLS * TILE_SIZE;
    private int boardHeight = ROWS * TILE_SIZE;

    private Image wallImage;
    private BufferedImage blueGhostImage;
    private BufferedImage orangeGhostImage;
    private BufferedImage pinkGhostImage;
    private BufferedImage redGhostImage;

    

    //X = wall, O = skip, P = pac man, ' ' = food
    //Ghosts: b = blue, o = orange, p = pink, r = red
 private String[] tileMap = {
        "XXXXXXXXXXXXXXXXXXX",
        "X        X        X",
        "X XX XXX X XXX XX X",
        "X                 X",
        "X XX X XXXXX X XX X",
        "X    X       X    X",
        "X XX XXXX XXXX XX X",
        "X    X       X    X",
        "XXXX X XXrXX X XXXX",
        "O       bpo       O",
        "XXXX X XXXXX X XXXX",
        "X    X       X    X",
        "X XX X XXXXX X XX X",
        "X        X        X",
        "X XX XXX X XXX XX X",
        "X  X     P     X  X",
        "XX X X XXXXX X X XX",
        "X    X   X   X    X",
        "X XXXXXX X XXXXXX X",
        "X                 X",
        "XXXXXXXXXXXXXXXXXXX" 
    };

    HashSet<Block> walls;
    HashSet<Block> foods;
    HashSet<Block> ghosts;
    Block pacman;

    Timer gameLoop;
    Direction[] directions = {
    Direction.UP,
    Direction.DOWN,
    Direction.LEFT,
    Direction.RIGHT
    };
    

    Random random = new Random();
    int score = 0;
    int lives = 3;
    boolean gameOver = false;
    Direction nextDirection = Direction.NONE;

    PacMan() {
        setPreferredSize(new Dimension(boardWidth, boardHeight));
        setBackground(Color.BLACK);
        addKeyListener(this);
        setFocusable(true);

        //load images
        wallImage = new ImageIcon(getClass().getResource("./wall.png")).getImage();
        try {
    blueGhostImage = ImageIO.read(getClass().getResource("./blueGhost.png"));
    orangeGhostImage = ImageIO.read(getClass().getResource("./orangeGhost.png"));
    pinkGhostImage = ImageIO.read(getClass().getResource("./pinkGhost.png"));
    redGhostImage = ImageIO.read(getClass().getResource("./redGhost.png"));
} catch (Exception e) {
    e.printStackTrace();
}

        loadMap();
        loadHighScore();

        for (Block ghost : ghosts) {
            Direction newDirection = directions[random.nextInt(directions.length)];
            ghost.updateDirection(newDirection);

        }
        //how long it takes to start timer, milliseconds gone between frames
        gameLoop = new Timer(50, this); //20fps (1000/50)
        gameLoop.start();

    }

    public void loadMap() {
        walls = new HashSet<Block>();
        foods = new HashSet<Block>();
        ghosts = new HashSet<Block>();

        for (int r = 0; r < ROWS; r++) {
             for (int c = 0; c < COLS; c++) {
                String row = tileMap[r];
                char tileMapChar = row.charAt(c);

                int x = c*TILE_SIZE;
                int y = r*TILE_SIZE;

                if (tileMapChar == 'X') { //block wall
                    Block wall = new Block(wallImage, x, y, TILE_SIZE, TILE_SIZE);
                    walls.add(wall);
                }
               else if (tileMapChar == 'b') {
                    Block ghost = new Block(blueGhostImage, x, y, TILE_SIZE, TILE_SIZE);
                    ghost.ghostType = "blue";
                    ghosts.add(ghost);
                }

                else if (tileMapChar == 'o') {
                    Block ghost = new Block(orangeGhostImage, x, y, TILE_SIZE, TILE_SIZE);
                    ghost.ghostType = "orange";
                    ghosts.add(ghost);
                }

                else if (tileMapChar == 'p') {
                    Block ghost = new Block(pinkGhostImage, x, y, TILE_SIZE, TILE_SIZE);
                    ghost.ghostType = "pink";
                    ghosts.add(ghost);
                }

                else if (tileMapChar == 'r') {
                    Block ghost = new Block(redGhostImage, x, y, TILE_SIZE, TILE_SIZE);
                    ghost.ghostType = "red";
                    ghosts.add(ghost);
                }

                else if (tileMapChar == 'P') { //pacman
                  pacman = new Block(null, x, y, TILE_SIZE, TILE_SIZE);

                }
                else if (tileMapChar == ' ') { // normal food
    Block food = new Block(null, x + 14, y + 14, 4, 4);
    foods.add(food);
                }

            }
        }
    placeRandomEnergizers(4);
    }

    @Override
public void paintComponent(Graphics g) {

    // TITLE SCREEN
    if (!gameStarted && !showReady) {
        Graphics2D g2 = (Graphics2D) g;

        g2.setColor(Color.BLACK);
        g2.fillRect(0, 0, boardWidth, boardHeight);

        g2.setFont(new Font("Arial", Font.BOLD, 48));
        g2.setColor(new Color(255, 255, 0, titleAlpha));
        g2.drawString("PAC-MAN", boardWidth / 2 - 120, boardHeight / 2 - 40);

        g2.setFont(new Font("Arial", Font.PLAIN, 18));
        g2.setColor(Color.WHITE);
        g2.drawString("Press ENTER to Start",
                boardWidth / 2 - 90, boardHeight / 2 + 10);
        return;
    }

    // READY / GO
    if (showReady) {
        Graphics2D g2 = (Graphics2D) g;

        g2.setColor(Color.BLACK);
        g2.fillRect(0, 0, boardWidth, boardHeight);

        g2.setFont(new Font("Arial", Font.BOLD, 36));

        if (readyTimer > READY_DURATION / 2) {
            g2.setColor(Color.YELLOW);
            g2.drawString("READY!", boardWidth / 2 - 70, boardHeight / 2);
        } else {
            g2.setColor(Color.GREEN);
            g2.drawString("GO!", boardWidth / 2 - 30, boardHeight / 2);
        }
        return;
    }

    // GAME
    super.paintComponent(g);
    draw(g);
}

    public void draw(Graphics g) {
    Graphics2D g2 = (Graphics2D) g;

    // === WALLS ===
    for (Block wall : walls) {
        g2.drawImage(wall.image, wall.x, wall.y, wall.width, wall.height, null);
    }

    // === FOOD ===
    for (Block food : foods) {
        g2.setColor(Color.WHITE);
        if (food.isEnergizer) {
            g2.fillOval(food.x, food.y, food.width, food.height);
        } else {
            g2.fillRect(food.x, food.y, food.width, food.height);
        }
    }

    // === GHOSTS ===
    for (Block ghost : ghosts) {
        if (scaredMode) {
            drawScaredGhost(g2, ghost);
        } else {
            g2.drawImage(
                ghost.image,
                ghost.x,
                ghost.y,
                ghost.width,
                ghost.height,
                null
            );
        }
    }

    // === PAC-MAN ===
    drawPacMan(g2);

    // === UI ===
    g2.setFont(new Font("Arial", Font.PLAIN, 18));
    if (gameOver) {
        g2.drawString("...", TILE_SIZE / 2, TILE_SIZE / 2);
    } else {
        g2.drawString(
    "x" + lives + "  Score: " + score + "  High: " + highScore,
    TILE_SIZE / 2,
    TILE_SIZE / 2
);

    }
}

    private void drawPacMan(Graphics2D g2) {
    g2.setColor(Color.YELLOW);

    int startAngle = 0;
    int arcAngle = mouthOpen ? 300 : 360; // open vs closed

    switch (pacman.direction) {
    case RIGHT -> startAngle = mouthOpen ? 30 : 0;
    case LEFT  -> startAngle = mouthOpen ? 210 : 180;
    case UP    -> startAngle = mouthOpen ? 120 : 90;
    case DOWN  -> startAngle = mouthOpen ? 300 : 270;
    case NONE  -> startAngle = 0;
}


    g2.fillArc(
            pacman.x,
            pacman.y,
            pacman.width,
            pacman.height,
            startAngle,
            arcAngle
    );
}

private void placeRandomEnergizers(int count) {
    Object[] foodArray = foods.toArray();

    for (int i = 0; i < count && foodArray.length > 0; i++) {
        Block food = (Block) foodArray[random.nextInt(foodArray.length)];
        food.isEnergizer = true;

        // make it bigger and centered
        food.x -= 6;
        food.y -= 6;
        food.width = TILE_SIZE / 2;
        food.height = TILE_SIZE / 2;
    }
}

    private void loadHighScore() {
    try {
        File file = new File("highscore.dat");
        if (file.exists()) {
            BufferedReader br = new BufferedReader(new FileReader(file));
            highScore = Integer.parseInt(br.readLine());
            br.close();
        }
    } catch (Exception e) {
        highScore = 0;
    }
}
    
private void saveHighScore() {
    try {
        BufferedWriter bw = new BufferedWriter(new FileWriter("highscore.dat"));
        bw.write(String.valueOf(highScore));
        bw.close();
    } catch (Exception e) {
        e.printStackTrace();
    }
}


    public boolean isCentered(Block b) {
    return b.x % TILE_SIZE == 0 && b.y % TILE_SIZE == 0;
        }

     public void move() {
        if (!gameStarted) return;
        if (nextDirection != Direction.NONE && isCentered(pacman)) {
            Direction oldDirection = pacman.direction;

    pacman.updateDirection(nextDirection);


    pacman.x += pacman.velocityX;
    pacman.y += pacman.velocityY;

    boolean blocked = false;
    for (Block wall : walls) {
        if (collision(pacman, wall)) {
            blocked = true;
            break;
        }
    }

    // undo test move
    pacman.x -= pacman.velocityX;
    pacman.y -= pacman.velocityY;

    if (blocked) {
        pacman.updateDirection(oldDirection); // revert
    } else {
        nextDirection = Direction.NONE;
    }
}
// === MOUTH ANIMATION TIMER ===
if (pacman.velocityX != 0 || pacman.velocityY != 0) {
    mouthTimer++;
    if (mouthTimer >= MOUTH_SPEED) {
        mouthOpen = !mouthOpen;
        mouthTimer = 0;
    }
} else {
    mouthOpen = true; // reset when stopped
}

        pacman.x += pacman.velocityX;
        pacman.y += pacman.velocityY;

        // === PAC-MAN PORTALS ===
if (pacman.x + pacman.width < 0) {
    // Went off the left side → teleport to right
    pacman.x = boardWidth - pacman.width;
} 
else if (pacman.x > boardWidth) {
    // Went off the right side → teleport to left
    pacman.x = 0;
}
        //check wall collisions
        for (Block wall : walls) {
            if (collision(pacman, wall)) {
                pacman.x -= pacman.velocityX;
                pacman.y -= pacman.velocityY;
                break;
            }
        }

        //check ghost collisions
        for (Block ghost : ghosts) {
            updateGhostAI(ghost);

        if (collision(ghost, pacman)) {
        
        if (scaredMode) {
        // eat ghost
        ghost.reset();
        score += 1000;
        } else {
        lives--;
        if (lives == 0) {
            gameOver = true;
            return;
        }
        resetPositions();
    }
        if (lives == 0) {
    gameOver = true;

    if (score > highScore) {
        highScore = score;
        saveHighScore();
    }
    return;
}

    
}

    boolean moved = false;

    for (int i = 0; i < 4 && !moved; i++) {

    // Copy the normal velocity
    int vx = ghost.velocityX;
    int vy = ghost.velocityY;

    // If scared, move at half speed
    if (scaredMode) {
        vx /= 2;
        vy /= 2;
    }

    // Move the ghost
    ghost.x += vx;
    ghost.y += vy;

    // Check collisions
    boolean blocked = false;
    for (Block wall : walls) {
        if (collision(ghost, wall) || ghost.x <= 0 || ghost.x + ghost.width >= boardWidth) {
            blocked = true;
            break;
        }
    }

    // Undo move if blocked
    if (blocked) {
        ghost.x -= vx;
        ghost.y -= vy;
        ghost.updateDirection(getRandomNewDirection(ghost.direction));

    } else {
        moved = true;
    }
}

        }

        Block foodEaten = null;

for (Block food : foods) {
    if (collision(pacman, food)) {
        foodEaten = food;

        if (food.isEnergizer) {
            scaredMode = true;
            scaredTimer = SCARED_DURATION;

            for (Block ghost : ghosts) {
                // reverse direction
                ghost.velocityX *= -1;
                ghost.velocityY *= -1;
            }
        } else {
            score += 10;
        }
        break;
    }
}



if (foodEaten != null) {
    foods.remove(foodEaten);

    // SAVE HIGH SCORE IF BEATEN
    if (score > highScore) {
        highScore = score;
        saveHighScore();
    }
}


        if (foods.isEmpty()) {
            loadMap();
            resetPositions();
        }
}
private Direction getRandomNewDirection(Direction current) {
    Direction newDir;
    do {
        newDir = directions[random.nextInt(directions.length)];
    } while (
        newDir == current ||
        (current == Direction.UP && newDir == Direction.DOWN) ||
        (current == Direction.DOWN && newDir == Direction.UP) ||
        (current == Direction.LEFT && newDir == Direction.RIGHT) ||
        (current == Direction.RIGHT && newDir == Direction.LEFT)
    );
    return newDir;
}
private Direction getBestDirectionToward(Block ghost, int targetX, int targetY) {
    Direction bestDir = ghost.direction;
    double bestDist = Double.MAX_VALUE;

    for (Direction dir : directions) {

        // prevent reverse
        if (
            (ghost.direction == Direction.UP && dir == Direction.DOWN) ||
            (ghost.direction == Direction.DOWN && dir == Direction.UP) ||
            (ghost.direction == Direction.LEFT && dir == Direction.RIGHT) ||
            (ghost.direction == Direction.RIGHT && dir == Direction.LEFT)
        ) continue;

        int dx = 0, dy = 0;
        switch (dir) {
    case UP -> dy = -TILE_SIZE;
    case DOWN -> dy = TILE_SIZE;
    case LEFT -> dx = -TILE_SIZE;
    case RIGHT -> dx = TILE_SIZE;
    case NONE -> { }
}


        Block test = new Block(null, ghost.x + dx, ghost.y + dy, ghost.width, ghost.height);

        boolean blocked = false;
        for (Block wall : walls) {
            if (collision(test, wall)) {
                blocked = true;
                break;
            }
        }
        if (blocked) continue;

        double dist = Math.hypot(targetX - test.x, targetY - test.y);
        if (dist < bestDist) {
            bestDist = dist;
            bestDir = dir;
        }
    }
    return bestDir;
}


private void updateGhostAI(Block ghost) {
   if (scaredMode) {
    // Run away from Pac-Man
    ghost.updateDirection(getBestDirectionAway(ghost, pacman.x, pacman.y));
    return;
}


    if (!isCentered(ghost)) return;

    // === NEW: RANDOM MODE OVERRIDE ===
    // === SCATTER MODE OVERRIDE ===
if (randomModeTimer > 0) {
    int scatterX = 0;
    int scatterY = 0;

    // Assign each ghost a unique corner "home"
    switch (ghost.ghostType) {
        case "red" -> { 
            // Top Right Corner
            scatterX = (COLS - 2) * TILE_SIZE; 
            scatterY = TILE_SIZE; 
        }
        case "pink" -> { 
            // Top Left Corner
            scatterX = TILE_SIZE; 
            scatterY = TILE_SIZE; 
        }
        case "blue" -> { 
            // Bottom Right Corner
            scatterX = (COLS - 2) * TILE_SIZE; 
            scatterY = (ROWS - 2) * TILE_SIZE; 
        }
        case "orange" -> { 
            // Bottom Left Corner
            scatterX = TILE_SIZE; 
            scatterY = (ROWS - 2) * TILE_SIZE; 
        }
    }

    // Move toward the corner using your existing logic
    ghost.updateDirection(getBestDirectionToward(ghost, scatterX, scatterY));
    return; // Exit so they don't switch back to Chase logic
}

    int targetX = pacman.x;
    int targetY = pacman.y;

    switch (ghost.ghostType) {

        // 🔴 RED — direct chase
        case "red" -> ghost.updateDirection(
            getBestDirectionToward(ghost, targetX, targetY)
        );

        // 🟣 PINK — aim 4 tiles ahead
        case "pink" -> {
            int offset = TILE_SIZE * 4;
            switch (pacman.direction) {
    case UP -> targetY -= offset;
    case DOWN -> targetY += offset;
    case LEFT -> targetX -= offset;
    case RIGHT -> targetX += offset;
    case NONE -> { }
}
        
            ghost.updateDirection(
                getBestDirectionToward(ghost, targetX, targetY)
            );
        }

        // 🔵 BLUE — ambush using red
        case "blue" -> {
            Block red = null;
            for (Block g : ghosts) {
                if ("red".equals(g.ghostType)) {
                    red = g;
                    break;
                }
            }
            if (red != null) {
                targetX = pacman.x * 2 - red.x;
                targetY = pacman.y * 2 - red.y;
            }
            ghost.updateDirection(
                getBestDirectionToward(ghost, targetX, targetY)
            );
        }

        // 🟠 ORANGE — random when close
        case "orange" -> {
            double dist = Math.hypot(pacman.x - ghost.x, pacman.y - ghost.y);
            if (dist > TILE_SIZE * 6) {
                ghost.updateDirection(
                    getBestDirectionToward(ghost, targetX, targetY)
                );
            } else {
                ghost.updateDirection(
                    getRandomNewDirection(ghost.direction)
                );
            }
        }
    }
}


    public boolean collision(Block a, Block b) {
        return  a.x < b.x + b.width &&
                a.x + a.width > b.x &&
                a.y < b.y + b.height &&
                a.y + a.height > b.y;
    }

    public void resetPositions() {
        pacman.reset();
        pacman.velocityX = 0;
        pacman.velocityY = 0;
        randomModeTimer = START_RANDOM_DURATION;
        for (Block ghost : ghosts) {
    ghost.reset();
    Direction newDirection = directions[random.nextInt(directions.length)];
    ghost.updateDirection(newDirection);
}

    }

    @Override
    public void actionPerformed(ActionEvent e) {
        if (!gameStarted && !showReady) {
        titleAlpha += alphaDirection;
        if (titleAlpha <= 50 || titleAlpha >= 255) {
            alphaDirection *= -1;
        }
        repaint();
        return;
    }
    // READY / GO COUNTDOWN
if (showReady) {
    readyTimer--;

    if (readyTimer <= 0) {
        showReady = false;
        gameStarted = true;
    }

    repaint();
    return;
}

        move();
        repaint();
        if (gameOver) {
            gameLoop.stop();
        }
        if (scaredMode) {
    scaredTimer--;
    if (scaredTimer <= 0) {
        scaredMode = false;
        
        // <-- NEW: Trigger 4-second random mode immediately after Scared Mode ends
        randomModeTimer = POST_SCARED_RANDOM_DURATION; 

        // 🔁 RESYNC GHOST DIRECTION AFTER SCARED MODE
        for (Block ghost : ghosts) {
            ghost.updateDirection(
                getRandomNewDirection(ghost.direction)
            );
        }
    }
} else if (randomModeTimer > 0) {
    // <-- NEW: Count down the random mode timer ONLY when not in scared mode
    randomModeTimer--;
    }
}

    @Override
    public void keyTyped(KeyEvent e) {}

    
    @Override
public void keyPressed(KeyEvent e) {

    // START GAME
    if (!gameStarted && e.getKeyCode() == KeyEvent.VK_ENTER) {
    showReady = true;
    readyTimer = READY_DURATION;
    randomModeTimer = START_RANDOM_DURATION;
    return;
}
    if (!gameStarted) return;


    // IGNORE MOVEMENT UNTIL GAME STARTS
    if (!gameStarted) return;

    // MOVEMENT
    if (e.getKeyCode() == KeyEvent.VK_UP)
        nextDirection = Direction.UP;
    else if (e.getKeyCode() == KeyEvent.VK_DOWN)
        nextDirection = Direction.DOWN;
    else if (e.getKeyCode() == KeyEvent.VK_LEFT)
        nextDirection = Direction.LEFT;
    else if (e.getKeyCode() == KeyEvent.VK_RIGHT)
        nextDirection = Direction.RIGHT;
}

    @Override
    public void keyReleased(KeyEvent e) {
        if (gameOver) {
            loadMap();
            resetPositions();
            lives = 3;
            score = 0;
            gameOver = false;
            gameLoop.start();
        }
        
    }
private void drawScaredGhost(Graphics2D g2, Block ghost) {
    int x = ghost.x;
    int y = ghost.y;
    int w = ghost.width;
    int h = ghost.height;

    // ================= BODY (OPAQUE BLUE) =================
    g2.setColor(new Color(0, 0, 255)); // classic solid blue
    g2.fillRoundRect(x, y, w, h - 6, 16, 16);

    // ================= WAVY BOTTOM =================
    int waveCount = 4;
    int waveWidth = w / waveCount;
    for (int i = 0; i < waveCount; i++) {
        g2.fillArc(
            x + i * waveWidth,
            y + h - 12,
            waveWidth,
            12,
            0,
            180
        );
    }

    // ================= EYES (WHITE, NO PUPILS) =================
    g2.setColor(Color.WHITE);
    int eyeWidth = w / 5;
    int eyeHeight = h / 5;
    int eyeY = y + h / 3;

    g2.fillOval(x + w / 4 - eyeWidth / 2, eyeY, eyeWidth, eyeHeight);
    g2.fillOval(x + (3 * w) / 4 - eyeWidth / 2, eyeY, eyeWidth, eyeHeight);

// ================= SERRATED "M" MOUTH =================
g2.setColor(Color.WHITE);

int mouthY = y + (h * 2) / 3;
int mouthLeft = x + w / 4;
int mouthRight = x + (3 * w) / 4;
int mouthWidth = mouthRight - mouthLeft;

// Height of the serration
int jag = 5;

// M-shaped jagged mouth (iconic scared look)
int[] xPoints = {
    mouthLeft,
    mouthLeft + mouthWidth / 6,
    mouthLeft + mouthWidth / 3,
    mouthLeft + mouthWidth / 2,
    mouthLeft + (2 * mouthWidth) / 3,
    mouthLeft + (5 * mouthWidth) / 6,
    mouthRight
};

int[] yPoints = {
    mouthY,
    mouthY - jag,
    mouthY,
    mouthY - jag,
    mouthY,
    mouthY - jag,
    mouthY
};

g2.drawPolyline(xPoints, yPoints, xPoints.length);
    }
    // NEW FUNCTION: Run away from a target (Pac-Man)
private Direction getBestDirectionAway(Block ghost, int targetX, int targetY) {
    Direction bestDir = ghost.direction;
    double bestDist = -1; // maximize distance

    for (Direction dir : directions) {
        // prevent reverse
        if ((ghost.direction == Direction.UP && dir == Direction.DOWN) ||
            (ghost.direction == Direction.DOWN && dir == Direction.UP) ||
            (ghost.direction == Direction.LEFT && dir == Direction.RIGHT) ||
            (ghost.direction == Direction.RIGHT && dir == Direction.LEFT))
            continue;

        int dx = 0, dy = 0;
        switch (dir) {
            case UP -> dy = -TILE_SIZE;
            case DOWN -> dy = TILE_SIZE;
            case LEFT -> dx = -TILE_SIZE;
            case RIGHT -> dx = TILE_SIZE;
            case NONE -> {}
        }

        Block test = new Block(null, ghost.x + dx, ghost.y + dy, ghost.width, ghost.height);

        boolean blocked = false;
        for (Block wall : walls) {
            if (collision(test, wall)) {
                blocked = true;
                break;
            }
        }
        if (blocked) continue;

        double dist = Math.hypot(targetX - test.x, targetY - test.y);
        if (dist > bestDist) {
            bestDist = dist;
            bestDir = dir;
        }
    }

    return bestDir;
}

}
