// Device Lab Veyra: the tool window. Pick a device and your app, then flip how the phone looks and behaves:
// theme, font size, display size, language, right-to-left, animations, layout bounds, taps, a clean status bar,
// airplane mode and the app's permissions. Presets apply the hardest combinations in one click; Reset puts it all back.
package com.flightcoders.veyra.devicelab.ui

import com.flightcoders.veyra.devicelab.ANIMATION_SCALES
import com.flightcoders.veyra.devicelab.DISPLAY_SIZES
import com.flightcoders.veyra.devicelab.Device
import com.flightcoders.veyra.devicelab.DeviceState
import com.flightcoders.veyra.devicelab.LANGUAGES
import com.flightcoders.veyra.devicelab.Permission
import com.flightcoders.veyra.devicelab.Theme
import com.flightcoders.veyra.devicelab.densityFor
import com.flightcoders.veyra.devicelab.fontScales
import com.flightcoders.veyra.devicelab.largestFontScale
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBRadioButton
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.FlowLayout
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import javax.swing.BoxLayout
import javax.swing.ButtonGroup
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel

class DeviceLabPanel(private val project: Project, parent: Disposable) : SimpleToolWindowPanel(true, true) {
    private val lab = DeviceLabService.get(project)
    /** True while the controls are being filled in from the device, so they don't send changes back. */
    private var updating = false
    private var shown: LabSnapshot? = null
    private var demo = mutableSetOf<String>()

    private val deviceBox = ComboBox<Device>().apply {
        renderer = SimpleListCellRenderer.create("No device") { it.label }
        addActionListener { if (!updating) (selectedItem as? Device)?.let { lab.select(it.serial) } }
    }
    private val appBox = ComboBox<String>().apply {
        isEditable = true
        toolTipText = "Your app's package name (applicationId). Found in build.gradle; type another one if you like."
        addActionListener { if (!updating) (selectedItem as? String)?.trim()?.takeIf { it.isNotEmpty() && it != lab.app }?.let { lab.app = it; loadApp() } }
    }

    private val themeButtons = Theme.entries.associateWith { JBRadioButton(it.label) }
    private val fontBox = ComboBox<Pair<String, Float>>().apply { renderer = SimpleListCellRenderer.create("") { it.first } }
    private val sizeBox = ComboBox<Pair<String, Int?>>().apply { renderer = SimpleListCellRenderer.create("") { it.first } }
    private val animBox = ComboBox(DefaultComboBoxModel(ANIMATION_SCALES.toTypedArray())).apply { renderer = SimpleListCellRenderer.create("") { it.first } }
    private val langBox = ComboBox(DefaultComboBoxModel(LANGUAGES.toTypedArray())).apply {
        renderer = SimpleListCellRenderer.create("") { it.second }
        toolTipText = "Your app's own language (Android 13+). Pseudolocales need pseudoLocalesEnabled true in your debug build type."
    }
    private val rtl = JBCheckBox("Right-to-left layout")
    private val bounds = JBCheckBox("Layout bounds")
    private val taps = JBCheckBox("Show taps")
    private val cleanBar = JBCheckBox("Clean status bar (12:00, full battery)")
    private val airplane = JBCheckBox("Airplane mode (offline)")

