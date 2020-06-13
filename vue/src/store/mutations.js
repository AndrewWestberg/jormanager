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
    setHosts: (state, hosts) => {
        state.hosts = hosts
        state.hosts = [{
                id: 0,
                type: 'local',
                cardanoCliPath: '/home/westbam/.local/bin/cardano-cli',
                cardanoNodePath: '/home/westbam/.local/bin/cardano-node',
                hostname: 'brainy',
                sshUser: 'westbam',
                sshPort: 15795,
                sshPemPath: '/home/westbam/.ssh/tux_private.pem',
                nodeHomeFolder: '/home/westbam/haskell'
            },
            {
                id: 1,
                type: 'remote',
                cardanoCliPath: '/home/westbam/.local/bin/cardano-cli',
                cardanoNodePath: '/home/westbam/.local/bin/cardano-node',
                hostname: 'papa',
                sshUser: 'westbam',
                sshPort: 15795,
                sshPemPath: '/home/westbam/.ssh/tux_private.pem',
                nodeHomeFolder: '/home/westbam/haskell'
            },
        ]
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