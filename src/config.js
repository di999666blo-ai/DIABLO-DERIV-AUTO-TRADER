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
  engine: process.env.ENGINE === "mt5" ? "mt5" : "options",
  maxDailyLoss: num("MAX_DAILY_LOSS", 10),
  maxTradesPerDay: Math.trunc(num("MAX_TRADES_PER_DAY", 10)),
  maxConsecutiveLosses: Math.trunc(num("MAX_CONSECUTIVE_LOSSES", 3)),
  cooldownSeconds: Math.trunc(num("COOLDOWN_SECONDS", 120)),
  defaultStake: num("DEFAULT_STAKE", 1),
  maxStake: num("MAX_STAKE", 5),
  allowedOrigins: (process.env.ALLOWED_ORIGINS || "http://127.0.0.1:8787,http://localhost:8787").split(",").map(x => x.trim())
});
