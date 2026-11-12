import crypto from "node:crypto";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

export const SECURITY_HEADERS_FILE_PATH = "_headers";
export const CONTROL_CACHE_CONTROL = "public, max-age=0, must-revalidate";
export const IMMUTABLE_CACHE_CONTROL = "public, max-age=31556952, immutable";
export const FONT_CACHE_CONTROL = "public, max-age=2592000";

const REPO_ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..", "..");

// The theme restore script is inlined into every release HTML head (one less
// render-blocking request); the CSP admits exactly that script by hash.
export const THEME_PRELOAD_INLINE_SOURCE = fs.readFileSync(
  path.join(REPO_ROOT, "resources", "public", "theme-preload.js"),
  "utf8"
);

export const THEME_PRELOAD_SCRIPT_HASH = `'sha256-${crypto
  .createHash("sha256")
  .update(THEME_PRELOAD_INLINE_SOURCE)
  .digest("base64")}'`;
export const DOCUMENT_PERMISSIONS_POLICY = [
  "accelerometer=()",
  "autoplay=()",
  "camera=()",
  "display-capture=()",
  "geolocation=()",
  "gyroscope=()",
  "microphone=()",
  "payment=()",
  "usb=()",
  "xr-spatial-tracking=()",
].join(", ");
export const DOCUMENT_REFERRER_POLICY = "strict-origin-when-cross-origin";

// This fork's release contacts Hyperliquid and the bridges the app offers,
// nothing else: no analytics beacon and no history server. Release builds
// turn the optimizer's history server off (hyperopen.config), so its origin
// is not admitted here either. Add it back together with that switch.
const DOCUMENT_CONNECT_SRC = [
  "'self'",
  "https://api.hyperliquid.xyz",
  "wss://api.hyperliquid.xyz",
  "https://stats-data.hyperliquid.xyz",
  "https://app.hyperliquid.xyz",
  // HyperEVM JSON-RPC (https://rpc.hyperliquid.xyz/evm): balance, gas-price
  // and receipt reads made by the page itself. Wallet-submitted transactions
  // go through the wallet's own RPC and never hit this directive.
  "https://rpc.hyperliquid.xyz",
  "https://api.hyperunit.xyz",
  "https://api.hyperunit-testnet.xyz",
  "https://app.across.to",
  "https://li.quest",
];

const DOCUMENT_IMG_SRC = [
  "'self'",
  "data:",
  "blob:",
  "https://app.hyperliquid.xyz",
  // Fallback market icons for HIP-3 coins Hyperliquid's CDN lacks.
  "https://assets.entropy.trade",
];

function appendDirective(directives, name, values) {
  if (!Array.isArray(values) || values.length === 0) {
    directives.push(name);
    return;
  }

  directives.push(`${name} ${values.join(" ")}`);
}

function normalizeImmutableAssetPath(assetPath) {
  const normalized = String(assetPath || "").trim();
  if (!normalized.startsWith("/")) {
    throw new Error(`Expected immutable asset paths to start with '/': ${assetPath}`);
  }

  return normalized;
}

function formatHeaderBlock(pattern, entries) {
  const lines = [pattern];

  for (const entry of entries) {
    if (entry?.detach) {
      lines.push(`  ! ${entry.name}`);
      continue;
    }

    lines.push(`  ${entry.name}: ${entry.value}`);
  }

  return lines.join("\n");
}

