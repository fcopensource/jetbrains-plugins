// The Hotspots tool window: the Map, Top Hotspots, Changes Together and Knowledge Risks.
package com.flightcoders.veyra.hotspots.ui

import com.flightcoders.veyra.hotspots.Analysis
import com.flightcoders.veyra.hotspots.ColorMode
import com.flightcoders.veyra.hotspots.Coupling
import com.flightcoders.veyra.hotspots.FileStat
import com.flightcoders.veyra.hotspots.HotspotsListener
import com.flightcoders.veyra.hotspots.HotspotsService
import com.flightcoders.veyra.hotspots.Knowledge
import com.flightcoders.veyra.hotspots.Level
import com.flightcoders.veyra.hotspots.Status
import com.flightcoders.veyra.hotspots.ago
import com.flightcoders.veyra.hotspots.heatRgb
import com.flightcoders.veyra.hotspots.level
import com.flightcoders.veyra.hotspots.periodLabel
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.JBColor
import com.intellij.ui.SearchTextField
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.content.ContentFactory
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.event.MouseEvent
import javax.swing.BoxLayout
import javax.swing.DefaultListModel
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.event.DocumentEvent
import kotlin.math.roundToInt

class HotspotsToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val cf = ContentFactory.getInstance()
        val map = MapPanel(project)
        toolWindow.contentManager.addContent(cf.createContent(map, "Map", false).also { it.isCloseable = false; it.setDisposer(map) })
        toolWindow.contentManager.addContent(cf.createContent(ListPanel(project, ListKind.TOP), "Top Hotspots", false).also { it.isCloseable = false })
        toolWindow.contentManager.addContent(cf.createContent(ListPanel(project, ListKind.COUPLING), "Changes Together", false).also { it.isCloseable = false })
        toolWindow.contentManager.addContent(cf.createContent(ListPanel(project, ListKind.KNOWLEDGE), "Knowledge Risks", false).also { it.isCloseable = false })
    }

    companion object {
        const val ID = "Hotspots"
        /** Opens the tool window on the map, with `path` selected. */
        fun reveal(project: Project, path: String?) {
            val tw = ToolWindowManager.getInstance(project).getToolWindow(ID) ?: return
            tw.activate {
                val content = tw.contentManager.contents.firstOrNull() ?: return@activate
                tw.contentManager.setSelectedContent(content)
                if (path != null) (content.component as? MapPanel)?.reveal(path)
            }
        }
    }
}

fun levelColor(lv: Level): Color = when (lv) {
    Level.CRITICAL -> JBColor(0xd6264a, 0xff4f6d)
    Level.HIGH -> JBColor(0xc2410c, 0xff8a50)
    Level.MEDIUM -> JBColor(0x7a2bd0, 0xb48cff)
    Level.LOW -> JBColor(0x0e6b80, 0x6fb3c8)
    Level.NONE -> JBColor.GRAY
}

/** A small rounded swatch, for list rows. */
class Swatch(private val color: Color, private val size: Int = JBUI.scale(10)) : Icon {
    override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) { g.color = color; g.fillRoundRect(x, y + 1, size, size, 4, 4) }
    override fun getIconWidth() = size + JBUI.scale(2)
    override fun getIconHeight() = size + 2
}

fun openFile(project: Project, rel: String) {
    HotspotsService.of(project).fileFor(rel)?.let { FileEditorManager.getInstance(project).openFile(it, true) }
}

// ------------------------------------------------------------------------------------------------ the map

