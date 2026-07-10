package main

import (
	"bufio"
	"fmt"
	"log"
	"os"
	"os/signal"
	"strings"
	"syscall"
	"time"

	"netboost/dns"
)

func main() {
	upstream, useTLS, listenPort := parseArgs()

	if upstream == "" {
		upstream, useTLS = interactive()
	}

	listenAddr := fmt.Sprintf(":%s", listenPort)

	cache := dns.NewCache(10000)
	upType := dns.UpstreamUDP
	upAddr := upstream
	upHost := ""

	if useTLS {
		upType = dns.UpstreamTLS
		if !strings.Contains(upstream, ":") {
			upAddr = upstream + ":853"
		}
		upHost = strings.Split(upstream, ":")[0]
	} else {
		if !strings.Contains(upstream, ":") {
			upAddr = upstream + ":53"
		}
	}

	srv := dns.NewServer(cache, upAddr, upType, upHost)
	if err := srv.Start(listenAddr); err != nil {
		log.Fatalf("Failed to start: %v", err)
	}

	mode := "UDP"
	if useTLS {
		mode = "DNS-over-TLS"
	}

	fmt.Println()
	fmt.Println("  ╔══════════════════════════════════════════╗")
	fmt.Println("  ║             netboost                      ║")
	fmt.Println("  ║        DNS Cache + Forwarder              ║")
	fmt.Println("  ╚══════════════════════════════════════════╝")
	fmt.Println()
	fmt.Printf("  Local DNS : %-10s\n", "udp://127.0.0.1"+listenAddr)
	fmt.Printf("  Upstream  : %-10s (%s)\n", upAddr, mode)
	fmt.Println()
	fmt.Printf("  Configure your device DNS to:  127.0.0.1%s\n", listenAddr)
	fmt.Println()
	fmt.Println("  Press Ctrl+C to stop")
	fmt.Println()

	printStats := time.NewTicker(10 * time.Second)
	defer printStats.Stop()

	sigCh := make(chan os.Signal, 1)
	signal.Notify(sigCh, syscall.SIGINT, syscall.SIGTERM)

	for {
		select {
		case <-sigCh:
			fmt.Println("\n  Shutting down...")
			srv.Stop()
			fmt.Println("  Done.")
			return
		case <-printStats.C:
			q, h, m := srv.Stats()
			total := q
			rate := 0.0
			if total > 0 {
				rate = float64(h) / float64(total) * 100
			}
			fmt.Printf("  \r  Queries: %d  |  Cache hits: %d  |  Misses: %d  |  Hit rate: %.1f%%  ", q, h, m, rate)
		}
	}
}

func parseArgs() (upstream string, useTLS bool, port string) {
	port = "5353"
	for i := 1; i < len(os.Args); i++ {
		switch {
		case os.Args[i] == "--upstream" && i+1 < len(os.Args):
			upstream = os.Args[i+1]
			i++
		case os.Args[i] == "--port" && i+1 < len(os.Args):
			port = os.Args[i+1]
			i++
		case os.Args[i] == "--tls":
			useTLS = true
		case os.Args[i] == "--help" || os.Args[i] == "-h":
			printUsage()
			os.Exit(0)
		case strings.HasPrefix(os.Args[i], "--"):
			fmt.Printf("Unknown flag: %s\n\n", os.Args[i])
			printUsage()
			os.Exit(1)
		default:
			if upstream == "" {
				upstream = os.Args[i]
			}
		}
	}
	return
}

func interactive() (string, bool) {
	reader := bufio.NewReader(os.Stdin)

	fmt.Println("  ╔══════════════════════════════════════════╗")
	fmt.Println("  ║             netboost                      ║")
	fmt.Println("  ║        DNS Cache + Forwarder              ║")
	fmt.Println("  ╚══════════════════════════════════════════╝")
	fmt.Println()
	fmt.Println("  Enter your preferred DNS provider.")
	fmt.Println("  Examples: 1.1.1.1, 8.8.8.8, dns.google, cloudflare-dns.com")
	fmt.Println()

	fmt.Print("  DNS provider [1.1.1.1]: ")
	input, _ := reader.ReadString('\n')
	input = strings.TrimSpace(input)
	if input == "" {
		input = "1.1.1.1"
	}

	useTLS := false
	isHostname := strings.Contains(input, ".")
	if !isHostname {
		return input, false
	}

	fmt.Print("  Use TLS (DNS-over-TLS)? [Y/n]: ")
	tlsInput, _ := reader.ReadString('\n')
	tlsInput = strings.TrimSpace(strings.ToLower(tlsInput))
	useTLS = tlsInput != "n" && tlsInput != "no"

	return input, useTLS
}

func printUsage() {
	fmt.Println("  netboost - DNS Cache + Forwarder")
	fmt.Println()
	fmt.Println("  Usage:")
	fmt.Println("    netboost                    Interactive mode")
	fmt.Println("    netboost [provider]         Use provider (IP or hostname)")
	fmt.Println("    netboost --upstream <addr>  Specify upstream DNS")
	fmt.Println("    netboost --upstream dns.google --tls   DNS-over-TLS")
	fmt.Println("    netboost --upstream 1.1.1.1 --port 5353  Custom port")
	fmt.Println()
	fmt.Println("  Without root, use --port 5353 (any port > 1024)")
	fmt.Println("  With root, use --port 53")
}
