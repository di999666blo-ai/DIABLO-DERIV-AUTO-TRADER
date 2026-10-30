export class RiskGuard {
  constructor(limits = {}) {
    this.limits = { dailyLoss: 10, tradesPerDay: 100, consecutiveLosses: 3, cooldownMs: 120000, maxStake: 5, maxOpenTrades: 100, maxTradesPerMarket: 20, baseStake: 1, ...limits };
    this.day = this.dayKey(); this.reset(); this.stopped = true;
    this.currentStake = Math.min(this.limits.baseStake, this.limits.maxStake);
  }
  dayKey(now = new Date()) { return `${now.getFullYear()}-${now.getMonth()+1}-${now.getDate()}`; }
  reset() { this.trades=0; this.loss=0; this.consecutiveLosses=0; this.lastTradeAt=0; this.openTrades=0; this.openByMarket={}; }
  rollover(now = new Date()) { const day=this.dayKey(now); if(day!==this.day){this.day=day;this.reset();} }
  recordTrade({symbol="UNKNOWN"}={}) { this.rollover(); this.trades++; this.lastTradeAt=Date.now(); this.openTrades++; this.openByMarket[symbol]=(this.openByMarket[symbol]||0)+1; return this.currentStake; }
  recordClosedTrade(profit,symbol="UNKNOWN") {
    const p=Number(profit); if(!Number.isFinite(p)) return {recorded:false};
    this.openTrades=Math.max(0,this.openTrades-1); this.openByMarket[symbol]=Math.max(0,(this.openByMarket[symbol]||0)-1);
    if(p<0){this.loss+=Math.abs(p);this.consecutiveLosses++;}
    else if(p>0){this.consecutiveLosses=0;this.currentStake=Math.min(this.limits.maxStake,this.currentStake+this.limits.baseStake);}
    if(this.loss>=this.limits.dailyLoss||this.consecutiveLosses>=this.limits.consecutiveLosses)this.emergencyStop();
    return {recorded:true,currentStake:this.currentStake,stopped:this.stopped};
  }
  canTrade({stake=this.currentStake,autoTrade=false,mode="demo",symbol="UNKNOWN",ignoreCooldown=false}={}) {
    this.rollover();
    if(mode!=="demo")return{ok:false,reason:"Real-money execution is locked in this build"};
    if(this.stopped)return{ok:false,reason:"Emergency stop is active"};
    if(!autoTrade)return{ok:false,reason:"Demo execution is not enabled"};
    const amount=Number(stake);
    if(!Number.isFinite(amount)||amount<=0||amount>this.limits.maxStake)return{ok:false,reason:"Stake is outside configured limits"};
    if(this.loss>=this.limits.dailyLoss)return{ok:false,reason:"Daily loss limit reached"};
    if(this.trades>=this.limits.tradesPerDay)return{ok:false,reason:"Daily trade limit reached"};
    if(this.openTrades>=this.limits.maxOpenTrades)return{ok:false,reason:"Account-wide open-trade limit reached"};
    if((this.openByMarket[symbol]||0)>=this.limits.maxTradesPerMarket)return{ok:false,reason:"Per-market open-trade limit reached"};
    if(this.consecutiveLosses>=this.limits.consecutiveLosses)return{ok:false,reason:"Consecutive loss limit reached"};
    if(!ignoreCooldown && Date.now()-this.lastTradeAt<this.limits.cooldownMs)return{ok:false,reason:"Cooldown active"};
    return{ok:true,stake:amount};
  }
  emergencyStop(){this.stopped=true;}
  arm(){this.stopped=false;}
  restore(saved){if(!saved||typeof saved!=="object")return;this.day=saved.day||this.day;this.rollover();for(const k of ["trades","loss","consecutiveLosses","lastTradeAt","openTrades","currentStake"])if(Number.isFinite(Number(saved[k])))this[k]=Number(saved[k]);if(typeof saved.stopped==="boolean")this.stopped=saved.stopped;if(saved.openByMarket&&typeof saved.openByMarket==="object")this.openByMarket={...saved.openByMarket};}\n  snapshot(){this.rollover();return{day:this.day,stopped:this.stopped,trades:this.trades,openTrades:this.openTrades,dailyLoss:this.loss,consecutiveLosses:this.consecutiveLosses,currentStake:this.currentStake,openByMarket:{...this.openByMarket},limits:this.limits};}
}