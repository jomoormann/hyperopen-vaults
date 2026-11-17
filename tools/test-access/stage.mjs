// Assembles the Cloudflare Pages project of the test deployment in
// out/test-pages, next to the release in out/release-public:
//
//   out/test-pages/wrangler.toml   project name and KV namespace filled in
//   out/test-pages/functions/      the password gate and the coin-icon proxy
//   out/test-pages/lib/            the gate's logic
//   out/release-public/_routes.json
//
// Pages reads its configuration and functions only from the directory the
// deploy runs in, so keeping them here leaves the public release and every
// self-hosted copy without the gate.
//
//   [TEST_ACCESS_KV_ID=<id>] [CF_PAGES_PROJECT=<name>] node tools/test-access/stage.mjs

import fs from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";

export const ROUTES_FILE = "_routes.json";
// The Pages project and the KV namespace of the passwords
// ("hyperopen-vaults-test-access"). Neither is a secret. CF_PAGES_PROJECT and
// TEST_ACCESS_KV_ID override them for another account.
export const DEFAULT_PROJECT_NAME = "hyperopen-vaults";
export const DEFAULT_KV_NAMESPACE_ID = "53d0df25368d43489527c1d6a8119482";

// Cloudflare Pages accepts at most 100 include and exclude rules together,
// each at most 100 characters.
export const MAX_RULES = 100;
export const MAX_RULE_LENGTH = 100;

const REPO_ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..", "..");

// Files Cloudflare reads itself; they are never served.
const CLOUDFLARE_FILES = new Set(["_headers", "_redirects", ROUTES_FILE]);

// The gate runs for pages and /api/*. Every directory in the release root
// holds static files only, and every top-level file except *.html is static:
// those are served without it. The code is public anyway, and it keeps a page
// load to one or two Function invocations on the free plan. Any other path,
// including every address the app routes in the browser, reaches the gate.
// `entries` are the release root's entries as { name, directory }.
export function buildRoutes(entries) {
  const exclude = [];

  for (const { name, directory } of entries) {
    if (CLOUDFLARE_FILES.has(name)) {
      continue;
    }
    if (directory) {
      exclude.push(`/${name}/*`);
    } else if (!name.endsWith(".html")) {
      exclude.push(`/${name}`);
    }
  }

  exclude.sort();

  const routes = { version: 1, include: ["/*"], exclude };
  const rules = [...routes.include, ...routes.exclude];

  if (rules.length > MAX_RULES) {
    throw new Error(
      `${ROUTES_FILE} would have ${rules.length} rules; Cloudflare Pages allows ${MAX_RULES}.`,
    );
  }
  for (const rule of rules) {
    if (rule.length > MAX_RULE_LENGTH) {
      throw new Error(`${ROUTES_FILE} rule is longer than ${MAX_RULE_LENGTH} characters: ${rule}`);
    }
  }

  return routes;
}

export function renderWranglerConfig(template, { projectName, kvNamespaceId }) {
  if (!/^[a-z0-9][a-z0-9-]{0,57}$/.test(projectName ?? "")) {
    throw new Error(`Not a Pages project name: ${projectName}`);
  }
  if (!/^[0-9a-f]{32}$/.test(kvNamespaceId ?? "")) {
    throw new Error(
      "TEST_ACCESS_KV_ID must be the 32-character id of the KV namespace (docs/test-deployment.md).",
    );
  }

  return template
    .replace("__PROJECT_NAME__", projectName)
    .replace("__KV_NAMESPACE_ID__", kvNamespaceId);
}

export async function stage({
  releaseRoot = path.join(REPO_ROOT, "out", "release-public"),
  stageRoot = path.join(REPO_ROOT, "out", "test-pages"),
  projectName = DEFAULT_PROJECT_NAME,
  kvNamespaceId = DEFAULT_KV_NAMESPACE_ID,
} = {}) {
  const source = path.join(REPO_ROOT, "deploy", "test-pages");
  const template = await fs.readFile(path.join(source, "wrangler.template.toml"), "utf8");
  const config = renderWranglerConfig(template, { projectName, kvNamespaceId });

  if (path.relative(path.dirname(stageRoot), releaseRoot) !== "release-public") {
    throw new Error("The release must sit next to the staged project as release-public.");
  }

  await fs.rm(stageRoot, { recursive: true, force: true });
  await fs.mkdir(stageRoot, { recursive: true });
  await fs.cp(path.join(source, "functions"), path.join(stageRoot, "functions"), { recursive: true });
  await fs.cp(path.join(source, "lib"), path.join(stageRoot, "lib"), { recursive: true });
  await fs.cp(path.join(REPO_ROOT, "functions", "api"), path.join(stageRoot, "functions", "api"), {
    recursive: true,
  });
  await fs.writeFile(path.join(stageRoot, "wrangler.toml"), config);

  const dirents = await fs.readdir(releaseRoot, { withFileTypes: true });
  const routes = buildRoutes(
    dirents.map((dirent) => ({ name: dirent.name, directory: dirent.isDirectory() })),
  );
  await fs.writeFile(path.join(releaseRoot, ROUTES_FILE), `${JSON.stringify(routes, null, 2)}\n`);

  return { stageRoot, routes };
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try {
    const { stageRoot, routes } = await stage({
      projectName: process.env.CF_PAGES_PROJECT?.trim() || DEFAULT_PROJECT_NAME,
      kvNamespaceId: process.env.TEST_ACCESS_KV_ID?.trim() || DEFAULT_KV_NAMESPACE_ID,
    });
    console.log(
      `[test-access] staged ${stageRoot}; ${routes.exclude.length} static paths bypass the gate.`,
    );
  } catch (error) {
    console.error(`[test-access] ${error.message}`);
    process.exit(1);
  }
}
