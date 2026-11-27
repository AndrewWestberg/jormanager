package com.swiftmako.jormanager.controllers.utils

import com.muquit.libsodiumjna.SodiumLibrary
import com.swiftmako.jormanager.model.GenesisByron
import com.swiftmako.jormanager.model.GenesisShelley
import com.swiftmako.jormanager.model.NodeStats
import com.swiftmako.jormanager.nodeclient.protocols.chainsync.MsgRollForwardAdapter.LEADER_VRF_HEADER
import com.swiftmako.jormanager.utils.CardanoNetworkEpochs
import com.swiftmako.jormanager.utils.Blake2b
import com.swiftmako.jormanager.utils.CardanoLeaderElection
import org.joda.time.DateTime
import org.joda.time.format.DateTimeFormat
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.context.annotation.Scope
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.getOrSet
import kotlin.experimental.xor
import kotlin.math.abs

@Component
@Scope(ConfigurableBeanFactory.SCOPE_SINGLETON)
class BlockUtils
    @Autowired
    constructor(
        @param:Qualifier("latestNodeStats") private val latestNodeStats: AtomicReference<NodeStats>,
        @Value("\${libsodium.path}") libsodiumPath: String
    ) {
        final val log: Logger = LoggerFactory.getLogger("BlockUtils")

        private val dateTimeFormat = DateTimeFormat.forPattern("yyyy-MM-dd'T'hh:mm:ss.SSS a z")

        init {
            SodiumLibrary.setLibraryPath(libsodiumPath)
            val v = SodiumLibrary.libsodiumVersionString()
            log.info("libsodium loaded, version: $v")
        }

        private val slotBytesContainer = ThreadLocal<ByteArray>()

        /**
         * The universal constant nonce. The blake2b hash of the 8 byte long value of 1
         * 12dd0a6a7d0e222a97926da03adb5a7768d31cc7c5c2bd6828e14a7d25fa3a60
         * Sometimes called seedL in the haskell code
         */
        private val ucNonce by lazy {
            Blake2b.hash256(byteArrayOf(0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x01))
        }

        fun getEpoch(
            byron: GenesisByron,
            shelley: GenesisShelley
        ): Long {
            val currentTimeSec = System.currentTimeMillis() / 1000L
            val byronEpochLength = 10L * byron.protocolConsts.k
            val byronSlotLength = byron.blockVersionData.slotDuration / 1000L
            val shelleyTransitionEpoch = getShelleyTransitionEpoch(byron, shelley)
            val byronEndTime = byron.startTime + (shelleyTransitionEpoch * byronEpochLength * byronSlotLength)
            return shelleyTransitionEpoch + ((currentTimeSec - byronEndTime) / shelley.slotLength / shelley.epochLength)
        }

        fun getEpochAndSlot(
            byron: GenesisByron,
            shelley: GenesisShelley,
            absoluteSlot: Long
        ): Pair<Long, Long> {
            val shelleyTransitionEpoch = getShelleyTransitionEpoch(byron, shelley)
            if (shelleyTransitionEpoch == -1L) {
                return Pair(-1L, -1L)
            }
            val byronEpochLength = 10L * byron.protocolConsts.k
            val byronSlots = byronEpochLength * shelleyTransitionEpoch
            val shelleySlots = absoluteSlot - byronSlots
            val shelleyEpoch = shelleyTransitionEpoch + (shelleySlots / shelley.epochLength)
            val shelleySlotInEpoch = shelleySlots % shelley.epochLength
            return Pair(shelleyEpoch, shelleySlotInEpoch)
        }

        fun slotToTimestamp(
            byron: GenesisByron,
            shelley: GenesisShelley,
            absoluteSlot: Long
        ): String {
            val networkStartTime = DateTime(byron.startTime * 1000L)
            val shelleyTransitionEpoch = getShelleyTransitionEpoch(byron, shelley)
            val byronEpochLength = 10L * byron.protocolConsts.k

            val byronSlots = byronEpochLength * shelleyTransitionEpoch
            val shelleySlots = absoluteSlot - byronSlots

            val byronSecs = (byron.blockVersionData.slotDuration * byronSlots) / 1000L
            val shelleySecs = shelleySlots * shelley.slotLength

            return dateTimeFormat.print(networkStartTime.plusSeconds((byronSecs + shelleySecs).toInt()))
        }

        fun getFirstSlotOfEpoch(
            byron: GenesisByron,
            shelley: GenesisShelley,
            absoluteSlot: Long
        ): Long {
            val shelleyTransitionEpoch = getShelleyTransitionEpoch(byron, shelley)
            if (shelleyTransitionEpoch == -1L) {
                return -1L
            }
            val byronEpochLength = 10L * byron.protocolConsts.k
            val byronSlots = byronEpochLength * shelleyTransitionEpoch
            val shelleySlots = absoluteSlot - byronSlots
            val shelleySlotInEpoch = shelleySlots % shelley.epochLength
            return absoluteSlot - shelleySlotInEpoch
        }

        fun getShelleyTransitionEpoch(
            byron: GenesisByron,
            shelley: GenesisShelley
        ): Long = CardanoNetworkEpochs.byronToShelleyEpochs(shelley)

        /**
         * true if this slot is reserved for the BFT nodes based on the decentralization parameter d
         */
        fun isOverlaySlot(
            firstSlotOfEpoch: Long,
            currentSlot: Long,
            d: BigDecimal
        ): Boolean {
            if (d.signum() == 0) {
                return false
            }
            val diffSlot = abs(currentSlot - firstSlotOfEpoch)
            return d
                .times(diffSlot.toBigDecimal())
                .setScale(0, RoundingMode.CEILING) <
                d
                    .times((diffSlot + 1L).toBigDecimal())
                    .setScale(0, RoundingMode.CEILING)
        }

        /** TPraos uses the unmodified 64-byte VRF output. */
        fun isSlotLeaderTPraos(
            slot: Long,
            threshold: CardanoLeaderElection.LeaderThreshold,
            eta0: ByteArray,
            poolVrfSkey: ByteArray
        ): Boolean {
            require(threshold.vrfSizeBytes == 64) { "TPraos requires a 64-byte threshold" }
            val seed = mkSeed(slot, eta0)
            return threshold.isLeader(vrfEvalCertified(seed, poolVrfSkey))
        }

        /** Praos range-extends the VRF output with Blake2b-256("L" <> rawVRF). */
        fun isSlotLeaderPraos(
            slot: Long,
            threshold: CardanoLeaderElection.LeaderThreshold,
            eta0: ByteArray,
            poolVrfSkey: ByteArray
        ): Boolean {
            require(threshold.vrfSizeBytes == 32) { "Praos requires a 32-byte threshold" }
            val seed = mkInputVRF(slot, eta0)
            return threshold.isLeader(vrfLeaderValue(vrfEvalCertified(seed, poolVrfSkey)))
        }

        /**
         * Create the leadership seed value for Alonzo and earlier epochs (TPraos)
         * @param slot The slot to create the leadership seed value for
         * @param eta0 The epoch nonce value
         */
        fun mkSeed(
            slot: Long,
            eta0: ByteArray
        ): ByteArray {
            val slotToSeedByteArray = Blake2b.hash256(slotToBigEndianBytes(slot), eta0)

            val constant = ucNonce
            for (index in slotToSeedByteArray.indices) {
                slotToSeedByteArray[index] = slotToSeedByteArray[index] xor constant[index]
            }
            return slotToSeedByteArray
        }

        /**
         * Create the leadership input VRF value for Babbage and later epochs (Praos)
         * @param slot The slot to create the leadership value for
         * @param eta0 The epoch nonce value
         */
        fun mkInputVRF(
            slot: Long,
            eta0: ByteArray
        ): ByteArray = Blake2b.hash256(slotToBigEndianBytes(slot), eta0)

        private fun slotToBigEndianBytes(slot: Long): ByteArray {
            val bytes = slotBytesContainer.getOrSet { ByteArray(8) }
            bytes[0] = (slot ushr 56).toByte()
            bytes[1] = (slot ushr 48).toByte()
            bytes[2] = (slot ushr 40).toByte()
            bytes[3] = (slot ushr 32).toByte()
            bytes[4] = (slot ushr 24).toByte()
            bytes[5] = (slot ushr 16).toByte()
            bytes[6] = (slot ushr 8).toByte()
            bytes[7] = slot.toByte()
            return bytes
        }

        fun vrfLeaderValue(rawVrf: ByteArray): ByteArray = Blake2b.hash256(LEADER_VRF_HEADER, rawVrf)

        /**
         * Sign and hash the slot seed value with our node vrf signing key. The output is the certified natural (certNat)
         */
        fun vrfEvalCertified(
            seed: ByteArray,
            tpraosCanBeLeaderSignKeyVRF: ByteArray
        ): ByteArray {
//        // output of mkseed for slot 7397153L
//        val mkSeed = "7e2a4eff14e47fa5d8df184a1d6c3e8906837224a7f9392d1994293a4bc6d709".hexToByteArray()
//        // prfit.vrf.skey
//        val tpraosCanBeLeaderSignKeyVRF = "30780cc944d4c32d5774d1b1c102f69ca954095722c26f0696dc39adec9532f09162a3ec9fa00531afea9c26bddbb663fe95904e9076fd86b94f9c075b83fd30".hexToByteArray()

            val certifiedProof = SodiumLibrary.cryptoVrfProve_ietfdraft03(tpraosCanBeLeaderSignKeyVRF, seed)

            // return the certVRF value
            return SodiumLibrary.cryptoVrfProofToHash_ietfdraft03(certifiedProof)
        }
    }
