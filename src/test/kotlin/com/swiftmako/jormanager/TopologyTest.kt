package com.swiftmako.jormanager

import com.squareup.moshi.Moshi
import com.swiftmako.jormanager.model.topology.Producer
import com.swiftmako.jormanager.model.topology.Topology
import okhttp3.CacheControl
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.buffer
import okio.sink
import okio.source
import org.junit.jupiter.api.Test
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.util.TreeSet
import kotlin.system.measureTimeMillis

class TopologyTest {

    private val northAmerica = TreeSet<Producer> { producer1, producer2 -> producer1.latency.compareTo(producer2.latency) }
    private val europe = TreeSet<Producer> { producer1, producer2 -> producer1.latency.compareTo(producer2.latency) }
    private val asiaPacific = TreeSet<Producer> { producer1, producer2 -> producer1.latency.compareTo(producer2.latency) }

    @Test
    fun pingNodesTopologyTest() {
        val client = OkHttpClient.Builder().build()
        val response = client.newCall(
                Request.Builder()
                        .get()
                        .url("https://explorer.mainnet-candidate-4.dev.cardano.org/relays/topology.json")
                        .cacheControl(CacheControl.FORCE_NETWORK)
                        .build()
        ).execute()

        response.body?.source()?.let { source ->
            val adapter = Moshi.Builder().build().adapter(Topology::class.java).indent("  ")
            adapter.fromJson(source)?.let { topology ->
                topology.producers.filter { !it.addr.contains("bluecheese") }.forEach { producer ->
                    try {
                        Socket().use { socket ->
                            val durationMs = measureTimeMillis {
                                socket.connect(InetSocketAddress(producer.addr, producer.port), 1000)
                            }
                            println("${producer.continent}, ${producer.state} - ${producer.addr}:${producer.port}: ${durationMs}ms")
                            producer.latency = durationMs
                            when (producer.continent) {
                                "North America" -> northAmerica.add(producer)
                                "Europe" -> europe.add(producer)
                                else -> asiaPacific.add(producer)
                            }
                        }
                    } catch (e: Throwable) {
                        println("${producer.continent}, ${producer.state} - ${producer.addr}:${producer.port}: FAILURE!")
                    }
                }
            }

            val relay0Topology = File("/home/westbam/haskell/relay0static.json").source().buffer().use { relay0Source ->
                adapter.fromJson(relay0Source)!!
            }
            val relay1Topology = File("/home/westbam/haskell/relay1static.json").source().buffer().use { relay1Source ->
                adapter.fromJson(relay1Source)!!
            }
            val relay2Topology = File("/home/westbam/haskell/relay2static.json").source().buffer().use { relay2Source ->
                adapter.fromJson(relay2Source)!!
            }

            File("/home/westbam/haskell/northamerica.json").sink().buffer().use { northAmericaSink ->
                adapter.toJson(northAmericaSink, Topology(relay0Topology.producers + northAmerica.take(15)))
            }

            File("/home/westbam/haskell/europe.json").sink().buffer().use { europeSink ->
                adapter.toJson(europeSink, Topology(relay1Topology.producers + europe.take(15)))
            }

            File("/home/westbam/haskell/asiapacific.json").sink().buffer().use { asiaPacificSink ->
                adapter.toJson(asiaPacificSink, Topology(relay2Topology.producers + asiaPacific.take(15)))
            }
        }
    }
}