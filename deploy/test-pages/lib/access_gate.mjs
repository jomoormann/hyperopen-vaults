// Password gate for the test deployment on Cloudflare Pages.
//
// Each tester has their own password. The KV namespace bound as TEST_ACCESS
// holds one entry per password: the key is the hex SHA-256 of the password,
// the value is a label naming whose it is. Deleting an entry revokes that
// password and no other. The plain password is stored nowhere.
//
// The browser asks with its own Basic auth prompt. The username is ignored.
// Only Web APIs are used here, so the same code runs in a Pages Function and
// under node:test.

export const REALM = "Hyperopen test";

// A password longer than this is refused before it is hashed.
export const MAX_PASSWORD_LENGTH = 256;

const UNAUTHORIZED_HEADERS = {
  "www-authenticate": `Basic realm="${REALM}", charset="UTF-8"`,
  "cache-control": "no-store",
  "content-type": "text/plain; charset=utf-8",
  "content-security-policy": "default-src 'none'; frame-ancestors 'none'",
  "x-content-type-options": "nosniff",
  "x-frame-options": "DENY",
  "referrer-policy": "no-referrer",
  "x-robots-tag": "noindex, nofollow",
};

// The password from an `Authorization: Basic …` header, or null.
export function passwordFromAuthorization(header) {
  if (typeof header !== "string") {
    return null;
  }

  const match = /^Basic\s+([A-Za-z0-9+/=]+)\s*$/i.exec(header);
  if (!match) {
    return null;
  }

  let decoded;
  try {
    const binary = atob(match[1]);
    const bytes = Uint8Array.from(binary, (char) => char.charCodeAt(0));
    decoded = new TextDecoder("utf-8", { fatal: true }).decode(bytes);
  } catch {
    return null;
  }

  const separator = decoded.indexOf(":");
  if (separator < 0) {
    return null;
  }

  const password = decoded.slice(separator + 1);
  if (password.length === 0 || password.length > MAX_PASSWORD_LENGTH) {
    return null;
  }

  return password;
}

// The KV key of a password: its SHA-256 in lowercase hex.
export async function passwordKey(password) {
  const digest = await crypto.subtle.digest(
    "SHA-256",
    new TextEncoder().encode(password),
  );

  return Array.from(new Uint8Array(digest), (byte) =>
    byte.toString(16).padStart(2, "0"),
  ).join("");
}

// The label of the password in `authorizationHeader`, or null when there is
// none or it is unknown. `lookup` reads a KV key and resolves to its value or
// null.
export async function authorizedLabel(authorizationHeader, lookup) {
  const password = passwordFromAuthorization(authorizationHeader);
  if (password === null) {
    return null;
  }

  const label = await lookup(await passwordKey(password));
  return typeof label === "string" && label.length > 0 ? label : null;
}

export function unauthorizedResponse() {
  return new Response("A password is required to open this test system.\n", {
    status: 401,
    headers: UNAUTHORIZED_HEADERS,
  });
}

// Without the KV binding nobody could be let in, so nobody is.
export function notConfiguredResponse() {
  return new Response(
    "This test system has no password store bound (TEST_ACCESS). See docs/test-deployment.md.\n",
    {
      status: 503,
      headers: {
        "cache-control": "no-store",
        "content-type": "text/plain; charset=utf-8",
        "x-content-type-options": "nosniff",
        "x-robots-tag": "noindex, nofollow",
      },
    },
  );
}
