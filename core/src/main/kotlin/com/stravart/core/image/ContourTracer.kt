package com.stravart.core.image

import com.stravart.core.shape.Pt

/**
 * Suit le bord de la plus grande tache d'une image binaire.
 *
 * On ne garde que le **contour extérieur** d'une seule tache : un parcours est un
 * trait continu, il ne peut ni se dédoubler ni sauter d'un morceau à l'autre. Les
 * trous intérieurs — l'intérieur d'un anneau, l'œil d'un visage — sont ignorés pour
 * la même raison.
 *
 * L'algorithme est celui de Moore : on longe la tache en tournant toujours dans le
 * même sens, main sur le mur, jusqu'à repasser au point de départ dans les mêmes
 * conditions.
 */
object ContourTracer {

    /** Voisinage de Moore, dans le sens horaire à l'écran (l'axe y descend). */
    private val OFFSETS = arrayOf(
        0 to -1, 1 to -1, 1 to 0, 1 to 1,
        0 to 1, -1 to 1, -1 to 0, -1 to -1,
    )

    /** Une tache plus petite que cela relève du bruit, pas du sujet. */
    private const val MIN_BLOB_PIXELS = 24

    /** @return le contour en pixels, ou `null` si l'image ne contient rien d'exploitable. */
    fun outerContour(mask: Mask): List<Pt>? {
        val labels = label(mask) ?: return null
        return trace(mask.width, mask.height, labels.map, labels.largest)
    }

    private class Labels(val map: IntArray, val largest: Int)

    /** Étiquette les taches en 4-connexité et retient la plus grande. */
    private fun label(mask: Mask): Labels? {
        val map = IntArray(mask.width * mask.height) { -1 }
        val stack = IntArray(mask.width * mask.height)
        var current = 0
        var largest = -1
        var largestSize = 0

        for (start in map.indices) {
            if (!mask.inside[start] || map[start] != -1) continue

            var top = 0
            stack[top++] = start
            map[start] = current
            var size = 0

            while (top > 0) {
                val node = stack[--top]
                size++
                val x = node % mask.width
                val y = node / mask.width
                for ((dx, dy) in FOUR_NEIGHBOURS) {
                    val nx = x + dx
                    val ny = y + dy
                    if (nx !in 0 until mask.width || ny !in 0 until mask.height) continue
                    val next = ny * mask.width + nx
                    if (!mask.inside[next] || map[next] != -1) continue
                    map[next] = current
                    stack[top++] = next
                }
            }

            if (size > largestSize) {
                largestSize = size
                largest = current
            }
            current++
        }

        return if (largestSize < MIN_BLOB_PIXELS) null else Labels(map, largest)
    }

    private fun trace(width: Int, height: Int, labels: IntArray, target: Int): List<Pt>? {
        fun belongs(x: Int, y: Int): Boolean =
            x in 0 until width && y in 0 until height && labels[y * width + x] == target

        var startX = -1
        var startY = -1
        outer@ for (y in 0 until height) {
            for (x in 0 until width) {
                if (belongs(x, y)) { startX = x; startY = y; break@outer }
            }
        }
        if (startX < 0) return null

        val contour = ArrayList<Pt>()
        contour += Pt(startX.toDouble(), startY.toDouble())

        var bx = startX
        var by = startY
        // Le pixel de gauche est forcément hors de la tache : le balayage l'aurait
        // rencontré avant. C'est notre point de départ pour tourner autour.
        var px = startX - 1
        var py = startY
        val initialPx = px
        val initialPy = py

        val limit = 8 * width * height
        var steps = 0

        while (steps++ < limit) {
            val from = directionOf(bx, by, px, py)
            var moved = false
            for (k in 1..8) {
                val i = (from + k) % 8
                val nx = bx + OFFSETS[i].first
                val ny = by + OFFSETS[i].second
                if (!belongs(nx, ny)) continue

                // Le voisin précédent, hors de la tache, devient le nouveau dos au mur.
                val back = (i + 7) % 8
                px = bx + OFFSETS[back].first
                py = by + OFFSETS[back].second
                bx = nx
                by = ny
                contour += Pt(bx.toDouble(), by.toDouble())
                moved = true
                break
            }
            if (!moved) break // pixel isolé

            // Critère de Jacob : revenir au départ dans la même posture, et non
            // simplement y repasser — un contour étroit traverse deux fois le même point.
            if (bx == startX && by == startY && px == initialPx && py == initialPy) {
                contour.removeAt(contour.lastIndex)
                break
            }
        }

        return if (contour.size >= 4) contour else null
    }

    private fun directionOf(bx: Int, by: Int, px: Int, py: Int): Int {
        val dx = px - bx
        val dy = py - by
        val index = OFFSETS.indexOfFirst { it.first == dx && it.second == dy }
        return if (index >= 0) index else 6 // par défaut : l'ouest
    }

    private val FOUR_NEIGHBOURS = arrayOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)
}
