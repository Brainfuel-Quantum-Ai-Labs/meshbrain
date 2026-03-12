"""
MeshBrain — Mesh Node (P2P Networking)
Handles peer discovery, WebSocket connections, packet broadcast/receive.
Two phones on same WiFi can find each other automatically.
Works over internet relay for cross-network peers.
"""

import asyncio
import json
import time
import socket
import hashlib
import logging
from typing import Dict, Set, Optional, Callable, List

import aiohttp
from aiohttp import web

from core.identity import NodeIdentity
from core.packet import NanoPacket, PACKET_KNOWLEDGE, PACKET_HANDSHAKE, PACKET_HEARTBEAT
from core.trust import TrustManager

log = logging.getLogger("MeshNode")


class MeshNode:
    """
    One node in the MeshBrain P2P network.
    Each phone/computer runs one of these.
    
    Handles:
    - WebSocket server (peers connect to you)
    - WebSocket client (you connect to peers)  
    - mDNS-style local discovery via UDP broadcast
    - Packet routing + trust verification
    - Knowledge integration from verified peers
    """

    def __init__(
        self,
        identity: NodeIdentity,
        trust: TrustManager,
        port: int = 8765,
        relay_url: Optional[str] = None
    ):
        self.identity = identity
        self.trust = trust
        self.port = port
        self.relay_url = relay_url

        # Connected peers: node_id_hex -> websocket
        self.peers: Dict[str, web.WebSocketResponse] = {}
        self.peer_clients: Dict[str, aiohttp.ClientWebSocketResponse] = {}

        # Callbacks
        self.on_knowledge_received: Optional[Callable] = None
        self.on_peer_connected: Optional[Callable] = None
        self.on_peer_disconnected: Optional[Callable] = None

        # Stats
        self.packets_sent = 0
        self.packets_received = 0
        self.bytes_sent = 0
        self.bytes_received = 0

        self._app = web.Application()
        self._runner: Optional[web.AppRunner] = None

    # ── Server (others connect to us) ────────────────────────────

    async def start_server(self):
        """Start WebSocket server so peers can connect to us"""
        self._app.router.add_get("/mesh", self._ws_handler)
        self._app.router.add_get("/status", self._status_handler)

        self._runner = web.AppRunner(self._app)
        await self._runner.setup()
        site = web.TCPSite(self._runner, "0.0.0.0", self.port)
        await site.start()

        local_ip = self._get_local_ip()
        print(f"[Mesh] 🌐 Node listening on ws://{local_ip}:{self.port}/mesh")
        print(f"[Mesh] 📊 Status at http://{local_ip}:{self.port}/status")
        print(f"[Mesh] 🔑 Node ID: {self.identity.node_id_short()}")

    async def _ws_handler(self, request):
        """Handle incoming WebSocket connection from a peer"""
        ws = web.WebSocketResponse(heartbeat=30)
        await ws.prepare(request)

        peer_node_id = None
        try:
            # Send handshake
            hs = NanoPacket.create_handshake(self.identity)
            await ws.send_bytes(hs.to_bytes())

            async for msg in ws:
                if msg.type == aiohttp.WSMsgType.BINARY:
                    try:
                        packet = NanoPacket.from_bytes(msg.data)
                        self.packets_received += 1
                        self.bytes_received += len(msg.data)

                        if packet.packet_type == PACKET_HANDSHAKE:
                            peer_node_id = packet.node_id.hex()
                            self.peers[peer_node_id] = ws
                            print(f"[Mesh] 🤝 Peer connected: {peer_node_id[:12]}...")
                            if self.on_peer_connected:
                                await self.on_peer_connected(peer_node_id)
                        else:
                            await self._handle_packet(packet)
                    except Exception as e:
                        log.warning(f"Bad packet from peer: {e}")

                elif msg.type in (aiohttp.WSMsgType.ERROR, aiohttp.WSMsgType.CLOSE):
                    break
        finally:
            if peer_node_id and peer_node_id in self.peers:
                del self.peers[peer_node_id]
                print(f"[Mesh] 👋 Peer disconnected: {peer_node_id[:12]}...")
                if self.on_peer_disconnected:
                    await self.on_peer_disconnected(peer_node_id)
        return ws

    async def _status_handler(self, request):
        """HTTP status endpoint — useful for debugging"""
        status = {
            "node_id": self.identity.node_id_hex,
            "node_id_short": self.identity.node_id_short(),
            "peers_connected": len(self.peers) + len(self.peer_clients),
            "packets_sent": self.packets_sent,
            "packets_received": self.packets_received,
            "bytes_sent": self.bytes_sent,
            "bytes_received": self.bytes_received,
            "trust": self.trust.status(),
            "port": self.port,
        }
        return web.json_response(status)

    # ── Client (we connect to others) ────────────────────────────

    async def connect_to_peer(self, peer_url: str) -> bool:
        """
        Connect to a specific peer by URL.
        peer_url format: ws://192.168.1.X:8765/mesh
        """
        try:
            session = aiohttp.ClientSession()
            ws = await session.ws_connect(
                peer_url,
                timeout=aiohttp.ClientWSTimeout(ws_close=10)
            )

            # Send our handshake
            hs = NanoPacket.create_handshake(self.identity)
            await ws.send_bytes(hs.to_bytes())

            peer_node_id = None

            # Listen in background
            async def _listen():
                nonlocal peer_node_id
                async for msg in ws:
                    if msg.type == aiohttp.WSMsgType.BINARY:
                        try:
                            packet = NanoPacket.from_bytes(msg.data)
                            self.packets_received += 1
                            self.bytes_received += len(msg.data)

                            if packet.packet_type == PACKET_HANDSHAKE:
                                peer_node_id = packet.node_id.hex()
                                self.peer_clients[peer_node_id] = ws
                                print(f"[Mesh] ✅ Connected to: {peer_node_id[:12]}...")
                                if self.on_peer_connected:
                                    await self.on_peer_connected(peer_node_id)
                            else:
                                await self._handle_packet(packet)
                        except Exception as e:
                            log.warning(f"Peer message error: {e}")
                    elif msg.type in (aiohttp.WSMsgType.ERROR, aiohttp.WSMsgType.CLOSE):
                        break

                if peer_node_id and peer_node_id in self.peer_clients:
                    del self.peer_clients[peer_node_id]
                    print(f"[Mesh] 📡 Peer client disconnected: {peer_node_id[:12]}...")
                await session.close()

            asyncio.create_task(_listen())
            print(f"[Mesh] 📡 Connecting to {peer_url}...")
            return True

        except Exception as e:
            print(f"[Mesh] ❌ Failed to connect to {peer_url}: {e}")
            return False

    # ── Packet handling ───────────────────────────────────────────

    async def _handle_packet(self, packet: NanoPacket):
        """Process and verify an incoming packet"""
        accepted, reason = self.trust.verify_and_score(packet, self.identity)

        if not accepted:
            log.debug(f"Packet rejected: {reason}")
            return

        if packet.packet_type == PACKET_KNOWLEDGE:
            self.trust.integrate_knowledge(packet)
            self.trust.reward(packet.node_id.hex())
            print(f"[Mesh] 💡 Knowledge integrated from {packet.node_id.hex()[:8]}... "
                  f"quality={packet.quality_score:.2f} size={packet.size_bytes()}B")

            if self.on_knowledge_received:
                await self.on_knowledge_received(packet)

            # Relay to other peers (gossip protocol) if hop count allows
            if packet.hop_count < 3:
                packet.hop_count += 1
                await self._relay_to_others(packet, exclude=packet.node_id.hex())

        elif packet.packet_type == PACKET_HEARTBEAT:
            self.trust.reward(packet.node_id.hex(), delta=0.005)

    async def _relay_to_others(self, packet: NanoPacket, exclude: str = ""):
        """Forward a packet to all other connected peers (gossip)"""
        data = packet.to_bytes()
        for node_id, ws in list(self.peers.items()):
            if node_id != exclude:
                try:
                    await ws.send_bytes(data)
                except Exception:
                    pass
        for node_id, ws in list(self.peer_clients.items()):
            if node_id != exclude:
                try:
                    await ws.send_bytes(data)
                except Exception:
                    pass

    # ── Broadcast ─────────────────────────────────────────────────

    async def broadcast_knowledge(self, packet: NanoPacket):
        """Send a knowledge packet to all connected peers"""
        data = packet.to_bytes()
        sent_to = 0
        for ws in list(self.peers.values()):
            try:
                await ws.send_bytes(data)
                sent_to += 1
            except Exception:
                pass
        for ws in list(self.peer_clients.values()):
            try:
                await ws.send_bytes(data)
                sent_to += 1
            except Exception:
                pass
        self.packets_sent += 1
        self.bytes_sent += len(data)
        if sent_to > 0:
            print(f"[Mesh] 📤 Broadcast knowledge to {sent_to} peers ({len(data)}B)")
        return sent_to

    async def send_heartbeat(self):
        """Broadcast a heartbeat to all peers"""
        reputation = self.trust.status().get("integrated_packets", 0) / max(1, 100)
        reputation = min(1.0, reputation)
        hb = NanoPacket.create_heartbeat(self.identity, reputation)
        await self.broadcast_knowledge(hb)

    # ── Discovery ─────────────────────────────────────────────────

    async def discover_local_peers(self):
        """
        Simple LAN discovery: broadcast our presence on local network.
        Peers on same WiFi network will see this and can connect.
        """
        local_ip = self._get_local_ip()
        if local_ip == "127.0.0.1":
            print("[Mesh] 📡 Discovery: no LAN interface found, skipping")
            return

        # UDP broadcast
        DISCOVERY_PORT = 8766
        DISCOVERY_MSG = json.dumps({
            "type": "meshbrain_announce",
            "node_id": self.identity.node_id_hex,
            "ws_port": self.port,
            "ip": local_ip,
        }).encode()

        # Send broadcast
        try:
            sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
            sock.sendto(DISCOVERY_MSG, ("<broadcast>", DISCOVERY_PORT))
            sock.close()
            print(f"[Mesh] 📡 Discovery broadcast sent from {local_ip}:{self.port}")
        except Exception as e:
            print(f"[Mesh] Discovery broadcast failed: {e}")

        # Also listen for others
        asyncio.create_task(self._listen_for_peers(DISCOVERY_PORT))

    async def _listen_for_peers(self, port: int):
        """Listen for peer discovery broadcasts"""
        try:
            sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            sock.bind(("", port))
            sock.setblocking(False)

            print(f"[Mesh] 👂 Listening for peers on UDP:{port}")
            loop = asyncio.get_event_loop()

            while True:
                try:
                    data, addr = await loop.run_in_executor(None, sock.recvfrom, 1024)
                    msg = json.loads(data)
                    if (msg.get("type") == "meshbrain_announce" and
                            msg.get("node_id") != self.identity.node_id_hex):
                        peer_url = f"ws://{msg['ip']}:{msg['ws_port']}/mesh"
                        peer_id = msg["node_id"]
                        if peer_id not in self.peers and peer_id not in self.peer_clients:
                            print(f"[Mesh] 🔍 Discovered peer: {msg['ip']}:{msg['ws_port']}")
                            await self.connect_to_peer(peer_url)
                except (json.JSONDecodeError, KeyError):
                    pass
                except Exception:
                    pass
                await asyncio.sleep(0.1)
        except Exception as e:
            print(f"[Mesh] Discovery listener error: {e}")

    # ── Helpers ───────────────────────────────────────────────────

    def _get_local_ip(self) -> str:
        try:
            s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            s.connect(("8.8.8.8", 80))
            ip = s.getsockname()[0]
            s.close()
            return ip
        except Exception:
            return "127.0.0.1"

    def peer_count(self) -> int:
        return len(self.peers) + len(self.peer_clients)

    async def stop(self):
        if self._runner:
            await self._runner.cleanup()

    def __repr__(self):
        return (f"<MeshNode id={self.identity.node_id_short()} "
                f"peers={self.peer_count()} port={self.port}>")
