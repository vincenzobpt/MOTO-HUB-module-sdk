# Paddock — design (v0.1)

Paddock is a MOTO-HUB module for riders who ride together. v0.1: a rider profile made on the
phone, a private room opened with a QR code, and everyone's live position on the NAV map.
Later: chat, a public room, bike profiles. No account, no MOTO-HUB server, nothing stored by
anyone but the riders' own phones. Transport: [Reticulum](https://reticulum.network) + LXMF,
run by CPython inside the module (Chaquopy runtime, started without the app's assets — see
`ModulePython.kt`). Wire-compatible with Columba and Sideband for positions.

## 1. Layers

```
UI (Compose, ui/)  ──►  PaddockController (engine/)  ──►  paddock_engine.py (Python, RNS+LXMF)
      ▲                     │  poll() every 1 s, JSON          │
      └── StateFlow<PaddockState> ◄────────────────────────────┘
RidersLayer (map/) reads the same state → NAV map pins (ModuleMapLayer, refreshSeconds = 5)
```

Python never calls back into Java: Kotlin polls. Every Python call runs on one Kotlin thread
("paddock-py"), the thread that started Python.

## 2. Python API (`src/main/python/paddock_engine.py`) — v0.2, many rooms

All functions take/return plain str/int/float/bool; structured values are JSON strings. A room
is a **permanent group** ("Sunday group", "Track days"): the rider keeps as many as they like,
some led, some joined. Each lives in `<storage>/rooms/<id>.json` (atomic write) and survives
restarts and module updates. **Positions go to one room at a time, only while riding with it**
(`start_ride`); every other room is quiet. A v0.1 `<storage>/room.json` is migrated once into
`rooms/` (and was riding if it was active).

| Function | Does |
|---|---|
| `start(storage, node_host, node_port, name, color, bike) -> json` | As v0.1 (stdout to `python.log`, `signal.signal` neutralised, stamps off, identity, LXMRouter), then loads every room in `rooms/` and the riding room id from `<storage>/ride.json`. Returns `{"hash": "…"}`. Idempotent. |
| `set_profile(name, color, bike)` | My roster entry changes: `hello` to the leader of every room I joined; as leader, a new roster to my rooms' members. |
| `create_room(name) -> json` | New room, me as leader. `{"ok": true, "id": …, "link": …}`. |
| `join_room(link) -> json` | Starts joining (state `joining`). `{"ok": true, "id": …}`, or `{"ok": false, "error": …}` (bad link, own room, already a member). |
| `rename_room(id, name) -> json` | Leader only: new name, roster to every member (they see the new name). |
| `leave_room(id)` | Member: `leave` to the leader, room forgotten. Leader: same as `delete_room`. |
| `delete_room(id)` | Leader: `close` to every member, room forgotten everywhere. |
| `start_ride(id) -> json` | Rides with that room (only an `active` one): from now on `send_position` goes to its members. Replaces any other riding room. Persisted. |
| `stop_ride()` | No room is riding; positions stop. |
| `send_position(lat, lon, alt, speed_mps, bearing, accuracy_m, fix_epoch_s)` | To every other member of the riding room. No-op when not riding. |
| `poll() -> json` | Whole state (§4) + events since last poll. Cheap, never blocks. Also runs timers: join retries, announces. |
| `remove_member(id, hash) -> json` | Leader only: the rider leaves the roster (new roster to everyone, `removed` to them). |
| `send_text(id, text) -> json` | Chat (§3b): to every other member; `{"ok": true, "msg": "<local id>"}`. Text ≤ 2000 chars. |
| `send_quick(id, kind, lat, lon) -> json` | A quick message (`stop`, `fuel`, `slow`, `wait`, `photo`, `spot`, `emergency`); `lat`/`lon` NaN when unknown (`spot` and `emergency` carry them). |
| `chat(id, before_ms, limit) -> json` | `{"messages": […]}` (§4b), newest last, at most `limit`, older than `before_ms` (0 = latest). |
| `mark_read(id)` | Everything in that room so far is read (unread count 0). |
| `set_node(host, port)`, `reset_identity()` | As v0.1; `reset_identity` leaves/deletes every room and every chat. |

Rooms are never left by a module update or an app restart: only `leave_room`/`delete_room` do it.

