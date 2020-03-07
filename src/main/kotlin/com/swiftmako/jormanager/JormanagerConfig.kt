package com.swiftmako.jormanager

import com.swiftmako.jormanager.utils.JormanagerProperties
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.context.annotation.Scope
import org.springframework.stereotype.Component

@Component
@Scope(value = ConfigurableBeanFactory.SCOPE_PROTOTYPE)
class JormanagerConfig @Autowired constructor(properties: JormanagerProperties) {

    val nodeCount: Int = properties.getIntProperty("jormanager.nodecount")
    val standbyMode: Boolean = properties.getBooleanProperty("jormanager.standby.mode")
    val leaderElectionDelayMs: Long = properties.getMillisProperty("jormanager.leader_election_delay")
    val maxBlocksBehind: Int = properties.getIntProperty("jormanager.max_blocks_behind")
    val nodeProbationSecs: Long = properties.getSecondsProperty("jormanager.node_probation")
    val nodeSequentialApiFailuresAllowed: Int = properties.getIntProperty("jormanager.node_sequential_api_failures_allowed")
    val maxBootstrapMs: Long = properties.getMillisProperty("jormanager.max_bootstrap")
    val nodeStaggerByBootstrap: Boolean = properties.getBooleanProperty("jormanager.node_stagger_by_bootstrap")
    val nodeStaggerMs: Long = properties.getMillisProperty("jormanager.node_stagger")
    val nodeStatsTimeoutMs: Long = properties.getMillisProperty("jormanager.nodestats_timeout")
    val incrementPublicIdEnabled: Boolean = properties.getBooleanProperty("jormanager.increment.public_id.enabled")
    val pooltoolEnabled: Boolean = properties.getBooleanProperty("jormanager.pooltool.enabled")
    val pooltoolJormverEnabled: Boolean = properties.getBooleanProperty("jormanager.pooltool.jormver.enabled")
    val pooltoolDelayMs: Long = properties.getMillisProperty("jormanager.pooltool.delay")
    val pooltoolPoolId: String = properties.getStringProperty("jormanager.pooltool.poolId")
    val pooltoolUserId: String = properties.getStringProperty("jormanager.pooltool.userId")
    val pooltoolGenesisPref: String = properties.getStringProperty("jormanager.pooltool.genesisPref")
    val pooltoolKeystorage: String = properties.getStringProperty("jormanager.pooltool.keystorage")
    val blockLogPath: String = properties.getStringProperty("jormanager.block_log")
    val statsLogPath: String = properties.getStringProperty("jormanager.stats_log")
    val restApiUrlPattern: String = properties.getStringProperty("jormanager.jormungandr.rest_api_url")
    val jormungandrLogPath: String = properties.getStringProperty("jormanager.jormungandr.log_location")
    val jormungandrProcessPath: String = properties.getStringProperty("jormanager.jormungandr.process")
    val jormungandrStoragePath: String = properties.getStringProperty("jormanager.jormungandr.storage")
    val jormungandrConfigPath: String = properties.getStringProperty("jormanager.jormungandr.config")
    val jormungandrGenesisHash: String = properties.getStringProperty("jormanager.jormungandr.genesis")
    val jormungandrSecretPath: String = properties.getStringProperty("jormanager.jormungandr.secret")
    val jormungandrSecretJsonPath: String = properties.getStringProperty("jormanager.jormungandr.secret_json")
    val jormanagerUfwEnabled: Boolean = properties.getBooleanProperty("jormanager.ufw.enabled")
    val jormanagerUfwPassiveEnabled: Boolean = properties.getBooleanProperty("jormanager.ufw.passive.enabled")
    val jormanagerUfwLowerLimit: Int = properties.getIntProperty("jormanager.ufw.lower_limit")
    val jormanagerUfwUpperLimit: Int = properties.getIntProperty("jormanager.ufw.upper_limit")
    val jormanagerUfwAllowCmd: String = properties.getStringProperty("jormanager.ufw.allow")
    val jormanagerUfwDenyCmd: String = properties.getStringProperty("jormanager.ufw.deny")
    val passiveNodeList: List<Boolean> = properties.getBooleanListProperty("jormanager.node.passive")
    val useLightweightPeerCount: Boolean = properties.getBooleanProperty("jormanager.lightweight.peercount")
    val minPeersForSDCalculationEnabled: Boolean = properties.getBooleanProperty("jormanager.minpeers.sdcalculation.enabled")
    val minPeersForSDCalculation: Int = properties.getIntProperty("jormanager.minpeers.sdcalculation")
    val minPeersBadSDLimit: Double = properties.getDoubleProperty("jormanager.minpeers.badsdlimit")
    val peersOutputEnabled: Boolean = properties.getBooleanProperty("jormanager.peers.output.enabled")
    val peersOutputIp: String = properties.getStringProperty("jormanager.peers.output_ip")
    val peersOutputPort: String = properties.getStringProperty("jormanager.peers.output_port")
    val peersOutputLogPath: String = properties.getStringProperty("jormanager.peers.output_log")
    val killPath: String = properties.getStringProperty("jormanager.kill.path")
    val leadershipProbationEnabled: Boolean = properties.getBooleanProperty("jormanager.leadership.probation.enabled")
    val leadershipProbationDurationMs: Long = properties.getMillisProperty("jormanager.leadership.probation.duration")
    val processPriorityEnabled: Boolean = properties.getBooleanProperty("jormanager.processpriority.enabled")
    val processPriorityHighCmd: String = properties.getStringProperty("jormanager.processpriority.high")
    val processPriorityNormalCmd: String = properties.getStringProperty("jormanager.processpriority.normal")

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as JormanagerConfig

