// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.routesim

import io.motohub.android.module.MotoHubModuleContract
import io.motohub.android.routesim.plugin.routeSimulatorManifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModuleManifestTest {
    @Test
    fun manifestIdIsTheModuleId() {
        assertEquals("flyby-route-simulator", routeSimulatorManifest.id)
    }

    @Test
    fun manifestIsBuiltAgainstContract22() {
        assertEquals(22, routeSimulatorManifest.contractVersion)
        assertTrue(routeSimulatorManifest.contractVersion <= MotoHubModuleContract.CONTRACT_VERSION)
        assertTrue(routeSimulatorManifest.isIdSafe)
    }
}
