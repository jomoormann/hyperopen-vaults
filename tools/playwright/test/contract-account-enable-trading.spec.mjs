// Enable Trading for a contract account (a Safe, for example), end to end
// against this build.
//
// A contract account cannot sign `approveAgent`, so the app registers its
// trading key with a HyperEVM transaction to CoreWriter and waits until
// Hyperliquid reports the key as the account's agent.
//
// Every case runs the real app with:
// - the debug wallet simulator on HyperEVM, which logs every EIP-1193
//   request (`walletSimulatorSnapshot`);
// - a mocked HyperEVM RPC whose `eth_getCode` decides whether the account
//   is a contract;
// - fixture HyperCore info reads, where `userRole` of the new key says
//   whether Hyperliquid has registered it;
// - a virtual WebAuthn authenticator with the PRF extension, so the real
//   passkey lockbox code encrypts and stores the key (no lockbox mock).
//
// WebAuthn needs a domain as its relying-party id, so these cases load the
// app from `localhost`, not from the default `127.0.0.1`.
import { expect, test } from "../support/guarded_test.mjs";
import {
  debugCall,
  dispatch,
  expectOracle,
  visitRoute
} from "../support/hyperopen.mjs";
import {
  clearinghouseState,
  hyperCoreInfoFixture,
  hyperEvmFixture,
  routeHyperCoreAccountStreams,
  routeHyperCoreInfo,
  routeHyperEvmRpc
} from "../support/hyperevm_fixtures.mjs";

const VAULT = "0x0eef159d0f5fe7d186bd105fce15b46c422141cf";
const HYPEREVM = "0x3e7";
const CORE_WRITER = "0x3333333333333333333333333333333333333333";
const SEND_RAW_ACTION = "0x17938e13";
const ADD_API_WALLET = "01000009";
// A Safe proxy's runtime code starts like this; any non-empty code will do.
const CONTRACT_CODE = "0x608060405273ffffffffffffffffffffffffffffffffffffffff600054167fa619486e";
const OK = { status: "ok", response: { type: "default" } };
const KEY_STORAGE_REFUSAL =
  "A contract account's trading key stays on this device behind a passkey.";

const baseURL = (process.env.PLAYWRIGHT_BASE_URL || "http://127.0.0.1:8080").replace(
  "127.0.0.1",
  "localhost"
);

test.use({ baseURL });

async function storeValue(page, path) {
  return page.evaluate((segments) => {
    const c = globalThis.cljs.core;
    const state = c.deref(globalThis.hyperopen.system.store);
    const value = c.get_in(
      state,
      c.PersistentVector.fromArray(segments.map((segment) => c.keyword(segment)), true)
    );
    return c.clj__GT_js(value);
  }, path);
}

async function addPasskeyAuthenticator(page) {
  const cdp = await page.context().newCDPSession(page);
  await cdp.send("WebAuthn.enable");
  await cdp.send("WebAuthn.addVirtualAuthenticator", {
    options: {
      protocol: "ctap2",
      ctap2Version: "ctap2_1",
      transport: "internal",
      hasResidentKey: true,
      hasUserVerification: true,
      isUserVerified: true,
      hasPrf: true,
      automaticPresenceSimulation: true
    }
  });
  return cdp;
}

// Route everything, open /trade and connect `VAULT` on HyperEVM. `code` is
// what `eth_getCode` answers for it.
async function connectAccount(page, { code, wallet = {}, exchange = {} }) {
  const fixture = hyperEvmFixture({ owner: VAULT, holdings: { HYPE: "1" } });
  fixture.code = { [VAULT]: code };
  const rpc = await routeHyperEvmRpc(page, fixture);
  const info = hyperCoreInfoFixture();
  info.spotStates[VAULT] = { balances: [] };
  info.clearinghouseStates[VAULT] = clearinghouseState("5000.0");
  info.userRoles[VAULT] = { role: "user" };
  await routeHyperCoreInfo(page, info);
  await routeHyperCoreAccountStreams(page);
  await visitRoute(page, "/trade");
  await addPasskeyAuthenticator(page);
  await debugCall(page, "installWalletSimulator", {
    accounts: [VAULT],
    requestAccounts: [VAULT],
    chainId: HYPEREVM,
    ...wallet
  });
  await debugCall(page, "installExchangeSimulator", {
    signedActions: { default: { responses: Array.from({ length: 8 }, () => OK) } },
    ...exchange
  });
  await debugCall(page, "setWalletConnectedHandlerMode", "suppress");
  await dispatch(page, [":actions/connect-wallet"]);
  await expectOracle(page, "wallet-status", {
    connected: true,
    address: VAULT,
    chainId: HYPEREVM,
    agentStatus: "not-ready"
  });
  return { rpc, info };
}

