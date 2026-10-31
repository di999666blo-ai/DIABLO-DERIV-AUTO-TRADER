import test from "node:test";
import assert from "node:assert/strict";
import { RiskGuard } from "../src/risk.js";

test("starts stopped and rejects real mode",()=>{const g=new RiskGuard();g.arm();assert.equal(g.canTrade({stake:1,autoTrade:true,mode:"real"}).ok,false);});
test("stake cap and emergency stop work",()=>{const g=new RiskGuard({maxStake:2});g.arm();assert.equal(g.canTrade({stake:3,autoTrade:true,mode:"demo"}).ok,false);g.emergencyStop();assert.equal(g.canTrade({stake:1,autoTrade:true,mode:"demo"}).ok,false);});
test("daily loss limit stops session",()=>{const g=new RiskGuard({dailyLoss:5});g.arm();g.recordClosedTrade(-5);assert.equal(g.stopped,true);});
test("stake remains fixed after wins and losses",()=>{const g=new RiskGuard({baseStake:1,maxStake:5});for(let i=0;i<8;i++)g.recordClosedTrade(1);g.recordClosedTrade(-1);assert.equal(g.currentStake,1);});
test("conservative default allows only one open trade",()=>{const g=new RiskGuard({cooldownMs:0});g.arm();g.recordTrade({symbol:"frxEURUSD"});assert.equal(g.canTrade({autoTrade:true,mode:"demo",symbol:"frxGBPUSD"}).reason,"Account-wide open-trade limit reached");});
test("account-wide and per-market open trade caps are enforced",()=>{const g=new RiskGuard({maxOpenTrades:2,maxTradesPerMarket:1,cooldownMs:0});g.arm();g.recordTrade({symbol:"frxEURUSD"});assert.equal(g.canTrade({autoTrade:true,mode:"demo",symbol:"frxEURUSD"}).ok,false);g.recordTrade({symbol:"frxGBPUSD"});assert.equal(g.canTrade({autoTrade:true,mode:"demo",symbol:"frxAUDUSD"}).reason,"Account-wide open-trade limit reached");});
test("restored stake progression is discarded while risk state is restored",()=>{const g=new RiskGuard({baseStake:1,maxStake:5,dailyLoss:5,cooldownMs:120000,maxOpenTrades:3,maxTradesPerMarket:3});g.restore({day:g.day,stopped:false,trades:4,openTrades:2,dailyLoss:3,consecutiveLosses:1,lastTradeAt:Date.now(),currentStake:3,openByMarket:{frxEURUSD:2}});assert.equal(g.loss,3);assert.equal(g.openTrades,2);assert.equal(g.currentStake,1);assert.equal(g.canTrade({autoTrade:true,mode:"demo",symbol:"frxEURUSD"}).reason,"Cooldown active");assert.equal(g.canTrade({autoTrade:true,mode:"demo",symbol:"frxEURUSD",ignoreCooldown:true}).ok,true);});

test("win/loss stake progression steps up and down without exceeding configured ladder",()=>{
  const g=new RiskGuard({baseStake:1,maxStake:5,stakeProgressionEnabled:true,stakeProgression:[1,2,3,4,5],cooldownMs:0});
  assert.equal(g.currentStake,1);
  g.recordClosedTrade(1); assert.equal(g.currentStake,2);
  g.recordClosedTrade(1); assert.equal(g.currentStake,3);
  g.recordClosedTrade(-1); assert.equal(g.currentStake,2);
  g.recordClosedTrade(-1); assert.equal(g.currentStake,1);
  g.recordClosedTrade(-1); assert.equal(g.currentStake,1);
  for(let i=0;i<10;i++)g.recordClosedTrade(1);
  assert.equal(g.currentStake,5);
});
test("stake progression state is restored from persisted risk snapshot",()=>{
  const limits={baseStake:1,maxStake:5,stakeProgressionEnabled:true,stakeProgression:[1,2,3,4,5]};
  const first=new RiskGuard(limits);first.recordClosedTrade(1);first.recordClosedTrade(1);
  const restored=new RiskGuard(limits);restored.restore(first.snapshot());
  assert.equal(restored.currentStake,3);
  assert.equal(restored.snapshot().progressionIndex,2);
});


test("restored progression derives stake from the clamped ladder index",()=>{
  const limits={baseStake:1,maxStake:3,stakeProgressionEnabled:true,stakeProgression:[1,2,3,4,5]};
  const source=new RiskGuard(limits);
  source.restore({day:source.day,stopped:false,progressionIndex:4,currentStake:999});
  assert.equal(source.progressionIndex,2);
  assert.equal(source.currentStake,3);
});

test("invalid stake caps and base stakes are rejected",()=>{
  assert.throws(()=>new RiskGuard({maxStake:0}),/maxStake/);
  assert.throws(()=>new RiskGuard({baseStake:0}),/baseStake/);
});

test("disabled progression always restores the configured base stake",()=>{
  const g=new RiskGuard({baseStake:2,maxStake:5,stakeProgressionEnabled:false});
  g.restore({day:g.day,stopped:false,progressionIndex:3,currentStake:5});
  assert.equal(g.progressionIndex,0);
  assert.equal(g.currentStake,2);
});
