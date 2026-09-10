import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;

public class SM64Server {
    
    public static void main(String[] args) {
        System.out.println("=========================================");
        System.out.println("  SM64 Ranked Matchmaking Server Active  ");
        System.out.println("  Listening on Port: 8080                ");
        System.out.println("=========================================\n");

        try (ServerSocket serverSocket = new ServerSocket(8080)) {
            
            // Infinite loop allows the server to run continuously across multiple matches
            while (true) {
                try {
                    System.out.println("[SERVER] Waiting for Player 1 (Host) to connect...");
                    Socket player1 = serverSocket.accept();
                    System.out.println("[SERVER] Player 1 connected! Waiting for Player 2...");
                    PrintWriter out1 = new PrintWriter(player1.getOutputStream(), true);
                    BufferedReader in1 = new BufferedReader(new InputStreamReader(player1.getInputStream()));
                    out1.println("SYS:Waiting for Player 2...");

                    Socket player2 = serverSocket.accept();
                    System.out.println("[SERVER] Player 2 connected! Match is locked and starting.\n");
                    PrintWriter out2 = new PrintWriter(player2.getOutputStream(), true);
                    BufferedReader in2 = new BufferedReader(new InputStreamReader(player2.getInputStream()));
                    
                    // Trigger the clients to release the starting gates
                    out1.println("SYS:Opponent Found! Ready to race.");
                    out2.println("SYS:Opponent Found! Ready to race.");

                    // Start dedicated routing threads for this specific match
                    Thread t1 = new Thread(() -> relayData(in1, out2, "Player 1"));
                    Thread t2 = new Thread(() -> relayData(in2, out1, "Player 2"));
                    
                    t1.start();
                    t2.start();

                    // The main server waits here until both players disconnect or the match drops
                    t1.join();
                    t2.join();
                    
                    // Clean up and loop back to the top for the next race
                    System.out.println("\n[SERVER] Match concluded. Resetting server for next race...\n");
                    player1.close();
                    player2.close();

                } catch (Exception e) {
                    System.out.println("[SERVER] Match interrupted or connection lost. Resetting...");
                }
            }

        } catch (Exception e) {
            System.out.println("[SERVER] CRITICAL ERROR: Could not start server. Is port 8080 already in use?");
            e.printStackTrace();
        }
    }

private static void relayData(BufferedReader in, PrintWriter out, String playerName) {
        try {
            String line;
            while ((line = in.readLine()) != null) {
                // NEW: Swallow the heartbeat silently so it doesn't spam the console
                if (line.equals("PING")) {
                    continue; 
                }
                
                System.out.println("[ROUTING from " + playerName + "]: " + line);
                out.println(line); 
            }
        } catch (Exception e) {
            // Socket closed or connection dropped, allow thread to exit quietly
        } finally {
            System.out.println("[SERVER] " + playerName + " has disconnected.");
        }
    }
}