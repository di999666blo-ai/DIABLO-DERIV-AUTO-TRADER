import test from "node:test";
import assert from "node:assert/strict";
import { RiskGuard } from "../src/risk.js";
test("starts stopped and rejects real mode",()=>{const g=new RiskGuard();g.arm();assert.equal(g.canTrade({stake:1,autoTrade:true,mode:"real"}).ok,false);});
test("stake cap and emergency stop work",()=>{const g=new RiskGuard({maxStake:2});g.arm();assert.equal(g.canTrade({stake:3,autoTrade:true,mode:"demo"}).ok,false);g.emergencyStop();assert.equal(g.canTrade({stake:1,autoTrade:true,mode:"demo"}).ok,false);});
test("daily loss limit stops session",()=>{const g=new RiskGuard({dailyLoss:5});g.arm();g.recordClosedTrade(-5);assert.equal(g.stopped,true);});
test("win progression increases by one and caps at five",()=>{const g=new RiskGuard({baseStake:1,maxStake:5});for(let i=0;i<8;i++)g.recordClosedTrade(1);assert.equal(g.currentStake,5);});
test("loss holds current stake",()=>{const g=new RiskGuard({baseStake:1,maxStake:5});g.recordClosedTrade(1);g.recordClosedTrade(1);assert.equal(g.currentStake,3);g.recordClosedTrade(-1);assert.equal(g.currentStake,3);});
test("account-wide and per-market open trade caps are enforced",()=>{const g=new RiskGuard({maxOpenTrades:2,maxTradesPerMarket:1,cooldownMs:0});g.arm();g.recordTrade({symbol:"frxEURUSD"});assert.equal(g.canTrade({autoTrade:true,mode:"demo",symbol:"frxEURUSD"}).ok,false);g.recordTrade({symbol:"frxGBPUSD"});assert.equal(g.canTrade({autoTrade:true,mode:"demo",symbol:"frxAUDUSD"}).reason,"Account-wide open-trade limit reached");});

test("risk state restores daily loss, open counts and stake progression",()=>{const g=new RiskGuard({dailyLoss:5,cooldownMs:120000});g.restore({day:g.day,stopped:false,trades:4,openTrades:2,dailyLoss:3,consecutiveLosses:1,lastTradeAt:Date.now(),currentStake:3,openByMarket:{frxEURUSD:2}});assert.equal(g.loss,3);assert.equal(g.openTrades,2);assert.equal(g.currentStake,3);assert.equal(g.canTrade({autoTrade:true,mode:"demo",symbol:"frxEURUSD"}).reason,"Cooldown active");assert.equal(g.canTrade({autoTrade:true,mode:"demo",symbol:"frxEURUSD",ignoreCooldown:true}).ok,true);});
