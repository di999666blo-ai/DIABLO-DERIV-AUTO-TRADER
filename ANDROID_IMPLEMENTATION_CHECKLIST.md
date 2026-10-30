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

## Source implementation status (not equivalent to verified functionality)
- [x] Android Kotlin/Gradle project scaffold, minSdk 26.
- [x] PAT/App ID entry with encrypted-preferences storage; app now fails closed if encryption cannot initialize (no plaintext fallback).
- [x] Deriv OTP authenticated WebSocket connection path (response schema still needs live verification).
- [x] Market-list and contracts_for request primitives.
- [x] MULTUP/MULTDOWN proposal, buy, monitor and sell request primitives.
- [x] Live tick subscription request primitive.
- [x] Closed-candle EMA/RSI/momentum signal engine (setup strength is not a win probability).
- [x] Tick-to-OHLC candle aggregation and stale-tick helper.
- [x] Risk guard for daily loss, consecutive losses, daily trade cap, open-position cap, cooldown, emergency stop and TP/SL exit thresholds.
- [x] Android APK GitHub Actions workflow.
- [ ] Build and inspect APK artifact after latest source changes.
- [ ] Verify OTP schema and successful connection against a Deriv Demo account.
- [ ] Display account list and balance.
- [ ] Implement selectable market list and multi-market subscription management.
- [ ] Wire tick stream -> candle builder -> signal engine -> risk guard -> proposal/buy -> position monitor/sell.
- [ ] Add configurable settings UI and persistent risk/trade state.
- [ ] Add duplicate-entry prevention, reconnect/backoff and open-position reconciliation.
- [ ] Persistent trade history and realized P/L.
- [ ] Complete Demo end-to-end validation on device.
- [ ] Unlock Real only after all tests pass and user explicitly chooses it.

Checked items mean source code was added; they do not mean live API tests passed. Do not use real money with an unverified build.
