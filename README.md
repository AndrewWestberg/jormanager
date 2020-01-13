# JorManager

This project was born out of my frustration as a stakepool operator on the incentivized testnet for cardano. It is designed to spin up many Jormungandr nodes at once for improved reliability and bootstrapping. The nodes are monitored for healthiness on an interval and a single node in the cluster is promoted to leader (capable of minting blocks). Other nodes remain passive, but could be promoted to leader if they become the healthiest.

![alt text](jormanager_log.png "JorManager Log")

![alt text](jormanager_status.png "JorManager Status")

## Health

The health calculation is determined by:

  * The block height of a node. Higher is better.
  * The number of peers a node has. 248 peers is the current default for marking a node as "healthy"
  * The uptime a peer has. Normally, you'd think a longer-uptime node would be healthier, but since the ITN is so unstable, we currently consider the youngest node to be the healthiest. The old ones are closer to "death".

## Setup

1. Download and extract `JorManager-#.#.#-SNAPSHOT.tar.bz2` to a folder of your choosing.
2. Create your config.yaml files. The default uses 10 nodes, so for this setup, create `itn_rewards_v1-config00.yaml` up to `itn_rewards_v1-config09.yaml`
3. Edit your config.yaml files. Ensure to update the following:
    * Each node should use a different storage folder. `mkdir` these storage folders that you specify here. The software will not create them for you.
    * Each node should use `0.0.0.0` as the `listen_address`. _Important_ since other local nodes will connect to it on 127.0.0.1.
    * `public_id` should be unique to your setup, but can follow a pattern to make things simple. See the `itn_rewards_v1-config04.yaml` example file.
    * If you already have a storage folder from a previous node, copy the contents of it to each and every storage file of the new setup. This will greatly improve your initial bootstrap time and you won't have to download the entire blockchain 10 times.
    * Edit the port numbers for both the node itself and the REST port. The nodes won't work right if there are port conflicts.
    * _Important!_ Don't forget to modify your firewall to allow these new ports from the outside.
4. Edit `application.properties` for your own stakepool and https://pooltool.io information.

## Running

```
$ java -jar JorManager-#.#.#-SNAPSHOT.jar
```

## Support

If you need support, the Beta test group meets on this telegram channel -> https://t.me/jormanager

#### Project Support

This project is designed to do nothing more than help out the Cardano community and ecosystem. It's offered without charge and without warranty of any kind. However, if you feel inclined to tip the developer, that can be done by sending MainNet ADA to the following address:
  
```
DdzFFzCqrht3wNbkrRTt36nrHbSBNHaJ6mTMthoaKfwwcTRSmTudRdbgcgS3cdUjJ8mweNkrHrSqM4mLXAgMh7aVDjPxobYr7rh6s8E2
```

###### Release Notes

0.0.4-SNAPSHOT - Add quiet period for leader election during block creation times.

0.0.3-SNAPSHOT - Bug fixes & cleaned up logging

0.0.2-SNAPSHOT - Bug fixes 

0.0.1-SNAPSHOT - Initial Beta Release