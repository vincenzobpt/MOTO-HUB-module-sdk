# SPDX-License-Identifier: MPL-2.0
# Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
# This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
# of the MPL was not distributed with this file, you can obtain one at https://mozilla.org/MPL/2.0/.
"""Paddock voice, the network half: one call at a time over one RNS Link. See VOICE.md.

Python only carries bytes. Kotlin owns the microphone, Opus and the speaker: it hands each media
packet to send() and drains what arrived with pull(). Call state reaches Kotlin through the
engine's poll(). RNS threads and Kotlin meet under _clock, never under the engine's _lock.
"""
import collections
import struct
import threading
import time

import RNS.vendor.umsgpack as msgpack

# One-byte packet types on the call's Link.
OFFER, RINGING, ACCEPT, DECLINE, BUSY, BYE, MUTE, MEDIA = 1, 2, 3, 4, 5, 6, 7, 16
RING_TIMEOUT_S, ANSWER_TIMEOUT_S, CONNECT_TIMEOUT_S = 10, 45, 60
ANNOUNCE_DELAY_S, PATH_WAIT_S, PATH_ASK_EVERY_S = 6, 30, 8
# No link after WAKE_AFTER_S: the callee's phone is rung through the push service (contract 51) and
# the call keeps trying for WOKEN_RING_S - long enough to see the notification, open MOTO-HUB and
# let Paddock reconnect - dialling again every REDIAL_S.
WAKE_AFTER_S, WOKEN_RING_S, REDIAL_S = 4, 90, 5
INBOX_MAX = 100  # ~4 s of 40 ms packets: Kotlin drains every 20 ms, more means it stalled

_clock = threading.RLock()
_v = {"dest": None, "call": None, "inbox": collections.deque(maxlen=INBOX_MAX), "events": []}
_eng = {}


def init(g, member_of, wake=None):
    """Called by the engine's start(), after RNS is up. member_of(identity_hash_hex) gives
    (name, room id) for a rider who shares a room with me, or None. wake(identity_hash_hex, room id)
    rings that rider's phone and says whether it could (they shared a push token)."""
    RNS = g["RNS"]
    _eng.update(g=g, RNS=RNS, member_of=member_of, wake=wake or (lambda *a: False))
    dest = RNS.Destination(g["ident"], RNS.Destination.IN, RNS.Destination.SINGLE, "paddock", "voice")
    dest.set_link_established_callback(_inbound_link)
    _v["dest"] = dest


def announce():
    """A few seconds after the LXMF announce, not with it: a relay holds back a client's announces
    that come in a burst (ingress control), and a held one leaves us unreachable for minutes."""
    if _v["dest"] is not None:
        t = threading.Timer(ANNOUNCE_DELAY_S, lambda: _v["dest"].announce())  # no app data
        t.daemon = True
        t.start()


def voice_hash_of_key(key_hex):
    RNS = _eng["RNS"]
    ident = RNS.Identity(create_keys=False)
    ident.load_public_key(bytes.fromhex(key_hex))
    return RNS.Destination.hash(ident, "paddock", "voice")


def _event(kind, text):
    _v["events"].append({"type": kind, "text": text})


def _new_call(direction, peer_hash, name, room):
    return {"dir": direction, "state": "calling" if direction == "out" else "ringing",
            "peer": peer_hash, "name": name, "room": room, "link": None, "started": time.time(),
            "connectedAt": None, "remoteMuted": False, "reason": ""}


def _end(call, reason, tell=True):
    """Under _clock. Ends the call once; a reason the rider reads ("declined", "no answer"...)."""
    if call is None or call["state"] == "ended":
        return
    link = call["link"]
    if tell and link is not None and link.status == _eng["RNS"].Link.ACTIVE:
        _send_ctl(link, BYE)
    call["state"], call["reason"] = "ended", reason
    _v["call"] = None
    _v["inbox"].clear()
    _event("call_ended", reason)
    if link is not None:
        threading.Thread(target=_teardown_later, args=(link,), daemon=True).start()


def _teardown_later(link):
    time.sleep(0.3)  # let the last control packet leave first
    try:
        link.teardown()
    except Exception:
        pass


def _send_ctl(link, kind, body=None):
    try:
        _eng["RNS"].Packet(link, bytes([kind]) + msgpack.packb(body or {})).send()
        return True
    except Exception as e:
        print("voice control %d failed: %r" % (kind, e))
        return False


# ---------------------------------------------------------------- outgoing

def call(peer_key_hex, name, room_id, callback=False):
    """Ring a room-mate. Returns an error text, or "" when the call is on its way.
    callback: I was rung by this rider's call through the push service and tapped it: my link
    becomes their call (they take it without ringing me again), see _identified."""
    RNS, g = _eng["RNS"], _eng["g"]
    with _clock:
        if _v["call"] is not None:
            return "You are already in a call."
        try:
            ident = RNS.Identity(create_keys=False)
            ident.load_public_key(bytes.fromhex(peer_key_hex))
        except Exception:
            return "This rider's key is not valid."
        c = _new_call("out", ident.hash.hex(), name, room_id)
        c["ident"] = ident
        c["callback"] = bool(callback)
        _v["call"] = c
    threading.Thread(target=_dial, args=(c, ident), daemon=True).start()
    return ""


