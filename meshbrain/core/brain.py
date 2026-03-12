"""
MeshBrain — Local AI Brain
Connects to your locally running LLM (Ollama / llama.cpp server).
Runs 100% offline. No API keys. No external calls.
"""

import asyncio
import aiohttp
import json
import time
from typing import AsyncGenerator, Optional


class LocalBrain:
    """
    Interface to your local LLM (Gemma 2, Llama 3.2, Mistral, etc.)
    running via Ollama or any OpenAI-compatible local server.
    """

    def __init__(
        self,
        model: str = "gemma2:2b",
        base_url: str = "http://localhost:11434",
        fallback_mode: bool = True
    ):
        self.model = model
        self.base_url = base_url
        self.fallback_mode = fallback_mode  # use mock if Ollama not running
        self.is_available = False
        self.conversation_history = []      # in-memory only, never persisted

    # ── Startup ───────────────────────────────────────────────────

    async def check_availability(self) -> bool:
        """Check if local LLM is running"""
        try:
            async with aiohttp.ClientSession(
                timeout=aiohttp.ClientTimeout(total=3)
            ) as session:
                async with session.get(f"{self.base_url}/api/tags") as resp:
                    if resp.status == 200:
                        data = await resp.json()
                        models = [m["name"] for m in data.get("models", [])]
                        print(f"[Brain] ✅ Ollama running. Models: {models}")
                        self.is_available = True
                        # Try to find best available model
                        for preferred in [self.model, "gemma2:2b", "llama3.2:1b",
                                         "mistral:7b", "gemma:2b", "phi3:mini"]:
                            if any(preferred in m for m in models):
                                self.model = preferred
                                print(f"[Brain] Using model: {self.model}")
                                break
                        return True
        except Exception as e:
            print(f"[Brain] ⚠️  Ollama not running ({e})")
            if self.fallback_mode:
                print("[Brain] 🔄 Fallback mode: using mock responses for testing")
        return False

    # ── Core inference ────────────────────────────────────────────

    async def think_stream(self, user_input: str) -> AsyncGenerator[str, None]:
        """
        Stream tokens from local LLM.
        Conversation history kept in memory (session only).
        """
        self.conversation_history.append({
            "role": "user",
            "content": user_input
        })

        if not self.is_available:
            # Fallback for testing when Ollama not running
            async for token in self._mock_stream(user_input):
                yield token
            return

        full_response = ""
        try:
            async with aiohttp.ClientSession() as session:
                payload = {
                    "model": self.model,
                    "messages": self.conversation_history,
                    "stream": True,
                    "options": {
                        "temperature": 0.7,
                        "top_p": 0.9,
                        "num_predict": 512,
                    }
                }
                async with session.post(
                    f"{self.base_url}/api/chat",
                    json=payload,
                    timeout=aiohttp.ClientTimeout(total=120)
                ) as resp:
                    async for line in resp.content:
                        line = line.decode().strip()
                        if not line:
                            continue
                        try:
                            data = json.loads(line)
                            token = data.get("message", {}).get("content", "")
                            if token:
                                full_response += token
                                yield token
                            if data.get("done"):
                                break
                        except json.JSONDecodeError:
                            continue

        except Exception as e:
            yield f"\n[Brain error: {e}]"

        # Store assistant response in history (in-memory only)
        if full_response:
            self.conversation_history.append({
                "role": "assistant",
                "content": full_response
            })

    async def think(self, user_input: str) -> str:
        """Non-streaming version — returns complete response"""
        tokens = []
        async for token in self.think_stream(user_input):
            tokens.append(token)
        return "".join(tokens)

    # ── Knowledge extraction ──────────────────────────────────────

    async def extract_knowledge_quality(self, query: str, response: str) -> float:
        """
        Estimate how good/confident this response is.
        Uses a quick self-evaluation prompt.
        Returns 0.0–1.0
        """
        if not self.is_available:
            # Simple heuristic for mock mode
            words = len(response.split())
            if words < 5:  return 0.3
            if words < 20: return 0.6
            return 0.8

        eval_prompt = (
            f"Rate the quality of this answer from 0.0 to 1.0. "
            f"Return ONLY a number like 0.7.\n"
            f"Question: {query[:100]}\n"
            f"Answer: {response[:200]}"
        )
        try:
            rating_str = await self.think_quick(eval_prompt)
            # Extract first number found
            import re
            nums = re.findall(r'\d+\.?\d*', rating_str)
            if nums:
                val = float(nums[0])
                if val > 1.0:  # might have given 70 instead of 0.7
                    val /= 100.0
                return max(0.0, min(1.0, val))
        except Exception:
            pass
        return 0.7  # default

    async def think_quick(self, prompt: str) -> str:
        """Quick single-turn inference, no history"""
        if not self.is_available:
            return "0.7"
        try:
            async with aiohttp.ClientSession() as session:
                payload = {
                    "model": self.model,
                    "messages": [{"role": "user", "content": prompt}],
                    "stream": False,
                    "options": {"num_predict": 10, "temperature": 0.1}
                }
                async with session.post(
                    f"{self.base_url}/api/chat",
                    json=payload,
                    timeout=aiohttp.ClientTimeout(total=15)
                ) as resp:
                    data = await resp.json()
                    return data.get("message", {}).get("content", "0.7")
        except Exception:
            return "0.7"

    # ── Session management ────────────────────────────────────────

    def clear_history(self):
        """Purge conversation — called at session end"""
        self.conversation_history.clear()

    def context_length(self) -> int:
        return sum(len(m["content"]) for m in self.conversation_history)

    # ── Mock responses for testing ────────────────────────────────

    async def _mock_stream(self, query: str) -> AsyncGenerator[str, None]:
        """
        Simulates LLM streaming for testing when Ollama is not installed.
        Replace with real model by running: ollama pull gemma2:2b
        """
        responses = {
            "hello": "Hello! I'm MeshBrain, running locally on your device. No data leaves your phone. How can I help?",
            "what": "I'm a local AI model running on your device using llama.cpp / Ollama. I operate completely offline.",
            "mesh": "The mesh network connects local AI nodes on nearby devices. Each phone is its own brain, sharing compressed knowledge vectors — not raw conversations.",
            "blockchain": "Blockchain cryptography (Ed25519 signatures + Merkle trees) verifies every knowledge packet. No central server needed — math enforces trust.",
            "default": f"[Mock Brain - Ollama not running] I received: '{query[:50]}'. Install Ollama and run 'ollama pull gemma2:2b' to activate the real local model.",
        }
        key = "default"
        q = query.lower()
        for k in responses:
            if k in q:
                key = k
                break
        response = responses[key]
        for word in response.split():
            yield word + " "
            await asyncio.sleep(0.04)

    def __repr__(self):
        status = "✅ online" if self.is_available else "⚠️ offline (fallback)"
        return f"<LocalBrain model={self.model} status={status}>"
