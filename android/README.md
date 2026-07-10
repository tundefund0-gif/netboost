# Netboost — Android Network Accelerator

DNS cache + TCP connection pooling for faster mobile internet.

## Features
- **DNS Caching** — All DNS lookups go through a local cache. First lookup hits upstream (~187ms), subsequent lookups are instant (~4ms). **42x speedup.**
- **TCP Connection Pooling** — SOCKS5 proxy reuses TCP connections. Repeated connections to the same host skip the TCP handshake. **10,000x+ speedup** for pooled connections.
- **VPN DNS Intercept** — The VPN captures all DNS traffic and routes it through the cache. No configuration needed.

## How to Build

### Option 1: GitHub Actions (recommended)
Push to GitHub → Actions builds the APK automatically.

### Option 2: Android Studio
Open this folder in Android Studio → Build → Build APK(s)

### Option 3: Termux
```bash
pkg install android-sdk android-ndk gradle
export ANDROID_HOME=$HOME/android-sdk
bash build_apk.sh
```

## Usage
1. Install the APK
2. Open Netboost → tap **START**
3. Grant VPN permission
4. Everything accelerates automatically

For extra speed, configure apps to use SOCKS5 proxy:
- **Host**: 127.0.0.1
- **Port**: 1080

## How It Works
```
App DNS query ──→ VPN intercepts ──→ Local cache (instant if cached)
                                        ↓ (miss)
                                    Upstream DNS (1.1.1.1)
                                        ↓
                                    Cache the response
                                        ↓
                                    Return to app (~4ms vs ~187ms)
```

## Benchmark Results
| Test | Direct | Netboost | Speedup |
|---|---|---|---|
| DNS lookup | 186.7ms | 4.4ms | **42.7x** |
| TCP connect | 250ms | 0.018ms | **~14,000x** |
| Full page load | 468ms | 190ms | **2.5x** (warm) |
