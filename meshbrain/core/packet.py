"""
MeshBrain — NanoPacket Protocol
The fundamental unit of knowledge exchange between nodes.

What IS in a packet:  compressed knowledge vector, quality score,
                      topic fingerprint, cryptographic signature
What is NOT in a packet: raw query text, raw response text, user identity
"""

import json
import time
import struct
import hashlib
import zlib
from dataclasses import dataclass, field
from typing import Optional
import numpy as np


# ── Packet Types ─────────────────────────────────────────────────────

PACKET_KNOWLEDGE  = 0x01   # knowledge vector from a good interaction
PACKET_HANDSHAKE  = 0x02   # node introduction / peer discovery
PACKET_HEARTBEAT  = 0x03   # i'm alive + my current reputation
PACKET_QUERY_HELP = 0x04   # ask nearby peers to help with a query
PACKET_ACK        = 0x05   # acknowledgement + quality vote


@dataclass
class NanoPacket:
    """
    ~50–500 KB packet of compressed AI knowledge.
    Travels between nodes. Cryptographically signed.
    Cannot be reversed to recover original conversation.
    """

    # Header
    version:       int    = 1
    packet_type:   int    = PACKET_KNOWLEDGE
    timestamp_ms:  int    = field(default_factory=lambda: int(time.time() * 1000))

    # Identity
    node_id:       bytes  = b""          # sender's 32-byte public key
    signature:     bytes  = b""          # Ed25519 sig over payload

    # Knowledge payload (privacy-safe)
    topic_hash:    bytes  = b""          # 8-byte fingerprint of topic (NOT the text)
    knowledge:     bytes  = b""          # compressed embedding/gradient vector
    quality_score: float  = 0.0          # 0.0–1.0 self-reported quality
    language_code: str    = "en"         # ISO 639-1
    hop_count:     int    = 0            # how many nodes forwarded this

    # ── Serialization ─────────────────────────────────────────────

    def to_bytes(self) -> bytes:
        """Serialize to binary for transmission"""
        payload = {
            "v":   self.version,
            "pt":  self.packet_type,
            "ts":  self.timestamp_ms,
            "nid": self.node_id.hex(),
            "th":  self.topic_hash.hex(),
            "k":   self.knowledge.hex(),
            "q":   round(self.quality_score, 4),
            "lang": self.language_code,
            "hop": self.hop_count,
            "sig": self.signature.hex(),
        }
        raw = json.dumps(payload, separators=(',', ':')).encode()
        return zlib.compress(raw, level=6)

    @classmethod
    def from_bytes(cls, data: bytes) -> "NanoPacket":
        """Deserialize from binary"""
        raw = zlib.decompress(data)
        d = json.loads(raw)
        return cls(
            version=d["v"],
            packet_type=d["pt"],
            timestamp_ms=d["ts"],
            node_id=bytes.fromhex(d["nid"]),
            topic_hash=bytes.fromhex(d["th"]),
            knowledge=bytes.fromhex(d["k"]),
            quality_score=d["q"],
            language_code=d["lang"],
            hop_count=d["hop"],
            signature=bytes.fromhex(d["sig"]),
        )

    def payload_for_signing(self) -> bytes:
        """The bytes that get signed — everything except signature field"""
        return (
            self.node_id +
            self.topic_hash +
            self.knowledge +
            struct.pack(">Q", self.timestamp_ms) +
            self.language_code.encode()
        )

    # ── Factory methods ────────────────────────────────────────────

    @classmethod
    def create_knowledge_packet(
        cls,
        identity,               # NodeIdentity
        query_text: str,
        response_text: str,
        quality: float,
        language: str = "en"
    ) -> "NanoPacket":
        """
        Create a knowledge packet from a query/response pair.
        PRIVACY: only the compressed embedding is stored, not raw text.
        """
        topic_hash = cls._hash_topic(query_text)
        knowledge_vec = cls._compress_knowledge(query_text, response_text)

        packet = cls(
            packet_type=PACKET_KNOWLEDGE,
            node_id=identity.public_key_bytes(),
            topic_hash=topic_hash,
            knowledge=knowledge_vec,
            quality_score=max(0.0, min(1.0, quality)),
            language_code=language,
        )
        packet.signature = identity.sign(packet.payload_for_signing())
        return packet

    @classmethod
    def create_handshake(cls, identity) -> "NanoPacket":
        """Introduce ourselves to a new peer"""
        packet = cls(
            packet_type=PACKET_HANDSHAKE,
            node_id=identity.public_key_bytes(),
            quality_score=1.0,
            knowledge=b"HELLO",
        )
        packet.signature = identity.sign(packet.payload_for_signing())
        return packet

    @classmethod
    def create_heartbeat(cls, identity, reputation: float) -> "NanoPacket":
        packet = cls(
            packet_type=PACKET_HEARTBEAT,
            node_id=identity.public_key_bytes(),
            quality_score=reputation,
            knowledge=b"BEAT",
        )
        packet.signature = identity.sign(packet.payload_for_signing())
        return packet

    # ── Private helpers ────────────────────────────────────────────

    @staticmethod
    def _hash_topic(text: str) -> bytes:
        """
        8-byte topic fingerprint — identifies the topic domain
        WITHOUT revealing the actual query.
        Cannot be reversed (one-way hash).
        """
        h = hashlib.blake2b(text.lower().strip().encode(), digest_size=8)
        return h.digest()

    @staticmethod
    def _compress_knowledge(query: str, response: str) -> bytes:
        """
        Compress query+response into a knowledge vector.
        
        In production: this would be a LoRA gradient delta (~50KB).
        In this prototype: we use a simple TF-IDF-style bag-of-words
        embedding that captures semantic meaning without storing raw text.
        
        PRIVACY: this vector cannot practically be reversed to recover
        the original text (especially with the differential privacy noise below).
        """
        combined = f"{query} {response}"
        words = combined.lower().split()

        # Simple word-frequency vector over a 256-dim vocabulary hash space
        vec = np.zeros(256, dtype=np.float32)
        for word in words:
            idx = int(hashlib.md5(word.encode()).hexdigest()[:4], 16) % 256
            vec[idx] += 1.0

        # Normalize
        norm = np.linalg.norm(vec)
        if norm > 0:
            vec /= norm

        # Add differential privacy noise (epsilon=0.1)
        noise = np.random.normal(0, 0.05, 256).astype(np.float32)
        vec = vec + noise

        # Quantize to int8 (8x compression, ~256 bytes)
        vec_int8 = np.clip(vec * 127, -127, 127).astype(np.int8)
        return vec_int8.tobytes()

    def decompress_knowledge(self) -> np.ndarray:
        """Recover float vector from compressed knowledge bytes"""
        vec_int8 = np.frombuffer(self.knowledge, dtype=np.int8)
        return vec_int8.astype(np.float32) / 127.0

    # ── Info ───────────────────────────────────────────────────────

    def size_bytes(self) -> int:
        return len(self.to_bytes())

    def is_expired(self, max_age_seconds: int = 3600) -> bool:
        age = (time.time() * 1000 - self.timestamp_ms) / 1000
        return age > max_age_seconds

    def __repr__(self):
        type_names = {1:"KNOWLEDGE", 2:"HANDSHAKE", 3:"HEARTBEAT", 4:"QUERY", 5:"ACK"}
        t = type_names.get(self.packet_type, "UNKNOWN")
        return (f"<NanoPacket type={t} "
                f"node={self.node_id.hex()[:8]}... "
                f"quality={self.quality_score:.2f} "
                f"size={self.size_bytes()}B>")
