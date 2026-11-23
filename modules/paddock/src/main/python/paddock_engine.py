# SPDX-License-Identifier: MPL-2.0
# Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
# This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
# of the MPL was not distributed with this file, you can obtain one at https://mozilla.org/MPL/2.0/.
"""Paddock engine v0.3: many permanent riding rooms + room chat over Reticulum + LXMF. See DESIGN.md (2-4b).

Kotlin calls the plain functions below from one thread and polls; nothing here calls back
into Java. LXMF/RNS threads and Kotlin meet only under _lock.
"""
import base64
import collections
import ipaddress
import json
import math
import os
import re
import shutil
import signal
import socket
import sys
import threading
import time
import urllib.parse

_lock = threading.RLock()
_g = {"started": False, "events": [], "rooms": {}, "riding": None, "profile": None,
      "dlq": collections.deque(), "pns": [], "last_fetch": 0, "last_pn_eval": 0,
      # (room, chat id, recipient) -> send time while "pending"; keys already handed to the mailbox;
      # member -> last resend time; last 5-minute retry of "waiting" recipients.
      "pend": {}, "handed": set(), "resent": {}, "last_retry": 0,
      # Leaders of rooms I am joining, and those of them just heard announcing (an RNS thread adds).
      "awaited": frozenset(), "back": collections.deque(),
      # Relays connected (set_online); while off, the ones to connect again.
      "net_on": True, "parked": []}
# Announce-handler threads touch only the propagation-node list, under this small lock (never _lock).
_pn_lock = threading.Lock()

APP_TYPE = "paddock/1"
# A join is asked DIRECT every 15 s for 90 s, then through the mailbox (the leader's MOTO-HUB may be
# closed) and DIRECT again every 2 min: it never gives up on its own, the rider cancels.
JOIN_RETRY_S, JOIN_MAILBOX_AFTER_S, JOIN_RETRY_LATE_S, ANNOUNCE_EVERY_S = 15, 90, 120, 600
# While a join waits, the mailbox is asked this often (instead of every 10 min).
FETCH_JOINING_S = 120
MAX_MEMBERS = 40
DEFAULT_COLOR = "#EDEFF2"
CHAT_MAX_TEXT, CHAT_KEEP = 2000, 1000
PN_EVAL_S, FETCH_FIRST_S, FETCH_MIN_GAP_S, FETCH_EVERY_S = 300, 8, 120, 600
MAX_PNS = 20
PENDING_S, RESEND_GAP_S, RESEND_MAX, RETRY_EVERY_S = 45, 30, 50, 300
QUICK = {"stop": "Stop", "fuel": "Fuel", "slow": "Slow down", "wait": "Wait for me",
         "photo": "Photo stop", "spot": "Share my spot", "emergency": "Emergency"}
# Room events that also appear in that room's conversation (closed / join_failed: the room is gone).
CHAT_EVENTS = ("room_joined", "member_joined", "member_left", "room_renamed")
_HEX = re.compile(r"^[0-9a-f]+$")

# Public Reticulum TCP relays (the community list Columba also starts from); "auto" uses the
# fastest few of them at once, and Reticulum's interface discovery adds what it hears of.
SEED_RELAYS = [
    ("rns.kin.earth", "rns.kin.earth", 4242), ("rmap.world", "rmap.world", 4242),
    ("RNS Sofia", "193.193.182.147", 4242), ("noDNS1", "202.61.243.41", 4965),
    ("noDNS2", "193.26.158.230", 4965), ("interloper node", "intr.cx", 4242),
    ("Jon's Node", "rns.jlamothe.net", 4242), ("R-Net TCP", "istanbul.reserve.network", 9034),
    ("RNS bnZ-NODE01", "node01.rns.bnz.se", 4242), ("RNS_Transport_US-East", "45.77.109.86", 4965),
    ("SparkN0de", "aspark.uber.space", 44860), ("Beleth RNS Hub", "rns.beleth.net", 4242),
    ("g00n.cloud Hub", "dfw.us.g00n.cloud", 6969),
]
AUTO_RELAYS = 4
# Riders of a room meet on the relays its leader shares (in the link and the roster): public relays
# do not all pass traffic to each other. A room needs at least MIN_ROOM_RELAYS; the leader shares up
# to ROOM_RELAYS_MAX; below TARGET_RELAYS online, Paddock adds the next fastest seed relays.
MIN_ROOM_RELAYS, ROOM_RELAYS_MAX, TARGET_RELAYS, TOPUP_EVERY_S = 2, 6, 4, 60
_HOST = re.compile(r"[A-Za-z0-9.\-:]{1,253}")

CONFIG_AUTO = """[reticulum]
  enable_transport = False
  share_instance = No
  panic_on_interface_error = No
  discover_interfaces = yes
  autoconnect_discovered_interfaces = 2

[logging]
  loglevel = 3

[interfaces]
"""

RELAY_BLOCK = """  [[{name}]]
    type = TCPClientInterface
    enabled = yes
    target_host = {host}
    target_port = {port}
"""

CONFIG = """[reticulum]
  enable_transport = False
  # In-process inside MOTO-HUB: never open the shared-instance port another RNS app may own.
  share_instance = No
  panic_on_interface_error = No
  # Hear of other relays even on one relay of the rider's own: the Relays map shows them.
  discover_interfaces = yes

[logging]
  loglevel = 3

[interfaces]
  [[Paddock relay]]
    type = TCPClientInterface
    enabled = yes
    target_host = {host}
    target_port = {port}
"""


# ---------------------------------------------------------------- helpers

def _now():
    return int(time.time())


def _event(kind, text, room=None):
    ev = _g["events"]
    ev.append({"type": kind, "text": text, "room": room["id"] if room else None})
    del ev[:-50]
    if room and kind in CHAT_EVENTS and room["id"] in _g["rooms"]:
        _chat_event(room, text)


def _hexs(v, n):
    """v if it is a lowercase hex string of exactly n chars, else None."""
    return v if isinstance(v, str) and len(v) == n and _HEX.match(v) else None


def _clean(v, limit, default=""):
    if not isinstance(v, str):
        return default
    v = "".join(c for c in v if c.isprintable()).strip()[:limit]
    return v or default


def _color(v):
    return v if isinstance(v, str) and re.match(r"^#[0-9A-Fa-f]{6}$", v) else DEFAULT_COLOR


def _path(name):
    return os.path.join(_g["storage"], name)


def _dest_hash_of_key(key_hex):
    """lxmf.delivery destination hash (hex) for a public key, or None if the key is invalid."""
    try:
        RNS = _g["RNS"]
        ident = RNS.Identity(create_keys=False)
        ident.load_public_key(bytes.fromhex(key_hex))
        return RNS.Destination.hash(ident, "lxmf", "delivery").hex()
    except Exception:
        return None


def _profile_entry():
    p = _g["profile"]
    return {"name": p["name"], "color": p["color"], "bike": p["bike"]}


def _member(hash_, key, name, color, bike):
    # Every member's key is made known to Reticulum at once: LXMF validates a sender's signature
    # only for a remembered identity, and would otherwise drop their positions until we had
    # written to them first.
    if hash_ != _g.get("my_hash"):
        try:
            _remember(bytes.fromhex(hash_), bytes.fromhex(key))
        except Exception:
            pass
    return {"hash": hash_, "key": key, "name": _clean(name, 40, "Rider"), "color": _color(color),
            "bike": _clean(bike, 60), "lat": None, "lon": None, "speed": None, "bearing": None,
            "fixAt": None, "heardAt": None}


def _remember(dest_hash, public_key):
    # Only when unknown: remember() on a known entry wipes what its announce taught (link cost).
    RNS = _g["RNS"]
    if RNS.Identity.recall(dest_hash) is None:
        RNS.Identity.remember(None, dest_hash, public_key)


def _room_file(rid):
    return _path("rooms/%s.json" % rid)


def _save_room(room):
    data = {"id": room["id"], "name": room["name"], "link": room["link"], "leader": room["leader"],
            "iAmLeader": room["iAmLeader"], "state": room["state"], "createdAt": room["createdAt"],
            "lastActivity": room["lastActivity"], "readRx": room["readRx"],
            "relays": [list(r) for r in room.get("relays", [])],
            "members": [{k: m[k] for k in ("hash", "key", "name", "color", "bike")}
                        for m in room["members"].values()]}
    os.makedirs(_path("rooms"), exist_ok=True)
    p = _room_file(room["id"])
    with open(p + ".tmp", "w") as f:
        json.dump(data, f)
    os.replace(p + ".tmp", p)


