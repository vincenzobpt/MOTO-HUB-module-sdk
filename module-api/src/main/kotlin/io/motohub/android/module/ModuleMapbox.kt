// SPDX-License-Identifier: Apache-2.0
// Copyright (C) 2026 Vincenzo Buonomano.
// Part of the MOTO-HUB module SDK; see module-api/LICENSE.
package io.motohub.android.module

/**
 * The rider's own Mapbox account (contract 36). With it the 3D POV - the trip replay's on the
 * phone and the Ride Dashboard's on the motorcycle's screen - is drawn by Mapbox GL JS, from
 * Mapbox's own CDN, with Mapbox Standard: its map, terrain, trees, landmarks and light.
 *
 * Unlike the NAV data of contract 32 this is drawn on the TFT too: the token is the rider's,
 * under the rider's own Mapbox account and terms.
 *
 * Asked on any thread whenever a 3D POV opens: answer from memory, never from the network.
 */
interface ModuleMapboxAccount {

    /** The rider's public token ("pk.…"), or null while the module is not set up or is switched off. */
    fun accessToken(): String?

    /**
     * A 3D POV opened a Mapbox map: one billed map load, for the module's meter against the free
     * allowance. The tiles it then draws are part of that load.
     */
    fun countMapLoad()
}