        if (nodeCount != other.nodeCount) return false
        if (standbyMode != other.standbyMode) return false
        if (leaderElectionDelayMs != other.leaderElectionDelayMs) return false
        if (maxBlocksBehind != other.maxBlocksBehind) return false
        if (nodeProbationSecs != other.nodeProbationSecs) return false
        if (nodeSequentialApiFailuresAllowed != other.nodeSequentialApiFailuresAllowed) return false
        if (maxBootstrapMs != other.maxBootstrapMs) return false
        if (nodeStaggerByBootstrap != other.nodeStaggerByBootstrap) return false
        if (nodeStaggerMs != other.nodeStaggerMs) return false
        if (nodeStatsTimeoutMs != other.nodeStatsTimeoutMs) return false
        if (incrementPublicIdEnabled != other.incrementPublicIdEnabled) return false
        if (pooltoolEnabled != other.pooltoolEnabled) return false
        if (pooltoolJormverEnabled != other.pooltoolJormverEnabled) return false
        if (pooltoolDelayMs != other.pooltoolDelayMs) return false
        if (pooltoolPoolId != other.pooltoolPoolId) return false
        if (pooltoolUserId != other.pooltoolUserId) return false
        if (pooltoolGenesisPref != other.pooltoolGenesisPref) return false
        if (pooltoolKeystorage != other.pooltoolKeystorage) return false
        if (blockLogPath != other.blockLogPath) return false
        if (statsLogPath != other.statsLogPath) return false
        if (restApiUrlPattern != other.restApiUrlPattern) return false
        if (jormungandrLogPath != other.jormungandrLogPath) return false
        if (jormungandrProcessPath != other.jormungandrProcessPath) return false
        if (jormungandrStoragePath != other.jormungandrStoragePath) return false
        if (jormungandrConfigPath != other.jormungandrConfigPath) return false
        if (jormungandrGenesisHash != other.jormungandrGenesisHash) return false
        if (jormungandrSecretPath != other.jormungandrSecretPath) return false
        if (jormungandrSecretJsonPath != other.jormungandrSecretJsonPath) return false
        if (jormanagerUfwEnabled != other.jormanagerUfwEnabled) return false
        if (jormanagerUfwPassiveEnabled != other.jormanagerUfwPassiveEnabled) return false
        if (jormanagerUfwLowerLimit != other.jormanagerUfwLowerLimit) return false
        if (jormanagerUfwUpperLimit != other.jormanagerUfwUpperLimit) return false
        if (jormanagerUfwAllowCmd != other.jormanagerUfwAllowCmd) return false
        if (jormanagerUfwDenyCmd != other.jormanagerUfwDenyCmd) return false
        if (passiveNodeList != other.passiveNodeList) return false
        if (useLightweightPeerCount != other.useLightweightPeerCount) return false
        if (minPeersForSDCalculationEnabled != other.minPeersForSDCalculationEnabled) return false
        if (minPeersForSDCalculation != other.minPeersForSDCalculation) return false
        if (minPeersBadSDLimit != other.minPeersBadSDLimit) return false
        if (peersOutputEnabled != other.peersOutputEnabled) return false
        if (peersOutputIp != other.peersOutputIp) return false
        if (peersOutputPort != other.peersOutputPort) return false
        if (peersOutputLogPath != other.peersOutputLogPath) return false
        if (killPath != other.killPath) return false
        if (leadershipProbationEnabled != other.leadershipProbationEnabled) return false
        if (leadershipProbationDurationMs != other.leadershipProbationDurationMs) return false
        if (processPriorityEnabled != other.processPriorityEnabled) return false
        if (processPriorityHighCmd != other.processPriorityHighCmd) return false
        if (processPriorityNormalCmd != other.processPriorityNormalCmd) return false

