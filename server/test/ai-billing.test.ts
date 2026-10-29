import {test} from 'node:test';
import assert from 'node:assert/strict';
import {cumulativeCharge} from '../src/ai/billing.ts';
test('two half-point tasks charge one point in total instead of two minimum fees',()=>{
 assert.equal(cumulativeCharge(0n,0n,700,1400),1);
 assert.equal(cumulativeCharge(700n,1n,700,1400),0);
});
test('billing is independent of splitting and order, within one frozen-rate bucket',()=>{
 const calls=[0,1,699,700,1400,2801,430];
 for(const sequence of [calls,[...calls].reverse(),[...calls].sort((a,b)=>a-b)]){
  let tokens=0n,charged=0n;
  for(const n of sequence){charged+=BigInt(cumulativeCharge(tokens,charged,n,1400));tokens+=BigInt(n);}
  assert.equal(charged,(tokens+1399n)/1400n);
 }
});
test('large historical totals keep exact integer arithmetic and reject invalid state',()=>{
 const tokens=9007199254740993000n,charged=(tokens+1399n)/1400n;
 assert.ok(cumulativeCharge(tokens,charged,1000,1400)>=0);
 assert.throws(()=>cumulativeCharge(-1n,0n,1,1400));
 assert.throws(()=>cumulativeCharge(0n,5n,1,1400));
 assert.throws(()=>cumulativeCharge(0n,0n,1,0));
});
