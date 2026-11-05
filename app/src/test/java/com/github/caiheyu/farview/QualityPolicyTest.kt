package com.github.caiheyu.farview

import com.github.caiheyu.farview.session.QualityPolicy
import org.junit.Assert.*
import org.junit.Test

class QualityPolicyTest {
    @Test fun startsAt720AndRequiresStableHeadroom() {
        val p = QualityPolicy(); p.reset(1000)
        assertEquals(720, p.target(11000, 720, 30, true))
        assertEquals(1080, p.target(22000, 720, 30, true))
    }
    @Test fun unsupported1080IsNeverSelected() {
        val p = QualityPolicy(); p.reset(1000)
        assertEquals(720, p.target(99000, 720, 30, false))
    }
    @Test fun congestionResetsTheUpgradeWindow() {
        val p = QualityPolicy(); p.reset(1000); p.congestion(19000)
        assertEquals(720, p.target(22000, 720, 30, true))
        assertEquals(1080, p.target(40000, 720, 30, true))
    }
    @Test fun downgradeHasCooldownBeforeAnotherUpgrade() {
        val p = QualityPolicy(); p.reset(1000)
        assertEquals(1080, p.target(22000, 720, 40, true))
        assertEquals(1080, p.target(23000, 1080, 400, true))
        assertEquals(720, p.target(33000, 1080, 400, true))
        assertEquals(720, p.target(44000, 720, 40, true))
        assertEquals(1080, p.target(55000, 720, 40, true))
    }
}
