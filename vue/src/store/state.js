export default {
    // Are we connected to the websocket?
    connected: false,
    stompClient: null,

    // jormanager version string
    appVersion: "---",

    blocks: [],

    toastError: null,
    toastWarn: null,
    toastInfo: null,
    toastSuccess: null
}