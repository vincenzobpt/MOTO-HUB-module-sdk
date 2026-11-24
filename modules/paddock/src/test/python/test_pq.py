# SPDX-License-Identifier: MPL-2.0
# Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
# This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
# of the MPL was not distributed with this file, you can obtain one at https://mozilla.org/MPL/2.0/.
"""Self-check of the post-quantum room key (DESIGN.md 2c).

    PYTHONPATH=<site-packages with rns 1.6.1 + lxmf 1.3.1> python3 -I test_pq.py
"""
import os
import sys
import types
import urllib.parse

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "../../main/python"))
for p in os.environ.get("PYTHONPATH", "").split(os.pathsep):
    if p and p not in sys.path:
        sys.path.append(p)
import RNS  # noqa: E402
import LXMF  # noqa: E402
import paddock_engine as E  # noqa: E402

E._g.update(RNS=RNS, LXMF=LXMF)
me, other = RNS.Identity(), RNS.Identity()
E._g["my_hash"] = RNS.Destination.hash(me, "lxmf", "delivery").hex()
E._g["key"] = me.get_public_key().hex()
E._g["profile"] = {"name": "Me", "color": "#EDEFF2", "bike": ""}
other_hash = RNS.Destination.hash(other, "lxmf", "delivery").hex()


def room_with(pq):
    rid = E._rid_of_secret(pq)
    members = {E._g["my_hash"]: E._member(E._g["my_hash"], E._g["key"], "Me", None, ""),
               other_hash: E._member(other_hash, other.get_public_key().hex(), "Other", None, "")}
    r = {"id": rid, "pq": pq, "name": "Test", "leader": E._g["my_hash"], "iAmLeader": True, "state": "active",
         "members": members, "chat": [], "idx": {}, "rx": 0, "readRx": 0, "lastActivity": 0, "createdAt": 0}
    E._g["rooms"][rid] = r
    return r


secret = os.urandom(32).hex()
room = room_with(secret)

# 1. A sealed body opens with the room key, telemetry bytes included.
blob = E._seal(room, {"b": {"t": "pos"}, "f": {LXMF.FIELD_TELEMETRY: b"\x01\x02\xff"}})
inner = E._open(room, blob)
assert inner["b"] == {"t": "pos"} and inner["f"][LXMF.FIELD_TELEMETRY] == b"\x01\x02\xff", inner

# 2. Wrong key, tampered bytes, another room: refused.
assert E._open(room_with(os.urandom(32).hex()), blob) is None
bad = bytearray(blob); bad[20] ^= 1
assert E._open(room, bytes(bad)) is None
twin = dict(room, id="0" * 32, tok=None)
assert E._open(twin, blob) is None

# 3. The v3 link carries the secret (not the id) and the id follows from it.
link = E._make_link(room["id"], E._g["key"], "Ride", secret)
q = dict(urllib.parse.parse_qsl(urllib.parse.urlparse(link).query))
assert q["v"] == "3" and "r" not in q and E._unb64u(q["s"], 32) == secret, link
assert E._make_link(room["id"], E._g["key"], "Ride").startswith("paddock://join?v=2&r="), "classic rooms keep v2"


# 4. A plain message in a post-quantum room is dropped; a sealed one is read.
class Msg:
    def __init__(self, fields):
        self.fields, self.source_hash, self.signature_validated = fields, bytes.fromhex(other_hash), True
        self.unverified_reason = None


E._g["pend"], E._g["resent"] = {}, {}
E._resend = lambda r, h: None
E._save_room = lambda r: None
E._chat_add = lambda r, e: r["chat"].append(e)
plain = {"t": "msg", "r": room["id"], "i": "a" * 16, "s": 1, "x": "plain"}
E._handle(Msg({LXMF.FIELD_CUSTOM_TYPE: E.APP_TYPE, LXMF.FIELD_CUSTOM_DATA: plain}))
assert room["chat"] == [], "plain message accepted"
sealed = {"t": "e", "r": room["id"], "e": E._seal(room, {"b": dict(plain, x="sealed"), "f": {}})}
E._handle(Msg({LXMF.FIELD_CUSTOM_TYPE: E.APP_TYPE, LXMF.FIELD_CUSTOM_DATA: sealed}))
assert [e["text"] for e in room["chat"]] == ["sealed"], room["chat"]

