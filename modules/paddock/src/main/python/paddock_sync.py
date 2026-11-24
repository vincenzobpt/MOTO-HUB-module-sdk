# SPDX-License-Identifier: MPL-2.0
# Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
# This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
# of the MPL was not distributed with this file, you can obtain one at https://mozilla.org/MPL/2.0/.
"""Paddock sync: a rider rung by the push service fetches what waits for them from the sender.

The sender is online (it just rang); the rung rider's phone has just reconnected, and the relays may
take a minute or two to tell anyone where it is. So the rung rider opens a Link to the sender's
`paddock/sync` destination, says who it is and which room, and the sender answers on that Link with
one Resource: its chat messages still waiting for that rider. Nothing needs a path to the rung rider.

RNS threads and the engine meet only through the hooks given to init(); none is called under a lock
of this module.
"""
import threading
import time

import RNS.vendor.umsgpack as msgpack

ASK = b"S"
PATH_WAIT_S, PATH_ASK_EVERY_S, PULL_TIMEOUT_S = 60, 8, 90
MAX_BYTES = 256 * 1024

_s = {"dest": None}
_eng = {}


def init(g, pending, delivered, ingest):
    """pending(identity hex, room id) -> [chat bodies] waiting for that rider, or None (not a room-mate).
    delivered(identity hex, room id, [chat ids]) marks them delivered. ingest(sender key hex, room id,
    [chat bodies]) adds what a sender handed me."""
    RNS = g["RNS"]
    _eng.update(g=g, RNS=RNS, pending=pending, delivered=delivered, ingest=ingest)
    dest = RNS.Destination(g["ident"], RNS.Destination.IN, RNS.Destination.SINGLE, "paddock", "sync")
    dest.set_link_established_callback(_inbound)
    _s["dest"] = dest


def announce():
    """With the voice announce, a few seconds after LXMF's (relays hold back bursts)."""
    if _s["dest"] is not None:
        t = threading.Timer(9, lambda: _s["dest"].announce())  # no app data
        t.daemon = True
        t.start()


# ---------------------------------------------------------------- the sender's side

def _inbound(link):
    state = {"who": None}
    link.set_remote_identified_callback(lambda l, identity: state.update(who=identity.hash.hex()))
    link.set_packet_callback(lambda data, packet: _asked(link, state, data))
    t = threading.Timer(30, lambda: link.teardown() if link.status == _eng["RNS"].Link.ACTIVE else None)
    t.daemon = True
    t.start()


def _asked(link, state, data):
    if not data or data[:1] != ASK or state["who"] is None:
        return
    try:
        rid = msgpack.unpackb(data[1:]).get("r")
    except Exception:
        return
    entries = _eng["pending"](state["who"], rid) if isinstance(rid, str) else None
    if entries is None:
        link.teardown()  # not a rider of that room: nothing learnt
        return
    payload = msgpack.packb({"r": rid, "m": entries})
    if not entries or len(payload) > MAX_BYTES:
        link.teardown()
        return
    ids = [e.get("i") for e in entries]
    who = state["who"]

    def done(resource):
        if resource.status == _eng["RNS"].Resource.COMPLETE:
            _eng["delivered"](who, rid, ids)
        link.teardown()
    _eng["RNS"].Resource(payload, link, callback=done)


# ---------------------------------------------------------------- the rung rider's side

def pull(sender_key_hex, room_id):
    """In a thread: fetch what sender_key_hex holds for me in room_id."""
    threading.Thread(target=_pull, args=(sender_key_hex, room_id), daemon=True).start()


def _pull(key_hex, rid):
    RNS = _eng["RNS"]
    try:
        ident = RNS.Identity(create_keys=False)
        ident.load_public_key(bytes.fromhex(key_hex))
        dest = RNS.Destination(ident, RNS.Destination.OUT, RNS.Destination.SINGLE, "paddock", "sync")
        until, asked = time.time() + PATH_WAIT_S, 0
        while not RNS.Transport.has_path(dest.hash) and time.time() < until:
            if time.time() - asked >= PATH_ASK_EVERY_S:
                RNS.Transport.request_path(dest.hash)
                asked = time.time()
            time.sleep(0.2)
        if not RNS.Transport.has_path(dest.hash):
            print("sync: no path to the sender")
            return
        t0 = time.time()

        def established(link):
            link.identify(_eng["g"]["ident"])
            RNS.Packet(link, ASK + msgpack.packb({"r": rid})).send()

        def concluded(resource):
            if resource.status != RNS.Resource.COMPLETE:
                return
            try:
                body = msgpack.unpackb(resource.data.read())
                if body.get("r") == rid and isinstance(body.get("m"), list):
                    _eng["ingest"](key_hex, rid, body["m"])
                    print("sync: %d message(s) in %.1f s" % (len(body["m"]), time.time() - t0))
            except Exception as e:
                print("sync: bad answer %r" % (e,))

        link = RNS.Link(dest, established_callback=established)
        link.set_resource_strategy(RNS.Link.ACCEPT_ALL)
        link.set_resource_concluded_callback(concluded)
        t = threading.Timer(PULL_TIMEOUT_S, lambda: link.teardown())
        t.daemon = True
        t.start()
    except Exception as e:
        print("sync failed: %r" % (e,))
