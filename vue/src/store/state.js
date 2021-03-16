export default {
    // Are we connected to the websocket?
    connected: false,
    // stompClient: null,

    // jormanager version string
    appVersion: "---",
    mp: false,

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
    incomingPeersSeries: [],
    remainingKESSeriesCategories: [],
    remainingKESSeriesCategoryLabels: [],
    remainingKESSeries: [{
        name: 'Low',
        data: []
    }, {
        name: 'Ok',
        data: []
    }, {
        name: 'All Good',
        data: []
    }],
    txsProcessedSeries: [],

    epoch: 0,
    slot: 0,
    epochLength: 432000,

    editorMetadata: null,
}