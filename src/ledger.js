import {mkdir, readFile, rename, writeFile} from "node:fs/promises";
import {dirname} from "node:path";

export class TradeLedger {
  constructor(path = process.env.DIABLO_STATE_FILE || "./data/trading-state.json") {
    this.path = path;
    this.state = {version:1, open:{}, closed:[], risk:null};
    this.ready = false;
    this.queue = Promise.resolve();
  }
  async load() {
    try {
      const parsed = JSON.parse(await readFile(this.path, "utf8"));
      if (parsed?.version === 1) this.state = {...this.state, ...parsed};
    } catch (error) {
      if (error.code !== "ENOENT") throw new Error("Cannot read persisted trading state: " + error.message);
    }
    this.ready = true;
    return this.snapshot();
  }
  async save() {
    if (!this.ready) throw new Error("Trade ledger is not loaded");
    const task = async () => {
      await mkdir(dirname(this.path), {recursive:true});
      const tmp = this.path + ".tmp";
      await writeFile(tmp, JSON.stringify(this.state, null, 2), {mode:0o600});
      await rename(tmp, this.path);
    };
    this.queue = this.queue.then(task, task);
    return this.queue;
  }
  async recordOpen(contract) {
    const id = String(contract.contract_id || "");
    if (!id) throw new Error("Deriv did not return a contract ID");
    if (this.state.closed.some(x => x.contractId === id)) return false;
    this.state.open[id] = {...this.state.open[id], ...contract, contractId:id, openedAt:this.state.open[id]?.openedAt || new Date().toISOString()};
    await this.save();
    return true;
  }
  async settle(contract) {
    const id = String(contract.contract_id || contract.contractId || "");
    if (!id || this.state.closed.some(x => x.contractId === id)) return {recorded:false, duplicate:Boolean(id)};
    // Only settle contracts this ledger actually opened or recovered. An
    // unrelated/stale stream update must never alter the user's P/L history.
    const prior = this.state.open[id];
    if (!prior) return {recorded:false, unknown:true};
    const terminal = contract.is_sold === 1 || contract.is_sold === true || contract.is_expired === 1 || contract.is_expired === true || ["won","lost","sold"].includes(String(contract.status||"").toLowerCase());
    if (!terminal) return {recorded:false, pending:true};
    const profit = Number(contract.profit);
    if (!Number.isFinite(profit)) return {recorded:false, pending:true, reason:"Settlement profit not available yet"};
    const row = {contractId:id, symbol:contract.underlying || prior.symbol || "UNKNOWN", profit, status:contract.status || (profit >= 0 ? "won":"lost"), stake:Number(contract.buy_price ?? prior.stake ?? 0), settledAt:new Date().toISOString()};
    delete this.state.open[id];
    this.state.closed.push(row);
    if (this.state.closed.length > 2000) this.state.closed = this.state.closed.slice(-2000);
    await this.save();
    return {recorded:true, trade:row};
  }
  async saveRisk(snapshot) { this.state.risk = snapshot; await this.save(); }
  snapshot() { return JSON.parse(JSON.stringify(this.state)); }
}
