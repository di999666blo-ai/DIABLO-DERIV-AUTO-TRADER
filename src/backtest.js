import { analyzeCandles } from "./strategy.js";

/**
 * No-lookahead candle replay.
 * Signal is evaluated using candles through index i; entry is next candle open;
 * expiry is the close of the configured future candle. This is a research
 * approximation, not a Deriv payout/execution simulator.
 */
export function walkForwardBacktest(candles, options = {}) {
  const rows = (candles || []).filter(c => c && [c.open,c.high,c.low,c.close].every(v => Number.isFinite(Number(v))))
    .map(c => ({epoch:Number(c.epoch ?? c.open_time ?? 0),open:Number(c.open),high:Number(c.high),low:Number(c.low),close:Number(c.close)}))
    .filter(c => c.open > 0 && c.close > 0 && c.high >= Math.max(c.open,c.close,c.low) && c.low <= Math.min(c.open,c.close,c.high))
    .sort((a,b)=>a.epoch-b.epoch);
  const requestedExpiry=Number(options.expiryBars ?? 1);
  if(!Number.isInteger(requestedExpiry)||requestedExpiry<1||requestedExpiry>50)throw new Error("expiryBars must be an integer from 1 to 50");
  const expiryBars=requestedExpiry;
  const payoutRate = Number(options.payoutRate ?? 0.85);
  if (!Number.isFinite(payoutRate) || payoutRate <= 0 || payoutRate >= 1) {
    throw new Error("payoutRate must be greater than 0 and less than 1");
  }
  const requestedWarmup=Number(options.minCandles ?? 35);
  if(!Number.isInteger(requestedWarmup)||requestedWarmup<35||requestedWarmup>500)throw new Error("minCandles must be an integer from 35 to 500");
  const minCandles=requestedWarmup;
  const requestedScore=Number(options.minScore ?? 72);
  if(!Number.isFinite(requestedScore))throw new Error("minScore must be a finite number");
  const minScore=Math.max(50,Math.min(95,requestedScore));
  const trades = [];
  let nextAvailableIndex = minCandles - 1;
  for (let i = minCandles - 1; i + expiryBars < rows.length; i++) {
    if (i < nextAvailableIndex) continue;
    const signal = analyzeCandles(rows.slice(0,i+1),{...options,minCandles,minScore});
    if (!["UP","DOWN"].includes(signal.action)) continue;
    const entry = rows[i+1].open;
    const exit = rows[i+expiryBars].close;
    const result = exit === entry ? "PUSH" :
      (signal.action === "UP" ? exit > entry : exit < entry) ? "WIN" : "LOSS";
    trades.push({signalEpoch:rows[i].epoch,entryEpoch:rows[i+1].epoch,expiryEpoch:rows[i+expiryBars].epoch,direction:signal.action,score:signal.score,entry,exit,result,netR:result==="WIN"?payoutRate:result==="LOSS"?-1:0});
    nextAvailableIndex = i + expiryBars;
  }
  const wins = trades.filter(t=>t.result==="WIN").length;
  const losses = trades.filter(t=>t.result==="LOSS").length;
  const pushes = trades.length-wins-losses;
  const decisive = wins+losses;
  const grossProfit = wins*payoutRate;
  const grossLoss = losses;
  const netR = trades.reduce((sum,t)=>sum+t.netR,0);
  return {
    method:"WALK_FORWARD_NO_LOOKAHEAD",
    candles:rows.length,
    expiryBars,
    payoutRate,
    totalTrades:trades.length,
    decisiveTrades:decisive,
    wins,losses,pushes,
    winRate:decisive?Number((wins/decisive*100).toFixed(2)):null,
    profitFactor:grossLoss?Number((grossProfit/grossLoss).toFixed(3)):grossProfit?null:0,
    expectancyR:trades.length?Number((netR/trades.length).toFixed(4)):null,
    netR:Number(netR.toFixed(4)),
    warning:decisive<100?"Small sample: do not treat this result as evidence of a reliable edge.":"Historical replay only; validate on unseen data and demo execution.",
    trades
  };
}
