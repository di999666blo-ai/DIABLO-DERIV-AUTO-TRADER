# DIABLO DERIV AUTO TRADER — complete scope checklist

This native APK is Deriv-only and separate from Diablo Scanner, the Telegram bot, and the legacy MT5 EA.

1. Native Android APK, Android 8.0+ (API 26), phone-first; no Termux, PC or Android Studio required from the user.
2. Direct Deriv authentication using App ID + PAT with trade permission; credentials stored only on device, never GitHub.
3. Demo/Real account selection; Real locked until full Demo verification and explicit user activation.
4. Account list and balance; market search and selection; check contract availability per symbol.
5. Deriv Multipliers only (MULTUP/MULTDOWN); no MT5, BigQuote or third-party market-data host.
6. Live tick/candle data and selectable scanner markets (up to 20).
7. Fast scalper continuously evaluates entries and exits; no fixed-duration CALL/PUT lifecycle.
8. Deriv proposal, buy, open-contract monitoring and sell-to-close.
9. Risk settings: stake, multiplier, take-profit, stop-loss, max simultaneous positions, daily loss, consecutive-loss cap, trade cap, cooldown and emergency stop.
10. Trade history with symbol, direction, entry, exit, P/L and decision reason.
11. App-open lifecycle: stop new entries when app is stopped/closed/offline; reconcile open Deriv contracts on reconnect; no hidden 24/7 service.
12. Reconnection/backoff, stale-feed checks, duplicate-entry prevention, API error handling and idempotency.
13. GitHub Actions APK build and downloadable artifact; inspect successful build before calling APK ready.
14. Demo end-to-end tests: auth, market discovery, multiplier availability, proposal, buy, monitor, sell, settlement, reconnect and risk stops.
15. Real-money execution only after all Demo checks pass and explicit user approval.

## Current source status (not equivalent to verified functionality)
- [x] Android Kotlin/Gradle project scaffold, minSdk 26.
- [x] PAT/App ID entry with encrypted-preferences storage attempt.
- [x] Deriv OTP authenticated WebSocket connection path (response schema still needs live verification).
- [x] Market-list and contracts_for request primitives.
- [x] MULTUP/MULTDOWN proposal, buy, monitor and sell request primitives.
- [x] Android APK GitHub Actions workflow.
- [ ] Build and inspect APK artifact.
- [ ] Verify OTP schema and successful connection against a Deriv Demo account.
- [ ] Display account list and balance.
- [ ] Implement selectable market list and live tick/candle stream.
- [ ] Implement tested fast-scalper strategy and autonomous execution loop.
- [ ] Enforce entry/exit risk controls, duplicate prevention and open-position reconciliation.
- [ ] Persistent trade history and realized P/L.
- [ ] Complete Demo end-to-end validation on device.
- [ ] Unlock Real only after all tests pass and user explicitly chooses it.

Checked items mean code was added; they do not mean live API tests passed. Do not use real money with this build.
