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
            frame => {
                console.log(frame);
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
            }
        });
        commit('setConnected', true)
    },
    requestAppVersion: ({
        state,
        commit
    }) => {
        if (state.stompClient && state.stompClient.connected) {
            console.log("Request version");
            // const msg = { name: this.send_message };
            // console.log(JSON.stringify(msg));
            // this.stompClient.send("/jormanager/version", JSON.stringify(msg))
            state.stompClient.send("/jormanager/version");
        } else {
            commit('toastError', {
                title: "Error getting version!",
                message: "stompClient not connected!"
            })
        }
    }
}