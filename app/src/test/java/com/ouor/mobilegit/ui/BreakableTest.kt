package com.ouor.mobilegit.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class BreakableTest {
    private val zwsp = '​'
    private val wj = '⁠'

    @Test fun breaksOnlyAfterPathSlashesAndKeepsNamesWhole() {
        val out = breakable("http://192.168.31.120:8080/field-notes.git")
        assertEquals("http://192.168.31.120:8080/$zwsp" + "field$wj-${wj}notes.git", out)
        // removing the invisible marks gives back the original text
        assertEquals("http://192.168.31.120:8080/field-notes.git", out.filterNot { it == zwsp || it == wj })
    }

    @Test fun commandsKeepSpacesAndBreakAfterEachPathSlash() {
        assertEquals("git clone https://github.com/$zwsp" + "chalk/$zwsp" + "chalk.git", breakable("git clone https://github.com/chalk/chalk.git"))
    }
}
