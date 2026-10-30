# DIABLO DERIV AUTO TRADER

Separate project from the Diablo Scanner APK.

## Execution engines
- **Deriv Trader Options**: Node.js service using Deriv's current Options REST/OTP + authenticated WebSocket workflow. Supports contract discovery/proposal validation before any purchase.
- **Deriv MT5**: MQL5 Expert Advisor source in `mt5/DIABLO_DERIV_EA.mq5`. It runs inside a desktop MT5 terminal; it does not run inside the MT5 Android app.

## Safety defaults
- Demo mode only by default.
- Automatic execution is disabled until explicitly enabled.
- No martingale or loss-chasing.
- Daily loss limit, maximum trades, cooldown, spread filter and emergency stop.
- Signal confidence is a score, **not** a statistically proven win probability. No win-rate guarantee.

## Runtime
Node.js 20+. Copy `.env.example` to `.env`, add a scoped Deriv API token and App ID locally, then run `npm install` and `npm start`. Never commit credentials. A local phone process is not guaranteed to stay alive 24/7; GitHub Actions is not a trading host.

## Current implementation status
This repository is being built in stages. The first commit provides the safety-oriented service foundation and MT5 EA source. Validate on a Deriv demo account and strategy-test the EA before considering real funds.
