export class RiskGuard {
  constructor(limits = {}) {
    this.limits = { dailyLoss: 10, tradesPerDay: 100, consecutiveLosses: 3, cooldownMs: 120000, maxStake: 5, allowLive: false, maxOpenTrades: 1, maxTradesPerMarket: 1, baseStake: 1, stakeProgressionEnabled: false, stakeProgression: [1,2,3,4,5], ...limits };
    if (!Number.isFinite(this.limits.maxStake) || this.limits.maxStake <= 0) throw new Error("maxStake must be a positive finite number");
    if (!Number.isFinite(this.limits.baseStake) || this.limits.baseStake <= 0) throw new Error("baseStake must be a positive finite number");
    this.limits.stakeProgression = Array.isArray(this.limits.stakeProgression) && this.limits.stakeProgression.length
      ? this.limits.stakeProgression.map(Number) : [this.limits.baseStake];
    if (this.limits.stakeProgression.some(x => !Number.isFinite(x) || x <= 0)) throw new Error("Invalid stake progression");
    // Remove duplicate rungs created when maxStake caps the configured ladder.
    this.limits.stakeProgression = [...new Set(this.limits.stakeProgression.map(x => Math.min(x, this.limits.maxStake)))];
    if (this.limits.stakeProgression.some(x => !Number.isFinite(x) || x <= 0)) throw new Error("Stake progression is invalid after applying maxStake");
    this.day = this.dayKey(); this.reset(); this.stopped = true;
    this.progressionIndex = 0;
    this.currentStake = this.limits.stakeProgressionEnabled ? this.limits.stakeProgression[0] : Math.min(this.limits.baseStake, this.limits.maxStake);
  }
  dayKey(now = new Date()) { return `${now.getFullYear()}-${now.getMonth()+1}-${now.getDate()}`; }
  reset() { this.trades=0; this.loss=0; this.consecutiveLosses=0; this.lastTradeAt=0; this.openTrades=0; this.openByMarket={}; }
  rollover(now = new Date()) {
    const day=this.dayKey(now);
    if(day!==this.day){
      // Daily counters reset at midnight; outstanding contracts still consume exposure.
      const openTrades=this.openTrades;
      const openByMarket=this.openByMarket;
      this.day=day;
      this.reset();
      this.openTrades=openTrades;
      this.openByMarket=openByMarket;
    }
  }
  recordTrade({symbol="UNKNOWN"}={}) { this.rollover(); this.trades++; this.lastTradeAt=Date.now(); this.openTrades++; this.openByMarket[symbol]=(this.openByMarket[symbol]||0)+1; return this.currentStake; }
  recordClosedTrade(profit,symbol="UNKNOWN") {
    this.rollover();
    const p=Number(profit); if(!Number.isFinite(p)) return {recorded:false};
    this.openTrades=Math.max(0,this.openTrades-1); this.openByMarket[symbol]=Math.max(0,(this.openByMarket[symbol]||0)-1);
    if(p<0){this.loss+=Math.abs(p);this.consecutiveLosses++;if(this.limits.stakeProgressionEnabled){this.progressionIndex=Math.max(0,this.progressionIndex-1);this.currentStake=this.limits.stakeProgression[this.progressionIndex];}}
    else if(p>0){this.consecutiveLosses=0;if(this.limits.stakeProgressionEnabled){this.progressionIndex=Math.min((this.progressionIndex||0)+1,this.limits.stakeProgression.length-1);this.currentStake=this.limits.stakeProgression[this.progressionIndex];}}
    if(this.loss>=this.limits.dailyLoss||this.consecutiveLosses>=this.limits.consecutiveLosses)this.emergencyStop();
    return {recorded:true,currentStake:this.currentStake,stopped:this.stopped};
  }
  canTrade({stake=this.currentStake,autoTrade=false,mode="demo",symbol="UNKNOWN",ignoreCooldown=false}={}) {
    this.rollover();
    if(mode!=="demo"&&!(mode==="real"&&this.limits.allowLive))return{ok:false,reason:"Live trading is not explicitly enabled in local configuration"};
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
  restore(saved){
    if(!saved||typeof saved!=="object")return;
    const today=this.dayKey();
    const sameDay=saved.day===today;
    this.day=today;
    this.reset();
    // Restore daily counters only for the current day.
    if(sameDay){
      for(const k of ["trades","consecutiveLosses","lastTradeAt"])if(Number.isFinite(Number(saved[k])))this[k]=Number(saved[k]);
      if(Number.isFinite(Number(saved.dailyLoss)))this.loss=Number(saved.dailyLoss);
      else if(Number.isFinite(Number(saved.loss)))this.loss=Number(saved.loss);
    }
    // Open positions survive midnight and process restarts until Deriv settles them.
    if(Number.isFinite(Number(saved.openTrades)))this.openTrades=Math.max(0,Math.floor(Number(saved.openTrades)));
    if(saved.openByMarket&&typeof saved.openByMarket==="object"){
      this.openByMarket={};
      for(const [symbol,count] of Object.entries(saved.openByMarket)){
        const n=Number(count);
        if(Number.isFinite(n)&&n>0)this.openByMarket[symbol]=Math.floor(n);
      }
    }
    this.progressionIndex=this.limits.stakeProgressionEnabled && Number.isInteger(saved.progressionIndex)
      ? Math.max(0,Math.min(saved.progressionIndex,this.limits.stakeProgression.length-1)) : 0;
    this.currentStake=this.limits.stakeProgressionEnabled
      ? this.limits.stakeProgression[this.progressionIndex]
      : Math.min(this.limits.baseStake,this.limits.maxStake);
    if(typeof saved.stopped==="boolean")this.stopped=saved.stopped;
  }
  snapshot(){this.rollover();return{day:this.day,stopped:this.stopped,trades:this.trades,openTrades:this.openTrades,dailyLoss:this.loss,consecutiveLosses:this.consecutiveLosses,currentStake:this.currentStake,progressionIndex:this.progressionIndex||0,openByMarket:{...this.openByMarket},limits:this.limits};}
}