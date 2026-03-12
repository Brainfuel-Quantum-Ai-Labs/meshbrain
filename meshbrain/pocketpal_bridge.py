#!/usr/bin/env python3
"""
MeshBrain — PocketPal Bridge
============================
This connects YOUR PocketPal AI (already running on your phone)
to the MeshBrain mesh network.

How it works:
  1. PocketPal runs Gemma locally on your phone
  2. PocketPal exposes a local API on your phone at port 8080
  3. This bridge script runs on your PC
  4. It connects your phone's Gemma to the mesh
  5. Knowledge flows between all nodes

Run this on your PC:
  python pocketpal_bridge.py

Then on another device run:
  python node.py --connect ws://YOUR_PC_IP:8765/mesh
"""

import asyncio
import aiohttp
import json
import sys
import os

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from core.identity import NodeIdentity
from core.trust import TrustManager
from core.mesh import MeshNode
from core.packet import NanoPacket


class C:
    RESET  = "\033[0m"
    BOLD   = "\033[1m"
    CYAN   = "\033[96m"
    GREEN  = "\033[92m"
    YELLOW = "\033[93m"
    RED    = "\033[91m"
    DIM    = "\033[2m"


async def find_pocketpal() -> str | None:
    """
    Scan your local network for PocketPal's API.
    PocketPal runs an OpenAI-compatible API on port 8080.
    """
    print(f"{C.CYAN}[Bridge]{C.RESET} Scanning for PocketPal on your network...")

    # Common ports PocketPal uses
    ports = [8080, 8081, 11434, 1234]

    # Get local IP range
    import socket
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80))
        local_ip = s.getsockname()[0]
        s.close()
        base = ".".join(local_ip.split(".")[:3])  # e.g. 192.168.1
    except Exception:
        base = "192.168.1"

    # Build candidate URLs
    candidates = []
    for port in ports:
        candidates.append(f"http://localhost:{port}")
        candidates.append(f"http://127.0.0.1:{port}")
        # Scan .1 to .20 on local subnet
        for i in [1,2,3,4,5,100,101,102,103,104,105,106,107,108,200]:
            candidates.append(f"http://{base}.{i}:{port}")

    timeout = aiohttp.ClientTimeout(total=1.5)
    async with aiohttp.ClientSession(timeout=timeout) as session:
        for url in candidates:
            try:
                # Try OpenAI-compatible /v1/models endpoint
                async with session.get(f"{url}/v1/models") as resp:
                    if resp.status == 200:
                        data = await resp.json()
                        models = data.get("data", [])
                        if models:
                            print(f"{C.GREEN}[Bridge]{C.RESET} ✅ Found PocketPal at: {url}")
                            print(f"{C.DIM}         Models: {[m.get('id','?') for m in models]}{C.RESET}")
                            return url
            except Exception:
                pass
            try:
                # Try Ollama-style /api/tags
                async with session.get(f"{url}/api/tags") as resp:
                    if resp.status == 200:
                        print(f"{C.GREEN}[Bridge]{C.RESET} ✅ Found Ollama at: {url}")
                        return url
            except Exception:
                pass

    return None


async def chat_with_pocketpal(base_url: str, message: str, history: list) -> str:
    """Send a message to PocketPal's local API and get response."""
    messages = history + [{"role": "user", "content": message}]

    # Try OpenAI-compatible endpoint first (PocketPal uses this)
    payload = {
        "model": "default",  # PocketPal uses whatever model is loaded
        "messages": messages,
        "stream": False,
        "temperature": 0.7,
        "max_tokens": 512
    }

    timeout = aiohttp.ClientTimeout(total=60)
    async with aiohttp.ClientSession(timeout=timeout) as session:
        # OpenAI-compatible
        try:
            async with session.post(
                f"{base_url}/v1/chat/completions",
                json=payload
            ) as resp:
                if resp.status == 200:
                    data = await resp.json()
                    return data["choices"][0]["message"]["content"]
        except Exception:
            pass

        # Ollama-compatible fallback
        try:
            ollama_payload = {
                "model": "gemma2:2b",
                "messages": messages,
                "stream": False
            }
            async with session.post(
                f"{base_url}/api/chat",
                json=ollama_payload
            ) as resp:
                if resp.status == 200:
                    data = await resp.json()
                    return data["message"]["content"]
        except Exception:
            pass

    return "[Could not reach PocketPal — check it's running and API is enabled]"


