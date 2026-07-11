# Netboost

Local DNS cache and forwarder for Android — no root required.

This project was originally a Go-based DNS CLI for Termux, but has evolved into a full Android VpnService app that intercepts all system DNS traffic, caches responses locally, and races multiple upstream servers for lowest latency.

## Quick start

1. Go to [Actions](https://github.com/tundefund0-gif/netboost/actions) — download the latest `app-debug.apk` artifact
2. Install on Android 8.0+ (Settings → Private DNS must be **Off**)
3. Open app → tap **Start**
4. Notification shows "Ready" with hit/miss counters

## Source structure

| Path | What |
|------|------|
| `android/` | Android app (VpnService + Activity) |
| `android/README.md` | **Full architecture docs** — start here to understand the code |
| `dns/`, `go.mod`, `main.go` | Original Go CLI prototype (archived) |

## Platform

[GitHub: tundefund0-gif/netboost](https://github.com/tundefund0-gif/netboost)
