# Task 204 Node-State DataPoint Contract

## Status

- Implemented and review-approved on 2026-05-14.

## Durable Findings

- The first node-state migration version is pinned to the direct `DataPoint` mini-protocol, not forwarded `TraceObject`s.
- JorManager's pinned eight-key node-state request list is:
  - `cardano.node.metrics.connectionManager.outgoingConns`
  - `cardano.node.metrics.connectionManager.incomingConns`
  - `cardano.node.metrics.blockNum`
  - `cardano.node.metrics.remainingKESPeriods`
  - `cardano.node.metrics.epoch`
  - `cardano.node.metrics.slotNum`
  - `cardano.node.metrics.slotInEpoch`
  - `cardano.node.metrics.txsProcessedNum`
- The pinned `DataPoint` wire contract for this task is:
  - request id `1`: `array(2)[1, [names...]]`
  - done id `2`: `array(1)[2]`
  - reply id `3`: `array(2)[3, [(name, maybeValue)...]]`
  - `Nothing` encoded as `array(0)`
  - `Just value` encoded as `array(1)[bytes]`
- Independent literal CBOR hex anchors now pin the minimum node-state protocol fixtures so builders and fake-server tests cannot self-validate against the same helper output:
  - exact eight-key request: `820188783463617264616e6f2e6e6f64652e6d6574726963732e636f6e6e656374696f6e4d616e616765722e6f7574676f696e67436f6e6e73783463617264616e6f2e6e6f64652e6d6574726963732e636f6e6e656374696f6e4d616e616765722e696e636f6d696e67436f6e6e73781d63617264616e6f2e6e6f64652e6d6574726963732e626c6f636b4e756d782863617264616e6f2e6e6f64652e6d6574726963732e72656d61696e696e674b4553506572696f6473781a63617264616e6f2e6e6f64652e6d6574726963732e65706f6368781c63617264616e6f2e6e6f64652e6d6574726963732e736c6f744e756d782063617264616e6f2e6e6f64652e6d6574726963732e736c6f74496e45706f6368782463617264616e6f2e6e6f64652e6d6574726963732e74787350726f6365737365644e756d`
  - full positive reply: `82038882783463617264616e6f2e6e6f64652e6d6574726963732e636f6e6e656374696f6e4d616e616765722e6f7574676f696e67436f6e6e738142313282783463617264616e6f2e6e6f64652e6d6574726963732e636f6e6e656374696f6e4d616e616765722e696e636f6d696e67436f6e6e7381413782781d63617264616e6f2e6e6f64652e6d6574726963732e626c6f636b4e756d81473734303332323182782863617264616e6f2e6e6f64652e6d6574726963732e72656d61696e696e674b4553506572696f64738142333682781a63617264616e6f2e6e6f64652e6d6574726963732e65706f6368814334393082781c63617264616e6f2e6e6f64652e6d6574726963732e736c6f744e756d81473734303332323182782063617264616e6f2e6e6f64652e6d6574726963732e736c6f74496e45706f6368814333323182782463617264616e6f2e6e6f64652e6d6574726963732e74787350726f6365737365644e756d8146313233343536`
- The decoder must join all `CborByteString` chunks before parsing the JSON scalar payload. The NEWM CBOR library exposes byte strings as `byte[][]` segments and its built-in `toJavaObject()` only returns the first chunk, so chunk joining is a required compatibility step for Haskell `serialise` indefinite-length byte strings.
- The minimal runtime topology remains one long-lived trace-object session plus one separate long-lived sibling `DataPoint` session per eligible core node. `task-204` does not widen `TracingConnectionManager` yet.

## Verification Evidence

- Focused backend verification command:
  - `./gradlew test --tests "com.swiftmako.jormanager.tracing.NodeStateDataPointDecoderTest" --tests "com.swiftmako.jormanager.tracing.fixtures.TraceForwardFixturesTest"`
- Current repo Gradle wiring also runs Vue unit tests and frontend production build during that command; both passed during task-204 verification.

## Files

- `src/main/kotlin/com/swiftmako/jormanager/tracing/TraceForwardMessage.kt`
- `src/main/kotlin/com/swiftmako/jormanager/tracing/TraceForwardSessionClient.kt`
- `src/main/kotlin/com/swiftmako/jormanager/tracing/DataPointSessionClient.kt`
- `src/main/kotlin/com/swiftmako/jormanager/tracing/NodeStateDataPointDecoder.kt`
- `src/test/kotlin/com/swiftmako/jormanager/tracing/fixtures/TraceForwardFixtures.kt`
- `src/test/kotlin/com/swiftmako/jormanager/tracing/fixtures/TraceForwardFixturesTest.kt`
- `src/test/kotlin/com/swiftmako/jormanager/tracing/NodeStateDataPointDecoderTest.kt`
