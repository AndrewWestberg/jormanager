import _ from 'lodash'

export default {
    isDebug: () => {
        if (window.webpackHotUpdate) {
            return true
        } else {
            return false
        }
    },
    websocketUrl: (_, getters) => {
        return getters.isDebug ?
            // debug mode
            "http://localhost:8787/jormanager-websocket" :
            // release mode
            "/jormanager-websocket"
    },
    backupDownloadUrl: (_, getters) => {
        return getters.isDebug ?
            // debug mode
            "http://localhost:8787/jormanager_backup.zip" :
            // release mode
            "/jormanager_backup.zip"
    },
    blocksCount: (state) => {
        return state.blocks.length
    },
    hostsCount: (state) => {
        return state.hosts.length
    },
    hostSelectOptions: (state) => {
        return _.sortBy(
            _.map(state.hosts, (host) => {
                return {
                    value: host.id,
                    text: host.hostname
                }
            }), ['text'])
    },
    stakingSKeys: (state) => {
        return _.sortBy(_.filter(state.files, (file) => {
            return file.text.match(/.*\.staking\.skey/i) != null
        }), ['text'])
    },
    stakingVKeys: (state) => {
        return _.sortBy(_.filter(state.files, (file) => {
            return file.text.match(/.*\.staking\.vkey/i) != null
        }), ['text'])
    },
    paymentSKeys: (state) => {
        return _.sortBy(_.filter(state.files, (file) => {
            return file.text.match(/^[a-z0-9]*\.skey|.*\.payment\.skey/i) != null
        }), ['text'])
    },
    paymentVKeys: (state) => {
        return _.sortBy(_.filter(state.files, (file) => {
            return file.text.match(/^[a-z0-9]*\.vkey|.*\.payment\.vkey/i) != null
        }), ['text'])
    },
    genesisFiles: (state) => {
        return _.sortBy(_.filter(state.files, (file) => {
            return file.text.match(/.*genesis\.json/i) != null
        }), ['text'])
    },
    displayNodes: (state) => {
        return _.map(state.nodes, (node) => {
            let host = _.find(state.hosts, (host) => {
                return node.hostId === host.id
            })
            return {
                color: node.color,
                name: node.name,
                type: node.type,
                host: host ? host.hostname : "",
                isDefault: node.isDefault
            }
        })
    },
    paymentSelectOptions: (state) => (currency) => {
        return _.sortBy(
            _.map(state.walletItems, (walletItem) => {
                return {
                    value: walletItem.id,
                    text: walletItem.name + " - " +
                        currency(
                            walletItem.paymentAddrLovelace / 1000000,
                            "₳",
                            6
                        )
                }
            }), ['text'])
    },
    registrationFeesSelectOptions: (state) => (currency) => {
        return _.sortBy(
            _.map(_.filter(state.walletItems, (walletItem) => {
                // must have over 503 ada to pay fees for registering a pool.
                return walletItem.type !== "address" && walletItem.hasPaymentKeys && walletItem.paymentAddrLovelace > 503000000
            }), (walletItem) => {
                return {
                    value: walletItem.id,
                    text: walletItem.name + " - " +
                        currency(
                            walletItem.paymentAddrLovelace / 1000000,
                            "₳",
                            6
                        )
                }
            }), ['text'])
    },
    stakingSelectOptions: (state) => (currency) => {
        return _.sortBy(
            _.map(_.filter(state.walletItems, (walletItem) => {
                return walletItem.type === "stake"
            }), (walletItem) => {
                return {
                    value: walletItem.id,
                    text: walletItem.name + " - " +
                        currency(
                            walletItem.paymentAddrLovelace / 1000000,
                            "₳",
                            6
                        )
                }
            }), ['text'])
    },
    rewardsSelectOptions: (state) => (currency) => {
        return _.sortBy(
            _.map(state.walletItems, (walletItem) => {
                return {
                    value: walletItem.id,
                    text: walletItem.name + " - " +
                        currency(
                            walletItem.paymentAddrLovelace / 1000000,
                            "₳",
                            6
                        )
                }
            }), ['text'])
    },
}