def _save_ride():
    p = _path("ride.json")
    if _g["riding"] is None:
        if os.path.exists(p):
            os.remove(p)
        return
    with open(p + ".tmp", "w") as f:
        json.dump({"id": _g["riding"]}, f)
    os.replace(p + ".tmp", p)


def _send(room, dest_hex, key_hex, kind, data=None, extra=None, opportunistic=False,
          content="", method=None, track=None, mailbox=False):
    """Queue one LXMF message; the router sends it in its own thread. Never blocks for long.
    track = (room id, chat id, recipient, via): delivery/failure is noted in _g["dlq"] (chat).
    mailbox: a DIRECT message that fails goes again through the propagation node, so a rider whose
    MOTO-HUB is closed gets it when they open it (join and roster)."""
    def failed():
        if track:
            _g["dlq"].append(track + ("failed",))
    try:
        RNS, LXMF = _g["RNS"], _g["LXMF"]
        ident = RNS.Identity(create_keys=False)
        ident.load_public_key(bytes.fromhex(key_hex))
        dest = RNS.Destination(ident, RNS.Destination.OUT, RNS.Destination.SINGLE, "lxmf", "delivery")
        if dest.hash.hex() != dest_hex:
            return failed()
        # Make the identity known so LXMF can encrypt, and ask the network for a route if needed.
        _remember(dest.hash, ident.get_public_key())
        if method != LXMF.LXMessage.PROPAGATED and not RNS.Transport.has_path(dest.hash):
            RNS.Transport.request_path(dest.hash)
        body = {"t": kind, "r": room["id"]}
        body.update(data or {})
        fields = {LXMF.FIELD_CUSTOM_TYPE: APP_TYPE, LXMF.FIELD_CUSTOM_DATA: body}
        fields.update(extra or {})
        msg = LXMF.LXMessage(dest, _g["dest"], content, fields=fields,
                             desired_method=method or (LXMF.LXMessage.OPPORTUNISTIC if opportunistic
                                                       else LXMF.LXMessage.DIRECT))
        if mailbox and msg.desired_method == LXMF.LXMessage.DIRECT:
            # Runs inside the router's outbound lock: only start a thread there.
            msg.register_failed_callback(lambda m: threading.Thread(
                target=_send, args=(room, dest_hex, key_hex, kind, data, extra),
                kwargs={"method": LXMF.LXMessage.PROPAGATED}, daemon=True).start()
                if _pn_current() is not None else None)
        if track:
            # Both callbacks run inside the router's outbound lock: never take _lock there.
            msg.register_delivery_callback(lambda m: _dl_note(track, m))
            msg.register_failed_callback(lambda m: _dl_note(track, m, True))
        _g["router"].handle_outbound(msg)
    except Exception as e:
        print("send %s failed: %r" % (kind, e))
        failed()


def _others(room):
    return [m for h, m in room["members"].items() if h != _g["my_hash"]]


def _roster_payload(room):
    return {"n": room["name"], "l": room["leader"], "x": _fmt_relays(room.get("relays") or _live_relays()),
            "m": [{"d": m["hash"], "k": m["key"], "name": m["name"], "color": m["color"],
                   "bike": m["bike"]} for m in room["members"].values()]}


def _broadcast_roster(room):
    for m in _others(room):
        _send(room, m["hash"], m["key"], "roster", _roster_payload(room), mailbox=True)


def _send_join(room, method=None):
    leader = room["members"][room["leader"]]
    _send(room, leader["hash"], leader["key"], "join", dict(_profile_entry(), k=_g["key"]), method=method)
    room["last_try"] = _now()


def _mail_join(room):
    """The leader did not answer DIRECT: leave the request in the mailbox for when they are back."""
    if room.get("mailed") or _pn_current() is None:
        return
    room["mailed"] = True
    _send_join(room, _g["LXMF"].LXMessage.PROPAGATED)
    print("join for %s left in the mailbox" % room["id"])


def _announce():
    # No app_data on purpose: only a random hash is public, never a name.
    _g["dest"].announce()
    if "voice" in _g:
        _g["voice"].announce()
    _g["last_announce"] = _now()


def _touch(room):
    room["lastActivity"] = _now()


def _drop_room(room):
    """Forget a room everywhere (file, riding). Only leave/delete/close/join-failure get here."""
    _g["rooms"].pop(room["id"], None)
    try:
        os.remove(_room_file(room["id"]))
    except OSError:
        pass
    try:
        os.remove(_chat_file(room["id"]))
    except OSError:
        pass
    if _g["riding"] == room["id"]:
        _g["riding"] = None
        _save_ride()


def _make_link(rid, key_hex, name):
    # v2: binary fields in base64url and no destination hash (it follows from the key), so the
    # QR has ~40% fewer cells than v1 and reads more easily in sunlight.
    return "paddock://join?v=2&r=%s&k=%s&n=%s" % (
        _b64u(bytes.fromhex(rid)), _b64u(bytes.fromhex(key_hex)), urllib.parse.quote(name, safe=""))


def _ep(i):
    """(host, port) of a relay interface (TCP or Backbone client), or None."""
    try:
        return (str(i.target_ip), int(i.target_port)) if getattr(i, "target_ip", None) else None
    except Exception:
        return None


def _tcp_ifaces():
    return [i for i in _g["RNS"].Transport.interfaces if _ep(i)]


def _live_relays():
    """The relays this phone is connected to now, sorted so a room's QR stays the same."""
    return sorted({_ep(i) for i in _tcp_ifaces() if getattr(i, "online", False)})[:ROOM_RELAYS_MAX]


def _public_host(h):
    """A link must not make phones connect into the rider's own network: no private addresses."""
    try:
        a = ipaddress.ip_address(h)
    except ValueError:
        return h.lower() != "localhost" and not h.lower().endswith((".local", ".lan", ".home", ".localhost"))
    return a.is_global


def _valid_relays(items):
    out = []
    for it in items or []:
        try:
            h, p = str(it[0]).strip("[]"), int(it[1])
        except Exception:
            continue
        if _HOST.fullmatch(h) and _public_host(h) and 0 < p < 65536 and (h, p) not in out:
            out.append((h, p))
    return out[:ROOM_RELAYS_MAX * 2]


def _parse_relays(text):
    """"host:port,[v6]:port,…" from a link or a roster."""
    return _valid_relays(part.strip().rpartition(":")[::2] for part in str(text or "").split(",") if part.strip())


def _fmt_relays(rs):
    return ",".join(("[%s]:%d" if ":" in h else "%s:%d") % (h, p) for h, p in rs)


def _room_link(room):
    """The room's link with the relays to meet on: the leader's live ones (or, for a member, the
    ones the leader shared). Older Paddocks ignore the extra x= parameter."""
    q = urllib.parse.urlparse(room["link"])
    params = [(k, v) for k, v in urllib.parse.parse_qsl(q.query) if k != "x"]
    rs = room.get("relays") or (_live_relays() if room["iAmLeader"] else [])
    if rs:
        params.append(("x", _fmt_relays(rs[:ROOM_RELAYS_MAX])))
    return "paddock://join?" + urllib.parse.urlencode(params, safe=":,[]", quote_via=urllib.parse.quote)


def _add_relay(name, host, port, room=False):
    """Connects one more relay while Reticulum runs (what its discovery does). Blocks up to the
    5 s connect timeout: call from a thread, never under _lock."""
    RNS = _g["RNS"]
    from RNS.Interfaces import TCPInterface
    with _pn_lock:
        if (not _g["net_on"] or (host, port) in _g.setdefault("adding", set())
                or any(_ep(i) == (host, port) for i in _tcp_ifaces())):
            return False
        _g["adding"].add((host, port))
    try:
        iface = TCPInterface.TCPClientInterface(RNS.Transport, {"name": name, "target_host": host, "target_port": port})
        iface.paddock_room = room
        RNS.Reticulum.get_instance()._add_interface(iface)
        if not _g["net_on"]:
            # Parked while this one was connecting: drop it too, and bring it back with the others.
            RNS.Reticulum.get_instance()._detach_interface(name, internal_forced=True, interface_instance=iface)
            _g["parked"].append((name, host, port, room))
            return False
        _g["last_announce"] = 0  # tell the new relay about me on the next tick
        print("relay added: %s %s:%d" % (name, host, port))
        return True
    except Exception as e:
        print("relay %s:%d not added: %r" % (host, port, e))
    finally:
        with _pn_lock:
            _g["adding"].discard((host, port))


