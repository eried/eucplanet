# Location Share Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Live location sharing from the navigator map: a Share button yields a QR/link; app users join as riders, browser users watch, everyone sees each other on a map with fading trails, chosen identity, optional stats, staleness handling, a peer list with tap-to-locate, and Leave.

**Architecture:** A tiny end-to-end-encrypted WebSocket relay (`eucshare.ried.no`, new repo `eried/eucshare`, FastAPI + single-process uvicorn) fans out opaque envelopes per room; rooms die 1 h after the last disconnect. The Android app (`ShareSession`) and a static web viewer on GitHub Pages (`eucplanet.ried.no/share`) share one contract: AES-256-GCM envelopes keyed from the URL fragment, a fixed peer-color palette, and fresh/stale/lost thresholds. The link `https://eucplanet.ried.no/share#roomId.key` is an Android App Link, so one QR serves both audiences.

**Tech Stack:** Relay: Python 3, FastAPI, `uvicorn[standard]` (native WebSockets), pytest + httpx/`starlette.testclient`. App: Kotlin, Compose, Hilt, OkHttp WebSocket (already a dependency), `javax.crypto` AES-GCM, ZXing QR (already present, `QrCodeImage`), JUnit 4 only (no mockk/Robolectric). Web: static HTML + MapLibre GL JS + WebCrypto, Playwright for tests.

**Spec:** `docs/superpowers/specs/2026-08-29-location-share-design.md`

## Global Constraints

- No em-dashes anywhere (code, comments, strings, commit messages). Use commas, " - ", or separate sentences.
- Rider terminology (wheel / rider). Localize every user-facing app string to all locales; keep labels short.
- Colors via `MaterialTheme.appColors.*` with explicit on-colors. The peer palette is the one deliberate exception: a fixed data set that MUST be byte-identical in Kotlin and the web viewer (`PeerPalette`).
- Settings only through `SettingsRepository`; `ShareSettings` is ONE nested group on `AppSettings` (AppSettings is at 248/255 arg slots; one slot allowed) and must be in BOTH `SettingsJson.toJson` and `fromJson` with a round-trip test. Bump `AppSettingsArgLimitTest.expectedSlots` 248 -> 249 with a documenting comment.
- The relay must never be able to read positions: every envelope payload is AES-256-GCM ciphertext; the relay forwards `ct` opaquely and never parses it.
- Shared contract values (verbatim, used by relay tests, Kotlin, and web): envelope JSON `{"from":"<senderId>","ct":"<base64url ciphertext>"}`; control frames `{"type":"peers","seen":{...}}` and `{"type":"leave"}`; key derivation `HKDF-SHA256(ikm = raw 16-byte key, salt = "eucshare-v1", info = "aes-256-gcm", len = 32)`; ciphertext layout `nonce(12) || AES-GCM(plaintext, aad = roomId)`; roomId and key are 16 random bytes base64url no padding (22 chars each); staleness thresholds `FRESH < 15000 ms`, `STALE < 120000 ms`, else `LOST`; publish cadence 3 s or >10 m.
- Peer palette (index by join order, wrap): `#E53935,#1E88E5,#43A047,#FB8C00,#8E24AA,#00ACC1,#F4511E,#3949AB,#7CB342,#D81B60,#00897B,#FDD835`.
- Relay: port **8006** on the droplet (`root@64.227.89.199`, ssh key auth works), own dir `/opt/eucshare`, own systemd unit `eucshare`, nginx site + Certbot exactly like `eucstats.ried.no` (see Task 8). DNS `eucshare.ried.no -> 64.227.89.199` is already live.
- Verify builds by grepping `BUILD SUCCESSFUL`; never mask exit codes. Bash tool with forward-slash paths; `./gradlew.bat` for the app.
- Branch `feature/location-share` (app repo, off next-experimental). Do NOT push the app repo until the rider tests. The `eucshare` repo IS pushed (its deploy script requires HEAD == origin/main).

**Testability note:** the app cannot unit-test OkHttp/Compose/Android here (JUnit 4 only). So all decision logic is pure and tested: crypto codec, staleness, trail fade, palette, identity resolution, link parse/format. The wiring (WebSocket, service, Compose, map JS) is verified by build + the Task 10 end-to-end run.

---

### Task 1: `eucshare` repo + relay core (rooms, fan-out, replay, leave)

**Files (new repo `D:\GitHub\eucshare`, pushed to `eried/eucshare`):**
- Create: `main.py`, `relay.py`, `config.py`, `requirements.txt`, `README.md`, `.gitignore`, `tests/test_relay.py`

**Interfaces:**
- Produces: `RoomRegistry` (in `relay.py`) with `async def join(room_id, ws)`, `async def leave(room_id, ws)`, `async def broadcast(room_id, sender_ws, text)`; ASGI app `main:app` exposing `GET /health` -> `{"ok":true,"rooms":N}` and `WS /ws/{room_id}`.
- Envelope rule: any client text that parses as JSON with a `"ct"` key is stored as that sender's latest (keyed by its `"from"`) and fanned out to all OTHER sockets in the room. `{"type":"leave"}` deletes the sender's stored envelope and broadcasts `{"type":"left","from":id}`. On join, the joiner receives every stored latest envelope once.

- [ ] **Step 1: Create the repo skeleton**

```bash
mkdir -p /d/GitHub/eucshare/tests && cd /d/GitHub/eucshare && git init -q -b main
cat > requirements.txt <<'EOF'
fastapi
uvicorn[standard]
pytest
httpx
EOF
cat > .gitignore <<'EOF'
.venv/
__pycache__/
*.pyc
.pytest_cache/
EOF
cat > config.py <<'EOF'
"""eucshare relay settings (env-overridable, mirrors eucstats config.py)."""
import os

ROOM_TTL_S = int(os.environ.get("EUCSHARE_ROOM_TTL_S", "3600"))   # delete a room 1 h after its last socket closes
MAX_PEERS = int(os.environ.get("EUCSHARE_MAX_PEERS", "32"))
MAX_MSG_BYTES = int(os.environ.get("EUCSHARE_MAX_MSG_BYTES", "2048"))
RATE_PER_S = float(os.environ.get("EUCSHARE_RATE_PER_S", "4"))
PEERS_FRAME_S = int(os.environ.get("EUCSHARE_PEERS_FRAME_S", "10"))  # how often the relay reports peer ages
EOF
python -m venv .venv && .venv/Scripts/pip install -q -r requirements.txt
```

- [ ] **Step 2: Write the failing relay tests** `tests/test_relay.py`:

```python
import json
from fastapi.testclient import TestClient
import main

ROOM = "AAAAAAAAAAAAAAAAAAAAAA"  # 22-char base64url shape

def env(sender, ct="x"):
    return json.dumps({"from": sender, "ct": ct})

def test_health():
    c = TestClient(main.app)
    r = c.get("/health")
    assert r.status_code == 200 and r.json()["ok"] is True

def test_fanout_to_other_peers_not_self():
    c = TestClient(main.app)
    with c.websocket_connect(f"/ws/{ROOM}") as a, c.websocket_connect(f"/ws/{ROOM}") as b:
        a.send_text(env("A", "ctA"))
        got = json.loads(b.receive_text())
        assert got == {"from": "A", "ct": "ctA"}
        # A must not get its own message back: send from B and check A only sees B's
        b.send_text(env("B", "ctB"))
        assert json.loads(a.receive_text()) == {"from": "B", "ct": "ctB"}

def test_joiner_gets_latest_envelope_replay():
    c = TestClient(main.app)
    with c.websocket_connect(f"/ws/{ROOM}2") as a:
        a.send_text(env("A", "old"))
        a.send_text(env("A", "new"))
        with c.websocket_connect(f"/ws/{ROOM}2") as b:
            replay = json.loads(b.receive_text())
            assert replay == {"from": "A", "ct": "new"}   # only the latest, once

def test_leave_removes_envelope_and_broadcasts_left():
    c = TestClient(main.app)
    with c.websocket_connect(f"/ws/{ROOM}3") as a, c.websocket_connect(f"/ws/{ROOM}3") as b:
        a.send_text(env("A", "ctA")); b.receive_text()
        a.send_text(json.dumps({"type": "leave", "from": "A"}))
        assert json.loads(b.receive_text()) == {"type": "left", "from": "A"}
        with c.websocket_connect(f"/ws/{ROOM}3") as d:
            # nothing stored for A anymore -> a fresh joiner gets no replay for A
            d.send_text(env("D", "ctD"))
            assert json.loads(b.receive_text()) == {"from": "D", "ct": "ctD"}

def test_bad_room_id_rejected():
    c = TestClient(main.app)
    import pytest
    with pytest.raises(Exception):
        with c.websocket_connect("/ws/not-valid!"):
            pass
```

