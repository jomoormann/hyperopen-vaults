// Adds, lists and revokes passwords of the test deployment.
//
//   npm run test-access:add -- <label>      new random password, printed once
//   npm run test-access:list                labels of all passwords
//   npm run test-access:revoke -- <label>   deletes every password with that label
//
// Talks to Cloudflare through wrangler, so it needs `npx wrangler login` or
// CLOUDFLARE_API_TOKEN (with Workers KV edit rights) and CLOUDFLARE_ACCOUNT_ID.
// The namespace is the one the deployment binds as TEST_ACCESS
// (DEFAULT_KV_NAMESPACE_ID in stage.mjs), or TEST_ACCESS_KV_ID.
// Only the password's hash goes to Cloudflare; the password itself is never
// stored or passed on the command line.

import { execFileSync } from "node:child_process";
import { randomBytes } from "node:crypto";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { passwordKey } from "../../deploy/test-pages/lib/access_gate.mjs";
import { DEFAULT_KV_NAMESPACE_ID } from "./stage.mjs";

export const WRANGLER = "wrangler@4.156.2";

const REPO_ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..", "..");

const LABEL_PATTERN = /^[A-Za-z0-9._-]{1,64}$/;

export function validateLabel(label) {
  if (typeof label !== "string" || !LABEL_PATTERN.test(label)) {
    throw new Error("A label is 1-64 letters, digits, dots, dashes or underscores, such as anna.");
  }
  return label;
}

export function generatePassword() {
  return randomBytes(18).toString("base64url");
}

// The keys whose label is `label`, from `wrangler kv key list` output.
export function keysWithLabel(listed, label) {
  return listed
    .filter((entry) => entry?.metadata?.label === label)
    .map((entry) => entry.name);
}

export function labelsOf(listed) {
  return listed
    .map((entry) => entry?.metadata?.label ?? "(no label)")
    .sort();
}

function namespaceId() {
  const id = process.env.TEST_ACCESS_KV_ID?.trim() || DEFAULT_KV_NAMESPACE_ID;
  if (!/^[0-9a-f]{32}$/.test(id ?? "")) {
    throw new Error("TEST_ACCESS_KV_ID must be the 32-character id of the TEST_ACCESS KV namespace.");
  }
  return id;
}

function wrangler(args) {
  return execFileSync("npx", ["--yes", WRANGLER, ...args], {
    cwd: REPO_ROOT,
    encoding: "utf8",
    stdio: ["ignore", "pipe", "inherit"],
  });
}

function listKeys(id) {
  return JSON.parse(wrangler(["kv", "key", "list", "--namespace-id", id, "--remote"]));
}

async function add(label) {
  const id = namespaceId();
  const password = generatePassword();
  const key = await passwordKey(password);

  wrangler([
    "kv", "key", "put", key, label,
    "--namespace-id", id,
    "--metadata", JSON.stringify({ label }),
    "--remote",
  ]);

  console.log(`Password for ${label} (shown once, pass it on privately):\n\n  ${password}\n`);
}

function list() {
  const labels = labelsOf(listKeys(namespaceId()));
  console.log(labels.length === 0 ? "No passwords." : labels.join("\n"));
}

function revoke(label) {
  const id = namespaceId();
  const keys = keysWithLabel(listKeys(id), label);

  if (keys.length === 0) {
    console.log(`No password with the label ${label}.`);
    return;
  }
  for (const key of keys) {
    wrangler(["kv", "key", "delete", key, "--namespace-id", id, "--remote"]);
  }
  console.log(`Revoked ${keys.length} password(s) of ${label}.`);
}

async function main([command, label]) {
  switch (command) {
    case "add":
      return add(validateLabel(label));
    case "list":
      return list();
    case "revoke":
      return revoke(validateLabel(label));
    default:
      throw new Error("Usage: manage.mjs add|list|revoke [label]");
  }
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main(process.argv.slice(2)).catch((error) => {
    console.error(`[test-access] ${error.message}`);
    process.exit(1);
  });
}