def _ensure_relays(rs):
    """Connects, in the background, the room relays this phone does not have yet."""
    for h, p in rs:
        threading.Thread(target=_add_relay, args=("Room relay %s:%d" % (h, p), h, p, True), daemon=True).start()


def _topup(n):
    """Adds the [n] fastest seed relays not connected yet that keep the connection open."""
    try:
        have = {_ep(i) for i in _tcp_ifaces()}
        times = {}

        def one(seed):
            ms = _probe(seed[1], seed[2])
            if ms is not None:
                times[seed] = ms
        threads = [threading.Thread(target=one, args=(s,), daemon=True) for s in SEED_RELAYS if (s[1], s[2]) not in have]
        for t in threads:
            t.start()
        for t in threads:
            t.join(6)
        snap = dict(times)
        added = sum(1 for seed in sorted(snap, key=snap.get)[:n] if _add_relay(seed[0], seed[1], seed[2]))
        # Nothing new answers: wait longer each time (60 s up to 30 min), not probe all seeds every minute.
        _g["topup_gap"] = TOPUP_EVERY_S if added else min(_g.get("topup_gap", TOPUP_EVERY_S) * 2, 1800)
    finally:
        _g["topup_running"] = False


def _maybe_topup(now, force=False):
    if _g.get("topup_running") or not str(_g.get("node", "")).startswith("Automatic"):
        return
    if not force and now - _g.get("last_topup", 0) < _g.get("topup_gap", TOPUP_EVERY_S):
        return
    _g["last_topup"] = now
    online = sum(1 for i in _tcp_ifaces() if getattr(i, "online", False))
    if online < TARGET_RELAYS:
        _g["topup_running"] = True
        threading.Thread(target=_topup, args=(TARGET_RELAYS - online,), daemon=True).start()


# ---------------------------------------------------------------- public API

def start(storage, node_host, node_port, name, color, bike):
    with _lock:
        if _g["started"]:
            return json.dumps({"hash": _g["my_hash"]})
        os.makedirs(storage, exist_ok=True)
        _g["storage"] = storage
        # Without Chaquopy's Android startup nothing redirects Python output.
        log = open(os.path.join(storage, "python.log"), "a", buffering=1)
        sys.stdout = sys.stderr = log
        # RNS installs SIGINT/SIGTERM handlers; in-process they would take over the host app's.
        signal.signal = lambda *a, **k: None

        _g["profile"] = {"name": _clean(name, 40, "Rider"), "color": _color(color),
                         "bike": _clean(bike, 60)}
        _write_config(node_host, node_port)
        import RNS
        import LXMF
        import paddock_telemeter
        import paddock_voice
        _g.update(RNS=RNS, LXMF=LXMF, tele=paddock_telemeter, voice=paddock_voice)
        if str(node_host) != "auto":
            _g["node"] = "%s:%d" % (node_host, int(node_port))
        import RNS.Stamps
        # RNS 1.6 holds every announce until a 2^25 proof-of-work stamp is done (~1 min on a Mac,
        # single core; worse on a phone): until then nobody has a path to a new rider. 1.6 nodes
        # accept unstamped announces, path and link requests (Transport.STAMPS_ENFORCED = False);
        # relays that enforce stamps would drop them.
        RNS.Transport.STAMP_DESTINATIONS = False
        RNS.Transport.STAMP_PRS = False
        RNS.Transport.STAMP_LRS = False
        # On Android RNS computes stamps with multiprocessing "fork": forking MOTO-HUB's own process.
        # Should a stamp ever be needed anyway, compute it on this thread instead.
        RNS.Stamps.job_android = RNS.Stamps.job_simple
        RNS.Reticulum(configdir=_path("rns"), logdest=RNS.LOG_FILE)

        idp = _path("identity")
        ident = RNS.Identity.from_file(idp) if os.path.isfile(idp) else None
        if ident is None:
            ident = RNS.Identity()
            ident.to_file(idp)
        router = LXMF.LXMRouter(identity=ident, storagepath=_path("lxmf"), autopeer=False)
        dest = router.register_delivery_identity(ident)  # no display_name: nothing public
        router.register_delivery_callback(_on_message)
        RNS.Transport.register_announce_handler(_PNHandler())
        RNS.Transport.register_announce_handler(_BackHandler())
        _g.update(router=router, dest=dest, ident=ident, my_hash=dest.hash.hex(),
                  key=ident.get_public_key().hex(), since=_now())
        paddock_voice.init(_g, _member_by_identity)
        _announce()
        _migrate_v01()
        _restore_rooms()
        _restore_pn()
        _g["started"] = True
        return json.dumps({"hash": _g["my_hash"]})


def _write_config(host, port):
    """One relay the rider chose, or "auto": the fastest few public relays, all at once, plus
    Reticulum's own discovery of more. Two riders reach each other when they share a relay (or
    their relays are linked), so the same short list ranked by speed keeps a group together."""
    os.makedirs(_path("rns"), exist_ok=True)
    if str(host) == "auto":
        chosen = _fastest_relays()
        blocks = "".join(RELAY_BLOCK.format(name=n, host=h, port=p) for n, h, p in chosen)
        _g["node"] = "Automatic: " + ", ".join(n for n, _, _ in chosen)
        _g["relays_chosen"] = chosen
        text = CONFIG_AUTO + blocks
    else:
        # A host is a name or an address: anything else could write extra lines into the config.
        if not re.fullmatch(r"[A-Za-z0-9.\-:\[\]]{1,253}", str(host)):
            host = SEED_RELAYS[0][1]
        text = CONFIG.format(host=host, port=int(port))
        _g["relays_chosen"] = [(str(host), str(host), int(port))]
    with open(_path("rns/config"), "w") as f:
        f.write(text)


def _probe(host, port, timeout=2.5, hold=2.0):
    """Connect time in ms, or None. Some relays accept the TCP connection and hang up at once
    (seen 22/11: noDNS1/2, SparkN0de, rmap…): RNS then reconnects every 5 s and is never online.
    So the socket must also stay open for [hold] seconds; data arriving in that time is fine."""
    t = time.time()
    try:
        s = socket.create_connection((host, port), timeout=timeout)
    except Exception:
        return None
    ms = (time.time() - t) * 1000
    try:
        s.settimeout(hold)
        if s.recv(1) == b"":
            return None  # closed by the relay
    except socket.timeout:
        pass  # still open and quiet: good
    except Exception:
        return None  # reset by the relay
    finally:
        s.close()
    return ms


def _fastest_relays():
    """The AUTO_RELAYS public relays that answer fastest from here and keep the connection open (parallel, ~5 s).
    None answering (no network yet): the ones chosen last time, else the first of the list."""
    times = {}

    def one(seed):
        ms = _probe(seed[1], seed[2])
        if ms is not None:
            times[seed] = ms
    threads = [threading.Thread(target=one, args=(s,), daemon=True) for s in SEED_RELAYS]
    for t in threads:
        t.start()
    deadline = time.time() + 5  # connect 2.5 s + hold 2 s
    for t in threads:
        t.join(max(0.0, deadline - time.time()))
    snap = dict(times)  # late probe threads may still write
    ranked = sorted(snap, key=snap.get)[:AUTO_RELAYS]
    path = _path("relays.json")
    if ranked:
        try:
            with open(path, "w") as f:
                json.dump({"chosen": [list(r) for r in ranked], "ms": {r[0]: round(snap[r]) for r in ranked}}, f)
        except OSError:
            pass
        print("relays by speed: " + ", ".join("%s %d ms" % (r[0], snap[r]) for r in ranked))
        return ranked
    try:
        with open(path) as f:
            return [tuple(r) for r in json.load(f)["chosen"]][:AUTO_RELAYS] or list(SEED_RELAYS[:AUTO_RELAYS])
    except Exception:
        return list(SEED_RELAYS[:AUTO_RELAYS])


def set_profile(name, color, bike):
    with _lock:
        _g["profile"] = {"name": _clean(name, 40, "Rider"), "color": _color(color),
                         "bike": _clean(bike, 60)}
        for room in _g["rooms"].values():
            me = room["members"].get(_g["my_hash"])
            if me:
                me.update(_profile_entry())
            if room["iAmLeader"]:
                _broadcast_roster(room)
            elif room["state"] == "active":
                leader = room["members"][room["leader"]]
                _send(room, leader["hash"], leader["key"], "hello", _profile_entry())
            _save_room(room)


