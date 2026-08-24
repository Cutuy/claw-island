import { buildApp } from "./app.js";
const port=Number(process.env.PORT??18818),{app,closeClients}=buildApp();
let stopping=false;const stop=async()=>{if(stopping)return;stopping=true;closeClients();await app.close();process.exit(0)};
process.on("SIGTERM",stop);process.on("SIGINT",stop);
await app.listen({host:"0.0.0.0",port});
