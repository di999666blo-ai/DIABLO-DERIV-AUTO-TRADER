import http from "node:http";
import {readFile} from "node:fs/promises";
import {resolve,extname} from "node:path";
import {fileURLToPath} from "node:url";
import {config} from "./config.js";
import {RiskGuard} from "./risk.js";
import {analyzeCandles} from "./strategy.js";
import {DerivClient} from "./deriv.js";
import {createBatchPlan} from "./portfolio.js";
import {TradeLedger} from "./ledger.js";

const root=resolve(fileURLToPath(new URL("../public",import.meta.url)));
const guard=new RiskGuard({dailyLoss:config.maxDailyLoss,tradesPerDay:config.maxTradesPerDay,cooldownMs:config.cooldownSeconds*1000,maxStake:config.maxStake,baseStake:config.defaultStake});
const deriv=new DerivClient({token:config.apiToken,appId:config.appId});
const ledger=new TradeLedger();
let activeAccount=null;
const wait=ms=>new Promise(resolve=>setTimeout(resolve,ms));
const json=(res,status,data)=>{res.writeHead(status,{"content-type":"application/json; charset=utf-8","cache-control":"no-store"});res.end(JSON.stringify(data));};
const body=async req=>{let raw="";for await(const c of req){raw+=c;if(raw.length>1000000)throw Error("Request too large");}return JSON.parse(raw||"{}");};
const persistRisk=async()=>{await ledger.saveRisk(guard.snapshot());};
deriv.onContractUpdate=async contract=>{
  try {
    const result=await ledger.settle(contract);
    if(result.recorded){guard.recordClosedTrade(result.trade.profit,result.trade.symbol);await persistRisk();console.log("DIABLO settlement recorded",result.trade.contractId,result.trade.profit);}
  } catch(error){console.error("DIABLO settlement persistence failed:",error.message);guard.emergencyStop();}
};

