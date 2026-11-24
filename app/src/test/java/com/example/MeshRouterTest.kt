package com.example

import com.example.domain.model.MeshMode
import com.example.domain.model.PeerNode
import com.example.domain.model.TransportType
import com.example.network.MeshRouter
import com.example.network.PacketType
import com.example.network.RouteNeighborAdvert
import com.example.network.SocketFrameCodec
import com.example.network.WireEnvelope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class MeshRouterTest {

    private fun peer(
        id: String,
        name: String,
        transport: TransportType = TransportType.WIFI_DIRECT,
        bw: Float = 65f
    ) = PeerNode(
        peerId = id,
        displayName = name,
        deviceName = "Android",
        transport = transport,
        isConnected = true,
        bandwidthMbps = bw
    )

    @Test
    fun `multi-hop route discovery across A to B to C to D`() {
        val router = MeshRouter()
        val directB = peer("NODE-B", "Relay B", TransportType.WIFI_DIRECT, 80f)
        router.onDirectNeighborsChanged("NODE-A", listOf(directB))

        // Node B advertises Node C (via B)
        router.onRouteAdvertReceived(
            localNodeId = "NODE-A",
            fromNeighborId = "NODE-B",
            advertisedBy = "NODE-B",
            neighbors = listOf(
                RouteNeighborAdvert("NODE-A", "Local A", TransportType.WIFI_DIRECT.name, 80f),
                RouteNeighborAdvert("NODE-C", "Node C", TransportType.HOTSPOT.name, 50f)
            ),
            directPeers = listOf(directB)
        )

        // Node C advertises Node D (via B -> C)
        router.onRouteAdvertReceived(
            localNodeId = "NODE-A",
            fromNeighborId = "NODE-B",
            advertisedBy = "NODE-C",
            neighbors = listOf(
                RouteNeighborAdvert("NODE-B", "Relay B", TransportType.HOTSPOT.name, 50f),
                RouteNeighborAdvert("NODE-D", "Node D", TransportType.BLUETOOTH.name, 3f)
            ),
            directPeers = listOf(directB)
        )

        val routeC = router.resolveNextHop("NODE-C", MeshMode.MESH_MODE)
        assertNotNull(routeC)
        assertEquals(2, routeC!!.hopCount)
        assertEquals("NODE-B", routeC.nextHopPeerId)
        assertEquals(listOf("NODE-A", "NODE-B", "NODE-C"), routeC.path)

        val routeD = router.resolveNextHop("NODE-D", MeshMode.MESH_MODE)
        assertNotNull(routeD)
        assertEquals(3, routeD!!.hopCount)
        assertEquals("NODE-B", routeD.nextHopPeerId)
        assertEquals(listOf("NODE-A", "NODE-B", "NODE-C", "NODE-D"), routeD.path)

        // DIRECT_ONLY rejects multi-hop destinations while keeping 1-hop direct routes
        assertNull(router.resolveNextHop("NODE-D", MeshMode.DIRECT_ONLY))
        assertNotNull(router.resolveNextHop("NODE-B", MeshMode.DIRECT_ONLY))
        assertEquals(2, router.multiHopPeers.value.size)
    }

    @Test
    fun `prefers shorter hop route and deduplicates seen packets`() {
        val router = MeshRouter()
        val peerB = peer("NODE-B", "B", TransportType.BLUETOOTH, 3f)
        val peerE = peer("NODE-E", "E", TransportType.WIFI_DIRECT, 90f)
        router.onDirectNeighborsChanged("NODE-A", listOf(peerB, peerE))

        // Long path via B -> C -> Target
        router.onRouteAdvertReceived(
            "NODE-A",
            "NODE-B",
            "NODE-B",
            listOf(RouteNeighborAdvert("NODE-C", "C", TransportType.BLUETOOTH.name, 3f)),
            listOf(peerB, peerE)
        )
        router.onRouteAdvertReceived(
            "NODE-A",
            "NODE-B",
            "NODE-C",
            listOf(RouteNeighborAdvert("TARGET", "Target", TransportType.BLUETOOTH.name, 3f)),
            listOf(peerB, peerE)
        )
        assertEquals(3, router.resolveNextHop("TARGET", MeshMode.MESH_MODE)?.hopCount)

        // Shorter 2-hop path via E -> Target
        router.onRouteAdvertReceived(
            "NODE-A",
            "NODE-E",
            "NODE-E",
            listOf(RouteNeighborAdvert("TARGET", "Target", TransportType.WIFI_DIRECT.name, 90f)),
            listOf(peerB, peerE)
        )
        val best = router.resolveNextHop("TARGET", MeshMode.MESH_MODE)
        assertNotNull(best)
        assertEquals(2, best!!.hopCount)
        assertEquals("NODE-E", best.nextHopPeerId)

        // Duplicate packet prevention
        assertTrue(router.markPacketSeenIfNew("pkt-unique-1"))
        assertFalse(router.markPacketSeenIfNew("pkt-unique-1"))
    }

    @Test
    fun `wire envelope json and frame codec preserve phase 2 mesh and call fields`() {
        val envelope = WireEnvelope(
            packetId = "pkt-v2-99",
            type = PacketType.CALL_INVITE.name,
            senderId = "NX-ALPHA",
            senderName = "Alpha",
            senderDevice = "Pixel 9",
            targetId = "NX-BRAVO",
            conversationId = "grp_mesh",
            isGroup = true,
            hopCount = 2,
            maxHops = 5,
            routeTraceCsv = "NX-ALPHA,NX-RELAY",
            callId = "call_1234",
            callType = "VIDEO",
            pttChannel = "Tactical-1",
            neighbors = listOf(
                RouteNeighborAdvert("NX-RELAY", "Relay", TransportType.WIFI_DIRECT.name, 75f, 2)
            )
        )
        val out = ByteArrayOutputStream()
        SocketFrameCodec.writeFrame(out, envelope)
        val decoded = SocketFrameCodec.readFrame(ByteArrayInputStream(out.toByteArray()))
        assertNotNull(decoded)
        val actual = decoded!!.first
        assertEquals("NX-BRAVO", actual.targetId)
        assertEquals(2, actual.hopCount)
        assertEquals("call_1234", actual.callId)
        assertEquals("VIDEO", actual.callType)
        assertEquals("Tactical-1", actual.pttChannel)
        assertEquals(1, actual.neighbors?.size)

        val json = SocketFrameCodec.toJson(envelope)
        val fromJson = SocketFrameCodec.fromJson(json)
        assertNotNull(fromJson)
        assertEquals(envelope.packetId, fromJson!!.packetId)
        assertEquals("NX-ALPHA,NX-RELAY", fromJson.routeTraceCsv)
    }

    @Test
    fun `peer disconnect purges unreachable multi-hop routes and preserves alternate path`() {
        val router = MeshRouter()
        val peerB = peer("NODE-B", "Relay B", TransportType.WIFI_DIRECT, 80f)
        val peerE = peer("NODE-E", "Relay E", TransportType.HOTSPOT, 50f)
        router.onDirectNeighborsChanged("NODE-A", listOf(peerB, peerE))

        // B reaches C (2 hops) and D (3 hops via B -> C)
        router.onRouteAdvertReceived(
            "NODE-A", "NODE-B", "NODE-B",
            listOf(RouteNeighborAdvert("NODE-C", "C", TransportType.WIFI_DIRECT.name, 80f)),
            listOf(peerB, peerE)
        )
        router.onRouteAdvertReceived(
            "NODE-A", "NODE-B", "NODE-C",
            listOf(RouteNeighborAdvert("NODE-D", "D", TransportType.WIFI_DIRECT.name, 80f)),
            listOf(peerB, peerE)
        )
        // E also reaches C (2 hops, lower bandwidth so B was initially preferred)
        router.onRouteAdvertReceived(
            "NODE-A", "NODE-E", "NODE-E",
            listOf(RouteNeighborAdvert("NODE-C", "C", TransportType.HOTSPOT.name, 40f)),
            listOf(peerB, peerE)
        )

        assertEquals("NODE-B", router.resolveNextHop("NODE-C", MeshMode.MESH_MODE)?.nextHopPeerId)
        assertEquals("NODE-B", router.resolveNextHop("NODE-D", MeshMode.MESH_MODE)?.nextHopPeerId)

        // Disconnect B: D (which only hung off B->C,Wait: C is still reachable via E -> C -> D!)
        // Now drop both B and E? First drop B -> C fails over cleanly to E!
        router.onPeerDisconnected("NODE-A", "NODE-B", listOf(peerE))
        assertNull(router.resolveNextHop("NODE-B", MeshMode.MESH_MODE))
        val failoverC = router.resolveNextHop("NODE-C", MeshMode.MESH_MODE)
        assertNotNull(failoverC)
        assertEquals("NODE-E", failoverC!!.nextHopPeerId)

        // Disconnect E as well -> C and D become unreachable cleanly
        router.onPeerDisconnected("NODE-A", "NODE-E", emptyList())
        assertNull(router.resolveNextHop("NODE-C", MeshMode.MESH_MODE))
        assertNull(router.resolveNextHop("NODE-D", MeshMode.MESH_MODE))
        assertTrue(router.multiHopPeers.value.isEmpty())
    }

    @Test
    fun `cyclic neighbor graphs do not loop infinitely`() {
        val router = MeshRouter()
        val peerB = peer("NODE-B", "B", TransportType.WIFI_DIRECT, 70f)
        router.onDirectNeighborsChanged("NODE-A", listOf(peerB))

        // B <-> C <-> B cycle pointing back to A
        router.onRouteAdvertReceived(
            "NODE-A", "NODE-B", "NODE-B",
            listOf(
                RouteNeighborAdvert("NODE-A", "A", TransportType.WIFI_DIRECT.name, 70f),
                RouteNeighborAdvert("NODE-C", "C", TransportType.WIFI_DIRECT.name, 70f)
            ),
            listOf(peerB)
        )
        router.onRouteAdvertReceived(
            "NODE-A", "NODE-B", "NODE-C",
            listOf(
                RouteNeighborAdvert("NODE-B", "B", TransportType.WIFI_DIRECT.name, 70f),
                RouteNeighborAdvert("NODE-A", "A", TransportType.WIFI_DIRECT.name, 70f)
            ),
            listOf(peerB)
        )

        val routeC = router.resolveNextHop("NODE-C", MeshMode.MESH_MODE)
        assertNotNull(routeC)
        assertEquals(listOf("NODE-A", "NODE-B", "NODE-C"), routeC!!.path)
        assertEquals(1, router.multiHopPeers.value.size)
    }

    @Test
    fun `frame codec rejects oversized or negative lengths`() {
        val invalidZero = ByteArrayOutputStream().apply {
            java.io.DataOutputStream(this).writeInt(0)
        }.toByteArray()
        var threwZero = false
        try {
            SocketFrameCodec.readFrame(ByteArrayInputStream(invalidZero))
        } catch (_: IllegalStateException) {
            threwZero = true
        }
        assertTrue(threwZero)

        val oversizedHeader = ByteArrayOutputStream().apply {
            java.io.DataOutputStream(this).writeInt(64 * 1024 * 1024)
        }.toByteArray()
        var threwOversized = false
        try {
            SocketFrameCodec.readFrame(ByteArrayInputStream(oversizedHeader))
        } catch (_: IllegalStateException) {
            threwOversized = true
        }
        assertTrue(threwOversized)
    }
}

