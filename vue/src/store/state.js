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

    epoch: 0,
    slot: 0,
}