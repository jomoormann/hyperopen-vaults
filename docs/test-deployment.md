---
owner: platform
status: draft
last_reviewed: 2026-11-17
review_cycle_days: 90
source_of_truth: false
---

# Test deployment

A copy of this fork runs on Cloudflare Pages for testing, behind a password: https://hyperopen-vaults.pages.dev. It is for the people who test the fork, not a public service.

## What it does

- Every push to `main` deploys the release build to https://hyperopen-vaults.pages.dev. Every push to another branch deploys to that branch's own preview address, which the workflow prints in its summary. `.github/workflows/test-deploy.yml` does this.
- Every page asks for a password first, with the browser's own prompt. The username is ignored; any name works.
- Each tester gets their own password. Revoking one does not affect anyone else.
- Only the release's static files (scripts, styles, fonts, images) are served without the password, each listed by its exact path. Everything else asks for it, including any path with no file, which Pages would otherwise answer with the app. The code is public on GitHub anyway, and without a page nobody can use the app on this address. This also keeps the free plan's Function requests to one or two per page load.
- Each preview address is its own origin. A trading key enabled on one address is not available on another, and must be enabled again there.

## How it works

- `deploy/test-pages/functions/_middleware.js` is the password gate, with its logic in `deploy/test-pages/lib/access_gate.mjs`.
- The passwords live in the Workers KV namespace `hyperopen-vaults-test-access`, bound as `TEST_ACCESS`. Each entry's key is the SHA-256 of a password and its value names whose it is. The password itself is stored nowhere.
- `npm run test-access:stage` puts the Pages project together in `out/test-pages` next to the release in `out/release-public`, and writes the release's `_routes.json`, which lists the static files that skip the gate (at most 100 rules; staging fails beyond that). Pages only reads a configuration and functions from the directory the deploy runs in, so the public release and self-hosted copies (see [self-hosting.md](self-hosting.md)) never get the gate.
- If the password store is not bound, every page answers 503, never the app.
- After each deploy the workflow checks that `/trade` and a missing file under `/js/` ask for a password and `/robots.txt` does not.

## Setup, once

This is done for the current account. For another account:

1. Create a Pages project with production branch `main` (`npx wrangler pages project create <name> --production-branch main`) and a KV namespace (`npx wrangler kv namespace create <name>`).
2. Set the repository variables `CF_PAGES_PROJECT` and `TEST_ACCESS_KV_ID` to the project name and the namespace id. Without them, the defaults in `tools/test-access/stage.mjs` are used.
3. Set the repository secrets `CLOUDFLARE_API_TOKEN` (an API token allowed to edit Cloudflare Pages and Workers KV) and `CLOUDFLARE_ACCOUNT_ID`. Without them the workflow deploys nothing and says so.

## Passwords

Run these from the repository root. They need `npx wrangler login`, or `CLOUDFLARE_API_TOKEN` and `CLOUDFLARE_ACCOUNT_ID` in the environment.

    npm run test-access:add -- team 'the password'   # adds a password, labelled team
    npm run test-access:list              # lists the labels
    npm run test-access:revoke -- team    # deletes the passwords labelled team

Only the password's SHA-256 hash goes to Cloudflare. A new password works within a minute or so, and a revoked one stops working within a minute or so, because Cloudflare caches KV reads briefly at the edge. Nothing is redeployed.

## Trying it locally

    npm run build
    npm run test-access:stage
    cd out/test-pages
    npx wrangler@4.156.2 kv key put <sha256-of-a-password> me --binding TEST_ACCESS --local
    npx wrangler@4.156.2 pages dev --kv TEST_ACCESS=53d0df25368d43489527c1d6a8119482

`wrangler pages dev` does not take the KV binding from `wrangler.toml`, so the `--kv` flag names it, with the namespace id the local store was filled under.
