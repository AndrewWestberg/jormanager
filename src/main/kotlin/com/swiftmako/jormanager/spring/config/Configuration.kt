package com.swiftmako.jormanager.spring.config

import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.swiftmako.jormanager.entities.Node
import com.swiftmako.jormanager.model.*
import com.swiftmako.jormanager.model.key.Key
import com.swiftmako.jormanager.model.ledger.Ledger
import com.swiftmako.jormanager.model.metadata.pool.ExtendedMetadata
import com.swiftmako.jormanager.moshi.adapters.BigIntegerAdapter
import com.swiftmako.jormanager.moshi.adapters.JodaDateTimeAdapter
import com.swiftmako.jormanager.moshi.adapters.QueryUtxoJsonAdapter
import com.swiftmako.jormanager.services.PooltoolService
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Scope
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder
import org.springframework.transaction.annotation.EnableTransactionManagement
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.atomic.AtomicReference


@Configuration
@EnableTransactionManagement
class Configuration {


    @Bean
    @Scope(ConfigurableBeanFactory.SCOPE_SINGLETON)
    fun getMoshi(): Moshi {
        return Moshi.Builder().add(BigIntegerAdapter).add(JodaDateTimeAdapter()).build()
    }

    @Bean
    @Scope(ConfigurableBeanFactory.SCOPE_SINGLETON)
    fun getAdoptedBlockAdapter(moshi: Moshi): JsonAdapter<TraceAdoptedBlock> =
        moshi.adapter(TraceAdoptedBlock::class.java)

    @Bean
    @Scope(ConfigurableBeanFactory.SCOPE_SINGLETON)
    fun getAddedToCurrentChainAdapter(moshi: Moshi): JsonAdapter<AddedToCurrentChain> =
        moshi.adapter(AddedToCurrentChain::class.java)

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
    fun getConfigAdapter(moshi: Moshi): JsonAdapter<Config> = moshi.adapter(Config::class.java)

    @Bean
    @Scope(ConfigurableBeanFactory.SCOPE_SINGLETON)
    fun getStakeAddressInfoListAdapter(moshi: Moshi): JsonAdapter<List<StakeAddressInfo>> {
        val type = Types.newParameterizedType(List::class.java, StakeAddressInfo::class.java)
        return moshi.adapter(type)
    }

    @Bean
    @Scope(ConfigurableBeanFactory.SCOPE_SINGLETON)
    fun getProtocolParametersAdapter(moshi: Moshi): JsonAdapter<ProtocolParameters> =
        moshi.adapter(ProtocolParameters::class.java)

    @Bean
    @Scope(ConfigurableBeanFactory.SCOPE_SINGLETON)
    fun getLedgerAdapter(moshi: Moshi): JsonAdapter<Ledger> = moshi.adapter(Ledger::class.java)

    @Bean
    @Scope(ConfigurableBeanFactory.SCOPE_SINGLETON)
    fun getKeyAdapter(moshi: Moshi): JsonAdapter<Key> = moshi.adapter(Key::class.java)

    @Bean
    @Scope(ConfigurableBeanFactory.SCOPE_SINGLETON)
    fun getExtendedMetadataAdapter(moshi: Moshi): JsonAdapter<ExtendedMetadata> =
        moshi.adapter(ExtendedMetadata::class.java)

    @Bean
    @Scope(ConfigurableBeanFactory.SCOPE_SINGLETON)
    fun getMetadataAdapter(moshi: Moshi): JsonAdapter<com.swiftmako.jormanager.model.metadata.pool.Metadata> =
        moshi.adapter(com.swiftmako.jormanager.model.metadata.pool.Metadata::class.java)

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
    fun getOkHttpClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .addNetworkInterceptor(
                HttpLoggingInterceptor(object : HttpLoggingInterceptor.Logger {
                    private val log by lazy {  LoggerFactory.getLogger("NETWORK") }
                    override fun log(message: String) {
                        log.info(message)
                    }
                }).setLevel(
                    HttpLoggingInterceptor.Level.NONE
//                         HttpLoggingInterceptor.Level.BODY
                )
            )
            .build()
    }

    @Bean
    @Scope(ConfigurableBeanFactory.SCOPE_SINGLETON)
    fun getRetrofit(client: OkHttpClient, moshi: Moshi): Retrofit {
        return Retrofit.Builder()
            .baseUrl("http://127.0.0.1")
            .client(client)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
    }

    @Bean
    @Scope(ConfigurableBeanFactory.SCOPE_SINGLETON)
    fun getPooltoolService(retrofit: Retrofit): PooltoolService {
        return retrofit.newBuilder().baseUrl("https://api.pooltool.io").build().create(PooltoolService::class.java)
    }

    @Bean("nodesChannel")
    @Scope(ConfigurableBeanFactory.SCOPE_SINGLETON)
    fun getNodesBroadcastChannel(): MutableSharedFlow<Node> {
        // Channel which broadcasts nodeIds to start monitoring for blocks
        return MutableSharedFlow()
    }

    @Bean("newBlockChannel")
    @Scope(ConfigurableBeanFactory.SCOPE_SINGLETON)
    fun getNewBlockChannel(): MutableStateFlow<Long?> {
        return MutableStateFlow(null)
    }

    @Bean
    @Scope(ConfigurableBeanFactory.SCOPE_SINGLETON)
    fun argonPasswordEncoder(): Argon2PasswordEncoder {
        return Argon2PasswordEncoder()
    }

    @Bean("latestNodeStats")
    @Scope(ConfigurableBeanFactory.SCOPE_SINGLETON)
    fun getLatestNodeStats(): AtomicReference<NodeStats?> {
        return AtomicReference(null)
    }
}