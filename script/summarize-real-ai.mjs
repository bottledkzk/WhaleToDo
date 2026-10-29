/** Offline, synthetic-only calibration. Does not open account credentials or send model requests. */
import {readFile} from 'node:fs/promises';
import {resolve} from 'node:path';

const args=process.argv.slice(2);
if(args.length<1||args.length>2)throw Error('Usage: node script/summarize-real-ai.mjs <finished-receipt.json> [targeted-repair-receipt.json]');
const receipt=JSON.parse(await readFile(resolve(args[0]),'utf8'));
if(receipt.kind!=='synthetic_provider_acceptance_not_account_loop'||!Array.isArray(receipt.results))throw Error('Synthetic receipt required');
const repair=args[1]?JSON.parse(await readFile(resolve(args[1]),'utf8')):null;
if(repair&&(repair.kind!==receipt.kind||!Array.isArray(repair.results)))throw Error('Synthetic repair receipt required');
const rows=[...receipt.results,...(repair?.results??[])];
if(rows.some(r=>!Number.isSafeInteger(r.usage?.total_tokens)||r.usage.total_tokens<1))throw Error('Missing real usage; cannot calibrate from reservation estimates');
const ordinary=id=>['chat-next-step','chat-history','chat-long-history','plan-fixed','plan-large-context','review-facts','review-workload-15','diary-facts','diary-short','diary-workload-15','edit-rename','edit-category','edit-move','edit-opinion','edit-client-text','edit-client-opinion'].includes(id)||/^(?:chat|plan)-workload-(?:5|15|50|100)$/.test(id)||id.startsWith('share-');
const selected=receipt.results.filter(r=>ordinary(r.base_id));
const repairs=[];
for(const row of repair?.results??[]){
 const failed=receipt.results.find(r=>r.base_id===row.base_id&&r.repeat===row.repeat&&!r.automatic_checks_passed);
 if(failed&&ordinary(row.base_id)&&row.automatic_checks_passed){selected.push(row);repairs.push(row.id);}
}
const groups=new Map();
for(const row of selected){
 const base=row.base_id==='edit-opinion'?'edit-move':row.base_id==='edit-client-opinion'?'edit-client-text':row.base_id;
 const id=base+':'+row.repeat;groups.set(id,[...(groups.get(id)??[]),row]);
}
for(const [id,group] of groups){
 const required=id.startsWith('edit-move:')?['edit-move','edit-opinion']:id.startsWith('edit-client-text:')?['edit-client-text','edit-client-opinion']:[group[0].base_id];
 if(required.some(base=>!group.some(r=>r.base_id===base&&r.automatic_checks_passed)))throw Error('Incomplete task cannot be priced as a completed task: '+id);
}
const sum=items=>items.reduce((n,r)=>n+r.usage.total_tokens,0);
const tokens=sum(selected),tasks=groups.size,rate=Math.ceil((2*tokens/tasks)/100)*100;
const byMode=Object.fromEntries([...new Set(rows.map(r=>r.mode))].sort().map(mode=>{
 const sample=rows.filter(r=>r.mode===mode),normal=selected.filter(r=>r.mode===mode);
 return [mode,{calls:sample.length,tokens:sum(sample),ordinary_calls:normal.length,ordinary_tokens:sum(normal)}];
}));
console.log(JSON.stringify({kind:'synthetic_calibration_summary',all_calls:rows.length,all_tokens:sum(rows),automatic_passed:rows.filter(r=>r.automatic_checks_passed).length,
 flagged_cases:rows.filter(r=>!r.automatic_checks_passed).map(r=>({id:r.id,errors:r.errors})),
 calibration:{definition:'Normal user scenarios only; exclude identity, injection, missing facts and protected/unauthorized requests. Initial edit + opinion are one task; retain failed-attempt tokens and include the matching successful repair. Additional confirmation repeats do not inflate the task count. This synthetic mix is a pilot, not production traffic.',included_repairs:repairs,calls:selected.length,tasks,tokens,mean_tokens_per_task:tokens/tasks,target_points_per_task:0.5,recommended_tokens_per_point:rate,amortized_points_per_task:tokens/rate/tasks,single_wallet_rounded_points_per_task:Math.ceil(tokens/rate)/tasks},by_mode:byMode},null,2));
