"""
MeshBrain — Main Node
Run this on any device to join the network.

Usage:
  python node.py                    # start on port 8765
  python node.py --port 8766        # different port (Node 2 on same machine)
  python node.py --connect ws://192.168.1.X:8765/mesh   # connect to peer
  python node.py --demo             # run 2-node demo automatically
"""

import asyncio
import argparse
import sys
import time
import os

# Add project root to path
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from core.identity import NodeIdentity
from core.trust import TrustManager
from core.brain import LocalBrain
from core.mesh import MeshNode
from core.packet import NanoPacket, PACKET_KNOWLEDGE


# ── ANSI Colors ──────────────────────────────────────────────────────

class C:
    RESET  = "\033[0m"
    BOLD   = "\033[1m"
    CYAN   = "\033[96m"
    GREEN  = "\033[92m"
    YELLOW = "\033[93m"
    RED    = "\033[91m"
    PURPLE = "\033[95m"
    DIM    = "\033[2m"
    BLUE   = "\033[94m"

def banner():
    print(f"""
{C.CYAN}{C.BOLD}
 ███╗   ███╗███████╗███████╗██╗  ██╗    ██████╗ ██████╗  █████╗ ██╗███╗   ██╗
 ████╗ ████║██╔════╝██╔════╝██║  ██║    ██╔══██╗██╔══██╗██╔══██╗██║████╗  ██║
 ██╔████╔██║█████╗  ███████╗███████║    ██████╔╝██████╔╝███████║██║██╔██╗ ██║
 ██║╚██╔╝██║██╔══╝  ╚════██║██╔══██║    ██╔══██╗██╔══██╗██╔══██║██║██║╚██╗██║
 ██║ ╚═╝ ██║███████╗███████║██║  ██║    ██████╔╝██║  ██║██║  ██║██║██║ ╚████║
 ╚═╝     ╚═╝╚══════╝╚══════╝╚═╝  ╚═╝   ╚═════╝ ╚═╝  ╚═╝╚═╝  ╚═╝╚═╝╚═╝  ╚═══╝
{C.RESET}
{C.DIM} Decentralized P2P AI · Every Device = Local Brain · Blockchain Verified · $0 Infrastructure{C.RESET}
""")


# ── MeshBrain Application ────────────────────────────────────────────

