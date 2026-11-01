/** Time is supplied by the server clock; only a validated IANA timezone comes from the client. */
export function runtimeTime(context:string,now=Date.now()) {
 let zone='UTC';
 try {const value=JSON.parse(context);zone=typeof value?.timezone==='string'?value.timezone:typeof value?.agenda?.timezone==='string'?value.agenda.timezone:zone;}catch{zone=/^时区=([^;\r\n]+)/u.exec(context)?.[1]??zone;}
 let format:Intl.DateTimeFormat;
 try {format=new Intl.DateTimeFormat('sv-SE',{timeZone:zone,year:'numeric',month:'2-digit',day:'2-digit',hour:'2-digit',minute:'2-digit',second:'2-digit',hourCycle:'h23'});}catch {zone='UTC';format=new Intl.DateTimeFormat('sv-SE',{timeZone:zone,year:'numeric',month:'2-digit',day:'2-digit',hour:'2-digit',minute:'2-digit',second:'2-digit',hourCycle:'h23'});}
 const local=format.format(now),today=local.slice(0,10),day=Date.parse(today+'T00:00:00Z');
 return {now_ms:now,now_utc:new Date(now).toISOString(),timezone:zone,local_datetime:local,today,yesterday:new Date(day-86400000).toISOString().slice(0,10),tomorrow:new Date(day+86400000).toISOString().slice(0,10)};
}

/** Reject nonexistent or ambiguous wall times instead of silently selecting a DST occurrence. */
export function localMillis(date:string,clock:string,zone:string):number|null {
 const format=new Intl.DateTimeFormat('sv-SE',{timeZone:zone,year:'numeric',month:'2-digit',day:'2-digit',hour:'2-digit',minute:'2-digit',second:'2-digit',hourCycle:'h23'});
 const wanted=date+' '+clock,utc=Date.parse(date+'T'+clock+'Z'),matches=new Set<number>();
 for(const delta of [-86400000,0,86400000]) {const probe=utc+delta,offset=Date.parse(format.format(probe).replace(' ','T')+'Z')-probe,target=utc-offset;if(format.format(target)===wanted)matches.add(target);}
 return matches.size===1?[...matches][0]!:null;
}
