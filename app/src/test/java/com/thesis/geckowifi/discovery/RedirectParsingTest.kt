package com.thesis.geckowifi.discovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RedirectParsingTest {

    @Test
    fun metaRefresh_basic() {
        val html = """<html><head><meta http-equiv="refresh" content="0;url=http://portal.gecko-a.lab/?tok=1&amp;x=2"></head></html>"""
        assertEquals("http://portal.gecko-a.lab/?tok=1&x=2", RedirectParsing.metaRefreshTarget(html))
    }

    @Test
    fun metaRefresh_quotedUrlUppercaseAndSpaces() {
        val html = """<META HTTP-EQUIV='Refresh' CONTENT='5; URL="https://login.example/start"'>"""
        assertEquals("https://login.example/start", RedirectParsing.metaRefreshTarget(html))
    }

    @Test
    fun metaRefresh_absentOrWithoutUrl() {
        assertNull(RedirectParsing.metaRefreshTarget("<html><body>Login</body></html>"))
        assertNull(RedirectParsing.metaRefreshTarget("""<meta http-equiv="refresh" content="30">"""))
    }

    @Test
    fun resolve_relativeAndAbsolute() {
        assertEquals("https://portal.gecko-a.lab/complete?a=1",
            RedirectParsing.resolve("https://portal.gecko-a.lab/login", "/complete?a=1"))
        assertEquals("http://192.168.8.1:2050/opennds_auth/",
            RedirectParsing.resolve("https://portal.gecko-a.lab/", "http://192.168.8.1:2050/opennds_auth/"))
    }

    @Test
    fun resolve_rejectsNonHttpTargets() {
        assertNull(RedirectParsing.resolve("http://a.example/", "javascript:alert(1)"))
        assertNull(RedirectParsing.resolve("http://a.example/", "file:///etc/passwd"))
    }
}