def _new_room(rid, name, leader_hash, leader_key, link, i_am_leader):
    me = _member(_g["my_hash"], _g["key"], **_profile_entry())
    members = {me["hash"]: me}
    if not i_am_leader:
        members[leader_hash] = _member(leader_hash, leader_key, "Leader", None, "")
    now = _now()
    room = {"id": rid, "name": name, "link": link, "leader": leader_hash,
            "iAmLeader": i_am_leader, "state": "active" if i_am_leader else "joining",
            "members": members, "started": now, "last_try": 0, "createdAt": now,
            "lastActivity": now, "readRx": 0, "rx": 0, "chat": [], "idx": {}, "relays": []}
    _g["rooms"][rid] = room
    _announce()
    return room


def _b64u(raw):
    return base64.urlsafe_b64encode(raw).decode().rstrip("=")


def _unb64u(text, size):
    """Hex of exactly [size] bytes from unpadded base64url, or None."""
    try:
        raw = base64.urlsafe_b64decode(str(text) + "=" * (-len(str(text)) % 4))
        return raw.hex() if len(raw) == size else None
    except Exception:
        return None


def create_room(name):
    with _lock:
        if not _g["started"]:
            return json.dumps({"ok": False, "error": "Not started."})
        live = _live_relays()
        # With one relay of the rider's own (Settings ▸ Relays) that one is enough.
        need = MIN_ROOM_RELAYS if str(_g.get("node", "")).startswith("Automatic") else 1
        if len(live) < need:
            _maybe_topup(_now(), force=True)
            return json.dumps({"ok": False, "error": "Paddock is connected to %d relay%s. A room needs at least %d, "
                               "so your riders can always reach you: it is connecting to more. Try again in a few "
                               "seconds." % (len(live), "" if len(live) == 1 else "s", need)})
        name = _clean(name, 64, "Paddock")
        rid = os.urandom(16).hex()
        room = _new_room(rid, name, _g["my_hash"], _g["key"], _make_link(rid, _g["key"], name), True)
        room["relays"] = live
        _save_room(room)
        return json.dumps({"ok": True, "id": rid, "link": _room_link(room)})


def join_room(link):
    with _lock:
        try:
            q = urllib.parse.urlparse(link.strip())
            if q.scheme != "paddock" or q.netloc != "join":
                raise ValueError
            p = {k: v[0] for k, v in urllib.parse.parse_qs(q.query).items()}
            if p.get("v") == "2":
                rid, k = _hexs(_unb64u(p.get("r"), 16), 32), _hexs(_unb64u(p.get("k"), 64), 128)
                d = _dest_hash_of_key(k) if k else None
            elif p.get("v") == "1":
                rid, d, k = _hexs(p.get("r"), 32), _hexs(p.get("d"), 32), _hexs(p.get("k"), 128)
            else:
                raise ValueError
            if not (rid and d and k) or not _g["started"]:
                raise ValueError
            if _dest_hash_of_key(k) != d:
                raise ValueError
        except Exception:
            return json.dumps({"ok": False, "error": "This is not a Paddock room link."})
        if d == _g["my_hash"]:
            return json.dumps({"ok": False, "error": "This is your own room."})
        shared = _parse_relays(p.get("x"))
        if rid in _g["rooms"]:
            room = _g["rooms"][rid]
            if shared:  # scanning the code again brings the leader's relays
                room["relays"] = _valid_relays(shared + room.get("relays", []))
                _save_room(room)
                _ensure_relays(room["relays"])
            return json.dumps({"ok": False, "error": "You are already in this room." + (
                " Paddock now also uses its leader's relays." if shared else "")})
        room = _new_room(rid, _clean(p.get("n"), 64, "Paddock"), d, k, link.strip(), False)
        room["relays"] = shared
        _ensure_relays(shared)
        _send_join(room)
        _save_room(room)
        return json.dumps({"ok": True, "id": rid})


def rename_room(room_id, name):
    with _lock:
        room = _g["rooms"].get(room_id)
        if not room or not room["iAmLeader"]:
            return json.dumps({"ok": False, "error": "Only the leader can rename a room."})
        room["name"] = _clean(name, 64, room["name"])
        room["link"] = _make_link(room["id"], _g["key"], room["name"])
        _touch(room)
        _broadcast_roster(room)
        _save_room(room)
        return json.dumps({"ok": True})


def leave_room(room_id):
    with _lock:
        room = _g["rooms"].get(room_id)
        if not room:
            return
        if room["iAmLeader"]:
            return delete_room(room_id)
        leader = room["members"][room["leader"]]
        _send(room, leader["hash"], leader["key"], "leave")
        _drop_room(room)


def delete_room(room_id):
    with _lock:
        room = _g["rooms"].get(room_id)
        if not room:
            return
        if not room["iAmLeader"]:
            return leave_room(room_id)
        for m in _others(room):
            _send(room, m["hash"], m["key"], "close")
        _drop_room(room)


def start_ride(room_id):
    with _lock:
        room = _g["rooms"].get(room_id)
        if not room or room["state"] != "active":
            return json.dumps({"ok": False, "error": "This room is not ready yet."})
        _g["riding"] = room_id
        _save_ride()
        _announce()
        return json.dumps({"ok": True})


def stop_ride():
    with _lock:
        _g["riding"] = None
        _save_ride()


def send_position(lat, lon, alt, speed_mps, bearing, accuracy_m, fix_epoch_s):
    with _lock:
        room = _g["rooms"].get(_g["riding"])
        if not room or room["state"] != "active":
            return
        vals = [lat, lon, alt, speed_mps, bearing, accuracy_m]
        if not all(isinstance(v, (int, float)) and math.isfinite(v) for v in vals):
            return
        if not (-90 <= lat <= 90 and -180 <= lon <= 180):
            return
        fix = int(fix_epoch_s) if fix_epoch_s and fix_epoch_s > 0 else _now()
        me = room["members"][_g["my_hash"]]
        me.update(lat=lat, lon=lon, speed=max(speed_mps, 0.0), bearing=bearing, fixAt=fix,
                  heardAt=_now())
        blob = _g["tele"].pack_location(lat, lon, alt, speed_mps, bearing, accuracy_m, fix)
        for m in _others(room):
            _send(room, m["hash"], m["key"], "pos", extra={_g["LXMF"].FIELD_TELEMETRY: blob},
                  opportunistic=True)


def poll():
    with _lock:
        if not _g["started"]:
            return json.dumps({"me": {"hash": "", "online": False, "node": "", "since": 0},
                               "riding": None, "rooms": [], "events": []})
        _tick()
        events, _g["events"] = _g["events"], []
        call, call_events = _g["voice"].state()
        return json.dumps({"me": {"hash": _g["my_hash"], "online": _online(), "node": _g["node"],
                                  "since": _g["since"]},
                           "riding": _g["riding"], "rooms": _rooms_json(), "events": events + call_events,
                           "call": call})


# ---------------------------------------------------------------- voice (paddock_voice.py)

def _member_by_identity(identity_hash_hex):
    """(name, room id) of a rider with this identity in one of my active rooms, else None.
    Called from an RNS thread holding no lock of the voice side."""
    with _lock:
        RNS = _g["RNS"]
        for room in _g["rooms"].values():
            if room["state"] != "active":
                continue
            for m in room["members"].values():
                if m["hash"] == _g["my_hash"]:
                    continue
                try:
                    if RNS.Identity.truncated_hash(bytes.fromhex(m["key"])).hex() == identity_hash_hex:
                        return m["name"], room["id"]
                except Exception:
                    pass
    return None


def voice_call(room_id, member_hash):
    with _lock:
        room = _g["rooms"].get(room_id)
        m = room["members"].get(member_hash) if room else None
        if not _g["started"] or m is None or member_hash == _g["my_hash"]:
            return json.dumps({"ok": False, "error": "This rider is not in the room."})
        key, name = m["key"], m["name"]
    err = _g["voice"].call(key, name, room_id)
    return json.dumps({"ok": not err, "error": err})


def voice_answer():
    err = _g["voice"].answer()
    return json.dumps({"ok": not err, "error": err})


def voice_decline():
    _g["voice"].decline()


def voice_hangup():
    _g["voice"].hangup()


def voice_mute(muted):
    _g["voice"].set_muted(bool(muted))