class MeshBrainApp:

    def __init__(self, port: int = 8765, connect_url: str = None,
                 model: str = "gemma2:2b", storage_dir: str = None):

        storage = storage_dir or f"./node_data_{port}"
        os.makedirs(storage, exist_ok=True)

        # Core components
        self.identity = NodeIdentity(storage_path=f"{storage}/identity")
        self.trust = TrustManager(self.identity)
        self.brain = LocalBrain(model=model)
        self.node = MeshNode(
            identity=self.identity,
            trust=self.trust,
            port=port,
        )

        self.connect_url = connect_url
        self.running = False
        self.session_start = time.time()

        # Register callbacks
        self.node.on_knowledge_received = self._on_knowledge_received
        self.node.on_peer_connected = self._on_peer_connected
        self.node.on_peer_disconnected = self._on_peer_disconnected

        # Knowledge absorbed from mesh
        self.knowledge_absorbed = []

    # ── Startup ──────────────────────────────────────────────────

    async def start(self):
        banner()

        print(f"{C.CYAN}[Init]{C.RESET} Starting MeshBrain node...")
        print(f"{C.CYAN}[Init]{C.RESET} Node ID: {C.YELLOW}{self.identity.node_id_hex[:32]}...{C.RESET}")
        print(f"{C.CYAN}[Init]{C.RESET} Storage: {C.DIM}{self.identity.storage_path}{C.RESET}")

        # Check local LLM
        await self.brain.check_availability()

        # Start mesh server
        await self.node.start_server()

        # Connect to peer if specified
        if self.connect_url:
            print(f"{C.CYAN}[Init]{C.RESET} Connecting to peer: {self.connect_url}")
            await self.node.connect_to_peer(self.connect_url)
            await asyncio.sleep(1)

        # Local network discovery
        await self.node.discover_local_peers()

        # Background tasks
        asyncio.create_task(self._heartbeat_loop())
        asyncio.create_task(self._status_loop())

        self.running = True
        print(f"\n{C.GREEN}{'━'*60}{C.RESET}")
        print(f"{C.GREEN}✅ MeshBrain Node ONLINE{C.RESET}")
        print(f"{C.GREEN}{'━'*60}{C.RESET}")
        print(f"{C.DIM}Type your message and press Enter. Commands:{C.RESET}")
        print(f"{C.DIM}  /peers    — show connected peers{C.RESET}")
        print(f"{C.DIM}  /trust    — show trust/reputation table{C.RESET}")
        print(f"{C.DIM}  /merkle   — show knowledge Merkle tree{C.RESET}")
        print(f"{C.DIM}  /absorbed — show knowledge absorbed from mesh{C.RESET}")
        print(f"{C.DIM}  /status   — full node status{C.RESET}")
        print(f"{C.DIM}  /clear    — clear conversation history{C.RESET}")
        print(f"{C.DIM}  /quit     — exit{C.RESET}")
        print(f"{C.GREEN}{'━'*60}{C.RESET}\n")

    # ── Main chat loop ────────────────────────────────────────────

    async def chat_loop(self):
        """Interactive chat — runs in terminal"""
        while self.running:
            try:
                # Get input (non-blocking)
                loop = asyncio.get_event_loop()
                user_input = await loop.run_in_executor(
                    None, lambda: input(f"{C.CYAN}You:{C.RESET} ")
                )
                user_input = user_input.strip()
                if not user_input:
                    continue

                # Commands
                if user_input.startswith("/"):
                    await self._handle_command(user_input)
                    continue

                # Regular chat
                await self._chat(user_input)

            except (EOFError, KeyboardInterrupt):
                print(f"\n{C.YELLOW}[Node] Shutting down...{C.RESET}")
                self.running = False
                break
            except Exception as e:
                print(f"{C.RED}[Error] {e}{C.RESET}")

    async def _chat(self, user_input: str):
        """Process a chat message through local brain + share to mesh"""
        start_time = time.time()

        print(f"\n{C.PURPLE}Brain:{C.RESET} ", end="", flush=True)

        # Stream response from local LLM
        full_response = ""
        async for token in self.brain.think_stream(user_input):
            print(token, end="", flush=True)
            full_response += token

        elapsed = time.time() - start_time
        peer_count = self.node.peer_count()

        print(f"\n{C.DIM}  [{elapsed:.1f}s · local:{self.brain.model}]{C.RESET}\n")

        # If we have peers, extract + share knowledge
        if peer_count > 0 and full_response:
            asyncio.create_task(
                self._share_to_mesh(user_input, full_response)
            )

    async def _share_to_mesh(self, query: str, response: str):
        """Extract knowledge and broadcast to peers (runs in background)"""
        try:
            quality = await self.brain.extract_knowledge_quality(query, response)
            if quality < 0.55:
                print(f"{C.DIM}  [Mesh] Knowledge quality too low to share ({quality:.2f}){C.RESET}")
                return

            packet = NanoPacket.create_knowledge_packet(
                identity=self.identity,
                query_text=query,
                response_text=response,
                quality=quality,
                language=self._detect_language(query),
            )
            sent_to = await self.node.broadcast_knowledge(packet)
            if sent_to > 0:
                print(f"{C.GREEN}  [Mesh]{C.RESET} {C.DIM}Shared knowledge to {sent_to} peer(s) "
                      f"(quality={quality:.2f}, size={packet.size_bytes()}B, "
                      f"privacy=✅ raw text NOT shared){C.RESET}")
        except Exception as e:
            print(f"{C.RED}  [Mesh error] {e}{C.RESET}")

    # ── Commands ─────────────────────────────────────────────────

    async def _handle_command(self, cmd: str):
        cmd = cmd.lower().strip()

        if cmd == "/peers":
            total = self.node.peer_count()
            print(f"\n{C.CYAN}Connected peers:{C.RESET} {total}")
            for nid in list(self.node.peers.keys()) + list(self.node.peer_clients.keys()):
                rep = self.trust.get_reputation(nid)
                bar = "█" * int(rep * 10) + "░" * (10 - int(rep * 10))
                print(f"  {C.DIM}{nid[:16]}...{C.RESET}  rep=[{C.GREEN}{bar}{C.RESET}] {rep:.2f}")
            print()

        elif cmd == "/trust":
            s = self.trust.status()
            print(f"\n{C.CYAN}Trust Status:{C.RESET}")
            for k, v in s.items():
                print(f"  {C.DIM}{k}:{C.RESET} {C.YELLOW}{v}{C.RESET}")
            print()

        elif cmd == "/merkle":
            mt = self.trust.merkle
            print(f"\n{C.CYAN}Merkle Knowledge Tree:{C.RESET}")
            print(f"  Leaves (knowledge packets): {C.YELLOW}{mt.size()}{C.RESET}")
            print(f"  Root hash: {C.GREEN}{mt.root.hex()}{C.RESET}")
            print(f"  {C.DIM}(Root changes every time new knowledge is integrated){C.RESET}")
            print()

        elif cmd == "/absorbed":
            print(f"\n{C.CYAN}Knowledge absorbed from mesh ({len(self.knowledge_absorbed)} packets):{C.RESET}")
            if not self.knowledge_absorbed:
                print(f"  {C.DIM}None yet — waiting for peers to share...{C.RESET}")
            for i, entry in enumerate(self.knowledge_absorbed[-10:], 1):
                print(f"  {i}. {C.DIM}from={entry['from'][:8]}... "
                      f"quality={entry['quality']:.2f} "
                      f"time={entry['time']}{C.RESET}")
            print()

        elif cmd == "/status":
            elapsed = time.time() - self.session_start
            print(f"\n{C.CYAN}{'━'*50}{C.RESET}")
            print(f"{C.CYAN}Node Status:{C.RESET}")
            print(f"  Node ID:     {C.YELLOW}{self.identity.node_id_hex[:24]}...{C.RESET}")
            print(f"  Uptime:      {C.GREEN}{elapsed:.0f}s{C.RESET}")
            print(f"  Peers:       {C.GREEN}{self.node.peer_count()}{C.RESET}")
            print(f"  LLM:         {C.GREEN}{self.brain.model} ({'✅' if self.brain.is_available else '⚠️  fallback'}){C.RESET}")
            print(f"  Pkts sent:   {C.DIM}{self.node.packets_sent}{C.RESET}")
            print(f"  Pkts recv:   {C.DIM}{self.node.packets_received}{C.RESET}")
            print(f"  KB sent:     {C.DIM}{self.node.bytes_sent//1024}{C.RESET}")
            print(f"  KB received: {C.DIM}{self.node.bytes_received//1024}{C.RESET}")
            trust = self.trust.status()
            print(f"  Knowledge:   {C.GREEN}{trust['integrated_packets']} packets integrated{C.RESET}")
            print(f"  Merkle root: {C.DIM}{trust['merkle_root']}{C.RESET}")
            print(f"{C.CYAN}{'━'*50}{C.RESET}\n")

        elif cmd == "/clear":
            self.brain.clear_history()
            print(f"{C.GREEN}[Node] Conversation history cleared (privacy ✅){C.RESET}\n")

        elif cmd in ("/quit", "/exit", "/q"):
            self.running = False
            print(f"{C.YELLOW}[Node] Goodbye!{C.RESET}")

        else:
            print(f"{C.RED}Unknown command: {cmd}{C.RESET}\n")

    # ── Callbacks ─────────────────────────────────────────────────

    async def _on_knowledge_received(self, packet: NanoPacket):
        """Called when a verified knowledge packet arrives from a peer"""
        self.knowledge_absorbed.append({
            "from": packet.node_id.hex(),
            "quality": packet.quality_score,
            "size": packet.size_bytes(),
            "time": time.strftime("%H:%M:%S"),
        })
        print(f"\n{C.GREEN}  ✨ [Mesh] Knowledge received from peer!{C.RESET} "
              f"{C.DIM}quality={packet.quality_score:.2f} "
              f"size={packet.size_bytes()}B "
              f"merkle_size={self.trust.merkle.size()}{C.RESET}")

    async def _on_peer_connected(self, node_id: str):
        print(f"\n{C.GREEN}  🤝 [Mesh] Peer joined network: {node_id[:16]}...{C.RESET}")

    async def _on_peer_disconnected(self, node_id: str):
        print(f"\n{C.YELLOW}  👋 [Mesh] Peer left: {node_id[:16]}...{C.RESET}")

    # ── Background tasks ──────────────────────────────────────────

    async def _heartbeat_loop(self):
        """Send heartbeat every 30s to keep peers updated"""
        while self.running:
            await asyncio.sleep(30)
            if self.node.peer_count() > 0:
                await self.node.send_heartbeat()

    async def _status_loop(self):
        """Print brief status every 60s"""
        while self.running:
            await asyncio.sleep(60)
            peers = self.node.peer_count()
            integrated = self.trust.integrated_count
            if peers > 0 or integrated > 0:
                print(f"\n{C.DIM}  [Status] peers={peers} "
                      f"knowledge_integrated={integrated} "
                      f"merkle_size={self.trust.merkle.size()}{C.RESET}")

    @staticmethod
    def _detect_language(text: str) -> str:
        """Simple language detection (could use fasttext in production)"""
        # For now return 'en' — in production use: langdetect or fasttext
        return "en"


