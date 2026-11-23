# SPDX-License-Identifier: MPL-2.0
# Derived from Columba (https://github.com/torlando-tech/columba), Copyright torlando-tech.
"""Sideband Telemeter (time + location) packing for LXMF FIELD_TELEMETRY.

Same byte layout as Columba's TelemeterCodec.kt / Sideband's sense.py:
  msgpack {0x01: time_s, 0x02: [i32 lat*1e6, i32 lon*1e6, i32 alt*1e2, u32 speed*1e2,
                                i32 bearing*1e2, u16 acc*1e2, last_update_s]}
with the six packed values as big-endian msgpack `bin`.
"""
import math
import struct

import RNS.vendor.umsgpack as msgpack

SID_TIME = 0x01
SID_LOCATION = 0x02


def _q(value, scale, lo, hi):
    # Half-up rounding like Kotlin's round(); clamped so struct never overflows.
    return max(lo, min(hi, int(math.floor(value * scale + 0.5))))


def pack_location(lat, lon, alt, speed, bearing, acc, ts):
    loc = [
        struct.pack("!i", _q(lat, 1e6, -2**31, 2**31 - 1)),
        struct.pack("!i", _q(lon, 1e6, -2**31, 2**31 - 1)),
        struct.pack("!i", _q(alt, 1e2, -2**31, 2**31 - 1)),
        struct.pack("!I", _q(max(speed, 0.0), 1e2, 0, 2**32 - 1)),
        struct.pack("!i", _q(bearing, 1e2, -2**31, 2**31 - 1)),
        struct.pack("!H", _q(max(acc, 0.0), 1e2, 0, 0xFFFF)),
        int(ts),
    ]
    return msgpack.packb({SID_TIME: int(ts), SID_LOCATION: loc})


def unpack_location(data):
    """-> dict(lat, lon, alt, speed, bearing, acc, ts) or None when not a Telemeter location."""
    try:
        loc = msgpack.unpackb(data)[SID_LOCATION]
        if len(loc) < 7:
            return None
        return {
            "lat": struct.unpack("!i", loc[0])[0] / 1e6,
            "lon": struct.unpack("!i", loc[1])[0] / 1e6,
            "alt": struct.unpack("!i", loc[2])[0] / 1e2,
            "speed": struct.unpack("!I", loc[3])[0] / 1e2,
            "bearing": struct.unpack("!i", loc[4])[0] / 1e2,
            "acc": struct.unpack("!H", loc[5])[0] / 1e2,
            "ts": int(loc[6]),
        }
    except Exception:
        return None
