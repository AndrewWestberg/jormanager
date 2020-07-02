import _ from 'lodash'

export default {
    setConnected: (state, isConnected) => {
        state.connected = isConnected
    },
    setStompClient: (state, client) => {
        state.stompClient = client
    },
    setAppVersion: (state, appVersion) => {
        state.appVersion = appVersion
    },
    setBlocks: (state, blocks) => {
        state.blocks = _.orderBy(_.unionWith(state.blocks, blocks, _.isEqual), ["slot"], ["desc"])
    },
    addBlock: (state, block) => {
        state.blocks.unshift(block)
    },
    setHosts: (state, hosts) => {
        state.hosts = hosts
    },
    setNodes: (state, nodes) => {
        state.nodes = nodes
    },
    setFileOptions: (state, fileOptions) => {
        state.files = fileOptions
    },
    saveNodeStats: (state, nodeStats) => {
        let index = _.findIndex(state.blockHeightSeries, ["name", nodeStats.nodeName])
        if (index > -1) {
            state.blockHeightSeries[index].data.push([nodeStats.timestamp, nodeStats.blockHeight])
            state.blockHeightSeries[index].data = state.blockHeightSeries[index].data.slice(-60) // keep 5 minutes worth of data
            state.nodeColors[index] = nodeStats.color
        } else {
            state.blockHeightSeries.push({
                name: nodeStats.nodeName,
                data: [
                    [nodeStats.timestamp, nodeStats.blockHeight]
                ]
            })
            state.nodeColors[state.blockHeightSeries.length - 1] = nodeStats.color
        }
        // This is a terrible code smell, but I can't get the charts to update otherwise
        state.blockHeightSeries.__ob__.dep.notify()

        let index1 = _.findIndex(state.peersSeries, ["name", nodeStats.nodeName])
        if (index1 > -1) {
            state.peersSeries[index].data.push([nodeStats.timestamp, nodeStats.peers])
            state.peersSeries[index].data = state.peersSeries[index].data.slice(-160) // keep 5 minutes worth of data
        } else {
            state.peersSeries.push({
                name: nodeStats.nodeName,
                data: [
                    [nodeStats.timestamp, nodeStats.peers]
                ]
            })
        }
        // This is a terrible code smell, but I can't get the charts to update otherwise
        state.peersSeries.__ob__.dep.notify()
    },
    saveWallet: (state, walletItems) => {
        state.walletItems = walletItems
    },

    toastError: (state, toast) => {
        state.toastError = toast
    },
    toastWarn: (state, toast) => {
        state.toastWarn = toast
    },
    toastInfo: (state, toast) => {
        state.toastInfo = toast
    },
    toastSuccess: (state, toast) => {
        state.toastSuccess = toast
    }
}