- [ ] **Step 3: Run tests, confirm they fail** (no `main`):

Run: `cd /d/GitHub/eucshare && .venv/Scripts/python -m pytest -q`
Expected: FAIL (ModuleNotFoundError / no app).

- [ ] **Step 4: Implement `relay.py`**

```python
"""In-memory rooms. The relay never inspects `ct` (end-to-end encrypted by clients)."""
import asyncio, json, re, time
from typing import Dict, Optional
import config

ROOM_ID = re.compile(r"^[A-Za-z0-9_-]{22}$")

class Room:
    def __init__(self):
        self.sockets = set()
        self.latest: Dict[str, str] = {}     # sender id -> last envelope text
        self.last_seen: Dict[str, float] = {}
        self.expire_task: Optional[asyncio.Task] = None

class RoomRegistry:
    def __init__(self):
        self.rooms: Dict[str, Room] = {}

    def count(self) -> int:
        return len(self.rooms)

    async def join(self, room_id: str, ws) -> Room:
        room = self.rooms.get(room_id)
        if room is None:
            room = self.rooms[room_id] = Room()
        if room.expire_task:
            room.expire_task.cancel(); room.expire_task = None   # a reconnect cancels the TTL
        if len(room.sockets) >= config.MAX_PEERS:
            raise PermissionError("room full")
        room.sockets.add(ws)
        for text in list(room.latest.values()):                  # replay latest, once each
            await ws.send_text(text)
        return room

    async def leave(self, room_id: str, ws):
        room = self.rooms.get(room_id)
        if room is None:
            return
        room.sockets.discard(ws)
        if not room.sockets:
            room.expire_task = asyncio.create_task(self._expire(room_id))

    async def _expire(self, room_id: str):
        await asyncio.sleep(config.ROOM_TTL_S)
        room = self.rooms.get(room_id)
        if room is not None and not room.sockets:
            del self.rooms[room_id]

    async def handle(self, room_id: str, room: Room, sender_ws, text: str):
        try:
            msg = json.loads(text)
        except ValueError:
            return
        if not isinstance(msg, dict):
            return
        sender = str(msg.get("from", ""))
        if msg.get("type") == "leave":
            room.latest.pop(sender, None); room.last_seen.pop(sender, None)
            await self._fanout(room, sender_ws, json.dumps({"type": "left", "from": sender}))
            return
        if "ct" in msg and sender:
            room.latest[sender] = text
            room.last_seen[sender] = time.monotonic()
            await self._fanout(room, sender_ws, text)

    async def _fanout(self, room: Room, sender_ws, text: str):
        for other in list(room.sockets):
            if other is sender_ws:
                continue
            try:
                await other.send_text(text)
            except Exception:
                room.sockets.discard(other)

    def peers_frame(self, room: Room) -> str:
        now = time.monotonic()
        return json.dumps({"type": "peers", "seen": {k: int(now - v) for k, v in room.last_seen.items()}})

registry = RoomRegistry()
```

- [ ] **Step 5: Implement `main.py`**

```python
"""eucshare: tiny end-to-end-encrypted location-share relay (served by uvicorn, ONE process)."""
import asyncio, time
from fastapi import FastAPI, WebSocket, WebSocketDisconnect
from starlette.websockets import WebSocketState
import config
from relay import registry, ROOM_ID

app = FastAPI(title="eucshare")

@app.get("/health")
def health():
    return {"ok": True, "rooms": registry.count()}

@app.websocket("/ws/{room_id}")
async def ws_room(ws: WebSocket, room_id: str):
    if not ROOM_ID.match(room_id):
        await ws.close(code=1008); return
    await ws.accept()
    try:
        room = await registry.join(room_id, ws)
    except PermissionError:
        await ws.close(code=1013); return          # try again later / room full
    bucket, last = config.RATE_PER_S, time.monotonic()
    peers_task = asyncio.create_task(_peers_loop(ws, room))
    try:
        while True:
            text = await ws.receive_text()
            if len(text.encode("utf-8")) > config.MAX_MSG_BYTES:
                continue
            now = time.monotonic()
            bucket = min(config.RATE_PER_S, bucket + (now - last) * config.RATE_PER_S); last = now
            if bucket < 1:
                continue                            # rate limited: drop, do not disconnect
            bucket -= 1
            await registry.handle(room_id, room, ws, text)
    except WebSocketDisconnect:
        pass
    finally:
        peers_task.cancel()
        await registry.leave(room_id, ws)

async def _peers_loop(ws: WebSocket, room):
    while ws.client_state == WebSocketState.CONNECTED:
        await asyncio.sleep(config.PEERS_FRAME_S)
        try:
            await ws.send_text(registry.peers_frame(room))
        except Exception:
            return
```

- [ ] **Step 6: Run tests, confirm pass**

Run: `cd /d/GitHub/eucshare && .venv/Scripts/python -m pytest -q`
Expected: 5 passed.

- [ ] **Step 7: README + first commit + create the GitHub repo and push**

```bash
cd /d/GitHub/eucshare
cat > README.md <<'EOF'
# eucshare

Tiny end-to-end-encrypted relay for EUC Planet live location share
(`eucshare.ried.no`). Riders share a link `https://eucplanet.ried.no/share#roomId.key`;
positions are AES-GCM encrypted with the key from the URL fragment, so this relay
only ever forwards ciphertext. Rooms live in memory and are deleted 1 hour after the
last connection closes. No accounts, no database.

Run: `pip install -r requirements.txt && uvicorn main:app --port 8006`. Tests: `pytest`.
Deploy: `scripts/deploy.sh` (same pattern as eucstats).
EOF
git add -A && git commit -q -m "feat: relay core - rooms, fan-out, latest replay, leave, limits"
gh repo create eried/eucshare --public --source=. --remote=origin --push --description "E2E-encrypted location-share relay for EUC Planet"
```

Expected: repo created, `main` pushed.

---

### Task 2: Relay room TTL + config test

**Files:** `D:\GitHub\eucshare\tests\test_ttl.py`

**Interfaces:** Consumes `RoomRegistry` from Task 1. Produces: proven TTL semantics (delete after `ROOM_TTL_S` with no sockets; reconnect cancels).

- [ ] **Step 1: Write the failing TTL test** (uses a tiny TTL via env so the test is fast):

```python
import asyncio, os, importlib

def test_room_deleted_after_ttl_and_reconnect_cancels(monkeypatch):
    monkeypatch.setenv("EUCSHARE_ROOM_TTL_S", "1")
    import config; importlib.reload(config)
    import relay; importlib.reload(relay)
    reg = relay.RoomRegistry()

    class FakeWs:
        async def send_text(self, t): pass

    async def run():
        ws = FakeWs()
        room = await reg.join("R" * 22, ws)
        room.latest["A"] = '{"from":"A","ct":"x"}'
        await reg.leave("R" * 22, ws)
        assert reg.count() == 1                # still there right after leave
        await asyncio.sleep(0.3)
        await reg.join("R" * 22, FakeWs())     # reconnect inside the window cancels the timer
        await asyncio.sleep(1.2)
        assert reg.count() == 1                # NOT deleted: someone is connected
        assert reg.rooms["R" * 22].latest["A"] == '{"from":"A","ct":"x"}'  # envelopes survived
        # now leave for real and let it expire
        for s in list(reg.rooms["R" * 22].sockets):
            await reg.leave("R" * 22, s)
        await asyncio.sleep(1.3)
        assert reg.count() == 0
    asyncio.run(run())