def voice_send(packet):
    try:
        raw = bytes(packet)
    except (TypeError, ValueError):  # a Java byte[] holds signed bytes
        raw = bytes(b & 0xFF for b in packet)
    return _g["voice"].send(raw)


def voice_pull():
    return _g["voice"].pull()


def set_online(on):
    """Paddock holds its relays only while they are needed (a ride, a call, MOTO-HUB on screen, or
    Always on: the host decides). Off, every relay is dropped: each one forwards every announce of
    the whole network, which kept a phone awake all night (23/11: 820 MB in 9 h on 11 relays).
    On again, the same relays come back; woke() then announces and reads the mailbox."""
    on = bool(on)
    with _lock:
        if not _g["started"] or _g["net_on"] == on:
            return
        _g["net_on"] = on
        R = _g["RNS"].Reticulum
        if not on:
            # Discovery would connect relays again on its own: no slots while parked.
            _g["autoconnect_max"] = R.max_autoconnected_interfaces()
            R._Reticulum__autoconnect_discovered_interfaces = 0
            parked = []
            for i in _tcp_ifaces():
                if not hasattr(i, "autoconnect_hash"):  # discovery brings its own back
                    parked.append((str(i.name),) + _ep(i) + (bool(getattr(i, "paddock_room", False)),))
                R.get_instance()._detach_interface(i.name, internal_forced=True, interface_instance=i)
            _g["parked"] = parked
            print("relays parked: %d" % len(parked))
            return
        R._Reticulum__autoconnect_discovered_interfaces = _g.get("autoconnect_max", 0)
        parked, _g["parked"] = _g["parked"], []
        _g["last_topup"] = 0
    for name, h, p, room in parked:
        threading.Thread(target=_add_relay, args=(name, h, p, room), daemon=True).start()
    print("relays back: %d" % len(parked))


def woke():
    """MOTO-HUB came back to the front: say we are here and read the mailbox now."""
    with _lock:
        if not _g["started"]:
            return
        _announce()
        _g["last_fetch"] = 0
        for room in _g["rooms"].values():
            if room["state"] == "joining":
                _send_join(room)


def relays():
    """The relays now: the ones this phone connects to (with how fast each answered at start) and
    the ones Reticulum's discovery has heard of, with where they are when they say."""
    with _lock:
        if not _g["started"]:
            return json.dumps({"auto": False, "connected": [], "discovered": []})
        RNS = _g["RNS"]
        ms = {}
        try:
            with open(_path("relays.json")) as f:
                ms = json.load(f).get("ms", {})
        except Exception:
            pass
        connected = []
        for i in RNS.Transport.interfaces:
            if not hasattr(i, "target_ip"):
                continue
            connected.append({"name": str(i.name), "host": str(getattr(i, "target_ip", "")),
                              "port": int(getattr(i, "target_port", 0) or 0), "online": bool(getattr(i, "online", False)),
                              "ms": ms.get(str(i.name)), "discovered": hasattr(i, "autoconnect_hash"),
                              "room": bool(getattr(i, "paddock_room", False))})
        found = []
        h = getattr(RNS.Transport, "discovery_handler", None)
        if h is not None:
            try:
                for d in h.list_discovered_interfaces()[:200]:
                    found.append({"name": str(d.get("name", "")), "host": str(d.get("reachable_on") or ""),
                                  "port": int(d.get("port") or 0), "hops": d.get("hops"), "status": d.get("status"),
                                  "transport": bool(d.get("transport")), "type": str(d.get("type", "")),
                                  "lat": d.get("latitude"), "lon": d.get("longitude"), "heard": d.get("last_heard")})
            except Exception as e:
                print("discovered relays unreadable: %r" % (e,))
        return json.dumps({"auto": str(_g.get("node", "")).startswith("Automatic"), "connected": connected,
                           "discovered": found})


def set_node(host, port):
    with _lock:
        if _g.get("storage"):
            _write_config(host, port)


def reset_identity():
    """Leaves/deletes every room and wipes local identity/rooms/LXMF store. The running engine still holds
    the old identity in memory: the host must restart the app before start() again. Chats go too."""
    with _lock:
        if _g["started"]:
            for rid in list(_g["rooms"]):
                leave_room(rid)
        base = _g.get("storage")
        if not base:
            return
        for name in ("identity", "room.json", "ride.json", "python.log", "pn.json"):
            try:
                os.remove(os.path.join(base, name))
            except OSError:
                pass
        shutil.rmtree(os.path.join(base, "rooms"), ignore_errors=True)
        shutil.rmtree(os.path.join(base, "chat"), ignore_errors=True)
        shutil.rmtree(os.path.join(base, "lxmf"), ignore_errors=True)
        # Reticulum's own store keeps the room-mates' public keys and paths: gone too.
        shutil.rmtree(os.path.join(base, "rns", "storage"), ignore_errors=True)


# ---------------------------------------------------------------- state

def _online():
    try:
        return any(getattr(i, "online", False) for i in _g["RNS"].Transport.interfaces)
    except Exception:
        return False


def _room_json(r):
    members = []
    for m in r["members"].values():
        members.append({"hash": m["hash"], "name": m["name"], "color": m["color"],
                        "bike": m["bike"], "isMe": m["hash"] == _g["my_hash"],
                        "isLeader": m["hash"] == r["leader"], "lat": m["lat"], "lon": m["lon"],
                        "speed": m["speed"], "bearing": m["bearing"], "fixAt": m["fixAt"],
                        "heardAt": m["heardAt"]})
    return {"id": r["id"], "name": r["name"], "link": _room_link(r), "state": r["state"],
            "iAmLeader": r["iAmLeader"], "leader": r["leader"], "createdAt": r["createdAt"],
            "lastActivity": r["lastActivity"], "unread": _unread(r), "last": _last(r),
            "members": members}


def _rooms_json():
    rooms = sorted(_g["rooms"].values(), key=lambda r: (r["lastActivity"], r["createdAt"]),
                   reverse=True)
    return [_room_json(r) for r in rooms]


def _tick():
    now = _now()
    _drain()
    if not _g["net_on"]:
        return  # relays parked: no joins, mailbox, announces or relay top-up until set_online(True)
    if _pn_current() is None and now - _g["last_pn_eval"] >= PN_EVAL_S:
        _g["last_pn_eval"] = now
        _pick_pn()
    joining = any(r["state"] == "joining" for r in _g["rooms"].values())
    _g["awaited"] = frozenset(r["leader"] for r in _g["rooms"].values() if r["state"] == "joining")
    back = set()
    while _g["back"]:
        back.add(_g["back"].popleft())
    for room in _g["rooms"].values():
        if room["state"] == "joining" and room["leader"] in back:
            _send_join(room)  # the leader's MOTO-HUB just opened: ask now, the path is fresh
    _maybe_fetch(FETCH_JOINING_S if joining else FETCH_EVERY_S)
    _chat_timers(now)
    for room in list(_g["rooms"].values()):
        if room["state"] != "joining":
            continue
        late = now - room["started"] > JOIN_MAILBOX_AFTER_S
        if late:
            _mail_join(room)
        if now - room["last_try"] >= (JOIN_RETRY_LATE_S if late else JOIN_RETRY_S):
            _send_join(room)
    _maybe_topup(now)
    live = _live_relays()
    for room in _g["rooms"].values():
        # A leader on relays the room does not know yet tells it (at most every 5 min: relays come
        # and go); the old ones stay listed, the newest first.
        old = room.get("relays", [])
        if (room["iAmLeader"] and len(live) >= MIN_ROOM_RELAYS and set(live) - set(old)
                and now - room.get("x_at", 0) >= 300):
            room["x_at"] = now
            room["relays"] = _valid_relays(live + old)[:ROOM_RELAYS_MAX]
            _save_room(room)
            _broadcast_roster(room)
    if now - _g.get("last_announce", 0) >= ANNOUNCE_EVERY_S:
        _announce()


