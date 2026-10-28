package com.swiftmako.jormanager.tables

import org.jetbrains.exposed.v1.core.Table

object SyncStatesTable : Table("sync_states") {
    val network = text("network_key")
    val generation = long("generation")
    val observedSlot = decimal("observed_slot", 20, 0).nullable()
    val observedHash = binary("observed_hash").nullable()
    val completeInclusion = long("complete_inclusion_id").nullable()
    val status = text("status")
    val reason = text("reason").nullable()
    override val primaryKey = PrimaryKey(network)
}

object ChainInclusionsTable : JmLongIdTable("chain_inclusions") {
    val network = text("network_key")
    val hash = binary("header_hash")
    val slot = decimal("slot", 20, 0)
    val height = decimal("height", 20, 0)
    val previousHash = binary("previous_hash").nullable()
    val era = integer("era")
    val header = binary("raw_header")
    val body = binary("raw_body")
    val adopted = bool("adopted")
    val complete = bool("complete")
    val sourceKind = text("source")
}

object TransactionBatchesTable : JmLongIdTable("transaction_batches") {
    val inclusion = long("inclusion_id").references(ChainInclusionsTable.id)
    val sourceKind = text("source")
    val position = integer("source_position")
    val applicationOrdinal = integer("application_ordinal")
    val valid = bool("valid")
    val raw = binary("raw_included")
    val fullHash = binary("full_hash").nullable()
}

object TransactionMembersTable : JmLongIdTable("transaction_members") {
    val batch = long("batch_id").references(TransactionBatchesTable.id)
    val txId = binary("tx_id")
    val parent = long("parent_id").nullable()
    val childPosition = integer("child_position").nullable()
    val processingOrdinal = integer("processing_ordinal")
    val rawBody = binary("raw_body")
    val rawWitnesses = binary("raw_witnesses")
    val rawAuxiliary = binary("raw_auxiliary").nullable()
    val auxiliaryPresence = text("auxiliary_presence")
    val fee = decimal("fee", 20, 0).nullable()
    val effective = bool("effective")
}

object OutputOccurrencesTable : JmLongIdTable("output_occurrences") {
    val member = long("member_id").references(TransactionMembersTable.id)
    val index = integer("output_index")
    val raw = binary("raw_output")
    val address = binary("address")
    val coin = decimal("coin", 20, 0)
    val datum = binary("datum").nullable()
    val referenceScript = binary("reference_script").nullable()
    val collateralReturn = bool("collateral_return")
    val effective = bool("effective")
}

object OutputAssetsTable : Table("output_assets") {
    val output = long("output_id").references(OutputOccurrencesTable.id)
    val policy = binary("policy")
    val name = binary("asset_name")
    val quantity = decimal("quantity", 20, 0)
    override val primaryKey = PrimaryKey(output, policy, name)
}

object InputOccurrencesTable : JmLongIdTable("input_occurrences") {
    val member = long("member_id").references(TransactionMembersTable.id)
    val role = text("role")
    val position = integer("position")
    val txId = binary("tx_id")
    val index = integer("output_index")
    val resolvedOutput = long("resolved_output_id").references(OutputOccurrencesTable.id).nullable()
    val effective = bool("effective")
}

object MemberDataTable : JmLongIdTable("member_data") {
    val member = long("member_id").references(TransactionMembersTable.id)
    val family = text("family")
    val position = integer("position")
    val raw = binary("raw_data")
    val typed = text("typed_data")
    val effective = bool("effective")
}

object AccountEffectsTable : JmLongIdTable("account_effects") {
    val member = long("member_id").references(TransactionMembersTable.id)
    val position = integer("position")
    val account = binary("account")
    val kind = text("kind")
    val amount = decimal("amount", 20, 0).nullable()
    val lower = decimal("lower_inclusive", 20, 0).nullable()
    val upper = decimal("upper_exclusive", 20, 0).nullable()
    val effective = bool("effective")
}

object CertificateGovernanceEffectsTable : JmLongIdTable("certificate_governance_effects") {
    val member = long("member_id").references(TransactionMembersTable.id)
    val position = integer("position")
    val kind = text("kind")
    val target = binary("target").nullable()
    val amount = decimal("amount", 20, 0).nullable()
    val raw = binary("raw_data")
    val typed = text("typed_data")
    val effective = bool("effective")
}

/** Current projections retain their complete prior value in the inclusion's journal. */
object DerivedAccountGovernanceStatesTable : Table("derived_account_governance_states") {
    val network = text("network_key")
    val family = text("family")
    val key = binary("state_key")
    val value = text("state_value")
    val inclusion = long("inclusion_id").references(ChainInclusionsTable.id)
    val member = long("member_id").references(TransactionMembersTable.id).nullable()
    override val primaryKey = PrimaryKey(network, family, key)
}

object ReversibleChangesTable : JmLongIdTable("reversible_changes") {
    val inclusion = long("inclusion_id").references(ChainInclusionsTable.id)
    val sequence = integer("sequence")
    val family = text("family")
    val key = binary("state_key")
    val priorValue = text("prior_value").nullable()
    val priorInclusion = long("prior_inclusion_id").nullable()
    val priorMember = long("prior_member_id").nullable()
}

object ProtocolSnapshotsTable : JmLongIdTable("protocol_snapshots") {
    val network = text("network_key")
    val slot = decimal("slot", 20, 0)
    val hash = binary("header_hash")
    val era = integer("era")
    val raw = text("raw_parameters")
    val evidence = text("evidence_id")
}

object LeiosAnnouncementsTable : JmLongIdTable("leios_announcements") {
    val inclusion = long("inclusion_id").references(ChainInclusionsTable.id)
    val hash = binary("eb_hash")
    val size = decimal("eb_size", 20, 0)
    val raw = binary("raw_announcement")
}
object LeiosCertificationsTable : JmLongIdTable("leios_certifications") {
    val inclusion = long("inclusion_id").references(ChainInclusionsTable.id)
    val announcementHash = binary("announcement_hash")
    val raw = binary("raw_certificate")
    val complete = bool("complete")
}
object LeiosContentReferencesTable : JmLongIdTable("leios_content_references") {
    val certification = long("certification_id").references(LeiosCertificationsTable.id)
    val position = integer("position")
    val fullHash = binary("full_hash")
    val size = decimal("byte_size", 20, 0)
    val batch = long("batch_id").references(TransactionBatchesTable.id).nullable()
}

object NetworkGenesisTable : Table("network_genesis") {
    val network = text("network_key")
    val hash = binary("genesis_hash")
    val seeded = bool("seeded")
    override val primaryKey = PrimaryKey(network)
}

/** Exposed view of JPA-managed operations: only confirmation changes participate in chain commits. */
object OperationConfirmationsTable : Table("authorized_operations") {
    val network = text("network_key")
    val txId = text("tx_id")
    val state = text("state")
    val signedBatch = text("signed_batch").nullable()
    val hash = text("confirmation_hash").nullable()
    val slot = decimal("confirmation_slot", 20, 0).nullable()
    val height = decimal("confirmation_height", 20, 0).nullable()
}
