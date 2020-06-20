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
    nodes: [{
            id: 0,
            hostId: 0,
            type: "relay",
            name: "node",
            listen: "127.0.0.1",
            port: 6000,
            genesisFileId: 0,
            configFileId: 1,
            coldNodeSkeyFileId: null,
            coldNodeVkeyFileId: null,
            hotNodeKesSkeyFileId: null,
            hotNodeKesVkeyFileId: null,
            opcertFileId: null,
            opcertExpiration: null,
            isDefault: true
        },
        {
            id: 1,
            hostId: 1,
            type: "relay",
            name: "relay0",
            listen: "0.0.0.0",
            port: 5001,
            genesisFileId: 0,
            configFileId: 2,
            coldNodeSkeyFileId: 3,
            coldNodeVkeyFileId: 4,
            hotNodeKesSkeyFileId: 5,
            hotNodeKesVkeyFileId: 6,
            opcertFileId: 7,
            opcertExpiration: 8,
            isDefault: false
        },
        {
            id: 2,
            hostId: 2,
            type: "relay",
            name: "relay1",
            listen: "0.0.0.0",
            port: 5002,
            genesisFileId: 0,
            configFileId: 3,
            coldNodeSkeyFileId: 9,
            coldNodeVkeyFileId: 10,
            hotNodeKesSkeyFileId: 11,
            hotNodeKesVkeyFileId: 12,
            opcertFileId: 13,
            opcertExpiration: 14,
            isDefault: false
        },
        {
            id: 3,
            hostId: 3,
            type: "core",
            name: "bcsh",
            listen: "127.0.0.1",
            port: 5000,
            genesisFileId: 0,
            configFileId: 4,
            coldNodeSkeyFileId: 15,
            coldNodeVkeyFileId: 16,
            hotNodeKesSkeyFileId: 17,
            hotNodeKesVkeyFileId: 18,
            opcertFileId: 19,
            opcertExpiration: 20,
            isDefault: false
        }
    ],
    blocks: [],
    files: [{
            id: 0,
            name: "funds.skey",
            content: `type: SigningKeyShelley
            title: Free form text
            cbor-hex:
             18ad58206303b131b6610b727bb65ac6003ef124d7e7401b306062cd4d869ba4ea6cfb89`
        },
        {
            id: 1,
            name: "funds.vkey",
            content: `type: PaymentVerificationKeyShelley
            title: Free form text
            cbor-hex:
             18af5820db168baa6a540b8e4e82b491cb047030501efd52441c4c8a4ba07b951c955f33`
        },
        {
            id: 2,
            name: "bcsh.payment.skey",
            content: `type: SigningKeyShelley
            title: Free form text
            cbor-hex:
             18ad58200dd37951f103a3517c06b1ec2007034fdca822918334f76c2188cea24f24c5ed`
        },
        {
            id: 3,
            name: "bcsh.payment.vkey",
            content: `type: PaymentVerificationKeyShelley
            title: Free form text
            cbor-hex:
             18af582008c9ffef12f77c4db5af5514f98c260ee962db10377e8b032d73262bc80d4910`
        },
        {
            id: 4,
            name: "bcsh.staking.addr",
            content: `5821e0e6e03b9c199b3bbeafa73cdc4b1a5c6fa95bda9bcf86649af042f0b6a1abb10c`
        },
        {
            id: 5,
            name: "bcsh.staking.skey",
            content: `type: SigningKeyShelley
            title: Free form text
            cbor-hex:
             18ad5820282b1b22890ec8d54569bc77958e858aa620741a74d744c9728983353d0d267c`
        },
        {
            id: 6,
            name: "bcsh.staking.vkey",
            content: `type: StakingVerificationKeyShelley
            title: Free form text
            cbor-hex:
             18b95820ec772af7721271cccaeaa8b71de6242ca22143172d99527b40446380f5e1b05d`
        },
        {
            id: 7,
            name: "bcsh.payment.addr",
            content: `0003997af2c86db7dac20fb6f3ce398da57d298aaf3967804b2b9955423f4dbc38e6e03b9c199b3bbeafa73cdc4b1a5c6fa95bda9bcf86649af042f0b6a1abb10c`
        }
    ],

}