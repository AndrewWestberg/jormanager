package com.swiftmako.jormanager.entities

import org.hibernate.annotations.Where
import javax.persistence.*

@Entity
@Table(name = "ledger")
//@Cacheable
//@org.hibernate.annotations.Cache(usage = CacheConcurrencyStrategy.READ_WRITE)
data class LedgerAddress(
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE)
    val id: Long? = null,
    @Column(name = "address")
    val address: String,
    @Column(name = "stake_address")
    val stakeAddress: String?,

    @OneToMany(fetch = FetchType.EAGER)
    @JoinColumn(name = "ledger_id", insertable = false, updatable = false)
    @Where(clause = "block_spent IS NULL")
    val ledgerUtxos: List<LedgerUtxo> = emptyList()
)