class MapPanel(private val project: Project) : SimpleToolWindowPanel(true, true), com.intellij.openapi.Disposable {
    private val service = HotspotsService.of(project)
    private val crumbs = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply { isOpaque = false }
    private val subtitle = JBLabel().apply { foreground = UIUtil.getContextHelpForeground() }
    private val legend = JBLabel().apply { foreground = UIUtil.getContextHelpForeground() }
    private val side = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS); border = JBUI.Borders.empty(8, 12) }
    private val treemap = TreemapComponent(onSelect = { select(it) }, onOpen = { openFile(project, it) }, onFocus = { focus(it) })
    private val modes = ComboBox(ColorMode.entries.toTypedArray()).apply {
        renderer = SimpleListCellRenderer.create("") { it.label }
        toolTipText = "What the colours show"
        addActionListener { treemap.mode = selectedItem as ColorMode; updateLegend(); renderSide() }
    }
    private val search = SearchTextField(false).apply {
        textEditor.emptyText.text = "Filter files"
        addDocumentListener(object : com.intellij.ui.DocumentAdapter() { override fun textChanged(e: DocumentEvent) { treemap.query = text.trim(); renderSide() } })
    }

    init {
        val actions = ActionManager.getInstance().getAction("VeyraHotspots.Toolbar") as DefaultActionGroup
        val toolbar = ActionManager.getInstance().createActionToolbar("VeyraHotspotsMap", actions, true).apply { targetComponent = this@MapPanel }
        val top = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(4, 8)
            add(JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0)).apply { isOpaque = false; add(toolbar.component); add(modes); add(search); add(subtitle) }, BorderLayout.WEST)
        }
        val bar = JPanel(BorderLayout()).apply { border = JBUI.Borders.empty(0, 12, 4, 12); add(crumbs, BorderLayout.WEST); add(legend, BorderLayout.EAST) }
        val north = JPanel(BorderLayout()).apply { add(top, BorderLayout.NORTH); add(bar, BorderLayout.SOUTH) }
        val center = JPanel(BorderLayout()).apply {
            add(JPanel(BorderLayout()).apply { border = JBUI.Borders.empty(0, 12, 10, 4); add(treemap) }, BorderLayout.CENTER)
            add(JBScrollPane(side).apply { preferredSize = JBUI.size(300, 200); border = JBUI.Borders.empty() }, BorderLayout.EAST)
        }
        setContent(JPanel(BorderLayout()).apply { add(north, BorderLayout.NORTH); add(center, BorderLayout.CENTER) })
        project.messageBus.connect(this).subscribe(HotspotsListener.TOPIC, HotspotsListener { update() })
        update()
    }

    private fun update() {
        val a = service.analysis
        treemap.analysis = a
        if (treemap.selected != null && a?.byPath?.containsKey(treemap.selected!!) != true) treemap.selected = null
        subtitle.text = when (service.status) {
            Status.SCANNING -> if (a == null) "Reading your git history…" else "Updating…"
            Status.NO_GIT -> "This project isn't a git repository."
            Status.FAILED -> "Couldn't read git history: ${service.error ?: "unknown error"}"
            else -> a?.let { "${"%,d".format(it.commits)} commits by ${it.authors} ${if (it.authors == 1) "person" else "people"} in ${periodLabel(service.months)} · ${"%,d".format(it.files.size)} files" } ?: ""
        }
        updateLegend(); renderCrumbs(); renderSide()
    }

    private fun updateLegend() {
        val m = treemap.mode
        legend.text = "<html>${m.low} <font color='#${hex(heatRgb(0.0))}'>■</font><font color='#${hex(heatRgb(0.3))}'>■</font><font color='#${hex(heatRgb(0.52))}'>■</font><font color='#${hex(heatRgb(0.76))}'>■</font><font color='#${hex(heatRgb(1.0))}'>■</font> ${m.high} · ${esc(m.hint)} · tile size = lines of code</html>"
    }

    private fun focus(path: String) { treemap.focus = path; treemap.selected = null; renderCrumbs(); renderSide() }
    private fun select(path: String?) { treemap.selected = path; renderSide() }

    fun reveal(path: String) {
        if (service.analysis?.byPath?.containsKey(path) != true) return
        if (treemap.focus.isNotEmpty() && !path.startsWith(treemap.focus + "/")) treemap.focus = ""
        if (treemap.visibleRect(path) == null) treemap.focus = path.substringBeforeLast('/', "")
        select(path); renderCrumbs()
    }

    private fun renderCrumbs() {
        crumbs.removeAll()
        val parts = if (treemap.focus.isEmpty()) emptyList() else treemap.focus.split('/')
        fun link(text: String, target: String) = JBLabel("<html><a href=''>${esc(text)}</a></html>").apply {
            cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
            addMouseListener(object : java.awt.event.MouseAdapter() { override fun mouseClicked(e: MouseEvent) = focus(target) })
        }
        crumbs.add(link(project.name, ""))
        parts.forEachIndexed { i, p -> crumbs.add(JBLabel("  ›  ").apply { foreground = UIUtil.getContextHelpForeground() }); crumbs.add(link(p, parts.subList(0, i + 1).joinToString("/"))) }
        crumbs.revalidate(); crumbs.repaint()
    }

    private fun renderSide() {
        side.removeAll()
        val a = service.analysis
        val sel = treemap.selected?.let { a?.byPath?.get(it) }
        if (a != null && sel != null) fileDetails(sel, a) else topList(a)
        side.add(javax.swing.Box.createVerticalGlue())
        side.revalidate(); side.repaint()
    }

    private fun heading(text: String) = JBLabel(text).apply { font = JBUI.Fonts.label().asBold(); border = JBUI.Borders.empty(12, 0, 6, 0); alignmentX = 0f }
    private fun note(text: String) = JBLabel("<html>${text}</html>").apply { foreground = UIUtil.getContextHelpForeground(); alignmentX = 0f }

    private fun topList(a: Analysis?) {
        side.add(JBLabel(if (treemap.focus.isEmpty()) "Top hotspots" else "Top hotspots in ${treemap.focus.substringAfterLast('/')}").apply { font = JBUI.Fonts.label(14f).asBold(); alignmentX = 0f })
        side.add(note("Complex code that changes often.<br>Start refactoring here."))
        side.add(javax.swing.Box.createVerticalStrut(JBUI.scale(8)))
        if (a == null) return
        val q = treemap.query.lowercase()
        val prefix = if (treemap.focus.isEmpty()) "" else treemap.focus + "/"
        val list = a.ranked.filter { it.path.startsWith(prefix) && (q.isEmpty() || it.path.lowercase().contains(q)) }.take(25)
        if (list.isEmpty()) side.add(note("No files changed in this period${if (q.isNotEmpty()) " match your filter" else ""}."))
        for (f in list) side.add(JBLabel("<html><b>${f.rank}.&nbsp;${esc(f.name)}</b><br><font color='#${hex(UIUtil.getContextHelpForeground().rgb)}'>${esc(f.path)}</font></html>", Swatch(Color(heatRgb(f.score))), JBLabel.LEFT).apply {
            alignmentX = 0f; border = JBUI.Borders.empty(4, 0); cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
            addMouseListener(object : java.awt.event.MouseAdapter() { override fun mouseClicked(e: MouseEvent) { if (e.clickCount >= 2) openFile(project, f.path) else reveal(f.path) } })
        })
    }

    private fun fileDetails(f: FileStat, a: Analysis) {
        val lv = level(f.score)
        side.add(JBLabel("← Top hotspots").apply {
            foreground = JBUI.CurrentTheme.Link.Foreground.ENABLED; alignmentX = 0f; cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
            addMouseListener(object : java.awt.event.MouseAdapter() { override fun mouseClicked(e: MouseEvent) = select(null) })
        })
        side.add(JBLabel(f.name).apply { font = JBUI.Fonts.label(15f).asBold(); border = JBUI.Borders.emptyTop(8); alignmentX = 0f })
        side.add(note(esc(f.path)))
        side.add(JBLabel("${if (f.rank > 0) "#${f.rank} · " else ""}${lv.label}").apply { foreground = levelColor(lv); font = JBUI.Fonts.label().asBold(); border = JBUI.Borders.emptyTop(6); alignmentX = 0f })
        side.add(heading("At a glance"))
        side.add(note("<b>${f.commits}</b> commits · <b>${"%,d".format(f.loc)}</b> lines · <b>${f.maxDepth}</b> levels deep<br>Last changed ${ago(f.lastChange, a.until)}"))
        if (f.knowledge != Knowledge.SHARED && f.commits > 0) side.add(JBLabel("<html>${if (f.knowledge == Knowledge.LOST) "⚠ Only ${esc(f.authors[0].name)} has worked on this, and they haven't committed in over 6 months." else "Only ${esc(f.authors[0].name)} has changed this file. Pair on the next change to share what they know."}</html>").apply {
            foreground = levelColor(Level.CRITICAL); border = JBUI.Borders.emptyTop(8); alignmentX = 0f
        })
        if (f.authors.isNotEmpty()) {
            side.add(heading("Who knows it"))
            val total = f.authors.sumOf { it.changes }.coerceAtLeast(1)
            for (au in f.authors.take(5)) side.add(note("${esc(au.name)} · ${(au.changes * 100.0 / total).roundToInt()}% · ${au.commits} commits"))
        }
        val together = a.coupledWith(f.path).sortedByDescending { it.degree }.take(6)
        if (together.isNotEmpty()) {
            side.add(heading("Changes together with"))
            for (c in together) side.add(JBLabel("<html><a href=''>${esc(c.b.substringAfterLast('/'))}</a> <font color='#${hex(UIUtil.getContextHelpForeground().rgb)}'>${(c.degree * 100).roundToInt()}% · ${c.shared} commits</font></html>").apply {
                alignmentX = 0f; border = JBUI.Borders.empty(2, 0); cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
                addMouseListener(object : java.awt.event.MouseAdapter() { override fun mouseClicked(e: MouseEvent) = reveal(c.b) })
            })
        }
        side.add(javax.swing.Box.createVerticalStrut(JBUI.scale(12)))
        side.add(JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            alignmentX = 0f; isOpaque = false
            add(JButton("Open File").apply { addActionListener { openFile(project, f.path) } })
            add(JButton("File History").apply { addActionListener { showHistory(project, f.path) } })
        })
    }

    override fun dispose() {}
}