const walletLog = (page) => debugCall(page, "walletSimulatorSnapshot");

const methodsOf = (snapshot) => snapshot.requests.map((request) => request.method);

// The agent address inside CoreWriter `sendRawAction(addApiWallet(...))`
// calldata: selector, offset word, length word, 4 action bytes, address word.
function agentAddressOf(calldata) {
  const body = calldata.slice(2);
  const action = body.slice(8 + 64 + 64);
  expect(action.slice(0, 8)).toBe(ADD_API_WALLET);
  return `0x${action.slice(8 + 24, 8 + 64)}`;
}

// The UTF-8 name inside the same calldata.
function agentNameOf(calldata) {
  const action = calldata.slice(2).slice(8 + 64 + 64);
  const fields = action.slice(8);
  const length = Number.parseInt(fields.slice(128, 192), 16);
  return Buffer.from(fields.slice(192, 192 + length * 2), "hex").toString("utf8");
}

async function browserKeyStorage(page) {
  return page.evaluate(async (vault) => {
    const local = Object.fromEntries(
      Object.keys(localStorage).map((key) => [key, localStorage.getItem(key)])
    );
    const session = Object.fromEntries(
      Object.keys(sessionStorage).map((key) => [key, sessionStorage.getItem(key)])
    );
    const databases = await indexedDB.databases();
    const records = [];
    for (const { name } of databases) {
      const db = await new Promise((resolve, reject) => {
        const request = indexedDB.open(name);
        request.onsuccess = () => resolve(request.result);
        request.onerror = () => reject(request.error);
      });
      for (const storeName of db.objectStoreNames) {
        const all = await new Promise((resolve, reject) => {
          const request = db.transaction(storeName, "readonly").objectStore(storeName).getAll();
          request.onsuccess = () => resolve(request.result);
          request.onerror = () => reject(request.error);
        });
        records.push({ db: name, store: storeName, values: all });
      }
      db.close();
    }
    return {
      plainSession: local[`hyperopen:agent-session:v1:${vault}`] ?? null,
      plainSessionInTab: session[`hyperopen:agent-session:v1:${vault}`] ?? null,
      passkeyMetadata: local[`hyperopen:agent-passkey-session:v1:${vault}`] ?? null,
      everything: JSON.stringify({ local, session, records })
    };
  }, VAULT);
}

