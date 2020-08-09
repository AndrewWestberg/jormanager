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
    saveNodeStats: (state, nodeStatEvents) => {
        for (var i = 0; i < nodeStatEvents.length; i++) {
            let nodeStats = nodeStatEvents[i];
            let index = _.findIndex(state.blockHeightSeries, ["name", nodeStats.nodeName])
            if (index > -1) {
                state.blockHeightSeries[index].data.push([nodeStats.timestamp, nodeStats.blockHeight])
                state.blockHeightSeries[index].data = state.blockHeightSeries[index].data.slice(-60) // keep 5 minutes worth of data
                state.nodeColors[index] = nodeStats.color
            } else {
                let heightData = {
                    name: nodeStats.nodeName,
                    data: [
                        [nodeStats.timestamp, nodeStats.blockHeight]
                    ]
                };
                state.blockHeightSeries.push(heightData)
                state.blockHeightSeries = _.sortBy(state.blockHeightSeries, ["name"])
                state.nodeColors[_.indexOf(state.blockHeightSeries, heightData)] = nodeStats.color
            }

            let index1 = _.findIndex(state.peersSeries, ["name", nodeStats.nodeName])
            if (index1 > -1) {
                state.peersSeries[index].data.push([nodeStats.timestamp, nodeStats.peers])
                state.peersSeries[index].data = state.peersSeries[index].data.slice(-60) // keep 5 minutes worth of data
            } else {
                state.peersSeries.push({
                    name: nodeStats.nodeName,
                    data: [
                        [nodeStats.timestamp, nodeStats.peers]
                    ]
                })
                state.peersSeries = _.sortBy(state.peersSeries, ["name"])
            }
        }

        // This is a terrible code smell, but I can't get the charts to update otherwise
        if (state.nodeColors.length > 0) {
            state.nodeColors.__ob__.dep.notify()
        }
        if (state.blockHeightSeries.length > 0) {
            state.blockHeightSeries.__ob__.dep.notify()
        }
        if (state.peersSeries.length > 0) {
            state.peersSeries.__ob__.dep.notify()
        }
    },
    saveWallet: (state, walletItems) => {
        state.walletItems = walletItems
    },
    saveTxFee: (state, fee) => {
        state.txFee = fee
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