// A squarified treemap (Bruls, Huizing, van Wijk) of the project's folders and files. Plain Kotlin, unit-tested.
package com.flightcoders.veyra.hotspots

import kotlin.math.max
import kotlin.math.min

class TreeNode(val name: String, val path: String, var size: Long, val children: MutableList<TreeNode>? = null) {
    val isDir get() = children != null
}

data class Rect(val path: String, val name: String, val x: Double, val y: Double, val w: Double, val h: Double, val dir: Boolean, val depth: Int) {
    fun contains(px: Double, py: Double) = px >= x && px < x + w && py >= y && py < y + h
}

/** Folder tree with sizes summed from the files (size = lines of code, at least 1). Single-child folders merge ("src/main"). */
fun buildTree(files: List<Pair<String, Int>>): TreeNode {
    val root = TreeNode("", "", 0, ArrayList())
    for ((path, loc) in files) {
        val parts = path.split('/')
        var node = root
        for (i in 0 until parts.size - 1) {
            val p = parts.subList(0, i + 1).joinToString("/")
            node = node.children!!.firstOrNull { it.isDir && it.name == parts[i] } ?: TreeNode(parts[i], p, 0, ArrayList()).also { node.children!!.add(it) }
        }
        node.children!!.add(TreeNode(parts.last(), path, max(1, loc).toLong()))
    }
    fun sum(n: TreeNode): Long { if (n.children != null) n.size = n.children.sumOf { sum(it) }; return n.size }
    sum(root)
    fun compact(n: TreeNode): TreeNode {
        val kids = n.children ?: return n
        val next = kids.map { compact(it) }
        kids.clear(); kids.addAll(next)
        if (n.path.isNotEmpty() && kids.size == 1 && kids[0].isDir) {
            val only = kids[0]
            return TreeNode("${n.name}/${only.name}", only.path, only.size, only.children)
        }
        return n
    }
    return compact(root)
}

/** Lays out the tree in a w×h box. Folders get a header strip for their name when there's room. */
fun layoutTreemap(root: TreeNode, width: Double, height: Double, header: Double = 16.0, pad: Double = 2.0): List<Rect> {
    val out = ArrayList<Rect>()
    fun place(node: TreeNode, x: Double, y: Double, w: Double, h: Double, depth: Int) {
        if (w < 1 || h < 1) return
        val kids = node.children
        if (kids == null) { out += Rect(node.path, node.name, x, y, w, h, false, depth); return }
        var ix = x; var iy = y; var iw = w; var ih = h
        if (node.path.isNotEmpty()) {
            out += Rect(node.path, node.name, x, y, w, h, true, depth)
            val showHeader = w > 44 && h > header * 2
            ix += pad; iw -= pad * 2
            val top = if (showHeader) header else pad
            iy += top; ih -= top + pad
            if (iw < 1 || ih < 1) return
        }
        val sorted = kids.filter { it.size > 0 }.sortedByDescending { it.size }
        val total = sorted.sumOf { it.size }.toDouble()
        if (total <= 0) return
        val scale = iw * ih / total
        squarify(sorted.map { it to it.size * scale }, ix, iy, iw, ih) { n, rx, ry, rw, rh -> place(n, rx, ry, rw, rh, depth + 1) }
    }
    place(root, 0.0, 0.0, width, height, 0)
    return out
}

private fun squarify(items: List<Pair<TreeNode, Double>>, x0: Double, y0: Double, w0: Double, h0: Double, emit: (TreeNode, Double, Double, Double, Double) -> Unit) {
    var x = x0; var y = y0; var w = w0; var h = h0
    var i = 0
    while (i < items.size) {
        val short = min(w, h)
        val row = arrayListOf(items[i])
        var worst = worst(row, short)
        var j = i + 1
        while (j < items.size) {
            row += items[j]
            val next = worst(row, short)
            if (next > worst) { row.removeAt(row.size - 1); break }
            worst = next; j++
        }
        val rowArea = row.sumOf { it.second }
        if (w >= h) {
            val rw = rowArea / h; var ry = y
            for ((n, area) in row) { val rh = area / rw; emit(n, x, ry, rw, rh); ry += rh }
            x += rw; w -= rw
        } else {
            val rh = rowArea / w; var rx = x
            for ((n, area) in row) { val rw = area / rh; emit(n, rx, y, rw, rh); rx += rw }
            y += rh; h -= rh
        }
        i = j
    }
}

private fun worst(row: List<Pair<TreeNode, Double>>, side: Double): Double {
    val s = row.sumOf { it.second }
    val maxA = row.maxOf { it.second }; val minA = row.minOf { it.second }
    val s2 = s * s; val side2 = side * side
    return max(side2 * maxA / s2, s2 / (side2 * minA))
}
