export default {
    // Are we connected to the websocket?
    connected: false,
    // stompClient: null,

    // jormanager version string
    appVersion: "---",

    toastError: null,
    toastWarn: null,
    toastInfo: null,
    toastSuccess: null,

    hosts: [],
    nodes: [],
    blocks: [],
    files: [],
    walletItems: [],

    txFee: 0,

    // chart data
    nodeColors: [],
    peersSeries: [],
    blockHeightSeries: [],
    remainingKESSeries: [],

    epoch: 0,
    slot: 0,
}