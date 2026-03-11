# Pac-Man Adventure (Java Edition)

A modern Java-based Pac-Man game inspired by the classic arcade game. This version features expanded gameplay, smarter ghosts, energizers, portals, and smooth animations.

---

## Features

- **Classic Maze Gameplay:** Navigate Pac-Man through the maze, collect pellets, and avoid ghosts.
- **Smart Ghost AI:**
  - 🔴 **Red** – Direct chase
  - 🟣 **Pink** – Ambushes 4 tiles ahead
  - 🔵 **Blue** – Uses Red’s position for ambush
  - 🟠 **Orange** – Random movement when near, chases when far
- **Energizers:** Temporarily make ghosts vulnerable and edible.
- **High Score Tracking:** Saves the highest score in `highscore.dat`.
- **Mouth Animation:** Pac-Man’s mouth opens and closes dynamically.
- **Portals:** Teleport from one side of the screen to the other.
- **Lives System:** Game ends when all lives are lost.
- **Title & Ready Screens:** Fading title and countdown before starting.

---

## Controls

- **Arrow Keys:** Move Pac-Man (Up, Down, Left, Right)
- **Enter:** Start the game

---

## How to Run

1. Install Java JDK 8 or higher.
2. Place all image resources in the same folder as `PacMan.java`:
   - `wall.png`
   - `blueGhost.png`
   - `orangeGhost.png`
   - `pinkGhost.png`
   - `redGhost.png`
3. Compile and run:

```bash
javac PacMan.java
java PacMan

## Acknowledgment

This Pac-Man project was inspired by the original work of [ImKennyYip](https://github.com/ImKennyYip).  
I have significantly expanded and modified it with new features, levels, and mechanics to create this version.