        return true
    }

    override fun hashCode(): Int {
        var result = nodeCount
        result = 31 * result + standbyMode.hashCode()
        result = 31 * result + leaderElectionDelayMs.hashCode()
        result = 31 * result + maxBlocksBehind
        result = 31 * result + nodeProbationSecs.hashCode()
        result = 31 * result + nodeSequentialApiFailuresAllowed
        result = 31 * result + maxBootstrapMs.hashCode()
        result = 31 * result + nodeStaggerByBootstrap.hashCode()
        result = 31 * result + nodeStaggerMs.hashCode()
        result = 31 * result + nodeStatsTimeoutMs.hashCode()
        result = 31 * result + incrementPublicIdEnabled.hashCode()
        result = 31 * result + pooltoolEnabled.hashCode()
        result = 31 * result + pooltoolJormverEnabled.hashCode()
        result = 31 * result + pooltoolDelayMs.hashCode()
        result = 31 * result + pooltoolPoolId.hashCode()
        result = 31 * result + pooltoolUserId.hashCode()
        result = 31 * result + pooltoolGenesisPref.hashCode()
        result = 31 * result + pooltoolKeystorage.hashCode()
        result = 31 * result + blockLogPath.hashCode()
        result = 31 * result + statsLogPath.hashCode()
        result = 31 * result + restApiUrlPattern.hashCode()
        result = 31 * result + jormungandrLogPath.hashCode()
        result = 31 * result + jormungandrProcessPath.hashCode()
        result = 31 * result + jormungandrStoragePath.hashCode()
        result = 31 * result + jormungandrConfigPath.hashCode()
        result = 31 * result + jormungandrGenesisHash.hashCode()
        result = 31 * result + jormungandrSecretPath.hashCode()
        result = 31 * result + jormungandrSecretJsonPath.hashCode()
        result = 31 * result + jormanagerUfwEnabled.hashCode()
        result = 31 * result + jormanagerUfwPassiveEnabled.hashCode()
        result = 31 * result + jormanagerUfwLowerLimit
        result = 31 * result + jormanagerUfwUpperLimit
        result = 31 * result + jormanagerUfwAllowCmd.hashCode()
        result = 31 * result + jormanagerUfwDenyCmd.hashCode()
        result = 31 * result + passiveNodeList.hashCode()
        result = 31 * result + useLightweightPeerCount.hashCode()
        result = 31 * result + minPeersForSDCalculationEnabled.hashCode()
        result = 31 * result + minPeersForSDCalculation
        result = 31 * result + minPeersBadSDLimit.hashCode()
        result = 31 * result + peersOutputEnabled.hashCode()
        result = 31 * result + peersOutputIp.hashCode()
        result = 31 * result + peersOutputPort.hashCode()
        result = 31 * result + peersOutputLogPath.hashCode()
        result = 31 * result + killPath.hashCode()
        result = 31 * result + leadershipProbationEnabled.hashCode()
        result = 31 * result + leadershipProbationDurationMs.hashCode()
        result = 31 * result + processPriorityEnabled.hashCode()
        result = 31 * result + processPriorityHighCmd.hashCode()
        result = 31 * result + processPriorityNormalCmd.hashCode()
        return result
    }

    override fun toString(): String {
        return "JormanagerConfig(nodeCount=$nodeCount, standbyMode=$standbyMode, leaderElectionDelayMs=$leaderElectionDelayMs, maxBlocksBehind=$maxBlocksBehind, nodeProbationSecs=$nodeProbationSecs, nodeSequentialApiFailuresAllowed=$nodeSequentialApiFailuresAllowed, maxBootstrapMs=$maxBootstrapMs, nodeStaggerByBootstrap=$nodeStaggerByBootstrap, nodeStaggerMs=$nodeStaggerMs, nodeStatsTimeoutMs=$nodeStatsTimeoutMs, incrementPublicIdEnabled=$incrementPublicIdEnabled, pooltoolEnabled=$pooltoolEnabled, pooltoolJormverEnabled=$pooltoolJormverEnabled, pooltoolDelayMs=$pooltoolDelayMs, pooltoolPoolId='$pooltoolPoolId', pooltoolUserId='$pooltoolUserId', pooltoolGenesisPref='$pooltoolGenesisPref', pooltoolKeystorage='$pooltoolKeystorage', blockLogPath='$blockLogPath', statsLogPath='$statsLogPath', restApiUrlPattern='$restApiUrlPattern', jormungandrLogPath='$jormungandrLogPath', jormungandrProcessPath='$jormungandrProcessPath', jormungandrStoragePath='$jormungandrStoragePath', jormungandrConfigPath='$jormungandrConfigPath', jormungandrGenesisHash='$jormungandrGenesisHash', jormungandrSecretPath='$jormungandrSecretPath', jormungandrSecretJsonPath='$jormungandrSecretJsonPath', jormanagerUfwEnabled=$jormanagerUfwEnabled, jormanagerUfwPassiveEnabled=$jormanagerUfwPassiveEnabled, jormanagerUfwLowerLimit=$jormanagerUfwLowerLimit, jormanagerUfwUpperLimit=$jormanagerUfwUpperLimit, jormanagerUfwAllowCmd='$jormanagerUfwAllowCmd', jormanagerUfwDenyCmd='$jormanagerUfwDenyCmd', passiveNodeList=$passiveNodeList, useLightweightPeerCount=$useLightweightPeerCount, minPeersForSDCalculationEnabled=$minPeersForSDCalculationEnabled, minPeersForSDCalculation=$minPeersForSDCalculation, minPeersBadSDLimit=$minPeersBadSDLimit, peersOutputEnabled=$peersOutputEnabled, peersOutputIp='$peersOutputIp', peersOutputPort='$peersOutputPort', peersOutputLogPath='$peersOutputLogPath', killPath='$killPath', leadershipProbationEnabled=$leadershipProbationEnabled, leadershipProbationDurationMs=$leadershipProbationDurationMs, processPriorityEnabled=$processPriorityEnabled, processPriorityHighCmd='$processPriorityHighCmd', processPriorityNormalCmd='$processPriorityNormalCmd')"
    }
}