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
    val leaderElectionDelayMs: Long = properties.getLongProperty("jormanager.leader_election_delay_ms")
    val maxBlocksBehind: Int = properties.getIntProperty("jormanager.max_blocks_behind")
    val nodeProbationSecs: Long = properties.getLongProperty("jormanager.node_probation_secs")
    val maxBootstrapMs: Long = properties.getLongProperty("jormanager.max_bootstrap_ms")
    val nodeStaggerMs: Long = properties.getLongProperty("jormanager.node_stagger_ms")
    val pooltoolEnabled: Boolean = properties.getBooleanProperty("jormanager.pooltool.enabled")
    val pooltoolJormverEnabled: Boolean = properties.getBooleanProperty("jormanager.pooltool.jormver.enabled")
    val pooltoolPoolId: String = properties.getStringProperty("jormanager.pooltool.poolId")
    val pooltoolUserId: String = properties.getStringProperty("jormanager.pooltool.userId")
    val pooltoolGenesisPref: String = properties.getStringProperty("jormanager.pooltool.genesisPref")
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
    val jormanagerUfwLowerLimit: Int = properties.getIntProperty("jormanager.ufw.lower_limit")
    val jormanagerUfwUpperLimit: Int = properties.getIntProperty("jormanager.ufw.upper_limit")
    val jormanagerUfwAllowCmd: String = properties.getStringProperty("jormanager.ufw.allow")
    val jormanagerUfwDenyCmd: String = properties.getStringProperty("jormanager.ufw.deny")
    val passiveNodeList: List<Boolean> = properties.getBooleanListProperty("jormanager.node.passive")
    val useLightweightPeerCount: Boolean = properties.getBooleanProperty("jormanager.lightweight.peercount")

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as JormanagerConfig

        if (nodeCount != other.nodeCount) return false
        if (leaderElectionDelayMs != other.leaderElectionDelayMs) return false
        if (maxBlocksBehind != other.maxBlocksBehind) return false
        if (nodeProbationSecs != other.nodeProbationSecs) return false
        if (maxBootstrapMs != other.maxBootstrapMs) return false
        if (nodeStaggerMs != other.nodeStaggerMs) return false
        if (pooltoolEnabled != other.pooltoolEnabled) return false
        if (pooltoolJormverEnabled != other.pooltoolJormverEnabled) return false
        if (pooltoolPoolId != other.pooltoolPoolId) return false
        if (pooltoolUserId != other.pooltoolUserId) return false
        if (pooltoolGenesisPref != other.pooltoolGenesisPref) return false
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
        if (jormanagerUfwLowerLimit != other.jormanagerUfwLowerLimit) return false
        if (jormanagerUfwUpperLimit != other.jormanagerUfwUpperLimit) return false
        if (jormanagerUfwAllowCmd != other.jormanagerUfwAllowCmd) return false
        if (jormanagerUfwDenyCmd != other.jormanagerUfwDenyCmd) return false
        if (passiveNodeList != other.passiveNodeList) return false
        if (useLightweightPeerCount != other.useLightweightPeerCount) return false

        return true
    }

    override fun hashCode(): Int {
        var result = nodeCount
        result = 31 * result + leaderElectionDelayMs.hashCode()
        result = 31 * result + maxBlocksBehind
        result = 31 * result + nodeProbationSecs.hashCode()
        result = 31 * result + maxBootstrapMs.hashCode()
        result = 31 * result + nodeStaggerMs.hashCode()
        result = 31 * result + pooltoolEnabled.hashCode()
        result = 31 * result + pooltoolJormverEnabled.hashCode()
        result = 31 * result + pooltoolPoolId.hashCode()
        result = 31 * result + pooltoolUserId.hashCode()
        result = 31 * result + pooltoolGenesisPref.hashCode()
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
        result = 31 * result + jormanagerUfwLowerLimit
        result = 31 * result + jormanagerUfwUpperLimit
        result = 31 * result + jormanagerUfwAllowCmd.hashCode()
        result = 31 * result + jormanagerUfwDenyCmd.hashCode()
        result = 31 * result + passiveNodeList.hashCode()
        result = 31 * result + useLightweightPeerCount.hashCode()
        return result
    }
}