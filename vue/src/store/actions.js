import SockJS from "sockjs-client";
import Stomp from "webstomp-client";

export default {
    connectToServer: ({
        dispatch,
        commit,
        getters
    }) => {
        let socket = new SockJS(getters.websocketUrl)

        let stompClient = Stomp.over(socket, {
            debug: getters.isDebug
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
        commit
    }) => {
        state.stompClient.subscribe("/topic/messages", tick => {
            console.log(tick);
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
            console.log("Request Hosts");
            state.stompClient.send("/jormanager/hosts");
        } else {
            commit('toastError', {
                title: "Error getting hosts!",
                message: "stompClient not connected!"
            })
        }
    },
    addHost: ({
        state,
        commit
    }, host) => {
        if (state.stompClient && state.stompClient.connected) {
            console.log("Add Host: " + JSON.stringify(host));
            state.stompClient.send("/jormanager/addhost", JSON.stringify(host));
        } else {
            commit('toastError', {
                title: "Communication Error!",
                message: "stompClient not connected!"
            })
        }
    }

}