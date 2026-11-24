# SPDX-License-Identifier: MPL-2.0
# Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
# This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
# of the MPL was not distributed with this file, you can obtain one at https://mozilla.org/MPL/2.0/.
"""Self-check of who is in a room (DESIGN.md 3): join, remove, leave, close, and the newest message.

    PYTHONPATH=<site-packages with rns 1.6.1 + lxmf 1.3.1> python3 -I test_rooms.py
"""
import json
import os
import sys
import tempfile

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "../../main/python"))
for p in os.environ.get("PYTHONPATH", "").split(os.pathsep):
    if p and p not in sys.path:
        sys.path.append(p)
import RNS  # noqa: E402
import LXMF  # noqa: E402
import paddock_engine as E  # noqa: E402


def dest(ident):
    return RNS.Destination.hash(ident, "lxmf", "delivery").hex()


me, ex, friend, boss = RNS.Identity(), RNS.Identity(), RNS.Identity(), RNS.Identity()
E._g.update(RNS=RNS, LXMF=LXMF, storage=tempfile.mkdtemp(), started=True, push_token="", rooms={},
            my_hash=dest(me), key=me.get_public_key().hex(),
            profile={"name": "Me", "color": "#EDEFF2", "bike": ""})
sent = []
E._send = lambda room, d, key, kind, data=None, **kw: sent.append((d, kind, kw.get("mailbox", False)))
E._announce = lambda: None


class Msg:
    def __init__(self, src, body):
        self.fields = {LXMF.FIELD_CUSTOM_TYPE: E.APP_TYPE, LXMF.FIELD_CUSTOM_DATA: body}
        self.source_hash, self.signature_validated, self.unverified_reason = bytes.fromhex(dest(src)), True, None


def join(room, who, name):
    E._handle(Msg(who, {"t": "join", "r": room["id"], "k": who.get_public_key().hex(), "name": name}))


# 1. Anyone with the room id gets in; a removed rider does not, even with the link they kept.
room = E._new_room(os.urandom(16).hex(), "Sunday", dest(me), E._g["key"], "paddock://join?v=2", True)
join(room, ex, "Ex")
join(room, friend, "Friend")
assert {dest(ex), dest(friend)} <= set(room["members"]), room["members"].keys()
sent.clear()
assert json.loads(E.remove_member(room["id"], dest(ex)))["ok"]
assert (dest(ex), "removed", True) in sent, "removed must also go to the mailbox: " + repr(sent)
join(room, ex, "Ex again")
assert dest(ex) not in room["members"], "a removed rider came back with the old link"

# 2. The ban survives a restart.
with open(E._room_file(room["id"])) as f:
    assert E._load_room(json.load(f))["banned"] == [dest(ex)]

# 3. The same rider of two rooms is found by their RNS identity (voice, sync), never me.
assert E._by_identity(room, friend.hash.hex()) == dest(friend)
assert E._by_identity(room, me.hash.hex()) is None

# 4. Closing a room tells every member through the mailbox too.
sent.clear()
E.delete_room(room["id"])
assert (dest(friend), "close", True) in sent and room["id"] not in E._g["rooms"], sent

# 5. Leaving tells the leader through the mailbox too; a close from the leader drops the room here.
mine = E._new_room(os.urandom(16).hex(), "Track", dest(boss), boss.get_public_key().hex(), "paddock://join?v=2", False)
mine["state"] = "active"
sent.clear()
E.leave_room(mine["id"])
assert sent == [(dest(boss), "leave", True)] and mine["id"] not in E._g["rooms"], sent
theirs = E._new_room(os.urandom(16).hex(), "Night", dest(boss), boss.get_public_key().hex(), "paddock://join?v=2", False)
theirs["state"] = "active"
E._handle(Msg(boss, {"t": "close", "r": theirs["id"]}))
assert theirs["id"] not in E._g["rooms"], "close from the leader ignored"

# 6. The newest unread message is the one that arrived last, not the last in the chat (a mailbox copy
# takes its place by send time, before messages already read).
room = E._new_room(os.urandom(16).hex(), "Chat", dest(me), E._g["key"], "paddock://join?v=2", True)
join(room, friend, "Friend")
room["chat"], room["idx"] = [], {}
for i, (at, text) in enumerate([(100, "read before"), (200, "unread"), (50, "late from the mailbox")]):
    E._chat_add(room, {"id": "%016x" % i, "from": dest(friend), "name": "Friend", "color": "#fff", "kind": "text",
                       "text": text, "quick": None, "lat": None, "lon": None, "at": at, "rx": E._next_rx(room),
                       "mine": False, "rc": None})
    if i == 0:
        room["readRx"] = room["rx"]
assert [e["text"] for e in room["chat"]] == ["late from the mailbox", "read before", "unread"]
got = json.loads(E.newest_unread(room["id"]))["messages"]
assert [m["text"] for m in got] == ["late from the mailbox"], got

print("rooms self-check: ok")
