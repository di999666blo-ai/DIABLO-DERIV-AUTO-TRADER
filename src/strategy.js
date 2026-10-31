// Adaptive closed-candle strategy for Deriv Options.
// Score is a setup-quality heuristic, NOT a probability of profit.
const clamp=(x,lo,hi)=>Math.max(lo,Math.min(hi,x));
const mean=a=>a.length?a.reduce((s,x)=>s+x,0)/a.length:0;

function ema(values,period){
  if(values.length<period)return null;
  const alpha=2/(period+1);
  let value=mean(values.slice(0,period));
  for(let i=period;i<values.length;i++)value=values[i]*alpha+value*(1-alpha);
  return value;
}
function rsi(values,period=14){
  if(values.length<=period)return null;
  let gain=0,loss=0;
  for(let i=1;i<=period;i++){
    const change=values[i]-values[i-1];
    if(change>0)gain+=change;else loss-=change;
  }
  let avgGain=gain/period,avgLoss=loss/period;
  for(let i=period+1;i<values.length;i++){
    const change=values[i]-values[i-1];
    avgGain=(avgGain*(period-1)+Math.max(0,change))/period;
    avgLoss=(avgLoss*(period-1)+Math.max(0,-change))/period;
  }
  if(avgLoss===0)return avgGain===0?50:100;
  return 100-100/(1+avgGain/avgLoss);
}
function trueRanges(rows){
  const ranges=[];
  for(let i=1;i<rows.length;i++){
    const c=rows[i],p=rows[i-1];
    ranges.push(Math.max(c.high-c.low,Math.abs(c.high-p.close),Math.abs(c.low-p.close)));
  }
  return ranges;
}
function median(values){
  if(!values.length)return 0;
  const sorted=[...values].sort((a,b)=>a-b),mid=Math.floor(sorted.length/2);
  return sorted.length%2?sorted[mid]:(sorted[mid-1]+sorted[mid])/2;
}
function atrPercent(rows,period=14){
  if(rows.length<period+1)return null;
  const ranges=trueRanges(rows),atr=mean(ranges.slice(-period));
  return rows.at(-1).close===0?null:atr/Math.abs(rows.at(-1).close)*100;
}
function relativeVolatility(rows,period=14){
  const ranges=trueRanges(rows);
  if(ranges.length<period)return null;
  const baseline=median(ranges.slice(-30));
  return baseline>0?mean(ranges.slice(-period))/baseline:null;
}
function efficiency(values,period=10){
  if(values.length<=period)return 0;
  const net=Math.abs(values.at(-1)-values[values.length-1-period]);
  let path=0;
  for(let i=values.length-period;i<values.length;i++)path+=Math.abs(values[i]-values[i-1]);
  return path===0?0:net/path;
}

/**
 * Adaptive closed-candle analysis. It adjusts confirmation thresholds to the
 * observed volatility/trend regime; user stake never changes signal direction.
 * options: minCandles, fastPeriod, slowPeriod, rsiPeriod, minScore.
 */
