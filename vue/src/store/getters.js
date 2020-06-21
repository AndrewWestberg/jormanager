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
    }
}