```

- [ ] **Step 2: Run, confirm it fails or passes honestly.** Run: `.venv/Scripts/python -m pytest tests/test_ttl.py -q`. If it passes immediately the Task 1 implementation already satisfies it (that is fine: it is a regression guard); if it fails, fix `RoomRegistry.leave/_expire/join` until it passes without changing the assertions.

- [ ] **Step 3: Commit + push**

```bash
git add tests/test_ttl.py && git commit -q -m "test: room TTL expiry and reconnect cancel" && git push -q
```

---

### Task 3: Shared crypto + link contract in Kotlin (`ShareCrypto`, `ShareLink`)

**Files (app repo):**
- Create: `app/src/main/java/com/eried/eucplanet/share/ShareCrypto.kt`
- Create: `app/src/main/java/com/eried/eucplanet/share/ShareLink.kt`
- Test: `app/src/test/java/com/eried/eucplanet/share/ShareCryptoTest.kt`, `ShareLinkTest.kt`

**Interfaces:**
- Produces: `object ShareCrypto { fun deriveKey(rawKey: ByteArray): ByteArray /*32*/; fun encrypt(key: ByteArray, roomId: String, plaintext: ByteArray): ByteArray /*nonce||ct*/; fun decrypt(key: ByteArray, roomId: String, blob: ByteArray): ByteArray; fun b64u(bytes: ByteArray): String; fun unb64u(s: String): ByteArray; fun randomBytes(n: Int): ByteArray }`
- Produces: `data class ShareLink(val roomId: String, val key: ByteArray)`, `object ShareLinks { const val BASE = "https://eucplanet.ried.no/share"; fun format(link: ShareLink): String; fun parse(url: String): ShareLink? }`.
- Test vector (MUST also be asserted by the web viewer in Task 7): rawKey = 16 bytes `0x01..0x10`, roomId = `"AAAAAAAAAAAAAAAAAAAAAA"`, nonce = 12 zero bytes, plaintext `"hi"` -> the ciphertext hex is whatever this implementation produces; the test pins it by asserting `decrypt(encrypt(...)) == plaintext` AND records the hex in a comment for Task 7 to copy verbatim.

- [ ] **Step 1: Write the failing tests**

`ShareCryptoTest.kt`:
```kotlin
package com.eried.eucplanet.share

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ShareCryptoTest {
    private val raw = ByteArray(16) { (it + 1).toByte() }
    private val room = "AAAAAAAAAAAAAAAAAAAAAA"

    @Test fun deriveKey_is32Bytes_andDeterministic() {
        val k1 = ShareCrypto.deriveKey(raw); val k2 = ShareCrypto.deriveKey(raw)
        assertEquals(32, k1.size); assertArrayEquals(k1, k2)
    }
    @Test fun roundTrip() {
        val k = ShareCrypto.deriveKey(raw)
        val blob = ShareCrypto.encrypt(k, room, "hi".toByteArray())
        assertEquals(12 + 2 + 16, blob.size)                  // nonce + ct + GCM tag
        assertArrayEquals("hi".toByteArray(), ShareCrypto.decrypt(k, room, blob))
    }
    @Test fun wrongRoomAadFails() {
        val k = ShareCrypto.deriveKey(raw)
        val blob = ShareCrypto.encrypt(k, room, "hi".toByteArray())
        try { ShareCrypto.decrypt(k, "BBBBBBBBBBBBBBBBBBBBBB", blob); org.junit.Assert.fail("aad mismatch must fail") }
        catch (e: Exception) { /* expected */ }
    }
    @Test fun twoEncryptsDiffer_randomNonce() {
        val k = ShareCrypto.deriveKey(raw)
        assertNotEquals(ShareCrypto.b64u(ShareCrypto.encrypt(k, room, "x".toByteArray())),
                        ShareCrypto.b64u(ShareCrypto.encrypt(k, room, "x".toByteArray())))
    }
    @Test fun b64uRoundTrip_noPadding() {
        val s = ShareCrypto.b64u(raw)
        assertEquals(22, s.length); assertEquals(false, s.contains("="))
        assertArrayEquals(raw, ShareCrypto.unb64u(s))
    }
}
```

`ShareLinkTest.kt`:
```kotlin
package com.eried.eucplanet.share

import org.junit.Assert.*
import org.junit.Test

class ShareLinkTest {
    @Test fun formatAndParse() {
        val link = ShareLink("AAAAAAAAAAAAAAAAAAAAAA", ByteArray(16) { (it + 1).toByte() })
        val url = ShareLinks.format(link)
        assertTrue(url.startsWith("https://eucplanet.ried.no/share#"))
        assertFalse(url.contains("?"))                       // secret only in the fragment
        val back = ShareLinks.parse(url)!!
        assertEquals(link.roomId, back.roomId); assertArrayEquals(link.key, back.key)
    }
    @Test fun parseRejectsGarbage() {
        assertNull(ShareLinks.parse("https://eucplanet.ried.no/share"))
        assertNull(ShareLinks.parse("https://eucplanet.ried.no/share#short.x"))
        assertNull(ShareLinks.parse("https://other.example/share#AAAAAAAAAAAAAAAAAAAAAA.AAAAAAAAAAAAAAAAAAAAAA"))
    }
}
```

- [ ] **Step 2: Run, confirm fail.** `./gradlew.bat :app:testDebugUnitTest --tests "com.eried.eucplanet.share.*"` -> FAIL (unresolved).

- [ ] **Step 3: Implement** `ShareCrypto.kt`:

```kotlin
package com.eried.eucplanet.share

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * End-to-end crypto for location share. Same contract as the web viewer:
 * key = HKDF-SHA256(rawKey, salt "eucshare-v1", info "aes-256-gcm", 32);
 * blob = nonce(12) || AES-256-GCM(plaintext, aad = roomId). The relay only
 * ever sees the blob, so it cannot read positions.
 */
object ShareCrypto {
    private const val SALT = "eucshare-v1"
    private const val INFO = "aes-256-gcm"
    private val rng = SecureRandom()

    fun randomBytes(n: Int): ByteArray = ByteArray(n).also { rng.nextBytes(it) }

    fun deriveKey(rawKey: ByteArray): ByteArray {
        // HKDF extract + expand (RFC 5869) with HMAC-SHA256, one block (32 bytes).
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(SALT.toByteArray(), "HmacSHA256"))
        val prk = mac.doFinal(rawKey)
        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        mac.update(INFO.toByteArray()); mac.update(1.toByte())
        return mac.doFinal()
    }

    fun encrypt(key: ByteArray, roomId: String, plaintext: ByteArray): ByteArray {
        val nonce = randomBytes(12)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        c.updateAAD(roomId.toByteArray())
        return nonce + c.doFinal(plaintext)
    }

    fun decrypt(key: ByteArray, roomId: String, blob: ByteArray): ByteArray {
        require(blob.size > 12) { "blob too short" }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, blob.copyOfRange(0, 12)))
        c.updateAAD(roomId.toByteArray())
        return c.doFinal(blob.copyOfRange(12, blob.size))
    }

    fun b64u(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    fun unb64u(s: String): ByteArray = Base64.getUrlDecoder().decode(s)
}
```

`ShareLink.kt`:
```kotlin
package com.eried.eucplanet.share

/** roomId is public (the relay sees it); key lives only in the URL fragment. */
data class ShareLink(val roomId: String, val key: ByteArray)

object ShareLinks {
    const val BASE = "https://eucplanet.ried.no/share"
    private val ID = Regex("^[A-Za-z0-9_-]{22}$")

    fun newLink(): ShareLink = ShareLink(ShareCrypto.b64u(ShareCrypto.randomBytes(16)), ShareCrypto.randomBytes(16))

    fun format(link: ShareLink): String = "$BASE#${link.roomId}.${ShareCrypto.b64u(link.key)}"

