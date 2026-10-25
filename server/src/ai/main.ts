import { loadAiConfig } from './config.ts';
import { AiStore } from './store.ts';
import { buildAiApp } from './app.ts';
const config=loadAiConfig();const store=new AiStore(config.databaseUrl);
await store.ready();await store.recover();
const app=buildAiApp(config,store);
for(const signal of ['SIGTERM','SIGINT'])process.once(signal,()=>{void app.close().then(()=>store.close());});
await app.listen({host:config.host,port:config.port});
console.log('WhaleToDo AI gateway ready on loopback; credentials and request bodies are not logged');