    private val permissionsPanel = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS); isOpaque = false }
    private val linkBox = ComboBox<String>().apply { isEditable = true; toolTipText = "Deep links found in your AndroidManifest.xml and Navigation graphs, or type any link" }
    private val status = JBLabel(" ").apply { border = JBUI.Borders.empty(6, 2, 2, 2) }
    private val deviceInfo = JBLabel("").apply { foreground = UIUtil.getContextHelpForeground() }

    private val content: JComponent

    init {
        ButtonGroup().apply { themeButtons.values.forEach(::add) }
        themeButtons.forEach { (theme, button) -> button.addActionListener { if (!updating) apply("${theme.label} theme") { c, _ -> c.setTheme(theme) } } }
        fontBox.addActionListener { if (!updating) (fontBox.selectedItem as? Pair<*, *>)?.let { (label, scale) -> apply("Font size $label") { c, _ -> c.setFontScale(scale as Float) } } }
        sizeBox.addActionListener { if (!updating) (sizeBox.selectedItem as? Pair<*, *>)?.let { (label, dpi) -> apply("Display size $label") { c, _ -> c.setDensity(dpi as Int?) } } }
        animBox.addActionListener { if (!updating) (animBox.selectedItem as? Pair<*, *>)?.let { (label, scale) -> apply("Animations $label") { c, _ -> c.setAnimationScale(scale as Float) } } }
        langBox.addActionListener { if (!updating) (langBox.selectedItem as? Pair<*, *>)?.let { (tag, label) -> setLanguage(tag as String, label as String) } }
        rtl.addActionListener { if (!updating) apply(if (rtl.isSelected) "Right-to-left on" else "Right-to-left off", restartApp = true) { c, _ -> c.setForceRtl(rtl.isSelected) } }
        bounds.addActionListener { if (!updating) apply(if (bounds.isSelected) "Layout bounds on" else "Layout bounds off") { c, _ -> c.setLayoutBounds(bounds.isSelected) } }
        taps.addActionListener { if (!updating) apply(if (taps.isSelected) "Show taps on" else "Show taps off") { c, _ -> c.setShowTaps(taps.isSelected) } }
        airplane.addActionListener { if (!updating) apply(if (airplane.isSelected) "Airplane mode on: your app is offline" else "Airplane mode off") { c, s -> c.setAirplane(airplane.isSelected, s.sdk) } }
        cleanBar.addActionListener { if (!updating) setDemo(cleanBar.isSelected) }

        content = panel {
            row("Device:") {
                cell(deviceBox).align(AlignX.FILL).resizableColumn()
                button("") { lab.refresh() }.applyToComponent { icon = AllIcons.Actions.Refresh; toolTipText = "Look for devices again" }
            }
            row("") { cell(deviceInfo) }
            row("App:") { cell(appBox).align(AlignX.FILL).resizableColumn() }

            group("Presets") {
                row {
                    // Two by two, so the presets fit a narrow tool window.
                    cell(JPanel(java.awt.GridLayout(0, 2, JBUI.scale(6), JBUI.scale(6))).apply {
                        isOpaque = false
                        add(preset("Worst case", AllIcons.General.Warning, "Largest font and display size, right-to-left and dark theme at once: the hardest screen your layout will meet") { worstCase() })
                        add(preset("Screenshot ready", AllIcons.Actions.Lightning, "Clean status bar, no layout bounds or taps, normal animations") { screenshotReady() })
                        add(preset("Slow motion", AllIcons.Actions.Pause, "Animations 5× slower, to watch every transition") { animBox.selectedItem = ANIMATION_SCALES.first { it.second == 5f } })
                        add(preset("Reset", AllIcons.Actions.Rollback, "Put the device back the way it was before Device Lab changed it") { reset() })
                    }).align(AlignX.FILL)
                }
            }
            group("Looks") {
                row("Theme:") {
                    cell(JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(10), 0)).apply { isOpaque = false; themeButtons.values.forEach(::add) })
                }
                row("Font size:") { cell(fontBox).align(AlignX.FILL) }
                row("Display size:") { cell(sizeBox).align(AlignX.FILL) }
                row("App language:") { cell(langBox).align(AlignX.FILL) }
                row { cell(rtl) }
            }
            group("Debug") {
                row("Animations:") { cell(animBox).align(AlignX.FILL) }
                row { cell(bounds); cell(taps) }
                row { cell(cleanBar) }
                row { cell(airplane) }
            }
            group("Deep links") {
                row { cell(linkBox).align(AlignX.FILL).resizableColumn(); button("") { openLink() }.applyToComponent { icon = AllIcons.Actions.Execute; toolTipText = "Open this link on the device" } }
            }
            group("App") {
                row {
                    button("Restart app") { appAction("Restarted ${lab.app}") { it.restartApp(lab.app) } }.applyToComponent { icon = AllIcons.Actions.Restart }
                    button("Screenshot") { screenshot() }.applyToComponent { icon = AllIcons.Actions.Dump }
                    button("Clear data…") { clearData() }.applyToComponent { icon = AllIcons.Actions.GC }
                }
            }
            collapsibleGroup("Permissions") {
                row { cell(permissionsPanel).align(AlignX.FILL) }
                row { button("Reload") { loadApp() } }
            }.apply { expanded = true }
            row { cell(status).align(AlignX.FILL) }
        }.apply { border = JBUI.Borders.empty(4, 10, 10, 10) }

        setContent(JBScrollPane(content).apply { border = JBUI.Borders.empty() })
        fillApps()
        fillLinks()
        lab.subscribe(parent) { show(it) }
    }

    private fun preset(text: String, icon: javax.swing.Icon, tip: String, action: () -> Unit) =
        JButton(text, icon).apply { toolTipText = tip; addActionListener { action() } }

    /* ---------- Device state → controls ---------- */

    private fun show(snapshot: LabSnapshot) {
        val previous = shown
        shown = snapshot
        updating = true
        try {
            if (previous?.devices != snapshot.devices || previous.selected != snapshot.selected) {
                deviceBox.model = DefaultComboBoxModel(snapshot.devices.toTypedArray())
                deviceBox.selectedItem = snapshot.selected
            }
            val state = snapshot.state
            val enabled = state != null
            listOf(fontBox, sizeBox, animBox, langBox, rtl, bounds, taps, cleanBar, airplane, linkBox).forEach { it.isEnabled = enabled }
            themeButtons.values.forEach { it.isEnabled = enabled }
            deviceInfo.text = when {
                snapshot.adbPath == null -> "adb not found. Install the Android SDK, or choose adb with Set adb Path in Tools."
                snapshot.devices.isEmpty() -> "Start an emulator or connect a phone with USB debugging on."
                state == null -> snapshot.selected?.label.orEmpty()
                else -> "Android ${state.release} (API ${state.sdk}) · ${state.density} dpi" + if (snapshot.changed) " · changed by Device Lab" else ""
            }
            if (state != null) showState(state, snapshot.selected!!.serial)
        } finally { updating = false }
        if (previous?.selected?.serial != snapshot.selected?.serial || (previous?.state == null && snapshot.state != null)) loadApp()
    }

    private fun showState(state: DeviceState, serial: String) {
        themeButtons.getValue(state.theme).isSelected = true
        val fonts = fontScales(state.sdk).toMutableList()
        if (fonts.none { near(it.second, state.fontScale) }) fonts += "${Math.round(state.fontScale * 100)} %" to state.fontScale
        fontBox.model = DefaultComboBoxModel(fonts.sortedBy { it.second }.toTypedArray())
        fontBox.selectedItem = fonts.first { near(it.second, state.fontScale) }

        val sizes: MutableList<Pair<String, Int?>> = DISPLAY_SIZES.map { (label, f) ->
            val dpi = densityFor(state.physicalDensity, f)
            (if (f == 1f) "Default (${state.physicalDensity} dpi)" else "$label ($dpi dpi)") to (if (f == 1f) null else dpi)
        }.toMutableList()
        val current = sizes.firstOrNull { (it.second ?: state.physicalDensity) == state.density }
            ?: ("Custom (${state.density} dpi)" to state.density).also { sizes += it }
        sizeBox.model = DefaultComboBoxModel(sizes.toTypedArray())
        sizeBox.selectedItem = current

        animBox.selectedItem = ANIMATION_SCALES.firstOrNull { near(it.second, state.animationScale) } ?: ANIMATION_SCALES[2]
        rtl.isSelected = state.forceRtl
        bounds.isSelected = state.layoutBounds
        taps.isSelected = state.showTaps
        airplane.isSelected = state.airplane
        cleanBar.isSelected = serial in demo
        langBox.isEnabled = state.sdk >= 33
        if (state.sdk < 33) langBox.toolTipText = "App languages need Android 13 or newer"
    }

    private fun near(a: Float, b: Float) = Math.abs(a - b) < 0.01f

    /* ---------- The app ---------- */

    private fun fillApps() {
        updating = true
        try {
            val apps = (listOf(lab.app) + lab.detectedApps()).filter { it.isNotBlank() }.distinct()
            appBox.model = DefaultComboBoxModel(apps.toTypedArray())
            appBox.selectedItem = lab.app.ifBlank { null }
        } finally { updating = false }
    }

    private fun fillLinks() {
        val links = lab.detectedLinks().map { it.uri }
        linkBox.model = DefaultComboBoxModel(links.toTypedArray())
        if (links.isEmpty()) linkBox.editor.item = "https://"
    }

    /** The app's language and permissions, read from the device. */
    private fun loadApp() {
        val pkg = lab.app
        if (pkg.isBlank()) { showPermissions(null, "Type your app's package name above."); return }
        lab.run({ c ->
            val sdk = lab.snapshot.state?.sdk ?: 0
            if (!c.isInstalled(pkg)) null
            else Triple(c.permissions(pkg), if (sdk >= 33) c.appLanguage(pkg) else "", sdk)
        }) { result ->
            val app = result.getOrNull()
            if (result.isFailure) { showPermissions(null, result.exceptionOrNull()?.message ?: ""); return@run }
            if (app == null) { showPermissions(null, "$pkg isn't installed on this device. Run your app once, then click Reload."); return@run }
            updating = true
            try { langBox.selectedItem = LANGUAGES.firstOrNull { it.first == app.second } ?: LANGUAGES.first() } finally { updating = false }
            showPermissions(app.first, if (app.first.isEmpty()) "$pkg doesn't ask for any runtime permissions." else null)
        }
    }

    private fun showPermissions(list: List<Permission>?, message: String?) {
        permissionsPanel.removeAll()
        message?.let { permissionsPanel.add(JBLabel(it).apply { foreground = UIUtil.getContextHelpForeground() }) }
        list.orEmpty().forEach { permission ->
            val box = JBCheckBox(permission.shortName.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }, permission.granted).apply {
                toolTipText = permission.name
                addActionListener {
                    val grant = isSelected
                    lab.run({ it.setPermission(lab.app, permission.name, grant) }) { r ->
                        if (r.isFailure) { isSelected = !grant; say("Couldn't change ${permission.shortName}: ${r.exceptionOrNull()?.message}", false) }
                        else say("${permission.shortName} ${if (grant) "granted" else "revoked"}", true)
                    }
                }
            }
            permissionsPanel.add(JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply { isOpaque = false; add(box) })
        }
        permissionsPanel.revalidate(); permissionsPanel.repaint()
    }

    private fun setLanguage(tag: String, label: String) {
        val pkg = lab.app
        if (pkg.isBlank()) { say("Type your app's package name first", false); return }
        lab.run({ it.setAppLanguage(pkg, tag) }) { r ->
            if (r.isFailure) say("Language: ${r.exceptionOrNull()?.message}", false)
            else say(if (tag.isEmpty()) "$pkg follows the system language again" else "$pkg is now in $label", true)
        }
    }

    /* ---------- Changes ---------- */

    private fun apply(what: String, restartApp: Boolean = false, change: (com.flightcoders.veyra.devicelab.DeviceControl, DeviceState) -> Unit) {
        say("$what…", null)
        lab.change(what, { error ->
            if (error != null) say(error, false)
            else if (restartApp && lab.app.isNotBlank()) appAction("$what (app restarted)") { it.restartApp(lab.app) }
            else say(what, true)
        }, change)
    }

    private fun appAction(what: String, work: (com.flightcoders.veyra.devicelab.DeviceControl) -> Unit) {
        if (lab.app.isBlank()) { say("Type your app's package name first", false); return }
        lab.run(work) { r -> if (r.isFailure) say(r.exceptionOrNull()?.message ?: "Failed", false) else say(what, true) }
    }

    private fun setDemo(on: Boolean) {
        val serial = shown?.selected?.serial ?: return
        apply(if (on) "Clean status bar on" else "Clean status bar off") { c, _ -> c.setDemoMode(on); if (on) demo += serial else demo -= serial }
    }

    private fun worstCase() = apply("Worst case: largest font and display, right-to-left, dark", restartApp = true) { c, s ->
        c.setTheme(Theme.DARK)
        c.setFontScale(largestFontScale(s.sdk))
        c.setDensity(densityFor(s.physicalDensity, DISPLAY_SIZES.last().second))
        c.setForceRtl(true)
    }

    private fun screenshotReady() {
        val serial = shown?.selected?.serial ?: return
        apply("Screenshot ready: clean status bar") { c, _ ->
            c.setLayoutBounds(false); c.setShowTaps(false); c.setAnimationScale(1f); c.setDemoMode(true); demo += serial
        }
    }

    private fun reset() {
        say("Resetting…", null)
        val serial = shown?.selected?.serial
        val pkg = lab.app
        lab.reset { error ->
            if (error != null) { say(error, false); return@reset }
            serial?.let { demo -= it }
            if (pkg.isNotBlank() && (shown?.state?.sdk ?: 0) >= 33) lab.run({ runCatching { it.setAppLanguage(pkg, "") } }) { loadApp() }
            say(if (shown?.changed == false) "The device is the way it was before" else "Reset", true)
        }
    }

    private fun openLink() {
        val uri = (linkBox.editor.item as? String)?.trim().orEmpty()
        if (uri.isEmpty() || !uri.contains(':')) { say("Type a link like myapp://orders/42 or https://…", false); return }
        val pkg = lab.app
        say("Opening $uri…", null)
        lab.run({ c -> c.openLink(uri, pkg.takeIf { it.isNotBlank() && c.isInstalled(it) }) }) { r ->
            if (r.isFailure) say(r.exceptionOrNull()?.message ?: "Couldn't open the link", false)
            else say("Opened $uri in ${r.getOrNull()!!.summary}", true)
        }
    }

    private fun screenshot() {
        val model = shown?.selected?.model?.replace(Regex("[^A-Za-z0-9]+"), "-")?.trim('-') ?: "device"
        lab.run({ it.screenshot() }) { r ->
            val png = r.getOrElse { say("Screenshot: ${it.message}", false); return@run }
            val folder = File(System.getProperty("user.home"), "Pictures${File.separator}Device Lab Veyra").apply { mkdirs() }
            val file = File(folder, "$model-${SimpleDateFormat("yyyyMMdd-HHmmss").format(Date())}.png")
            file.writeBytes(png)
            LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file)?.let { FileEditorManager.getInstance(project).openFile(it, true) }
            say("Screenshot saved to ${file.path}", true)
        }
    }

    private fun clearData() {
        val pkg = lab.app.ifBlank { say("Type your app's package name first", false); return }
        val ok = Messages.showYesNoDialog(project, "Delete all of $pkg's data on the device: accounts, settings, databases and files? The app starts like a fresh install.", "Clear App Data", "Clear Data", "Cancel", Messages.getWarningIcon())
        if (ok == Messages.YES) appAction("$pkg's data cleared") { it.clearData(pkg) }
    }

    /** The status line: ok = true (done), false (failed), null (working). */
    private fun say(text: String, ok: Boolean?) {
        status.text = text
        status.icon = when (ok) { true -> AllIcons.General.InspectionsOK; false -> AllIcons.General.Error; null -> AllIcons.Process.Step_1 }
        status.foreground = if (ok == false) JBColor.RED else UIUtil.getLabelForeground()
    }
}