const server=http.createServer(async(req,res)=>{try{
if(req.method==="GET"&&req.url==="/api/status")return json(res,200,{name:"DIABLO DERIV AUTO TRADER",engine:config.engine,mode:"demo-only",autoTradeConfigured:config.autoTrade,credentialsConfigured:deriv.credentialsReady(),connected:deriv.connected,account:activeAccount,risk:guard.snapshot(),capabilities:{marketData:true,analysis:true,proposal:deriv.connected,manualDemoBuy:deriv.connected,batchScanAndExecute:deriv.connected&&config.autoTrade,settlementStream:deriv.connected,realMoney:false,mt5:"desktop-terminal-only"}});
if(req.method==="GET"&&req.url==="/api/ledger")return json(res,200,{ledger:ledger.snapshot()});
if(req.method==="GET"&&req.url==="/api/accounts"){try{return json(res,200,{accounts:await deriv.accounts()});}catch(e){return json(res,400,{error:e.message});}}
if(req.method==="POST"&&req.url==="/api/connect"){
  const b=await body(req),r=await deriv.connect(String(b.accountId||""),"demo");activeAccount=r.accountId;
  for(const id of Object.keys(ledger.state.open)){try{await deriv.watchContract(id);}catch(e){console.error("Could not restore contract stream",id,e.message);}}
  return json(res,200,r);
}
if(req.method==="POST"&&req.url==="/api/disconnect"){deriv.close();activeAccount=null;return json(res,200,{connected:false});}
if(req.method==="GET"&&req.url==="/api/symbols"){const symbols=deriv.connected?await deriv.symbols():await deriv.publicSymbols();return json(res,200,{symbols});}
if(req.method==="POST"&&req.url==="/api/history"){const b=await body(req);return json(res,200,{candles:await deriv.history(String(b.symbol||""),b.count,b.granularity)});}
if(req.method==="POST"&&req.url==="/api/analyze"){const b=await body(req);return json(res,200,analyzeCandles(b.candles,b.options));}
if(req.method==="POST"&&req.url==="/api/batch-plan"){const b=await body(req);const plan=createBatchPlan(b.markets,b.tradesPerMarket);return json(res,200,{...plan,execution:"PREVIEW_ONLY",note:"Preview only. Use scan-and-execute with explicit demo confirmation to place demo orders."});}
if(req.method==="POST"&&req.url==="/api/proposal"){const b=await body(req);if(!deriv.connected||!activeAccount)return json(res,409,{error:"Connect a demo Options account first"});return json(res,200,{proposal:await deriv.proposal(b),approvalRequired:true});}
if(req.method==="POST"&&req.url==="/api/buy"){
  const b=await body(req);if(!deriv.connected||!activeAccount)return json(res,409,{error:"Connect a demo Options account first"});
  if(b.confirm!==true)return json(res,400,{error:"Explicit confirm=true is required"});
  const symbol=String(b.symbol||"UNKNOWN"),stake=Number(b.stake||guard.currentStake);
  const gate=guard.canTrade({stake,autoTrade:true,mode:"demo",symbol});
  if(!gate.ok)return json(res,403,{error:gate.reason});
  const result=await deriv.buy(String(b.proposalId||""),b.maxPrice);const contractId=result?.buy?.contract_id;
  if(!contractId)return json(res,502,{error:"Buy response did not contain a contract ID"});
  guard.recordTrade({symbol});await ledger.recordOpen({contract_id:contractId,symbol,stake,source:"manual-demo"});await persistRisk();
  await deriv.watchContract(contractId);
  return json(res,200,{bought:true,result,risk:guard.snapshot(),settlementMonitoring:true});
}
if(req.method==="POST"&&req.url==="/api/scan-and-execute"){
  const b=await body(req);
  if(!deriv.connected||!activeAccount)return json(res,409,{error:"Connect a demo Options account first"});
  if(b.confirm!==true)return json(res,400,{error:"Explicit confirm=true is required to execute demo batch"});
  if(!config.autoTrade)return json(res,403,{error:"AUTO_TRADE=true must be explicitly enabled in the local environment"});
  if(guard.stopped)return json(res,403,{error:"Arm the demo risk guard before running a batch"});
  const plan=createBatchPlan(b.markets,b.tradesPerMarket);
  const duration=Number(b.duration||1),durationUnit=String(b.durationUnit||"m");
  if(!Number.isInteger(duration)||duration<1||duration>60||!["s","m","h"].includes(durationUnit))return json(res,400,{error:"Invalid contract duration"});
  const active=await deriv.symbols(),available=new Set(active.map(x=>x.symbol));
  const signals=[];
  for(const symbol of plan.markets){
    if(!available.has(symbol)){signals.push({symbol,action:"WAIT",reason:"Market unavailable for this account"});continue;}
    try{
      const candles=await deriv.history(symbol,100,Number(b.granularity||60));
      const signal=analyzeCandles(candles);
      signals.push({symbol,...signal});
    }catch(error){signals.push({symbol,action:"WAIT",reason:"Market analysis failed: "+error.message});}
  }
  const approved=signals.filter(x=>x.action==="UP"||x.action==="DOWN");
  if(!approved.length)return json(res,200,{execution:"NO_ORDERS",signals,placed:[],note:"All selected markets returned WAIT or unavailable."});
  const orders=approved.flatMap(signal=>Array.from({length:plan.tradesPerMarket},(_,i)=>({...signal,sequence:i+1})));
  const placed=[],failed=[];
  for(const order of orders){
    const stake=guard.currentStake;
    const gate=guard.canTrade({stake,autoTrade:true,mode:"demo",symbol:order.symbol,ignoreCooldown:true});
    if(!gate.ok){failed.push({symbol:order.symbol,sequence:order.sequence,reason:gate.reason});break;}
    try{
      const proposal=await deriv.proposal({symbol:order.symbol,action:order.action,stake,duration,durationUnit,currency:String(b.currency||"USD")});
      const purchase=await deriv.buy(proposal.id,Number(proposal.ask_price));
      const contractId=purchase?.buy?.contract_id;
      if(!contractId)throw Error("Deriv buy response omitted contract ID");
      guard.recordTrade({symbol:order.symbol});
      await ledger.recordOpen({contract_id:contractId,symbol:order.symbol,stake,action:order.action,source:"confirmed-demo-batch"});
      await persistRisk();
      await deriv.watchContract(contractId);
      placed.push({symbol:order.symbol,action:order.action,stake,contractId,sequence:order.sequence});
      await wait(1200);
    }catch(error){failed.push({symbol:order.symbol,sequence:order.sequence,reason:error.message});break;}
  }
  if(failed.length)guard.emergencyStop();
  return json(res,200,{execution:"DEMO_ONLY",signals,planned:orders.length,placed,failed,remaining:orders.length-placed.length,risk:guard.snapshot(),note:"Orders are queued with pacing. Any failure halts the remaining batch. Settlement updates are monitored from Deriv."});
}
if(req.method==="POST"&&req.url==="/api/stop"){guard.emergencyStop();deriv.close();activeAccount=null;await persistRisk();return json(res,200,{stopped:true});}
if(req.method==="POST"&&req.url==="/api/arm-demo"){guard.arm();await persistRisk();return json(res,200,{armed:true,mode:"demo"});}
if(req.method==="GET"){const pathname=req.url.split("?")[0];const name=pathname==="/"? "index.html":pathname.substring(1);if(name.includes(".."))return json(res,400,{error:"Invalid path"});try{const data=await readFile(resolve(root,name));res.writeHead(200,{"content-type":extname(name)===".html"?"text/html; charset=utf-8":"text/plain; charset=utf-8","x-content-type-options":"nosniff"});return res.end(data);}catch{return json(res,404,{error:"Not found"});}}
return json(res,404,{error:"Not found"});
}catch(e){return json(res,400,{error:e?.message||"Unexpected error"});}});

await ledger.load();
if(ledger.state.risk)guard.restore(ledger.state.risk);
server.listen(config.port,"127.0.0.1",()=>console.log("DIABLO demo service listening on http://127.0.0.1:"+config.port));