# 5. A saved room keeps its secret; a damaged secret drops the room instead of going plain.
saved = {"id": room["id"], "name": "Test", "link": link, "leader": E._g["my_hash"], "iAmLeader": True,
         "state": "active", "pq": secret, "members": [
             {"hash": h, "key": m["key"], "name": m["name"], "color": m["color"], "bike": ""}
             for h, m in room["members"].items()]}
E._chat_load = lambda r: None
assert E._load_room(saved)["pq"] == secret
assert E._load_room(dict(saved, pq=os.urandom(32).hex())) is None

# 6. A sealed position arrives (int LXMF field keys inside the envelope) and fits one packet.
import time  # noqa: E402
import paddock_telemeter  # noqa: E402
E._g["tele"] = paddock_telemeter
now = int(time.time())
tele = paddock_telemeter.pack_location(45.4642, 9.19, 120.0, 22.5, 90.0, 4.0, now)
body = {"t": "e", "r": room["id"], "e": E._seal(room, {"b": {"t": "pos"}, "f": {LXMF.FIELD_TELEMETRY: tele}})}
fields = {LXMF.FIELD_CUSTOM_TYPE: E.APP_TYPE, LXMF.FIELD_CUSTOM_DATA: body}
E._handle(Msg(fields))
o = room["members"][other_hash]
assert abs(o["lat"] - 45.4642) < 1e-5 and abs(o["lon"] - 9.19) < 1e-5, o
msgpack = E._msgpack()
plain_fields = {LXMF.FIELD_CUSTOM_TYPE: E.APP_TYPE, LXMF.FIELD_CUSTOM_DATA: {"t": "pos", "r": room["id"]},
                LXMF.FIELD_TELEMETRY: tele}
size = lambda f: len(msgpack.packb([time.time(), b"", b"", f]))
limit = LXMF.LXMessage.ENCRYPTED_PACKET_MAX_CONTENT
print("pos payload: plain %d, sealed %d bytes, single-packet limit %d" % (size(plain_fields), size(fields), limit))
assert size(fields) <= limit - 16, "sealed pos no longer fits one opportunistic packet"

# 7. Sync hand-over (push wake): sealed entries, ids outside, read back as chat.
room["chat"] = [{"id": "b" * 16, "from": E._g["my_hash"], "name": "Me", "color": "#fff", "kind": "text",
                 "text": "via sync", "quick": None, "lat": None, "lon": None, "at": 2, "rx": 1, "mine": True,
                 "rc": {other_hash: "pending"}}]
handed = E._sync_pending(other.hash.hex(), room["id"])
assert handed and handed[0]["i"] == "b" * 16 and set(handed[0]) == {"i", "e"}, handed
room["chat"], room["idx"] = [], {}
E._on_chat_real = E._on_chat
got = []
E._on_chat = lambda r, snd, src, kind, b: got.append((src, kind, b.get("x")))
E._sync_ingest(other.get_public_key().hex(), room["id"], handed)
assert got == [(other_hash, "msg", "via sync")], got
E._sync_ingest(other.get_public_key().hex(), room["id"], [{"t": "msg", "i": "c" * 16, "s": 1, "x": "plain"}])
assert len(got) == 1, "plain sync entry accepted in a post-quantum room"
E._on_chat = E._on_chat_real

# 8. create_room: post-quantum by default, v2 when classic; join_room reads the v3 link.
E._g.update(started=True, node="Automatic", push_token="", rooms={})
E._live_relays = lambda: [("relay.one", 4242), ("relay.two", 4242)]
for stub in ("_announce", "_save_room", "_send_join", "_ensure_relays"):
    setattr(E, stub, lambda *a, **k: None)
import json  # noqa: E402
made = json.loads(E.create_room("Sunday"))
r = E._g["rooms"][made["id"]]
assert r.get("pq") and E._rid_of_secret(r["pq"]) == r["id"] and "v=3" in made["link"], made
made = json.loads(E.create_room("Old friends", True))
assert not E._g["rooms"][made["id"]].get("pq") and "v=2" in made["link"], made

joiner_link = E._make_link(E._rid_of_secret(secret), other.get_public_key().hex(), "Their room", secret)
E._g["rooms"] = {}
ans = json.loads(E.join_room(joiner_link))
assert ans["ok"] and E._g["rooms"][ans["id"]]["pq"] == secret and ans["id"] == E._rid_of_secret(secret), ans
ans = json.loads(E.join_room(joiner_link.replace("v=3", "v=4")))
assert not ans["ok"] and "newer Paddock" in ans["error"], ans

print("post-quantum self-check: ok")
