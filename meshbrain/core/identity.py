"""
MeshBrain — Node Identity
Every phone/device generates a permanent Ed25519 keypair.
Public key = your node ID. Private key never leaves your device.
"""

import os
import json
import hashlib
from pathlib import Path
from cryptography.hazmat.primitives.asymmetric.ed25519 import (
    Ed25519PrivateKey, Ed25519PublicKey
)
from cryptography.hazmat.primitives.serialization import (
    Encoding, PublicFormat, PrivateFormat, NoEncryption,
    load_pem_private_key
)


class NodeIdentity:
    """
    Permanent cryptographic identity for a MeshBrain node.
    Generated once, saved to disk, reloaded on restart.
    """

    def __init__(self, storage_path: str = "./node_identity"):
        self.storage_path = Path(storage_path)
        self.storage_path.mkdir(parents=True, exist_ok=True)
        self._private_key, self._public_key = self._load_or_create()

    # ── Key management ──────────────────────────────────────────────

    def _load_or_create(self):
        key_file = self.storage_path / "private.pem"
        if key_file.exists():
            with open(key_file, "rb") as f:
                priv = load_pem_private_key(f.read(), password=None)
            pub = priv.public_key()
            print(f"[Identity] Loaded existing node: {self.node_id_short(pub)}")
        else:
            priv = Ed25519PrivateKey.generate()
            pub = priv.public_key()
            pem = priv.private_bytes(Encoding.PEM, PrivateFormat.PKCS8, NoEncryption())
            with open(key_file, "wb") as f:
                f.write(pem)
            print(f"[Identity] Created new node: {self.node_id_short(pub)}")
        return priv, pub

    # ── Public properties ────────────────────────────────────────────

    @property
    def node_id(self) -> bytes:
        """32-byte public key = node identity"""
        return self._public_key.public_bytes(Encoding.Raw, PublicFormat.Raw)

    @property
    def node_id_hex(self) -> str:
        return self.node_id.hex()

    def node_id_short(self, pub_key=None) -> str:
        pk = pub_key or self._public_key
        raw = pk.public_bytes(Encoding.Raw, PublicFormat.Raw)
        return raw.hex()[:12] + "..."

    # ── Cryptographic operations ─────────────────────────────────────

    def sign(self, data: bytes) -> bytes:
        """Sign any bytes with our private key"""
        return self._private_key.sign(data)

    def verify(self, data: bytes, signature: bytes, sender_pub_key_bytes: bytes) -> bool:
        """Verify a signature from any node"""
        try:
            from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PublicKey
            pub = Ed25519PublicKey.from_public_bytes(sender_pub_key_bytes)
            pub.verify(signature, data)
            return True
        except Exception:
            return False

    def public_key_bytes(self) -> bytes:
        return self._public_key.public_bytes(Encoding.Raw, PublicFormat.Raw)

    def __repr__(self):
        return f"<NodeIdentity id={self.node_id_short()}>"
