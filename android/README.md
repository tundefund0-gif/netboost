# Netboost for Android

A local DNS cache + forwarder that runs as an Android VpnService — no root required. Accelerates DNS lookups by caching responses locally and racing multiple upstream DNS servers.

## How it works

```
App → System DNS resolver → TUN interface → Netboost VpnService → 1.1.1.1 (racing)
                                              ↓                       8.8.8.8
                                         65K-slot hash cache
                                              ↓
                                     Response written to TUN
                                              ↓
                                     System → App (sub-ms on hit)
```

1. Creates a local VPN (VpnService) with address `10.0.0.1`
2. Sets system DNS server to `10.0.0.1` — all DNS queries route through the TUN
3. Reads raw IP packets from TUN fd
4. For UDP/53 packets: extracts DNS question, checks hash cache, forwards upstream on miss
5. Constructs response IP packet (swap src/dst, fix checksums) and writes to TUN
6. Non-DNS traffic passes through the normal network (only `10.0.0.1` is routed through VPN)

## Speed features

- **Raw byte cache** — stores DNS response `ByteArray` directly, zero parsing on hit
- **65,536-slot open-addressing hash table** — O(1) lookups, no GC pressure
- **Racing upstreams** — sends query to both `1.1.1.1` and `8.8.8.8`, takes first response
- **Reusable sockets** — upstream `DatagramSocket`s are created once with `connect()` and `protect()`
- **No object allocation on cache hit** — no String, Message, or ByteArray copy
- **IP checksum computed inline** — no library overhead

## Source files

| File | Purpose |
|------|---------|
| `app/src/main/java/netboost/NetboostVpnService.kt` | Core VpnService: TUN loop, cache, upstream racing, packet construction (everything in one file) |
| `app/src/main/java/netboost/MainActivity.kt` | Simple UI: Start/Stop button, hit/miss display |
| `app/src/main/AndroidManifest.xml` | Permissions, service declaration, activity |
| `app/build.gradle.kts` | Build config (minSdk 26, targetSdk 34, dnsjava dependency) |
| `app/src/main/res/` | App icon (adaptive), strings, theme |

## Building

```bash
# Debug APK
cd android
gradle assembleDebug
# Output: app/build/outputs/apk/debug/app-debug.apk
```

## Requirements

- Android 8.0+ (API 26)
- No root required
- Private DNS must be **Off** in Settings → Private DNS

## Architecture notes for upgrades

### The TUN loop (`NetboostVpnService.kt`)

The `loop()` function reads raw IP packets from a `FileInputStream` wrapping the TUN fd:

```
read() → parse IP header → check UDP/53 → extract DNS question bytes
  → hash question name → lookup cache → hit? return cached bytes : miss?
  → send to upstream sockets → take first response → cache raw bytes
  → build response IP packet (swap addresses, fix checksum) → write to TUN
```

### Packet construction

The response packet is built by:
1. Copying IP header from original request
2. Swapping source/destination IP addresses (bytes 12-19)
3. Fixing total length field (bytes 2-3)
4. Zeroing + recomputing IP checksum (bytes 10-11)
5. Swapping UDP source/destination ports
6. Replacing DNS payload with cached/upstream response

### Hash cache

Uses open-addressing with linear probing (8 probes max):
- `cKeys[IntArray(65536)]` stores hash → -1 means empty slot
- `cData[arrayOfNulls<ByteArray>(65536)]` stores raw response bytes
- Hash is computed from domain name labels + query type (XOR)
- On collision, probes forward up to 8 slots

### Upstream racing

Two pre-connected `DatagramSocket`s (1.1.1.1:53 and 8.8.8.8:53) with 2.5s timeout:
- Both receive the query simultaneously
- First to respond is used; if both time out, falls back to single retry

## To upgrade

1. Fork/clone the repo
2. Edit `NetboostVpnService.kt` — the main service logic is ~180 lines
3. Key areas for improvement:
   - **Additional upstreams** — add more DNS servers to the race pool
   - **DNS-over-TLS** — replace UDP sockets with SSLSocket for encrypted upstream
   - **Metrics** — expose hit rate, latency percentiles
   - **Per-domain stats** — track most queried domains
   - **Cache persistence** — save hot cache to disk on shutdown
   - **Settings activity** — configurable upstream IP, cache size, etc.
4. Build with `gradle assembleDebug`
5. Install APK on device
