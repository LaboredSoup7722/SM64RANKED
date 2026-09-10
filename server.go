package main

import (
	"bufio"
	"fmt"
	"log"
	"net"
)

func main() {
	fmt.Println("=========================================")
	fmt.Println("  SM64 Go Matchmaking Server Active      ")
	fmt.Println("  Listening on Port: 8080                ")
	fmt.Println("=========================================\n")

	// A channel replaces Java's ConcurrentLinkedQueue. 
	// It safely passes sockets between concurrent goroutines.
	matchQueue := make(chan net.Conn, 100)

	// 1. Start the matchmaker routine in the background
	go processMatchmaking(matchQueue)

	// 2. Start listening for TCP connections on port 8080
	listener, err := net.Listen("tcp", ":8080")
	if err != nil {
		log.Fatal("[SERVER] CRITICAL ERROR: Could not bind to port 8080. ", err)
	}
	defer listener.Close()

	// 3. The main thread's ONLY job is to accept new connections
	for {
		conn, err := listener.Accept()
		if err != nil {
			fmt.Println("[SERVER] Failed to accept connection:", err)
			continue
		}

		fmt.Println("[SERVER] New racer connected from:", conn.RemoteAddr())

		// Tell the client they are in the queue 
		// (\n is required because Java's readLine() expects it)
		conn.Write([]byte("SYS:Searching for opponent...\n"))

		// Put the client in the matchmaking queue
		matchQueue <- conn
	}
}

// Continuously waits for two people to enter the queue
func processMatchmaking(queue <-chan net.Conn) {
	for {
		// This blocks silently until two players are available
		racer1 := <-queue
		racer2 := <-queue

		fmt.Println("[MATCHMAKER] Found a pair! Spinning up a new match session.")

		// Hand them off to a dedicated Match Session goroutine
		go startMatchSession(racer1, racer2)
	}
}

// Manages an isolated 1v1 race
func startMatchSession(r1, r2 net.Conn) {
	r1.Write([]byte("SYS:Opponent Found! Ready to race.\n"))
	r2.Write([]byte("SYS:Opponent Found! Ready to race.\n"))

	// Create a channel to signal when a relay stops
	done := make(chan bool)

	// Start dedicated routing goroutines
	go relayData(r1, r2, "Racer A", done)
	go relayData(r2, r1, "Racer B", done)

	// Wait here until ONE of the relay routines finishes (someone disconnects)
	<-done

	fmt.Println("[SESSION] Match concluded. Tearing down sockets.")
	r1.Close()
	r2.Close()
	
	// Wait for the SECOND relay routine to cleanly exit before destroying the session
	<-done
}

// Relays data blindly between the two connected sockets
func relayData(in net.Conn, out net.Conn, playerName string, done chan<- bool) {
	// The defer statement guarantees this cleanup code runs exactly when the function exits
	defer func() {
		fmt.Println("[SERVER]", playerName, "disconnected.")
		in.Close()
		out.Close() // Forcefully close the opponent's socket to end the match
		done <- true
	}()

	scanner := bufio.NewScanner(in)
	for scanner.Scan() {
		line := scanner.Text()
		
		if line == "PING" {
			continue // Swallow heartbeats
		}

		fmt.Println("[ROUTING from " + playerName + "]: " + line)
		out.Write([]byte(line + "\n"))
	}
}