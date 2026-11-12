---
owner: wallet
status: draft
last_reviewed: 2026-11-12
review_cycle_days: 90
source_of_truth: false
---

# Contract accounts

This fork lets a contract account on HyperEVM trade on Hyperliquid. A Safe is the usual example. Upstream Hyperopen serves wallets that sign with their own key; nothing changes for them.

Status: experimental. It is tested against a stand-in wallet and a real passkey lockbox. It has not yet run with a real vault on mainnet.

## Why it needs its own path

Hyperliquid checks the signature of an account's own actions (approve a trading key, move funds) with ECDSA. A contract has no key and cannot produce one. It reaches Hyperliquid through the CoreWriter contract on HyperEVM instead: an ordinary transaction whose sender becomes the Hyperliquid account of the action.

Whoever stands behind the contract decides whether a transaction goes through. For a Safe these are its owners or a role. That can take hours or days.

## What the app does for a contract account

The app treats the connected account as a contract account when the wallet is on HyperEVM and the address has code there. An EIP-7702 delegation does not count.

**Enable Trading**

1. The app makes a trading key (a Hyperliquid API wallet) and stores it behind a passkey. This comes first, because a passkey prompt needs a focused page and the approval may arrive much later.
2. It checks that the account has a free named-key slot. Hyperliquid allows three and drops a fourth without an error.
3. It sends one transaction to CoreWriter that registers the key for 90 days.
4. It asks Hyperliquid whether the key is registered until it is. There is no time limit. The wait survives a reload.

The wallet is never asked for a signature. The wallet menu shows the key address while the registration waits, so the signers can compare it with what they sign.

**The trading key**

- It stays on the device behind the passkey. The passkey cannot be turned off for a contract account.
- It is kept when the wallet disconnects or switches account.
- It locks after 30 minutes without input.
- The app uses it for orders, cancels and margin settings only. Hyperliquid itself does not limit a trading key, so this is a guard in the app, not a security boundary.
- The wallet menu can replace it at any time, and asks for a renewal from 14 days before the expiry. Trading pauses until the new key is registered. If the replacement is refused or cancelled, the old key comes back.
- A waiting registration can be cancelled, which removes the stored key.

**Funds**

The app moves USDC only between the account's own balances. Each move is a transaction through the wallet:

- HyperEVM to Spot: an approval for exactly the amount, then a deposit through Circle's CoreDepositWallet.
- Spot to Perps and back: CoreWriter action 7.
- Spot to HyperEVM: CoreWriter action 13 to USDC's system address. Every field but the amount is fixed.

Every other move is refused: other tokens, sends to another address, withdrawals to another chain, staking.

## Wallets that queue transactions

A contract wallet may take a transaction without a hash that will ever appear on chain. The app never follows a hash for these flows. It asks Hyperliquid for the result, or closes the dialog once the wallet has the transaction.

Zodiac Pilot answers a transaction on a chain it cannot simulate with the error "Pilot added this transaction to the batch without simulating it". The app reads that as a transaction the wallet took.

## Not proven yet

- A full run with a real vault on mainnet.
- Removing a trading key through CoreWriter (registering the zero address under the key's name).
- The Spot to HyperEVM move for a contract account.

## Where the code is

- `src/hyperopen/wallet/contract_account.cljs`: detection, and sending a contract account's transactions.
- `src/hyperopen/wallet/vault_registration.cljs`: register, resume, cancel and remove a key.
- `src/hyperopen/wallet/registration_record.cljs`, `key_backup.cljs`, `idle_lock.cljs`: the record of a registration, the key set aside during a renewal, the idle lock.
- `src/hyperopen/hyperevm/domain/core_writer.cljs`: the CoreWriter calldata, with test vectors.
- `src/hyperopen/api/trading/user_actions.cljs`, `agent_actions.cljs`: what a contract account may send and what its key may sign.
- `tools/playwright/test/contract-account-enable-trading.spec.mjs` and the `(v1)` to `(v3)` cases in `funding-transfer-hyperevm.spec.mjs`: browser tests.
