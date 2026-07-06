import javax.swing.JFrame;

public class App {

    public static void main(String[] args) {

        JFrame frame = new JFrame("Pac Man");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setResizable(false);

        GamePanel pacmanGame = new GamePanel();
        frame.add(pacmanGame);
        frame.pack();

        frame.setLocationRelativeTo(null);
        frame.setVisible(true);

        pacmanGame.requestFocusInWindow();
    }
}
