# State machine inventory and design notes

Status: **design proposals, not implemented or approved**. This documentation records the implicit state machines found in the codebase and evaluates explicit replacements. It does not authorize runtime, database, API, or frontend changes.

The wallet reservation framework and durable era approvals described by the original inventory have been removed. The [wallet submission note](reviewed-transactions.md) and [era query note](era-evidence.md) describe the replacement; other proposals remain historical, unapproved designs rather than current wallet contracts.

The [frontend inventory](frontend.md) is deliberately deferred. Backend proposals are separate because their authority, persistence, failure modes, and useful Kotlin representations differ. They are not parts of one proposed global state-machine framework.

## Backend decisions

| Machine | Recommended explicit representation | Why this boundary matters |
| --- | --- | --- |
| [Wallet transactions](reviewed-transactions.md) | Implemented direct CLI submission with success-only in-memory change | Rejected attempts do not create persistent spend locks. |
| [Pool draft deployment](pool-deployment.md) | Durable attempt with sealed outcome and recovery checkpoints | Certificate issuance and host mutation cannot be atomic with database updates; uncertain effects require inspection, not retry. |
| [Leios key installation](leios-key-installation.md) | Separate durable installation attempt and typed host evidence | Installed, loaded, registered and adopted are different facts; shared producer files require exclusive mutation ownership. |
| [Chain synchronization](chain-synchronization.md) | Readiness enum and explicit transitions over the locked durable snapshot | Keep ledger projection, rollback, coverage and session fencing in the same database authority. |
| [Queued block-fetch validity](block-fetch-validity.md) | Refine existing protocol substates and expose ownership-transfer operations | Bounded queues, exact permit release and durable generation checks matter more than a new per-block object hierarchy. |
| [Block-production adjudication](block-adjudication.md) | Status enum, pure classifier and concurrency-safe writer boundaries | Make classification policy visible without inventing stronger network identity or reorg guarantees. |
| [Current-era queries](era-evidence.md) | Implemented direct node query with latest in-memory observation | The CLI-reported era selects transaction syntax. |
| [Capability qualification](capability-qualification.md) | Sealed effective validation result over stored attestation and current inputs | This is principally a derived validity judgment, not a persistent lifecycle worth turning into an FSM engine. |
| [Monitor and connection supervision](connection-supervision.md) | Explicit service/worker lifetimes, typed control events and owned coroutine scopes | Cancellation, teardown, replacement and observation ownership need precise boundaries; sampling itself can remain a loop. |

## Kotlin design principles

- **Sealed types where payloads differ.** Included transactions require a signed batch and inclusion point; current era evidence needs a verified observation. Constructors and persistence decoders must enforce those requirements. Kotlin types alone cannot validate historical SQL rows or prevent an unrelated SQL writer from bypassing a transition.
- **Enums where payloads do not differ.** Readiness and heuristic block labels do not justify copying an entire record into multiple subclasses. Keep one authoritative snapshot and exhaustive mappings.
- **Exhaustive `when`, not accidental fallthrough.** Handle every typed state/event explicitly. Unknown database/wire values are trust-boundary errors or conservative historical-data cases, not permission to pick a convenient default state. An exhaustive expression helps compile-time coverage; transactional guards establish runtime legality.
- **One owner of each transition.** Database transactions and conditional writes own durable authorization/projection changes. A coroutine control loop can own process-local worker lifetimes. Side effects occur outside long-held locks and return results bound to the original request/attempt identity.
- **Flows for observations, not authority.** `StateFlow` can expose the latest immutable snapshot; conflation makes it unsuitable for block delivery, commands, or exactly-once cleanup. Its collection must not accidentally create another side-effecting session. Channels remain useful where bounded ordered work is already required.
- **No custom DSL by default.** A type-safe builder may become useful for a large stable transition vocabulary shared by real consumers. These machines currently have different persistence and effect semantics. Local sealed types, enums, ordinary functions and coroutine scopes are more transparent than a common transition DSL. No new library is required by these proposals.

## Independence and migration boundaries

These designs share evidence but not a universal `generation`. Distinguish network chain-delivery generation, era-change generation, local query revision, optional durable query ticket, connection attempt identity, and frontend invalidation counter. Giving them separate names/types prevents a valid token in one subsystem from becoming authority in another.

Migration 047 retires the shared authorization tables after preserving accepted setup receipts and transaction-ID history. Chain ingestion no longer writes operation confirmations or reservations.

Existing database and external status spellings should be retained through deliberate mappings unless a separately approved contract migration changes them. Internal state classes are not automatically JSON schemas. A proposed `InspectionRequired` state must not become a new frontend status by accident: the frontend remains deferred, and existing uncertain/error responses must remain conservative.

The host mutation designs propose durable inspection because a representation-only enum cannot make remote effects recoverable. They do not claim exactly-once SSH execution, safe automatic retries, or distributed atomicity. Database fencing prevents stale commits, not remote commands already in flight. Installation success is not proof that a producer restarted or loaded a key.

Other policy changes are explicitly separated from representational changes: block network identity and reorg reclassification, era-query retry/competing-writer invalidation, shared monitor eligibility, and any new public lifecycle endpoints. Decide those contracts before implementation rather than hiding them inside a type refactor.

## Suggested implementation order, if approved later

Host deployment and key-installation proposals concern their shared producer-resource boundary. Chain-readiness, connection supervision and fetch ownership remain separate from ordinary wallet submission. This inventory does not propose reintroducing wallet reviews, reservations or durable era approvals.

This is a risk-based recommendation, not a scheduled roadmap. The frontend document remains inventory-only. Each backend note names current sources, proposed ownership, crash/recovery limits, migration surface and behavioral checks required for a future implementation.

## Evidence limits

Current-behavior statements are grounded in repository source and linked symbols. Concurrency and recovery concerns not reproduced at runtime are identified as design risks rather than observed incidents. Kotlin snippets illustrate proposed type/transition shapes; supporting domain types are not implemented code. Verification scenarios are future acceptance criteria, not claims that those tests or guarantees already exist.
