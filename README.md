# DIABLO DERIV AUTO TRADER

Separate project from the Diablo Scanner Android APK.

## What is implemented

- Deriv Options REST authentication using a Personal Access Token and App ID, followed by authenticated WebSocket sessions.
- Demo and real account session selection. Real account connections use Deriv's real Options WebSocket endpoint.
- EMA 9/21 + RSI signal filter using closed candles. The score is a heuristic strength score, **not** a win probability.
- Multi-market continuous scanner with signal-score threshold, one-entry-per-closed-candle deduplication during a running session, contract proposals, order placement and open-contract settlement subscriptions.
- Risk guard: daily loss cap, trade-count cap, consecutive-loss stop, cooldown, open-position caps and maximum stake.
- Persistent local JSON ledger and risk state; reconnect attempts are bounded and the runner stops after repeated failures.
- Start/stop/status endpoints for autonomous mode.
- Termux background-start helper and GitHub Actions syntax/test workflow.
- Existing MT5 Expert Advisor source remains a separate desktop-only starting point.

## Important verification status

The autonomous engine and real-account path have been added to source, but **they have not yet passed a live Deriv end-to-end test**. GitHub source updates are not proof of a working trade. Do not assume the bot is production-ready or profitable. The signal strategy has no demonstrated edge; score is not win probability. Live trading can lose the entire stake on each contract.

## Local setup (Android / Termux)

1. Install Node.js 20+, `tmux`, and (for automatic boot startup) Termux:Boot.
2. Clone/download this repository into `~/DIABLO-DERIV-AUTO-TRADER`.
3. Copy `.env.example` to `.env`.
4. Put your Deriv App ID, PAT with the `trade` permission, and account ID in the local `.env`. Never paste credentials into chat or commit `.env`.
5. First test with `TRADING_MODE=demo`, `AUTO_TRADE=false`. Run `npm install && npm run check && npm test`.
6. Start the service with `node --env-file=.env src/server.js`. Open `http://127.0.0.1:8787` on the same phone.
7. Test account connection, market list, analysis, contract proposals, stop control, ledger and reconnect behavior on demo before enabling unattended execution.

### Autonomous mode configuration

In local `.env` only, set `AUTO_TRADE=true`, `AUTO_ACCOUNT_ID=<your Deriv Options account ID>`, and `AUTO_MARKETS=R_10,R_25` (replace with symbols available to your account). Adjust `AUTO_GRANULARITY`, `AUTO_DURATION`, `MIN_SIGNAL_SCORE`, and risk limits conservatively. `AUTO_START=true` makes the scanner attempt to start when the Node service boots. The account must be reachable and configured.

Start manually by POSTing JSON `{"confirm":true}` to `/api/automation/start`; check `/api/automation/status`; stop new entries with POST `{"confirm":true}` to `/api/automation/stop`. The service is bound to loopback only.

### Real-account mode

Deriv's API has separate authenticated demo and real WebSocket endpoints. This repository now supports selecting a real Options account through `TRADING_MODE=real`, but this is an **unverified, high-risk path**. Only after the complete demo test sequence and your own review, set the following in local `.env`: `TRADING_MODE=real`, `LIVE_TRADING_ACK=I_ACCEPT_REAL_MONEY_RISK`, `AUTO_TRADE=true`, and your real Options account ID in `AUTO_ACCOUNT_ID`.

Keep `MAX_STAKE`, `MAX_DAILY_LOSS`, and `MAX_TRADES_PER_DAY` low. The example defaults are deliberately conservative. The acknowledgement is a local safety gate, not a guarantee of safety. Never commit the real token or `.env`. Do not activate real automation until demo tests and live-account identity checks pass.

## Background operation

Run `bash scripts/termux-start.sh` to launch the service in a tmux session and request a Termux wake lock when available. To start it after phone reboot, install Termux:Boot and place a boot script in `~/.termux/boot/` that runs this helper. Android battery management can still kill background work; a phone is not a reliable 24/7 trading host. A secured always-on VPS is more reliable. Do not expose the loopback service directly to the public internet.

## MT5 engine

`mt5/DIABLO_DERIV_EA.mq5` is an Expert Advisor source file. It must be compiled and tested in desktop MetaTrader 5; it does not run inside MT5 Android or the web terminal. It has not been verified in Strategy Tester.

## Risk and limitations

No win rate or profit is guaranteed. Before risking funds, measure expectancy, drawdown, payout structure, transaction costs and out-of-sample performance over a meaningful sample. Confirm account currency and contract rules. Stop the bot if market behavior or execution differs from expectations.