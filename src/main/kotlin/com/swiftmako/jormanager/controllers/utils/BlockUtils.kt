package com.swiftmako.jormanager.controllers.utils

import com.muquit.libsodiumjna.SodiumLibrary
import com.swiftmako.jormanager.ktx.hexToByteArray
import com.swiftmako.jormanager.ktx.toHexString
import com.swiftmako.jormanager.model.GenesisByron
import com.swiftmako.jormanager.model.GenesisShelley
import com.swiftmako.jormanager.model.NodeStats
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
import java.math.BigInteger
import java.math.RoundingMode
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicReference
import kotlin.experimental.xor
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln

@Component
@Scope(ConfigurableBeanFactory.SCOPE_SINGLETON)
class BlockUtils @Autowired constructor(
        @Qualifier("latestNodeStats") private val latestNodeStats: AtomicReference<NodeStats>,
        @Value("\${libsodium.path}") libsodiumPath: String
) {
    final val log: Logger = LoggerFactory.getLogger("BlockUtils")

    private val dateTimeFormat = DateTimeFormat.forPattern("yyyy-MM-dd'T'hh:mm:ss.SSS a z")

    init {
        SodiumLibrary.setLibraryPath(libsodiumPath)
        val v = SodiumLibrary.libsodiumVersionString()
        log.info("libsodium loaded, version: $v")
    }

    /**
     * The universal constant nonce. The blake2b hash of the 8 byte long value of 1
     * 12dd0a6a7d0e222a97926da03adb5a7768d31cc7c5c2bd6828e14a7d25fa3a60
     * Sometimes called seedL in the haskell code
     */
    private val ucNonce by lazy {
        SodiumLibrary.cryptoBlake2bHash(
                byteArrayOf(0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x01), null
        )
    }

    fun getEpoch(byron: GenesisByron, shelley: GenesisShelley): Long {
        val currentTimeSec = System.currentTimeMillis() / 1000L
        val byronEpochLength = 10L * byron.protocolConsts.k
        val byronSlotLength = byron.blockVersionData.slotDuration / 1000L
        val shelleyTransitionEpoch = getShelleyTransitionEpoch(byron, shelley)
        val byronEndTime = byron.startTime + (shelleyTransitionEpoch * byronEpochLength * byronSlotLength)
        return shelleyTransitionEpoch + ((currentTimeSec - byronEndTime) / shelley.slotLength / shelley.epochLength)
    }

    fun getEpochAndSlot(byron: GenesisByron, shelley: GenesisShelley, absoluteSlot: Long): Pair<Long, Long> {
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

    fun slotToTimestamp(byron: GenesisByron, shelley: GenesisShelley, absoluteSlot: Long): String {
        val networkStartTime = DateTime(byron.startTime * 1000L)
        val shelleyTransitionEpoch = getShelleyTransitionEpoch(byron, shelley)
        val byronEpochLength = 10L * byron.protocolConsts.k

        val byronSlots = byronEpochLength * shelleyTransitionEpoch
        val shelleySlots = absoluteSlot - byronSlots

        val byronSecs = (byron.blockVersionData.slotDuration * byronSlots) / 1000L
        val shelleySecs = shelleySlots * shelley.slotLength

        return dateTimeFormat.print(networkStartTime.plusSeconds((byronSecs + shelleySecs).toInt()))
    }

    fun getFirstSlotOfEpoch(byron: GenesisByron, shelley: GenesisShelley, absoluteSlot: Long): Long {
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

    fun getShelleyTransitionEpoch(byron: GenesisByron, shelley: GenesisShelley): Long {
        latestNodeStats.get()?.let { nodeStats ->
            if (nodeStats.epoch == null || nodeStats.slot == null || nodeStats.slotInEpoch == null) {
                return -1L
            }
            val byronEpochLength = 10L * byron.protocolConsts.k
            var calcSlot = 0L
            var byronEpochs = nodeStats.epoch
            val slotInEpoch = nodeStats.slotInEpoch
            val slot = nodeStats.slot
            var shelleyEpochs = 0L
            while (byronEpochs >= 0L) {
                calcSlot = (byronEpochs * byronEpochLength) + (shelleyEpochs * shelley.epochLength) + slotInEpoch
                if (calcSlot == slot) {
                    break
                }
                byronEpochs--
                shelleyEpochs++
            }

            if (calcSlot != slot || shelleyEpochs == 0L) {
                return -1L
            }

            return byronEpochs
        }
        return -1L
    }

    /**
     * true if this slot is reserved for the BFT nodes based on the decentralization parameter d
     */
    fun isOverlaySlot(firstSlotOfEpoch: Long, currentSlot: Long, d: BigDecimal): Boolean {
        val diffSlot = abs(currentSlot - firstSlotOfEpoch)
        return d.times(diffSlot.toBigDecimal()).setScale(0, RoundingMode.CEILING) < d.times((diffSlot + 1L).toBigDecimal()).setScale(0, RoundingMode.CEILING)
    }

    /**
     * Determine if our pool is a slot leader for this given slot
     * @param slot The slot to check
     * @param f The activeSlotsCoeff value from protocol params
     * @param sigma The controlled stake proportion for the pool
     * @param eta0 The epoch nonce value
     * @param poolVrfSkey The vrf signing key for the pool
     */
    fun isSlotLeader(slot: Long, f: Double, sigma: BigDecimal, eta0: ByteArray, poolVrfSkey: ByteArray): Boolean {

        val seed = mkSeed(slot, eta0)
        // add 00 to make sure we don't get a negative number by accident
        val certVRF = ("00" + vrfEvalCertified(seed, poolVrfSkey).toHexString()).hexToByteArray()

        val certVRFHex = certVRF.toHexString()

        val certNat = BigInteger(certVRF)

        val certNatMax = BigInteger("2").pow(8 * 64) // 8 * vrfoutput bytes
        val denominator = certNatMax.minus(certNat)

        val q = certNatMax.toBigDecimal().divide(denominator.toBigDecimal(), 34, RoundingMode.CEILING)

        val c = ln(1.0 - f)
        val sigmaOfF = exp(-sigma.toDouble() * c)

        // return true if q <= sigmaOfF
        if (q.compareTo(sigmaOfF.toBigDecimal()) != 1) {
            log.warn("isLeader($slot): $q <= $sigmaOfF Difference Of: ${sigmaOfF.toBigDecimal() - q}")
        }
        return q.compareTo(sigmaOfF.toBigDecimal()) != 1
    }

    /**
     * Create the leadership seed value
     * @param slot The slot to create the leadership seed value for
     * @param eta0 The epoch nonce value
     */
    fun mkSeed(slot: Long, eta0: ByteArray): ByteArray {
//        // The epoch nonce for 87 on testnet (figure out how to calculate it ourselves later instead of trace from the node)
//        val eta0 = "70c0f591099a8de944e02585841d602479493f2d7e360f04c8b8cf990988eda3".hexToByteArray()

        val concatByteArray = ByteArray(8 + 32)
        val concatByteBuffer = ByteBuffer.wrap(concatByteArray)
        concatByteBuffer.putLong(slot)
        concatByteBuffer.put(eta0)

        val slotToSeedByteArray = SodiumLibrary.cryptoBlake2bHash(concatByteArray, null)

        // return the computed seed value
        return ucNonce.mapIndexed { index, byte -> byte xor slotToSeedByteArray[index] }.toByteArray()
    }

    /**
     * Sign and hash the slot seed value with our node vrf signing key. The output is the certified natural (certNat)
     */
    private fun vrfEvalCertified(seed: ByteArray, tpraosCanBeLeaderSignKeyVRF: ByteArray): ByteArray {

//        // output of mkseed for slot 7397153L
//        val mkSeed = "7e2a4eff14e47fa5d8df184a1d6c3e8906837224a7f9392d1994293a4bc6d709".hexToByteArray()
//        // prfit.vrf.skey
//        val tpraosCanBeLeaderSignKeyVRF = "30780cc944d4c32d5774d1b1c102f69ca954095722c26f0696dc39adec9532f09162a3ec9fa00531afea9c26bddbb663fe95904e9076fd86b94f9c075b83fd30".hexToByteArray()

        val certifiedProof = SodiumLibrary.cryptoVrfProve(tpraosCanBeLeaderSignKeyVRF, seed)

        // return the certVRF value
        return SodiumLibrary.cryptoVrfProofToHash(certifiedProof)
    }

}