    fun parse(url: String): ShareLink? {
        if (!url.startsWith(BASE)) return null
        val frag = url.substringAfter('#', "")
        val parts = frag.split('.')
        if (parts.size != 2 || !ID.matches(parts[0]) || !ID.matches(parts[1])) return null
        return runCatching { ShareLink(parts[0], ShareCrypto.unb64u(parts[1])) }.getOrNull()
    }
}
```

- [ ] **Step 4: Run, confirm pass.** `./gradlew.bat :app:testDebugUnitTest --tests "com.eried.eucplanet.share.*"` -> 7 tests PASS.

- [ ] **Step 5: Commit**
```bash
git add app/src/main/java/com/eried/eucplanet/share/ app/src/test/java/com/eried/eucplanet/share/
git commit -m "feat(share): E2E crypto (HKDF + AES-GCM) and share-link contract"
```

---

### Task 4: Pure share model - payload codec, palette, staleness, trail

**Files (app repo):**
- Create: `app/src/main/java/com/eried/eucplanet/share/ShareModel.kt`
- Test: `app/src/test/java/com/eried/eucplanet/share/ShareModelTest.kt`

**Interfaces:**
- Produces: `enum class IdentityMode { ANON, SESSION, PROFILE }`; `data class ShareStats(val speedKmh: Float, val batteryPct: Int, val tempC: Float)`; `data class SharePayload(val id: String, val name: String, val mode: IdentityMode, val color: String, val icon: String?, val avatarUrl: String?, val flag: String?, val lat: Double, val lng: Double, val heading: Float?, val t: Long, val stats: ShareStats?)` with `fun toJson(): String` and `companion fun fromJson(s: String): SharePayload?` (v:1); `object PeerPalette { val COLORS: List<String>; fun colorFor(joinIndex: Int): String }`; `enum class Freshness { FRESH, STALE, LOST }` + `object Staleness { const val FRESH_MS = 15_000L; const val STALE_MS = 120_000L; fun of(ageMs: Long): Freshness }`; `class Trail(val maxAgeMs: Long) { fun add(lat, lng, t); fun points(now): List<TrailPoint>; }` with `data class TrailPoint(val lat: Double, val lng: Double, val t: Long, val alpha: Float)` where alpha fades 1.0 (newest) -> 0.15 (oldest).

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.eried.eucplanet.share

import org.junit.Assert.*
import org.junit.Test

class ShareModelTest {
    private fun p(t: Long = 1000L, stats: ShareStats? = ShareStats(25.5f, 80, 31f)) = SharePayload(
        id = "abc", name = "Erwin", mode = IdentityMode.SESSION, color = "#E53935", icon = null,
        avatarUrl = null, flag = "NO", lat = 59.91, lng = 10.75, heading = 90f, t = t, stats = stats)

    @Test fun payloadJsonRoundTrip() {
        val back = SharePayload.fromJson(p().toJson())!!
        assertEquals(p(), back)
    }
    @Test fun payloadWithoutStats_omitsStatsKey() {
        val json = p(stats = null).toJson()
        assertFalse(json.contains("\"stats\""))
        assertNull(SharePayload.fromJson(json)!!.stats)
    }
    @Test fun payloadRejectsWrongVersionOrGarbage() {
        assertNull(SharePayload.fromJson("{\"v\":2}")); assertNull(SharePayload.fromJson("nope"))
    }
    @Test fun paletteIsFixedAndWraps() {
        assertEquals(12, PeerPalette.COLORS.size)
        assertEquals("#E53935", PeerPalette.colorFor(0)); assertEquals("#1E88E5", PeerPalette.colorFor(1))
        assertEquals(PeerPalette.colorFor(0), PeerPalette.colorFor(12))
    }
    @Test fun stalenessThresholds() {
        assertEquals(Freshness.FRESH, Staleness.of(0)); assertEquals(Freshness.FRESH, Staleness.of(14_999))
        assertEquals(Freshness.STALE, Staleness.of(15_000)); assertEquals(Freshness.STALE, Staleness.of(119_999))
        assertEquals(Freshness.LOST, Staleness.of(120_000))
    }
    @Test fun trailKeepsOnlyRecent_andFades() {
        val tr = Trail(maxAgeMs = 5 * 60_000L)
        tr.add(1.0, 1.0, t = 0L)                 // 10 min old at now=600s -> dropped
        tr.add(2.0, 2.0, t = 400_000L)
        tr.add(3.0, 3.0, t = 600_000L)
        val pts = tr.points(now = 600_000L)
        assertEquals(2, pts.size)
        assertEquals(3.0, pts.last().lat, 0.0); assertEquals(1.0f, pts.last().alpha, 0.001f)
        assertTrue(pts.first().alpha < pts.last().alpha && pts.first().alpha >= 0.15f)
    }
}
```

- [ ] **Step 2: Run, confirm fail.** `./gradlew.bat :app:testDebugUnitTest --tests "com.eried.eucplanet.share.ShareModelTest"` -> FAIL.

- [ ] **Step 3: Implement `ShareModel.kt`**

```kotlin
package com.eried.eucplanet.share

import org.json.JSONObject

enum class IdentityMode { ANON, SESSION, PROFILE }

data class ShareStats(val speedKmh: Float, val batteryPct: Int, val tempC: Float)

/** The decrypted per-rider message. v:1. Field names are the wire contract shared with the web viewer. */
data class SharePayload(
    val id: String, val name: String, val mode: IdentityMode, val color: String,
    val icon: String?, val avatarUrl: String?, val flag: String?,
    val lat: Double, val lng: Double, val heading: Float?, val t: Long, val stats: ShareStats?,
) {
    fun toJson(): String = JSONObject().apply {
        put("v", 1); put("id", id); put("name", name); put("mode", mode.name); put("color", color)
        icon?.let { put("icon", it) }; avatarUrl?.let { put("avatarUrl", it) }; flag?.let { put("flag", it) }
        put("lat", lat); put("lng", lng); heading?.let { put("heading", it.toDouble()) }; put("t", t)
        stats?.let { s -> put("stats", JSONObject().apply {
            put("speedKmh", s.speedKmh.toDouble()); put("batteryPct", s.batteryPct); put("tempC", s.tempC.toDouble()) }) }
    }.toString()

    companion object {
        fun fromJson(s: String): SharePayload? = runCatching {
            val j = JSONObject(s)
            if (j.optInt("v", -1) != 1) return null
            val st = j.optJSONObject("stats")?.let {
                ShareStats(it.getDouble("speedKmh").toFloat(), it.getInt("batteryPct"), it.getDouble("tempC").toFloat()) }
            SharePayload(
                id = j.getString("id"), name = j.getString("name"),
                mode = IdentityMode.valueOf(j.getString("mode")), color = j.getString("color"),
                icon = j.optString("icon").takeIf { j.has("icon") },
                avatarUrl = j.optString("avatarUrl").takeIf { j.has("avatarUrl") },
                flag = j.optString("flag").takeIf { j.has("flag") },
                lat = j.getDouble("lat"), lng = j.getDouble("lng"),
                heading = if (j.has("heading")) j.getDouble("heading").toFloat() else null,
                t = j.getLong("t"), stats = st,
            )
        }.getOrNull()
    }
}

/** Fixed, shared with the web viewer byte for byte. Assigned by join order. */
object PeerPalette {
    val COLORS = listOf("#E53935", "#1E88E5", "#43A047", "#FB8C00", "#8E24AA", "#00ACC1",
        "#F4511E", "#3949AB", "#7CB342", "#D81B60", "#00897B", "#FDD835")
    fun colorFor(joinIndex: Int): String = COLORS[Math.floorMod(joinIndex, COLORS.size)]
}

enum class Freshness { FRESH, STALE, LOST }

/** Same thresholds in the app and the web viewer. Constants, not settings. */
object Staleness {
    const val FRESH_MS = 15_000L
    const val STALE_MS = 120_000L
    fun of(ageMs: Long): Freshness = when {
        ageMs < FRESH_MS -> Freshness.FRESH
        ageMs < STALE_MS -> Freshness.STALE
        else -> Freshness.LOST
    }
}

data class TrailPoint(val lat: Double, val lng: Double, val t: Long, val alpha: Float)

/** Ring of recent positions; alpha fades 1.0 (newest) to 0.15 (oldest kept). */
class Trail(private val maxAgeMs: Long) {
    private val pts = ArrayDeque<Triple<Double, Double, Long>>()
    fun add(lat: Double, lng: Double, t: Long) { pts.addLast(Triple(lat, lng, t)) }
    fun points(now: Long): List<TrailPoint> {
        while (pts.isNotEmpty() && now - pts.first().third > maxAgeMs) pts.removeFirst()
        val n = pts.size
        return pts.mapIndexed { i, (lat, lng, t) ->
            val frac = if (n <= 1) 1f else i.toFloat() / (n - 1)      // 0 oldest .. 1 newest
            TrailPoint(lat, lng, t, 0.15f + 0.85f * frac)
        }
    }
}
```

- [ ] **Step 4: Run, confirm pass** (6 tests). Then commit:
```bash
git add app/src/main/java/com/eried/eucplanet/share/ShareModel.kt app/src/test/java/com/eried/eucplanet/share/ShareModelTest.kt
git commit -m "feat(share): payload codec, peer palette, staleness, fading trail (pure, tested)"
```

---

### Task 5: `ShareSettings` + `ShareSession` (WebSocket, publish, peers, identity)

**Files (app repo):**
- Modify: `app/src/main/java/com/eried/eucplanet/data/model/AppSettings.kt` (add `val share: ShareSettings = ShareSettings()` next to `mediaControl` ~L119; define `ShareSettings` near `MediaControlSettings`)
- Modify: `app/src/main/java/com/eried/eucplanet/data/store/SettingsJson.kt` (nested write after the `mediaControl` block ~L128; read after ~L475)
- Modify: `app/src/test/java/com/eried/eucplanet/data/AppSettingsArgLimitTest.kt` (`expectedSlots` 248 -> 249 + comment)
- Create: `app/src/test/java/com/eried/eucplanet/data/SettingsJsonShareTest.kt`
- Create: `app/src/main/java/com/eried/eucplanet/share/ShareSession.kt`
- Modify: `app/src/main/java/com/eried/eucplanet/service/WheelService.kt` (drive publishing; stop on Stop-all)

