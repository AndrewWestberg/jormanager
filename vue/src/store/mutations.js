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
        state.blocks = _.unionWith(state.blocks, blocks, _.isEqual)
    },
    addBlock: (state, block) => {
        state.blocks.unshift(block)
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