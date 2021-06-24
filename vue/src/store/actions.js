import SockJS from "sockjs-client";
import Stomp from "webstomp-client";
import _ from 'lodash';

export default {
    connectToServer: ({
        dispatch,
        commit,
        getters
    }) => {
        let socket = new SockJS(getters.websocketUrl)

        let stompClient = Stomp.over(socket, {
            debug: false /*getters.isDebug*/
        })
        commit('setStompClient', stompClient)

        stompClient.connect({},
            () => {
                dispatch('subscribeToMessages')
            },
            error => {
                commit('setConnected', false)
                console.log(error);
            }
        );
    },
    subscribeToMessages: ({
        state,
        commit,
        dispatch,
        getters
    }) => {
        state.stompClient.subscribe("/topic/messages", tick => {
            let message = JSON.parse(tick.body)
            switch (message.type) {
                case "version":
                    commit('setAppVersion', message.data)
                    break
                case "blocks":
                    commit('setBlocks', message.data)
                    break
                case "block":
                    commit('addBlock', message.data)
                    break
                case "hosts":
                    commit('setHosts', message.data)
                    break
                case "nodes":
                    commit('setNodes', message.data)
                    break
                case "addhost":
                    if (message.data) {
                        commit('toastSuccess', {
                            title: "Host Saved",
                            message: message.data
                        })

                    } else {
                        commit('toastError', {
                            title: "Host Save Error",
                            message: message.exception.message
                        })
                        console.log(message.exception)
                    }
                    break
                case "file_options":
                    commit('setFileOptions', message.data)
                    break
                case "createnode":
                    if (message.data) {
                        commit('toastSuccess', {
                            title: "Node Created",
                            message: message.data
                        })

                    } else {
                        commit('toastError', {
                            title: "Node Save Error",
                            message: message.exception.message
                        })
                        console.log(message.exception)
                    }
                    break
                case "nodestats":
                    commit('saveNodeStats', message.data)
                    break
                case "wallet":
                    commit('saveWallet', message.data)
                    break
                case "createwalletentry":
                    if (message.data) {
                        commit('toastSuccess', {
                            title: "Success",
                            message: message.data
                        })
                        dispatch('fetchWalletItems')
                    } else {
                        commit('toastError', {
                            title: "WalletEntry Save Error",
                            message: message.exception.message
                        })
                        console.log(message.exception)
                    }
                    break
                case "deletewalletentry":
                    if (message.data) {
                        commit('toastSuccess', {
                            title: "Success",
                            message: message.data
                        })
                        dispatch('fetchWalletItems')
                    } else {
                        commit('toastError', {
                            title: "Delete WalletEntry Error",
                            message: message.exception.message
                        })
                        console.log(message.exception)
                    }
                    break
                case "calculatefee":
                    if (message.data) {
                        commit('saveTxFee', message.data)
                    } else {
                        commit('toastError', {
                            title: "CalculateFee Error",
                            message: message.exception.message
                        })
                        console.log(message.exception)
                    }
                    break
                case "submittransaction":
                    if (message.data) {
                        commit('toastSuccess', {
                            title: "Ada Sent",
                            message: message.data
                        })
                    } else {
                        commit('toastError', {
                            title: "Submit Transaction Error",
                            message: message.exception.message
                        })
                        console.log(message.exception)
                    }
                    break
                case "restartnode":
                    if (message.data) {
                        commit('toastSuccess', {
                            title: "Node Restart...",
                            message: message.data
                        })
                    } else {
                        commit('toastError', {
                            title: "Restart Node Error",
                            message: message.exception.message
                        })
                        console.log(message.exception)
                    }
                    break
                case "rotatekes":
                    if (message.data) {
                        commit('toastSuccess', {
                            title: "Rotate KES...",
                            message: message.data
                        })
                    } else {
                        commit('toastError', {
                            title: "Rotate KES Error",
                            message: message.exception.message
                        })
                        console.log(message.exception)
                    }
                    break
                case "backup":
                    if (message.data) {
                        window.open(getters.backupDownloadUrl + "?token=" + message.data, "_jm_download");
                    } else {
                        commit('toastError', {
                            title: "Download Backup Error",
                            message: message.exception.message
                        })
                        console.log(message.exception)
                    }
                    break
                case "updatenodecolor":
                    if (message.data) {
                        commit('toastSuccess', {
                            title: "Update Color...",
                            message: message.data
                        })
                    } else {
                        commit('toastError', {
                            title: "Update Color Error",
                            message: message.exception.message
                        })
                        console.log(message.exception)
                    }
                    break
                case "updatepoolconfig":
                    if (message.data) {
                        commit('toastSuccess', {
                            title: "Update Pool Config...",
                            message: message.data
                        })
                    } else {
                        commit('toastError', {
                            title: "Update Pool Config Error",
                            message: message.exception.message
                        })
                        console.log(message.exception)
                    }
                    break
                case "leaderlogs":
                    if (message.data) {
                        commit('toastSuccess', {
                            title: "Leader Logs",
                            message: message.data
                        })
                    } else {
                        commit('toastError', {
                            title: "Update Pool Config Error",
                            message: message.exception.message
                        })
                        console.log(message.exception)
                    }
                    break
                case "getmetadata":
                    if (message.data) {
                        commit('saveMetadata', message.data)
                    } else {
                        commit('toastError', {
                            title: "Metadata Fetch Error",
                            message: message.exception.message
                        })
                        console.log(message.exception)
                    }
                    break
                case "updatemetadata":
                    if (message.data) {
                        commit('toastSuccess', {
                            title: "Update Metadata...",
                            message: message.data
                        })
                    } else {
                        commit('toastError', {
                            title: "Update Metadata Error",
                            message: message.exception.message
                        })
                        console.log(message.exception)
                    }
                    break
                case "updatestakingaddress":
                    if (message.data) {
                        commit('toastSuccess', {
                            title: "Update Staking Address...",
                            message: message.data
                        })
                    } else {
                        commit('toastError', {
                            title: "Update Staking Address Error",
                            message: message.exception.message
                        })
                        console.log(message.exception)
                    }
                    break
                case "editrelays":
                    if (message.data) {
                        commit('toastSuccess', {
                            title: "Edit Relays...",
                            message: message.data
                        })
                    } else {
                        commit('toastError', {
                            title: "Edit Relays Error",
                            message: message.exception.message
                        })
                        console.log(message.exception)
                    }
                    break
                case "retirepool":
                    if (message.data) {
                        commit('toastSuccess', {
                            title: "Retire Pool...",
                            message: message.data
                        })
                        commit('toastWarn', {
                            title: "Retire Pool",
                            message: "Pool has been left running intentionally. You should stop it and disable systemd scripts manually after retirement."
                        })
                    } else {
                        commit('toastError', {
                            title: "Retire Pool Error",
                            message: message.exception.message
                        })
                        console.log(message.exception)
                    }
                    break
            }
        });
        commit('setConnected', true)
    },
    requestAppVersion: ({
        state,
        commit
    }) => {
        if (state.stompClient && state.stompClient.connected) {
            state.stompClient.send("/jormanager/version");
        } else {
            commit('toastError', {
                title: "Communication Error!",
                message: "stompClient not connected!"
            })
        }
    },
    requestHosts: ({
        state,
        commit
    }) => {
        if (state.stompClient && state.stompClient.connected) {
            // console.log("Request Hosts");
            state.stompClient.send("/jormanager/hosts");
        } else {
            commit('toastError', {
                title: "Error getting hosts!",
                message: "stompClient not connected!"
            })
        }
    },
    requestNodes: ({
        state,
        commit
    }) => {
        if (state.stompClient && state.stompClient.connected) {
            // console.log("Request Nodes");
            state.stompClient.send("/jormanager/nodes");
        } else {
            commit('toastError', {
                title: "Error getting nodes!",
                message: "stompClient not connected!"
            })
        }
    },
    addHost: ({
        state,
        commit
    }, host) => {
        if (state.stompClient && state.stompClient.connected) {
            // console.log("Add Host: " + JSON.stringify(host));
            state.stompClient.send("/jormanager/addhost", JSON.stringify(host));
        } else {
            commit('toastError', {
                title: "Communication Error!",
                message: "stompClient not connected!"
            })
        }
    },
    requestFileOptions: ({
        state,
        commit
    }) => {
        if (state.stompClient && state.stompClient.connected) {
            // console.log("Request fileOptions");
            state.stompClient.send("/jormanager/file_options");
        } else {
            commit('toastError', {
                title: "Error getting file_options!",
                message: "stompClient not connected!"
            })
        }
    },
    createNode: ({
        state,
        commit
    }, formNode) => {
        if (state.stompClient && state.stompClient.connected) {
            // console.log("Create Node: " + JSON.stringify(formNode));
            state.stompClient.send("/jormanager/createnode", JSON.stringify(formNode));
        } else {
            commit('toastError', {
                title: "Communication Error!",
                message: "stompClient not connected!"
            })
        }
    },
    createWalletEntry: ({
        state,
        commit
    }, formWallet) => {
        if (state.stompClient && state.stompClient.connected) {
            // console.log("Create WalletEntry: " + JSON.stringify(formWallet));
            state.stompClient.send("/jormanager/createwalletentry", JSON.stringify(formWallet));
        } else {
            commit('toastError', {
                title: "Communication Error!",
                message: "stompClient not connected!"
            })
        }
    },
    requestBlocks: ({
        state,
        commit
    }) => {
        if (state.stompClient && state.stompClient.connected) {
            // console.log("Request blocks");
            state.stompClient.send("/jormanager/blocks");
        } else {
            commit('toastError', {
                title: "Error getting blocks!",
                message: "stompClient not connected!"
            })
        }
    },
    requestLeaderLogs: ({
        state,
        commit
    }, formLeaderLogs) => {
        if (state.stompClient && state.stompClient.connected) {
            // console.log("Leader Logs: " + JSON.stringify(formLeaderLogs));
            state.stompClient.send("/jormanager/leaderlogs", JSON.stringify(formLeaderLogs));
        } else {
            commit('toastError', {
                title: "Communication Error!",
                message: "stompClient not connected!"
            })
        }
    },
    requestMetadata: ({ state, commit }, nodeId) => {
        if (state.stompClient && state.stompClient.connected) {
            // console.log("fetch metadata: " + JSON.stringify(nodeId));
            state.stompClient.send("/jormanager/getmetadata", nodeId);
        } else {
            commit('toastError', {
                title: "Communication Error!",
                message: "stompClient not connected!"
            })
        }
    },
    deleteWalletItem: ({
        state,
        commit
    }, deleteRequest) => {
        commit('saveWallet', [])
        if (state.stompClient && state.stompClient.connected) {
            // console.log("Delete WalletItem: " + JSON.stringify(deleteRequest));
            state.stompClient.send("/jormanager/deletewalletentry", JSON.stringify(deleteRequest));
        } else {
            commit('toastError', {
                title: "Communication Error!",
                message: "stompClient not connected!"
            })
        }
    },
    fetchWalletItems: ({
        state,
        commit
    }) => {
        if (state.stompClient && state.stompClient.connected) {
            // console.log("Request wallet");
            state.stompClient.send("/jormanager/wallet");
        } else {
            commit('toastError', {
                title: "Error getting wallet!",
                message: "stompClient not connected!"
            })
        }
    },
    calculateSendAdaFees: ({
        state,
        commit
    }, request) => {
        if (state.stompClient && state.stompClient.connected) {
            commit('setRequestFeesUUID', request.uuid);
            // console.log("Calculate txfee: " + JSON.stringify(request));
            state.stompClient.send("/jormanager/calculatefee", JSON.stringify(request));
        } else {
            commit('toastError', {
                title: "Communication Error!",
                message: "stompClient not connected!"
            })
        }
    },
    submitTransaction: ({
        state,
        commit
    }, formSendAda) => {
        if (state.stompClient && state.stompClient.connected) {
            // console.log("Submit transaction: " + JSON.stringify(formSendAda));
            state.stompClient.send("/jormanager/submittransaction", JSON.stringify(formSendAda));
        } else {
            commit('toastError', {
                title: "Communication Error!",
                message: "stompClient not connected!"
            })
        }
    },
    restartNodeByName: ({
        state,
        commit
    }, nodeName) => {
        if (state.stompClient && state.stompClient.connected) {
            let node = _.find(state.nodes, ["name", nodeName]);
            if (node) {
                // console.log("Restart Node: " + JSON.stringify(node));
                state.stompClient.send("/jormanager/restartnode", JSON.stringify(node.id));
            }
        } else {
            commit('toastError', {
                title: "Communication Error!",
                message: "stompClient not connected!"
            })
        }
    },
    rotateKesByName: ({
        state,
        commit
    }, rotateRequest) => {
        if (state.stompClient && state.stompClient.connected) {
            let node = _.find(state.nodes, ["name", rotateRequest.name]);
            if (node) {
                // console.log("Rotate KES: " + JSON.stringify(node));
                state.stompClient.send("/jormanager/rotatekes", JSON.stringify({
                    id: node.id,
                    spendingPassword: rotateRequest.spendingPassword
                }));
            }
        } else {
            commit('toastError', {
                title: "Communication Error!",
                message: "stompClient not connected!"
            })
        }
    },
    downloadBackup: ({
        state,
        commit
    }, spendingPassword) => {
        if (state.stompClient && state.stompClient.connected) {
            // console.log("Download Backup: " + JSON.stringify(spendingPassword));
            state.stompClient.send("/jormanager/backup", spendingPassword);
        } else {
            commit('toastError', {
                title: "Communication Error!",
                message: "stompClient not connected!"
            })
        }
    },
    updateNodeColor: ({
        state,
        commit
    }, updateColorForm) => {
        if (state.stompClient && state.stompClient.connected) {
            // console.log("Update Color: " + JSON.stringify(updateColorForm));
            state.stompClient.send("/jormanager/updatenodecolor", JSON.stringify(updateColorForm));
        } else {
            commit('toastError', {
                title: "Communication Error!",
                message: "stompClient not connected!"
            })
        }
    },
    updatePoolConfig: ({
        state,
        commit
    }, editPoolForm) => {
        if (state.stompClient && state.stompClient.connected) {
            // console.log("Update Pool Config: " + JSON.stringify(editPoolForm));
            state.stompClient.send("/jormanager/updatepoolconfig", JSON.stringify(editPoolForm));
        } else {
            commit('toastError', {
                title: "Communication Error!",
                message: "stompClient not connected!"
            })
        }
    },
    updateMetadata: ({
        state, commit
    }, editMetadataForm) => {
        if (state.stompClient && state.stompClient.connected) {
            // console.log("Update Pool Metadata: " + JSON.stringify(editMetadataForm));
            state.stompClient.send("/jormanager/updatemetadata", JSON.stringify(editMetadataForm));
        } else {
            commit('toastError', {
                title: "Communication Error!",
                message: "stompClient not connected!"
            })
        }
    },
    updateStakingAddress: ({
        state, commit
    }, stakingAddressForm) => {
        if (state.stompClient && state.stompClient.connected) {
            // console.log("Update Staking Address: " + JSON.stringify(stakingAddressForm));
            state.stompClient.send("/jormanager/updatestakingaddress", JSON.stringify(stakingAddressForm));
        } else {
            commit('toastError', {
                title: "Communication Error!",
                message: "stompClient not connected!"
            })
        }
    },
    sendEditRelays: ({
        state, commit
    }, editRelaysForm) => {
        if (state.stompClient && state.stompClient.connected) {
            // console.log("Send Edit Relays: " + JSON.stringify(editRelaysForm));
            state.stompClient.send("/jormanager/editrelays", JSON.stringify(editRelaysForm));
        } else {
            commit('toastError', {
                title: "Communication Error!",
                message: "stompClient not connected!"
            })
        }
    },
    sendRetirePool: ({
        state, commit
    }, retirePoolForm) => {
        if (state.stompClient && state.stompClient.connected) {
            // console.log("Send Retire Pool: " + JSON.stringify(retirePoolForm));
            state.stompClient.send("/jormanager/retirepool", JSON.stringify(retirePoolForm));
        } else {
            commit('toastError', {
                title: "Communication Error!",
                message: "stompClient not connected!"
            })
        }
    },
}