**Interfaces:**
- Produces: `data class ShareSettings(val trailMinutes: Int = 5, val shareStatsDefault: Boolean = true, val lastIdentityMode: String = "ANON", val lastSessionName: String = "", val relayUrl: String = "wss://eucshare.ried.no")`.
- Produces `@Singleton class ShareSession @Inject constructor(settingsRepository, tripRepository, wheelRepository, syncManager, eucStatsApi: EucStatsApiContract)` with: `val state: StateFlow<ShareState>` where `sealed class ShareState { object Idle; data class Joined(val link: ShareLink, val me: Identity, val peers: Map<String, PeerState>, val connected: Boolean) }`; `data class Identity(val mode: IdentityMode, val name: String, val color: String, val icon: String?, val avatarUrl: String?, val flag: String?, val shareStats: Boolean)`; `data class PeerState(val last: SharePayload, val lastSeenMs: Long, val trail: Trail, val freshness: Freshness, val left: Boolean)`; `suspend fun start(identity: Identity): ShareLink` (new room), `suspend fun join(link: ShareLink, identity: Identity)`, `fun leave()`, `fun publishTick()` (called by WheelService each tick; rate-limits itself to 3 s / >10 m), `fun resolveDefaultIdentity(): Identity` (ANON -> `Rider #NNNN`; SESSION -> last name + navigator accent; PROFILE -> eucstats displayName/flag/avatar if `readRiderIdFile()` non-null).

- [ ] **Step 1: Add `ShareSettings` + serialization + tripwire, TDD.** Write `SettingsJsonShareTest.kt`:

```kotlin
package com.eried.eucplanet.data

import com.eried.eucplanet.data.model.AppSettings
import com.eried.eucplanet.data.model.ShareSettings
import com.eried.eucplanet.data.store.SettingsJson
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsJsonShareTest {
    @Test fun shareSettingsRoundTrip() {
        val s = AppSettings().copy(share = ShareSettings(trailMinutes = 12, shareStatsDefault = false,
            lastIdentityMode = "PROFILE", lastSessionName = "Erwin", relayUrl = "wss://x.example"))
        val back = SettingsJson.fromJson(JSONObject(SettingsJson.toJson(s).toString()))
        assertEquals(s.share, back.share)
    }
    @Test fun shareSettingsDefaultsOnEmptyJson() {
        assertEquals(ShareSettings(), SettingsJson.fromJson(JSONObject("{}")).share)
    }
}
```
Run `./gradlew.bat :app:testDebugUnitTest --tests "com.eried.eucplanet.data.SettingsJsonShareTest"` -> FAIL. Then add to `AppSettings.kt` (after the `mediaControl` line):
```kotlin
    // Live location share (navigator Share button) - see ShareSettings. Nested: one arg slot.
    val share: ShareSettings = ShareSettings(),
```
and near `MediaControlSettings`:
```kotlin
/** Live location share. Feature-local, nested so AppSettings.copy() stays under the dex 255-arg limit. */
data class ShareSettings(
    val trailMinutes: Int = 5,                // 1..30, how long a friend's fading trail is
    val shareStatsDefault: Boolean = true,    // default for the "Share my stats" toggle
    val lastIdentityMode: String = "ANON",    // ANON | SESSION | PROFILE, remembered per rider
    val lastSessionName: String = "",
    val relayUrl: String = "wss://eucshare.ried.no",
)
```
`SettingsJson.toJson` (after the `mediaControl` `put(...)` block):
```kotlin
        put("share", JSONObject().apply {
            put("trailMinutes", s.share.trailMinutes); put("shareStatsDefault", s.share.shareStatsDefault)
            put("lastIdentityMode", s.share.lastIdentityMode); put("lastSessionName", s.share.lastSessionName)
            put("relayUrl", s.share.relayUrl)
        })
```
`fromJson` (after the `mediaControl = ...` entry):
```kotlin
        share = j.optJSONObject("share")?.let { m -> base.share.copy(
            trailMinutes = m.optInt("trailMinutes", base.share.trailMinutes).coerceIn(1, 30),
            shareStatsDefault = m.optBoolean("shareStatsDefault", base.share.shareStatsDefault),
            lastIdentityMode = m.optString("lastIdentityMode", base.share.lastIdentityMode),
            lastSessionName = m.optString("lastSessionName", base.share.lastSessionName),
            relayUrl = m.optString("relayUrl", base.share.relayUrl),
        ) } ?: base.share,
```
Bump `AppSettingsArgLimitTest`: `val expectedSlots = 249` with a comment line `// 249: share - the nested live location share group (one slot). Nest the next addition too; 6 slots left.` Run the share test + `AppSettingsArgLimitTest` -> PASS. Commit: `git commit -am "feat(share): ShareSettings nested group, serialized + tripwire 249"` (plus `git add` the new test).

- [ ] **Step 2: Implement `ShareSession.kt`** (OkHttp WebSocket; all decisions delegate to the tested Task 3/4 objects):

```kotlin
package com.eried.eucplanet.share

import android.util.Log
import com.eried.eucplanet.data.repository.SettingsRepository
import com.eried.eucplanet.data.repository.TripRepository
import com.eried.eucplanet.data.repository.WheelRepository
import com.eried.eucplanet.data.sync.SyncManager
import com.eried.eucplanet.data.eucstats.EucStatsApiContract
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

data class Identity(val mode: IdentityMode, val name: String, val color: String, val icon: String?,
                    val avatarUrl: String?, val flag: String?, val shareStats: Boolean)

data class PeerState(val last: SharePayload, val lastSeenMs: Long, val trail: Trail,
                     val freshness: Freshness, val left: Boolean)

sealed class ShareState {
    object Idle : ShareState()
    data class Joined(val link: ShareLink, val me: Identity, val peers: Map<String, PeerState>,
                      val connected: Boolean, val error: String?) : ShareState()
}

@Singleton
class ShareSession @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val tripRepository: TripRepository,
    private val wheelRepository: WheelRepository,
    private val syncManager: SyncManager,
    private val eucStatsApi: EucStatsApiContract,
) {
    companion object {
        private const val TAG = "ShareSession"
        const val PUBLISH_INTERVAL_MS = 3_000L
        const val PUBLISH_MOVE_M = 10.0
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = OkHttpClient()
    private val _state = MutableStateFlow<ShareState>(ShareState.Idle)
    val state: StateFlow<ShareState> = _state.asStateFlow()

    private var ws: WebSocket? = null
    private var key: ByteArray? = null
    private val myId = ShareCrypto.b64u(ShareCrypto.randomBytes(8))
    private var lastPubMs = 0L
    private var lastLat = Double.NaN; private var lastLng = Double.NaN
    private var joinOrder = 0
    private val colorByPeer = HashMap<String, String>()
    private var closing = false

    suspend fun resolveDefaultIdentity(): Identity {
        val s = settingsRepository.get()
        val mode = runCatching { IdentityMode.valueOf(s.share.lastIdentityMode) }.getOrDefault(IdentityMode.ANON)
        return identityFor(mode, s.share.lastSessionName, s.share.shareStatsDefault)
    }

    suspend fun identityFor(mode: IdentityMode, sessionName: String, shareStats: Boolean): Identity {
        val s = settingsRepository.get()
        return when (mode) {
            IdentityMode.ANON -> Identity(mode, "Rider #${(1000..9999).random()}", PeerPalette.colorFor(0), null, null, null, shareStats)
            IdentityMode.SESSION -> Identity(mode, sessionName.ifBlank { "Rider" }, PeerPalette.colorFor(0), null, null, null, shareStats)
            IdentityMode.PROFILE -> {
                val storeId = syncManager.readRiderIdFile()
                val p = storeId?.let { runCatching { eucStatsApi.getProfile(it) }.getOrNull() }
                if (p == null) identityFor(IdentityMode.SESSION, sessionName, shareStats)
                else Identity(mode, p.displayName ?: "Rider", PeerPalette.colorFor(0), null, p.avatarUrl, p.flag, shareStats)
            }
        }
    }

    /** Create a new room and join it as the first rider. */
    suspend fun start(identity: Identity): ShareLink {
        val link = ShareLinks.newLink()
        join(link, identity)
        return link
    }

    suspend fun join(link: ShareLink, identity: Identity) {
        leave()
        closing = false
        key = ShareCrypto.deriveKey(link.key)
        settingsRepository.update { it.copy(share = it.share.copy(
            lastIdentityMode = identity.mode.name,
            lastSessionName = if (identity.mode == IdentityMode.SESSION) identity.name else it.share.lastSessionName)) }
        _state.value = ShareState.Joined(link, identity, emptyMap(), connected = false, error = null)
        connect(link)
    }

    private fun connect(link: ShareLink) {
        val relay = kotlinx.coroutines.runBlocking { settingsRepository.get().share.relayUrl }.trimEnd('/')
        val req = Request.Builder().url("$relay/ws/${link.roomId}").build()
        ws = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(w: WebSocket, r: okhttp3.Response) { update { it.copy(connected = true, error = null) }; lastPubMs = 0L; publishTick() }
            override fun onMessage(w: WebSocket, text: String) { onFrame(text) }
            override fun onFailure(w: WebSocket, t: Throwable, r: okhttp3.Response?) { update { it.copy(connected = false, error = t.message) }; scheduleReconnect(link) }
            override fun onClosed(w: WebSocket, code: Int, reason: String) { update { it.copy(connected = false) }; if (!closing) scheduleReconnect(link) }
        })
    }

    private fun scheduleReconnect(link: ShareLink) {
        if (closing) return
        scope.launch { delay(3_000); if (!closing && _state.value is ShareState.Joined) connect(link) }
    }

    private fun onFrame(text: String) {
        val j = runCatching { JSONObject(text) }.getOrNull() ?: return
        val now = System.currentTimeMillis()
        when {
            j.has("ct") -> {
                val from = j.optString("from"); if (from == myId) return
                val k = key ?: return
                val roomId = (state.value as? ShareState.Joined)?.link?.roomId ?: return
                val plain = runCatching { ShareCrypto.decrypt(k, roomId, ShareCrypto.unb64u(j.getString("ct"))) }.getOrNull() ?: return
                val p = SharePayload.fromJson(String(plain)) ?: return
                update { st ->
                    val prev = st.peers[from]
                    val trail = prev?.trail ?: Trail(trailMaxAgeMs())
                    trail.add(p.lat, p.lng, p.t)
                    val color = colorByPeer.getOrPut(from) { PeerPalette.colorFor(++joinOrder) }
                    st.copy(peers = st.peers + (from to PeerState(p.copy(color = color), now, trail, Freshness.FRESH, false)))
                }
            }
            j.optString("type") == "left" -> update { st -> st.copy(peers = st.peers.mapValues { (id, ps) -> if (id == j.optString("from")) ps.copy(left = true) else ps }) }
            j.optString("type") == "peers" -> {
                val seen = j.optJSONObject("seen") ?: return
                update { st -> st.copy(peers = st.peers.mapValues { (id, ps) ->
                    val ageMs = if (seen.has(id)) seen.optLong(id) * 1000L else (now - ps.lastSeenMs)
                    ps.copy(freshness = Staleness.of(maxOf(ageMs, now - ps.lastSeenMs))) }) }
            }
        }
    }

    /** Re-classify freshness from local clock; call from a UI ticker every second. */
    fun ageTick() { val now = System.currentTimeMillis()
        update { st -> st.copy(peers = st.peers.mapValues { (_, ps) -> ps.copy(freshness = Staleness.of(now - ps.lastSeenMs)) }) } }

    /** Called every telemetry tick by WheelService. Publishes at most every 3 s or on >10 m. */
    fun publishTick() {
        val st = _state.value as? ShareState.Joined ?: return
        val sock = ws ?: return; if (!st.connected) return
        val loc = tripRepository.currentLocation.value ?: return
        val now = System.currentTimeMillis()
        val moved = if (lastLat.isNaN()) Double.MAX_VALUE else distanceM(lastLat, lastLng, loc.latitude, loc.longitude)
        if (now - lastPubMs < PUBLISH_INTERVAL_MS && moved < PUBLISH_MOVE_M) return
        val wd = wheelRepository.wheelData.value
        val stats = if (st.me.shareStats) ShareStats(wd.speed, wd.batteryPercent, wd.maxTemperature) else null
        val payload = SharePayload(myId, st.me.name, st.me.mode, st.me.color, st.me.icon, st.me.avatarUrl, st.me.flag,
            loc.latitude, loc.longitude, if (loc.hasBearing()) loc.bearing else null, now, stats)
        val k = key ?: return
        val ct = ShareCrypto.b64u(ShareCrypto.encrypt(k, st.link.roomId, payload.toJson().toByteArray()))
        sock.send(JSONObject().put("from", myId).put("ct", ct).toString())
        lastPubMs = now; lastLat = loc.latitude; lastLng = loc.longitude
    }

    fun leave() {
        closing = true
        ws?.send(JSONObject().put("type", "leave").put("from", myId).toString())
        ws?.close(1000, "leave"); ws = null; key = null
        colorByPeer.clear(); joinOrder = 0; lastLat = Double.NaN
        _state.value = ShareState.Idle
    }

    private fun trailMaxAgeMs(): Long = kotlinx.coroutines.runBlocking { settingsRepository.get().share.trailMinutes } * 60_000L
    private inline fun update(f: (ShareState.Joined) -> ShareState.Joined) { (_state.value as? ShareState.Joined)?.let { _state.value = f(it) } }
    private fun distanceM(a: Double, b: Double, c: Double, d: Double): Double {
        val r = FloatArray(1); android.location.Location.distanceBetween(a, b, c, d, r); return r[0].toDouble() }
}
```

