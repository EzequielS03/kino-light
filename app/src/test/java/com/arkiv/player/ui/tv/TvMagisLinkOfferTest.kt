package com.arkiv.player.ui.tv

import com.arkiv.player.data.magis.MagisAccountState
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Covers `shouldOfferMagisLink` (Task 10; signature updated in Task 8, sub-project 2B: it no
 * longer looks at any Kino session, only [MagisAccountState]), the pure condition behind
 * "offer linking Magis right on entering the TV". Kept separate from the Composable on purpose
 * -this project has no Compose UI test infrastructure, see `entrarDesdeTv`'s KDoc-, so this
 * function is the only part of the feature that can be tested in a plain JVM test.
 */
class TvMagisLinkOfferTest {

    @Test fun `with no Magis linked and not dismissed it IS offered`() {
        assertEquals(true, shouldOfferMagisLink(MagisAccountState.None, dismissed = false))
    }

    @Test fun `already linked isn't offered, even if dismissed is false`() {
        assertEquals(
            false,
            shouldOfferMagisLink(MagisAccountState.Linked("a@b.co"), dismissed = false),
        )
    }

    @Test fun `not linked but already dismissed (Not now) isn't offered`() {
        assertEquals(false, shouldOfferMagisLink(MagisAccountState.None, dismissed = true))
    }

    @Test fun `linked AND dismissed isn't offered either (edge case, both reasons at once)`() {
        assertEquals(
            false,
            shouldOfferMagisLink(MagisAccountState.Linked("a@b.co"), dismissed = true),
        )
    }
}
