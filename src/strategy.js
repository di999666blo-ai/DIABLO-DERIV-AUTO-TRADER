// Deterministic indicator-based setup score. This is not a win probability.
const ema = (values, period) => {
  if (values.length < period) return null;
  const k = 2 / (period + 1);
  let out = values.slice(0, period).reduce((a, b) => a + b, 0) / period;
  for (const v of values.slice(period)) out = v * k + out * (1 - k);
  return out;
};
const rsi = (values, period = 14) => {
  if (values.length <= period) return null;
  let gains = 0, losses = 0;
  for (let i = values.length - period; i < values.length; i++) {
    const d = values[i] - values[i - 1];
    if (d > 0) gains += d; else losses -= d;
  }
  if (losses === 0) return 100;
  return 100 - 100 / (1 + gains / losses);
};
export function analyzeCandles(candles, options = {}) {
  const closes = (candles || []).map(c => Number(c.close)).filter(Number.isFinite);
  const min = options.minCandles ?? 35;
  if (closes.length < min) return { action: "WAIT", score: 0, reason: "Not enough closed candles" };
  const fast = ema(closes, options.fastPeriod ?? 9);
  const slow = ema(closes, options.slowPeriod ?? 21);
  const strength = rsi(closes, options.rsiPeriod ?? 14);
  if (fast == null || slow == null || strength == null) return { action: "WAIT", score: 0, reason: "Indicators unavailable" };
  const bullish = fast > slow && strength >= 52 && strength <= 72 && closes.at(-1) > fast;
  const bearish = fast < slow && strength <= 48 && strength >= 28 && closes.at(-1) < fast;
  if (!bullish && !bearish) return { action: "WAIT", score: 0, reason: "Trend and momentum are not aligned", indicators: { fast, slow, rsi: strength } };
  const separation = Math.min(1, Math.abs(fast - slow) / Math.max(Math.abs(slow), 1e-9) * 1000);
  const momentum = bullish ? (strength - 50) / 22 : (50 - strength) / 22;
  const score = Math.round(Math.max(0, Math.min(100, 50 + separation * 20 + momentum * 20)));
  return { action: bullish ? "UP" : "DOWN", score, reason: "EMA trend and RSI momentum aligned", indicators: { fast, slow, rsi: strength } };
}
