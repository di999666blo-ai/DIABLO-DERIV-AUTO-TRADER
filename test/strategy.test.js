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
