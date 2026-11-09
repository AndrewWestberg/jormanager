# Wallet transaction submission

The durable review/reservation design formerly described here was removed by the [minimal-wallet cutover](../minimal-dijkstra-wallet-plan.md).

Wallet, governance and pool actions validate the request, build, sign and submit directly. A process-local submission lock prevents these callers building against the same live inputs concurrently. Only acknowledged submissions update `LedgerDao`'s in-memory inputs/change and transaction-ID history.

Rejected submissions create no persistent spend locks. Confirmation stops applying provisional change. Baseline rollback replay uses only acknowledged transactions still cached in that process; restart restores none of them. An interrupted submission can still reach the node, so local failure is not proof of non-submission.

Migration 047 preserves matching accepted pool-setup receipts and acknowledged transaction IDs before dropping the authorization tables. The full pre-upgrade database backup retains old uncertain records; migration never replays their signed bytes.

See the [operator guide](../dijkstra-upgrade-guide.md#pool-setup-and-ordinary-transaction-actions) for deployment and verification limits.
