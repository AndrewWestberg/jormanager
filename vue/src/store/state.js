export default {
    // Are we connected to the websocket?
    connected: false,
    // stompClient: null,

    // jormanager version string
    appVersion: "---",
    mp: false,
    minUTxOValue: 1000000,

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
    tokenFees: [],
    tokenKeepFee: 0,
    tokenLocked: 0,

    requestFeesUUID: 'ec8301e5-98bb-4be2-82d4-0f97fa82bb32',
    responseFeesUUID: '200bbde5-d0ba-4810-ae31-aa5c66ff0bd1',

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