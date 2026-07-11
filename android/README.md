# Netboost for Android

Local DNS cache for Android — runs as VpnService, no root.

## How

```
App → system DNS → TUN → Netboost → hash cache hit? → return cached bytes
                                     → cache miss? → 1.1.1.1:53 → cache + respond
```

## Safety

- MTU 10000 (virtual interface), all packets validated before write
- Single upstream (1.1.1.1), one socket, one timeout (2s)
- 16384-slot hash cache, 4 max probes, no pre-warm
- All exceptions caught per-packet, loop always continues
- Packet size bounds checked before allocation
- AtomicInteger counters (thread-safe)
- Wire-format DNS parsing with compression pointer handling

## Build

```bash
cd android && ./gradlew assembleDebug
```

CI: [github.com/tundefund0-gif/netboost/actions](https://github.com/tundefund0-gif/netboost/actions)

## Files

| File | Purpose |
|------|---------|
| `NetboostVpnService.kt` | VpnService: TUN loop, cache, upstream, packet builder |
| `MainActivity.kt` | Start/Stop + hit/miss display |
| `AndroidManifest.xml` | Permissions, foreground service |
| `build.gradle.kts` | minSdk 26, targetSdk 34, no DNS library |