test("a contract account registers its trading key through CoreWriter and keeps it behind a passkey @regression", async ({ page }) => {
  test.setTimeout(90_000);
  const { info } = await connectAccount(page, { code: CONTRACT_CODE });

  await dispatch(page, [":actions/enable-agent-trading"]);

  // The registration goes out as one HyperEVM transaction.
  await expect
    .poll(async () => (await walletLog(page)).transactions.length, { timeout: 20_000 })
    .toBe(1);
  const afterSend = await walletLog(page);
  const [sent] = afterSend.requests
    .filter((request) => request.method === "eth_sendTransaction")
    .map((request) => request.params[0]);
  expect(sent.from.toLowerCase()).toBe(VAULT);
  expect(sent.to).toBe(CORE_WRITER);
  expect(sent.chainId).toBe(HYPEREVM);
  expect(sent.data.startsWith(SEND_RAW_ACTION)).toBe(true);
  const agentAddress = agentAddressOf(sent.data);
  expect(agentAddress).toMatch(/^0x[0-9a-f]{40}$/);
  const [, label, validUntil] = agentNameOf(sent.data).match(/^(.+) valid_until (\d+)$/);
  expect(label).toMatch(/^Hyperopen [0-9a-f]{6}$/);
  const days = (Number(validUntil) - Date.now()) / (24 * 60 * 60 * 1000);
  expect(days).toBeGreaterThan(89.9);
  expect(days).toBeLessThan(90.1);

  // The wallet was never asked for a signature.
  expect(methodsOf(afterSend).filter((method) => method.startsWith("eth_signTypedData"))).toEqual([]);

  // Hyperliquid has not registered the key yet: trading stays off, and the
  // key is already stored behind the passkey.
  await expect
    .poll(() => storeValue(page, ["wallet", "agent", "vault-registration", "step"]))
    .toBe("hyperliquid");
  expect(await storeValue(page, ["wallet", "agent", "status"])).toBe("approving");
  expect((await browserKeyStorage(page)).passkeyMetadata).not.toBeNull();

  // Hyperliquid applies the registration.
  info.userRoles[agentAddress] = { role: "agent", data: { user: VAULT } };
  await expectOracle(
    page,
    "wallet-status",
    { connected: true, agentStatus: "ready", agentAddress, agentError: null },
    { timeoutMs: 20_000 }
  );
  expect(await storeValue(page, ["wallet", "agent", "local-protection-mode"])).toBe("passkey");
  expect(await storeValue(page, ["wallet", "agent", "storage-mode"])).toBe("local");
  expect(await storeValue(page, ["wallet", "agent", "vault-registration"])).toBeNull();

  // Nothing was posted to Hyperliquid as an approval, and exactly one
  // transaction was sent.
  const exchange = await debugCall(page, "exchangeSimulatorSnapshot");
  expect(exchange.calls.filter((call) => call.matchedPath?.[0] === "approveAgent")).toEqual([]);
  const finalLog = await walletLog(page);
  expect(finalLog.transactions.length).toBe(1);
  expect(finalLog.unknownRequests).toEqual([]);

  // The private key is nowhere in browser storage in the clear: no plain
  // session, and the stored lockbox record holds ciphertext only.
  const storage = await browserKeyStorage(page);
  expect(storage.plainSession).toBeNull();
  expect(storage.plainSessionInTab).toBeNull();
  const metadata = JSON.parse(storage.passkeyMetadata);
  expect(JSON.stringify(metadata)).toContain(agentAddress);
  expect(storage.everything).toContain("ciphertext");
  expect(storage.everything).not.toMatch(/private-key|privateKey/);
  expect(storage.everything).not.toMatch(/0x[0-9a-f]{64}(?![0-9a-f])/i);

  // The passkey cannot be turned off for this account.
  await dispatch(page, [":actions/set-agent-local-protection-mode", ":plain"]);
  await expect
    .poll(() => storeValue(page, ["wallet", "agent", "error"]))
    .toBe(KEY_STORAGE_REFUSAL);
  expect(await storeValue(page, ["wallet", "agent", "local-protection-mode"])).toBe("passkey");
  expect(await storeValue(page, ["wallet", "agent", "status"])).toBe("ready");
  expect((await browserKeyStorage(page)).plainSession).toBeNull();
});

test("a refused registration leaves no key behind @regression", async ({ page }) => {
  await connectAccount(page, {
    code: CONTRACT_CODE,
    wallet: { sendTransactionErrors: [4001] }
  });

  await dispatch(page, [":actions/enable-agent-trading"]);

  await expectOracle(
    page,
    "wallet-status",
    { connected: true, agentStatus: "error", agentAddress: null },
    { timeoutMs: 20_000 }
  );
  expect(await storeValue(page, ["wallet", "agent", "error"])).toMatch(/rejected/i);
  const storage = await browserKeyStorage(page);
  expect(storage.plainSession).toBeNull();
  expect(storage.passkeyMetadata).toBeNull();
  expect(storage.everything).not.toContain("ciphertext");
  expect((await walletLog(page)).transactions.length).toBe(0);
});

test("an ordinary wallet on HyperEVM still signs approveAgent @regression", async ({ page }) => {
  await connectAccount(page, {
    code: "0x",
    exchange: { approveAgent: { responses: [{ status: "ok" }] } }
  });

  await dispatch(page, [":actions/enable-agent-trading"]);

  await expectOracle(
    page,
    "wallet-status",
    { connected: true, agentStatus: "ready", agentError: null },
    { timeoutMs: 20_000 }
  );
  const log = await walletLog(page);
  expect(methodsOf(log).filter((method) => method.startsWith("eth_signTypedData")).length).toBe(1);
  expect(log.transactions.length).toBe(0);
  const exchange = await debugCall(page, "exchangeSimulatorSnapshot");
  expect(exchange.calls).toEqual(
    expect.arrayContaining([
      expect.objectContaining({ matchedPath: ["approveAgent"], responseStatus: "ok" })
    ])
  );
  expect(await storeValue(page, ["wallet", "agent", "local-protection-mode"])).toBe("plain");
});
