import _ from 'lodash'

export default {
    setConnected: (state, isConnected) => {
        state.connected = isConnected
    },
    setStompClient: (state, client) => {
        state.stompClient = client
    },
    setAppVersion: (state, data) => {
        state.appVersion = data.version;
        state.mp = data.mp;
        state.minUTxOValue = data.minUTxOValue;
    },
    setBlocks: (state, blocks) => {
        state.blocks = _.orderBy(blocks, ["slot"], ["desc"]);
    },
    addBlock: (state, block) => {
        state.blocks = _.orderBy(_.unionWith([block], state.blocks, (first, second) => {
            return first.slot === second.slot
        }), ["slot"], ["desc"])
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
            let index = _.findIndex(state.blockHeightSeries, ["name", nodeStats.nodeName]);
            if (index > -1) {
                state.blockHeightSeries[index].data.push([nodeStats.timestamp, nodeStats.blockHeight]);
                state.blockHeightSeries[index].data = state.blockHeightSeries[index].data.slice(-60); // keep 5 minutes worth of data
                state.nodeColors[index] = nodeStats.color;
            } else {
                let heightData = {
                    name: nodeStats.nodeName,
                    data: [
                        [nodeStats.timestamp, nodeStats.blockHeight]
                    ]
                };
                state.blockHeightSeries.push(heightData);
                state.blockHeightSeries = _.sortBy(state.blockHeightSeries, ["name"]);
                state.nodeColors[_.indexOf(state.blockHeightSeries, heightData)] = nodeStats.color;
            }

            let index1 = _.findIndex(state.peersSeries, ["name", nodeStats.nodeName]);
            if (index1 > -1) {
                state.peersSeries[index1].data.push([nodeStats.timestamp, nodeStats.peers]);
                state.peersSeries[index1].data = state.peersSeries[index1].data.slice(-60); // keep 5 minutes worth of data
            } else {
                state.peersSeries.push({
                    name: nodeStats.nodeName,
                    data: [
                        [nodeStats.timestamp, nodeStats.peers]
                    ]
                });
                state.peersSeries = _.sortBy(state.peersSeries, ["name"]);
            }

            let daysRemaining = nodeStats.remainingKESPeriods * 1.5;
            if (daysRemaining > 0) {
                let low = 0
                let ok = 0
                let good = 0
                if (daysRemaining <= 5) {
                    low = daysRemaining;
                } else if (daysRemaining <= 20) {
                    low = 5;
                    ok = daysRemaining - 5;
                } else {
                    low = 5;
                    ok = 15;
                    good = daysRemaining - ok - low;
                }

                let index2 = state.remainingKESSeriesCategories.indexOf(nodeStats.nodeName);
                if (index2 < 0) {
                    state.remainingKESSeriesCategories.push(nodeStats.nodeName);
                    state.remainingKESSeriesCategories.sort();
                    state.remainingKESSeriesCategoryLabels.push(nodeStats.nodeName + ' (' + daysRemaining + 'd)');
                    state.remainingKESSeriesCategoryLabels.sort();
                    index2 = state.remainingKESSeriesCategories.indexOf(nodeStats.nodeName);
                }
                state.remainingKESSeriesCategoryLabels[index2] = nodeStats.nodeName + ' (' + daysRemaining + 'd)';
                state.remainingKESSeries[0].data[index2] = low;
                state.remainingKESSeries[1].data[index2] = ok;
                state.remainingKESSeries[2].data[index2] = good;
            }

            if (nodeStats.epoch !== state.epoch) {
                state.epoch = nodeStats.epoch;
                state.slot = 0;
            }
            if (nodeStats.slotInEpoch !== state.slot) {
                state.slot = nodeStats.slotInEpoch;
            }
            if (nodeStats.epochLength !== state.epochLength) {
                state.epochLength = nodeStats.epochLength;
            }

            let index3 = _.findIndex(state.incomingPeersSeries, ["name", nodeStats.nodeName]);
            if (index3 > -1) {
                state.incomingPeersSeries[index3].data.push([nodeStats.timestamp, nodeStats.incomingPeers]);
                state.incomingPeersSeries[index3].data = state.incomingPeersSeries[index3].data.slice(-60); // keep 5 minutes worth of data
            } else {
                state.incomingPeersSeries.push({
                    name: nodeStats.nodeName,
                    data: [
                        [nodeStats.timestamp, nodeStats.incomingPeers]
                    ]
                });
                state.incomingPeersSeries = _.sortBy(state.incomingPeersSeries, ["name"]);
            }

            let index4 = _.findIndex(state.txsProcessedSeries, ["name", nodeStats.nodeName]);
            if (index4 > -1) {
                if (nodeStats.txsProcessed > 0) {
                    state.txsProcessedSeries[index4].data.push([nodeStats.timestamp, nodeStats.txsProcessed]);
                    state.txsProcessedSeries[index4].data = state.txsProcessedSeries[index4].data.slice(-60); // keep 5 minutes worth of data
                }
            } else {
                if (nodeStats.txsProcessed > 0) {
                    state.txsProcessedSeries.push({
                        name: nodeStats.nodeName,
                        data: [
                            [nodeStats.timestamp, nodeStats.txsProcessed]
                        ]
                    });
                    state.txsProcessedSeries = _.sortBy(state.txsProcessedSeries, ["name"]);
                }
            }
        }

        // This is a terrible code smell, but I can't get the charts to update otherwise
        if (state.nodeColors.length > 0) {
            state.nodeColors.__ob__.dep.notify();
        }
        if (state.blockHeightSeries.length > 0) {
            state.blockHeightSeries.__ob__.dep.notify();
        }
        if (state.peersSeries.length > 0) {
            state.peersSeries.__ob__.dep.notify();
        }
        if (state.remainingKESSeries.length > 0) {
            state.remainingKESSeries.__ob__.dep.notify();
        }
        if (state.incomingPeersSeries.length > 0) {
            state.incomingPeersSeries.__ob__.dep.notify();
        }
        if (state.txsProcessedSeries.length > 0) {
            state.txsProcessedSeries.__ob__.dep.notify();
        }
    },
    saveWallet: (state, walletItems) => {
        state.walletItems = walletItems
    },
    saveTxFee: (state, data) => {
        state.txFee = data.txFee;
        state.tokenFees = data.tokenFees;
        state.tokenKeepFee = data.tokenKeepFee;
        state.tokenLocked = data.tokenLocked;
        state.responseFeesUUID = data.uuid;
    },
    saveMetadata: (state, data) => {
        state.editorMetadata = data
    },
    setRequestFeesUUID: (state, uuid) => {
        state.requestFeesUUID = uuid;
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