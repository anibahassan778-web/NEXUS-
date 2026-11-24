package com.example.network

import com.example.domain.model.ActiveMeshPath
import com.example.domain.model.MeshMode
import com.example.domain.model.MeshRoute
import com.example.domain.model.PeerNode
import com.example.domain.model.TransportType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Decentralized multi-hop mesh routing engine:
 * - Tracks direct neighbors and timestamped multi-hop routes learned from ROUTE_ADVERT packets
 * - Prevents routing loops & duplicate packet delivery via TTL-pruned seenPacketCache
 * - Expires stale neighbor graphs and automatically recomputes alternative paths on disconnect
 * - Emits active packet paths for real-time Mesh Network Visualization
 */
class MeshRouter {
    companion object {
        const val ROUTE_TTL_MS = 35_000L
        const val SEEN_CACHE_TTL_MS = 90_000L
        const val MAX_SEEN_CACHE_SIZE = 1500
    }

    private data class NeighborGraphEntry(
        val neighbors: List<RouteNeighborAdvert>,
        val updatedAtMs: Long
    )

    private val seenPackets = ConcurrentHashMap<String, Long>()
    private val neighborGraphs = ConcurrentHashMap<String, NeighborGraphEntry>()
    private val routeLock = Any()

    private val _routes = MutableStateFlow<Map<String, MeshRoute>>(emptyMap())
    val routes: StateFlow<Map<String, MeshRoute>> = _routes.asStateFlow()

    private val _multiHopPeers = MutableStateFlow<List<PeerNode>>(emptyList())
    val multiHopPeers: StateFlow<List<PeerNode>> = _multiHopPeers.asStateFlow()

    private val _topologyEdges = MutableStateFlow<List<Pair<String, String>>>(emptyList())
    val topologyEdges: StateFlow<List<Pair<String, String>>> = _topologyEdges.asStateFlow()

    private val _activePaths = MutableStateFlow<List<ActiveMeshPath>>(emptyList())
    val activePaths: StateFlow<List<ActiveMeshPath>> = _activePaths.asStateFlow()

    fun markPacketSeenIfNew(packetId: String): Boolean {
        val now = System.currentTimeMillis()
        if (seenPackets.size > MAX_SEEN_CACHE_SIZE) {
            pruneSeenPackets(now)
        }
        return seenPackets.putIfAbsent(packetId, now) == null
    }

    fun pruneSeenPackets(now: Long = System.currentTimeMillis()) {
        seenPackets.entries.removeIf { now - it.value > SEEN_CACHE_TTL_MS }
    }

    fun onDirectNeighborsChanged(localNodeId: String, directPeers: List<PeerNode>) {
        synchronized(routeLock) {
            val now = System.currentTimeMillis()
            neighborGraphs[localNodeId] = NeighborGraphEntry(
                neighbors = directPeers.map {
                    RouteNeighborAdvert(
                        peerId = it.peerId,
                        displayName = it.displayName,
                        transportName = it.transport.name,
                        bandwidthMbps = it.bandwidthMbps,
                        avatarIndex = it.avatarIndex
                    )
                },
                updatedAtMs = now
            )
            // Remove neighbor graphs of peers that are no longer directly connected if they aren't reachable via another hop
            recomputeRoutingTableLocked(localNodeId, directPeers, now)
        }
    }

    fun onPeerDisconnected(localNodeId: String, disconnectedPeerId: String, remainingDirectPeers: List<PeerNode>) {
        synchronized(routeLock) {
            neighborGraphs.remove(disconnectedPeerId)
            neighborGraphs[localNodeId] = NeighborGraphEntry(
                neighbors = remainingDirectPeers.map {
                    RouteNeighborAdvert(
                        peerId = it.peerId,
                        displayName = it.displayName,
                        transportName = it.transport.name,
                        bandwidthMbps = it.bandwidthMbps,
                        avatarIndex = it.avatarIndex
                    )
                },
                updatedAtMs = System.currentTimeMillis()
            )
            recomputeRoutingTableLocked(localNodeId, remainingDirectPeers, System.currentTimeMillis())
        }
    }

    fun onRouteAdvertReceived(
        localNodeId: String,
        fromNeighborId: String,
        advertisedBy: String,
        neighbors: List<RouteNeighborAdvert>,
        directPeers: List<PeerNode>
    ) {
        if (advertisedBy == localNodeId) return
        synchronized(routeLock) {
            val now = System.currentTimeMillis()
            neighborGraphs[advertisedBy] = NeighborGraphEntry(neighbors, now)
            recomputeRoutingTableLocked(localNodeId, directPeers, now)
        }
    }

