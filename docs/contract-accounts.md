---
owner: wallet
status: draft
last_reviewed: 2026-11-12
review_cycle_days: 90
source_of_truth: false
---

# Contract accounts

This fork lets a contract account on HyperEVM trade on Hyperliquid. A Safe is the usual example. Upstream Hyperopen serves wallets that sign with their own key; nothing changes for them.

Status: experimental. It is tested against a stand-in wallet and a real passkey lockbox, and has run end to end with a test Safe on HyperEVM mainnet through Zodiac Pilot, with small amounts: registering the trading key, an order and its close, a USDC deposit from HyperEVM, perps and spot, a withdrawal to HyperEVM, the direct perps routes and moving HYPE to staking.

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
- The app signs with it only the actions the app itself sends: orders, cancels, margin settings, sub-accounts, vault deposits and withdrawals, borrow and lend, referrals and rewards, and merging outcome shares. Any other action type is refused until it is added on purpose (`agent_actions.cljs`). Hyperliquid itself does not limit a trading key, so this is a guard in the app, not a security boundary.
- The wallet menu can replace it at any time, and asks for a renewal from 14 days before the expiry. Trading pauses until the new key is registered. If the replacement is refused or cancelled, the old key comes back.
- A waiting registration can be cancelled, which removes the stored key.

**Funds**

The app moves funds only between the account's own balances. Each move is a transaction through the wallet:

- HyperEVM to Spot: for USDC an approval for exactly the amount, then a deposit through Circle's CoreDepositWallet; for HYPE the amount as value to HYPE's system address; for another linked token a transfer to its system address. Each credits the sender.
- Spot to Perps and back: CoreWriter action 7.
- Perps to HyperEVM in one step, USDC only: action 7 (Perps to Spot) and action 13 to USDC's system address, sent as one batch.
- HyperEVM to Perps in one step, USDC only: the approval and a CoreDepositWallet deposit into Perps (destination dex 0), sent as one batch. Ordinary wallets keep going through Spot both ways.
- Spot to HyperEVM, any linked token: CoreWriter action 13 to the token's system address, from Spot to Spot. HyperCore pays it out to the sender's own HyperEVM address.
- Between the account's own balances, or to and from its own sub-accounts: CoreWriter action 13 with the account or one of its own sub-accounts as the destination. Named perp dexes are not mapped yet.
- Staking: Spot to staking (action 4), staking to Spot (action 5), delegate and undelegate (action 3). Delegating more than the staking balance moves the rest from Spot first, in the same batch (action 4, then action 3).

When the wallet runs batches atomically (EIP-5792 `wallet_getCapabilities`, which Zodiac Pilot answers), two or more transactions go as one `wallet_sendCalls`, so the account signs and executes them as one transaction. Other wallets get them one after the other.

`user_actions.cljs` decides what a `sendAsset` may name before anything reaches the wallet: the token must match the spot metadata exactly (name and token id), and the destination must be the token's system address, the account itself, or a sub-account whose master is the account.

Every other move is refused: sends to another address and withdrawals to another chain. For a contract account, Deposit, Withdraw and Send open Transfer instead of their own dialogs, and authorizing or removing other API wallets is refused.

**The account needs a little HYPE on Spot.** HyperCore charges every move from Spot to HyperEVM a fee of 200,000 gas at the next HyperEVM block's base fee, paid in Spot HYPE (a few hundred-thousandths of a HYPE). Until the account holds some, the Transfer dialog refuses the move with "You need about … HYPE on Spot to pay the HyperEVM transfer fee." Fund it once with about 0.001 HYPE, which covers dozens of withdrawals. Either send HYPE to the account's address on Hyperliquid from any account that holds it, or send HYPE to the account on HyperEVM and move it with HyperEVM to Spot. A role that only allows USDC moves (for example the Hyperliquid presets of `@zodiaceco/presets`) cannot do the second, so do it as an owner of the account.


## Wallets that queue transactions

A contract wallet may take a transaction without a hash that will ever appear on chain. The app never follows a hash for these flows. It asks Hyperliquid for the result, or closes the dialog once the wallet has the transaction.

Zodiac Pilot answers a transaction on a chain it cannot simulate with the error "Pilot added this transaction to the batch without simulating it". The app reads that as a transaction the wallet took.

## Not proven yet

- Removing a trading key through CoreWriter (registering the zero address under the key's name).
- Deposits of HYPE and other linked tokens from HyperEVM, and withdrawals of tokens other than USDC.
- Moves to and from sub-accounts and between named perp dexes.
- Unstaking and the way back from staking to spot.

## Where the code is

- `src/hyperopen/wallet/contract_account.cljs`: detection, and sending a contract account's transactions.
- `src/hyperopen/wallet/vault_registration.cljs`: register, resume, cancel and remove a key.
- `src/hyperopen/wallet/registration_record.cljs`, `key_backup.cljs`, `idle_lock.cljs`: the record of a registration, the key set aside during a renewal, the idle lock.
- `src/hyperopen/hyperevm/domain/core_writer.cljs`: the CoreWriter calldata, with test vectors.
- `src/hyperopen/api/trading/user_actions.cljs`, `agent_actions.cljs`: what a contract account may send and what its key may sign.
- `tools/playwright/test/contract-account-enable-trading.spec.mjs` and the `(v1)` to `(v3)` cases in `funding-transfer-hyperevm.spec.mjs`: browser tests.
