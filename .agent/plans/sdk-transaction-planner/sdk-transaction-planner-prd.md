# SDK-Backed Transaction Planner & UTxO Defragmentation

## Overview

Promotes `com.bloxbean.cardano:cardano-client-lib` to a production runtime dependency and introduces `TransactionPlannerService` for local, key-free UTxO coin selection, multi-asset minimum-ADA calculation, dynamic CBOR fee estimation, 16KB (`maxTxSize`) transaction size enforcement, and `unfrack.it`-inspired wallet UTxO defragmentation across JorManager's wallet and stake pool administration workflows.

## Status

- **Status:** ✅ Completed
- **Started:** 2026-11-22
- **Completed:** 2026-11-22
- **Author:** JorManager Engineering

---

## Requirements

### Functional Requirements
- [x] Elevate `cardano-client-lib` (`0.7.3`) from `testImplementation` to `implementation` on the runtime classpath
- [x] Integrate `Random-Improve` (`RandomImproveUtxoSelectionStrategy`) as the default coin selection algorithm for wallet and administrative transactions
- [x] Automatically fall back to `Largest-First` (`LargestFirstUtxoSelectionStrategy`) when primary coin selection fails to cover the target amount or hits input limits
- [x] Support an explicit `DEFRAGMENT` (`unfrack.it` model) selection mode that sweeps dust UTxOs and groups native tokens into clean outputs up to the 16KB transaction size limit
- [x] Programmatically compute multi-asset minimum-ADA (`MinAdaCalculator`) and dynamic transaction fees locally without remote `cardano-cli` dry-runs
- [x] Build transaction inputs and outputs locally from live `LedgerDao` + `TransactionCache` UTxOs while continuing to emit standard `cardano-cli` signing/submission formats without private key access during planning
- [x] Validate all generated transactions against Cardano's `maxTxSize` (16KB) protocol limit prior to node dispatch
- [x] Expose "Defragment UTxOs" workflow in `Wallet.vue` and `SendAdaModal.vue`
- [x] Ensure UTxO selection and fee planning completes in `< 500ms` for wallets with up to 1,000 UTxOs

---

## Technical Design

### Components Affected
- `build.gradle.kts`: Runtime `cardano-client-lib` dependency
- `TransactionPlannerService.kt`: Core coin selection (`STANDARD` & `DEFRAGMENT`), `MinAdaCalculator`, CBOR dynamic fee convergence, and `maxTxSize` validation
- `SelectionMode.kt` / `TransactionPlan.kt` / `CalculateFeeRequest.kt` / `CalculateFeeResponse.kt` / `SubmitTransactionRequest.kt` / `ProtocolParameters.kt`: Request/response and plan models
- `WalletController.kt`: Refactor `calculateTxFee`, `submitTransaction`, and `updateStakingAddress`
- `NodeController.kt`: Refactor `createNode`, `updatePoolConfig`, `updateMetadata`, `editRelays`, `retirePool`, and `submitGovernanceVote`
- `Wallet.vue` / `SendAdaModal.vue` / `jormanager.ts` / `types/index.ts`: UI workflow and selection mode controls for "Defragment UTxOs"

### Backend Changes
- `TransactionPlannerService` wraps an in-memory `UtxoSupplier` fed by `WalletUtils.getUtxos` (`LedgerDao` + `TransactionCache`), runs `RandomImproveUtxoSelectionStrategy` with automatic fallback to `LargestFirstUtxoSelectionStrategy` in `STANDARD` mode, and runs dust-first + policy-grouped token bundling bounded by `maxTxSize` in `DEFRAGMENT` mode.
- Eliminates remote `/tmp/dummy-*.txbody`, `transaction calculate-min-fee`, and `transaction calculate-min-required-utxo` CLI calls.
- Validates both estimated signed CBOR size during planning and actual signed CBOR envelope size prior to `transaction submit`.

### Frontend Changes
- `Wallet.vue`: Adds a "Defragment UTxOs" action button on wallet rows with multiple UTxOs, opening `SendAdaModal` pre-configured for self-consolidation in `DEFRAGMENT` mode.
- `SendAdaModal.vue`: Adds Coin Selection Mode toggle (`Standard (Random-Improve)` vs `Defragment UTxOs (Unfrack)`) and displays live consolidation metrics (`inputCount`, `outputCount`, `estimatedTxSize`).

---

## Implementation Steps

1. Update `build.gradle.kts` and add `SelectionMode`, `TransactionPlan`, and request/response model fields.
2. Implement `TransactionPlannerService` with `STANDARD` (`Random-Improve` + `Largest-First` fallback), `DEFRAGMENT` (`unfrack.it` consolidation), `MinAdaCalculator`, iterative CBOR fee calculation, and 16KB guardrails.
3. Refactor `WalletController.kt` and `NodeController.kt` to use `TransactionPlannerService` and pre-dispatch 16KB validation.
4. Implement Vue UI "Defragment UTxOs" workflow in `Wallet.vue`, `SendAdaModal.vue`, and `jormanager.ts`.
5. Verify with Kotlin JUnit tests (including 1,000-UTxO `< 500ms` benchmark), Vue Vitest suite, `bootJar` build, and UI screenshots.

## Testing Strategy

- Unit tests in `TransactionPlannerServiceTest` for `Random-Improve`, `Largest-First` fallback, `DEFRAGMENT` consolidation, multi-asset min-ADA, dynamic fee convergence, 16KB size enforcement, zero private key access, and `< 500ms` latency for 1,000 UTxOs.
- Controller tests in `WalletControllerPlannerTest` and `NodeControllerPlannerTest` verifying zero CLI dry-run fee queries and selective input selection across wallet and SPO administrative endpoints.
- Frontend Vitest specs in `WalletDefragment.spec.ts` and `jormanager.spec.ts`.

## Rollout Plan

Ships in the standard Spring Boot fat JAR (`bootJar`) with backward-compatible WebSocket payloads (`selectionMode` defaults to `"STANDARD"`).

## Status Log

- `2026-11-22`: Promoted `cardano-client-lib:0.7.3` to `implementation` and added `SelectionMode`, `TransactionPlan`, and backward-compatible fee/submit model fields.
- `2026-11-22`: Implemented `TransactionPlannerService` with `RandomImproveUtxoSelectionStrategy`, automatic `LargestFirstUtxoSelectionStrategy` + net-unlocked-lovelace fallback, `unfrack.it`-style `DEFRAGMENT` consolidation, `MinAdaCalculator`, dynamic CBOR `FeeCalculationServiceImpl` convergence, and 16KB size validation.
- `2026-11-22`: Refactored `WalletController` and `NodeController` (all 9 transaction endpoints), added Vue "Defragment UTxOs" workflow in `Wallet.vue` and `SendAdaModal.vue`, and verified all JUnit, Vitest, `< 500ms` 1,000-UTxO benchmark, and `bootJar` checks.

## Open Questions

None.
