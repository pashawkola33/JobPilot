import { readdirSync, readFileSync, statSync } from "node:fs";
import { join, relative } from "node:path";
import ts from "typescript";

const projectRoot = new URL("../", import.meta.url);
const rootPath = decodeURIComponent(projectRoot.pathname).replace(/\/$/, "");
const files = ["src", "test"]
  .flatMap((directory) => collect(join(rootPath, directory)))
  .filter((file) => relative(rootPath, file) !== "src/login.ts");
const errors = [];

for (const file of files) {
  const display = relative(rootPath, file);
  const source = readFileSync(file, "utf8");
  const parsed = ts.createSourceFile(display, source, ts.ScriptTarget.Latest, true, ts.ScriptKind.TS);
  for (const diagnostic of parsed.parseDiagnostics) {
    errors.push(`${display}: ${ts.flattenDiagnosticMessageText(diagnostic.messageText, " ")}`);
  }
  source.split("\n").forEach((line, index) => {
    if (/[ \t]+$/.test(line)) errors.push(`${display}:${index + 1}: trailing whitespace`);
    if (/\t/.test(line)) errors.push(`${display}:${index + 1}: tab indentation`);
  });
  for (const forbidden of [".only(", "@ts-ignore", "enqueueLinks(", "addRequests("]) {
    if (source.includes(forbidden)) errors.push(`${display}: forbidden token ${forbidden}`);
  }
  if (display.startsWith("src/") && display !== "src/browserBinary.ts") {
    for (const unsafe of ["--no-sandbox", "--disable-setuid-sandbox"]) {
      if (source.includes(unsafe)) errors.push(`${display}: sandbox-disabling argument ${unsafe}`);
    }
  }
}

if (errors.length > 0) {
  process.stderr.write(`${errors.join("\n")}\n`);
  process.exitCode = 1;
} else {
  process.stdout.write(`lint: ${files.length} TypeScript files passed\n`);
}

function collect(directory) {
  const result = [];
  for (const name of readdirSync(directory).sort()) {
    const path = join(directory, name);
    if (statSync(path).isDirectory()) result.push(...collect(path));
    else if (path.endsWith(".ts")) result.push(path);
  }
  return result;
}
