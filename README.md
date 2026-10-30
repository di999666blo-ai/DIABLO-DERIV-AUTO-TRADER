# DIABLO DERIV AUTO TRADER

Separate project from the Diablo Scanner Android APK.

## Current build status

The repository now contains a demo-only Deriv Options service and browser dashboard foundation:
- Deriv Options REST account lookup and OTP WebSocket authentication (PAT + App ID).
- Demo account connection, active market lookup and historical candles.
- EMA 9/21 + RSI signal filter. The score is a heuristic strength score, not a win probability.
- Contract proposal and separate explicit manual demo-buy confirmation.
- Local emergency stop, daily loss and consecutive-loss guard, account-wide/per-market open-trade caps, and capped win-step stake progression (1 to 5; losses hold the current step).
- Multi-market batch planner: select 1–20 markets, set trades per market, and preview up to 100 planned orders.
- Initial MT5 Expert Advisor source and GitHub Actions Node checks.

**Still incomplete / not verified:** unattended multi-market execution, automatic contract outcome reconciliation and durable daily P/L tracking, live candle streaming, complete trade history, MT5 compile and Strategy Tester verification, Android runtime packaging, and 24/7 hosting. The batch planner is preview-only; it does not place multiple orders. This is not yet a finished autonomous bot.

## Deriv Options setup

1. Install Node.js 20+ in a trusted local runtime. On Android, a compatible terminal environment is required; an ordinary browser tab cannot run this server in the background.
2. Download this repository into that runtime.
3. Copy `.env.example` to a local file named `.env`.
4. Create a Deriv Personal Access Token with trade permission. Keep it private. Put the token and App ID only in the local `.env`; never paste them into chat or commit them.
5. Run `npm install`, `npm test`, then `npm start`.
6. Open `http://127.0.0.1:8787` on the same device.
7. Connect a demo Options account, load markets, analyze, request a quote, arm the demo risk guard, and confirm any demo purchase manually.

The service binds to loopback and is not designed for public internet exposure. Real-money sessions are rejected. Do not use real funds before comprehensive testing.

## MT5 engine

`mt5/DIABLO_DERIV_EA.mq5` is an Expert Advisor source file. It must be compiled and tested in **desktop MetaTrader 5**. It does not run inside the MT5 Android app or web terminal. The current source is early and has not been compiled or validated in Strategy Tester. Keep `EnableTrading=false` and test on demo only.

## Hosting and performance

A phone may suspend background processes. GitHub Actions runs checks; it is not a safe continuous trading host. 24/7 operation requires a secured always-on host. No win rate or profit is guaranteed; measure expectancy, drawdown, payouts, costs, slippage and out-of-sample performance over a meaningful sample.

## Safety defaults

- Demo only; real-money sessions rejected.
- Explicit manual confirmation for each order.
- No martingale or loss chasing.
- Risk guard starts stopped.
- Stake cap defaults to 5 account-currency units.
