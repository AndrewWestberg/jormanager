package com.swiftmako.jormanager.nodeclient.protocols.chainsync

import com.google.iot.cbor.CborArray
import com.google.iot.cbor.CborReader
import com.muquit.libsodiumjna.SodiumLibrary
import com.swiftmako.jormanager.ktx.elementToByteArray
import com.swiftmako.jormanager.ktx.elementToHexString
import com.swiftmako.jormanager.ktx.elementToLong
import com.swiftmako.jormanager.ktx.toHexString
import org.slf4j.LoggerFactory

object MsgRollForwardAdapter {
    private val log = LoggerFactory.getLogger("MsgRollForwardAdapter")

//    private var tipDone = false

    fun fromCborArray(cborArray: CborArray): MsgRollForward {
        assert(MsgRollForward.MESSAGE_ID == cborArray.elementToLong(0))

        // parse wrappedHeader
        val headerCborArray = cborArray.elementAt(1) as CborArray
//        val someNumber = headerCborArray.elementToLong(0)
        val wrappedBlockHeaderBytes = headerCborArray.elementToByteArray(1)

        // calculate the block hash
        val hash = SodiumLibrary.cryptoBlake2bHash(wrappedBlockHeaderBytes,null).toHexString()

        // unwrap the inner block header
        val blockHeaderCborArray = CborReader.createFromByteArray(wrappedBlockHeaderBytes).readDataItem() as CborArray
        val blockHeaderCborArrayInner = blockHeaderCborArray.elementAt(0) as CborArray
        val blockNumber = blockHeaderCborArrayInner.elementToLong(0)
        val slotNumber = blockHeaderCborArrayInner.elementToLong(1)
        val prevHash = blockHeaderCborArrayInner.elementToHexString(2)
        val nodeVkey = blockHeaderCborArrayInner.elementToHexString(3) // issuer_vkey
//        val nodeVrfVkey = blockHeaderCborArrayInner.elementToHexString(4)
        val nonceCborArray = blockHeaderCborArrayInner.elementAt(5) as CborArray
        val etaVrfFirstPart = nonceCborArray.elementToHexString(0)
//        val etaVrfSecondPart = nonceCborArray.elementToHexString(1)
        val leaderCborArray = blockHeaderCborArrayInner.elementAt(6) as CborArray
        val leaderVrfFirstPart = leaderCborArray.elementToHexString(0)
//        val leaderVrfSecondPart = leaderCborArray.elementToHexString(1)
//        val blockSize = blockHeaderCborArrayInner.elementToLong(7)
//        val blockBodyHash = blockHeaderCborArrayInner.elementToHexString(8)
//        val poolOpcert = blockHeaderCborArrayInner.elementToHexString(9)
//        val unknown1 = blockHeaderCborArrayInner.elementToLong(10)
//        val kesPeriod = blockHeaderCborArrayInner.elementToLong(11)
//        val unknown2 = blockHeaderCborArrayInner.elementToHexString(12) // someHashMaybeKesRelated
//        val protocolMajorVersion = blockHeaderCborArrayInner.elementToLong(13)
//        val protocolMinorVersion = blockHeaderCborArrayInner.elementToLong(14)

        // parse tip
        val tipCborArray = cborArray.elementAt(2) as CborArray
        val tipInfoCborArray = tipCborArray.elementAt(0) as CborArray
        val tipSlot = tipInfoCborArray.elementToLong(0)
        val tipHash = tipInfoCborArray.elementToHexString(1)
        val tipBlockHeight = tipCborArray.elementToLong(1)

        return MsgRollForward(
            blockNumber,
            slotNumber,
            hash,
            prevHash,
            nodeVkey,
            etaVrfFirstPart,
            leaderVrfFirstPart,
            ChainTip(tipSlot, tipBlockHeight, tipHash)
        )
    }
}
// msgRollForward         = [2, wrappedHeader, tip]