def _load_room(d):
    """A room from its saved dict (rooms/<id>.json or a v0.1 room.json); None if invalid."""
    members = {}
    for m in d["members"]:
        if _hexs(m["hash"], 32) and _hexs(m["key"], 128):
            members[m["hash"]] = _member(m["hash"], m["key"], m["name"], m["color"], m["bike"])
    i_am_leader = bool(d["iAmLeader"])
    if (_g["my_hash"] not in members or d["leader"] not in members or not _hexs(d["id"], 32)
            or i_am_leader != (d["leader"] == _g["my_hash"]) or d["state"] not in ("joining", "active")):
        return None
    now = _now()
    members[_g["my_hash"]].update(_profile_entry())
    room = {"id": d["id"], "name": _clean(d["name"], 64, "Paddock"), "link": d["link"],
            "leader": d["leader"], "iAmLeader": i_am_leader, "state": d["state"],
            "members": members, "started": now, "last_try": 0,
            "createdAt": int(d.get("createdAt") or now), "lastActivity": int(d.get("lastActivity") or now),
            "readRx": int(d.get("readRx") or 0), "rx": 0, "chat": [], "idx": {},
            "relays": _valid_relays(d.get("relays"))}
    _chat_load(room)
    return room


def _migrate_v01():
    """One time: a v0.1 <storage>/room.json becomes rooms/<id>.json (riding if it was active)."""
    p = _path("room.json")
    try:
        with open(p) as f:
            room = _load_room(json.load(f))
        if room is None:
            print("room.json invalid, ignored")
            return
        if not os.path.exists(_room_file(room["id"])):
            _save_room(room)
        if room["state"] == "active" and not os.path.exists(_path("ride.json")):
            _g["riding"] = room["id"]
            _save_ride()
        os.remove(p)
    except FileNotFoundError:
        pass
    except Exception as e:
        print("room.json unreadable, ignored: %r" % (e,))


def _restore_rooms():
    try:
        names = sorted(os.listdir(_path("rooms")))
    except OSError:
        names = []
    for fn in names:
        if not fn.endswith(".json"):
            continue
        try:
            with open(_path("rooms/" + fn)) as f:
                room = _load_room(json.load(f))
            if room is None or fn != room["id"] + ".json":
                print("rooms/%s invalid, ignored" % fn)
                continue
            _g["rooms"][room["id"]] = room
        except Exception as e:
            print("rooms/%s unreadable, ignored: %r" % (fn, e))
    try:
        with open(_path("ride.json")) as f:
            rid = json.load(f).get("id")
        room = _g["rooms"].get(rid)
        if room and room["state"] == "active":
            _g["riding"] = rid
    except Exception:
        pass
    _ensure_relays(_valid_relays([r for room in _g["rooms"].values() for r in room.get("relays", [])]))
    for room in _g["rooms"].values():
        if room["iAmLeader"]:
            continue
        if room["state"] == "joining":
            _send_join(room)
        else:  # tell the leader we are back; it answers with a fresh roster
            leader = room["members"][room["leader"]]
            _send(room, leader["hash"], leader["key"], "hello", _profile_entry())


# ---------------------------------------------------------------- inbound

def _on_message(message):
    try:
        with _lock:
            _handle(message)
    except Exception as e:  # never let a hostile message kill the LXMF thread
        print("inbound message error: %r" % (e,))


def _handle(message):
    LXMF = _g["LXMF"]
    fields = message.fields
    if not isinstance(fields, dict) or fields.get(LXMF.FIELD_CUSTOM_TYPE) != APP_TYPE:
        return
    body = fields.get(LXMF.FIELD_CUSTOM_DATA)
    rid = body.get("r") if isinstance(body, dict) else None
    room = _g["rooms"].get(rid) if isinstance(rid, str) else None
    if room is None:
        return
    kind = body.get("t")
    src = message.source_hash.hex()
    now = _now()

    if kind == "join":
        if room["iAmLeader"]:
            _on_join(room, src, body, message)
        return
    sender = room["members"].get(src)
    # Members' keys are known from the link/roster, so their signatures can be checked.
    if sender is None or src == _g["my_hash"] or not message.signature_validated:
        return
    sender["heardAt"] = now
    _resend(room, src)

    if kind == "roster" and src == room["leader"] and not room["iAmLeader"]:
        _on_roster(room, body)
    elif kind == "close" and src == room["leader"] and not room["iAmLeader"]:
        _event("room_closed", "The room was closed by %s." % sender["name"], room)
        _drop_room(room)
    elif kind == "hello" and room["iAmLeader"]:
        sender.update(name=_clean(body.get("name"), 40, sender["name"]),
                      color=_color(body.get("color")), bike=_clean(body.get("bike"), 60))
        _broadcast_roster(room)
        _save_room(room)
    elif kind == "leave" and room["iAmLeader"]:
        del room["members"][src]
        _event("member_left", "%s left the room." % sender["name"], room)
        _touch(room)
        _broadcast_roster(room)
        _save_room(room)
    elif kind == "pos":
        if _on_pos(sender, fields.get(LXMF.FIELD_TELEMETRY), now):
            room["lastActivity"] = now
    elif kind in ("msg", "quick"):
        _on_chat(room, sender, src, kind, body)
    elif kind == "removed" and src == room["leader"] and not room["iAmLeader"]:
        _event("room_closed", "You were removed from %s." % room["name"], room)
        _drop_room(room)


def _on_join(room, src, body, message):
    key = _hexs(body.get("k"), 128)
    # The key must hash to the sender; an unknown-source message cannot be signature-checked yet,
    # but a wrong signature is rejected. Knowing the room id is the invitation.
    if not key or _dest_hash_of_key(key) != src:
        return
    if message.unverified_reason == _g["LXMF"].LXMessage.SIGNATURE_INVALID:
        return
    known = src in room["members"]
    if not known and len(room["members"]) >= MAX_MEMBERS:
        return
    m = _member(src, key, body.get("name"), body.get("color"), body.get("bike"))
    if known:
        old = room["members"][src]
        old.update(name=m["name"], color=m["color"], bike=m["bike"])
    else:
        room["members"][src] = m
        _event("member_joined", "%s joined the room." % m["name"], room)
        _touch(room)
    room["members"][src]["heardAt"] = _now()
    _broadcast_roster(room)
    _save_room(room)


def _on_roster(room, body):
    entries = body.get("m")
    if not isinstance(entries, list) or len(entries) > MAX_MEMBERS:
        return
    new = {}
    for e in entries:
        if not isinstance(e, dict):
            return
        h, k = _hexs(e.get("d"), 32), _hexs(e.get("k"), 128)
        if not h or not k or _dest_hash_of_key(k) != h:
            return
        new[h] = _member(h, k, e.get("name"), e.get("color"), e.get("bike"))
    if _g["my_hash"] not in new or room["leader"] not in new:
        return
    old = room["members"]
    for h, m in new.items():  # keep positions we already have
        if h in old:
            for f in ("lat", "lon", "speed", "bearing", "fixAt", "heardAt"):
                m[f] = old[h][f]
    first = room["state"] == "joining"
    changed = False
    if first:
        room["state"] = "active"
        room["name"] = _clean(body.get("n"), 64, room["name"])
        _event("room_joined", "You joined %s." % room["name"], room)
        changed = True
    else:
        for h, m in new.items():
            if h not in old:
                _event("member_joined", "%s joined the room." % m["name"], room)
                changed = True
        for h, m in old.items():
            if h not in new:
                _event("member_left", "%s left the room." % m["name"], room)
                changed = True
        name = _clean(body.get("n"), 64, room["name"])
        if name != room["name"]:
            room["name"] = name
            room["link"] = _make_link(room["id"], room["members"][room["leader"]]["key"], name)
            _event("room_renamed", "The room is now called %s." % name, room)
            changed = True
    room["members"] = new
    shared = _parse_relays(body.get("x"))
    if shared and shared != room.get("relays"):
        room["relays"] = _valid_relays(shared + room.get("relays", []))
        _ensure_relays(shared)
    if changed:
        _touch(room)
    _save_room(room)


def _on_pos(sender, blob, now):
    t = _g["tele"].unpack_location(blob) if isinstance(blob, (bytes, bytearray)) else None
    if not t or not (-90 <= t["lat"] <= 90 and -180 <= t["lon"] <= 180):
        return False
    fix = t["ts"] if 0 < t["ts"] <= now + 60 else now
    # LXMF may deliver a retried older message after a newer one: never move a rider backwards.
    if sender.get("fixAt") and fix < sender["fixAt"]:
        return False
    sender.update(lat=t["lat"], lon=t["lon"], speed=t["speed"], bearing=t["bearing"], fixAt=fix)
    return True


# ---------------------------------------------------------------- chat (DESIGN.md 3b)

def _chat_file(rid):
    return _path("chat/%s.jsonl" % rid)


def _ms():
    return int(time.time() * 1000)


def _next_rx(room):
    """Receive order (ms, strictly increasing): unread is counted by it, not by the sender's clock."""
    room["rx"] = max(_ms(), room["rx"] + 1)
    return room["rx"]


