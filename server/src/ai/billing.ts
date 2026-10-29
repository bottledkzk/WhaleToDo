/** Incremental ceiling over a frozen-rate bucket: splitting a task does not add rounding fees. */
export function cumulativeCharge(previousTokens:bigint,previousCharged:bigint,currentTokens:number,rate:number):number {
 if(previousTokens<0n||previousCharged<0n||!Number.isSafeInteger(currentTokens)||currentTokens<0||!Number.isSafeInteger(rate)||rate<1)throw Error('Invalid cumulative billing inputs');
 const divisor=BigInt(rate),total=previousTokens+BigInt(currentTokens);
 const expected=(total+divisor-1n)/divisor;
 const increment=expected-previousCharged;
 if(increment<0n||increment>BigInt(Number.MAX_SAFE_INTEGER))throw Error('Cumulative billing invariant violated');
 return Number(increment);
}
export const CUMULATIVE_LEDGER_REASON='ai-cumulative-v1';
