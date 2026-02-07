import java.awt.*;
import java.awt.event.*;
import java.util.HashSet;
import java.util.Random;
import javax.swing.*;
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
    private Image blueGhostImage;
    private Image orangeGhostImage;
    private Image pinkGhostImage;
    private Image redGhostImage;

    private Image pacmanUpImage;
    private Image pacmanDownImage;
    private Image pacmanLeftImage;
    private Image pacmanRightImage;

    //X = wall, O = skip, P = pac man, ' ' = food
    //Ghosts: b = blue, o = orange, p = pink, r = red
    private String[] tileMap = {
        "XXXXXXXXXXXXXXXXXXX",
        "X        X        X",
        "X XX XXX X XXX XX X",
        "X                 X",
        "X XX X XXXXX X XX X",
        "X    X       X    X",
        "XXXX XXXX XXXX XXXX",
        "OOOX X       X XOOO",
        "XXXX X XXrXX X XXXX",
        "O       bpo       O",
        "XXXX X XXXXX X XXXX",
        "OOOX X       X XOOO",
        "XXXX X XXXXX X XXXX",
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
        blueGhostImage = new ImageIcon(getClass().getResource("./blueGhost.png")).getImage();
        orangeGhostImage = new ImageIcon(getClass().getResource("./orangeGhost.png")).getImage();
        pinkGhostImage = new ImageIcon(getClass().getResource("./pinkGhost.png")).getImage();
        redGhostImage = new ImageIcon(getClass().getResource("./redGhost.png")).getImage();

        pacmanUpImage = new ImageIcon(getClass().getResource("./pacmanUp.png")).getImage();
        pacmanDownImage = new ImageIcon(getClass().getResource("./pacmanDown.png")).getImage();
        pacmanLeftImage = new ImageIcon(getClass().getResource("./pacmanLeft.png")).getImage();
        pacmanRightImage = new ImageIcon(getClass().getResource("./pacmanRight.png")).getImage();

        loadMap();
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
                else if (tileMapChar == 'b') { //blue ghost
                    Block ghost = new Block(blueGhostImage, x, y, TILE_SIZE, TILE_SIZE);
                    ghosts.add(ghost);
                }
                else if (tileMapChar == 'o') { //orange ghost
                    Block ghost = new Block(orangeGhostImage, x, y, TILE_SIZE, TILE_SIZE);
                    ghosts.add(ghost);
                }
                else if (tileMapChar == 'p') { //pink ghost
                    Block ghost = new Block(pinkGhostImage, x, y, TILE_SIZE, TILE_SIZE);
                    ghosts.add(ghost);
                }
                else if (tileMapChar == 'r') { //red ghost
                    Block ghost = new Block(redGhostImage, x, y, TILE_SIZE, TILE_SIZE);
                    ghosts.add(ghost);
                }
                else if (tileMapChar == 'P') { //pacman
                    pacman = new Block(pacmanRightImage, x, y, TILE_SIZE, TILE_SIZE);
                }
                else if (tileMapChar == ' ') { //food
                    Block food = new Block(null, x + 14, y + 14, 4, 4);
                    foods.add(food);
                }
            }
        }
    }

    public void paintComponent(Graphics g) {
        super.paintComponent(g);
        draw(g);
        if (!gameStarted) {
    g.setColor(Color.YELLOW);
    g.setFont(new Font("Arial", Font.BOLD, 36));
    g.drawString("PAC-MAN", boardWidth/2 - 100, boardHeight/2 - 50);
    g.setFont(new Font("Arial", Font.PLAIN, 24));
    g.drawString("Press ENTER to Start", boardWidth/2 - 120, boardHeight/2);
    return; // stop drawing the game until started
}

    }

    public void draw(Graphics g) {
        g.drawImage(pacman.image, pacman.x, pacman.y, pacman.width, pacman.height, null);

        for (Block ghost : ghosts) {
            g.drawImage(ghost.image, ghost.x, ghost.y, ghost.width, ghost.height, null);
        }

        for (Block wall : walls) {
            g.drawImage(wall.image, wall.x, wall.y, wall.width, wall.height, null);
        }

        g.setColor(Color.WHITE);
        for (Block food : foods) {
            g.fillRect(food.x, food.y, food.width, food.height);
        }
        //score
        g.setFont(new Font("Arial", Font.PLAIN, 18));
        if (gameOver) {
            g.drawString("...", TILE_SIZE / 2, TILE_SIZE / 2);

        }
        else {
            g.drawString("x" + String.valueOf(lives) + " Score: " + String.valueOf(score), TILE_SIZE/2, TILE_SIZE/2);
        }
    }
    public boolean isCentered(Block b) {
    return b.x % TILE_SIZE == 0 && b.y % TILE_SIZE == 0;
        }
    public void move() {
        if (nextDirection != Direction.NONE && isCentered(pacman)) {
            Direction oldDirection = pacman.direction;


    pacman.updateDirection(nextDirection);

    // test the turn by simulating one step
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
switch (pacman.direction) {
    case UP -> pacman.image = pacmanUpImage;
    case DOWN -> pacman.image = pacmanDownImage;
    case LEFT -> pacman.image = pacmanLeftImage;
    case RIGHT -> pacman.image = pacmanRightImage;
    case NONE -> {} // do nothing
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
            if (collision(ghost, pacman)) {
                lives -= 1;
                if (lives == 0) {
                    gameOver = true;
                    return;
                }
                resetPositions();
            }

            if (ghost.y == TILE_SIZE * 9 &&
            ghost.direction != Direction.UP &&
            ghost.direction != Direction.DOWN) {
            ghost.updateDirection(Direction.UP);
            }

            boolean moved = false;

for (int i = 0; i < 4 && !moved; i++) {

    ghost.x += ghost.velocityX;
    ghost.y += ghost.velocityY;

    boolean blocked = false;
    for (Block wall : walls) {
        if (collision(ghost, wall) || ghost.x <= 0 || ghost.x + ghost.width >= boardWidth) {
            blocked = true;
            break;
        }
    }

    if (blocked) {
        ghost.x -= ghost.velocityX;
        ghost.y -= ghost.velocityY;
        ghost.updateDirection(directions[random.nextInt(4)]);
    } else {
        moved = true;
    }
}

        }

        //check food collision
        Block foodEaten = null;
        for (Block food : foods) {
            if (collision(pacman, food)) {
                foodEaten = food;
                score += 10;
            }
        }
        foods.remove(foodEaten);

        if (foods.isEmpty()) {
            loadMap();
            resetPositions();
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
        for (Block ghost : ghosts) {
    ghost.reset();
    Direction newDirection = directions[random.nextInt(directions.length)];
    ghost.updateDirection(newDirection);
}

    }

    @Override
    public void actionPerformed(ActionEvent e) {
        move();
        repaint();
        if (gameOver) {
            gameLoop.stop();
        }
    }

    @Override
    public void keyTyped(KeyEvent e) {}

    @Override
    public void keyPressed(KeyEvent e) {
         if (e.getKeyCode() == KeyEvent.VK_UP)
    nextDirection = Direction.UP;
else if (e.getKeyCode() == KeyEvent.VK_DOWN)
    nextDirection = Direction.DOWN;
else if (e.getKeyCode() == KeyEvent.VK_LEFT)
    nextDirection = Direction.LEFT;
else if (e.getKeyCode() == KeyEvent.VK_RIGHT)
    nextDirection = Direction.RIGHT;
if (!gameStarted && e.getKeyCode() == KeyEvent.VK_ENTER) {
    gameStarted = true;
    gameLoop.start();
    return;
}


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
    

}
