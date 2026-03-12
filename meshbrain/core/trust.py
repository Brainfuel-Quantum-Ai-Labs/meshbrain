"""
MeshBrain — Trust & Reputation System
Tracks node reputation, verifies packet integrity,
maintains a Merkle tree of all integrated knowledge.
No central authority — math enforces trust.
"""

import hashlib
import time
import json
from collections import defaultdict
from typing import Dict, List, Optional, Tuple
from dataclasses import dataclass, field

from core.packet import NanoPacket


# ── Merkle Tree ───────────────────────────────────────────────────────

class MerkleTree:
    """
    Compact proof of all knowledge this node has integrated.
    Peers can verify our knowledge state without seeing the actual knowledge.
    """

    def __init__(self):
        self.leaves: List[bytes] = []
        self._root: Optional[bytes] = None
        self._dirty = False

    def insert(self, data: bytes):
        leaf = hashlib.sha256(data).digest()
        self.leaves.append(leaf)
        self._dirty = True

    @property
    def root(self) -> bytes:
        if self._dirty or self._root is None:
            self._root = self._compute_root()
            self._dirty = False
        return self._root

    def _compute_root(self) -> bytes:
        if not self.leaves:
            return b'\x00' * 32
        nodes = list(self.leaves)
        while len(nodes) > 1:
            if len(nodes) % 2 != 0:
                nodes.append(nodes[-1])  # duplicate last if odd
            nodes = [
                hashlib.sha256(nodes[i] + nodes[i+1]).digest()
                for i in range(0, len(nodes), 2)
            ]
        return nodes[0]

    def proof(self, index: int) -> List[Tuple[bytes, str]]:
        """Generate Merkle proof for a leaf (for verification by peers)"""
        if index >= len(self.leaves):
            return []
        nodes = list(self.leaves)
        proof = []
        while len(nodes) > 1:
            if len(nodes) % 2 != 0:
                nodes.append(nodes[-1])
            sibling_idx = index ^ 1  # flip last bit
            side = "right" if index % 2 == 0 else "left"
            proof.append((nodes[sibling_idx], side))
            index //= 2
            nodes = [
                hashlib.sha256(nodes[i] + nodes[i+1]).digest()
                for i in range(0, len(nodes), 2)
            ]
        return proof

    def size(self) -> int:
        return len(self.leaves)

    def __repr__(self):
        return f"<MerkleTree leaves={len(self.leaves)} root={self.root.hex()[:12]}...>"


# ── Node Reputation ────────────────────────────────────────────────────

@dataclass
class NodeRecord:
    node_id: str
    reputation: float = 0.5           # starts neutral
    packets_received: int = 0
    packets_accepted: int = 0
    packets_rejected: int = 0
    first_seen: float = field(default_factory=time.time)
    last_seen: float = field(default_factory=time.time)
    is_banned: bool = False

    def acceptance_rate(self) -> float:
        if self.packets_received == 0:
            return 0.0
        return self.packets_accepted / self.packets_received

    def age_hours(self) -> float:
        return (time.time() - self.first_seen) / 3600


# ── Trust Manager ─────────────────────────────────────────────────────