def _maybe_wake(c):
    """Out of _clock (the engine's wake takes no lock of ours, but stay clear): ring the callee's
    phone once, when the call has had no answer from the network for WAKE_AFTER_S."""
    if c["dir"] != "out" or c.get("woken") is not None or c["state"] != "calling" or c.get("callback"):
        return
    if time.time() - c["started"] < WAKE_AFTER_S:
        return
    try:
        c["woken"] = bool(_eng["wake"](c["peer"], c["room"]))
    except Exception as e:
        print("voice wake failed: %r" % (e,))
        c["woken"] = False


def _ring_limit(c):
    return WOKEN_RING_S if c.get("woken") else RING_TIMEOUT_S + 20


def _dial(c, ident):
    RNS, g = _eng["RNS"], _eng["g"]
    dest_hash = RNS.Destination.hash(ident, "paddock", "voice")
    if not RNS.Transport.has_path(dest_hash):
        # Ask again now and then: one request can be dropped by a busy relay. A rung callee gets
        # until the end of the ring to come back online.
        asked = 0
        while not RNS.Transport.has_path(dest_hash) and time.time() < c["started"] + max(PATH_WAIT_S, _ring_limit(c)):
            if c["state"] == "ended":
                return
            _maybe_wake(c)
            if time.time() - asked >= PATH_ASK_EVERY_S:
                RNS.Transport.request_path(dest_hash)
                asked = time.time()
            time.sleep(0.2)
    with _clock:
        if c["state"] == "ended":
            return
        if not RNS.Transport.has_path(dest_hash):
            _end(c, "%s cannot be reached: MOTO-HUB may be closed on their phone." % c["name"], tell=False)
            return
        out = RNS.Destination(ident, RNS.Destination.OUT, RNS.Destination.SINGLE, "paddock", "voice")
        link = RNS.Link(out, established_callback=lambda l: _out_established(c, l),
                        closed_callback=lambda l: _closed(c, l))
        link.set_packet_callback(lambda data, packet: _on_packet(c, data))
        c["link"] = link
        if c.get("watched"):
            return  # a redial: the first dial's watcher is still on it
        c["watched"] = True
    threading.Thread(target=_watch, args=(c,), daemon=True).start()


def _out_established(c, link):
    with _clock:
        if c["state"] == "ended" or c["link"] is not link:
            link.teardown()  # ended, or the callee's call-back became the call meanwhile
            return
        link.identify(_eng["g"]["ident"])
        prof = _eng["g"]["profile"]
        _send_ctl(link, OFFER, {"name": prof["name"], "room": c["room"], "codec": "opus16"})


def _watch(c):
    """Timeouts while the call is not connected yet."""
    while True:
        time.sleep(0.5)
        _maybe_wake(c)
        with _clock:
            if c["state"] in ("ended", "connected"):
                return
            age = time.time() - c["started"]
            if c["state"] == "calling" and age > _ring_limit(c):
                _end(c, "%s did not answer the network." % c["name"])
                return
            if c["state"] == "ringing_out" and age > ANSWER_TIMEOUT_S + 20:
                _end(c, "No answer from %s." % c["name"])
                return
            if c["dir"] == "in" and age > ANSWER_TIMEOUT_S:
                _end(c, "Missed call from %s." % c["name"])
                return


# ---------------------------------------------------------------- incoming

def _inbound_link(link):
    link.set_remote_identified_callback(_identified)
    link.set_packet_callback(lambda data, packet: _on_packet(_call_of(link), data, link))
    link.set_link_closed_callback(lambda l: _closed(_call_of(l), l))
    # A link that never says who it is goes away on its own.
    threading.Timer(RING_TIMEOUT_S, lambda: _drop_anonymous(link)).start()


def _call_of(link):
    c = _v["call"]
    return c if c is not None and c["link"] is link else None


def _drop_anonymous(link):
    with _clock:
        if _call_of(link) is None and link.status == _eng["RNS"].Link.ACTIVE and link.get_remote_identity() is None:
            link.teardown()