    fun pruneExpiredRoutes(localNodeId: String, directPeers: List<PeerNode>) {
        val now = System.currentTimeMillis()
        pruneSeenPackets(now)
        synchronized(routeLock) {
            val expiredGraph = neighborGraphs.entries.removeIf { (nodeId, entry) ->
                nodeId != localNodeId && (now - entry.updatedAtMs) > ROUTE_TTL_MS
            }
            val hasExpiredRoute = _routes.value.values.any { it.hopCount > 1 && it.expiresAtMs < now }
            if (expiredGraph || hasExpiredRoute) {
                recomputeRoutingTableLocked(localNodeId, directPeers, now)
            }
        }
        _activePaths.update { list ->
            list.filter { now - it.timestamp < 4_500L }
        }
    }

    fun recordActivePath(hops: List<String>, transport: TransportType) {
        if (hops.size < 2) return
        val path = ActiveMeshPath(
            id = UUID.randomUUID().toString(),
            hops = hops,
            transport = transport
        )
        _activePaths.update { list ->
            (list + path).takeLast(12)
        }
    }

    fun resolveNextHop(destinationId: String, meshMode: MeshMode): MeshRoute? {
        val route = _routes.value[destinationId] ?: return null
        if (meshMode == MeshMode.DIRECT_ONLY && route.hopCount > 1) return null
        return route
    }

    private fun recomputeRoutingTableLocked(
        localNodeId: String,
        directPeers: List<PeerNode>,
        now: Long
    ) {
        val newRoutes = mutableMapOf<String, MeshRoute>()
        val directMap = directPeers.associateBy { it.peerId }
        val edges = mutableSetOf<Pair<String, String>>()

        // 1-hop direct routes
        directPeers.forEach { peer ->
            edges.add(canonicalEdge(localNodeId, peer.peerId))
            newRoutes[peer.peerId] = MeshRoute(
                destinationId = peer.peerId,
                destinationName = peer.displayName,
                nextHopPeerId = peer.peerId,
                hopCount = 1,
                path = listOf(localNodeId, peer.peerId),
                transport = peer.transport,
                bandwidthMbps = peer.bandwidthMbps,
                expiresAtMs = now + ROUTE_TTL_MS
            )
        }

        // BFS for multi-hop routes (up to 5 hops)
        val queue = ArrayDeque<String>()
        directPeers.forEach { queue.add(it.peerId) }

        val remoteMeta = mutableMapOf<String, RouteNeighborAdvert>()

        while (queue.isNotEmpty()) {
            val currentId = queue.removeFirst()
            val currentRoute = newRoutes[currentId] ?: continue
            if (currentRoute.hopCount >= 5) continue

            val entry = neighborGraphs[currentId] ?: continue
            if (now - entry.updatedAtMs > ROUTE_TTL_MS) continue

            for (nbr in entry.neighbors) {
                if (nbr.peerId == localNodeId || nbr.peerId in currentRoute.path) continue
                edges.add(canonicalEdge(currentId, nbr.peerId))
                remoteMeta[nbr.peerId] = nbr

                val candidateHops = currentRoute.hopCount + 1
                val existing = newRoutes[nbr.peerId]
                val candidateBw = minOf(currentRoute.bandwidthMbps, nbr.bandwidthMbps)

                val isBetter = existing == null ||
                    candidateHops < existing.hopCount ||
                    (candidateHops == existing.hopCount && candidateBw > existing.bandwidthMbps)

                if (isBetter) {
                    val nextHop = currentRoute.nextHopPeerId
                    val nextHopPeer = directMap[nextHop]
                    newRoutes[nbr.peerId] = MeshRoute(
                        destinationId = nbr.peerId,
                        destinationName = nbr.displayName,
                        nextHopPeerId = nextHop,
                        hopCount = candidateHops,
                        path = currentRoute.path + nbr.peerId,
                        transport = nextHopPeer?.transport ?: currentRoute.transport,
                        bandwidthMbps = candidateBw,
                        expiresAtMs = now + ROUTE_TTL_MS
                    )
                    queue.add(nbr.peerId)
                }
            }
        }

        _routes.value = newRoutes
        _topologyEdges.value = edges.toList()
        _multiHopPeers.value = newRoutes.values
            .filter { it.hopCount > 1 }
            .map { route ->
                val meta = remoteMeta[route.destinationId]
                PeerNode(
                    peerId = route.destinationId,
                    displayName = route.destinationName,
                    deviceName = "Mesh Node (${route.hopCount} hops via ${route.nextHopPeerId})",
                    statusText = "Multi-hop route: ${route.path.joinToString(" → ")}",
                    avatarIndex = meta?.avatarIndex ?: 0,
                    transport = route.transport,
                    isConnected = true,
                    isRelayCapable = true,
                    hopCount = route.hopCount,
                    viaPeerId = route.nextHopPeerId,
                    signalStrength = (95 - route.hopCount * 14).coerceIn(25, 90),
                    bandwidthMbps = route.bandwidthMbps
                )
            }
    }

    private fun canonicalEdge(a: String, b: String): Pair<String, String> =
        if (a <= b) a to b else b to a
}