class TrustManager:
    """
    Manages reputation of all known nodes.
    Verifies incoming packets.
    Maintains Merkle tree of integrated knowledge.
    """

    # Thresholds
    MIN_REPUTATION_TO_ACCEPT = 0.35    # below this = quarantine
    MIN_QUALITY_TO_INTEGRATE = 0.60    # below this = ignore
    MAX_HOP_COUNT = 5                  # don't relay stale packets
    BAN_THRESHOLD = 0.10               # auto-ban very bad nodes

    def __init__(self, identity):
        self.identity = identity
        self.nodes: Dict[str, NodeRecord] = {}
        self.merkle = MerkleTree()
        self.integrated_count = 0
        self.rejected_count = 0
        self._seen_packets = set()     # dedup by packet hash

    # ── Packet Verification Pipeline ──────────────────────────────

    def verify_and_score(self, packet: NanoPacket, identity) -> Tuple[bool, str]:
        """
        Full verification pipeline for an incoming packet.
        Returns (accept: bool, reason: str)
        """
        node_id_hex = packet.node_id.hex()

        # 0. Deduplication
        packet_hash = hashlib.sha256(packet.knowledge + packet.node_id).hexdigest()
        if packet_hash in self._seen_packets:
            return False, "duplicate"
        self._seen_packets.add(packet_hash)
        # Keep set from growing forever
        if len(self._seen_packets) > 10000:
            self._seen_packets = set(list(self._seen_packets)[-5000:])

        # 1. Don't process our own packets
        if packet.node_id == identity.public_key_bytes():
            return False, "self-packet"

        # 2. Hop count limit
        if packet.hop_count > self.MAX_HOP_COUNT:
            return False, "too-many-hops"

        # 3. Expiry check
        if packet.is_expired(max_age_seconds=7200):
            return False, "expired"

        # 4. Cryptographic signature verification
        sig_valid = identity.verify(
            packet.payload_for_signing(),
            packet.signature,
            packet.node_id
        )
        if not sig_valid:
            self._punish(node_id_hex, "invalid-signature")
            return False, "invalid-signature"

        # 5. Reputation gate
        record = self._get_or_create(node_id_hex)
        if record.is_banned:
            return False, "banned-node"
        if record.reputation < self.MIN_REPUTATION_TO_ACCEPT:
            return False, f"low-reputation({record.reputation:.2f})"

        # 6. Quality threshold (for KNOWLEDGE packets)
        from core.packet import PACKET_KNOWLEDGE
        if packet.packet_type == PACKET_KNOWLEDGE:
            if packet.quality_score < self.MIN_QUALITY_TO_INTEGRATE:
                self._record(node_id_hex, accepted=False)
                return False, f"low-quality({packet.quality_score:.2f})"

        # ✅ All checks passed
        self._record(node_id_hex, accepted=True)
        return True, "accepted"

    def integrate_knowledge(self, packet: NanoPacket):
        """Record that we integrated this packet into our knowledge"""
        self.merkle.insert(packet.knowledge + packet.node_id)
        self.integrated_count += 1

    # ── Reputation Updates ─────────────────────────────────────────

    def reward(self, node_id_hex: str, delta: float = 0.02):
        """Node contributed good knowledge — increase reputation"""
        record = self._get_or_create(node_id_hex)
        record.reputation = min(1.0, record.reputation + delta)

    def _punish(self, node_id_hex: str, reason: str, delta: float = 0.08):
        """Node sent bad/forged packet — decrease reputation"""
        record = self._get_or_create(node_id_hex)
        record.reputation = max(0.0, record.reputation - delta)
        if record.reputation < self.BAN_THRESHOLD:
            record.is_banned = True
            print(f"[Trust] 🚫 Node {node_id_hex[:8]}... BANNED: {reason}")

    def _record(self, node_id_hex: str, accepted: bool):
        record = self._get_or_create(node_id_hex)
        record.packets_received += 1
        record.last_seen = time.time()
        if accepted:
            record.packets_accepted += 1
        else:
            record.packets_rejected += 1

    def _get_or_create(self, node_id_hex: str) -> NodeRecord:
        if node_id_hex not in self.nodes:
            self.nodes[node_id_hex] = NodeRecord(node_id=node_id_hex)
        return self.nodes[node_id_hex]

    # ── Status ─────────────────────────────────────────────────────

    def status(self) -> dict:
        active = [r for r in self.nodes.values() if not r.is_banned]
        return {
            "known_nodes": len(self.nodes),
            "active_nodes": len(active),
            "banned_nodes": len([r for r in self.nodes.values() if r.is_banned]),
            "integrated_packets": self.integrated_count,
            "rejected_packets": self.rejected_count,
            "merkle_root": self.merkle.root.hex()[:16] + "...",
            "merkle_size": self.merkle.size(),
        }

    def get_reputation(self, node_id_hex: str) -> float:
        return self.nodes.get(node_id_hex, NodeRecord(node_id="")).reputation

    def __repr__(self):
        s = self.status()
        return f"<TrustManager nodes={s['known_nodes']} integrated={s['integrated_packets']}>"