RNS config written by `start` (`<storage>/rns/config`):
`enable_transport = False`, `share_instance = No`, `panic_on_interface_error = No`, loglevel 3,
one `TCPClientInterface` to `node_host:node_port`. Default node: `rns.kin.earth:4242` (Columba's
default community node).

## 3. Wire protocol

All messages are LXMF, signed (Ed25519) and encrypted (X25519 ECDH + AES-256) by LXMF.
Paddock messages carry `FIELD_CUSTOM_TYPE (0xFB) = "paddock/1"` and
`FIELD_CUSTOM_DATA (0xFC) = msgpack dict` with `"t"` = message type and `"r"` = room id hex.
Messages from anyone not in the room's roster are ignored (except `join` to the leader). Every
message names its room (`"r"`), so one rider can be in many rooms with the same people.
Positions received from a room's members are kept for that room whether or not I ride with it
(they chose to share with that room); `pos` is accepted only from a member of the room it names.

**Room link** (also the QR text; shareable as text):
`paddock://join?v=2&r=<room id, 16 random bytes, base64url>&k=<leader public key, 64 bytes, base64url>&n=<room name, URL-encoded>`
(no padding; the leader's LXMF destination hash is derived from the key). v1 links
(`v=1&r=<hex>&d=<dest hex32>&k=<key hex128>&n=…`) are still accepted.
Possessing the link = invited. Show it only to people you ride with.

| `t` | From → to | Data | Method |
|---|---|---|---|
| `join` | joiner → leader | `name, color, bike, k` (joiner public key hex128) | DIRECT |
| `roster` | leader → every member | `n` room name, `m` = list of `{d, k, name, color, bike}` incl. leader, `l` leader dest | DIRECT |
| `hello` | member → leader | profile changed: `name, color, bike` | DIRECT |
| `leave` | member → leader | — | DIRECT |
| `close` | leader → every member | — | DIRECT |
| `pos` | member → every other member | `FIELD_TELEMETRY (0x02)` = Sideband Telemeter (time + location), packed exactly like Columba's `TelemeterCodec.kt` / Sideband's `sense.py` | OPPORTUNISTIC |

- Before the first message to someone: `RNS.Identity.remember(...)` their public key (we have it
  from the link or roster) and `RNS.Transport.request_path(dest)` if there is no path.
- Announces: the delivery destination is announced with **no app_data** (no name: only a random
  hash is public), at start, on create/join/start_ride, and every 10 min while the engine runs
  (a leader must stay reachable for riders who scan a QR later).
  Announces are not stamped (`RNS.Transport.STAMP_DESTINATIONS = False`): RNS 1.6's 2^25
  proof-of-work would hold a new rider's first announce for about a minute, and nobody could
  reach them meanwhile. 1.6 nodes accept unstamped announces; a relay that enforces stamps would not.
- Join: retry `join` every 15 s until a `roster` containing me arrives; after 90 s the `join` also goes to the mailbox and is retried DIRECT every 2 min, never given up on its own (the rider cancels). `roster` falls back to the mailbox when DIRECT fails. With any room, the engine starts with MOTO-HUB, and coming back to the front announces and reads the mailbox at once.
  The leader accepts anyone who sends the right room id.
- Freshness: a member's position is `fresh` ≤ 60 s old, `stale` ≤ 10 min, then hidden on the map.

## 3b. Chat

- Every chat message is one LXMF message per other member of the room (no group key, no
  server). Paddock fields: `t` = `msg` (`x` = text) or `quick` (`q` = kind, optional `la`/`lo`),
  `r` = room id, `i` = a random 8-byte id (hex) shared by all copies of one message, `s` = send
  time (epoch ms). LXMF `content` also carries the text, so any LXMF client shows something.
- Sent `DIRECT` with `try_propagation_on_fail`: when the rider is offline, the message goes to a
  **propagation node** (a public Reticulum store-and-forward mailbox) that holds it, encrypted for
  the recipient only, until they fetch it. Outbound propagation node: the nearest by hops among
  `lxmf.propagation` announces heard (`LXMRouter` keeps them), else none (then the message waits
  and LXMF retries). Inbound: `request_messages_from_propagation_node` at start, when a room opens
  (UI calls `mark_read`/`chat`) at most every 2 min, and every 10 min while running.
- Accepted only from a member of the named room, deduplicated by (`sender`, `i`). Stored per room
  in `<storage>/chat/<room id>.jsonl` (append; trimmed to the last 1000), deleted with the room.
- Delivery per recipient: `pending` → `delivered` (proof back), `mailbox` (handed to the propagation
  node) or `waiting`. A recipient still `pending` 45 s after sending (LXMF over a real relay keeps
  retrying for minutes) becomes `waiting`, and goes to the mailbox too if a node is known. `failed`
  only means the rider is no longer in the room. States persist in the sender's copy (`rc`); after a
  restart `pending` becomes `waiting`.
- Resend on return: whenever a valid Paddock message (any kind) or position arrives from a member,
  every message of ours in that room that is `waiting`/`failed` for them is sent again DIRECT
  (oldest first, ≤ 50, at most once per 30 s per member); all `waiting` recipients are also retried
  every 5 min. Duplicates are harmless (dedup by sender + `i`); a delivery proof turns it `delivered`.
- Rooms are not chat-only: positions keep their own path (§3). A `removed` rider drops the room.

## 4. State JSON (`poll()`)

```json
{
  "me": {"hash": "…", "online": true, "node": "rns.kin.earth:4242", "since": 1795254295},
  "riding": null | "<room id>",
  "rooms": [{
    "id": "…", "name": "Sunday group", "link": "paddock://join?…",
    "state": "joining" | "active", "iAmLeader": true, "leader": "<hash>",
    "createdAt": 1795254000, "lastActivity": 1795254296,
    "unread": 2,
    "last": null | {"from": "<hash>", "name": "Luca", "kind": "text" | "quick", "text": "Fuel", "at": 1795254296000},
    "members": [{"hash": "…", "name": "Vincenzo", "color": "#FF7A1A", "bike": "CFMOTO 800MT",
                 "isMe": false, "isLeader": true,
                 "lat": 45.1, "lon": 9.2, "speed": 12.5, "bearing": 270.0, "fixAt": 1795254295,
                 "heardAt": 1795254296}]
  }],
  "events": [{"type": "room_joined" | "member_joined" | "member_left" | "room_closed" |
              "join_failed" | "room_renamed" | "error", "room": "<id>", "text": "…"}]
}
```
`lat/lon/fixAt/heardAt` are `null` until known. `online` = the TCP interface is up.
`lastActivity` = the latest of creation, a roster change, a position heard or a chat message (epoch s).

## 4b. Chat JSON (`chat()`)

```json
{"messages": [{"id": "<i>", "from": "<hash>", "name": "Marco", "color": "#33C7FF", "mine": false,
  "kind": "text" | "quick" | "event", "text": "Coffee at the pass?", "quick": null | "fuel",
  "lat": null, "lon": null, "at": 1795254296000,
  "delivery": null | {"total": 3, "delivered": 1, "mailbox": 1, "waiting": 1, "failed": 0}}]}
```
`event` = a room event shown in the chat ("Luca joined the room"), from the engine's own events.
Rooms are listed newest `lastActivity` first.

## 5. Kotlin

- `engine/PaddockController.kt` — the one object the UI talks to (`state`, `profile`, actions).
- `engine/ModulePython.kt` — boots Python from the module's own files.
- `map/RidersLayer.kt` — `ModuleMapLayer`, title "Paddock riders", `refreshSeconds = 5`.
- `qr/` — `QrImage` (ZXing encode) and `QrScanner` (Camera2 + ZXing decode).
- `ui/` — screens. Fixed palette (not the rider's theme), icons drawn in code (a module has no
  resources), one function per screen, every stage explained in one or two plain sentences.
- Positions: `host.places.livePosition()` every 10 s while a room is open → `send_position`.
- `host.keepAlive("Paddock: riding with your room")` from the create/join tap, closed on leave/close.

## 6. Privacy — what we say, and why it is true

- No account, no email, no phone number: the rider ID is a key pair made on the phone.
- MOTO-HUB runs no server for Paddock and receives nothing.
- Names, bikes and positions only travel inside encrypted LXMF messages to the room's members.
- The public Reticulum relay forwards encrypted packets it cannot read. It does see the phone's
  IP address and that some traffic flows, like any internet service — the privacy page says so.
- When a room closes, its names, bikes and positions are deleted. Reticulum's routing cache keeps
  the room-mates' addresses (destination hash + public key, no names) until "Forget my rider ID", which also
  deletes that cache.
- Chat messages a rider could not receive wait on a public propagation node, encrypted so that
  only that rider can read them; the node sees who it is for, the size and when, not the text.

## Credits
See `NOTICE.md`.