def _chat_load(room):
    try:
        with open(_chat_file(room["id"])) as f:
            lines = f.readlines()[-CHAT_KEEP:]
    except OSError:
        return
    changed = False
    for ln in lines:
        try:
            e = json.loads(ln)
            if not (isinstance(e, dict) and isinstance(e["id"], str) and e["kind"] in ("text", "quick", "event")
                    and isinstance(e["at"], int) and isinstance(e["rx"], int)):
                continue
            room["chat"].append(e)
            room["idx"][e["from"] + ":" + e["id"]] = e
            room["rx"] = max(room["rx"], e["rx"])
            if e["mine"] and isinstance(e.get("rc"), dict) and "pending" in e["rc"].values():
                # Sent before a restart: nobody is tracking it any more; it is resent on return.
                e["rc"] = {h: "waiting" if v == "pending" else v for h, v in e["rc"].items()}
                changed = True
        except Exception:
            continue
    if changed:
        _chat_rewrite(room)
    room["readRx"] = min(room["readRx"], room["rx"])


def _chat_rewrite(room):
    try:
        os.makedirs(_path("chat"), exist_ok=True)
        p = _chat_file(room["id"])
        with open(p + ".tmp", "w") as f:
            for e in room["chat"]:
                f.write(json.dumps(e) + "\n")
        os.replace(p + ".tmp", p)
    except OSError as e:
        print("chat write failed: %r" % (e,))


def _chat_add(room, e):
    c = room["chat"]
    i = len(c)
    while i > 0 and c[i - 1]["at"] > e["at"]:  # late (mailbox) messages take their place in time
        i -= 1
    c.insert(i, e)
    room["idx"][e["from"] + ":" + e["id"]] = e
    appended = i == len(c) - 1
    if len(c) > CHAT_KEEP:
        old = c.pop(0)
        room["idx"].pop(old["from"] + ":" + old["id"], None)
        appended = False
    if appended:
        try:
            os.makedirs(_path("chat"), exist_ok=True)
            with open(_chat_file(room["id"]), "a") as f:
                f.write(json.dumps(e) + "\n")
        except OSError as ex:
            print("chat write failed: %r" % (ex,))
    else:
        _chat_rewrite(room)


def _chat_event(room, text):
    now = _ms()
    _chat_add(room, {"id": "e" + os.urandom(6).hex(), "from": "", "name": "", "color": DEFAULT_COLOR,
                     "kind": "event", "text": _clean(text, 200), "quick": None, "lat": None, "lon": None,
                     "at": now, "rx": _next_rx(room), "mine": False, "rc": None})


def _unread(room):
    return sum(1 for e in room["chat"] if e["kind"] != "event" and not e["mine"] and e["rx"] > room["readRx"])


def _who(room, e):
    m = room["members"].get(e["from"])
    return (m["name"], m["color"]) if m else (e["name"], e["color"])


def _last(room):
    for e in reversed(room["chat"]):
        if e["kind"] != "event":
            return {"from": e["from"], "name": _who(room, e)[0], "kind": e["kind"], "text": e["text"],
                    "at": e["at"]}
    return None


def _chat_text(v):
    if not isinstance(v, str):
        return None
    v = "".join(c for c in v if c == "\n" or c.isprintable()).strip()[:CHAT_MAX_TEXT].strip()
    return v or None


def _coord(lat, lon):
    ok = all(isinstance(v, (int, float)) and not isinstance(v, bool) and math.isfinite(v) for v in (lat, lon))
    return (float(lat), float(lon)) if ok and -90 <= lat <= 90 and -180 <= lon <= 180 else (None, None)


def _chat_json(room, e):
    name, color = _who(room, e)
    rc = e.get("rc")
    vals = list(rc.values()) if rc else []
    delivery = ({"total": len(vals), "delivered": vals.count("delivered"), "mailbox": vals.count("mailbox"),
                 "waiting": vals.count("waiting"), "failed": vals.count("failed"),
                 # Who, by state: the rider must see who has it and who does not yet.
                 "who": [{"name": room["members"][h]["name"] if h in room["members"] else "A rider who left",
                          "state": v} for h, v in rc.items()]} if e["mine"] and rc is not None else None)
    return {"id": e["id"], "from": e["from"], "name": name, "color": color, "mine": e["mine"],
            "kind": e["kind"], "text": e["text"], "quick": e["quick"], "lat": e["lat"], "lon": e["lon"],
            "at": e["at"], "delivery": delivery}


def _chat_body(e):
    if e["kind"] == "text":
        return "msg", {"i": e["id"], "s": e["at"], "x": e["text"]}
    d = {"i": e["id"], "s": e["at"], "q": e["quick"]}
    if e["lat"] is not None:
        d.update(la=e["lat"], lo=e["lon"])
    return "quick", d


def _chat_out(rid, e, hashes, via):
    """Thread body: one LXMF message per recipient. Holds no lock while LXMF works."""
    LXMF = _g["LXMF"]
    kind, data = _chat_body(e)
    method = LXMF.LXMessage.PROPAGATED if via == "prop" else LXMF.LXMessage.DIRECT
    for h in hashes:
        with _lock:
            room = _g["rooms"].get(rid)
            m = room["members"].get(h) if room else None
        track = (rid, e["id"], h, via)
        if m is None:
            _g["dlq"].append(track + ("failed",))
            continue
        _send({"id": rid}, h, m["key"], kind, data, content=e["text"], method=method, track=track)


def _dl_note(track, lxm, failed=False):
    LXMessage = _g["LXMF"].LXMessage
    if failed:
        state = "failed"
    elif lxm.state == LXMessage.DELIVERED:
        state = "delivered"
    elif lxm.state == LXMessage.SENT and track[3] == "prop":
        state = "mailbox"
    else:
        return
    _g["dlq"].append(track + (state,))


def _drain():
    """Apply delivery news from the router threads (called under _lock from poll/chat)."""
    dirty = set()
    while True:
        try:
            rid, i, h, via, state = _g["dlq"].popleft()
        except IndexError:
            break
        room = _g["rooms"].get(rid)
        e = room["idx"].get(_g["my_hash"] + ":" + i) if room else None
        if not e or not e.get("rc") or h not in e["rc"] or e["rc"][h] == "delivered":
            continue
        cur, key = e["rc"][h], (rid, i, h)
        if state == "failed":
            if h not in room["members"]:
                pass  # the rider is gone: the only real "failed"
            elif cur == "mailbox" or via == "re":
                continue
            elif via == "direct" and cur == "pending" and _pn_current() is not None and key not in _g["handed"]:
                # LXMF 1.3.1 has no try_propagation_on_fail: hand it to the mailbox ourselves.
                _g["handed"].add(key)
                threading.Thread(target=_chat_out, args=(rid, dict(e), [h], "prop"), daemon=True).start()
                continue
            else:
                state = "waiting"  # resent when we hear from them
        if cur == state:
            continue
        e["rc"][h] = state
        dirty.add(rid)
    for rid in dirty:
        _chat_rewrite(_g["rooms"][rid])


def _chat_timers(now):
    """Under _lock: pending > 45 s becomes waiting (and goes to the mailbox if one is known);
    every 5 min waiting recipients are retried."""
    dirty = set()
    t = time.time()
    for key, since in list(_g["pend"].items()):
        rid, i, h = key
        room = _g["rooms"].get(rid)
        e = room["idx"].get(_g["my_hash"] + ":" + i) if room else None
        if not e or not e.get("rc") or e["rc"].get(h) != "pending":
            del _g["pend"][key]
        elif t - since >= PENDING_S:
            del _g["pend"][key]
            e["rc"][h] = "waiting"
            dirty.add(rid)
            if _pn_current() is not None and key not in _g["handed"]:
                _g["handed"].add(key)
                threading.Thread(target=_chat_out, args=(rid, dict(e), [h], "prop"), daemon=True).start()
    for rid in dirty:
        _chat_rewrite(_g["rooms"][rid])
    if now - _g["last_retry"] >= RETRY_EVERY_S:
        _g["last_retry"] = now
        for room in list(_g["rooms"].values()):
            for h in {h for e in room["chat"] if e["mine"] and e.get("rc")
                      for h, v in e["rc"].items() if v in ("waiting", "mailbox")}:
                _resend(room, h)


