export class RiskGuard {
  constructor(limits = {}) {
    this.limits = { dailyLoss: 10, tradesPerDay: 10, consecutiveLosses: 3, cooldownMs: 120000, ...limits };
    this.day = new Date().toISOString().slice(0, 10);
    this.reset();
    this.stopped = true;
  }
  reset() { this.trades = 0; this.loss = 0; this.consecutiveLosses = 0; this.lastTradeAt = 0; }
  rollover(now = new Date()) {
    const day = now.toISOString().slice(0, 10);
    if (day !== this.day) { this.day = day; this.reset(); }
  }
  recordTrade() { this.rollover(); this.trades++; this.lastTradeAt = Date.now(); }
  recordClosedTrade(profit) {
    if (Number(profit) < 0) { this.loss += Math.abs(Number(profit)); this.consecutiveLosses++; }
    else if (Number(profit) > 0) this.consecutiveLosses = 0;
  }
  canTrade({ stake, autoTrade = false } = {}) {
    this.rollover();
    if (this.stopped) return { ok: false, reason: "Emergency stop is active" };
    if (!autoTrade) return { ok: false, reason: "Automatic execution is disabled" };
    if (!(Number(stake) > 0)) return { ok: false, reason: "Stake must be positive" };
    if (this.loss >= this.limits.dailyLoss) return { ok: false, reason: "Daily loss limit reached" };
    if (this.trades >= this.limits.tradesPerDay) return { ok: false, reason: "Daily trade limit reached" };
    if (this.consecutiveLosses >= this.limits.consecutiveLosses) return { ok: false, reason: "Consecutive loss limit reached" };
    if (Date.now() - this.lastTradeAt < this.limits.cooldownMs) return { ok: false, reason: "Cooldown active" };
    return { ok: true };
  }
  emergencyStop() { this.stopped = true; }
  arm() { this.stopped = false; }
}
