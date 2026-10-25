import { readFileSync } from 'node:fs';
import { join } from 'node:path';
export interface AiConfig {
 databaseUrl: string; apiKey: string; host: string; port: number; model: string;
 maxOutput: number; timeoutMs: number; userDailyCalls: number; globalDailyCalls: number;
 userDailyTokens: number; globalDailyTokens: number; businessTimezone: string; tokensPerPoint?:number;editModel?:string;editEnabled?:boolean;
}
function integer(value: string | undefined, fallback: number, low: number, high: number): number {
 const n=value===undefined ? fallback : Number(value);
 if(!Number.isSafeInteger(n)||n<low||n>high) throw new Error('Invalid AI limit configuration');
 return n;
}
export function loadAiConfig(env: NodeJS.ProcessEnv=process.env): AiConfig {
 const dir=env.CREDENTIALS_DIRECTORY;
 if(!dir) throw new Error('AI credentials must be supplied by systemd LoadCredential');
 const databaseUrl=readFileSync(join(dir,'database.url'),'utf8').trim();
 const apiKey=readFileSync(join(dir,'deepseek.key'),'utf8').trim();
 const db=new URL(databaseUrl);
 if(!['postgresql:','postgres:'].includes(db.protocol)||db.hostname!=='127.0.0.1') throw new Error('Database must use local loopback');
 if(!/^sk-[A-Za-z0-9_-]+$/.test(apiKey)) throw new Error('Invalid provider credential');
 const businessTimezone=env.BUSINESS_TIMEZONE??'Asia/Shanghai';
 new Intl.DateTimeFormat('en-US',{timeZone:businessTimezone});
 return {databaseUrl,apiKey,host:'127.0.0.1',port:integer(env.AI_PORT,8081,1024,65535),
 model:env.DEEPSEEK_MODEL??'deepseek-flash',tokensPerPoint:integer(env.AI_TOKENS_PER_POINT,1000,1,1000000),editModel:env.DEEPSEEK_EDIT_MODEL??'deepseek-v4-pro',editEnabled:env.AI_EDIT_ENABLED==='true',maxOutput:integer(env.AI_MAX_OUTPUT,2048,32,4096),
 timeoutMs:integer(env.AI_TIMEOUT_MS,45000,1000,60000),userDailyCalls:integer(env.AI_USER_DAILY_CALLS,5,1,100),
 globalDailyCalls:integer(env.AI_GLOBAL_DAILY_CALLS,40,1,1000),
 userDailyTokens:integer(env.AI_USER_DAILY_TOKENS,30000,1024,500000),
 globalDailyTokens:integer(env.AI_GLOBAL_DAILY_TOKENS,120000,1024,2000000),businessTimezone};
}
