// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
// Ported from headunit-revived (AGPLv3): app/.../aap/Utils.java
// Original project (c) Michael Reid / Andre Rinas — headunit-revived, AGPLv3.
// Adapted for MOTO-HUB: package + logging shim only.
package io.motohub.android.aa;

public class Utils {

    public static long ms_sleep(long ms) {
        try {
            Thread.sleep(ms);
            return (ms);
        } catch (InterruptedException e) {
            AaLog.INSTANCE.e("Exception e: " + e);
            return (0);
        }
    }

    public static void intToBytes(int value, int offset, byte[] buf) {
        buf[offset] = (byte) (value / 256);                            // big-endian 16-bit length
        buf[offset + 1] = (byte) (value % 256);
    }

    public static int bytesToInt(byte[] buf, int idx, boolean isShort) {
        if (isShort) {
            return ((buf[idx] & 0xFF) << 8) + (buf[idx + 1] & 0xFF);
        }
        return ((buf[idx] & 0xFF) << 24) + ((buf[idx + 1] & 0xFF) << 16) + ((buf[idx + 2] & 0xFF) << 8) + (buf[idx + 3] & 0xFF);
    }
}
