# Frontend implicit state machines

Status: **inventory only; deferred**. No frontend refactor is proposed or scheduled by this document. The backend notes in this directory are design proposals, not implemented changes.

This records the five frontend machine families identified in the source investigation. Several components implement variants of the same family. Named server statuses are not themselves implicit; the implicit part is the orchestration around them using flags, nullable reviews, request IDs, generation counters, and watchers.

## 1. Node provisioning wizard

### State representation

[AddNodeWizard.vue](../../vue/src/components/AddNodeWizard.vue#L964) combines the form wizard's active step and node type with:

- `provisioningPreview`, `provisioningSignature`, `provisioningPending`, and `provisioningRequestId`;
- current capability evidence, qualification inputs, `qualificationConfirmed`, and `qualificationReview`;
- `draftConfirmed`, `draftPending`, `draftSignature`, and `draftError`.

These are several coordinated states, not a single wizard step number. Pool/core nodes have additional keys, pool configuration, relay, and metadata steps that relay creation does not need.

### Events and guards

Preflight snapshots selected target/configuration fields into a signature. A matching preflight response permits requesting host capabilities. Qualification approval captures both the qualification signature and observed era generation; changed input invalidates that approval by making the signatures differ. Request IDs correlate capability and qualification responses.

`draftPreflightReady` distinguishes verified Dijkstra operation evidence from the existing Conway path. Draft creation additionally requires valid form steps and approval of the current form. Password confirmation initiates the authorized operation; it is not equivalent to successful creation. Draft response handling and password dispatch are in [the wizard handlers](../../vue/src/components/AddNodeWizard.vue#L1274).

### Boundary to preserve

A completed form, a successful preflight, capability qualification, and an approved creation request are distinct facts. An old preview must not authorize a changed target. The eventual draft still requires the separate [backend deployment lifecycle](pool-deployment.md).

## 2. Wallet-entry creation wizard

### State representation

[AddWalletEntryWizard.vue](../../vue/src/components/AddWalletEntryWizard.vue#L164) uses `currentStep`, `pendingAction`, wallet type, `generateKeys`, and nullable file contents. The spending-password modal participates through event-bus messages rather than belonging to one explicit wizard state.

### Events and guards

[`handleNext`](../../vue/src/components/AddWalletEntryWizard.vue#L239) validates the fields appropriate to the wallet type. Generating keys clears supplied key material; importing reads selected files. Payment-only entries discard staking keys. Successful preparation requests password confirmation using the `create-wallet` action.

The matching password-confirmation handler sends the request, clears the password from the form, and closes the wizard. Closing here means **request dispatched**, not backend creation confirmed. The store/message layer handles later server responses.

### Boundary to preserve

Type-dependent required keys and password authorization must survive any eventual reorganization. UI dismissal must not be promoted into proof that a wallet entry was created.

## 3. Review, authorization, submission, and reconciliation UI

### Shared state representation

[WalletReviewPanel.vue](../../vue/src/components/WalletReviewPanel.vue#L37) combines the server's `review.state` with local `busy`, `uncertain`, `stale`, `error`, and password fields. `chainComplete` additionally requires a connected store, a complete read state, and a matching network.

### Events and guards

Network invalidation and connection/read-state watchers make an existing review stale. Submission requires current chain evidence, a password, and no local busy/stale condition; the template also gates the available action by server review state. Passwords are copied for the immediate request and cleared from local state.

Submission/reconciliation responses must match preview, transaction, and network identity. A failure sets local uncertainty rather than asserting that the server did nothing. Reconciliation observes the existing operation; it is not a request to resubmit. A network generation change while awaiting a response keeps the result stale even if the response itself was valid.

### Component variants

- [SendAdaModal.vue](../../vue/src/components/SendAdaModal.vue#L285) validates payment/withdrawal input, requests a review, and owns a `reviewGeneration`. Resetting the form invalidates older in-flight review responses.
- [Wallet.vue](../../vue/src/views/Wallet.vue#L255) has corresponding staking-review flags and generation ownership.
- [GovernanceVoteModal.vue](../../vue/src/components/GovernanceVoteModal.vue#L132) adds independently authorized rationale publication. The published anchor is tied to the rationale; edits invalidate it. Publication and transaction submission are separate effects.
- [Nodes.vue](../../vue/src/views/Nodes.vue#L576) implements pool-change review, submission, uncertainty, staleness, and reconciliation locally. Its [draft deployment handlers](../../vue/src/views/Nodes.vue#L743) have a separate preview/install lifecycle; installation errors mark the outcome uncertain and block blind retry.
- [LeiosKeyPanel.vue](../../vue/src/components/LeiosKeyPanel.vue#L90) coordinates capability checks, preparation/import, installation preview, approval, and installation outcome. Its [registration handlers](../../vue/src/components/LeiosKeyPanel.vue#L229) separately manage registration/rotation/cancellation review and reconciliation.

### Boundary to preserve

The UI is not the durable authority for submission. Reloading a component cannot erase backend uncertainty or restore permission to broadcast. Key preparation, key installation, on-chain registration, eligibility, and the producer's loaded key must remain separate observations. See [reviewed transactions](reviewed-transactions.md) and [Leios installation](leios-key-installation.md).

## 4. Connection and observation freshness

### State representation

The [Pinia store](../../vue/src/stores/jormanager.ts#L350) owns `connected`, the STOMP client, per-network read states, request identities, and `networkInvalidations`. Leios observations can remain visible while being marked historical/stale.

### Events and guards

Connection errors clear `connected`, invalidate network observations, and clear node-index request/status maps. Subscription establishment also invalidates old observations before marking the connection available and requesting nodes. Transport connectivity does not restore prior observations to current status.

[Network invalidation](../../vue/src/stores/jormanager.ts#L823) increments a network generation, marks retained Leios observations stale, and invalidates applicable outstanding request IDs. A fresh status response is accepted only through its relevant routing and identity checks. Server-pushed chain status without a request ID is also handled; it is not an ordinary request-response completion.

### Boundary to preserve

Connected, synchronized, and current are different conditions. Cached historical observations are useful for display but cannot authorize operations. Network generation invalidation propagates into the review panels above.

## 5. Asynchronous request ownership

### State representation

[`socketRequest`](../../vue/src/stores/jormanager.ts#L898) registers a unique ID in `pendingReviews`, storing the expected response type, promise callbacks, and timeout. Component-local generations provide an additional layer of ownership for requests whose UI context can be reset or replaced.

### Events and guards

[Response routing](../../vue/src/stores/jormanager.ts#L390) resolves/rejects a pending promise only for a matching request ID and type, then clears its timer and map entry. Sending failure removes the entry. A 60-second timeout removes it and rejects with an uncertain-outcome message. A later response can no longer complete that expired promise.

A component generation check controls whether a completed request may replace the current form's review/error/busy state. This protects UI ownership; it neither cancels server-side work nor reverses a submitted transaction. Not every component uses the same generation scheme, so this is a repeated pattern, not a centralized facility.

### Boundary to preserve

A transport timeout means lack of an observed response, not proof of failed execution. Request correlation and component freshness checks serve different purposes and are not interchangeable.

## Scope and evidence limits

This is a source inventory, not a claim that every asynchronous component has been exhaustively modeled. Ordinary loading flags and transient toast visibility are excluded unless they participate in one of the workflows above. Existing frontend tests were not run for this documentation-only work. No frontend implementation, behavior, or API contract changes are authorized by this note.
