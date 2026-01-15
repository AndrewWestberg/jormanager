package com.swiftmako.jormanager.model

data class NodeVoteSelection(
    val nodeId: Long,
    /** Vote choice: YES, NO, ABSTAIN */
    val vote: String
)

data class GovernanceVoteRequest(
    val govActionId: String,
    val votes: List<NodeVoteSelection>,
    val feesAccountId: Long,
    val spendingPassword: String
) {
    override fun toString(): String = "GovernanceVoteRequest(govActionId=$govActionId, votes=$votes, feesAccountId=$feesAccountId, spendingPassword='********')"
}
