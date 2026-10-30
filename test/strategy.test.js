import test from "node:test";
import assert from "node:assert/strict";
import { analyzeCandles } from "../src/strategy.js";
test("returns WAIT for insufficient candle history", () => {
  assert.equal(analyzeCandles([{close:1},{close:2}]).action, "WAIT");
});
test("does not report a win probability", () => {
  const candles = Array.from({length:50}, (_,i)=>({close:100+i*0.2}));
  const result = analyzeCandles(candles);
  assert.ok(["UP","DOWN","WAIT"].includes(result.action));
  assert.equal("winProbability" in result, false);
});
