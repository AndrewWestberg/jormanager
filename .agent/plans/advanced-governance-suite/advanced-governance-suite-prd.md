

# Advanced Governance Suite

## 1\. Context & Objectives

*Define the purpose of this initiative and what constitutes a successful outcome from a business and user perspective.*

-   **Problem Statement:** Advanced users and pool operators must currently use the command-line interface for CIP-1694 governance actions because the GUI does not support DRep registration, SPO voting, or metadata-rich discovery [cite:source4].
-   **Business Goal:** Consolidate all node management and governance tasks into a single interface to reduce operational errors and eliminate CLI dependency.
-   **Hypothesis:** Providing a high-security governance dashboard with chain scanning via Koios, SPO voting, and metadata visualization will transition professional operators from manual scripts to the GUI for all CIP-1694 activities.
-   **Success Metrics:**
    -   100% coverage of CIP-1694 governance actions (SPO, DRep, and constitutional committee) within the GUI.
    -   Successful integration of encrypted cold-key management for high-security transactions.
    -   Operators can review governance action metadata directly in the UI or via adastat.net fallback.
    -   Zero dependency on external CLI for multi-identity voting.

-----

## 2\. User Scenarios

*Narrative journeys that describe how a person interacts with the solution. Focus on the experience, not the interface.*

-   **Scenario: Multi-Identity Voting**
    -   **User Intent:** An operator managing three separate stake pools wants to vote "No" on a protocol change across all of them at once.
    -   **Desired Experience:** The user selects the governance action in the SPO dashboard. In the voting modal, they see a list of their managed pools. They check "Select All" (or pick specific pools) and submit a single batched signing request. The system processes the votes for each identity.
-   **Scenario: Managing Governance Deposits**
    -   **User Intent:** Register a new DRep identity and ensure the deposit is paid from a specific treasury wallet.
    -   **Desired Experience:** The user initiates registration. The system clearly displays the required ADA deposit. The user selects "Treasury Wallet" from a dropdown of their managed wallets. The transaction is built, signed using the encrypted cold-key pattern, and submitted.
-   **Scenario: History & Audit**
    -   **User Intent:** Review a proposal that expired two epochs ago.
    -   **Desired Experience:** The user navigates to the "History" tab of the Governance Suite. They find the expired action, see that they voted "Yes" with two pools, and can still access the metadata link to adastat.net for documentation.
-   **Scenario: Metadata Discovery & Fallback**
    -   **User Intent:** Review a complex proposal before voting.
    -   **Desired Experience:** The user opens the proposal details. If the system fails to resolve the on-chain metadata anchor via Koios, an error is displayed alongside a direct link to the same action on `adastat.net`.

-----

## 3\. Functional Requirements

*A high-level list of what the solution must be able to do. Avoid mentioning specific code, databases, or implementation details.*

-   **Requirement 1:** A dedicated dashboard must display the current DRep/SPO status and active governance proposals, including real-time state (Active, Expired, Voted, Pending).
-   **Requirement 2:** The system must generate DRep and SPO registration, update, and retirement certificates via guided workflows.
-   **Requirement 3:** **Chain Scanner (Koios-powered)**:
    -   Identify all new on-chain governance actions using the Koios API. **Note: Blockfrost is strictly prohibited.**
    -   If an action is eligible for multiple roles (SPO and DRep), duplicate the entry in the respective role-specific filters.
-   **Requirement 4:** **Metadata Resolution & Fallback**:
    -   Lookup, resolve, and display JSON-LD metadata for actions.
    -   If resolution fails, display a clear error message.
    -   **Mandatory**: Provide a persistent external link to `adastat.net` for every governance action.
-   **Requirement 5:** **Vote Lifecycle & History**:
    -   Disable voting for expired actions.
    -   Mark actions as "Completed" after a vote is cast.
    -   **History Tab**: Automatically move expired governance actions to a dedicated "History" tab for long-term audit.
    -   Allow simple "Change Vote" overwrites for any active proposal.
-   **Requirement 6:** **Identity & Wallet Selection**:
    -   Voting modals must allow users to select one or more managed identities (SPOs or DReps) to vote with simultaneously.
    -   Registration workflows must display required ADA deposits and allow the user to select the source wallet for the payment.
-   **Requirement 7:** **Secure Signing (Cold Keys)**:
    -   Utilize the existing JorManager "Encrypted Cold Key" pattern for all governance actions requiring high-security signatures.
-   **Requirement 8**: **Self-Contained Dashboard**: Governance alerts and status changes are dashboard-only for this phase; do not integrate with external notification systems.

-----

## 4\. Constraints & Guardrails

*The boundaries within which the solution must operate.*

-   **Koios Priority**: All blockchain data fetching for governance must be routed through Koios.
-   **No Multi-sig Support**: Transactions are assumed to be authorized by a single user with access to the necessary keys.
-   **Protocol Compliance**: Voting eligibility must strictly follow Cardano protocol rules.
-   **Metadata Sanitization**: All content pulled from remote metadata anchors must be sanitized before being displayed in the GUI.

-----

## 5\. Acceptance Criteria

*A checklist of conditions that must be met for the solution to be considered complete and successful.*

-   [ ] The voting modal allows selecting multiple pools or DRep IDs for a single action.
-   [ ] Required deposit amounts (e.g., 500 ADA for DRep) are visible during registration.
-   [ ] Users can choose which wallet pays the registration deposit.
-   [ ] The chain scanner uses Koios to fetch data and does not utilize Blockfrost.
-   [ ] Expired governance actions automatically move from the active dashboard to the "History" tab.
-   [ ] Every governance action in the UI includes a working link to its corresponding page on `adastat.net`.
-   [ ] Users can successfully initiate a "Change Vote" workflow for active proposals they have already voted on.
-   [ ] Decrypted cold keys are wiped from memory immediately after transaction signing.
-   [ ] Metadata errors are displayed gracefully alongside the adastat.net fallback link.
---

**Status:** 📝 Draft
**Date:** 2026-06-26
**Author:** AI Agent

