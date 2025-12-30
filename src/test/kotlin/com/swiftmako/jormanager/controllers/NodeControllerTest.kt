package com.swiftmako.jormanager.controllers

import com.google.common.truth.Truth.assertThat
import com.squareup.moshi.Moshi
import com.swiftmako.jormanager.entities.Host
import com.swiftmako.jormanager.entities.SocketResponse
import com.swiftmako.jormanager.model.*
import com.swiftmako.jormanager.spring.config.Configuration
import io.mockk.every
import io.mockk.mockk
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.springframework.data.repository.findByIdOrNull
import retrofit2.Retrofit

class NodeControllerTest {
    @Test
    @Disabled
    fun testCreateNode() {
        val config = Configuration()
        val moshi = config.getMoshi()
        val target =
            NodeController(
                nodeRepository = mockk(relaxed = true),
                hostRepository =
                    mockk(relaxed = true) {
                        every { findByIdOrNull(1L) } returns
                            Host(
                                id = 1L,
                                type = "local",
                                cardanoCliPath = "/home/westbam/.local/bin/cardano-cli",
                                cardanoNodePath = "/home/westbam/.local/bin/cardano-node",
                                hostname = "brainy",
                                sshUser = "westbam",
                                nodeHomePath = "/home/westbam/haskell",
                                jcliPath = "/home/westbam/.cargo/bin/jcli"
                            )
                    },
                fileRepository = mockk(relaxed = true),
                walletRepository = mockk(relaxed = true),
                walletUtils = mockk(relaxed = true) {
                    every { isValidSpendingPassword(any()) } returns true
                },
                transactionRepository = mockk(relaxed = true),
                webSocketTemplate = mockk(relaxed = true),
                nodesChannel = mockk(relaxed = true),
                retrofit = Retrofit.Builder().baseUrl("http://dummy.com").build(),
                okHttpClient = OkHttpClient.Builder().build(),
                relayRepository = mockk(relaxed = true),
                extendedMetadataAdapter = config.getExtendedMetadataAdapter(moshi),
                shelleyGenesisAdapter = config.getShelleyGenesisAdapter(moshi),
                byronGenesisAdapter = config.getByronGenesisAdapter(moshi),
                metadataAdapter = config.getMetadataAdapter(moshi),
                protocolParamsAdapter = config.getProtocolParametersAdapter(moshi),
                queryTipAdapter = config.getQueryTipAdapter(moshi),
                keyFileJsonAdapter = mockk(relaxed = true),
                bulkCredentialsJsonAdapter = mockk(relaxed = true),
                txSignedAdapter = mockk(relaxed = true),
                ledgerDao = mockk(relaxed = true),
                cardanoUtils = mockk(relaxed = true),
                cardanoRepository = mockk(relaxed = true),
            )

        val request =
            CreateNodeRequest(
                color = "#0000FF",
                hostId = 1L,
                name = "tickr",
                isDefault = false,
                type = "core",
                processorThreads = 8,
                listen = "127.0.0.1",
                port = 6001,
                genesisByronFileId = 1L,
                genesisShelleyFileId = 2L,
                genesisAlonzoFileId = 3L,
                genesisConwayFileId = 4L,
                generateColdKeys = true,
                coldSKey = null,
                coldVKey = null,
                coldCounter = null,
                generateVRFKeys = true,
                vrfSKey = null,
                vrfVKey = null,
                generateKESKeys = true,
                kesSKey = null,
                kesVKey = null,
                registrationFeesAccount = 1L,
                ownerStakingAccount = 2L,
                rewardsStakingAccount = 3L,
                poolPledge = 250000000000L.toBigInteger(),
                poolCost = 340000000L.toBigInteger(),
                poolMargin = "0.05",
                relays =
                    listOf(
                        Relay("relay0.flippin-stakes.com", 3001),
                        Relay("relay1.flippin-stakes.com", 3001),
                        Relay("52.98.47.198", 3001)
                    ),
                metadata =
                    com.swiftmako.jormanager.model.Metadata(
                        ticker = "TICKR",
                        name = "Flippin Stakes Pool",
                        description = "The best stakepool in Flippin, Arkansas!",
                        homepage = "https://flippin-stakes.com",
                        extended =
                            Extended(
                                itn =
                                    Itn(
                                        publicKey = "ed25519_pk1ly9wqdrgfjjskzy503z9dt8qsn5285uay3trfts2pdg85qwfkqnscrsknk",
                                        privateKey = "ed25519e_sk1jq49wy7uq4tepqumk2c9q3ym6fwlvk9g7l68e2kqzyafyx7hd9nvzjnrf8y5796mjrasyda7hgnzmk6jhyq88kkzg4l6us2x438n7tczxz02x"
                                    ),
                                info =
                                    Info(
                                        icon64 = "https://flippin-stakes.com/icon64x64.png",
                                        logo = "https://flippin-stakes.com/logo.png",
                                        location = "United States, North America",
                                        social =
                                            Social(
                                                twitter = "flippinstakes",
                                                telegram = "flippin_stakes_telegram_group",
                                                facebook = "flippin_stakes_facebook",
                                                youtube = "flippin_stakes_youtube",
                                                discord = "flippin_stakes_discord",
                                                github = "flippin_stakes_github",
                                                twitch = "flippin_stakes_twitch"
                                            ),
                                        company =
                                            Company(
                                                name = "Flippin Stakes, LLC.",
                                                addr = "123 Backflip Ln.",
                                                city = "Flippin, AK",
                                                country = "United States",
                                                companyId = "38391290",
                                                vatId = "2934858"
                                            ),
                                        about =
                                            About(
                                                me = "Best damn pool operator in Flippin, Arkansas!",
                                                server = "EPYC with lots 'o RAM!",
                                                company = "Flippin Stakes, LLC."
                                            ),
                                        rss = "https://flippin-stakes.com/atom.xml"
                                    ),
                                telegramAdminHandle = "Bubba1977",
                                adapoolsVerify = null
                            )
                    ),
                sudoPassword = "asdfasdf",
                spendingPassword = "asdfasdf",
                ekgPort = 12788,
                promPort = 12789,
                parentId = null,
            )
        val response = target.createNode(request)

        assertThat(response).isInstanceOf(SocketResponse.Success::class.java)
    }
}
