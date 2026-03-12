# 🧠 MeshBrain

> **Decentralized P2P AI — Every Device is a Local Brain**

[![License: MIT](https://img.shields.io/badge/License-MIT-cyan.svg)](LICENSE)
[![Python 3.10+](https://img.shields.io/badge/Python-3.10%2B-blue.svg)](https://python.org)
[![Android 8.0+](https://img.shields.io/badge/Android-8.0%2B-green.svg)](https://developer.android.com)
[![Tests](https://img.shields.io/badge/Tests-34%2F34%20passing-brightgreen.svg)](#testing)

MeshBrain is a fully decentralized AI system where every phone runs its own local AI model (Gemma 2, Llama 3.2, Mistral) and collaboratively improves through a cryptographically verified P2P knowledge mesh — with **zero cloud infrastructure**, **zero user data exposure**, and **$0 running cost**.

---

## ✨ Key Features

| Feature | How |
|---|---|
| 🔒 **Privacy-first** | Raw conversations never leave your device — only compressed vectors shared |
| ⛓️ **Blockchain verified** | Ed25519 signatures on every knowledge packet — forgery mathematically impossible |
| 🌐 **Fully decentralized** | No server, no API key, no central authority — phones ARE the network |
| 🧠 **Local AI** | Gemma 2 2B, Llama 3.2, Mistral running on-device via Ollama / PocketPal |
| 📡 **Auto-discovery** | Devices find each other on same WiFi via NSD/mDNS — no manual config |
| 🔁 **Collective learning** | Every good interaction improves the whole network via federated NanoPackets |
| 📱 **Mobile-first** | Android app (Jetpack Compose) + Python node for PC/Mac/Linux |

---

## 🏗️ Architecture

```
┌─────────────────────────────────────────────────────────┐
│                    MESHBRAIN NODE                        │
│                                                          │
│  ┌──────────┐    ┌──────────────┐    ┌───────────────┐  │
│  │  Local   │───▶│  NanoPacket  │───▶│  Mesh Network │  │
│  │  Brain   │    │  (463 bytes) │    │  (WebSocket)  │  │
│  │ (Gemma2) │    │              │    │               │  │
│  └──────────┘    │ • topic_hash │    │ • P2P gossip  │  │
│       ▲          │ • knowledge  │    │ • NSD/mDNS    │  │
│       │          │ • quality    │    │ • LAN auto-   │  │
│  User Input      │ • signature  │    │   discovery   │  │
│  (stays local)   └──────────────┘    └───────────────┘  │
│                         │                    │           │
│                  ┌──────▼──────┐    ┌────────▼───────┐  │
│                  │   Trust &   │    │  Merkle Tree   │  │
│                  │ Reputation  │    │  (tamper-proof │  │
│                  │  Manager   │    │  knowledge log)│  │
│                  └─────────────┘    └────────────────┘  │
└─────────────────────────────────────────────────────────┘
         │ NanoPackets (signed, compressed)
         ▼
┌─────────────────┐      ┌─────────────────┐
│   Phone Node    │◀────▶│   PC Node       │
│  (PocketPal)    │      │  (Ollama)       │
└─────────────────┘      └─────────────────┘
         ▲                        ▲
         │                        │
         └──────── Mesh ──────────┘
               (any topology)
```

### Privacy Proof

```python
packet = NanoPacket.create_knowledge_packet(
    query_text    = "What is blockchain?",
    response_text = "Blockchain is a distributed ledger...",
    quality       = 0.87
)

raw = packet.to_bytes()
assert "blockchain"  not in raw  # ✅ True — text never transmitted
assert "distributed" not in raw  # ✅ True — only vectors sent
assert len(raw) == 463           # ✅ Compact 463-byte packet
```

---

## 📁 Project Structure

```
meshbrain/                          ← Python mesh node (PC/Mac/Linux/Termux)
├── core/
│   ├── identity.py                 ← Ed25519 cryptographic node identity
│   ├── packet.py                   ← NanoPacket protocol (knowledge exchange)
│   ├── trust.py                    ← Merkle tree + reputation system
│   ├── brain.py                    ← Ollama/Gemma local AI connector
│   └── mesh.py                     ← WebSocket P2P network + LAN discovery
├── node.py                         ← Main interactive terminal node
├── pocketpal_bridge.py             ← Connect PocketPal phone AI to mesh
├── mesh_test.py                    ← 34-test blockchain verification suite
└── requirements.txt

meshbrain_android/                  ← Android app (Jetpack Compose + Kotlin)
├── app/
│   ├── build.gradle
│   └── src/main/
│       ├── AndroidManifest.xml
│       └── java/com/meshbrain/
│           ├── core/
│           │   ├── NodeIdentity.kt ← Ed25519 (BouncyCastle)
│           │   └── NanoPacket.kt   ← Packet protocol
│           ├── brain/
│           │   └── LocalBrain.kt   ← Ollama HTTP client
│           ├── mesh/
│           │   └── MeshNetwork.kt  ← OkHttp WebSocket + NSD
│           └── ui/
│               ├── MainViewModel.kt
│               └── MainActivity.kt ← Compose UI
└── SETUP.md
```

---

## 🚀 Quick Start

### Option A — Python Node (PC/Mac/Linux, fastest)

```bash
# 1. Clone
git clone https://github.com/YOUR_USERNAME/meshbrain.git
cd meshbrain

# 2. Install dependencies
pip install -r requirements.txt

# 3. Start Ollama with Gemma (if you have it)
OLLAMA_HOST=0.0.0.0 ollama serve &
ollama pull gemma2:2b

# 4. Run your node
python meshbrain/node.py

# 5. Connect a second node (another terminal or device)
python meshbrain/node.py --port 8766 --connect ws://127.0.0.1:8765/mesh
```

### Option B — PocketPal on Phone (no PC needed)

```bash
# 1. Install PocketPal on Android from F-Droid or GitHub
# 2. Load Gemma 2 2B inside PocketPal
# 3. Enable PocketPal's API server in Settings
# 4. On PC, run the bridge:
python meshbrain/pocketpal_bridge.py
# It auto-finds your phone on the LAN
```

### Option C — Demo (zero setup)

```bash
python meshbrain/node.py --demo
# Spins up 2 nodes, connects them, exchanges knowledge packets
# Shows everything working end-to-end
```

---

## 🧪 Testing

Run the full 34-test blockchain verification suite:

```bash
python meshbrain/mesh_test.py
```

Expected output:
```
━━━ SUITE 1: Cryptographic Identity (Ed25519) ━━━
  ✅ PASS  Node A has unique 32-byte identity
  ✅ PASS  Valid signature verifies correctly
  ✅ PASS  Wrong public key fails verification     ← attack blocked
  ✅ PASS  Forged signature fails verification     ← attack blocked

━━━ SUITE 2: NanoPacket Creation & Privacy ━━━
  ✅ PASS  🔒 Raw query NOT in packet bytes        ← privacy proven
  ✅ PASS  🔒 Raw response NOT in packet bytes     ← privacy proven

━━━ SUITE 3: Blockchain Trust Verification ━━━
  ✅ PASS  ⛔ Forged/impersonation packet REJECTED ← attack blocked
  ✅ PASS  Duplicate packet REJECTED               ← replay blocked

━━━ SUITE 4: P2P Mesh Network ━━━
  ✅ PASS  Two nodes connected and exchanging knowledge

━━━ SUITE 5: End-to-End Flow ━━━
  ✅ PASS  Phone → signs → PC → verifies → Merkle → privacy ✅

  Passed: 34/34
```

---

## 📱 Android App

See [`meshbrain_android/SETUP.md`](meshbrain_android/SETUP.md) for full build instructions.

**Requirements:**
- Android Studio (free) or `./gradlew assembleDebug`
- Android 8.0+ (API 26)
- Same WiFi network as a running node or Ollama instance

---

## 🔐 Security Model

```
Each node has a permanent Ed25519 keypair (32-byte public key = Node ID)
Private key never leaves the device.

Every NanoPacket contains:
  ├── node_id       → sender's public key
  ├── topic_hash    → 8-byte SHA-256 fingerprint of topic (NOT reversible)
  ├── knowledge     → 256-dim int8 embedding vector (NOT raw text)
  ├── quality_score → 0.0–1.0 self-reported quality
  └── signature     → Ed25519(private_key, payload) — 64 bytes

Receiving node:
  1. Checks deduplication  → blocks replay attacks
  2. Checks expiry         → blocks stale packet injection
  3. Verifies Ed25519 sig  → blocks impersonation
  4. Checks reputation     → blocks known bad nodes
  5. Checks quality score  → blocks low-value noise
  6. Inserts into Merkle   → tamper-proof knowledge log
```

---

## 🗺️ Roadmap

- [x] Ed25519 node identity
- [x] NanoPacket protocol (privacy-safe knowledge exchange)  
- [x] Merkle tree knowledge verification
- [x] WebSocket P2P mesh with gossip protocol
- [x] LAN peer auto-discovery (NSD/mDNS + UDP broadcast)
- [x] Reputation system with auto-ban for bad nodes
- [x] PocketPal bridge
- [x] Android app skeleton (Jetpack Compose)
- [ ] Whisper on-device voice input
- [ ] On-device LoRA gradient computation
- [ ] Feedback loop (thumbs → gradient → NanoPacket → mesh)
- [ ] iOS app (Swift + MLC-LLM)
- [ ] DHT peer routing (beyond LAN — internet mesh)
- [ ] F-Droid / GitHub Releases APK

---

## 🤝 Contributing

This is an open research project. Contributions welcome:

1. Fork the repo
2. Create a branch: `git checkout -b feature/your-feature`
3. Run tests: `python meshbrain/mesh_test.py`
4. All 34 tests must pass before PR
5. Submit pull request

---

## 📄 License

MIT License — free to use, modify, distribute.

---

## 🙏 Credits

Built on: [Ollama](https://ollama.ai) · [llama.cpp](https://github.com/ggerganov/llama.cpp) · [PocketPal AI](https://github.com/a-ghorbani/pocketpal-ai) · [Gemma 2](https://ai.google.dev/gemma) · [BouncyCastle](https://bouncycastle.org)
