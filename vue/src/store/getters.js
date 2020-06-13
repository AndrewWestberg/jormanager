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
    }
}