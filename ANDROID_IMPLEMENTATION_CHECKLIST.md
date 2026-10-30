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
- [x] Android Kotlin/Gradle project scaffold, minSdk 26 (Android 8+).
- [x] PAT/App ID entry with encrypted-preferences storage; fails closed if encryption cannot initialize.
- [x] OTP REST flow uses Deriv's returned authenticated Demo WebSocket URL; redundant `authorize` call removed.
- [x] Demo account discovery through `GET /trading/v1/options/accounts`; Demo-only account selection.
- [x] Account balance subscription and active-symbol discovery.
- [x] Per-symbol `contracts_for` check for MULTUP and MULTDOWN.
- [x] Select up to 20 markets; unsupported markets are skipped.
- [x] Shared-risk live tick -> 5-second OHLC candle -> EMA/RSI/momentum signal -> proposal -> buy -> open-contract monitor -> sell flow.
- [x] One open position maximum across selected markets; duplicate candle entries blocked.
- [x] Risk settings UI: stake, multiplier, TP, SL, daily loss, consecutive-loss cap, trade cap, cooldown.
- [x] Risk settings and closed-trade daily counters restored from encrypted local trade history.
- [x] Stale-feed watchdog; stale symbols do not produce entries.
- [x] Bounded reconnect backoff; reconnect does not automatically restart trading.
- [x] Portfolio check blocks new scanning when Deriv reports existing open positions.
- [x] Emergency stop prevents new entries while keeping an already-open position monitored if the app stays open.
- [x] Persistent trade history stores symbol, direction, entry price, exit price, P/L and decision reason.
- [x] Real-money execution remains locked.
- [x] GitHub Actions workflow configured to build and upload a debug APK.
- [ ] Successful latest APK build and artifact inspection.
- [ ] Live Demo validation of account discovery, OTP, ticks, multiplier proposal, buy, monitoring, sell and settlement.
- [ ] Device testing on Android 8 / Huawei Y6 Prime 2018.
- [ ] Verify behavior through app backgrounding, network loss and app restart; an existing position must be manually checked in Deriv if monitoring is interrupted.
- [ ] Confirm all account/contract response fields against the user's Demo account before enabling any real-money capability.

## Important operating limits
- The app does not run a hidden 24/7 background service. Leaving the app or losing the connection stops new entries; an open Deriv contract may remain active on Deriv.
- Reconnection is bounded and reconnects the account only. It never resumes scanning automatically.
- If an open position is found after reconnect, the app blocks new entries until the user checks it in Deriv.
- Signal strength is a setup score, not a win probability.
- No real-money mode is enabled.

