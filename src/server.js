import http from "node:http";
import {readFile} from "node:fs/promises";
import {resolve,extname} from "node:path";
import {fileURLToPath} from "node:url";
import {config} from "./config.js";
import {RiskGuard} from "./risk.js";
import {analyzeCandles} from "./strategy.js";
import {DerivClient} from "./deriv.js";
import {createBatchPlan} from "./portfolio.js";
const root=resolve(fileURLToPath(new URL("../public",import.meta.url)));
const guard=new RiskGuard({dailyLoss:config.maxDailyLoss,tradesPerDay:config.maxTradesPerDay,consecutiveLosses:config.maxConsecutiveLosses,cooldownMs:config.cooldownSeconds*1000,maxStake:config.maxStake});
const deriv=new DerivClient({token:config.apiToken,appId:config.appId});let activeAccount=null;
const json=(res,status,data)=>{res.writeHead(status,{"content-type":"application/json; charset=utf-8","cache-control":"no-store"});res.end(JSON.stringify(data));};
const body=async req=>{let raw="";for await(const c of req){raw+=c;if(raw.length>1000000)throw Error("Request too large");}return JSON.parse(raw||"{}");};
const server=http.createServer(async(req,res)=>{try{
if(req.method==="GET"&&req.url==="/api/status")return json(res,200,{name:"DIABLO DERIV AUTO TRADER",engine:config.engine,mode:"demo-only",autoTradeConfigured:config.autoTrade,credentialsConfigured:deriv.credentialsReady(),connected:deriv.connected,account:activeAccount,risk:guard.snapshot(),capabilities:{marketData:true,analysis:true,proposal:deriv.connected,orderExecution:deriv.connected,realMoney:false,mt5:"desktop-terminal-only"}});
if(req.method==="GET"&&req.url==="/api/accounts"){try{return json(res,200,{accounts:await deriv.accounts()});}catch(e){return json(res,400,{error:e.message});}}
if(req.method==="POST"&&req.url==="/api/connect"){const b=await body(req),r=await deriv.connect(String(b.accountId||""),"demo");activeAccount=r.accountId;return json(res,200,r);}
if(req.method==="POST"&&req.url==="/api/disconnect"){deriv.close();activeAccount=null;return json(res,200,{connected:false});}
if(req.method==="GET"&&req.url==="/api/symbols"){const symbols=deriv.connected?await deriv.symbols():await deriv.publicSymbols();return json(res,200,{symbols});}
if(req.method==="POST"&&req.url==="/api/history"){const b=await body(req);return json(res,200,{candles:await deriv.history(String(b.symbol||""),b.count,b.granularity)});}
if(req.method==="POST"&&req.url==="/api/analyze"){const b=await body(req);return json(res,200,analyzeCandles(b.candles,b.options));}
if(req.method==="POST"&&req.url==="/api/batch-plan"){const b=await body(req);const plan=createBatchPlan(b.markets,b.tradesPerMarket);return json(res,200,{...plan,execution:"NOT ENABLED",note:"Preview only. No orders are placed by this endpoint."});}
if(req.method==="POST"&&req.url==="/api/proposal"){const b=await body(req);if(!deriv.connected||!activeAccount)return json(res,409,{error:"Connect a demo Options account first"});return json(res,200,{proposal:await deriv.proposal(b),approvalRequired:true});}
if(req.method==="POST"&&req.url==="/api/buy"){const b=await body(req);if(!deriv.connected||!activeAccount)return json(res,409,{error:"Connect a demo Options account first"});if(b.confirm!==true)return json(res,400,{error:"Explicit confirm=true is required"});const gate=guard.canTrade({stake:b.stake,autoTrade:true,mode:"demo"});if(!gate.ok)return json(res,403,{error:gate.reason});const result=await deriv.buy(String(b.proposalId||""),b.maxPrice);guard.recordTrade();return json(res,200,{bought:true,result,risk:guard.snapshot()});}
if(req.method==="POST"&&req.url==="/api/stop"){guard.emergencyStop();deriv.close();activeAccount=null;return json(res,200,{stopped:true});}
if(req.method==="POST"&&req.url==="/api/arm-demo"){guard.arm();return json(res,200,{armed:true,mode:"demo"});}
if(req.method==="GET"){const pathname=req.url.split("?")[0];const name=pathname==="/"?"index.html":pathname.substring(1);if(name.includes(".."))return json(res,400,{error:"Invalid path"});try{const data=await readFile(resolve(root,name));res.writeHead(200,{"content-type":extname(name)===".html"?"text/html; charset=utf-8":"text/plain; charset=utf-8","x-content-type-options":"nosniff"});return res.end(data);}catch{return json(res,404,{error:"Not found"});}}
return json(res,404,{error:"Not found"});
}catch(e){return json(res,400,{error:e?.message||"Unexpected error"});}});
server.listen(config.port,"127.0.0.1",()=>console.log("DIABLO demo service listening on http://127.0.0.1:"+config.port));