export function analyzeCandles(candles,options={}){
  const rows=(candles||[]).filter(c=>c&&[c.open,c.high,c.low,c.close].every(v=>Number.isFinite(Number(v))))
    .map(c=>({open:Number(c.open),high:Number(c.high),low:Number(c.low),close:Number(c.close),epoch:Number(c.epoch??c.open_time??0)}))
    .filter(c=>c.high>=Math.max(c.open,c.close,c.low)&&c.low<=Math.min(c.open,c.close,c.high));
  const fastPeriod=Math.max(2,Math.trunc(options.fastPeriod??9));
  const slowPeriod=Math.max(fastPeriod+1,Math.trunc(options.slowPeriod??21));
  const rsiPeriod=Math.max(2,Math.trunc(options.rsiPeriod??14));
  const minimum= Math.max(slowPeriod+2,rsiPeriod+2,Math.trunc(options.minCandles??35));
  const base={action:"WAIT",score:0,reason:"Insufficient closed candles",candleCount:rows.length};
  if(rows.length<minimum)return base;
  const closes=rows.map(c=>c.close),last=rows.at(-1),prev=rows.at(-2);
  const fast=ema(closes,fastPeriod),slow=ema(closes,slowPeriod),momentum=rsi(closes,rsiPeriod);
  const atr=atrPercent(rows),volRatio=relativeVolatility(rows),er=efficiency(closes,Math.min(10,rows.length-1));
  if([fast,slow,momentum,atr,volRatio].some(v=>v===null||!Number.isFinite(v)))return{...base,reason:"Indicator calculation unavailable"};
  // Relative ATR adapts across Deriv symbols with very different quote scales.
  const volatility=volRatio<0.35?"LOW":volRatio>2.5?"HIGH":"NORMAL";
  const regime=er>0.55?"TRENDING":er<0.20?"RANGING":"MIXED";
  const diagnostics={fast,slow,rsi:momentum,atrPercent:atr,volatilityRatio:volRatio,efficiency:er};
  if(volatility==="HIGH")return{...base,reason:"Volatility spike: stand aside",regime,volatility,indicators:diagnostics};
  if(volatility==="LOW")return{...base,reason:"Dead volatility: stand aside",regime,volatility,indicators:diagnostics};
  if(regime==="RANGING")return{...base,reason:"Ranging market: no directional edge",regime,volatility,indicators:diagnostics};
  const range=Math.max(last.high-last.low,Math.abs(last.close)*1e-12);
  const body=Math.abs(last.close-last.open)/range;
  const candleUp=last.close>last.open&&last.close>prev.close;
  const candleDown=last.close<last.open&&last.close<prev.close;
  const upTrend=fast>slow&&last.close>fast&&momentum>=50&&momentum<=75;
  const downTrend=fast<slow&&last.close<fast&&momentum<=50&&momentum>=25;
  const trendGap=Math.abs(fast-slow)/Math.max(Math.abs(slow),1e-12);
  const trendPoints=clamp(trendGap*12000,0,25);
  const momentumPoints=clamp(Math.abs(momentum-50)*1.1,0,22);
  const structurePoints=body>=0.60?18:body>=0.40?12:0;
  const efficiencyPoints=clamp(er*30,0,25);
  const direction=upTrend&&candleUp?"UP":downTrend&&candleDown?"DOWN":"WAIT";
  const score=direction==="WAIT"?0:Math.round(clamp(35+trendPoints+momentumPoints+structurePoints+efficiencyPoints,0,100));
  const minScore=clamp(Number(options.minScore??72),50,95);
  const action=score>=minScore?direction:"WAIT";
  return{
    action,score:action==="WAIT"?score:score,
    reason:action==="WAIT"?(direction==="WAIT"?"Trend, momentum and closed-candle structure are not aligned":"Setup below adaptive quality threshold"):`Adaptive ${regime.toLowerCase()} ${action.toLowerCase()} setup confirmed on a closed candle`,
    regime,volatility,candleCount:rows.length,candleEpoch:last.epoch,
    indicators:{...diagnostics,bodyRatio:body},
    note:"Score measures setup alignment; it is not a win-rate or profit probability."
  };
}


/**
 * Tick-level entry confirmation. This is a setup-quality score, not a win
 * probability. It requires a minimum sample and directional follow-through.
 * BOOM/CRASH entries only trigger after an observed characteristic spike
 * followed by two confirming ticks; the engine never predicts a spike.
 */
export function analyzeTickMomentum(ticks,symbol="",options={}){
  const rows=(ticks||[]).map(t=>({quote:Number(t.quote??t.price),epoch:Number(t.epoch??0)}))
    .filter(t=>Number.isFinite(t.quote)&&t.quote>0);
  const minTicks=Math.max(10,Math.trunc(options.minTicks??14));
  const base={action:"WAIT",score:0,reason:"Collecting tick confirmation",tickCount:rows.length,engine:"TICK_FAST"};
  if(rows.length<minTicks)return base;
  const changes=[];
  for(let i=1;i<rows.length;i++)changes.push(rows[i].quote-rows[i-1].quote);
  const abs=changes.map(Math.abs).filter(x=>x>0);
  if(abs.length<8)return {...base,reason:"Insufficient price movement to establish tick baseline"};
  const sorted=[...abs].sort((a,b)=>a-b),median=sorted[Math.floor(sorted.length/2)]||0;
  if(!(median>0))return {...base,reason:"Market is stationary"};
  const upper=symbol.toUpperCase();
  const boom=upper.startsWith("BOOM"),crash=upper.startsWith("CRASH");
  let direction="WAIT",score=0,reason="Tick momentum is not confirmed";
  if(boom||crash){
    const expected=boom?1:-1;
    const tail=changes.slice(-8);
    const spikeIndex=tail.findIndex(x=>Math.sign(x)===expected&&Math.abs(x)>=median*4);
    if(spikeIndex<0)return {...base,reason:boom?"Waiting for an observed upward Boom spike":"Waiting for an observed downward Crash spike",marketProfile:boom?"BOOM_SPIKE":"CRASH_SPIKE"};
    const after=tail.slice(spikeIndex+1);
    if(after.length<2)return {...base,reason:"Spike detected; waiting for two follow-through ticks",marketProfile:boom?"BOOM_SPIKE":"CRASH_SPIKE"};
    const spikeMove=tail[spikeIndex],follow=after.slice(0,2);
    const held=follow.every(x=>Math.sign(x)===expected||x===0);
    // Compare the current quote with the quote immediately after the spike.
    // The previous index formula anchored before the spike and missed retraces.
    const postSpikeIndex=rows.length-tail.length+spikeIndex;
    const postSpikeQuote=rows[postSpikeIndex]?.quote;
    if(!Number.isFinite(postSpikeQuote))return {...base,reason:"Spike reference price unavailable",marketProfile:boom?"BOOM_SPIKE":"CRASH_SPIKE"};
    const netAfter=rows.at(-1).quote-postSpikeQuote;
    const retrace=Math.max(0,-expected*netAfter);
    if(!held||retrace>Math.abs(spikeMove)*0.5)return {...base,reason:"Spike failed follow-through; stand aside",marketProfile:boom?"BOOM_SPIKE":"CRASH_SPIKE"};
    direction=expected>0?"UP":"DOWN";
    score=76;
    reason="Observed characteristic spike with two confirming follow-through ticks";
  }else{
    const tail=changes.slice(-8),net=tail.reduce((a,b)=>a+b,0);
    const aligned=tail.filter(x=>Math.sign(x)===Math.sign(net)&&x!==0).length;
    const move=Math.abs(net)/median;
    if(aligned<6||move<3)return {...base,reason:"Waiting for sustained tick momentum"};
    direction=net>0?"UP":"DOWN";
    score=Math.min(90,65+aligned*2+Math.min(9,Math.floor(move)));
    reason="Sustained tick momentum confirmed across the recent price stream";
  }
  const minScore=clamp(Number(options.minScore??72),60,95);
  const action=score>=minScore?direction:"WAIT";
  return {action,score,reason:action==="WAIT"?"Tick setup below quality threshold":reason,tickCount:rows.length,engine:"TICK_FAST",marketProfile:boom?"BOOM_SPIKE":crash?"CRASH_SPIKE":"MOMENTUM",note:"Tick score is setup quality, not a probability of profit."};
}


