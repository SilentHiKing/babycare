package com.zero.babycare.statistics

import com.zero.babycare.statistics.mapper.WhoSexParser
import com.zero.babycare.statistics.standard.WhoSex
import org.junit.Assert.assertEquals
import org.junit.Test

class WhoSexParserTest {

    @Test
    fun `female text is parsed as girl before checking male substring`() {
        assertEquals(WhoSex.GIRL, WhoSexParser.parse("female"))
    }

    @Test
    fun `common localized gender labels are parsed`() {
        assertEquals(WhoSex.BOY, WhoSexParser.parse("男"))
        assertEquals(WhoSex.GIRL, WhoSexParser.parse("女"))
        assertEquals(WhoSex.BOY, WhoSexParser.parse("boy"))
        assertEquals(WhoSex.GIRL, WhoSexParser.parse("girl"))
        assertEquals(WhoSex.BOY, WhoSexParser.parse("male"))
    }

    @Test
    fun `unknown gender text remains unknown for percentile empty state`() {
        assertEquals(WhoSex.UNKNOWN, WhoSexParser.parse("unknown"))
    }
}