- [ ] **Step 3: Drive it from `WheelService`.** In `WheelService.onCreate()`'s telemetry collector (the block that calls `automationManager.evaluate(settings)` ~L175 / search `automationManager.evaluate`), add `shareSession.publishTick()` right after it, with `@Inject lateinit var shareSession: ShareSession` declared with the other injected fields. In the Stop-all / service-destroy path (search `automationManager.resetMediaControl()` ~L466), add `shareSession.leave()`.

- [ ] **Step 4: Build.** `./gradlew.bat :app:assembleDebug` -> `BUILD SUCCESSFUL`. Also run `./gradlew.bat :app:testDebugUnitTest --tests "com.eried.eucplanet.share.*" --tests "com.eried.eucplanet.data.SettingsJson*"` -> PASS.

- [ ] **Step 5: Commit**
```bash
git add app/src/main/java/com/eried/eucplanet/share/ShareSession.kt app/src/main/java/com/eried/eucplanet/service/WheelService.kt
git commit -m "feat(share): ShareSession - relay WebSocket, E2E publish/receive, peers, identity, driven by WheelService"
```

---

### Task 6: Navigator UI - Share button, share dialog, group view, friend markers + trails, App Link

**Files (app repo):**
- Create: `app/src/main/java/com/eried/eucplanet/ui/navigator/ShareDialog.kt` (dialog + group view composables)
- Modify: `app/src/main/java/com/eried/eucplanet/ui/navigator/RouteBuilderScreen.kt` (Share button near the `MoreVert` menu ~L680; push peers to the map; handle pending join link)
- Modify: `app/src/main/java/com/eried/eucplanet/ui/navigator/RouteBuilderViewModel.kt` (expose `shareSession.state`, `startShare/joinShare/leaveShare`, `pendingJoinLink`)
- Modify: `app/src/main/java/com/eried/eucplanet/ui/navigator/MapHtml.kt` (add `nativeSetPeers(json)` + `nativeFlyTo(lat,lng)` JS)
- Modify: `app/src/main/AndroidManifest.xml` (App Link intent-filter on MainActivity)
- Modify: `app/src/main/java/com/eried/eucplanet/MainActivity.kt` (route an incoming `https://eucplanet.ried.no/share#...` intent to the navigator with the link)
- Modify: `app/src/main/res/values/strings.xml` + all locales (Task 9 localizes; this task adds base English)

**Interfaces:**
- Consumes: `ShareSession` (Task 5), `ShareLinks` (Task 3), `PeerState/Freshness/Trail` (Task 4), existing `QrCodeImage(content, sizeDp)`.
- Produces: JS `window.nativeSetPeers(jsonArray)` where each item is `{id,name,color,lat,lng,heading,freshness:"FRESH|STALE|LOST",ageS,avatarUrl,flag,trail:[[lat,lng,alpha],...]}`; `window.nativeFlyTo(lat,lng)`.

- [ ] **Step 1: Map JS** - in `MapHtml.kt`, next to `window.nativeSetUser` (~L1589), add:

```javascript
  // --- Live location share: friends' markers + fading trails -------------
  var peerLayer = L.layerGroup().addTo(map);
  var peerMarkers = {};
  window.nativeSetPeers = function(json){
    var peers = JSON.parse(json); var keep = {};
    peerLayer.clearLayers();
    peers.forEach(function(p){
      keep[p.id] = true;
      // Trail: one polyline segment per hop so each hop gets its own alpha.
      for (var i = 1; i < p.trail.length; i++) {
        L.polyline([[p.trail[i-1][0], p.trail[i-1][1]], [p.trail[i][0], p.trail[i][1]]],
          {color: p.color, weight: 4, opacity: p.trail[i][2] * (p.freshness === 'LOST' ? 0.35 : 1)}).addTo(peerLayer);
      }
      var dim = p.freshness === 'FRESH' ? 1 : (p.freshness === 'STALE' ? 0.5 : 0.3);
      var bg = p.freshness === 'LOST' ? '#9E9E9E' : p.color;
      var inner = p.avatarUrl ? '<img src="'+p.avatarUrl+'" style="width:26px;height:26px;border-radius:50%;object-fit:cover">'
                              : '<span>'+(p.flag || '')+'</span>';
      var age = p.freshness === 'FRESH' ? '' : (p.freshness === 'LOST' ? 'lost' : p.ageS + 's');
      var html = '<div class="peer" style="opacity:'+dim+'"><div class="peer-dot" style="background:'+bg+'">'+inner+'</div>'
               + '<div class="peer-name">'+p.name+(age ? ' <i>'+age+'</i>' : '')+'</div></div>';
      L.marker([p.lat, p.lng], {icon: L.divIcon({className:'', html: html, iconSize:[30,30], iconAnchor:[15,15]})}).addTo(peerLayer);
    });
  };
  window.nativeFlyTo = function(lat, lng){ map.flyTo([lat, lng], Math.max(map.getZoom(), 15)); };
```
and CSS in the page's `<style>`: `.peer{display:flex;flex-direction:column;align-items:center}.peer-dot{width:30px;height:30px;border-radius:50%;border:2px solid #fff;box-shadow:0 1px 4px rgba(0,0,0,.5);display:flex;align-items:center;justify-content:center;font-size:14px;overflow:hidden}.peer-name{font:600 11px sans-serif;color:#fff;background:rgba(0,0,0,.55);padding:1px 5px;border-radius:6px;margin-top:2px;white-space:nowrap}.peer-name i{font-style:normal;opacity:.8}`