/**
 * DIABLO 90 precision gate.
 * Combines a live tick candidate with a fresh closed-candle context.
 * Scores are setup-alignment points, never win probabilities.
 */
export function confirmPrecisionEntry(tickSignal, candleSignal, symbol = "", options = {}) {
  const minimum = clamp(Number(options.minScore ?? 72), 60, 95);
  const base = {
    action: "WAIT",
    score: 0,
    engine: "DIABLO_90",
    reason: "Waiting for independent tick and candle confirmation",
    components: {
      tick: Number(tickSignal?.score) || 0,
      candle: Number(candleSignal?.score) || 0
    }
  };
  if (!tickSignal || !["UP", "DOWN"].includes(tickSignal.action)) {
    return { ...base, reason: tickSignal?.reason || base.reason };
  }
  if (!candleSignal || !Number.isFinite(Number(candleSignal.score))) {
    return { ...base, reason: "Fresh candle context is unavailable" };
  }

  const upper = String(symbol).toUpperCase();
  const spikeMarket = upper.startsWith("BOOM") || upper.startsWith("CRASH");
  if (candleSignal.volatility === "HIGH") {
    return { ...base, reason: "Candle volatility filter blocked entry", volatility: "HIGH" };
  }
  if (candleSignal.action !== "WAIT" && candleSignal.action !== tickSignal.action) {
    return { ...base, reason: "Tick and candle directions conflict" };
  }

  // Regular indices require directional candle agreement. BOOM/CRASH use
  // observed spike + follow-through as their trigger; candles act as a
  // volatility/conflict filter rather than forcing a trend-following entry.
  if (!spikeMarket && candleSignal.action !== tickSignal.action) {
    return { ...base, reason: "Waiting for candle trend/momentum agreement" };
  }
  if (spikeMarket && candleSignal.action === "WAIT" && candleSignal.regime === "RANGING") {
    return { ...base, reason: "Market structure is ranging after spike; stand aside" };
  }

  const candleWeight = spikeMarket ? 0.10 : 0.40;
  const tickWeight = 1 - candleWeight;
  const candlePoints = spikeMarket && candleSignal.action === "WAIT" ? 50 : candleSignal.score;
  const score = Math.round(tickSignal.score * tickWeight + candlePoints * candleWeight);
  const action = score >= minimum ? tickSignal.action : "WAIT";
  return {
    action,
    score,
    engine: "DIABLO_90",
    marketProfile: tickSignal.marketProfile || (spikeMarket ? (upper.startsWith("BOOM") ? "BOOM_SPIKE" : "CRASH_SPIKE") : "TREND_MOMENTUM"),
    reason: action === "WAIT" ? "Combined setup score is below the configured threshold" : "Tick trigger and fresh candle context confirmed",
    components: {
      tick: tickSignal.score,
      candle: candleSignal.score,
      tickWeight,
      candleWeight
    },
    regime: candleSignal.regime || "UNKNOWN",
    volatility: candleSignal.volatility || "UNKNOWN",
    note: "DIABLO 90 score measures setup alignment, not probability of profit."
  };
}
