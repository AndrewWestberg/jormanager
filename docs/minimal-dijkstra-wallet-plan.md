# Restore the minimal wallet on Dijkstra

## Goal

Restore wallet behavior from the branch point, `develop` at `fb09aa59cbcbac215b89308a50691343bff8e56b`, retaining only the changes needed for correct Dijkstra transactions. This is a removal plan, not authorization to change the running database or submit transactions.

## Verified baseline

The pre-branch source already had:

- One Balance column, rewards, native assets, wallet creation/import, backup and deletion.
- ADA/native-asset sends, multiple recipients, percentage amounts, metadata, reward claims and multi-claim.
- Stake registration and deregistration with a selectable fee account and spending-password confirmation.
- `/wallet`, `/calculatefee`, `/submittransaction` and `/updatestakingaddress` routes.
- An indexed UTxO balance, with `LedgerDao.queryLiveUtxos` adding in-memory pending change and subtracting pending inputs.
- Local balance updates **after the CLI submission succeeded**, not durable input reservations before submission.
- A six-hour transaction validity interval, an in-memory `TransactionCache`, transaction-ID history, and rollback resubmission of cached previously accepted transactions.

These are not new Dijkstra features. Do not remove multi-claim, pending-change behavior, ordinary transaction validity or the existing ledger just to remove the new reservation framework.

## Keep

- Dijkstra-aware CLI era selection and transaction/block decoding needed by the wallet and its existing ledger.
- Correct certificate commands, registration deposits and deregistration refunds.
- Fee/minimum-output calculations, reference-script fees, native-asset conservation and valid change outputs.
- Exact integer amounts; restoring the old UI does not require restoring JavaScript precision loss.
- Spending-password checks, encrypted keys, safe CLI argument handling, protected temporary signing files and cleanup.
- Basic request validation, command errors, and the existing single-submission locking behavior.
- Unrelated node operation, chain synchronization and necessary Leios support.

## Remove

- Wallet participation in `ReviewedWalletOperations` and the `ReviewedWalletController` preview/signing/uncertain-submission lifecycle.
- Persistent input and reward reservations, expiry reconciliation and reservation-dependent balances.
- `PendingWalletState` mempool queries, recursive pending-transaction eligibility and database/node snapshot merging.
- Wallet authorization fingerprints, generation comparisons and repeated point-matched observations.
- Persistent protocol snapshots as a prerequisite for ordinary wallet transactions. Query the parameters needed for the transaction instead.
- Added wallet readiness/review state in the frontend; retain ordinary connection/form/busy checks.
- The recently added On-chain balance / Available to spend split and `paymentAddrConfirmedLovelace` plumbing. Restore one sortable Balance column.
- Branch-only wallet controls such as the standalone Delegate button when restoring exact pre-branch functionality.
- Tests and documentation whose sole contract is the removed machinery. No compatibility endpoints or dormant alternate wallet implementation.

## Implementation order

### 1. Restore the ordinary wallet transaction path

Restore the original routes and form/password/submission sequence in `WalletController.kt`, `Wallet.vue`, `SendAdaModal.vue` and the wallet portions of `jormanager.ts`.

Use the existing transaction-building code where it supplies necessary Dijkstra correctness; decouple it from `ReviewedWalletOperations.CliBody` and authorization records. Keep fee estimation and submission consistent without storing a review or requiring a second approval.

A successful CLI submission updates the live wallet cache and transaction-ID history. A rejected submission reports the error and makes no local balance deduction. An interrupted command reports failure/unknown outcome without creating a persistent account lock or an automatic retry job.

### 2. Restore baseline balance behavior

Replace `PendingWalletState` with the old success-only, in-memory live UTxO behavior, adapted to the current Dijkstra decoder and retained ledger.

Update `WalletUtils`, `LedgerDao`, `WalletMonitor`, `WalletItem` and frontend types together. Preserve pending change after acknowledged submission; do not treat a failed attempt as a spend. Remove the extra confirmed-balance field and both replacement columns.

Keep ordinary ledger synchronization, but stop making wallet actions depend on the added authorization/readiness generation protocol. Era selection should obtain the era from the configured node, not require the added process/socket-provenance approval machinery.

### 3. Unhook shared callers before deleting services

`ReviewedWalletOperations`, `PendingWalletState` and `authorized_operations` are also used by governance, pool registration/retirement, pool creation and deployment. They cannot simply be deleted underneath those callers.

Migrate those transaction entry points to their existing direct build/sign/submit behavior as necessary to remove the shared reservation framework. Preserve required pool certificates, Dijkstra/Leios key handling and existing provisioning functionality. Replace dependencies on authorization records with the minimal submission result those callers need; do not introduce another approval/state-machine abstraction.

Remove operation-confirmation hooks from chain ingestion once no caller needs them. Retain chain decoding/indexing used by the wallet, blocks and Leios; do not turn this into a wholesale chain-index rewrite.

### 4. Cut over existing installations

- Stop the application during the cutover so the old process cannot create new reservations.
- Back up the database before applying a forward migration; do not edit already-applied Liquibase changesets.
- Preserve wallet IDs, encrypted keys, addresses, hosts/nodes, configuration, transaction history and required chain records.
- Preserve the removed authorization records in the backup; remove their active reservation effect and drop unused authorization tables only after their final callers are gone.
- Never replay the stored signed transactions during migration. Do not relabel an uncertain transaction as confirmed or proven never submitted.
- Rebuild live wallet state from the retained ledger/current node observations. Existing failed attempts must no longer suppress the account balance or survive restart as spend locks.

The current `funds` reservation is handled by this cutover, not a one-off database deletion while the old code remains active.

### 5. Verify behavior, not the removed machinery

Retain or adapt tests for:

- Conway and Dijkstra ADA/native-asset sends, change and fees.
- Multiple recipients, percentage amounts, metadata and multi-claim.
- Stake registration/deregistration with the same account or another account paying fees; correct deposit/refund accounting.
- A rejected registration leaving funds spendable, including after restart.
- Successful submission immediately updating pending inputs/change, then converging on confirmation without double counting.
- Existing rollback behavior for acknowledged submissions, without importing uncertain legacy attempts into the retry cache.
- Exact amounts, password rejection and signing-key cleanup.

Remove reservation/review-specific tests, while retaining unrelated Dijkstra decoding and chain tests. Verify the actual wallet UI has the original controls and one Balance column. Run transaction smoke checks only on an explicitly authorized test network.

## Acceptance criteria

1. The wallet looks and behaves like the pre-branch wallet, including multi-claim and immediate change after successful submission.
2. Dijkstra support does not require durable reviews, input reservations, mempool polling or generation approvals.
3. A failed registration cannot leave `funds` unavailable because of a local persisted operation record.
4. Restarting does not restore obsolete spend locks or rebroadcast the old uncertain registration.
5. Existing keys, wallet entries, history and unrelated node/Leios functionality remain intact.

## Tradeoff

Without persistent reservations, command timeouts and competing spends return to the pre-branch model: a transaction can reach the node despite a lost response, and conflicting spends may be rejected by the node. Basic locking and error reporting remain; no replacement reconciliation framework is added. Removing a local reservation cannot revoke transaction bytes that were already signed or broadcast.

## Status

Implemented: direct wallet/shared submissions, success-only live change, baseline wallet controls, and forward migration 047. Wallet reads use current node UTxOs rather than depending on indexed authorization snapshots; the existing chain index remains intact. Verified with focused backend/frontend tests, disposable PostgreSQL migrations, and fixture-backed browser interactions. Not deployed; no live database or node transaction was changed.
