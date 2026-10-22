// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.mapopt

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class TokenTest {

    @Test
    fun onlyAPublicTokenPasses() {
        assertNull(tokenProblem("  pk.eyJ1IjoiYSJ9.abc  "))
        assertNotNull(tokenProblem("sk.eyJ1IjoiYSJ9.abc"))
        assertNotNull(tokenProblem("eyJ1IjoiYSJ9"))
        assertNotNull(tokenProblem("pk.a b"))
        assertNotNull(tokenProblem(""))
    }
}
