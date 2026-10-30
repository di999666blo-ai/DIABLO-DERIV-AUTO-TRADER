import test from "node:test";
import assert from "node:assert/strict";
import { createBatchPlan } from "../src/portfolio.js";

test("plans five demo orders for one selected market", () => {
  const p=createBatchPlan(["frxEURUSD"],5);
  assert.equal(p.totalTrades,5);
  assert.equal(p.orders.length,5);
  assert.ok(p.orders.every(x=>x.symbol==="frxEURUSD"));
});
test("supports up to twenty markets and caps the whole batch at 100", () => {
  assert.equal(createBatchPlan(Array.from({length:20},(_,i)=>"R_"+i),5).totalTrades,100);
  assert.throws(()=>createBatchPlan(Array.from({length:20},(_,i)=>"R_"+i),6),/account-wide/);
});
test("rejects duplicate markets and invalid counts", () => {
  assert.throws(()=>createBatchPlan(["R_10","R_10"],1),/unique/);
  assert.throws(()=>createBatchPlan(["R_10"],0),/integer/);
  assert.throws(()=>createBatchPlan(Array.from({length:21},(_,i)=>"R_"+i),1),/1 and 20/);
});
