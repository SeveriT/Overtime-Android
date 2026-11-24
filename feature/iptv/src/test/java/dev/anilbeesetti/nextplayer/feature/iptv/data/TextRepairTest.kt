package dev.anilbeesetti.nextplayer.feature.iptv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class TextRepairTest {
    @Test
    fun repairsUtf8ReadAsKorean() {
        // Real names from a provider: ä (C3 A4) read as EUC-KR is 채
        assertEquals(
            "[Rutuu] (24/11) 18:10 O2-Jyväskylä - SPV",
            TextRepair.repair("[Rutuu] (24/11) 18:10 O2-Jyv채skyl채 - SPV"),
        )
        assertEquals(
            "TPS - EräViikingit",
            TextRepair.repair("TPS - Er채Viikingit"),
        )
        assertEquals(
            "Asiantuntijalähetys",
            TextRepair.repair("Asiantuntijal채hetys"),
        )
        // A run of them (Hääyö) and ö / å / é
        assertEquals(
            "Hääyö",
            TextRepair.repair("H채채y철"),
        )
        assertEquals(
            "Åbo får café",
            TextRepair.repair("Åbo f책r caf챕"),
        )
    }

    @Test
    fun leavesRealKoreanAlone() {
        val korean = "채널 뉴스 KBS 1TV"
        assertSame(
            korean,
            TextRepair.repair(korean),
        )
    }

    @Test
    fun repairsDoubleEncodedUtf8() {
        assertEquals(
            "Asiantuntijalähetys",
            TextRepair.repair("AsiantuntijalÃ¤hetys"),
        )
        assertEquals(
            "Ääni",
            TextRepair.repair("Ã\u0084Ã¤ni"),
        )
    }

    @Test
    fun repairsWindowsPunctuation() {
        assertEquals(
            "Uutiset – sää “tänään”",
            TextRepair.repair("Uutiset \u0096 sää \u0093tänään\u0094"),
        )
    }

    @Test
    fun returnsCleanTextAsIs() {
        val plain = "Yle TV1 HD"
        assertSame(
            plain,
            TextRepair.repair(plain),
        )
        val finnish = "Jyväskylä – Hääyö"
        assertSame(
            finnish,
            TextRepair.repair(finnish),
        )
    }
}
