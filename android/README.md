# Netboost for Android

Zero-root DNS cache + forwarder as an Android VpnService. Intercepts all system DNS traffic, caches responses locally, and races two upstream servers for lowest latency.

## How it works

```
App → system DNS → 10.0.0.1:53 (VPN) → Netboost → hash cache → hit? return cached bytes
                                                         miss? → race 1.1.1.1 + 8.8.8.8
                                                                 → cache raw bytes → respond
```

1. VpnService creates TUN at `10.0.0.1/24`, routes only that IP, sets system DNS to `10.0.0.1`
2. TUN loop reads raw IP packets, filters for UDP/53, extracts DNS question
3. Computes hash of domain name + query type, probes 65K-slot open-addressing hash cache
4. On hit: returns cached raw DNS bytes immediately (zero parsing, zero allocation)
5. On miss: sends to both `1.1.1.1:53` and `8.8.8.8:53` simultaneously, takes first response
6. Constructs IP response packet (swap addrs, recompute checksum), writes to TUN
7. Non-DNS traffic passes through untouched (only `10.0.0.1` is routed)

## Speed features

- **Raw byte cache** — stores DNS response `ByteArray` directly, no dnsjava parse on hit
- **65,536-slot open-addressing hash table** — O(1) lookups, probe limit 8
- **Racing upstreams** — 1.1.1.1 + 8.8.8.8 in parallel, first response wins
- **Pre-warm** — top 5 domains (google/youtube/facebook/wikipedia/amazon) cached on startup
- **Reusable pre-connected sockets** — `connect()` + `protect()` to bypass VPN loop
- **Zero object allocation** — no Strings, ByteArray copies, or DNS library objects on the hot path
- **AtomicInteger counters** — thread-safe hit/miss tracking
- **No external dependencies** — pure Android SDK + Kotlin stdlib (AppCompat only for notification)

## Source files

| File | Purpose |
|------|---------|
| `app/src/main/java/netboost/NetboostVpnService.kt` | Core VpnService: TUN loop, cache, upstream racing, packet construction (~230 lines) |
| `app/src/main/java/netboost/MainActivity.kt` | Minimal UI: Start/Stop button with hit/miss display |
| `app/src/main/AndroidManifest.xml` | Permissions, foreground service type, VpnService declaration |
| `app/build.gradle.kts` | Build config (minSdk 26, targetSdk 34, no DNS library) |
| `app/src/main/res/` | Adaptive icon, strings, theme |

## Building

```bash
cd android
./gradlew assembleDebug
# Output: app/build/outputs/apk/debug/app-debug.apk
```

CI builds at [github.com/tundefund0-gif/netboost/actions](https://github.com/tundefund0-gif/netboost/actions).

## Requirements

- Android 8.0+ (API 26)
- No root required
- **Private DNS must be Off** (Settings → Private DNS → Off)

## Architecture details

### TUN loop (`loop()`)

Runs in a dedicated thread. Reads raw IP packets from `FileInputStream` wrapping the TUN fd:

```
read(buf) → parse IP header (version==4, proto==UDP) → check dst port==53
→ hash domain name (length-prefixed labels + qtype)
→ cache lookup (open addressing, 8 probes)
→ hit? AtomicInteger++ → use cached ByteArray
→ miss? AtomicInteger++ → copy wire bytes → send to both upstream sockets
→ first DatagramSocket.receive() wins → copyOf(response)
→ validate ANCOUNT > 0 → store in cache
→ build response IP packet:
    1. Copy original IP header
    2. Swap src/dst IP addresses (bytes 12-19)
    3. Update total length (bytes 2-3)
    4. Zero + recompute IP checksum (bytes 10-11)
    5. Swap UDP src/dst ports
    6. Set UDP length, zero UDP checksum (optional in IPv4)
    7. Copy DNS response payload
→ write to TUN via FileOutputStream
→ flush
```

### Pre-warm (`prewarm()`)

Runs once at startup in a background thread. Sends A queries for 5 popular domains directly to `1.1.1.1:53` with correct DNS wire-format (length-prefixed labels), validates `ANCOUNT > 0`, then inserts directly into the shared cache arrays using the same hash function. Pre-warmed entries are immediately usable by the TUN loop.

### Hash cache

```
cKeys: IntArray(65536)   // hash value, -1 = empty slot
cData: arrayOfNulls(65536) // raw DNS response ByteArray
```

Open-addressing with linear probing (max 8 probes):
- Hash = `XOR(hashLabels(name), qtype) & 0x7FFFFFFF`
- Slot = `hash & 65535`
- On collision: probe forward up to 8 slots, take first empty or matching hash
- On store: write to `insertSlot` (first empty found during probe)
- No TTL eviction — entries are overwritten when all 8 probe slots are occupied (natural LRU)

### Upstream racing

Two `DatagramSocket`s pre-connected and `protect()`ed against VPN loop:
- Both receive the same query simultaneously
- First to respond is used; the other is discarded
- Timeout: 2.5s each; if both time out, retry once at 5s
- If both time out again, packet is silently dropped

### IP packet construction

The response IP header is built from the original query header:
- Copy all 20 bytes of original IP header
- Swap `ip_src <-> ip_dst`
- Set `ip_tot_len = ihl + 8 + dns_len`
- Zero `ip_check`, recompute over all 20 header bytes
- UDP header: swap ports, set length to `8 + dns_len`, checksum = 0
- Append DNS response payload

## How to upgrade

1. Fork/clone the repo
2. Edit `NetboostVpnService.kt` — the main service logic is one file
3. Key areas for improvement:
   - **DNS-over-TLS** — replace `DatagramSocket` with `SSLSocket` to upstream
   - **EDNS0 support** — add OPT record for 4096-byte responses
   - **Cache persistence** — serialize hot cache to file on `stop()`, load on start
   - **Per-domain metrics** — track QPS, latency per domain
   - **Multiple upstream pools** — DNS-over-HTTPS, DoT, or custom resolvers
   - **Settings UI** — configurable upstream IPs, cache size, logging level
   - **IPv6** — handle AAAA queries and dual-stack
4. Build with `./gradlew assembleDebug`
5. Install APK on device
