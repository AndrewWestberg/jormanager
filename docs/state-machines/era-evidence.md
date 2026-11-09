# Current-era queries

`CardanoRepository.getEra` queries the configured node's tip and accepts a known era name, including Dijkstra. Host ownership, network arguments and socket path must match the selected node. It no longer probes process ownership or persists an authorization generation.

The latest observation remains in memory for Leios status consumers. A failed query marks it unavailable; a subsequent call queries again. Same-era observations retain their local generation; an era change increments it. Historical block eras trigger a node query when different, rather than determining the current era themselves.

Wallet transactions query current protocol parameters without persisted snapshots or exact-tip equality checks. There is no durable approval or refresh state machine to recover after restart.
