package com.swiftmako.jormanager.spring.config

import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.swiftmako.jormanager.entities.Node
import com.swiftmako.jormanager.model.AddedToCurrentChain
import com.swiftmako.jormanager.model.Config
import com.swiftmako.jormanager.model.GenesisByron
import com.swiftmako.jormanager.model.GenesisShelley
import com.swiftmako.jormanager.model.NodeStats
import com.swiftmako.jormanager.model.ProtocolParameters
import com.swiftmako.jormanager.model.QueryTip
import com.swiftmako.jormanager.model.RegCert
import com.swiftmako.jormanager.model.StakeAddressInfo
import com.swiftmako.jormanager.model.StakeSnapshot
import com.swiftmako.jormanager.model.TraceAdoptedBlock
import com.swiftmako.jormanager.model.Utxo
import com.swiftmako.jormanager.model.key.Key
import com.swiftmako.jormanager.model.ledger.Ledger
import com.swiftmako.jormanager.model.metadata.pool.ExtendedMetadata
import com.swiftmako.jormanager.model.tx.TxSigned
import com.swiftmako.jormanager.moshi.adapters.BigIntegerAdapter
import com.swiftmako.jormanager.moshi.adapters.JodaDateTimeAdapter
import com.swiftmako.jormanager.moshi.adapters.QueryUtxoJsonAdapter
import com.swiftmako.jormanager.services.PooltoolService
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.util.concurrent.atomic.AtomicReference
import javax.sql.DataSource
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import liquibase.integration.spring.SpringLiquibase
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import org.jetbrains.exposed.v1.jdbc.Database
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Scope
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder
import org.springframework.transaction.annotation.EnableTransactionManagement
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

@Configuration
@EnableTransactionManagement
class Configuration {
    @Bean
    @Scope(ConfigurableBeanFactory.SCOPE_SINGLETON)
    @org.springframework.context.annotation.Lazy(value = false)
    fun getDataSource(
        @Value("\${spring.datasource.url}") jdbcUrl: String,
        @Value("\${spring.datasource.username}") dataSourceUser: String,
        @Value("\${spring.datasource.password}") dataSourcePassword: String,
    ): DataSource {
        val hikariConfig = HikariConfig()
        // hikariConfig.dataSourceClassName = "org.postgresql.ds.PGSimpleDataSource"
        hikariConfig.jdbcUrl = jdbcUrl
        hikariConfig.username = dataSourceUser
        hikariConfig.password = dataSourcePassword

        hikariConfig.isAutoCommit = false
        hikariConfig.connectionTimeout = 120_000L
        hikariConfig.maximumPoolSize = 30
        hikariConfig.minimumIdle = 5
        hikariConfig.maxLifetime = 600_000L // 10 minutes
        hikariConfig.validationTimeout = 12_000L
        hikariConfig.idleTimeout = 12_000L
        hikariConfig.leakDetectionThreshold = 120_000L

        val ds = HikariDataSource(hikariConfig)
        Database.connect(ds)
        return ds
    }

    @Bean
    @Scope(ConfigurableBeanFactory.SCOPE_SINGLETON)
    fun liquibase(
        dataSource: DataSource,
        @Value("\${spring.liquibase.change-log}") changeLog: String,
    ): SpringLiquibase =
        SpringLiquibase().apply {
            this.dataSource = dataSource
            this.changeLog = changeLog
        }

    @Bean
    @Scope(ConfigurableBeanFactory.SCOPE_SINGLETON)
    fun getMoshi(): Moshi =
        Moshi
            .Builder()
            .add(BigIntegerAdapter)
            .add(JodaDateTimeAdapter())
            .build()

    @Bean
    @Scope(ConfigurableBeanFactory.SCOPE_SINGLETON)
    fun getAdoptedBlockAdapter(moshi: Moshi): JsonAdapter<TraceAdoptedBlock> = moshi.adapter(TraceAdoptedBlock::class.java)

    @Bean
    @Scope(ConfigurableBeanFactory.SCOPE_SINGLETON)
    fun getAddedToCurrentChainAdapter(moshi: Moshi): JsonAdapter<AddedToCurrentChain> = moshi.adapter(AddedToCurrentChain::class.java)

    @Bean
    @Scope(ConfigurableBeanFactory.SCOPE_SINGLETON)
    fun getQueryTipAdapter(moshi: Moshi): JsonAdapter<QueryTip> = moshi.adapter(QueryTip::class.java)

    @Bean
    @Scope(ConfigurableBeanFactory.SCOPE_SINGLETON)
    fun getShelleyGenesisAdapter(moshi: Moshi): JsonAdapter<GenesisShelley> = moshi.adapter(GenesisShelley::class.java)

    @Bean
    @Scope(ConfigurableBeanFactory.SCOPE_SINGLETON)
    fun getByronGenesisAdapter(moshi: Moshi): JsonAdapter<GenesisByron> = moshi.adapter(GenesisByron::class.java)

    @Bean
    @Scope(ConfigurableBeanFactory.SCOPE_SINGLETON)
    fun getConfigAdapter(moshi: Moshi): JsonAdapter<Config> = moshi.adapter(Config::class.java).lenient()

