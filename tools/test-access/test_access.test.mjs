import assert from "node:assert/strict";
import fs from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import test from "node:test";

import {
  MAX_PASSWORD_LENGTH,
  authorizedLabel,
  passwordFromAuthorization,
  passwordKey,
  unauthorizedResponse,
} from "../../deploy/test-pages/lib/access_gate.mjs";
import {
  generatePassword,
  keysWithLabel,
  labelsOf,
  validateLabel,
} from "./manage.mjs";
import { MAX_RULES, buildRoutes, renderWranglerConfig, stage } from "./stage.mjs";

const basic = (credentials) =>
  `Basic ${Buffer.from(credentials, "utf8").toString("base64")}`;

test("reads the password from a Basic header and ignores the username", () => {
  assert.equal(passwordFromAuthorization(basic("anyone:s3cret")), "s3cret");
  assert.equal(passwordFromAuthorization(basic(":s3cret")), "s3cret");
  assert.equal(passwordFromAuthorization(basic("a:b:c")), "b:c");
  assert.equal(passwordFromAuthorization(basic("u:pässwörd")), "pässwörd");
  assert.equal(passwordFromAuthorization(`basic ${Buffer.from("u:p").toString("base64")}`), "p");
});

test("refuses headers without a usable password", () => {
  for (const header of [
    undefined,
    null,
    "",
    "Bearer abc",
    "Basic",
    "Basic !!!",
    basic("no-colon"),
    basic("user:"),
    basic(`u:${"x".repeat(MAX_PASSWORD_LENGTH + 1)}`),
    `Basic ${Buffer.from([0x75, 0x3a, 0xff]).toString("base64")}`,
  ]) {
    assert.equal(passwordFromAuthorization(header), null, String(header));
  }
});

test("keys a password by its SHA-256 in hex", async () => {
  assert.equal(
    await passwordKey("abc"),
    "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
  );
});

test("lets in a known password and nobody else", async () => {
  const store = new Map([[await passwordKey("right"), "anna"]]);
  const lookup = async (key) => store.get(key) ?? null;

  assert.equal(await authorizedLabel(basic("x:right"), lookup), "anna");
  assert.equal(await authorizedLabel(basic("x:wrong"), lookup), null);
  assert.equal(await authorizedLabel(null, lookup), null);
  assert.equal(await authorizedLabel(basic("x:right"), async () => ""), null);
});

test("does not look anything up without a password", async () => {
  let lookups = 0;
  await authorizedLabel("Bearer x", async () => {
    lookups += 1;
    return "anna";
  });
  assert.equal(lookups, 0);
});

test("asks for a password with a 401 that nobody caches", async () => {
  const response = unauthorizedResponse();
  assert.equal(response.status, 401);
  assert.match(response.headers.get("www-authenticate"), /^Basic realm="/);
  assert.equal(response.headers.get("cache-control"), "no-store");
  assert.equal(response.headers.get("x-frame-options"), "DENY");
});

test("sends pages through the gate and static files around it", () => {
  const routes = buildRoutes([
    { name: "_headers", directory: false },
    { name: "index.html", directory: false },
    { name: "trade.html", directory: false },
    { name: "js", directory: true },
    { name: "css", directory: true },
    { name: "sw.js", directory: false },
    { name: "favicon.ico", directory: false },
  ]);

  assert.deepEqual(routes, {
    version: 1,
    include: ["/*"],
    exclude: ["/css/*", "/favicon.ico", "/js/*", "/sw.js"],
  });
});

test("refuses more rules than Cloudflare Pages accepts", () => {
  const entries = Array.from({ length: MAX_RULES }, (_, index) => ({
    name: `file-${index}.png`,
    directory: false,
  }));
  assert.throws(() => buildRoutes(entries), /allows 100/);
});

test("stages the Pages project next to the release", async () => {
  const out = await fs.mkdtemp(path.join(os.tmpdir(), "test-access-stage-"));
  const releaseRoot = path.join(out, "release-public");
  const stageRoot = path.join(out, "test-pages");
  await fs.mkdir(path.join(releaseRoot, "fonts"), { recursive: true });
  await fs.writeFile(path.join(releaseRoot, "index.html"), "");
  await fs.writeFile(path.join(releaseRoot, "robots.txt"), "");

  await stage({ releaseRoot, stageRoot, projectName: "hv-test", kvNamespaceId: "a".repeat(32) });

  assert.deepEqual(JSON.parse(await fs.readFile(path.join(releaseRoot, "_routes.json"), "utf8")), {
    version: 1,
    include: ["/*"],
    exclude: ["/fonts/*", "/robots.txt"],
  });
  const config = await fs.readFile(path.join(stageRoot, "wrangler.toml"), "utf8");
  assert.match(config, /^name = "hv-test"$/m);
  assert.match(config, /^pages_build_output_dir = "\.\.\/release-public"$/m);
  assert.match(config, new RegExp(`^id = "${"a".repeat(32)}"$`, "m"));
  for (const file of [
    "functions/_middleware.js",
    "functions/api/coin-icon/[key].js",
    "lib/access_gate.mjs",
  ]) {
    await fs.access(path.join(stageRoot, file));
  }
});

test("refuses to stage without a real KV namespace id or project name", () => {
  const template = 'name = "__PROJECT_NAME__"\nid = "__KV_NAMESPACE_ID__"\n';
  assert.throws(
    () => renderWranglerConfig(template, { projectName: "hv-test", kvNamespaceId: undefined }),
    /TEST_ACCESS_KV_ID/,
  );
  assert.throws(
    () => renderWranglerConfig(template, { projectName: "hv-test", kvNamespaceId: "x" }),
    /TEST_ACCESS_KV_ID/,
  );
  assert.throws(
    () => renderWranglerConfig(template, { projectName: "Bad Name", kvNamespaceId: "a".repeat(32) }),
    /project name/,
  );
});

test("labels are short and plain", () => {
  assert.equal(validateLabel("anna.b-2_x"), "anna.b-2_x");
  for (const label of [undefined, "", "a b", "x".repeat(65), "ä"]) {
    assert.throws(() => validateLabel(label), /label/);
  }
});

test("passwords are long and random", () => {
  const first = generatePassword();
  assert.match(first, /^[A-Za-z0-9_-]{24}$/);
  assert.notEqual(first, generatePassword());
});

test("revokes by label and lists labels only", () => {
  const listed = [
    { name: "k1", metadata: { label: "anna" } },
    { name: "k2", metadata: { label: "ben" } },
    { name: "k3", metadata: { label: "anna" } },
    { name: "k4" },
  ];
  assert.deepEqual(keysWithLabel(listed, "anna"), ["k1", "k3"]);
  assert.deepEqual(labelsOf(listed), ["(no label)", "anna", "anna", "ben"]);
});