fun hex(rgb: Int) = String.format("%06x", rgb and 0xffffff)

private val GENERIC = Regex("""^(page|index|route|layout|loading|error|mod|main|lib|__init__|\+page|\+layout|\+server|default|styles?|types?|utils?|constants?)\.[^.]+$""")

/** A file's name, with its folder when the name alone says little ("app/users/page.tsx" → "users/page.tsx"). */
fun shortName(path: String, other: String? = null): String {
    val name = path.substringAfterLast('/')
    val same = other != null && other.substringAfterLast('/') == name
    if (!same && !GENERIC.matches(name)) return name
    val dir = path.substringBeforeLast('/', "")
    return if (dir.isEmpty()) name else "${dir.substringAfterLast('/')}/$name"
}

// ------------------------------------------------------------------------------------------------ the lists

enum class ListKind { TOP, COUPLING, KNOWLEDGE }

class ListPanel(private val project: Project, private val kind: ListKind) : SimpleToolWindowPanel(true, true) {
    private sealed interface Row { data class File(val f: FileStat) : Row; data class Pair(val c: Coupling) : Row }
    private val model = DefaultListModel<Row>()
    private val list = JBList(model)
    private val service = HotspotsService.of(project)

    init {
        list.cellRenderer = object : ColoredListCellRenderer<Row>() {
            override fun customizeCellRenderer(l: JList<out Row>, row: Row, index: Int, selected: Boolean, focus: Boolean) {
                when (row) {
                    is Row.File -> {
                        val f = row.f; val lv = level(f.score)
                        icon = if (kind == ListKind.KNOWLEDGE) (if (f.knowledge == Knowledge.LOST) AllIcons.General.Warning else AllIcons.General.User) else Swatch(levelColor(lv))
                        if (kind == ListKind.TOP) append("${f.rank}.  ", SimpleTextAttributes.GRAYED_BOLD_ATTRIBUTES)
                        append(f.name, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                        append("   " + when (kind) {
                            ListKind.KNOWLEDGE -> if (f.knowledge == Knowledge.LOST) "${f.authors[0].name} has left · ${f.commits} commits" else "only ${f.authors[0].name} · ${f.commits} commits"
                            else -> "${f.commits} commits · ${"%,d".format(f.loc)} lines · ${lv.label}"
                        }, SimpleTextAttributes.GRAYED_ATTRIBUTES)
                        append("   " + f.path.substringBeforeLast('/', ""), SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
                    }
                    is Row.Pair -> {
                        val c = row.c
                        val apart = c.a.substringBeforeLast('/', "") != c.b.substringBeforeLast('/', "")
                        icon = Swatch(levelColor(if (c.degree >= 0.75) Level.CRITICAL else if (c.degree >= 0.55) Level.HIGH else Level.MEDIUM))
                        append(shortName(c.a, c.b), SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                        append("  ↔  ", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                        append(shortName(c.b, c.a), SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                        append("   ${(c.degree * 100).roundToInt()}% · ${c.shared} commits together${if (apart) " · across folders" else ""}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                        toolTipText = "<html>${esc(c.a)}<br>${esc(c.b)}</html>"
                    }
                }
            }
        }
        list.emptyText.text = when (kind) {
            ListKind.TOP -> "No commits in this period yet"
            ListKind.COUPLING -> "No files change together often enough yet"
            ListKind.KNOWLEDGE -> "Every file has been changed by several people. Nice."
        }
        object : DoubleClickListener() {
            override fun onDoubleClick(e: MouseEvent): Boolean {
                when (val row = list.selectedValue ?: return false) {
                    is Row.File -> openFile(project, row.f.path)
                    is Row.Pair -> { openFile(project, row.c.a); openFile(project, row.c.b) }
                }
                return true
            }
        }.installOn(list)
        setContent(JBScrollPane(list))
        val header = JBLabel(when (kind) {
            ListKind.TOP -> "Complex code that changes often: where bugs live. Double-click to open."
            ListKind.COUPLING -> "Files that keep changing in the same commits, often a hidden dependency. Double-click to open both."
            ListKind.KNOWLEDGE -> "Files whose only author has left, then files only one person has changed."
        }).apply { foreground = UIUtil.getContextHelpForeground(); border = JBUI.Borders.empty(6, 10) }
        toolbar = header
        project.messageBus.connect(service).subscribe(HotspotsListener.TOPIC, HotspotsListener { update() })
        update()
    }

    private fun update() {
        model.clear()
        val a = service.analysis ?: return
        when (kind) {
            ListKind.TOP -> a.ranked.take(100).forEach { model.addElement(Row.File(it)) }
            ListKind.COUPLING -> a.coupling.take(100).forEach { model.addElement(Row.Pair(it)) }
            ListKind.KNOWLEDGE -> a.files.filter { it.knowledge != Knowledge.SHARED && it.commits >= 3 }
                .sortedWith(compareBy<FileStat> { if (it.knowledge == Knowledge.LOST) 0 else 1 }.thenByDescending { it.score }).take(100)
                .forEach { model.addElement(Row.File(it)) }
        }
    }
}

