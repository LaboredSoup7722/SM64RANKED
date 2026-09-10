import java.io.*;
import java.net.*;
import java.util.concurrent.*;

public class SM64CloudServer {
    
    // A thread-safe queue to hold players who are looking for a match
    private static final ConcurrentLinkedQueue<Socket> matchmakingQueue = new ConcurrentLinkedQueue<>();
    
    public static void main(String[] args) {
        System.out.println("=========================================");
        System.out.println("  SM64 Cloud Matchmaking Server Active   ");
        System.out.println("  Listening on Port: 8080                ");
        System.out.println("=========================================\n");

        // 1. Start a background daemon that constantly checks for pairs
        new Thread(SM64CloudServer::processMatchmaking).start();

        // 2. The main thread's ONLY job is to accept new connections instantly
        try (ServerSocket serverSocket = new ServerSocket(8080)) {
            while (true) {
                Socket clientSocket = serverSocket.accept();
                System.out.println("[SERVER] New racer connected from: " + clientSocket.getInetAddress());
                
                // Immediately tell the client they are in the queue
                PrintWriter out = new PrintWriter(clientSocket.getOutputStream(), true);
                out.println("SYS:Searching for opponent...");
                
                // Throw them in the matchmaking pool
                matchmakingQueue.add(clientSocket);
            }
        } catch (Exception e) {
            System.out.println("[SERVER] CRITICAL ERROR: Could not bind to port 8080.");
            e.printStackTrace();
        }
    }

    // Continuously checks the queue to see if two people are waiting
    private static void processMatchmaking() {
        while (true) {
            if (matchmakingQueue.size() >= 2) {
                // Pop the first two players out of the queue
                Socket racer1 = matchmakingQueue.poll();
                Socket racer2 = matchmakingQueue.poll();
                
                System.out.println("[MATCHMAKER] Found a pair! Spinning up a new match session.");
                
                // Hand them off to a dedicated Match Session thread so the server doesn't freeze
                new Thread(() -> startMatchSession(racer1, racer2)).start();
            }
            
            // Sleep for half a second to prevent the while-loop from burning out the CPU
            try { Thread.sleep(500); } catch (InterruptedException e) { break; } 
        }
    }

    // Manages an isolated 1v1 race
    private static void startMatchSession(Socket r1, Socket r2) {
        try {
            PrintWriter out1 = new PrintWriter(r1.getOutputStream(), true);
            BufferedReader in1 = new BufferedReader(new InputStreamReader(r1.getInputStream()));
            
            PrintWriter out2 = new PrintWriter(r2.getOutputStream(), true);
            BufferedReader in2 = new BufferedReader(new InputStreamReader(r2.getInputStream()));

            // Trigger both clients to release the starting gates
            out1.println("SYS:Opponent Found! Ready to race.");
            out2.println("SYS:Opponent Found! Ready to race.");

            // Start dedicated routing threads for this specific match
            // We pass the OPPONENT'S socket so we can forcefully disconnect them if one player rage-quits
            Thread t1 = new Thread(() -> relayData(in1, out2, "Racer A", r2));
            Thread t2 = new Thread(() -> relayData(in2, out1, "Racer B", r1));
            
            t1.start();
            t2.start();

            // Wait here until the race finishes or someone disconnects
            t1.join();
            t2.join();

            System.out.println("[SESSION] Match concluded. Tearing down sockets.");
            r1.close();
            r2.close();
            
        } catch (Exception e) {
            System.out.println("[SESSION] Match setup failed or dropped.");
        }
    }

    // Relays data blindly between the two connected sockets
    private static void relayData(BufferedReader in, PrintWriter out, String playerName, Socket opponentSocket) {
        try {
            String line;
            while ((line = in.readLine()) != null) {
                if (line.equals("PING")) continue; // Swallow heartbeats
                
                System.out.println("[ROUTING from " + playerName + "]: " + line);
                out.println(line); 
            }
        } catch (Exception e) {
            // Socket closed or connection dropped, allow thread to exit quietly
        } finally {
            System.out.println("[SERVER] " + playerName + " disconnected.");
            try { 
                // If Racer A disconnects, forcefully close Racer B's socket to end the match cleanly
                if (!opponentSocket.isClosed()) {
                    opponentSocket.close(); 
                }
            } catch (Exception e) {} 
        }
    }
}