---
owner: platform
status: draft
last_reviewed: 2026-11-12
review_cycle_days: 90
source_of_truth: false
---

# Run your own copy

This app is a static site. It has no server of its own and no accounts. You can run a copy that only your people use, on an address you control. The trading key of each trader stays in that trader's browser, behind a passkey.

Status: experimental. No release is published yet. The steps below apply from the first version tag on.

## Why run your own copy

The page that a trader opens holds the trading key while it is unlocked. Whoever serves that page decides which code runs. When you serve it yourself and pin a version, nobody else can change what your traders load.

A private address is not what protects the key. A stranger who opens your copy gets no key and no access to your account. Restricting access is still sensible, and it keeps your copy from being a public service.

## Choose the address first

A passkey belongs to the domain it was made on. If you move the app to another domain later, every trader must enable trading again, and for a contract account that means a new transaction that its signers approve.

- One trader on one machine: `http://localhost:8080`. Use `localhost`, not `127.0.0.1`.
- A team: a subdomain you own, with HTTPS. Browsers allow passkeys and wallets on HTTPS only, except on `localhost`.

## Option 1: run a release

Most teams need no copy of the code. Each version tag publishes two things that are built from the same commit:

- a container image that serves the site with the required security headers;
- the site as an archive, with the server configuration next to it.

### The container

For one machine:

```bash
docker run --rm -p 127.0.0.1:8080:8080 ghcr.io/jomoormann/hyperopen-vaults:<version>
```

Then open `http://localhost:8080/trade`.

For a team, behind your own proxy or access gateway that ends TLS: run the same image and point the proxy at port 8080.

Without a proxy, the server can get its own certificate. Give it the domain, publish ports 80 and 443, and keep its `/data` folder:

```bash
docker run -d --name trading \
  -e SITE_ADDRESS=trade.example.com \
  -p 80:80 -p 443:443 \
  -v trading-data:/data \
  ghcr.io/jomoormann/hyperopen-vaults:<version>
```

Pin a version. Do not use a moving tag.

### The archive

Unpack the archive into the folder your web server serves. The server must send the security headers, above all the content security policy. Without them the app works, but a script that does not belong to it could run on the page and use an unlocked key.

- Cloudflare Pages reads the `_headers` file in the archive.
- Caddy: use the `Caddyfile` published with the release.
- Any other server: send the same headers as that `Caddyfile`, serve `/trade` from `trade.html`, and answer page addresses that have no file with `index.html`.

To check a running copy against the headers this version expects:

```bash
npm run verify:deployment-headers -- https://trade.example.com
```

### Check what you downloaded

```bash
sha256sum -c hyperopen-vaults-<version>.sha256
gh attestation verify hyperopen-vaults-<version>.tar.gz --repo jomoormann/hyperopen-vaults
gh attestation verify oci://ghcr.io/jomoormann/hyperopen-vaults:<version> --repo jomoormann/hyperopen-vaults
```

The attestation says that GitHub built the file in this repository from the commit of that tag.

### Update

Change the version and restart. Keys and settings stay, because they live in each trader's browser under your address. Read the release notes first.

## Option 2: keep a private copy of the code

Do this only if you want to change the code.

1. Make a private repository of your own and import this one into it (GitHub: "Import repository", or clone and push). A GitHub fork of a public repository cannot be private.
2. Add this repository as a second remote, and merge its version tags when you update.
3. Set `SOURCE_URL` in `src/hyperopen/config.cljs` to where your people can get your source. The footer links there.
4. Build with `docker build`, as the `Dockerfile` shows, or with `npm ci && npm run build`. The build needs Node 22 and Java 21.

The license is AGPL-3.0. In short, and not as legal advice: if you let people outside your organization use your changed version, you must offer them its source.

## Restrict access

Put the copy behind what you already use: a VPN, or an access gateway with your company login. For one trader, bind it to `127.0.0.1` as in the first command above.

## What the app contacts

The browser talks to these hosts, and the content security policy allows no others:

| Host | When |
|---|---|
| `api.hyperliquid.xyz`, `stats-data.hyperliquid.xyz`, `app.hyperliquid.xyz` | Market data, account data, orders, coin icons |
| `rpc.hyperliquid.xyz` | HyperEVM balances and contract reads |
| `assets.entropy.trade` | Icons for markets that Hyperliquid has no icon for |
| `api.hyperunit.xyz`, `app.across.to`, `li.quest` | Only when someone starts a deposit from another chain. Not used by contract accounts. |

This fork's releases contact no analytics service and no server of the upstream project. Two things differ from the upstream site because of that:

- The portfolio optimizer reads price history from Hyperliquid directly. It is slower for many assets.
- Share cards show a letter in place of the coin icon when they are exported.

## When something goes wrong

- **A trader's device is lost, or a passkey is gone.** The key on that device cannot be used without the passkey. Enable trading on a new device. That registers a new key.
- **You think a trading key leaked.** A trading key can place orders with the whole trading balance. It cannot withdraw. Replace it at once:
  - On the device that holds the key: open the wallet menu and choose **Replace trading key**. The new key takes the old one's name, so Hyperliquid drops the old one.
  - From another device: remove the key by its name on the API wallets page. For a contract account this removal is not proven yet (see [contract-accounts.md](contract-accounts.md)).

  For a contract account both are transactions that its signers approve, so agree beforehand who signs out of hours.
- **You must take the copy offline.** Stop the server. Open orders stay on Hyperliquid. Cancel them from another running copy, for example on `localhost`.