    @Bean
    @Scope(ConfigurableBeanFactory.SCOPE_SINGLETON)
    fun getTxSignedAdapter(moshi: Moshi): JsonAdapter<TxSigned> = moshi.adapter(TxSigned::class.java)

    @Bean
    @Scope(ConfigurableBeanFactory.SCOPE_SINGLETON)
    fun getStakeAddressInfoListAdapter(moshi: Moshi): JsonAdapter<List<StakeAddressInfo>> {
        val type = Types.newParameterizedType(List::class.java, StakeAddressInfo::class.java)
        return moshi.adapter(type)
    }

    @Bean
    @Scope(ConfigurableBeanFactory.SCOPE_SINGLETON)
    fun getProtocolParametersAdapter(moshi: Moshi): JsonAdapter<ProtocolParameters> = moshi.adapter(ProtocolParameters::class.java)

    @Bean
    @Scope(ConfigurableBeanFactory.SCOPE_SINGLETON)
    fun getLedgerAdapter(moshi: Moshi): JsonAdapter<Ledger> = moshi.adapter(Ledger::class.java)

    @Bean
    @Scope(ConfigurableBeanFactory.SCOPE_SINGLETON)
    fun getKeyAdapter(moshi: Moshi): JsonAdapter<Key> = moshi.adapter(Key::class.java)

    @Bean
    @Scope(ConfigurableBeanFactory.SCOPE_SINGLETON)
    fun getExtendedMetadataAdapter(moshi: Moshi): JsonAdapter<ExtendedMetadata> = moshi.adapter(ExtendedMetadata::class.java)

    @Bean
    @Scope(ConfigurableBeanFactory.SCOPE_SINGLETON)
    fun getMetadataAdapter(moshi: Moshi): JsonAdapter<com.swiftmako.jormanager.model.metadata.pool.Metadata> = moshi.adapter(com.swiftmako.jormanager.model.metadata.pool.Metadata::class.java)

    @Bean
    @Scope(ConfigurableBeanFactory.SCOPE_SINGLETON)
    fun getBulkCredentialsAdapter(moshi: Moshi): JsonAdapter<List<List<Key>>> {
        val type =
            Types.newParameterizedType(List::class.java, Types.newParameterizedType(List::class.java, Key::class.java))
        return moshi.adapter(type)
    }

    @Bean
    @Scope(ConfigurableBeanFactory.SCOPE_SINGLETON)
    fun getQueryUtxoAdapter(): JsonAdapter<List<Utxo>> = QueryUtxoJsonAdapter()

    @Bean
    @Scope(ConfigurableBeanFactory.SCOPE_SINGLETON)
    fun getStakeSnapshotAdapter(moshi: Moshi): JsonAdapter<StakeSnapshot> = moshi.adapter(StakeSnapshot::class.java)

    @Bean
    @Scope(ConfigurableBeanFactory.SCOPE_SINGLETON)
    fun getRegCertAdapter(moshi: Moshi): JsonAdapter<RegCert> = moshi.adapter(RegCert::class.java)

    @Bean
    @Scope(ConfigurableBeanFactory.SCOPE_SINGLETON)
    fun getOkHttpClient(): OkHttpClient =
        OkHttpClient
            .Builder()
            .addNetworkInterceptor(
                HttpLoggingInterceptor(
                    object : HttpLoggingInterceptor.Logger {
                        private val log by lazy { LoggerFactory.getLogger("NETWORK") }

                        override fun log(message: String) {
                            log.info(message)
                        }
                    }
                ).setLevel(
                    HttpLoggingInterceptor.Level.NONE
//                         HttpLoggingInterceptor.Level.BODY
                )
            ).build()

    @Bean
    @Scope(ConfigurableBeanFactory.SCOPE_SINGLETON)
    fun getRetrofit(
        client: OkHttpClient,
        moshi: Moshi
    ): Retrofit =
        Retrofit
            .Builder()
            .baseUrl("http://127.0.0.1")
            .client(client)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()

    @Bean
    @Scope(ConfigurableBeanFactory.SCOPE_SINGLETON)
    fun getPooltoolService(retrofit: Retrofit): PooltoolService =
        retrofit
            .newBuilder()
            .baseUrl("https://api.pooltool.io")
            .build()
            .create(PooltoolService::class.java)

    @Bean("nodesChannel")
    @Scope(ConfigurableBeanFactory.SCOPE_SINGLETON)
    fun getNodesBroadcastChannel(): MutableSharedFlow<Node> {
        // Channel which broadcasts nodeIds to start monitoring for blocks
        return MutableSharedFlow()
    }

    @Bean("refreshWalletChannel")
    @Scope(ConfigurableBeanFactory.SCOPE_SINGLETON)
    fun getRefreshWalletChannel(): MutableStateFlow<Long?> = MutableStateFlow(null)

    @Bean
    @Scope(ConfigurableBeanFactory.SCOPE_SINGLETON)
    fun argonPasswordEncoder(): Argon2PasswordEncoder = Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8()

    @Bean("latestNodeStats")
    @Scope(ConfigurableBeanFactory.SCOPE_SINGLETON)
    fun getLatestNodeStats(): AtomicReference<NodeStats?> = AtomicReference(null)
}
