# Governance Voting Feature

## Overview

This feature enables stake pool operators to vote on Cardano governance actions directly from the JorManager Nodes view. All core nodes defined in the system can vote in a single transaction, with each node independently selecting Yes, No, or Abstain.

## Status

- **Status:** ✅ Completed
- **Started:** 2026-01-15
- **Completed:** 2026-01-15

---

## Requirements

### Functional Requirements

- [x] Add `Vote` button to Nodes view when core nodes exist
- [x] Create modal for governance voting with:
  - [x] Governance Action ID input using bech32 format: `gov_action1...`
  - [x] List of all core nodes with vote selection
  - [x] Fee account selection dropdown
- [x] All core nodes must submit a vote with no skip option
- [x] Submit all votes in a single on-chain transaction
- [x] Display the transaction ID on success

### Technical Requirements

- [x] Validate bech32 governance action ID format
- [x] Use `cardano-cli conway governance vote create` for vote file generation
- [x] Use cold signing keys for each pool to sign votes
- [x] Handle WebSocket communication for vote submission

---

## Technical Design

### Frontend Components

| Component | Purpose |
|-----------|---------|
| `Nodes.vue` | Add the `Vote` button and modal visibility |
| `GovernanceVoteModal.vue` | Capture action ID, optional shared rationale, votes, and fee source |
| `jormanager.ts` store | Add `submitGovernanceVote` action and message handler |

### Backend Components

| Component | Purpose |
|-----------|---------|
| `GovernanceVoteRequest.kt` | Data class for vote submission payload |
| `NodeController.kt` | New `@MessageMapping("/governancevote")` endpoint |

### Transaction Flow

```
1. User enters a `gov_action1...` ID and an optional shared rationale
2. User selects Yes, No, or Abstain for each core node
3. Frontend sends `GovernanceVoteRequest` via WebSocket after password confirmation
4. For a nonblank rationale, the backend publishes one CIP-100 JSON document and verifies the hosted bytes
5. Backend generates one vote file per node, reusing the verified anchor for every vote
6. Backend builds one transaction with all `--vote-file` params
7. Backend signs with all cold keys plus the payment key
8. Backend submits the transaction and returns the TxID
```

### Cardano CLI Commands Used

**Vote File Creation:**
```bash
cardano-cli conway governance vote create \
  --yes|--no|--abstain \
  --governance-action-tx-id ${txId} \
  --governance-action-index ${idx} \
  --cold-verification-key-file /tmp/node.vkey \
  [--anchor-url ${url} --anchor-data-hash ${blake2b256}] \
  --out-file /tmp/node.vote
```

**Transaction Building:**
```bash
cardano-cli conway transaction build-raw \
  --tx-in ${utxo}#${idx} \
  --tx-out ${addr}+${amount} \
  --fee ${fee} \
  --invalid-hereafter ${ttl} \
  --vote-file /tmp/node1.vote \
  --vote-file /tmp/node2.vote \
  --out-file /tmp/tx.txbody
```

---

## Implementation Checklist

### Phase 1: Frontend
- [x] Create `GovernanceVoteModal.vue`
- [x] Add vote button and modal to `Nodes.vue`
- [x] Add store action and message handler
- [x] Add TypeScript types

### Phase 2: Backend
- [x] Create `GovernanceVoteRequest.kt`
- [x] Implement `submitGovernanceVote` in `NodeController.kt`
- [x] Add transaction building logic
- [x] Add signing and submission logic

### Phase 3: Testing
- [x] Verify frontend validation
- [x] Test modal UX
- [x] Run end-to-end governance vote submission testing

---

## API Reference

### WebSocket Endpoint

**Endpoint:** `/jormanager/governancevote`

**Request:**
```json
{
  "govActionId": "gov_action1...",
  "votes": [
    { "nodeId": 1, "vote": "YES" },
    { "nodeId": 2, "vote": "NO" },
    { "nodeId": 3, "vote": "ABSTAIN" }
  ],
  "feesAccountId": 5,
  "spendingPassword": "********",
  "rationale": "Optional shared public rationale"
```

**Success Response:**
```json
{
  "type": "governancevote",
  "data": "Governance vote submitted successfully. TxId: abc123..."
}
```

**Error Response:**
```json
{
  "type": "governancevote",
  "exception": { "message": "Error description" }
}
```

---

## Notes

- All core nodes in the system must vote.
- Default vote selection is `Abstain`.
- Only bech32 format `gov_action1...` is supported for governance action IDs.
- Cold signing keys are accessed using the spending password already managed by JorManager.
- A nonblank rationale is public, is shared by all pool votes in the transaction, and may remain hosted if transaction submission fails.
- Blank or omitted rationale values create unanchored votes and perform no upload.
- Rationale publication fails closed if the returned URL is unsafe/overlong or the retrieved bytes differ from the uploaded document.

---

**Created:** 2026-01-15
**Author:** AI Assistant