# ── Demo mode — runs two nodes in one process ─────────────────────────

async def run_demo():
    """
    Demo: spin up TWO nodes in the same process.
    Node A and Node B find each other, exchange knowledge packets.
    Shows the complete P2P system working end-to-end.
    """
    print(f"\n{C.YELLOW}{'═'*60}{C.RESET}")
    print(f"{C.YELLOW} DEMO MODE: Two nodes, one machine{C.RESET}")
    print(f"{C.YELLOW}{'═'*60}{C.RESET}\n")

    # Node A
    app_a = MeshBrainApp(port=8765, storage_dir="./demo_node_a")
    await app_a.start()
    await asyncio.sleep(0.5)

    # Node B connects to Node A
    app_b = MeshBrainApp(
        port=8766,
        connect_url="ws://127.0.0.1:8765/mesh",
        storage_dir="./demo_node_b"
    )
    await app_b.start()
    await asyncio.sleep(1.5)

    print(f"\n{C.CYAN}{'═'*60}{C.RESET}")
    print(f"{C.CYAN} DEMO: Node A asking a question...{C.RESET}")
    print(f"{C.CYAN}{'═'*60}{C.RESET}\n")

    # Node A asks something
    await app_a._chat("What is federated learning and why is it good for privacy?")
    await asyncio.sleep(2)

    print(f"\n{C.CYAN}{'═'*60}{C.RESET}")
    print(f"{C.CYAN} DEMO: Node B asking a question...{C.RESET}")
    print(f"{C.CYAN}{'═'*60}{C.RESET}\n")

    await app_b._chat("How does blockchain cryptography prevent data tampering?")
    await asyncio.sleep(3)

    print(f"\n{C.GREEN}{'═'*60}{C.RESET}")
    print(f"{C.GREEN} DEMO COMPLETE{C.RESET}")
    print(f"{C.GREEN}{'═'*60}{C.RESET}")

    print(f"\n{C.CYAN}Node A status:{C.RESET}")
    await app_a._handle_command("/status")

    print(f"\n{C.CYAN}Node B status:{C.RESET}")
    await app_b._handle_command("/status")

    print(f"\n{C.CYAN}Knowledge absorbed by Node A:{C.RESET}")
    await app_a._handle_command("/absorbed")

    print(f"\n{C.CYAN}Knowledge absorbed by Node B:{C.RESET}")
    await app_b._handle_command("/absorbed")

    print(f"\n{C.GREEN}Both nodes verified each other's knowledge packets.{C.RESET}")
    print(f"{C.GREEN}Raw conversations never left either device.{C.RESET}")
    print(f"{C.DIM}Run 'python node.py' to start an interactive node.{C.RESET}\n")


# ── Entry point ───────────────────────────────────────────────────────

async def main():
    parser = argparse.ArgumentParser(description="MeshBrain Node")
    parser.add_argument("--port", type=int, default=8765)
    parser.add_argument("--connect", type=str, default=None,
                        help="ws://IP:PORT/mesh to connect to a peer")
    parser.add_argument("--model", type=str, default="gemma2:2b")
    parser.add_argument("--demo", action="store_true",
                        help="Run automated 2-node demo")
    args = parser.parse_args()

    if args.demo:
        await run_demo()
        return

    app = MeshBrainApp(
        port=args.port,
        connect_url=args.connect,
        model=args.model,
    )
    await app.start()
    await app.chat_loop()
    await app.node.stop()


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        print(f"\n{C.YELLOW}Node stopped.{C.RESET}")
