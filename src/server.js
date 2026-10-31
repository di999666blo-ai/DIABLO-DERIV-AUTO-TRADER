import http from "node:http";
import {readFile} from "node:fs/promises";
import {resolve,extname} from "node:path";
import {fileURLToPath} from "node:url";
import {config} from "./config.js";
import {RiskGuard} from "./risk.js";
import {analyzeCandles,analyzeTickMomentum} from "./strategy.js";
import {DerivClient} from "./deriv.js";
import {createBatchPlan} from "./portfolio.js";
import {TradeLedger} from "./ledger.js";

const root=resolve(fileURLToPath(new URL("../public",import.meta.url)));
const guard=new RiskGuard({dailyLoss:config.maxDailyLoss,tradesPerDay:config.maxTradesPerDay,cooldownMs:config.cooldownSeconds*1000,maxStake:config.maxStake,baseStake:config.defaultStake,stakeProgressionEnabled:config.stakeProgressionEnabled,stakeProgression:config.stakeProgression,maxOpenTrades:config.maxOpenTrades,maxTradesPerMarket:config.maxTradesPerMarket,allowLive:config.liveTradingAck});
const deriv=new DerivClient({token:config.apiToken,appId:config.appId});
const ledger=new TradeLedger();
let activeAccount=null;
let activeMode="demo";
const wait=ms=>new Promise(resolve=>setTimeout(resolve,ms));
let autoRunner=null;
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
if(req.method==="POST"&&req.url==="/api/automation/start"){
  const b=await body(req);
  if(b.confirm!==true)return json(res,400,{error:"confirm=true is required to start autonomous execution"});
  if(!config.autoTrade)return json(res,403,{error:"Set AUTO_TRADE=true in local .env"});
  if(config.mode==="real"&&!config.liveTradingAck)return json(res,403,{error:"Live trading acknowledgement missing"});
  const accountId=String(b.accountId||config.autoAccountId||"");
  const markets=Array.isArray(b.markets)?b.markets:config.autoMarkets;
  if(!accountId||!markets.length)return json(res,400,{error:"Set AUTO_ACCOUNT_ID and AUTO_MARKETS in .env or provide them in the start request"});
  if(!markets.every(x=>/^[A-Z0-9_]+$/i.test(x)))return json(res,400,{error:"Invalid market symbol"});
  if(autoRunner)return json(res,409,{error:"Autonomous scanner is already running"});
  const mode=config.mode;
  await deriv.connect(accountId,mode);activeAccount=accountId;activeMode=mode;for(const id of Object.keys(ledger.state.open)){try{await deriv.watchContract(id);}catch(e){console.error("Could not restore contract monitor",id,e.message);}}
  guard.arm();await persistRisk();
  let stopped=false;
  autoRunner={mode,accountId,markets:[...new Set(markets)],startedAt:new Date().toISOString(),lastProcessed:{},tickState:{},subscriptions:[],stop:()=>{stopped=true;for(const sub of autoRunner?.subscriptions||[])deriv.unsubscribeTicks(sub.id).catch(()=>{});}};
  const evaluateTick=async(symbol,tick)=>{
    const runner=autoRunner;if(stopped||!runner||guard.stopped||!deriv.connected)return;
    const state=runner.tickState[symbol];if(!state)return;
    const quote=Number(tick.quote),epoch=Number(tick.epoch||0);if(!(quote>0))return;
    if(state.ticks.length&&epoch&&epoch===state.lastEpoch)return;
    state.lastEpoch=epoch;state.ticks.push({quote,epoch});if(state.ticks.length>40)state.ticks.shift();
    if(state.evaluating||state.ticks.length<14)return;
    const signal=analyzeTickMomentum(state.ticks,symbol,{minScore:config.minSignalScore});
    state.lastSignal=signal;
    if(!["UP","DOWN"].includes(signal.action)||signal.score<config.minSignalScore)return;
    const now=Date.now();if(now-state.lastEntryAt<Math.max(5000,config.cooldownSeconds*1000))return;
    state.evaluating=true;
    try{
      const stake=guard.currentStake,gate=guard.canTrade({stake,autoTrade:true,mode,symbol});
      if(!gate.ok)return;
      const proposal=await deriv.proposal({symbol,action:signal.action,stake,duration:config.autoDuration,durationUnit:config.autoDurationUnit,currency:config.autoCurrency});
      if(stopped||guard.stopped||!deriv.connected)return;
      const purchase=await deriv.buy(proposal.id,Number(proposal.ask_price));
      const contractId=purchase?.buy?.contract_id;if(!contractId)throw Error("Buy response omitted contract ID");
      guard.recordTrade({symbol});state.lastEntryAt=Date.now();
      await ledger.recordOpen({contract_id:contractId,symbol,stake,action:signal.action,score:signal.score,source:"tick-autonomous",engine:signal.engine,marketProfile:signal.marketProfile});
      await persistRisk();await deriv.watchContract(contractId);
      console.log("DIABLO tick-scalp order",mode,symbol,signal.action,signal.score,signal.marketProfile,contractId);
      state.ticks=[]; // require a fresh sequence before another entry
    }catch(error){console.error("Tick entry failed",symbol,error.message);}
    finally{state.evaluating=false;}
  };
  try{
    const active=await deriv.symbols(),available=new Set(active.map(x=>x.symbol));
    for(const symbol of autoRunner.markets){
      if(stopped)break;
      if(!available.has(symbol)){console.warn("Selected market unavailable:",symbol);continue;}
      // Prime a market baseline before accepting tick entries.
      const history=await deriv.history(symbol,100,config.autoGranularity);
      const closed=history.length>1?history.slice(0,-1):[];
      const candleContext=analyzeCandles(closed,{minScore:config.minSignalScore});
      autoRunner.tickState[symbol]={ticks:[],lastEpoch:0,lastEntryAt:0,evaluating:false,lastSignal:null,candleContext};
      const sub=await deriv.subscribeTicks(symbol,tick=>{evaluateTick(symbol,tick).catch(e=>console.error("Tick callback error:",e.message));});
      autoRunner.subscriptions.push(sub);
      console.log("DIABLO tick feed subscribed",symbol,"baseline",candleContext.action);
    }
    if(!autoRunner.subscriptions.length)throw Error("No selected markets could be subscribed; autonomous scanner was not started");
  }catch(error){
    stopped=true;for(const sub of autoRunner.subscriptions)await deriv.unsubscribeTicks(sub.id).catch(()=>{});
    autoRunner=null;guard.emergencyStop();await persistRisk();
    return json(res,502,{error:"Could not initialize continuous tick feeds: "+error.message});
  }
  return json(res,200,{started:true,mode,accountId,markets:autoRunner.markets,engine:"continuous-tick",subscriptions:autoRunner.subscriptions.length,warning:mode==="real"?"REAL MONEY: losses are possible.": "Demo account."});
}
if(req.method==="POST"&&req.url==="/api/automation/stop"){
  if(autoRunner)autoRunner.stop();autoRunner=null;guard.emergencyStop();await persistRisk();
  return json(res,200,{stopped:true,note:"New autonomous entries stopped. Existing positions remain open and settlement monitoring continues while connected."});
}
if(req.method==="GET"&&req.url==="/api/automation/status")return json(res,200,{running:Boolean(autoRunner),runner:autoRunner?{mode:autoRunner.mode,accountId:autoRunner.accountId,markets:autoRunner.markets,startedAt:autoRunner.startedAt,engine:"continuous-tick",subscriptions:autoRunner.subscriptions.length,tickState:Object.fromEntries(Object.entries(autoRunner.tickState).map(([k,v])=>[k,{ticks:v.ticks.length,lastSignal:v.lastSignal}]))}:null,connected:deriv.connected,risk:guard.snapshot()});
if(req.method==="GET"&&req.url==="/api/status")return json(res,200,{name:"DIABLO DERIV AUTO TRADER",engine:config.engine,mode:config.mode,autoTradeConfigured:config.autoTrade,credentialsConfigured:deriv.credentialsReady(),connected:deriv.connected,account:activeAccount,risk:guard.snapshot(),capabilities:{marketData:true,analysis:true,proposal:deriv.connected,manualDemoBuy:deriv.connected,batchScanAndExecute:deriv.connected&&config.autoTrade,settlementStream:deriv.connected,realMoney:config.liveTradingAck,mt5:"desktop-terminal-only"}});
if(req.method==="GET"&&req.url==="/api/ledger")return json(res,200,{ledger:ledger.snapshot()});
if(req.method==="GET"&&req.url==="/api/accounts"){try{return json(res,200,{accounts:await deriv.accounts()});}catch(e){return json(res,400,{error:e.message});}}
if(req.method==="POST"&&req.url==="/api/connect"){
  const b=await body(req),selectedMode=b.mode==="real"?"real":"demo";if(selectedMode==="real"&&!config.liveTradingAck)return json(res,403,{error:"Live trading is locked. Configure LIVE_TRADING_ACK locally after reviewing all risks."});const r=await deriv.connect(String(b.accountId||""),selectedMode);activeAccount=r.accountId;activeMode=selectedMode;
  for(const id of Object.keys(ledger.state.open)){try{await deriv.watchContract(id);}catch(e){console.error("Could not restore contract stream",id,e.message);}}
  return json(res,200,r);
}
if(req.method==="POST"&&req.url==="/api/disconnect"){deriv.close();activeAccount=null;activeMode="demo";return json(res,200,{connected:false});}
if(req.method==="GET"&&req.url==="/api/symbols"){const symbols=deriv.connected?await deriv.symbols():await deriv.publicSymbols();return json(res,200,{symbols});}
if(req.method==="POST"&&req.url==="/api/history"){const b=await body(req);return json(res,200,{candles:await deriv.history(String(b.symbol||""),b.count,b.granularity)});}
if(req.method==="POST"&&req.url==="/api/analyze"){const b=await body(req);return json(res,200,analyzeCandles(b.candles,b.options));}
if(req.method==="POST"&&req.url==="/api/batch-plan"){const b=await body(req);const plan=createBatchPlan(b.markets,b.tradesPerMarket);return json(res,200,{...plan,execution:"PREVIEW_ONLY",note:"Preview only. Use scan-and-execute with explicit demo confirmation to place demo orders."});}
if(req.method==="POST"&&req.url==="/api/proposal"){const b=await body(req);if(!deriv.connected||!activeAccount)return json(res,409,{error:"Connect a demo Options account first"});return json(res,200,{proposal:await deriv.proposal(b),approvalRequired:true});}
if(req.method==="POST"&&req.url==="/api/buy"){
  const b=await body(req);if(!deriv.connected||!activeAccount)return json(res,409,{error:"Connect a demo Options account first"});
  if(b.confirm!==true)return json(res,400,{error:"Explicit confirm=true is required"});
  const symbol=String(b.symbol||"UNKNOWN"),stake=Number(b.stake||guard.currentStake);
  const gate=guard.canTrade({stake,autoTrade:true,mode:activeMode,symbol});
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
  if(!config.autoTrade)return json(res,403,{error:"AUTO_TRADE=true must be explicitly enabled in the local environment"});if(config.mode==="real"&&!config.liveTradingAck)return json(res,403,{error:"Live trading acknowledgement missing from local environment"});
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
      // Deriv includes the still-forming final candle. Analyze closed candles
      // only, matching the autonomous scanner and avoiding intrabar signals.
      const closedCandles=candles.length>1?candles.slice(0,-1):[];
      const signal=analyzeCandles(closedCandles,{minScore:config.minSignalScore});
      signals.push({symbol,...signal});
    }catch(error){signals.push({symbol,action:"WAIT",reason:"Market analysis failed: "+error.message});}
  }
  const approved=signals.filter(x=>x.action==="UP"||x.action==="DOWN");
  if(!approved.length)return json(res,200,{execution:"NO_ORDERS",signals,placed:[],note:"All selected markets returned WAIT or unavailable."});
  const orders=approved.flatMap(signal=>Array.from({length:plan.tradesPerMarket},(_,i)=>({...signal,sequence:i+1})));
  const placed=[],failed=[];
  for(const order of orders){
    const stake=guard.currentStake;
    const gate=guard.canTrade({stake,autoTrade:true,mode:activeMode,symbol:order.symbol,ignoreCooldown:true});
    if(!gate.ok){failed.push({symbol:order.symbol,sequence:order.sequence,reason:gate.reason});break;}
    try{
      const proposal=await deriv.proposal({symbol:order.symbol,action:order.action,stake,duration,durationUnit,currency:String(b.currency||config.autoCurrency)});
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
  if(failed.length){guard.emergencyStop();await persistRisk();}
  return json(res,200,{execution:config.mode.toUpperCase()+"_ACCOUNT",signals,planned:orders.length,placed,failed,remaining:orders.length-placed.length,risk:guard.snapshot(),note:"Orders are queued with pacing. Any failure halts the remaining batch. Settlement updates are monitored from Deriv."});
}
if(req.method==="POST"&&req.url==="/api/stop"){guard.emergencyStop();await persistRisk();return json(res,200,{stopped:true,connected:deriv.connected,note:"New orders stopped; existing contract settlement monitoring remains connected."});}
if(req.method==="POST"&&req.url==="/api/arm-demo"){guard.arm();await persistRisk();return json(res,200,{armed:true,mode:"demo"});}
if(req.method==="GET"){const pathname=req.url.split("?")[0];const name=pathname==="/"? "index.html":pathname.substring(1);if(name.includes(".."))return json(res,400,{error:"Invalid path"});try{const data=await readFile(resolve(root,name));res.writeHead(200,{"content-type":extname(name)===".html"?"text/html; charset=utf-8":"text/plain; charset=utf-8","x-content-type-options":"nosniff"});return res.end(data);}catch{return json(res,404,{error:"Not found"});}}
return json(res,404,{error:"Not found"});
}catch(e){return json(res,400,{error:e?.message||"Unexpected error"});}});

const recoveredState=await ledger.load();
if(recoveredState.risk){
  // The durable ledger is the source of truth for unsettled exposure. Rebuild
  // the risk counters from its open-contract map to repair a crash between
  // settlement persistence and the risk snapshot write.
  const openByMarket={};
  for(const contract of Object.values(recoveredState.open||{})){
    const symbol=String(contract.symbol||"UNKNOWN");
    openByMarket[symbol]=(openByMarket[symbol]||0)+1;
  }
  guard.restore({
    ...recoveredState.risk,
    openTrades:Object.keys(recoveredState.open||{}).length,
    openByMarket
  });
}
server.listen(config.port,"127.0.0.1",()=>{console.log("DIABLO service listening on http://127.0.0.1:"+config.port);if(config.autoStart&&config.autoTrade&&config.autoAccountId&&config.autoMarkets.length){setTimeout(()=>fetch("http://127.0.0.1:"+config.port+"/api/automation/start",{method:"POST",headers:{"content-type":"application/json"},body:JSON.stringify({confirm:true})}).then(r=>r.json()).then(x=>console.log("DIABLO AUTO_START:",x)).catch(e=>console.error("DIABLO AUTO_START failed:",e.message)),2000).unref?.();}});
