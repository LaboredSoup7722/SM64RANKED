import javax.swing.*;
import java.awt.*;
import java.io.*;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SM64RCT extends JFrame {

    private JPanel rootPanel;
    private CardLayout rootCardLayout;
    private JLabel timerLabel, statusLabel, targetLabel, starsLabel;
    private JLabel oppStatusLabel, oppLastSplitLabel;
    private DefaultListModel<String> splitsModel;
    private JPanel centerCards;
    private JLabel resultLabel;
    
    private int currentSegmentIndex = 0;
    private boolean isRunActive = false;
    private boolean matchReady = false; 
    private boolean atStartingLine = false;
    private boolean oppAtStartingLine = false;
    private boolean matchOver = false;
    private boolean hasFinished = false;
    private boolean opponentFinished = false;
    
    private long startTime = 0;
    private String serverIP = "localhost"; 
    private String playerFilePath;
    private String cmdFilePath;
    
    private PrintWriter networkOut;
    private BufferedReader networkIn;

    interface SegmentCondition { boolean isMet(int currentLevel, int lastLevel, int currentStars); }
    
    static class Segment {
        String name; 
        SegmentCondition condition;
        Segment(String name, SegmentCondition condition) { 
            this.name = name; 
            this.condition = condition; 
        }
    }

    private static final Segment[] ROUTE_SEGMENTS = {
        new Segment("Bob-omb Battlefield (1 Star)", (curr, last, stars) -> last == 9 && curr != 9 && stars >= 1),
        new Segment("Whomp's Fortress (6 Stars)",   (curr, last, stars) -> last == 24 && curr != 24 && stars >= 6),
        new Segment("Cool, Cool Mountain (8 Stars)",(curr, last, stars) -> last == 5 && curr != 5 && stars >= 8),
        new Segment("Big Boo's Haunt (9 Stars)",    (curr, last, stars) -> last == 4 && curr != 4 && stars >= 9),
        new Segment("Shifting Sand Land (10 Stars)",(curr, last, stars) -> last == 8 && curr != 8 && stars >= 10),
        new Segment("Lethal Lava Land (11 Stars)",  (curr, last, stars) -> last == 22 && curr != 22 && stars >= 11),
        new Segment("Hazy Maze Cave (15 Stars)",    (curr, last, stars) -> last == 7 && curr != 7 && stars >= 15),
        new Segment("MIPS Clip",                    (curr, last, stars) -> curr == 23 && last != 23 && stars == 15),
        new Segment("Dire, Dire Docks (16 Stars)",  (curr, last, stars) -> last == 23 && curr != 23 && stars >= 16),
        new Segment("Bowser in the Fire Sea",       (curr, last, stars) -> (last == 19 || last == 33) && curr == 6),
        new Segment("Bowser in the Dark World (Red Coins)", (curr, last, stars) -> (last == 17 || last == 30) && curr != last && stars >= 17),
        new Segment("Bowser in the Sky",            (curr, last, stars) -> last == 21 && curr == 34),
        new Segment("Final Bowser Fight",           (curr, last, stars) -> curr == 25)
    };

    private long[] localTimes = new long[ROUTE_SEGMENTS.length];
    private long[] oppTimes = new long[ROUTE_SEGMENTS.length];
    private int[] listIndexForSegment = new int[ROUTE_SEGMENTS.length];

    public SM64RCT() {
        Arrays.fill(localTimes, -1);
        Arrays.fill(oppTimes, -1);
        Arrays.fill(listIndexForSegment, -1);

        setTitle("SM64 Ranked - Launcher");
        setSize(450, 700);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        
        rootCardLayout = new CardLayout();
        rootPanel = new JPanel(rootCardLayout);

        JPanel startPanel = new JPanel(new GridBagLayout());
        startPanel.setBackground(new Color(20, 20, 20));
        
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(15, 15, 15, 15);
        gbc.gridx = 0;
        
        gbc.gridy = 0;
        
        // Use HTML to color each letter with official Mario hex color codes
        String marioTitle = "<html>"
            + "<font color='#E52521'>S</font>" // Mario Red
            + "<font color='#049CD8'>M</font>" // Sky Blue
            + "<font color='#FBD000'>6</font>" // Coin Yellow
            + "<font color='#43B047'>4</font>" // Pipe Green
            + " "
            + "<font color='#E52521'>R</font>"
            + "<font color='#049CD8'>a</font>"
            + "<font color='#FBD000'>n</font>"
            + "<font color='#43B047'>k</font>"
            + "<font color='#E52521'>e</font>"
            + "<font color='#049CD8'>d</font>"
            + "</html>";
            
        JLabel titleLabel = new JLabel(marioTitle, SwingConstants.CENTER);
        titleLabel.setFont(loadMarioFont(36f));
        startPanel.add(titleLabel, gbc);
        
        gbc.gridy = 1;
        JButton findMatchButton = new JButton("Find Ranked Match");
        styleButton(findMatchButton);
        findMatchButton.addActionListener(e -> startGame());
        startPanel.add(findMatchButton, gbc);

        rootPanel.add(startPanel, "START");
        add(rootPanel);
    }
    
    private void styleButton(JButton btn) {
        btn.setPreferredSize(new Dimension(200, 50));
        btn.setFont(loadMarioFont(16f));
        btn.setBackground(new Color(220, 220, 220)); 
        btn.setForeground(Color.BLACK); 
        btn.setFocusPainted(false);
        btn.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(new Color(150, 150, 150), 1, true),
            BorderFactory.createEmptyBorder(5, 15, 5, 15)
        ));
    }

    private void startGame() {
        // Hardcode your AWS EC2 IP here later. Using localhost for your local Go test!
        serverIP = "localhost"; 
        
        setTitle("SM64 Ranked - Searching...");
        
        // --- NEW UNIFIED DIRECTORY LOGIC ---
        String currentPath = System.getProperty("user.dir");
        File dataDir = new File(currentPath, "data");
        
        if (!dataDir.exists()) {
            dataDir.mkdirs();
        }
        
        // Everyone uses the exact same standard files now
        playerFilePath = new File(dataDir, "player.json").getAbsolutePath();
        cmdFilePath = new File(dataDir, "cmd.txt").getAbsolutePath();
        
        // Center the tracker on the screen
        setLocationRelativeTo(null);
        
        System.out.println("=============================================");
        System.out.println("LUA PATHS AUTO-GENERATED:");
        System.out.println("local jsonFilePath = \"" + playerFilePath.replace("\\", "\\\\") + "\"");
        System.out.println("local cmdFilePath  = \"" + cmdFilePath.replace("\\", "\\\\") + "\"");
        System.out.println("=============================================");
        
        writeCommand("GO");

        JPanel trackerContainer = new JPanel(new BorderLayout());
        trackerContainer.setBackground(new Color(20, 20, 20));

        JPanel localPanel = new JPanel(new GridLayout(4, 1));
        localPanel.setBackground(new Color(20, 20, 20));
        localPanel.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createEmptyBorder(10, 10, 5, 10), 
            // Updated border font:
            BorderFactory.createTitledBorder(BorderFactory.createLineBorder(new Color(70, 70, 70), 1, true), "Your Tracker", 0, 0, loadMarioFont(12f), Color.LIGHT_GRAY)
        ));

        statusLabel = new JLabel("Status: Connecting to Server...", SwingConstants.CENTER);
        statusLabel.setFont(loadMarioFont(14f)); // Added font
        statusLabel.setForeground(Color.LIGHT_GRAY);
        timerLabel = new JLabel("00:00.00", SwingConstants.CENTER);
        timerLabel.setFont(loadMarioFont(48f));
        timerLabel.setForeground(new Color(100, 255, 100));
        targetLabel = new JLabel("Target: N/A", SwingConstants.CENTER);
        targetLabel.setFont(loadMarioFont(14f)); // Added font
        targetLabel.setForeground(Color.WHITE);
        starsLabel = new JLabel("Stars: 0", SwingConstants.CENTER);
        starsLabel.setFont(loadMarioFont(18f)); // Added font
        starsLabel.setForeground(new Color(255, 215, 0));

        localPanel.add(statusLabel); localPanel.add(timerLabel);
        localPanel.add(targetLabel); localPanel.add(starsLabel);

        JPanel oppPanel = new JPanel(new GridLayout(2, 1));
        oppPanel.setBackground(new Color(20, 20, 20)); 
        oppPanel.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createEmptyBorder(0, 10, 5, 10),
            // Updated border font:
            BorderFactory.createTitledBorder(BorderFactory.createLineBorder(new Color(150, 50, 50), 1, true), "Opponent", 0, 0, loadMarioFont(12f), new Color(255, 100, 100))
        ));

        oppStatusLabel = new JLabel("Status: Waiting...", SwingConstants.CENTER);
        oppStatusLabel.setFont(loadMarioFont(14f)); // Added font
        oppStatusLabel.setForeground(Color.LIGHT_GRAY);
        oppLastSplitLabel = new JLabel("Last Split: None", SwingConstants.CENTER);
        oppLastSplitLabel.setForeground(Color.WHITE);
        oppLastSplitLabel.setFont(loadMarioFont(14f)); // Updated font

        oppPanel.add(oppStatusLabel); oppPanel.add(oppLastSplitLabel);

        JPanel topContainer = new JPanel(new BorderLayout());
        topContainer.setBackground(new Color(20, 20, 20));
        topContainer.add(localPanel, BorderLayout.CENTER);
        topContainer.add(oppPanel, BorderLayout.SOUTH);
        trackerContainer.add(topContainer, BorderLayout.NORTH);

        centerCards = new JPanel(new CardLayout());
        centerCards.setBackground(new Color(20, 20, 20)); 
        centerCards.setBorder(BorderFactory.createEmptyBorder(5, 10, 10, 10)); 
        
        splitsModel = new DefaultListModel<>();
        JList<String> splitsList = new JList<>(splitsModel);
        splitsList.setBackground(new Color(25, 25, 25)); 
        splitsList.setForeground(Color.WHITE);
        splitsList.setFont(loadMarioFont(14f));
        splitsList.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10)); 

        JScrollPane scrollPane = new JScrollPane(splitsList);
        scrollPane.setBorder(BorderFactory.createLineBorder(new Color(40, 40, 40), 1, true)); 
        scrollPane.getViewport().setBackground(new Color(25, 25, 25)); 
        
        centerCards.add(scrollPane, "SPLITS");
        
        JPanel resultPanel = new JPanel(new BorderLayout());
        resultPanel.setBackground(new Color(20, 20, 20));
        resultLabel = new JLabel("", SwingConstants.CENTER);
        resultLabel.setFont(loadMarioFont(42f));
        resultPanel.add(resultLabel, BorderLayout.CENTER);
        centerCards.add(resultPanel, "RESULT");

        trackerContainer.add(centerCards, BorderLayout.CENTER);

        rootPanel.add(trackerContainer, "TRACKER");
        rootCardLayout.show(rootPanel, "TRACKER");

        connectToServer();
        startTrackingThread();
    }
    
    private void writeCommand(String cmd) {
        try (PrintWriter writer = new PrintWriter(cmdFilePath)) {
            writer.print(cmd);
        } catch (FileNotFoundException e) {}
    }

    private void connectToServer() {
        new Thread(() -> {
            String targetIP = serverIP;
            int targetPort = 8080;
            
            if (serverIP.contains(":")) {
                String[] parts = serverIP.split(":");
                targetIP = parts[0];
                try { targetPort = Integer.parseInt(parts[1]); } catch (NumberFormatException e) { targetPort = 8080; }
            }

            try (Socket socket = new Socket(targetIP, targetPort)) {
                networkOut = new PrintWriter(socket.getOutputStream(), true);
                networkIn = new BufferedReader(new InputStreamReader(socket.getInputStream()));

                String serverMessage;
                while ((serverMessage = networkIn.readLine()) != null) {
                    final String msg = serverMessage;
                    
                    System.out.println("[NETWORK IN]: " + msg);
                    
                    SwingUtilities.invokeLater(() -> {
                        if (msg.startsWith("SYS:")) {
                            statusLabel.setText(msg.substring(4));
                            if (msg.contains("Opponent Found")) {
                                matchReady = true;
                                statusLabel.setForeground(new Color(100, 255, 100));
                            }
                        } else if (msg.startsWith("SPLIT#")) {
                            String[] parts = msg.split("#", 5);
                            if (parts.length == 5) {
                                try {
                                    long oppMs = Long.parseLong(parts[1]);
                                    String oppSplitTime = parts[2];
                                    int segIdx = Integer.parseInt(parts[3]);
                                    String segName = parts[4];

                                    oppTimes[segIdx] = oppMs;
                                    oppLastSplitLabel.setText("Just finished: " + segName + " (" + oppSplitTime + ")");

                                    if (localTimes[segIdx] != -1 && listIndexForSegment[segIdx] != -1) {
                                        long delta = localTimes[segIdx] - oppMs;
                                        String formattedItem = buildSplitItemString(formatTime(localTimes[segIdx]), segName, delta);
                                        int itemIdx = listIndexForSegment[segIdx];
                                        if (itemIdx < splitsModel.getSize()) {
                                            splitsModel.set(itemIdx, formattedItem);
                                        }
                                    }
                                } catch (Exception ex) {
                                    System.out.println("Error parsing opponent split: " + ex.getMessage());
                                }
                            }
                        } else if (msg.equals("AT_STARTING_LINE")) {
                            oppAtStartingLine = true;
                            oppStatusLabel.setText("At Starting Gate... Ready!");
                            oppStatusLabel.setForeground(Color.ORANGE);
                            
                        } else if (msg.equals("FINISH")) {
                            opponentFinished = true;
                            matchOver = true; 
                            oppStatusLabel.setText("Opponent FINISHED!");
                            oppStatusLabel.setForeground(Color.CYAN);
                            
                            if (!hasFinished) {
                                isRunActive = false; 
                                statusLabel.setText("Status: DEFEAT");
                                statusLabel.setForeground(Color.RED);
                                timerLabel.setForeground(Color.GRAY);
                                targetLabel.setText("Target: MATCH OVER");
                                
                                resultLabel.setText("<html><center>DEFEAT<br><br><font size='5' color='white'>Your opponent<br>finished first.</font></center></html>");
                                resultLabel.setForeground(Color.RED);
                                ((CardLayout) centerCards.getLayout()).show(centerCards, "RESULT");
                            }
                        }
                    });
                }
            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> {
                    statusLabel.setText("Server Connection Failed.");
                    statusLabel.setForeground(Color.RED);
                });
            }
        }).start();
    }

    private void startTrackingThread() {
        Thread trackerThread = new Thread(() -> {
            Path filePath = Paths.get(playerFilePath);
            int lastKnownStars = -1, lastKnownLevel = -1;
            Pattern starsPattern = Pattern.compile("\"stars\":\\s*(\\d+)");
            Pattern levelPattern = Pattern.compile("\"level\":\\s*(\\d+)");
            
            // NEW: Initialize the Heartbeat Timer
            long lastPingTime = System.currentTimeMillis();

            while (true) {
                // NEW: Fire a PING every 15 seconds to keep the tunnel alive
                if (networkOut != null && (System.currentTimeMillis() - lastPingTime > 15000)) {
                    networkOut.println("PING");
                    lastPingTime = System.currentTimeMillis();
                }

                try {
                    if (Files.exists(filePath)) {
                        String json = Files.readString(filePath).trim();
                        Matcher starsMatcher = starsPattern.matcher(json);
                        Matcher levelMatcher = levelPattern.matcher(json);

                        if (starsMatcher.find() && levelMatcher.find()) {
                            int currentStars = Integer.parseInt(starsMatcher.group(1));
                            int currentLevel = Integer.parseInt(levelMatcher.group(1));

                            if (lastKnownLevel == -1) { 
                                lastKnownLevel = currentLevel; 
                                lastKnownStars = currentStars; 
                            }

                            SwingUtilities.invokeLater(() -> starsLabel.setText("Stars: " + currentStars));

                            if (!atStartingLine && !matchOver && matchReady && lastKnownLevel != 16 && currentLevel == 16 && currentStars == 0) {
                                atStartingLine = true;
                                writeCommand("WAIT");
                                if (networkOut != null) {
                                    networkOut.println("AT_STARTING_LINE");
                                    System.out.println("[NETWORK OUT]: AT_STARTING_LINE");
                                }
                                
                                SwingUtilities.invokeLater(() -> {
                                    statusLabel.setText("WAITING AT GATE...");
                                    statusLabel.setForeground(Color.ORANGE);
                                });
                            }

                            if (atStartingLine && oppAtStartingLine && !isRunActive && !matchOver) {
                                isRunActive = true;
                                currentSegmentIndex = 0;
                                startTime = System.currentTimeMillis();
                                
                                Arrays.fill(localTimes, -1);
                                Arrays.fill(oppTimes, -1);
                                Arrays.fill(listIndexForSegment, -1);

                                writeCommand("GO");
                                
                                SwingUtilities.invokeLater(() -> {
                                    splitsModel.clear();
                                    ((CardLayout) centerCards.getLayout()).show(centerCards, "SPLITS");
                                    statusLabel.setText("Status: RACING");
                                    statusLabel.setForeground(new Color(255, 100, 100));
                                    oppStatusLabel.setText("Opponent Racing!");
                                    oppStatusLabel.setForeground(new Color(100, 255, 100));
                                    targetLabel.setText("Target: " + ROUTE_SEGMENTS[0].name);
                                });
                            }

                            if (isRunActive && currentSegmentIndex < ROUTE_SEGMENTS.length) {
                                Segment activeGoal = ROUTE_SEGMENTS[currentSegmentIndex];
                                
                                if (activeGoal.condition.isMet(currentLevel, lastKnownLevel, currentStars)) {
                                    long elapsed = System.currentTimeMillis() - startTime;
                                    String splitTime = formatTime(elapsed);
                                    
                                    localTimes[currentSegmentIndex] = elapsed;
                                    
                                    if (networkOut != null) {
                                        String payload = "SPLIT#" + elapsed + "#" + splitTime + "#" + currentSegmentIndex + "#" + activeGoal.name;
                                        networkOut.println(payload);
                                        System.out.println("[NETWORK OUT]: " + payload);
                                    }
                                    
                                    String itemStr;
                                    if (oppTimes[currentSegmentIndex] != -1) {
                                        long delta = elapsed - oppTimes[currentSegmentIndex];
                                        itemStr = buildSplitItemString(splitTime, activeGoal.name, delta);
                                    } else {
                                        itemStr = buildSplitItemString(splitTime, activeGoal.name, null);
                                    }

                                    final String finalItemStr = itemStr;
                                    final int segIdx = currentSegmentIndex;
                                    SwingUtilities.invokeLater(() -> {
                                        splitsModel.addElement(finalItemStr);
                                        listIndexForSegment[segIdx] = splitsModel.getSize() - 1;
                                    });
                                    
                                    currentSegmentIndex++;
                                    
                                    if (currentSegmentIndex < ROUTE_SEGMENTS.length) {
                                        SwingUtilities.invokeLater(() -> targetLabel.setText("Target: " + ROUTE_SEGMENTS[currentSegmentIndex].name));
                                    } else {
                                        isRunActive = false;
                                        hasFinished = true;
                                        matchOver = true;
                                        writeCommand("GO");
                                        
                                        if (networkOut != null) {
                                            networkOut.println("FINISH");
                                            System.out.println("[NETWORK OUT]: FINISH");
                                        }
                                        
                                        SwingUtilities.invokeLater(() -> {
                                            if (!opponentFinished) {
                                                statusLabel.setText("Status: VICTORY!");
                                                statusLabel.setForeground(new Color(255, 215, 0));
                                                timerLabel.setForeground(new Color(255, 215, 0)); 
                                                
                                                resultLabel.setText("<html><center>VICTORY!<br><br><font size='5' color='white'>You won the race!</font></center></html>");
                                                resultLabel.setForeground(new Color(255, 215, 0));
                                                ((CardLayout) centerCards.getLayout()).show(centerCards, "RESULT");
                                            }
                                            targetLabel.setText("Target: MATCH OVER");
                                        });
                                    }
                                }
                            }
                            lastKnownLevel = currentLevel; 
                            lastKnownStars = currentStars;
                        }
                    }
                } catch (IOException e) {}

                if (isRunActive) {
                    long elapsed = System.currentTimeMillis() - startTime;
                    SwingUtilities.invokeLater(() -> timerLabel.setText(formatTime(elapsed)));
                }

                try { Thread.sleep(16); } catch (InterruptedException e) { break; }
            }
        });
        trackerThread.setDaemon(true);
        trackerThread.start();
    }

    private String buildSplitItemString(String timeStr, String segName, Long deltaMs) {
        if (deltaMs == null) {
            return "<html><font color='white'>[" + timeStr + "] " + segName + "</font></html>";
        }
        long absMs = Math.abs(deltaMs);
        long m = (absMs / 1000) / 60;
        long s = (absMs / 1000) % 60;
        long cs = (absMs % 1000) / 10;
        String sign = deltaMs > 0 ? "+" : "-";
        String diffStr = String.format("%s%02d:%02d.%02d", sign, m, s, cs);
        String colorHex = deltaMs > 0 ? "#FF6464" : "#64FF64"; 
        
        return "<html><font color='white'>[" + timeStr + "] " + segName + " </font><font color='" + colorHex + "'>(" + diffStr + ")</font></html>";
    }

    // Loads the custom font from the relative assets folder
    private Font loadMarioFont(float size) {
        try {
            String currentPath = System.getProperty("user.dir");
            File fontFile = new File(currentPath, "assets/mario.ttf");
            
            if (fontFile.exists()) {
                Font customFont = Font.createFont(Font.TRUETYPE_FONT, fontFile);
                GraphicsEnvironment.getLocalGraphicsEnvironment().registerFont(customFont);
                // Note: The 'f' is required so Java knows you are passing a float for the size
                return customFont.deriveFont(size); 
            }
        } catch (Exception e) {
            System.out.println("Could not load custom font, falling back to default.");
        }
        // Fallback just in case the font file is missing
        return new Font("SansSerif", Font.BOLD, (int)size);
    }

    private static String formatTime(long totalMs) {
        long m = (totalMs / 1000) / 60;
        long s = (totalMs / 1000) % 60;
        long cs = (totalMs % 1000) / 10; 
        return String.format("%02d:%02d.%02d", m, s, cs);
    }

    public static void main(String[] args) {
        try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); } catch (Exception e) {}

        SwingUtilities.invokeLater(() -> {
            SM64RCT app = new SM64RCT();
            app.setLocationRelativeTo(null); 
            app.setVisible(true);
        });
    }
}