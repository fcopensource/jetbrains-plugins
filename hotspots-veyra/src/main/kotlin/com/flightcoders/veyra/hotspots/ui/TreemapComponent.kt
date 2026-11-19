// The heat map: every file a tile sized by its code and coloured by the chosen mode. Click to select, double-click to
// open, click a folder's name to zoom in.
package com.flightcoders.veyra.hotspots.ui

import com.flightcoders.veyra.hotspots.Analysis
import com.flightcoders.veyra.hotspots.ColorMode
import com.flightcoders.veyra.hotspots.FileStat
import com.flightcoders.veyra.hotspots.Rect
import com.flightcoders.veyra.hotspots.ago
import com.flightcoders.veyra.hotspots.buildTree
import com.flightcoders.veyra.hotspots.heatRgb
import com.flightcoders.veyra.hotspots.layoutTreemap
import com.flightcoders.veyra.hotspots.level
import com.flightcoders.veyra.hotspots.modeValue
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.intellij.openapi.util.text.StringUtil
import com.intellij.util.ui.UIUtil
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Cursor
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent
import kotlin.math.roundToInt

class TreemapComponent(
    private val onSelect: (String?) -> Unit,
    private val onOpen: (String) -> Unit,
    private val onFocus: (String) -> Unit,
) : JComponent() {
    var analysis: Analysis? = null; set(v) { field = v; relayout() }
    var mode = ColorMode.HOTSPOT; set(v) { field = v; repaint() }
    var focus = ""; set(v) { field = v; relayout() }
    var query = ""; set(v) { field = v; repaint() }
    var selected: String? = null; set(v) { field = v; repaint() }
    private var rects: List<Rect> = emptyList()
    private var hover: Rect? = null
    private var laidOutFor = 0 to 0
    private val header get() = JBUI.scale(17).toDouble()

    init {
        isOpaque = true
        toolTipText = ""
        val mouse = object : MouseAdapter() {
            override fun mouseMoved(e: MouseEvent) {
                val r = hit(e.x, e.y)
                if (r != hover) { hover = r; repaint() }
                cursor = if (r != null && (!r.dir || inHeader(r, e.y))) Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) else Cursor.getDefaultCursor()
            }
            override fun mouseExited(e: MouseEvent) { hover = null; repaint() }
            override fun mouseClicked(e: MouseEvent) {
                val r = hit(e.x, e.y) ?: return
                if (r.dir) { if (inHeader(r, e.y) || r.w < 60) onFocus(r.path); return }
                if (e.clickCount >= 2) onOpen(r.path) else onSelect(if (r.path == selected) null else r.path)
            }
        }
        addMouseListener(mouse); addMouseMotionListener(mouse)
    }

    private fun relayout() { laidOutFor = 0 to 0; repaint() }

    private fun ensureLayout() {
        val a = analysis ?: run { rects = emptyList(); return }
        if (laidOutFor == width to height) return
        laidOutFor = width to height
        val prefix = if (focus.isEmpty()) "" else "$focus/"
        val files = a.files.filter { it.path.startsWith(prefix) }.map { it.path.removePrefix(prefix) to it.loc }
        rects = layoutTreemap(buildTree(files), width.toDouble(), height.toDouble(), header, JBUI.scale(2).toDouble())
            .map { if (prefix.isNotEmpty() && it.path.isNotEmpty()) it.copy(path = prefix + it.path) else it }
    }

    private fun hit(x: Int, y: Int): Rect? { ensureLayout(); return rects.filter { it.contains(x.toDouble(), y.toDouble()) }.maxByOrNull { it.depth } }
    private fun inHeader(r: Rect, y: Int) = r.dir && y - r.y < header

    fun visibleRect(path: String): Rect? { ensureLayout(); return rects.firstOrNull { it.path == path && it.w > 6 && it.h > 6 } }

    override fun paintComponent(g0: Graphics) {
        val g = g0 as Graphics2D
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        val bg = UIUtil.getPanelBackground()
        g.color = bg; g.fillRect(0, 0, width, height)
        val a = analysis ?: return
        ensureLayout()
        val q = query.lowercase()
        val small = JBUI.Fonts.smallFont(); val bold = small.deriveFont(Font.BOLD)
        val dark = !JBColor.isBright()
        for (r in rects) {
            val x = r.x.roundToInt(); val y = r.y.roundToInt(); val w = r.w.roundToInt(); val h = r.h.roundToInt()
            if (r.dir) {
                g.color = shade(bg, 0.05f + r.depth * 0.025f, dark)
                g.fillRect(x, y, w, h)
                if (w > 44 && h > 34) { g.color = UIUtil.getContextHelpForeground(); g.font = bold; g.drawString(clip(g, r.name + "/", w - 8), x + 5, y + JBUI.scale(12)) }
                continue
            }
            val f = a.byPath[r.path] ?: continue
            val dim = q.isNotEmpty() && !f.path.lowercase().contains(q)
            val v = modeValue(f, mode, a)
            g.color = withAlpha(Color(heatRgb(v)), if (dim) 40 else 255)
            g.fillRect(x + 1, y + 1, maxOf(0, w - 1), maxOf(0, h - 1))
            if (w > 38 && h > 16 && !dim) {
                g.color = if (v > 0.45) Color(0x1a, 0x0a, 0x10) else Color(0xf2, 0xf4, 0xf8)
                g.font = if (w > 110 && h > 34) bold else small
                g.drawString(clip(g, r.name, w - 8), x + 4, y + JBUI.scale(13))
                if (h > 34 && w > 70 && f.rank in 1..30) { g.font = small; g.drawString(clip(g, "#${f.rank} · ${f.commits} commits", w - 8), x + 4, y + JBUI.scale(27)) }
            }
        }
        // The selected file, and dashed links to the files that change with it.
        val sel = selected?.let { s -> rects.firstOrNull { it.path == s } }
        if (sel != null) {
            val together = a.coupledWith(sel.path).map { it.b }.toSet()
            g.color = Color.WHITE
            for (r in rects) if (r.path in together) {
                g.stroke = BasicStroke(1.5f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, floatArrayOf(4f, 3f), 0f)
                g.drawRect((r.x + 1.5).roundToInt(), (r.y + 1.5).roundToInt(), (r.w - 3).roundToInt(), (r.h - 3).roundToInt())
                g.stroke = BasicStroke(1.2f)
                g.drawLine((sel.x + sel.w / 2).roundToInt(), (sel.y + sel.h / 2).roundToInt(), (r.x + r.w / 2).roundToInt(), (r.y + r.h / 2).roundToInt())
            }
            g.stroke = BasicStroke(3f)
            g.drawRect((sel.x + 1.5).roundToInt(), (sel.y + 1.5).roundToInt(), (sel.w - 3).roundToInt(), (sel.h - 3).roundToInt())
        }
        hover?.takeIf { !it.dir && it.path != selected }?.let { r ->
            g.color = JBUI.CurrentTheme.Focus.focusColor(); g.stroke = BasicStroke(2f)
            g.drawRect((r.x + 1).roundToInt(), (r.y + 1).roundToInt(), (r.w - 2).roundToInt(), (r.h - 2).roundToInt())
        }
    }

    override fun getToolTipText(e: MouseEvent): String? {
        val a = analysis ?: return null
        val r = hit(e.x, e.y) ?: return null
        if (r.dir) {
            val inside = a.files.filter { it.path.startsWith(r.path + "/") }
            val hot = inside.filter { it.rank > 0 }.minByOrNull { it.rank }
            return "<html><b>${esc(r.path)}/</b><br>${inside.size} files · ${"%,d".format(inside.sumOf { it.loc })} lines" +
                (hot?.let { "<br>Hottest: ${esc(it.name)} (#${it.rank})" } ?: "") + "<br><i>Click the name to zoom in</i></html>"
        }
        val f = a.byPath[r.path] ?: return null
        return "<html>${tooltipHtml(f, a)}</html>"
    }

    private fun clip(g: Graphics2D, text: String, max: Int): String {
        val fm = g.fontMetrics
        if (fm.stringWidth(text) <= max) return text
        var t = text
        while (t.length > 1 && fm.stringWidth("$t…") > max) t = t.dropLast(1)
        return if (t.length > 1) "$t…" else ""
    }
}

fun tooltipHtml(f: FileStat, a: Analysis): String {
    val lv = level(f.score)
    return "<b>${esc(f.path)}</b><br>${if (f.rank > 0) "#${f.rank} · " else ""}${lv.label}<br>" +
        "${f.commits} commit${if (f.commits == 1) "" else "s"} · ${"%,d".format(f.loc)} lines · depth ${f.maxDepth}<br>" +
        "Last changed ${ago(f.lastChange, a.until)}" + (f.authors.firstOrNull()?.let { "<br>Mostly ${esc(it.name)} (${(f.mainShare * 100).roundToInt()}%)" } ?: "")
}

fun esc(s: String): String = StringUtil.escapeXmlEntities(s)

private fun withAlpha(c: Color, alpha: Int) = Color(c.red, c.green, c.blue, alpha)
private fun shade(c: Color, amount: Float, dark: Boolean): Color {
    val k = if (dark) amount else -amount
    fun ch(v: Int) = (v + 255 * k).roundToInt().coerceIn(0, 255)
    return Color(ch(c.red), ch(c.green), ch(c.blue))
}