export function buildContentSecurityPolicy() {
  const directives = [];

  appendDirective(directives, "default-src", ["'self'"]);
  appendDirective(directives, "base-uri", ["'self'"]);
  appendDirective(directives, "form-action", ["'self'"]);
  appendDirective(directives, "object-src", ["'none'"]);
  // 'wasm-unsafe-eval' admits WebAssembly compilation and nothing else: it does
  // not permit eval() or any other string-to-JS execution, so the same-origin
  // script-execution control this policy exists for is unchanged. Without it the
  // bundled OSQP solver -- an Emscripten WASM build inside osqp.min.js -- throws
  // a CSP CompileError, and infrastructure/osqp.cljs catches it into
  // fallback.cljs, which silently re-solves with pure-JS quadprog and still
  // reports :status :solved. The run looks correct and is roughly ten times
  // slower. It has to live in this policy rather than a worker-specific one:
  // the "/*" rule below applies these headers to /js/*.js as well, and a
  // dedicated worker takes its CSP from its own script response.
  appendDirective(directives, "script-src", [
    "'self'",
    "'wasm-unsafe-eval'",
    THEME_PRELOAD_SCRIPT_HASH,
  ]);
  appendDirective(directives, "style-src", ["'self'", "'unsafe-inline'"]);
  appendDirective(directives, "style-src-elem", ["'self'", "'unsafe-inline'"]);
  appendDirective(directives, "style-src-attr", ["'unsafe-inline'"]);
  appendDirective(directives, "font-src", ["'self'", "data:"]);
  appendDirective(directives, "img-src", DOCUMENT_IMG_SRC);
  appendDirective(directives, "connect-src", DOCUMENT_CONNECT_SRC);
  appendDirective(directives, "worker-src", ["'self'"]);
  appendDirective(directives, "child-src", ["'self'"]);
  appendDirective(directives, "frame-src", ["'none'"]);
  appendDirective(directives, "frame-ancestors", ["'none'"]);
  appendDirective(directives, "manifest-src", ["'self'"]);
  appendDirective(directives, "media-src", ["'self'", "blob:"]);

  return `${directives.join("; ")};`;
}

export function expectedDocumentHeaders() {
  return {
    "content-security-policy": buildContentSecurityPolicy(),
    "x-frame-options": "DENY",
    "x-content-type-options": "nosniff",
    "referrer-policy": DOCUMENT_REFERRER_POLICY,
    "permissions-policy": DOCUMENT_PERMISSIONS_POLICY,
    "cache-control": CONTROL_CACHE_CONTROL,
  };
}

export function buildReleaseHeadersFile({
  immutableAssetPaths = [],
} = {}) {
  const normalizedImmutableAssetPaths = [...new Set(immutableAssetPaths.map(normalizeImmutableAssetPath))]
    .sort();
  const documentHeaders = expectedDocumentHeaders();
  const lines = [
    "# Generated by tools/release-assets/security_headers.mjs",
    "# Cloudflare Pages applies these rules to the published static release root.",
    "",
    formatHeaderBlock("/*", [
      {
        name: "Content-Security-Policy",
        value: documentHeaders["content-security-policy"],
      },
      { name: "X-Frame-Options", value: documentHeaders["x-frame-options"] },
      {
        name: "X-Content-Type-Options",
        value: documentHeaders["x-content-type-options"],
      },
      { name: "Referrer-Policy", value: documentHeaders["referrer-policy"] },
      {
        name: "Permissions-Policy",
        value: documentHeaders["permissions-policy"],
      },
      { name: "Cache-Control", value: documentHeaders["cache-control"] },
    ]),
  ];

  lines.push(
    "",
    formatHeaderBlock("/fonts/*", [
      { detach: true, name: "Cache-Control" },
      { name: "Cache-Control", value: FONT_CACHE_CONTROL },
    ]),
  );

  for (const assetPath of normalizedImmutableAssetPaths) {
    lines.push(
      "",
      formatHeaderBlock(assetPath, [
        { detach: true, name: "Cache-Control" },
        { name: "Cache-Control", value: IMMUTABLE_CACHE_CONTROL },
      ]),
    );
  }

  return `${lines.join("\n")}\n`;
}

// --- A server for hosts other than Cloudflare Pages -----------------------------
//
// `_headers` only means something to Cloudflare Pages. The Caddyfile below
// gives the same headers, the same caching and the same routing to anyone
// who serves the release from a container or their own machine, from the
// one policy above.

