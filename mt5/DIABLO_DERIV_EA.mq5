// DIABLO DERIV AUTO TRADER - MT5 Expert Advisor
// Attach to a DEMO chart first. Requires desktop MetaTrader 5 terminal.
#property strict
#include <Trade/Trade.mqh>
CTrade trade;
input bool EnableTrading = false;
input double FixedLot = 0.01;
input int FastEMA = 9;
input int SlowEMA = 21;
input int RSIPeriod = 14;
input double MaxSpreadPoints = 30;
input double StopLossPoints = 250;
input double TakeProfitPoints = 500;
input int MaxPositions = 1;
input int CooldownSeconds = 120;
input double DailyLossLimit = 10.0;
input ulong MagicNumber = 930030;
input bool DemoAccountOnly = true;
datetime lastTrade = 0;
int fastHandle, slowHandle, rsiHandle;
double dayStartEquity;
int OnInit() {
  if(DemoAccountOnly && AccountInfoInteger(ACCOUNT_TRADE_MODE) != ACCOUNT_TRADE_MODE_DEMO) {
    Print("DIABLO: Demo-only guard blocked initialization."); return INIT_FAILED;
  }
  fastHandle=iMA(_Symbol,_Period,FastEMA,0,MODE_EMA,PRICE_CLOSE);
  slowHandle=iMA(_Symbol,_Period,SlowEMA,0,MODE_EMA,PRICE_CLOSE);
  rsiHandle=iRSI(_Symbol,_Period,RSIPeriod,PRICE_CLOSE);
  if(fastHandle==INVALID_HANDLE || slowHandle==INVALID_HANDLE || rsiHandle==INVALID_HANDLE) return INIT_FAILED;
  trade.SetExpertMagicNumber(MagicNumber);
  dayStartEquity=AccountInfoDouble(ACCOUNT_EQUITY);
  return INIT_SUCCEEDED;
}
void OnDeinit(const int reason) { IndicatorRelease(fastHandle); IndicatorRelease(slowHandle); IndicatorRelease(rsiHandle); }
void OnTick() {
  if(!EnableTrading || !TerminalInfoInteger(TERMINAL_TRADE_ALLOWED) || !MQLInfoInteger(MQL_TRADE_ALLOWED)) return;
  if(TimeCurrent()-lastTrade<CooldownSeconds) return;
  MqlTick tick; if(!SymbolInfoTick(_Symbol,tick)) return;
  if((tick.ask-tick.bid)/_Point>MaxSpreadPoints) return;
  if(dayStartEquity-AccountInfoDouble(ACCOUNT_EQUITY)>=DailyLossLimit) return;
  int count=0;
  for(int i=PositionsTotal()-1;i>=0;i--) if(PositionGetSymbol(i)==_Symbol && (ulong)PositionGetInteger(POSITION_MAGIC)==MagicNumber) count++;
  if(count>=MaxPositions) return;
  double f[3],s[3],r[3];
  if(CopyBuffer(fastHandle,0,0,3,f)<3 || CopyBuffer(slowHandle,0,0,3,s)<3 || CopyBuffer(rsiHandle,0,0,3,r)<3) return;
  bool buy=(f[1]>s[1] && r[1]>=52 && r[1]<=72 && iClose(_Symbol,_Period,1)>f[1]);
  bool sell=(f[1]<s[1] && r[1]<=48 && r[1]>=28 && iClose(_Symbol,_Period,1)<f[1]);
  double sl=0,tp=0;
  if(buy) { sl=tick.ask-StopLossPoints*_Point; tp=tick.ask+TakeProfitPoints*_Point; if(trade.Buy(FixedLot,_Symbol,tick.ask,sl,tp,"DIABLO EMA/RSI BUY")) lastTrade=TimeCurrent(); }
  else if(sell) { sl=tick.bid+StopLossPoints*_Point; tp=tick.bid-TakeProfitPoints*_Point; if(trade.Sell(FixedLot,_Symbol,tick.bid,sl,tp,"DIABLO EMA/RSI SELL")) lastTrade=TimeCurrent(); }
}