//// my first bcsh block
//[
//  2, // roll forward
//  [
//    1, // some number and then our embedded cbor innards
//    "828f1a0045b2241a005c29a1582037f1d05be8f881f85f7607dcd28a38da5c037406471dc3c691b203412474fe3f5820cad3c900ca6baee9e65bf61073d900bfbca458eeca6d0b9f9931f5b1017a8cd65820576d49e98adfab65623dc16f9fff2edd210e8dd1d4588bfaf8af250beda9d3c7825840e59d061028b81461e7d4a7bd492224ac0d8f0f01bc059fda783c5953b8984cf7602b1fa878d815ed2eacd2e9917dfbef2a6d6b4e08459c10f249e10085b22e1a58500566bafa53c95110999f016a76321e7c0ebcddf31050f72b2c5ab9e9eb16cd2bdb24561d7c8f7654655ded3b784110a841347b56b2c4d1323e16611670f2b3e5416cb62b8a000abe1e17f06d3d65160d8258400001b72529b58b17530ca9d39d848594443a446b580bc35ca5ffa69100f1ab640512d24e4cf9ed65e8595159668540520730ddb4980e110a50642d91870bc9865850195702ba4d70426e3b1a7df756c265a6894ed6defaf216e9b358e003bd7b7cb8afc1aa6148aa51f20d8d49df56f6dfbb743ef43c4e1219d1fa69c1948f0e9bb13d45801c0beff970579dc96eb625f60719043d5820c1b9597e9866ea9f22de447246678f446e99afbbb45c6ee554179d7687da52955820563a91ebbd457c02ad1a8d001302bacb92a164efc7ba3c66e9e363b403f4aa8004182758408090b71aab44e77ee897e1b7bd0c5f0f29fb9ecbb6e1677345cc6a284a76379389c97b9216cb2f28f2a7bebf6d612d25d883d4a97323d55167a9a7cda8a3760302005901c0b7f8858559bbb912a275ed3e9f5cc98f05eab377db9c626cc51ccde650702cd2e21648e549a68b8ad133b09608904d298be3a19b0910fee1e10c925ac0d99f0eec87fa21af4a49e6982672c68aed11fdb95d6320c9a64d82752e7c16d24f8a4a48ffe19f81b93714610ccab3b012b2530331898e8a6e537061e6404bb788582a73e41dbfe690b9913e9142ea063f5237f78059f3dbf5a34deccdb22f3323d90ecb7899fffb1683907846d85bf92947d79e5a4bade98dd966f7b3b7c2640a46fad49f12e7df4608f674043bb72da07230de5b01e7ea8845c327688a8e4bbd3a422c99e80c57ed618fc0251352c583692990ff27cf8edd005c70269c78c39f5ccdbed39cf2c7c88f77109efb25ccd698276364038a784e0ca3ba9c86cd3d6e10da7f4104f7f834f19655e574b5ebcba5bf73f3523bb3caa15bb85184db14227f888bf3c484ea8cdc933e25556e930758337e5019b087ba075ae8151990b5222671091db5eaa758c93c7fa5251c8fa87fa95399de93bf1f856e17565cd6aa4a465232bb5228888e3d338045578b7e0745dcd56517c5a07b4ef906cf3032f4bb984942e9120472d2e7712dee2d31d07fecc5127f6b216fc6d00951a4dea9045dd0e9"
//  ],
//  [ // current tip from the remote node
//    [
//      9585315, // tip slot
//      "e99d092ea18a3ac7cff29e63cdc8727472250766e3eb4b9248fa074caac62977" // tip block hash
//    ],
//    4741522 // tip block number
//  ]
//]
//
//// the innards
//[
//  [
//    4567588, // block number
//    6039969, // slot number
//    "37f1d05be8f881f85f7607dcd28a38da5c037406471dc3c691b203412474fe3f", // parent block hash
//    "cad3c900ca6baee9e65bf61073d900bfbca458eeca6d0b9f9931f5b1017a8cd6", // node.vkey (pool that made the block)
//    "576d49e98adfab65623dc16f9fff2edd210e8dd1d4588bfaf8af250beda9d3c7", // vrf.vkey
//    [ // nonce_vrf
//      "e59d061028b81461e7d4a7bd492224ac0d8f0f01bc059fda783c5953b8984cf7602b1fa878d815ed2eacd2e9917dfbef2a6d6b4e08459c10f249e10085b22e1a",
//      "0566bafa53c95110999f016a76321e7c0ebcddf31050f72b2c5ab9e9eb16cd2bdb24561d7c8f7654655ded3b784110a841347b56b2c4d1323e16611670f2b3e5416cb62b8a000abe1e17f06d3d65160d"
//    ],
//    [ // leader_vrf
//      "0001b72529b58b17530ca9d39d848594443a446b580bc35ca5ffa69100f1ab640512d24e4cf9ed65e8595159668540520730ddb4980e110a50642d91870bc986",
//      "195702ba4d70426e3b1a7df756c265a6894ed6defaf216e9b358e003bd7b7cb8afc1aa6148aa51f20d8d49df56f6dfbb743ef43c4e1219d1fa69c1948f0e9bb13d45801c0beff970579dc96eb625f607"
//    ],
//    1085, // size in bytes
//    "c1b9597e9866ea9f22de447246678f446e99afbbb45c6ee554179d7687da5295", // block body hash
//    "563a91ebbd457c02ad1a8d001302bacb92a164efc7ba3c66e9e363b403f4aa80", // opcert
//    4,  // ??
//    39, // kes period??
//    "8090b71aab44e77ee897e1b7bd0c5f0f29fb9ecbb6e1677345cc6a284a76379389c97b9216cb2f28f2a7bebf6d612d25d883d4a97323d55167a9a7cda8a37603", // something to do with kes??
//    2, // protocol major version
//    0  // protocol minor version
//  ],
//  // body signature
//  "b7f8858559bbb912a275ed3e9f5cc98f05eab377db9c626cc51ccde650702cd2e21648e549a68b8ad133b09608904d298be3a19b0910fee1e10c925ac0d99f0eec87fa21af4a49e6982672c68aed11fdb95d6320c9a64d82752e7c16d24f8a4a48ffe19f81b93714610ccab3b012b2530331898e8a6e537061e6404bb788582a73e41dbfe690b9913e9142ea063f5237f78059f3dbf5a34deccdb22f3323d90ecb7899fffb1683907846d85bf92947d79e5a4bade98dd966f7b3b7c2640a46fad49f12e7df4608f674043bb72da07230de5b01e7ea8845c327688a8e4bbd3a422c99e80c57ed618fc0251352c583692990ff27cf8edd005c70269c78c39f5ccdbed39cf2c7c88f77109efb25ccd698276364038a784e0ca3ba9c86cd3d6e10da7f4104f7f834f19655e574b5ebcba5bf73f3523bb3caa15bb85184db14227f888bf3c484ea8cdc933e25556e930758337e5019b087ba075ae8151990b5222671091db5eaa758c93c7fa5251c8fa87fa95399de93bf1f856e17565cd6aa4a465232bb5228888e3d338045578b7e0745dcd56517c5a07b4ef906cf3032f4bb984942e9120472d2e7712dee2d31d07fecc5127f6b216fc6d00951a4dea9045dd0e9"
//]