def _resend(room, h):
    """Under _lock: our messages still waiting, failed or only in a mailbox for member h go to them
    again, DIRECT, oldest first (<= 50, once per 30 s per member). The receiver dedups by (sender, i).
    A mailbox copy is resent too: the rider may never fetch from that node (another relay, none known)."""
    m, t = room["members"].get(h), time.time()
    if m is None or t - _g["resent"].get(h, 0) < RESEND_GAP_S:
        return
    todo = [dict(e) for e in room["chat"]
            if e["mine"] and e.get("rc") and e["rc"].get(h) in ("waiting", "failed", "mailbox")][:RESEND_MAX]
    if todo:
        _g["resent"][h] = t
        threading.Thread(target=_resend_out, args=(room["id"], h, todo), daemon=True).start()


def _resend_out(rid, h, entries):
    for e in entries:
        _chat_out(rid, e, [h], "re")


def _send_chat(room_id, kind, text, quick=None, lat=None, lon=None):
    with _lock:
        room = _g["rooms"].get(room_id)
        if not _g["started"] or not room or room["state"] != "active":
            return json.dumps({"ok": False, "error": "This room is not ready yet."})
        me = room["members"][_g["my_hash"]]
        others = [m["hash"] for m in _others(room)]
        e = {"id": os.urandom(8).hex(), "from": me["hash"], "name": me["name"], "color": me["color"],
             "kind": kind, "text": text, "quick": quick, "lat": lat, "lon": lon, "at": _ms(),
             "rx": _next_rx(room), "mine": True, "rc": {h: "pending" for h in others}}
        _chat_add(room, e)
        _touch(room)
        _save_room(room)
        t = time.time()
        for h in others:
            _g["pend"][(room_id, e["id"], h)] = t
        threading.Thread(target=_chat_out, args=(room_id, dict(e), others, "direct"), daemon=True).start()
        return json.dumps({"ok": True, "msg": e["id"]})


def send_text(room_id, text):
    text = _chat_text(text)
    if text is None:
        return json.dumps({"ok": False, "error": "Write something first."})
    return _send_chat(room_id, "text", text)


def send_quick(room_id, kind, lat, lon):
    if kind not in QUICK:
        return json.dumps({"ok": False, "error": "Unknown quick message."})
    la, lo = _coord(lat, lon)
    return _send_chat(room_id, "quick", QUICK[kind], kind, la, lo)


def chat(room_id, before_ms, limit):
    with _lock:
        room = _g["rooms"].get(room_id)
        if not _g["started"] or not room:
            return json.dumps({"messages": []})
        _drain()
        _maybe_fetch(FETCH_MIN_GAP_S)
        limit = int(limit) if isinstance(limit, (int, float)) and limit > 0 else 50
        msgs = [e for e in room["chat"] if not before_ms or e["at"] < before_ms]
        return json.dumps({"messages": [_chat_json(room, e) for e in msgs[-min(limit, CHAT_KEEP):]]})


def mark_read(room_id):
    with _lock:
        room = _g["rooms"].get(room_id)
        if not _g["started"] or not room:
            return
        room["readRx"] = room["rx"]
        _save_room(room)
        _maybe_fetch(FETCH_MIN_GAP_S)


def remove_member(room_id, hash_):
    with _lock:
        room = _g["rooms"].get(room_id)
        if not room or not room["iAmLeader"]:
            return json.dumps({"ok": False, "error": "Only the leader can remove a rider."})
        m = room["members"].get(hash_)
        if m is None or hash_ == _g["my_hash"]:
            return json.dumps({"ok": False, "error": "This rider is not in the room."})
        _send(room, m["hash"], m["key"], "removed")
        del room["members"][hash_]
        _event("member_left", "%s was removed from the room." % m["name"], room)
        _touch(room)
        _broadcast_roster(room)
        _save_room(room)
        return json.dumps({"ok": True})


def _on_chat(room, sender, src, kind, body):
    i = _hexs(body.get("i"), 16)
    if not i or (src + ":" + i) in room["idx"]:  # also arrives twice: direct and from the mailbox
        return
    now = _ms()
    if kind == "msg":
        text, q, la, lo = _chat_text(body.get("x")), None, None, None
        if text is None:
            return
    else:
        q = body.get("q")
        if q not in QUICK:
            return
        text = QUICK[q]
        la, lo = _coord(body.get("la"), body.get("lo"))
    s = body.get("s")
    at = int(s) if isinstance(s, (int, float)) and not isinstance(s, bool) and 0 < s <= now + 60000 else now
    _chat_add(room, {"id": i, "from": src, "name": sender["name"], "color": sender["color"],
                     "kind": "text" if kind == "msg" else "quick", "text": text, "quick": q,
                     "lat": la, "lon": lo, "at": at, "rx": _next_rx(room), "mine": False, "rc": None})
    _touch(room)
    _save_room(room)


# ---------------------------------------------------------------- propagation node (mailbox)

class _PNHandler:
    """Hears `lxmf.propagation` announces (an LXMF client router does not keep them) and keeps the
    nearest enabled node as the outbound/inbound mailbox. Runs in an RNS thread: no _lock here."""
    aspect_filter = "lxmf.propagation"
    receive_path_responses = True

    def received_announce(self, destination_hash, announced_identity, app_data, announce_packet_hash,
                          is_path_response):
        try:
            import RNS.vendor.umsgpack as msgpack
            if not _g["LXMF"].pn_announce_data_is_valid(app_data) or msgpack.unpackb(app_data)[2] is not True:
                return
            h = destination_hash.hex()
            with _pn_lock:
                if h not in _g["pns"]:
                    _g["pns"] = (_g["pns"] + [h])[-MAX_PNS:]
            _pick_pn()
        except Exception as e:
            print("propagation announce error: %r" % (e,))


class _BackHandler:
    """Hears riders' `lxmf.delivery` announces; one from the leader of a room I am joining means their
    MOTO-HUB just opened. Runs in an RNS thread: no _lock, only the deque."""
    aspect_filter = "lxmf.delivery"
    receive_path_responses = True

    def received_announce(self, destination_hash, announced_identity, app_data, announce_packet_hash,
                          is_path_response):
        h = destination_hash.hex()
        if h in _g["awaited"]:
            _g["back"].append(h)


def _pn_current():
    cur = _g["router"].get_outbound_propagation_node()
    return cur.hex() if cur else None


def _pick_pn():
    """Nearest (fewest hops) known node; switches only to a strictly nearer one."""
    RNS = _g["RNS"]
    try:
        with _pn_lock:
            known = list(_g["pns"])
            hops = {h: RNS.Transport.hops_to(bytes.fromhex(h)) for h in known
                    if RNS.Identity.recall(bytes.fromhex(h)) is not None}
            if not hops:
                return
            best = min(hops, key=hops.get)
            cur = _pn_current()
            if cur == best or (cur in hops and hops[cur] <= hops[best]):
                return
            _g["router"].set_outbound_propagation_node(bytes.fromhex(best))
            _g["last_fetch"] = 0
            _save_pn(known, best)
    except Exception as e:
        print("propagation node pick error: %r" % (e,))


def _save_pn(known, chosen):
    try:
        with open(_path("pn.json") + ".tmp", "w") as f:
            json.dump({"nodes": known, "chosen": chosen}, f)
        os.replace(_path("pn.json") + ".tmp", _path("pn.json"))
    except OSError:
        pass


def _restore_pn():
    try:
        with open(_path("pn.json")) as f:
            d = json.load(f)
        with _pn_lock:
            _g["pns"] = [h for h in d.get("nodes", []) if _hexs(h, 32)][-MAX_PNS:]
        if _hexs(d.get("chosen"), 32):
            _g["router"].set_outbound_propagation_node(bytes.fromhex(d["chosen"]))
    except Exception:
        pass


def _maybe_fetch(gap):
    """Ask the mailbox for what waits for me (in a thread: LXMF may wait for a path)."""
    pn, now = _pn_current(), _now()
    if not pn or now - _g["since"] < FETCH_FIRST_S or now - _g["last_fetch"] < gap:
        return
    RNS = _g["RNS"]
    if RNS.Identity.recall(bytes.fromhex(pn)) is None:
        RNS.Transport.request_path(bytes.fromhex(pn))
        return
    _g["last_fetch"] = now
    router, ident = _g["router"], _g["ident"]

    def job():
        try:
            router.request_messages_from_propagation_node(ident)
        except Exception as e:
            print("mailbox fetch failed: %r" % (e,))
    threading.Thread(target=job, daemon=True).start()
