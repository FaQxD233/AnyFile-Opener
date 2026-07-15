package com.anyfile.x

import com.anyfile.x.data.DefaultAppRuleStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DefaultAppRuleStoreTest {

    @Test
    fun normalizesMimeAndStripsParameters() {
        assertEquals(
            "application/pdf",
            DefaultAppRuleStore.normalizeMime(" Application/PDF; charset=UTF-8 ")
        )
        assertNull(DefaultAppRuleStore.normalizeMime("*/*"))
        assertNull(DefaultAppRuleStore.normalizeMime("not-a-mime"))
        assertNull(DefaultAppRuleStore.normalizeMime("invalid/type/extra"))
    }

    @Test
    fun extractsNormalizedExtension() {
        assertEquals("pdf", DefaultAppRuleStore.normalizeExtension("Report.PDF"))
        assertEquals("gz", DefaultAppRuleStore.normalizeExtension("archive.tar.gz"))
        assertNull(DefaultAppRuleStore.normalizeExtension("README"))
    }
}
