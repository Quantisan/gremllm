import { spawn } from "node:child_process";
import { Readable, Writable } from "node:stream";

const proc = spawn("npx", ["@zed-industries/claude-code-acp"], {
  stdio: ["pipe", "pipe", "inherit"],
  env: { ...process.env }
});

console.log(`Spawned with PID: ${proc.pid}`);
console.log(`stdin writable: ${proc.stdin instanceof Writable}`);
console.log(`stdout readable: ${proc.stdout instanceof Readable}`);

setTimeout(() => {
  proc.kill("SIGTERM");
  console.log("Process terminated");
}, 5000);

proc.on("exit", (code, signal) => {
  console.log(`Exited: code=${code}, signal=${signal}`);
});
