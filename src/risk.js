export class RiskGuard {
  constructor(limits = {}) {
    this.limits = { dailyLoss: 10, tradesPerDay: 10, consecutiveLosses: 3, cooldownMs: 120000, maxStake: 5, ...limits };
    this.day = this.dayKey();
    this.reset();
    this.stopped = true;
  }
  dayKey(now = new Date()) { return `${now.getFullYear()}-${now.getMonth()+1}-${now.getDate()}`; }
  reset() { this.trades = 0; this.loss = 0; this.consecutiveLosses = 0; this.lastTradeAt = 0; }
  rollover(now = new Date()) {
    const day = this.dayKey(now);
    if (day !== this.day) { this.day = day; this.reset(); }
  }
  recordTrade() { this.rollover(); this.trades++; this.lastTradeAt = Date.now(); }
  recordClosedTrade(profit) {
    const p = Number(profit);
    if (!Number.isFinite(p)) return;
    if (p < 0) { this.loss += Math.abs(p); this.consecutiveLosses++; }
    else if (p > 0) this.consecutiveLosses = 0;
    if (this.loss >= this.limits.dailyLoss || this.consecutiveLosses >= this.limits.consecutiveLosses) this.emergencyStop();
  }
  canTrade({ stake, autoTrade = false, mode = "demo" } = {}) {
    this.rollover();
    if (mode !== "demo") return { ok: false, reason: "Real-money execution is locked in this build" };
    if (this.stopped) return { ok: false, reason: "Emergency stop is active" };
    if (!autoTrade) return { ok: false, reason: "Execution is disabled; enable demo execution explicitly" };
    const amount = Number(stake);
    if (!Number.isFinite(amount) || amount <= 0) return { ok: false, reason: "Stake must be positive" };
    if (amount > this.limits.maxStake) return { ok: false, reason: `Stake exceeds configured maximum ${this.limits.maxStake}` };
    if (this.loss >= this.limits.dailyLoss) return { ok: false, reason: "Daily loss limit reached" };
    if (this.trades >= this.limits.tradesPerDay) return { ok: false, reason: "Daily trade limit reached" };
    if (this.consecutiveLosses >= this.limits.consecutiveLosses) return { ok: false, reason: "Consecutive loss limit reached" };
    if (Date.now() - this.lastTradeAt < this.limits.cooldownMs) return { ok: false, reason: "Cooldown active" };
    return { ok: true };
  }
  emergencyStop() { this.stopped = true; }
  arm() { this.stopped = false; }
  snapshot() { this.rollover(); return { stopped: this.stopped, trades: this.trades, dailyLoss: this.loss, consecutiveLosses: this.consecutiveLosses, limits: this.limits }; }
}