- [ ] **Step 2: ViewModel.** In `RouteBuilderViewModel` inject `ShareSession` and expose:
```kotlin
    val shareState: StateFlow<ShareState> = shareSession.state
    private val _pendingJoin = MutableStateFlow<ShareLink?>(null)
    val pendingJoin: StateFlow<ShareLink?> = _pendingJoin.asStateFlow()
    fun offerJoin(link: ShareLink) { _pendingJoin.value = link }
    fun dismissJoin() { _pendingJoin.value = null }
    suspend fun defaultIdentity() = shareSession.resolveDefaultIdentity()
    suspend fun identityFor(mode: IdentityMode, name: String, stats: Boolean) = shareSession.identityFor(mode, name, stats)
    fun startShare(identity: Identity) = viewModelScope.launch { shareSession.start(identity) }
    fun joinShare(link: ShareLink, identity: Identity) = viewModelScope.launch { shareSession.join(link, identity); _pendingJoin.value = null }
    fun leaveShare() = shareSession.leave()
    fun ageTick() = shareSession.ageTick()
```

- [ ] **Step 3: `ShareDialog.kt`** - two composables. `ShareStartDialog(default: Identity, hasProfile: Boolean, onStart: (Identity) -> Unit, onDismiss)`: a `Dialog(properties = DialogProperties(dismissOnClickOutside = false))` with a 3-way segmented choice (Anonymous / This session / Profile - Profile enabled only if `hasProfile`). `SegmentedChoice` is `internal fun` in `ui/settings/SettingsScreen.kt` ~L9983 (internal = module-visible, so it IS callable from `ui.navigator`); reuse it, a name `OutlinedTextField` shown for SESSION, a "Share my stats" `Switch` (`themedSwitchColors()`), and a `Start sharing` button. `ShareGroupDialog(state: ShareState.Joined, onFlyTo: (Double, Double) -> Unit, onLeave, onDismiss)`: `QrCodeImage(ShareLinks.format(state.link), 200)`, the link text with Copy (`ClipboardManager`) and Share (ACTION_SEND chooser) buttons, a connection line ("Connected" / "Reconnecting..."), then a `LazyColumn` of peers: colored dot or avatar, name + flag, age text by `freshness`, stats line if present (`"%.0f km/h · %d%% · %.0f°C"` using `Units.effectiveSpeedUnit`), row `clickable { onFlyTo(p.last.lat, p.last.lng); onDismiss() }`; peers with `left == true` shown greyed with "left"; a `Leave` button at the bottom. All colors from `MaterialTheme.appColors`, all text from string resources (base keys below).

- [ ] **Step 4: Wire the button + map push in `RouteBuilderScreen.kt`.** Next to the `MoreVert` IconButton (~L680) add an `IconButton` with `Icons.Default.Share`; if `shareState is Joined` overlay a small `Badge` with `peers.size`. Tapping opens `ShareStartDialog` when Idle, `ShareGroupDialog` when Joined. A `LaunchedEffect(shareState)` serializes `peers` into the `nativeSetPeers` JSON (trail via `trail.points(now)`, `ageS = (now - lastSeenMs)/1000`) and calls `wv.evaluateJavascript("nativeSetPeers(${JSONObject.quote(json)});", null)`; a `LaunchedEffect(Unit) { while(true) { delay(1000); viewModel.ageTick() } }` while Joined. `onFlyTo` calls `wv.evaluateJavascript("nativeFlyTo($lat,$lng);", null)`. When `pendingJoin != null`, show `ShareStartDialog` titled "Join group" whose Start calls `joinShare(link, identity)`; if already Joined to a different room first show a confirm "Leave current group and join this one?".

- [ ] **Step 5: App Link.** In `AndroidManifest.xml`, inside MainActivity, after the `geo` filter:
```xml
            <!-- Live location share link (also served as a web page for riders
                 without the app). autoVerify needs /.well-known/assetlinks.json
                 on eucplanet.ried.no (Task 7). -->
            <intent-filter android:autoVerify="true">
                <action android:name="android.intent.action.VIEW" />
                <category android:name="android.intent.category.DEFAULT" />
                <category android:name="android.intent.category.BROWSABLE" />
                <data android:scheme="https" android:host="eucplanet.ried.no" android:path="/share" />
            </intent-filter>
```
In `MainActivity`, where other incoming intents (geo / SEND) are routed to the navigator, add: if `intent.data?.toString()` starts with `ShareLinks.BASE`, parse with `ShareLinks.parse(...)` (the fragment IS present in `intent.data`), navigate to the navigator, and call `routeBuilderViewModel.offerJoin(link)`; if parse fails show the snackbar `share_link_invalid`. Handle both `onCreate` and `onNewIntent` (launchMode is singleTask).

- [ ] **Step 6: Base strings** (`values/strings.xml`, add near the `nav_` block):
```xml
    <string name="share_button">Share my ride</string>
    <string name="share_title">Share my ride</string>
    <string name="share_join_title">Join group</string>
    <string name="share_identity_anon">Anonymous</string>
    <string name="share_identity_session">This session</string>
    <string name="share_identity_profile">Profile</string>
    <string name="share_name_label">Name</string>
    <string name="share_stats_toggle">Share my stats</string>
    <string name="share_start">Start sharing</string>
    <string name="share_copy_link">Copy link</string>
    <string name="share_send_link">Send link</string>
    <string name="share_connected">Connected</string>
    <string name="share_reconnecting">Reconnecting…</string>
    <string name="share_riders">Riders</string>
    <string name="share_leave">Leave</string>
    <string name="share_left">left</string>
    <string name="share_lost">lost</string>
    <string name="share_age_seconds">%1$d s ago</string>
    <string name="share_link_invalid">This share link has expired or is invalid.</string>
    <string name="share_room_full">This group is full.</string>
    <string name="share_switch_group">Leave current group and join this one?</string>
    <string name="share_cannot_reach">Cannot reach the share service</string>
```

- [ ] **Step 7: Build** `./gradlew.bat :app:assembleDebug` -> `BUILD SUCCESSFUL`. Commit:
```bash
git add -A app/src/main/java/com/eried/eucplanet/ui/navigator/ app/src/main/AndroidManifest.xml app/src/main/java/com/eried/eucplanet/MainActivity.kt app/src/main/res/values/strings.xml
git commit -m "feat(share): navigator Share button, share/group dialogs, friend markers + trails, App Link"
```

---

### Task 7: Web viewer on GitHub Pages + `assetlinks.json`

**Files (app repo, `docs/` = Pages site):**
- Create: `docs/share.html` (single self-contained page: MapLibre GL from CDN, WebCrypto, WebSocket)
- Create: `docs/.well-known/assetlinks.json`
- Create: `docs/share-test/` Playwright spec is in Task 10; here only the page.

**Interfaces:** Consumes the contract (Global Constraints) byte-for-byte: HKDF params, `nonce||ct` with aad=roomId, palette, thresholds, payload v:1, control frames. Watch-only: never sends.

- [ ] **Step 1: `assetlinks.json`** - both fingerprints were read from the real keystores (debug `~/.android/debug.keystore`; upload `D:/GitHub/eucplanet/upload.keystore`). Use them verbatim:
```json
[{"relation":["delegate_permission/common.handle_all_urls"],
  "target":{"namespace":"android_app","package_name":"com.eried.eucplanet",
            "sha256_cert_fingerprints":[
              "C7:B3:D7:BF:F3:DC:3F:11:60:16:B5:D6:57:3D:D3:AE:5F:5F:A9:7D:55:7B:1B:0B:86:74:3C:A9:A8:B4:24:A2",
              "A6:8B:8B:D4:1F:FB:C9:DC:45:D9:B4:38:69:8F:24:E7:90:67:69:AE:F0:8B:29:8E:0B:5F:46:F3:48:BA:86:AC"]}}]
```
The first is the **upload** key (sideloaded release builds), the second the **debug** key (emulator / debug builds, what Task 10 verifies). NOTE for the rider: if the app uses Play App Signing, Play re-signs production installs with Google's key; that SHA-256 (Play Console -> Setup -> App signing -> "App signing key certificate") must be ADDED as a third entry for App Links to verify on Play-installed builds. Not blocking Task 10.

