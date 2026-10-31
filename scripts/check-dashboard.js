import { readFile } from "node:fs/promises";
import vm from "node:vm";

const html = await readFile(new URL("../public/index.html", import.meta.url), "utf8");
const blocks = [...html.matchAll(/<script\\b[^>]*>([\\s\\S]*?)<\\/script>/gi)];
if (!blocks.length) throw new Error("Dashboard has no inline script to validate");
for (let i = 0; i < blocks.length; i++) {
  new vm.Script(blocks[i][1], { filename: `public/index.html:inline-script-${i + 1}` });
}
console.log(`Dashboard JavaScript syntax OK (${blocks.length} inline script block(s))`);
