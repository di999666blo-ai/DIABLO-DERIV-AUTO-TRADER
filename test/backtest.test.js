import test from "node:test";
import assert from "node:assert/strict";
import { walkForwardBacktest } from "../src/backtest.js";

function trendCandles(count=180) {
  const rows=[];
  let price=100;
  for(let i=0;i<count;i++){
    const open=price;
    price += i%37<24 ? 0.08 : -0.025;
    const close=price;
    rows.push({epoch:i*60,open,high:Math.max(open,close)+0.025,low:Math.min(open,close)-0.025,close});
  }
  return rows;
}

test("walk-forward backtest returns explicit sample and payout metrics",()=>{
  const report=walkForwardBacktest(trendCandles(),{payoutRate:0.85,expiryBars:1,minScore:72});
  assert.equal(report.method,"WALK_FORWARD_NO_LOOKAHEAD");
  assert.equal(report.candles,180);
  assert.equal(report.expiryBars,1);
  assert.equal(report.payoutRate,0.85);
  assert.equal(report.totalTrades,report.wins+report.losses+report.pushes);
  assert.ok(report.winRate===null||(report.winRate>=0&&report.winRate<=100));
  assert.ok(Number.isFinite(report.netR));
});

test("walk-forward backtest rejects impossible configuration values",()=>{
  assert.throws(()=>walkForwardBacktest(trendCandles(),{payoutRate:1}),/payoutRate/);
  assert.throws(()=>walkForwardBacktest(trendCandles(),{expiryBars:0}),/expiryBars/);
  assert.throws(()=>walkForwardBacktest(trendCandles(),{minCandles:10}),/minCandles/);
});

test("walk-forward backtest sorts candle data chronologically",()=>{
  const rows=trendCandles();
  const sorted=walkForwardBacktest(rows,{minScore:95});
  const shuffled=walkForwardBacktest([...rows].reverse(),{minScore:95});
  assert.equal(shuffled.candles,sorted.candles);
  assert.equal(shuffled.totalTrades,sorted.totalTrades);
  assert.equal(shuffled.netR,sorted.netR);
});