- [ ] **Step 2: `docs/share.html`** - implement: read `location.hash` -> `roomId.key` (validate both 22-char base64url; else show "This share link has expired or is invalid."); derive AES key with `crypto.subtle.importKey('raw', rawKey, 'HKDF')` + `deriveKey({name:'HKDF', hash:'SHA-256', salt: utf8('eucshare-v1'), info: utf8('aes-256-gcm')}, ..., {name:'AES-GCM', length:256})`; open `wss://eucshare.ried.no/ws/<roomId>`; on `{from, ct}`: `crypto.subtle.decrypt({name:'AES-GCM', iv: blob.slice(0,12), additionalData: utf8(roomId)}, key, blob.slice(12))`, parse v:1 payload, assign palette color by join order, push into a trail (5 min), render a MapLibre marker (dot/avatar + name + age) and a fading trail line; every 1 s reclassify FRESH/STALE/LOST from `Date.now() - lastSeen` cross-checked with the `peers` frame; `left` greys the peer; a side list of peers with stats, clicking flies to them; on socket close show "Reconnecting" and retry every 3 s; never send anything. Include the same CSS look as the app markers. Use the palette and thresholds VERBATIM from Global Constraints. Add a hidden self-test: if `location.hash === '#selftest'`, run the Task 3 test vector (rawKey 0x01..0x10, roomId `AAAAAAAAAAAAAAAAAAAAAA`) - derive the key and decrypt a fixed blob produced by Kotlin (the implementer runs a one-off Kotlin/JUnit print to obtain the base64url blob for plaintext `"hi"` with nonce zeros, and pastes it into the page) and write `SELFTEST_OK` / `SELFTEST_FAIL` into `document.title`. This is what Playwright asserts in Task 10.

- [ ] **Step 3: Verify locally** by opening `docs/share.html#selftest` in a browser (or `npx playwright` in Task 10) -> title `SELFTEST_OK`. Commit:
```bash
git add docs/share.html docs/.well-known/assetlinks.json
git commit -m "feat(share): web viewer on Pages (watch-only, E2E decrypt) + assetlinks for the App Link"
```

---

### Task 8: Deploy the relay to the droplet (nginx + Certbot + systemd) and the deploy script

**Files (`eucshare` repo):** `scripts/deploy.sh`, `deploy/eucshare.service`, `deploy/nginx-eucshare.conf`

- [ ] **Step 1: Deploy artifacts** in the `eucshare` repo:

`deploy/eucshare.service`:
```ini
[Unit]
Description=eucshare (EUC Planet location-share relay)
After=network.target

[Service]
Type=simple
User=root
WorkingDirectory=/opt/eucshare
Environment="PATH=/opt/eucshare/.venv/bin"
Environment="EUCSHARE_ROOM_TTL_S=3600"
ExecStart=/opt/eucshare/.venv/bin/uvicorn main:app --host 127.0.0.1 --port 8006 --proxy-headers
Restart=always
RestartSec=5

[Install]
WantedBy=multi-user.target
```
`deploy/nginx-eucshare.conf` (pre-Certbot; Certbot adds the TLS block):
```nginx
server {
    listen 80;
    server_name eucshare.ried.no;
    location / {
        proxy_pass http://127.0.0.1:8006;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_read_timeout 3600;
    }
}
```
`scripts/deploy.sh` (copy eucstats' script; set `HOST=root@64.227.89.199`, `APP=/opt/eucshare`, `SERVICE=eucshare`, `URL=https://eucshare.ried.no`, verify `/health` returns 200 and `"ok":true`; first-run bootstrap: `python3 -m venv .venv` if missing).

- [ ] **Step 2: One-time server setup** (over ssh, key auth works):
```bash
ssh root@64.227.89.199 'mkdir -p /opt/eucshare && cd /opt/eucshare && python3 -m venv .venv'
cd /d/GitHub/eucshare && git add -A && git commit -qm "deploy: systemd unit, nginx site, deploy script" && git push -q
./scripts/deploy.sh          # syncs tree, pip installs, restarts (service not yet enabled -> next lines)
ssh root@64.227.89.199 'cp /opt/eucshare/deploy/eucshare.service /etc/systemd/system/ && systemctl daemon-reload && systemctl enable --now eucshare && cp /opt/eucshare/deploy/nginx-eucshare.conf /etc/nginx/sites-available/eucshare.ried.no && ln -sf /etc/nginx/sites-available/eucshare.ried.no /etc/nginx/sites-enabled/ && nginx -t && systemctl reload nginx && certbot --nginx -d eucshare.ried.no --non-interactive --agree-tos -m erwin@ried.cl --redirect && systemctl is-active eucshare'
```
Expected: `active`; then `curl -s https://eucshare.ried.no/health` -> `{"ok":true,"rooms":0}`.

- [ ] **Step 3: Smoke the WebSocket end to end from the local machine** with a 10-line Python script (`websockets` or the FastAPI TestClient against the live URL): connect two clients to one room over `wss://`, send an envelope from one, assert the other receives it. Record the output in the commit message. Commit the script as `scripts/smoke_ws.py`.

---

### Task 9: Localize the share strings to all locales

**Files:** `app/src/main/res/values-*/strings.xml` (22 locales: b+es+419 cs da de es fi fr hu it ja ko nl no pl pt-rBR ro ru sv tr uk zh-rTW zh)

- [ ] **Step 1:** Write a Python script (UTF-8 file writes, ASCII `print`) with a dict `{locale: {key: translation}}` for ALL 22 keys from Task 6 Step 6 in ALL 22 locales (real translations, `%1$d` preserved, apostrophes escaped `\'`, no em-dashes; the `…` in `share_reconnecting` is fine as a literal ellipsis character). Insert after `nav_end_title` in each locale (idempotent: skip a locale that already has `share_button`).
- [ ] **Step 2:** Verify every locale has each of the 22 keys exactly once and no unescaped apostrophe; `./gradlew.bat :app:assembleDebug` -> `BUILD SUCCESSFUL` (AAPT2 validates).
- [ ] **Step 3:** Commit `i18n(share): localize location-share strings to all locales`.

---

### Task 10: End-to-end verification (2 emulators + Playwright web viewer) with screenshots + conclusions

**Files:** `docs/share-test/share.spec.ts` (Playwright), scratch screenshots, a conclusions markdown handed to the rider (not committed to the app repo).

- [ ] **Step 1: Playwright web viewer test** (`npx playwright test`, chromium): (a) open `https://eucplanet.ried.no/share#selftest` -> title `SELFTEST_OK` (crypto interop with Kotlin); (b) open a real link produced by an emulator (Step 3) -> within 10 s a peer marker + name renders, screenshot; (c) stop the emulator's app -> within 130 s the peer shows `lost`, screenshot; (d) open a garbage link -> the expired/invalid message.
- [ ] **Step 2: Build + install** the feature APK on two emulators (`emulator-5556` host, another as joiner). Enable mock/demo GPS movement on both (the app has a demo wheel; set distinct mock locations via `adb emu geo fix`).
- [ ] **Step 3: Host flow** on emulator A: Navigator -> Share -> choose identity -> Start sharing; screenshot the QR/link dialog; copy the link text via `adb shell uiautomator dump` or a debug log line.
- [ ] **Step 4: Joiner flow** on emulator B: `adb shell am start -a android.intent.action.VIEW -d "<link>"` -> the app opens (App Link) -> identity picker -> join; screenshot both maps showing each other + trails after moving `geo fix` a few times; screenshot the group view peer list with stats; tap a peer row -> map flies; screenshot.
- [ ] **Step 5: Staleness**: stop B's app; on A screenshot the peer dimming to stale (15 s) then lost (2 min). **Leave**: B rejoins, taps Leave; A shows "left".
- [ ] **Step 6: TTL**: set `EUCSHARE_ROOM_TTL_S=60` on the server temporarily (edit the unit env, restart), disconnect both, wait 70 s, reconnect with the old link -> the viewer reports expired/empty; restore 3600.
- [ ] **Step 7: Write conclusions** (pass/fail per case, the screenshots, and any defects found) and hand them to the rider. Fix defects found via the normal loop before hand-off.
