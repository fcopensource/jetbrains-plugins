// End-to-end: drives a real emulator or phone over adb, checks every change took effect, then puts it all back.
// Skipped when no device is connected. DEVICE_LAB_SERIAL picks the device; otherwise the first online emulator.
package com.flightcoders.veyra.devicelab

import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test

class DeviceTest {
    companion object {
        private var control: DeviceControl? = null
        private var before: DeviceState? = null

        @BeforeClass @JvmStatic fun connect() {
            val adb = findAdb(null, null) ?: return
            val devices = runCatching { parseDevices(AdbProcess(adb).run(listOf("devices", "-l"), 15_000).text) }.getOrDefault(emptyList())
            val wanted = System.getenv("DEVICE_LAB_SERIAL").orEmpty()
            val device = devices.firstOrNull { it.online && it.serial == wanted } ?: devices.firstOrNull { it.online && it.emulator } ?: return
            control = DeviceControl(AdbProcess(adb), device.serial).also { before = it.readState() }
        }

        @AfterClass @JvmStatic fun putBack() {
            val c = control ?: return
            before?.let { c.restore(it) }
            runCatching { c.setAppLanguage(APP, "") }
        }

        /** Built into every Android image, so the test needs nothing installed. */
        const val APP = "com.android.settings"
    }

    private fun device(): DeviceControl { assumeTrue("no emulator or phone connected", control != null); return control!! }

    @Test fun `theme, font, display size, animations`() {
        val c = device()
        val start = c.readState()
        c.setTheme(Theme.DARK)
        c.setFontScale(largestFontScale(start.sdk))
        c.setDensity(densityFor(start.physicalDensity, 1.3f))
        c.setAnimationScale(0.5f)
        val s = c.readState()
        assertEquals(Theme.DARK, s.theme)
        assertEquals(largestFontScale(start.sdk), s.fontScale)
        assertEquals(densityFor(start.physicalDensity, 1.3f), s.density)
        assertEquals(0.5f, s.animationScale)
        c.setDensity(null)
        assertEquals(start.physicalDensity, c.readState().density)
    }

    @Test fun `developer options - layout bounds, taps, right-to-left`() {
        val c = device()
        c.setLayoutBounds(true); c.setShowTaps(true); c.setForceRtl(true)
        c.readState().let { assertTrue(it.layoutBounds); assertTrue(it.showTaps); assertTrue(it.forceRtl) }
        c.setLayoutBounds(false); c.setShowTaps(false); c.setForceRtl(false)
        c.readState().let { assertFalse(it.layoutBounds); assertFalse(it.showTaps); assertFalse(it.forceRtl) }
    }

    @Test fun `airplane mode and the clean status bar`() {
        val c = device()
        val sdk = c.readState().sdk
        c.setAirplane(true, sdk); assertTrue(c.readState().airplane)
        c.setAirplane(false, sdk); assertFalse(c.readState().airplane)
        c.setDemoMode(true); c.setDemoMode(false)
    }

    @Test fun `the app - language, permissions, restart, links, screenshot`() {
        val c = device()
        assertTrue(c.isInstalled(APP))
        assertFalse(c.isInstalled("com.flightcoders.not.installed"))
        if (c.readState().sdk >= 33) {
            c.setAppLanguage(APP, "ar"); assertEquals("ar", c.appLanguage(APP))
            c.setAppLanguage(APP, ""); assertEquals("", c.appLanguage(APP))
        }
        val contacts = "com.android.contacts"
        val permission = c.permissions(contacts).firstOrNull { it.shortName == "READ_CONTACTS" }
        if (permission != null) {
            c.setPermission(contacts, permission.name, false)
            assertFalse(c.permissions(contacts).first { it.name == permission.name }.granted)
            c.setPermission(contacts, permission.name, true)
            assertTrue(c.permissions(contacts).first { it.name == permission.name }.granted)
        }
        c.restartApp(APP)
        c.shell("am force-stop com.android.dialer")
        val opened = c.openLink("tel:123", null)
        assertTrue("tel: opens the dialer: $opened", opened.activity.orEmpty().contains("dialer"))
        assertNotNull("a cold start reports its time: $opened", opened.millis)
        val error = runCatching { c.openLink("veyra-nothing://here", null) }.exceptionOrNull()
        assertTrue(error?.message.orEmpty(), error?.message?.startsWith("No app opens") == true)
        val png = c.screenshot()
        assertTrue(png.size > 10_000 && png[1] == 'P'.code.toByte() && png[2] == 'N'.code.toByte())
    }

    @Test fun `reset puts everything back`() {
        val c = device()
        val start = c.readState()
        c.setTheme(if (start.theme == Theme.DARK) Theme.LIGHT else Theme.DARK)
        c.setFontScale(1.15f); c.setForceRtl(!start.forceRtl); c.setAnimationScale(5f)
        c.restore(start)
        assertEquals(start, c.readState())
    }
}
