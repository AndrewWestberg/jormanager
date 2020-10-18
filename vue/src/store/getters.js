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
            "http://localhost:9797/jormanager-websocket" :
            // release mode
            "/jormanager-websocket"
    },
    backupDownloadUrl: (_, getters) => {
        return getters.isDebug ?
            // debug mode
            "http://localhost:9797/jormanager_backup.zip" :
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
    epochSelectOptions: (state) => {
        return _.orderBy(_.map(_.uniqBy(state.blocks, (block) => {
            return block.epoch
        }), (block) => {
            return {
                value: block.epoch,
                text: block.epoch
            }
        }), ['text'], ['desc'])
    },
    coreNodeSelectOptions: (state) => {
        return _.sortBy(
            _.map(_.filter(state.nodes, (node) => {
                return node.type === 'core'
            }), (node) => {
                return {
                    value: node.name,
                    text: node.name
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
                id: node.id,
                color: node.color,
                name: node.name,
                type: node.type,
                host: host ? host.hostname : "",
                isDefault: node.isDefault,
                kesExpireTimeSec: node.kesExpireTimeSec,
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
    reregistrationFeesSelectOptions: (state) => (currency) => {
        return _.sortBy(
            _.map(_.filter(state.walletItems, (walletItem) => {
                // must have over 1 ada to pay fees for re-registering a pool.
                return walletItem.type !== "address" && walletItem.hasPaymentKeys && walletItem.paymentAddrLovelace > 1000000
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
                return walletItem.type === "stake" || walletItem.type === "pledge"
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
    epochTimeRemaining: (state) => {
        // console.log("state.slot = " + state.slot);
        let time = 432000 - state.slot;
        // console.log("time: " + time);
        let days = Math.floor(time / 60 / 60 / 24);
        // console.log("days: " + days);
        let hours = Math.floor(time / 60 / 60) % 24;
        // console.log("hours: " + hours);
        let minutes = Math.floor(time / 60) % 60;
        // console.log("minutes: " + minutes);
        let seconds = Math.floor(time % 60);
        // console.log("seconds: " + seconds);

        return days + "d " + hours + "h " + minutes + "m " + seconds + "s ";
    },
    epochTimeRemainingSecs: (state) => {
        return 432000 - state.slot;
    }
}