export const CADDYFILE_FILE_PATH = "Caddyfile";

// Requests for a file of one of these types never fall back to the app
// shell: a missing script must be a 404, not a page of HTML.
const STATIC_FILE_EXTENSIONS = [
  "css",
  "html",
  "ico",
  "js",
  "json",
  "map",
  "png",
  "svg",
  "ttf",
  "txt",
  "wasm",
  "webmanifest",
  "woff",
  "woff2",
  "xml",
];

function caddyQuote(value) {
  const text = String(value);
  if (text.includes('"') || text.includes("\n")) {
    throw new Error(`Header value cannot be written to a Caddyfile: ${text}`);
  }
  return `"${text}"`;
}

export function buildCaddyfile({ immutableAssetPaths = [] } = {}) {
  const normalizedImmutableAssetPaths = [...new Set(immutableAssetPaths.map(normalizeImmutableAssetPath))]
    .sort();
  const documentHeaders = expectedDocumentHeaders();
  const lines = [
    "# Generated by tools/release-assets/security_headers.mjs",
    "# Serves the static release with the headers Cloudflare Pages reads from _headers.",
    "#",
    "# SITE_ADDRESS is what Caddy listens on. The default is plain HTTP on port",
    "# 8080, for localhost or behind a proxy that ends TLS. Set it to a domain",
    "# name and Caddy gets a certificate for it by itself.",
    "{",
    "\tadmin off",
    "}",
    "",
    "{$SITE_ADDRESS::8080} {",
    "\troot * /srv",
    "\tencode zstd gzip",
    "",
    "\theader {",
    `\t\tContent-Security-Policy ${caddyQuote(documentHeaders["content-security-policy"])}`,
    `\t\tX-Frame-Options ${caddyQuote(documentHeaders["x-frame-options"])}`,
    `\t\tX-Content-Type-Options ${caddyQuote(documentHeaders["x-content-type-options"])}`,
    `\t\tReferrer-Policy ${caddyQuote(documentHeaders["referrer-policy"])}`,
    `\t\tPermissions-Policy ${caddyQuote(documentHeaders["permissions-policy"])}`,
    "\t\t-Server",
    "\t}",
    "",
    "\t# Caddy sorts its directives by kind. Inside `route` they run as",
    "\t# written, which the caching rules and the 404s below depend on.",
    "\troute {",
    "\t\t# The last matching rule sets Cache-Control.",
    `\t\theader Cache-Control ${caddyQuote(documentHeaders["cache-control"])}`,
    `\t\theader /fonts/* Cache-Control ${caddyQuote(FONT_CACHE_CONTROL)}`,
  ];

  if (normalizedImmutableAssetPaths.length > 0) {
    lines.push(
      `\t\t@immutable path ${normalizedImmutableAssetPaths.join(" ")}`,
      `\t\theader @immutable Cache-Control ${caddyQuote(IMMUTABLE_CACHE_CONTROL)}`,
    );
  }

  lines.push(
    "",
    "\t\t# Cloudflare's own file, and its coin-icon function, which this",
    "\t\t# server does not run. The app does without the function.",
    `\t\t@notServed path /${SECURITY_HEADERS_FILE_PATH} /api/*`,
    "\t\trespond @notServed 404",
    "",
    "\t\t# /trade is trade.html. Any other page address is the app shell,",
    "\t\t# which routes in the browser. A missing file stays a 404.",
    `\t\t@file path_regexp \\.(${STATIC_FILE_EXTENSIONS.join("|")})$`,
    "\t\thandle @file {",
    "\t\t\tfile_server",
    "\t\t}",
    "\t\thandle {",
    "\t\t\ttry_files {path} {path}.html /index.html",
    "\t\t\tfile_server",
    "\t\t}",
    "\t}",
    "}",
  );

  return `${lines.join("\n")}\n`;
}
