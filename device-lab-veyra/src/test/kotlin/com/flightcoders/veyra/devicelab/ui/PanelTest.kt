// The real Device Lab panel inside a test IDE: it must open without a device, and with one it shows the device's
// settings. Saves a picture of the panel to build/device-lab-panel.png.
package com.flightcoders.veyra.devicelab.ui

import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

class PanelTest : BasePlatformTestCase() {
    fun `test the panel opens, finds the device and shows its settings`() {
        val lab = DeviceLabService.get(project)
        lab.app = "com.android.settings"
        val panel = DeviceLabPanel(project, testRootDisposable)
        panel.setSize(420, 1180)

        // Wait up to 60 s for a device; without one the panel must still be there, saying what to do.
        val until = System.currentTimeMillis() + 60_000
        while (System.currentTimeMillis() < until && lab.snapshot.state == null) {
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            Thread.sleep(200)
        }
        repeat(20) { PlatformTestUtil.dispatchAllEventsInIdeEventQueue(); Thread.sleep(100) }

        layout(panel)
        val image = BufferedImage(panel.width, panel.height, BufferedImage.TYPE_INT_ARGB)
        image.createGraphics().also { panel.printAll(it); it.dispose() }
        File("build").mkdirs()
        ImageIO.write(image, "png", File("build/device-lab-panel.png"))

        val texts = labels(panel)
        if (lab.snapshot.state != null) {
            assertTrue(texts.joinToString(), texts.any { it.startsWith("Android ") && it.contains("API") })
        } else {
            assertTrue(texts.joinToString(), texts.any { it.contains("emulator") || it.contains("adb") })
        }
    }

    private fun layout(c: java.awt.Component) {
        c.doLayout()
        (c as? java.awt.Container)?.components?.forEach { child ->
            if (child is javax.swing.JScrollPane) child.setSize(c.width, c.height)
            layout(child)
        }
        (c as? javax.swing.JViewport)?.view?.let { it.setSize(it.preferredSize.width.coerceAtLeast(c.width), it.preferredSize.height); layout(it) }
    }

    private fun labels(c: java.awt.Component): List<String> = buildList {
        if (c is javax.swing.JLabel) add(c.text.orEmpty())
        (c as? java.awt.Container)?.components?.forEach { addAll(labels(it)) }
    }
}
