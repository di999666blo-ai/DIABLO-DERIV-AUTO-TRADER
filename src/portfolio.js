export function createBatchPlan(markets, tradesPerMarket) {
  if (!Array.isArray(markets) || markets.length < 1 || markets.length > 20) {
    throw new Error("Select between 1 and 20 markets");
  }
  const unique = [...new Set(markets.map(x => String(x).trim()).filter(Boolean))];
  if (unique.length !== markets.length) throw new Error("Markets must be unique and non-empty");
  if (unique.some(x => !/^[A-Z0-9_]+$/.test(x))) throw new Error("One or more market symbols are invalid");
  const count = Number(tradesPerMarket);
  if (!Number.isInteger(count) || count < 1 || count > 100) throw new Error("Trades per market must be an integer from 1 to 100");
  const total = unique.length * count;
  if (total > 100) throw new Error("The planned batch exceeds the account-wide limit of 100 trades");
  return { markets: unique, tradesPerMarket: count, totalTrades: total,
    orders: unique.flatMap(symbol => Array.from({length: count}, (_, index) => ({symbol, sequence: index + 1}))) };
}