def _identified(link, identity):
    who = _eng["member_of"](identity.hash.hex())
    with _clock:
        if who is None:
            # Not a room-mate: no ring, no answer, nothing learnt about me.
            link.teardown()
            return
        cur = _v["call"]
        if (cur is not None and cur["dir"] == "out" and cur["peer"] == identity.hash.hex()
                and cur["state"] in ("calling", "ringing_out")):
            # The rider I am calling, calling me: they were rung through the push service and tapped
            # it, so this link is my call and they have answered.
            old = cur["link"]
            cur["link"] = link
            cur["state"], cur["connectedAt"] = "connected", time.time()
            _send_ctl(link, ACCEPT)
            _event("call_connected", "%s answered." % cur["name"])
            if old is not None and old is not link:
                threading.Thread(target=_teardown_later, args=(old,), daemon=True).start()
            return
        if _v["call"] is not None:
            _send_ctl(link, BUSY)
            threading.Thread(target=_teardown_later, args=(link,), daemon=True).start()
            return
        name, room = who
        c = _new_call("in", identity.hash.hex(), name, room)
        c["link"] = link
        _v["call"] = c
        _send_ctl(link, RINGING)
        _event("call_incoming", "%s is calling." % name)
    threading.Thread(target=_watch, args=(c,), daemon=True).start()


def answer():
    with _clock:
        c = _v["call"]
        if c is None or c["dir"] != "in" or c["state"] != "ringing":
            return "There is no call to answer."
        if not _send_ctl(c["link"], ACCEPT):
            _end(c, "The call was lost.", tell=False)
            return "The call was lost."
        c["state"], c["connectedAt"] = "connected", time.time()
        return ""


def decline():
    with _clock:
        c = _v["call"]
        if c is not None and c["dir"] == "in" and c["state"] == "ringing":
            _send_ctl(c["link"], DECLINE)
            _end(c, "Call declined.", tell=False)


def hangup():
    with _clock:
        _end(_v["call"], "Call ended.")


def set_muted(muted):
    with _clock:
        c = _v["call"]
        if c is not None and c["state"] == "connected":
            _send_ctl(c["link"], MUTE, {"m": bool(muted)})


# ---------------------------------------------------------------- both ways

def _on_packet(c, data, link=None):
    if not data:
        return
    kind = data[0]
    if kind == MEDIA:
        if c is not None and c["state"] == "connected":
            _v["inbox"].append(bytes(data[1:]))
        return
    try:
        body = msgpack.unpackb(data[1:]) if len(data) > 1 else {}
    except Exception:
        body = {}
    with _clock:
        if c is None or c["state"] == "ended":
            return
        if c["dir"] == "out":
            if kind == RINGING and c["state"] == "calling":
                c["state"] = "ringing_out"
            elif kind == ACCEPT and c["state"] in ("calling", "ringing_out"):
                c["state"], c["connectedAt"] = "connected", time.time()
            elif kind == DECLINE:
                _end(c, "%s declined the call." % c["name"], tell=False)
            elif kind == BUSY:
                _end(c, "%s is in another call." % c["name"], tell=False)
        if kind == BYE:
            _end(c, "%s hung up." % c["name"] if c["state"] == "connected" else "Call ended.", tell=False)
        elif kind == MUTE and isinstance(body, dict):
            c["remoteMuted"] = bool(body.get("m"))


def _closed(c, link):
    with _clock:
        if c is not None and c["link"] is not link:
            return  # an old link of this call (a redial, or replaced by the callee's call-back)
        if (c is not None and c["dir"] == "out" and c["state"] == "calling" and c.get("woken")
                and time.time() - c["started"] < WOKEN_RING_S - REDIAL_S):
            # The callee's MOTO-HUB was asleep (old path, relays parked): dial again while their
            # phone rings, until they open it or the ring ends.
            c["link"] = None
            threading.Timer(REDIAL_S, _redial, args=(c,)).start()
            return
        if c is not None and c["state"] != "ended":
            _end(c, "The call was lost." if c["state"] == "connected" else
                 "%s cannot be reached." % c["name"], tell=False)


def _redial(c):
    if c["state"] != "calling":
        return
    _eng["RNS"].Transport.request_path(_eng["RNS"].Destination.hash(c["ident"], "paddock", "voice"))
    _dial(c, c["ident"])


def send(packet):
    """One media packet from Kotlin (sequence, timestamp, Opus frames, all opaque here)."""
    c = _v["call"]
    if c is None or c["state"] != "connected":
        return False
    try:
        _eng["RNS"].Packet(c["link"], bytes([MEDIA]) + bytes(packet)).send()
        return True
    except Exception:
        return False


def pull():
    """Everything that arrived, as one bytes: [u16 length][packet] repeated."""
    out = bytearray()
    while _v["inbox"]:
        p = _v["inbox"].popleft()
        out += struct.pack(">H", len(p)) + p
    return bytes(out)


def state():
    """For poll(): the call now, and its news since the last poll."""
    with _clock:
        c = _v["call"]
        events, _v["events"] = _v["events"], []
        if c is None:
            return None, events
        return ({"dir": c["dir"], "state": c["state"], "peer": c["peer"], "name": c["name"], "room": c["room"],
                 "connectedAt": c["connectedAt"], "remoteMuted": c["remoteMuted"]}, events)
