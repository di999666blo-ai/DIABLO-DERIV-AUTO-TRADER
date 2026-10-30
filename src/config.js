const num = (key, fallback) => {
  const v = Number(process.env[key]);
  return Number.isFinite(v) && v >= 0 ? v : fallback;
};
export const config = Object.freeze({
  port: Math.trunc(num("PORT", 8787)),
  appId: process.env.DERIV_APP_ID || "",
  apiToken: process.env.DERIV_API_TOKEN || "",
  mode: process.env.TRADING_MODE === "real" ? "real" : "demo",
  autoTrade: process.env.AUTO_TRADE === "true",
  autoStart: process.env.AUTO_START === "true",
  liveTradingAck: process.env.LIVE_TRADING_ACK === "I_ACCEPT_REAL_MONEY_RISK",
  autoAccountId: process.env.AUTO_ACCOUNT_ID || "",
  autoMarkets: (process.env.AUTO_MARKETS || "").split(",").map(x=>x.trim()).filter(Boolean),
  autoCurrency: (process.env.AUTO_CURRENCY || "USD").toUpperCase(),
  autoGranularity: Math.trunc(num("AUTO_GRANULARITY", 60)),
  autoDuration: Math.trunc(num("AUTO_DURATION", 1)),
  autoDurationUnit: process.env.AUTO_DURATION_UNIT || "m",
  minSignalScore: Math.trunc(num("MIN_SIGNAL_SCORE", 70)),
  scanIntervalMs: Math.trunc(num("SCAN_INTERVAL_MS", 15000)),
  engine: process.env.ENGINE === "mt5" ? "mt5" : "options",
  maxDailyLoss: num("MAX_DAILY_LOSS", 10),
  maxTradesPerDay: Math.trunc(num("MAX_TRADES_PER_DAY", 10)),
  maxConsecutiveLosses: Math.trunc(num("MAX_CONSECUTIVE_LOSSES", 3)),
  cooldownSeconds: Math.trunc(num("COOLDOWN_SECONDS", 120)),
  defaultStake: num("DEFAULT_STAKE", 1),
  maxStake: num("MAX_STAKE", 5),
  allowedOrigins: (process.env.ALLOWED_ORIGINS || "http://127.0.0.1:8787,http://localhost:8787").split(",").map(x => x.trim())
});
