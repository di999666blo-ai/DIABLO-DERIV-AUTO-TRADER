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
  for(let i=values.length-period;i<values.length;i++){
    const change=values[i]-values[i-1];
    if(change>0)gain+=change;else loss-=change;
  }
  if(loss===0)return gain===0?50:100;
  return 100-100/(1+gain/loss);
}
function atrPercent(rows,period=14){
  if(rows.length<period+1)return null;
  const ranges=[];
  for(let i=1;i<rows.length;i++){
    const c=rows[i],p=rows[i-1];
    ranges.push(Math.max(c.high-c.low,Math.abs(c.high-p.close),Math.abs(c.low-p.close)));
  }
  const atr=mean(ranges.slice(-period));
  return rows.at(-1).close===0?null:atr/Math.abs(rows.at(-1).close)*100;
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
  const atr=atrPercent(rows),er=efficiency(closes,Math.min(10,rows.length-1));
  if([fast,slow,momentum,atr].some(v=>v===null||!Number.isFinite(v)))return{...base,reason:"Indicator calculation unavailable"};
  // Avoid dead markets and extreme spikes; thresholds adapt to observed volatility.
  const volatility=atr<0.015?"LOW":atr>0.35?"HIGH":"NORMAL";
  const regime=er>0.55?"TRENDING":er<0.20?"RANGING":"MIXED";
  if(volatility==="HIGH")return{...base,reason:"Volatility spike: stand aside",regime,volatility,indicators:{fast,slow,rsi:momentum,atrPercent:atr,efficiency:er}};
  if(regime==="RANGING")return{...base,reason:"Ranging market: no directional edge",regime,volatility,indicators:{fast,slow,rsi:momentum,atrPercent:atr,efficiency:er}};
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
    indicators:{fast,slow,rsi:momentum,atrPercent:atr,efficiency:er,bodyRatio:body},
    note:"Score measures setup alignment; it is not a win-rate or profit probability."
  };
}
