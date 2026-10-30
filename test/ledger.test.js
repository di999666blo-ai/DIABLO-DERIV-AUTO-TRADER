import test from "node:test";
import assert from "node:assert/strict";
import {mkdtemp} from "node:fs/promises";
import {tmpdir} from "node:os";
import {join} from "node:path";
import {TradeLedger} from "../src/ledger.js";

test("ledger persists open contracts and restores state", async()=>{
  const dir=await mkdtemp(join(tmpdir(),"diablo-ledger-"));
  const path=join(dir,"state.json"),a=new TradeLedger(path);await a.load();
  await a.recordOpen({contract_id:123,symbol:"frxEURUSD",stake:1});
  const b=new TradeLedger(path);const state=await b.load();
  assert.equal(state.open["123"].symbol,"frxEURUSD");
  assert.equal(state.open["123"].stake,1);
});

test("ledger records confirmed settlement once and removes open contract",async()=>{
  const dir=await mkdtemp(join(tmpdir(),"diablo-ledger-"));
  const ledger=new TradeLedger(join(dir,"state.json"));await ledger.load();
  await ledger.recordOpen({contract_id:456,symbol:"frxGBPUSD",stake:2});
  const pending=await ledger.settle({contract_id:456,status:"open",profit:0});
  assert.equal(pending.pending,true);
  const closed=await ledger.settle({contract_id:456,status:"won",is_sold:1,profit:1.5});
  assert.equal(closed.recorded,true);
  assert.equal(closed.trade.symbol,"frxGBPUSD");
  assert.equal(ledger.snapshot().open["456"],undefined);
  const duplicate=await ledger.settle({contract_id:456,status:"won",is_sold:1,profit:1.5});
  assert.equal(duplicate.duplicate,true);
  assert.equal(ledger.snapshot().closed.length,1);
});
