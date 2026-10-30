import http from "node:http";
import { config } from "./config.js";
import { RiskGuard } from "./risk.js";
import { analyzeCandles } from "./strategy.js";

const guard = new RiskGuard({
  dailyLoss: config.maxDailyLoss,
  tradesPerDay: config.maxTradesPerDay,
  consecutiveLosses: config.maxConsecutiveLosses,
  cooldownMs: config.cooldownSeconds * 1000
});
const send = (res, status, body) => {
  res.writeHead(status, { "content-type": "application/json; charset=utf-8", "access-control-allow-origin": "http://localhost" });
  res.end(JSON.stringify(body));
};
const server = http.createServer(async (req, res) => {
  if (req.method === "OPTIONS") { res.writeHead(204, { "access-control-allow-methods": "GET,POST,OPTIONS", "access-control-allow-headers": "content-type" }); return res.end(); }
  if (req.method === "GET" && req.url === "/api/status") {
    return send(res, 200, { name: "DIABLO DERIV AUTO TRADER", engine: config.engine, mode: config.mode, autoTrade: config.autoTrade, credentialsConfigured: Boolean(config.appId && config.apiToken), risk: { stopped: guard.stopped } });
  }
  if (req.method === "POST" && req.url === "/api/analyze") {
    let raw = ""; for await (const chunk of req) raw += chunk;
    try {
      const body = JSON.parse(raw || "{}");
      return send(res, 200, analyzeCandles(body.candles, body.options));
    } catch { return send(res, 400, { error: "Invalid JSON or candle payload" }); }
  }
  if (req.method === "POST" && req.url === "/api/stop") { guard.emergencyStop(); return send(res, 200, { stopped: true }); }
  if (req.method === "POST" && req.url === "/api/arm-demo") {
    if (config.mode !== "demo") return send(res, 403, { error: "Arming endpoint is demo-only" });
    guard.arm(); return send(res, 200, { armed: true, mode: "demo" });
  }
  return send(res, 404, { error: "Not found" });
});
server.listen(config.port, "127.0.0.1", () => console.log(`DIABLO service listening on http://127.0.0.1:${config.port}`));
