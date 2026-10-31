import { spawnSync } from "node:child_process";
function run(path, args) {
  const result = spawnSync(process.execPath, [path, ...args], {
    stdio: "inherit",
    shell: false,
  });
  if (result.error) throw result.error;
  if (result.status !== 0) process.exit(result.status || 1);
}
if (process.argv[2] === "build") {
  run("node_modules/typescript/bin/tsc", ["-b"]);
  run("node_modules/vite/bin/vite.js", ["build"]);
}
