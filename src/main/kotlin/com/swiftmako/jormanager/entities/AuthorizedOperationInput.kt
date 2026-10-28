package com.swiftmako.jormanager.entities

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint

/** A spent input remains reserved until reconciliation explicitly permits another authorization. */
@Entity
@Table(name = "authorized_operation_inputs", uniqueConstraints = [UniqueConstraint(columnNames = ["network_key", "input_tx_id", "input_index"])])
data class AuthorizedOperationInput(
    @Id val id: String,
    @Column(name = "operation_id", nullable = false) val operationId: String,
    @Column(name = "network_key", nullable = false) val networkKey: String,
    @Column(name = "input_tx_id", nullable = false) val inputTxId: String,
    @Column(name = "input_index", nullable = false) val inputIndex: Int,
)
