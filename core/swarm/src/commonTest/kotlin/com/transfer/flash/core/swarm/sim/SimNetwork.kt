package com.transfer.flash.core.swarm.sim

/**
 * Simulated network connecting SimNodes with latency, bandwidth constraints,
 * and disconnect/partition controls (SW-5).
 */
class SimNetwork(
    private val clock: SimClock,
    private val queue: SimEventQueue,
) {
    data class LinkConfig(
        val latencyMs: Long = 5L,
        val rateBytesPerMs: Long = 10_000L, // 10 MB/s default
    )

    private val nodes = LinkedHashMap<String, SimNode>()
    private val defaultLink = LinkConfig()
    private val linkOverrides = LinkedHashMap<Pair<String, String>, LinkConfig>()
    private val partitionedPairs = LinkedHashSet<Pair<String, String>>()
    private val disconnectedNodes = LinkedHashSet<String>()

    fun registerNode(node: SimNode) {
        nodes[node.id] = node
    }

    fun getNode(nodeId: String): SimNode? = nodes[nodeId]

    fun setLink(fromId: String, toId: String, config: LinkConfig) {
        linkOverrides[fromId to toId] = config
    }

    fun partition(nodeA: String, nodeB: String) {
        partitionedPairs.add(nodeA to nodeB)
        partitionedPairs.add(nodeB to nodeA)
    }

    fun healPartition(nodeA: String, nodeB: String) {
        partitionedPairs.remove(nodeA to nodeB)
        partitionedPairs.remove(nodeB to nodeA)
    }

    fun isLinkActive(fromId: String, toId: String): Boolean {
        if (fromId in disconnectedNodes || toId in disconnectedNodes) return false
        if ((fromId to toId) in partitionedPairs) return false
        return true
    }

    fun send(fromId: String, toId: String, payloadBytes: Long, action: () -> Unit) {
        if (!isLinkActive(fromId, toId)) return

        val link = linkOverrides[fromId to toId] ?: defaultLink
        val transferDelay = if (link.rateBytesPerMs > 0) payloadBytes / link.rateBytesPerMs else 0L
        val totalDelay = link.latencyMs + transferDelay

        queue.schedule(totalDelay) {
            if (isLinkActive(fromId, toId)) {
                action()
            }
        }
    }

    fun disconnect(nodeId: String) {
        if (!disconnectedNodes.add(nodeId)) return
        val disconnectedNode = nodes[nodeId] ?: return

        for ((otherId, otherNode) in nodes) {
            if (otherId != nodeId && otherId !in disconnectedNodes) {
                otherNode.onPeerDown(nodeId)
                disconnectedNode.onPeerDown(otherId)
            }
        }
    }

    fun reconnect(nodeId: String) {
        if (!disconnectedNodes.remove(nodeId)) return
        val reconnectedNode = nodes[nodeId] ?: return

        for ((otherId, otherNode) in nodes) {
            if (otherId != nodeId && otherId !in disconnectedNodes) {
                val otherFeatures = if (otherNode.isLegacy) emptySet() else setOf("sw1")
                val myFeatures = if (reconnectedNode.isLegacy) emptySet() else setOf("sw1")

                otherNode.onPeerUp(nodeId, myFeatures)
                reconnectedNode.onPeerUp(otherId, otherFeatures)
            }
        }
    }
}
