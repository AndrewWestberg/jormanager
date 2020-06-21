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
        return _.sortBy(_.map(
            _.filter(state.files, (file) => {
                return file.name.match(/.*\.staking\.skey/i) != null
            }), (file) => {
                return {
                    value: file.id,
                    text: file.name
                }
            }
        ), ['text'])
    },
    stakingVKeys: (state) => {
        return _.sortBy(_.map(
            _.filter(state.files, (file) => {
                return file.name.match(/.*\.staking\.vkey/i) != null
            }), (file) => {
                return {
                    value: file.id,
                    text: file.name
                }
            }
        ), ['text'])
    },
    paymentSKeys: (state) => {
        return _.sortBy(_.map(
            _.filter(state.files, (file) => {
                // match enterprise and regular payment skeys
                return file.name.match(/^[a-z0-9]*\.skey|.*\.payment\.skey/i) != null
            }), (file) => {
                return {
                    value: file.id,
                    text: file.name
                }
            }
        ), ['text'])
    },
    paymentVKeys: (state) => {
        return _.sortBy(_.map(
            _.filter(state.files, (file) => {
                // match enterprise and regular payment vkeys
                return file.name.match(/^[a-z0-9]*\.vkey|.*\.payment\.vkey/i) != null
            }), (file) => {
                return {
                    value: file.id,
                    text: file.name
                }
            }
        ), ['text'])
    }
}