async def main():
    print(f"""
{C.CYAN}{C.BOLD}
╔══════════════════════════════════════════════════════════╗
║         MESHBRAIN — POCKETPAL BRIDGE                    ║
║   Your Phone's Gemma → Mesh Network                     ║
╚══════════════════════════════════════════════════════════╝
{C.RESET}""")

    # Step 1: Find PocketPal
    pocketpal_url = await find_pocketpal()

    if not pocketpal_url:
        print(f"""
{C.YELLOW}[Bridge]{C.RESET} PocketPal not found automatically.

{C.BOLD}To fix this:{C.RESET}

  In PocketPal app on your phone:
  1. Open PocketPal
  2. Tap ⚙️  Settings (gear icon)
  3. Look for "Server" or "API" settings
  4. Enable the local API server
  5. Note the port number shown (usually 8080)

  Then run this script again, or enter the URL manually:
""")
        url_input = input(f"{C.CYAN}  Enter PocketPal URL (e.g. http://192.168.1.5:8080): {C.RESET}").strip()
        if url_input:
            pocketpal_url = url_input
        else:
            print(f"{C.RED}  No URL provided. Running in standalone mesh mode.{C.RESET}")
            pocketpal_url = None

    # Step 2: Start mesh node
    identity = NodeIdentity(storage_path="./bridge_identity")
    trust = TrustManager(identity)
    node = MeshNode(identity=identity, trust=trust, port=8765)

    await node.start_server()
    await node.discover_local_peers()

    print(f"""
{C.GREEN}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━{C.RESET}
{C.GREEN}✅ MESH NODE ONLINE{C.RESET}
{C.GREEN}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━{C.RESET}

{C.CYAN}Node ID:{C.RESET}     {identity.node_id_short()}
{C.CYAN}Mesh port:{C.RESET}   ws://YOUR_IP:8765/mesh
{C.CYAN}PocketPal:{C.RESET}   {pocketpal_url or 'Not connected'}

{C.DIM}Commands: type message | /peers | /trust | /mesh | /quit{C.RESET}
""")

    history = []
    running = True

    while running:
        try:
            loop = asyncio.get_event_loop()
            user_input = await loop.run_in_executor(
                None, lambda: input(f"{C.CYAN}You:{C.RESET} ")
            )
            user_input = user_input.strip()
            if not user_input:
                continue

            # Commands
            if user_input == "/peers":
                peers = node.peer_count()
                print(f"{C.CYAN}Connected peers: {peers}{C.RESET}")
                for nid in list(node.peers.keys()):
                    rep = trust.get_reputation(nid)
                    print(f"  {nid[:16]}... rep={rep:.2f}")
                continue

            if user_input == "/trust":
                s = trust.status()
                for k, v in s.items():
                    print(f"  {C.DIM}{k}:{C.RESET} {C.YELLOW}{v}{C.RESET}")
                continue

            if user_input == "/mesh":
                print(f"{C.CYAN}Merkle root:{C.RESET} {trust.merkle.root.hex()}")
                print(f"{C.CYAN}Knowledge packets:{C.RESET} {trust.integrated_count}")
                print(f"{C.CYAN}Peers:{C.RESET} {node.peer_count()}")
                continue

            if user_input in ("/quit", "/q"):
                running = False
                break

            # Chat with PocketPal (or mock)
            print(f"{C.DIM}  [thinking...]{C.RESET}", end="\r")

            if pocketpal_url:
                response = await chat_with_pocketpal(pocketpal_url, user_input, history)
            else:
                response = f"[Standalone mode] Received: '{user_input}' — Connect PocketPal to get real AI responses"

            print(f"{C.RESET}{C.BOLD}Gemma:{C.RESET} {response}\n")

            # Update history
            history.append({"role": "user", "content": user_input})
            history.append({"role": "assistant", "content": response})

            # Share to mesh if peers connected
            if node.peer_count() > 0:
                # Quality heuristic
                quality = min(1.0, len(response.split()) / 50)
                quality = max(0.5, quality)

                packet = NanoPacket.create_knowledge_packet(
                    identity=identity,
                    query_text=user_input,
                    response_text=response,
                    quality=quality
                )
                sent = await node.broadcast_knowledge(packet)
                if sent > 0:
                    print(f"{C.GREEN}  [Mesh]{C.RESET} {C.DIM}Knowledge shared to {sent} peer(s) "
                          f"({packet.size_bytes()}B · privacy ✅){C.RESET}\n")

        except (KeyboardInterrupt, EOFError):
            running = False

    print(f"\n{C.YELLOW}Bridge stopped.{C.RESET}")
    await node.stop()


if __name__ == "__main__":
    asyncio.run(main())
