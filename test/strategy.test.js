import test from "node:test";
import assert from "node:assert/strict";
import { analyzeCandles } from "../src/strategy.js";

function candles(n=50,step=0.02){
  const rows=[];let price=100;
  for(let i=0;i<n;i++){
    const open=price;
    price+=step+(i%4===0?-step*0.25:step*0.15);
    rows.push({epoch:i,open,high:Math.max(open,price)+0.01,low:Math.min(open,price)-0.01,close:price});
  }
  return rows;
}

test("requires enough closed candles before analysis",()=>{
  const result=analyzeCandles(candles(10));
  assert.equal(result.action,"WAIT");
  assert.match(result.reason,/Insufficient/);
});

test("returns bounded setup score and diagnostic indicators",()=>{
  const result=analyzeCandles(candles());
  assert.ok(["UP","DOWN","WAIT"].includes(result.action));
  assert.equal(typeof result.score,"number");
  assert.ok(result.score>=0&&result.score<=100);
  if(result.indicators)assert.ok(Number.isFinite(result.indicators.atrPercent));
  assert.equal("winProbability" in result,false);
});

test("invalid candle records are ignored safely",()=>{
  const rows=candles();
  rows.push({open:1,high:0,low:2,close:1});
  rows.push({open:"bad",high:2,low:0,close:1});
  const result=analyzeCandles(rows);
  assert.equal(result.candleCount,50);
});

test("user-configurable warmup and threshold are respected",()=>{
  const rows=candles(50);
  assert.equal(analyzeCandles(rows,{minCandles:60}).action,"WAIT");
  const result=analyzeCandles(rows,{minCandles:35,minScore:95});
  assert.ok(["UP","DOWN","WAIT"].includes(result.action));
});


import { analyzeTickMomentum } from "../src/strategy.js";

test("fast tick engine confirms sustained momentum without waiting for candle close",()=>{
  const ticks=Array.from({length:18},(_,i)=>({quote:100+i*0.12,epoch:i+1}));
  const result=analyzeTickMomentum(ticks,"R_100");
  assert.equal(result.action,"UP");
  assert.equal(result.engine,"TICK_FAST");
});

test("BOOM profile waits for an observed upward spike and follow-through",()=>{
  const quotes=[100,100.01,99.99,100.02,100.01,100.03,100.02,100.01,100.02,100.03,100.50,100.55,100.60,100.66,100.70];
  const result=analyzeTickMomentum(quotes.map((quote,epoch)=>({quote,epoch} )),"BOOM1000");
  assert.equal(result.action,"UP");
  assert.equal(result.marketProfile,"BOOM_SPIKE");
});

test("CRASH profile waits for an observed downward spike and follow-through",()=>{
  const quotes=[100,100.01,99.99,100.02,100.01,100.03,100.02,100.01,100.02,100.03,99.50,99.45,99.40,99.34,99.30];
  const result=analyzeTickMomentum(quotes.map((quote,epoch)=>({quote,epoch})),"CRASH1000");
  assert.equal(result.action,"DOWN");
  assert.equal(result.marketProfile,"CRASH_SPIKE");
});

test("BOOM/CRASH do not predict spikes when no characteristic impulse occurred",()=>{
  const ticks=Array.from({length:20},(_,i)=>({quote:100+i*0.01,epoch:i}));
  assert.equal(analyzeTickMomentum(ticks,"BOOM1000").action,"WAIT");
  assert.equal(analyzeTickMomentum(ticks,"CRASH1000").action,"WAIT");
});


import { confirmPrecisionEntry } from "../src/strategy.js";

test("DIABLO 90 requires candle direction agreement on regular indices",()=>{
  const tick={action:"UP",score:84,engine:"TICK_FAST",marketProfile:"MOMENTUM"};
  const aligned={action:"UP",score:80,regime:"TRENDING",volatility:"NORMAL"};
  const conflict={action:"DOWN",score:90,regime:"TRENDING",volatility:"NORMAL"};
  assert.equal(confirmPrecisionEntry(tick,aligned,"R_100").action,"UP");
  assert.equal(confirmPrecisionEntry(tick,conflict,"R_100").action,"WAIT");
});

test("DIABLO 90 accepts observed BOOM spike with non-conflicting candle context",()=>{
  const tick={action:"UP",score:76,engine:"TICK_FAST",marketProfile:"BOOM_SPIKE"};
  const context={action:"WAIT",score:0,regime:"MIXED",volatility:"NORMAL"};
  const result=confirmPrecisionEntry(tick,context,"BOOM1000",{minScore:70});
  assert.equal(result.action,"UP");
  assert.equal(result.engine,"DIABLO_90");
  assert.equal(result.score,73);
});

test("DIABLO 90 blocks extreme volatility and directional conflicts",()=>{
  const tick={action:"DOWN",score:88};
  assert.equal(confirmPrecisionEntry(tick,{action:"DOWN",score:90,volatility:"HIGH"},"R_100").action,"WAIT");
  assert.equal(confirmPrecisionEntry(tick,{action:"UP",score:90,volatility:"NORMAL"},"CRASH1000").action,"WAIT");
});


test("BOOM spike retracement after follow-through is rejected",()=>{
  const quotes=[100,100.01,100.00,100.02,100.01,100.03,100.02,100.04,100.03,100.05,100.04,100.06,100.56,100.61,100.66,100.36];
  const result=analyzeTickMomentum(quotes.map((quote,epoch)=>({quote,epoch})),"BOOM1000");
  assert.equal(result.action,"WAIT");
  assert.match(result.reason,/follow-through|stand aside/i);
});

test("closed-candle volatility filter adapts to a sudden range expansion",()=>{
  const rows=candles(50);
  for(let i=36;i<50;i++){
    const close=rows[i].close,open=rows[i].open;
    rows[i]={...rows[i],high:Math.max(open,close)+1.5,low:Math.min(open,close)-1.5};
  }
  const result=analyzeCandles(rows);
  assert.equal(result.action,"WAIT");
  assert.equal(result.volatility,"HIGH");
  assert.match(result.reason,/Volatility spike/);
});


test("BOOM spike is blocked until a full closed-candle context exists",()=>{
  const tick={action:"UP",score:76,marketProfile:"BOOM_SPIKE"};
  const warmup={action:"WAIT",score:0,reason:"Insufficient closed candles",candleCount:20};
  assert.equal(confirmPrecisionEntry(tick,warmup,"BOOM1000").action,"WAIT");
  assert.match(confirmPrecisionEntry(tick,warmup,"BOOM1000").reason,/Insufficient closed-candle context/);
});

test("BOOM spike retracement after the impulse is measured from the post-spike quote",()=>{
  const quotes=[100,100.01,100.00,100.02,100.01,100.03,100.02,100.04,100.03,100.05,100.04,100.06,100.56,100.61,100.66,100.36];
  const result=analyzeTickMomentum(quotes.map((quote,epoch)=>({quote,epoch})),"BOOM1000");
  assert.equal(result.action,"WAIT");
  assert.match(result.reason,/follow-through|stand aside/i);
});
