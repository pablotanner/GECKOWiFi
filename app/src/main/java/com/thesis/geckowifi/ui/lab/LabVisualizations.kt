package com.thesis.geckowifi.ui.lab

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CenterFocusStrong
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.thesis.geckowifi.data.remote.MapServerExchange
import com.thesis.geckowifi.ui.components.SectionCard
import com.thesis.geckowifi.ui.theme.MonoFontFamily
import com.thesis.geckowifi.ui.theme.Space
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

private fun shortHash(h: String) = if (h.length > 12) h.take(12) + "…" else h

private fun metersText(m: Double): String = when {
    m >= 10_000 -> "%.0f km".format(m / 1000)
    m >= 1_000 -> "%.1f km".format(m / 1000)
    m >= 10 -> "%.0f m".format(m)
    else -> "%.1f m".format(m)
}

private fun typeText(type: String) = type.ifEmpty { "(no type)" }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LabTopBar(title: String, onBack: () -> Unit, actions: @Composable () -> Unit = {}) {
    TopAppBar(
        title = { Text(title) },
        navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back") } },
        actions = { actions() }
    )
}

@Composable
private fun NoAnswer(title: String, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        LabTopBar(title, onBack)
        Text(
            "This exchange has no answer to show: it isn't a lookup, it failed before an answer arrived, or the answer was too large to keep.",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(Space.lg)
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Map: where the answer's cells and certificates are
// ---------------------------------------------------------------------------------------------

/** Local metres around a reference point; y grows north. */
private class LocalProjection(private val lng0: Double, private val lat0: Double) {
    private val perLng = CellBounds.METERS_PER_DEGREE * cos(Math.toRadians(lat0))
    fun x(lng: Double) = (lng - lng0) * perLng
    fun y(lat: Double) = (lat - lat0) * CellBounds.METERS_PER_DEGREE
    fun lng(x: Double) = lng0 + x / perLng
    fun lat(y: Double) = lat0 + y / CellBounds.METERS_PER_DEGREE
}

/**
 * The answer on a plain map in metres (lab mode): the cells the request asked
 * for, the tree's cells that hold entries (by type), and the areas of the
 * delivered WiFi certificates. Pinch and drag to move, tap to see what is there.
 */
@Composable
fun AnswerMapScreen(exchange: MapServerExchange?, onBack: () -> Unit, onOpenProof: () -> Unit) {
    val model = remember(exchange) { exchange?.let(AnswerModel::of) }
    if (model == null) return NoAnswer("Answer on the map", onBack)

    val searchCells = remember(model) { model.cells.map { it to CellBounds.of(it) } }
    val entryCells = remember(model) {
        model.nodes.values.filter { it.entries.isNotEmpty() }.groupBy { it.xy }.map { (xy, nodes) -> CellBounds.of(xy) to nodes }
    }
    // centred on the search cells, else on whatever has a place
    val anchor = remember(model) {
        val all = searchCells.map { it.second }.ifEmpty { entryCells.map { it.first } }
        if (all.isEmpty()) null else CellBounds(all.minOf { it.lngMin }, all.minOf { it.latMin }, all.maxOf { it.lngMax }, all.maxOf { it.latMax })
    }
    if (anchor == null) return NoAnswer("Answer on the map", onBack)
    val proj = remember(anchor) { LocalProjection((anchor.lngMin + anchor.lngMax) / 2, (anchor.latMin + anchor.latMax) / 2) }

    var showSearch by remember { mutableStateOf(true) }
    var showEntries by remember { mutableStateOf(true) }
    var showAreas by remember { mutableStateOf(true) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var scale by remember { mutableFloatStateOf(0f) } // px per metre, 0 = not fitted yet
    var offset by remember { mutableStateOf(Offset.Zero) } // screen position of the reference point
    var tapped by remember { mutableStateOf<Pair<Double, Double>?>(null) }

    fun fit() {
        if (canvasSize == IntSize.Zero) return
        val (w, h) = anchor.sizeMeters()
        scale = (min(canvasSize.width / w, canvasSize.height / h) * 0.85).toFloat()
        offset = Offset(canvasSize.width / 2f, canvasSize.height / 2f)
    }

    val colors = MaterialTheme.colorScheme
    val wifiColor = colors.primary
    val otherColor = colors.tertiary
    val searchColor = colors.onSurfaceVariant
    val tapColor = colors.error

    Column(Modifier.fillMaxSize()) {
        LabTopBar("Answer on the map", onBack) {
            IconButton(onClick = ::fit) { Icon(Icons.Outlined.CenterFocusStrong, contentDescription = "Fit to the search area") }
        }
        Row(Modifier.padding(horizontal = Space.lg), horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
            FilterChip(showSearch, { showSearch = !showSearch }, label = { Text("Search cells") })
            FilterChip(showEntries, { showEntries = !showEntries }, label = { Text("Tree entries") })
            FilterChip(showAreas, { showAreas = !showAreas }, label = { Text("WiFi areas") })
        }
        Canvas(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .onSizeChanged { canvasSize = it; if (scale == 0f) fit() }
                .pointerInput(anchor) {
                    detectTransformGestures { centroid, pan, zoom, _ ->
                        offset = centroid - (centroid - offset) * zoom + pan
                        scale *= zoom
                    }
                }
                .pointerInput(anchor) {
                    detectTapGestures { p ->
                        if (scale > 0f) tapped = proj.lng(((p.x - offset.x) / scale).toDouble()) to proj.lat(((offset.y - p.y) / scale).toDouble())
                    }
                }
        ) {
            if (scale <= 0f) return@Canvas
            fun sx(lng: Double) = offset.x + (proj.x(lng) * scale).toFloat()
            fun sy(lat: Double) = offset.y - (proj.y(lat) * scale).toFloat()
            fun rect(b: CellBounds, color: Color, fill: Boolean, effect: PathEffect? = null) {
                val left = sx(b.lngMin); val right = sx(b.lngMax); val top = sy(b.latMax); val bottom = sy(b.latMin)
                // a cell of the size of a continent is still drawn, clamped to just beyond the screen
                val l = max(left, -10f); val r = min(right, size.width + 10f); val t = max(top, -10f); val bt = min(bottom, size.height + 10f)
                if (r <= l || bt <= t) return
                if (fill) drawRect(color.copy(alpha = 0.16f), Offset(l, t), Size(r - l, bt - t))
                drawRect(color, Offset(l, t), Size(r - l, bt - t), style = Stroke(width = 1.5.dp.toPx(), pathEffect = effect))
            }

            if (showEntries) entryCells.forEach { (b, nodes) ->
                rect(b, if (nodes.any { n -> n.entries.any { it.type == "wifi" } }) wifiColor else otherColor, fill = true)
            }
            if (showSearch) searchCells.forEach { (_, b) ->
                rect(b, searchColor, fill = false, effect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)))
            }
            if (showAreas) model.certificates.forEach { c ->
                c.rings.forEach { ring ->
                    if (ring.size < 3) return@forEach
                    val path = Path().apply {
                        moveTo(sx(ring[0].first), sy(ring[0].second))
                        ring.drop(1).forEach { lineTo(sx(it.first), sy(it.second)) }
                        close()
                    }
                    drawPath(path, wifiColor, style = Stroke(width = 2.5.dp.toPx()))
                }
            }
            tapped?.let { (lng, lat) ->
                val c = Offset(sx(lng), sy(lat))
                drawCircle(tapColor, radius = 6.dp.toPx(), center = c, style = Stroke(width = 2.dp.toPx()))
            }
        }
        Column(
            Modifier.fillMaxWidth().heightIn(max = 260.dp).verticalScroll(rememberScrollState()).padding(Space.lg),
            verticalArrangement = Arrangement.spacedBy(Space.xs)
        ) {
            val shownWidth = if (scale > 0f) canvasSize.width / scale.toDouble() else 0.0
            Text(
                "Shown: ${metersText(shownWidth)} wide · ${searchCells.size} search cells · ${entryCells.size} cells with entries · " +
                    "${model.certificates.size} WiFi certificates. Filled: where the tree lists certificates (blue: wifi, other " +
                    "colour: other types); dashed: the cells asked for; thick outline: a WiFi certificate's area.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            val here = tapped
            if (here == null) {
                Text("Tap the map to see what is listed there.", style = MaterialTheme.typography.bodyMedium)
            } else {
                TappedInfo(model, here.first, here.second, searchCells, entryCells)
            }
            Text(
                "Open the proof path",
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.clickable(onClick = onOpenProof).heightIn(min = 48.dp).padding(top = Space.md)
            )
        }
    }
}

@Composable
private fun TappedInfo(
    model: AnswerModel,
    lng: Double,
    lat: Double,
    searchCells: List<Pair<String, CellBounds>>,
    entryCells: List<Pair<CellBounds, List<AnswerNode>>>,
) {
    Text("%.6f, %.6f".format(lat, lng), fontFamily = MonoFontFamily, style = MaterialTheme.typography.bodyMedium)
    searchCells.filter { it.second.contains(lng, lat) }.forEach { (bits, b) ->
        val (w, h) = b.sizeMeters()
        Text("Search cell, ${bits.length} bits: ${metersText(w)} × ${metersText(h)}", style = MaterialTheme.typography.bodyMedium)
    }
    entryCells.filter { it.first.contains(lng, lat) }.forEach { (b, nodes) ->
        val (w, h) = b.sizeMeters()
        nodes.forEach { n ->
            val (low, high) = CellBounds.altitudeOf(n.z)
            Text(
                "Tree node, cell ${metersText(w)} × ${metersText(h)}, altitude $low to $high m:",
                style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium
            )
            n.entries.forEach { e ->
                val id = model.certificate(e.hash)?.id
                Text("  ${typeText(e.type)} · ${id ?: "body not sent"} · ${shortHash(e.hash)}",
                    fontFamily = MonoFontFamily, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    model.certificates.filter { c -> c.rings.any { pointInRing(lng, lat, it) } }.forEach { c ->
        Text(
            "Inside the area of ${c.id ?: shortHash(c.hash)}" + (c.altitude?.let { " (altitude ${it.first.toInt()} to ${it.second.toInt()} m)" } ?: ""),
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

private fun pointInRing(lng: Double, lat: Double, ring: List<Pair<Double, Double>>): Boolean {
    var inside = false
    var j = ring.lastIndex
    for (i in ring.indices) {
        val (xi, yi) = ring[i]
        val (xj, yj) = ring[j]
        if ((yi > lat) != (yj > lat) && lng < (xj - xi) * (lat - yi) / (yj - yi) + xi) inside = !inside
        j = i
    }
    return inside
}

// ---------------------------------------------------------------------------------------------
// Proof path: from the signed root down to one certificate's entry
// ---------------------------------------------------------------------------------------------

/** What the answer says about a child that the path doesn't take. */
internal sealed class ChildState {
    data class Pruned(val hash: String) : ChildState()
    data class Sent(val entries: Int) : ChildState()
    data object Empty : ChildState()
}

private fun ChildState.text() = when (this) {
    is ChildState.Pruned -> "only its hash was sent (${shortHash(hash)})"
    is ChildState.Sent -> "sent in full" + if (entries > 0) ", lists $entries certificate(s)" else ""
    ChildState.Empty -> "empty (the default hash)"
}

/** One level of the path: the node there, the step taken and the other children hashed in. */
internal class PathLevel(
    val xy: String,
    val z: String,
    val node: AnswerNode?,
    val step: String?,
    val others: List<Pair<String, ChildState>>,
)

private fun childState(model: AnswerModel, parent: AnswerNode?, pruned: String?, key: Pair<String, String>): ChildState =
    when {
        pruned != null -> ChildState.Pruned(pruned)
        parent != null && model.nodes[key] != null -> ChildState.Sent(subtreeEntries(model, key))
        else -> ChildState.Empty
    }

/** Entries in the subtree at [key]: a surface node's covers its cell at all altitudes, an altitude node's only its range. */
private fun subtreeEntries(model: AnswerModel, key: Pair<String, String>): Int =
    model.nodes.values.filter {
        if (key.second.isEmpty()) it.xy.startsWith(key.first) else it.xy == key.first && it.z.startsWith(key.second)
    }.sumOf { it.entries.size }

private fun xyHalf(depth: Int, bit: Char) =
    if (depth % 2 == 0) (if (bit == '0') "west half" else "east half") else (if (bit == '0') "south half" else "north half")

private fun zHalf(bit: Char) = if (bit == '0') "lower half" else "upper half"

/** The levels from the root to the node (xy, z): first down the surface bits, then down the altitude bits. */
internal fun pathTo(model: AnswerModel, xy: String, z: String): List<PathLevel> {
    val levels = ArrayList<PathLevel>()
    for (i in 0..xy.length) {
        val here = xy.take(i)
        val node = model.nodes[here to ""]
        val zLeft = "zLeft" to childState(model, node, node?.prunedZLeft, here to "0")
        val zRight = "zRight" to childState(model, node, node?.prunedZRight, here to "1")
        val xyChildren = if (i < 51) listOf(
            '0' to childState(model, node, node?.prunedXyLeft, here + "0" to ""),
            '1' to childState(model, node, node?.prunedXyRight, here + "1" to "")
        ) else emptyList()
        if (i < xy.length) {
            val bit = xy[i]
            val other = xyChildren.first { it.first != bit }
            levels += PathLevel(here, "", node, "into the ${xyHalf(i, bit)}",
                listOf(xyHalf(i, other.first) to other.second,
                    "altitude tree of this cell, lower half" to zLeft.second,
                    "altitude tree of this cell, upper half" to zRight.second))
        } else if (z.isNotEmpty()) {
            val bit = z[0]
            levels += PathLevel(here, "", node, "into this cell's altitude tree, ${zHalf(bit)}",
                listOfNotNull(
                    ("altitude tree, ${zHalf(if (bit == '0') '1' else '0')}" to (if (bit == '0') zRight.second else zLeft.second)),
                    xyChildren.getOrNull(0)?.let { xyHalf(i, '0') to it.second },
                    xyChildren.getOrNull(1)?.let { xyHalf(i, '1') to it.second }))
        } else {
            levels += PathLevel(here, "", node, null,
                listOfNotNull(xyChildren.getOrNull(0)?.let { xyHalf(i, '0') to it.second },
                    xyChildren.getOrNull(1)?.let { xyHalf(i, '1') to it.second },
                    "altitude tree, lower half" to zLeft.second, "altitude tree, upper half" to zRight.second))
        }
    }
    for (j in 1..z.length) {
        val here = z.take(j)
        val node = model.nodes[xy to here]
        val left = childState(model, node, node?.prunedZLeft, xy to here + "0")
        val right = childState(model, node, node?.prunedZRight, xy to here + "1")
        if (j < z.length) {
            val bit = z[j]
            levels += PathLevel(xy, here, node, "into the ${zHalf(bit)}", listOf(zHalf(if (bit == '0') '1' else '0') to (if (bit == '0') right else left)))
        } else {
            levels += PathLevel(xy, here, node, null, listOf(zHalf('0') to left, zHalf('1') to right))
        }
    }
    return levels
}

/** Consecutive levels whose other children are all empty and that list nothing: shown as one row. */
private sealed class PathRow {
    class Level(val index: Int, val level: PathLevel) : PathRow()
    class Quiet(val from: Int, val to: Int, val first: PathLevel, val last: PathLevel) : PathRow()
}

private fun rows(levels: List<PathLevel>): List<PathRow> {
    val out = ArrayList<PathRow>()
    var i = 0
    while (i < levels.size) {
        val quiet = { l: PathLevel -> l.step != null && l.others.all { it.second == ChildState.Empty } && l.node?.entries.isNullOrEmpty() }
        if (quiet(levels[i])) {
            var j = i
            while (j + 1 < levels.size && quiet(levels[j + 1])) j++
            out += if (j > i) PathRow.Quiet(i, j, levels[i], levels[j]) else PathRow.Level(i, levels[i])
            i = j + 1
        } else {
            out += PathRow.Level(i, levels[i])
            i++
        }
    }
    return out
}

private fun areaText(level: PathLevel): String =
    if (level.z.isEmpty()) {
        if (level.xy.isEmpty()) "the whole world" else CellBounds.of(level.xy).sizeMeters().let { (w, h) -> "${metersText(w)} × ${metersText(h)}" }
    } else {
        CellBounds.altitudeOf(level.z).let { (low, high) -> "altitude $low to $high m" }
    }

/**
 * The Merkle proof for one listed certificate (lab mode): every level from the
 * signed root down to the node listing it, with the other children whose hashes
 * go into each level. Display only; the client checked the whole tree.
 */
@Composable
fun ProofPathScreen(exchange: MapServerExchange?, onBack: () -> Unit) {
    val model = remember(exchange) { exchange?.let(AnswerModel::of) }
    if (model == null) return NoAnswer("Proof path", onBack)
    var selected by remember { mutableStateOf<Pair<String, AnswerNode>?>(null) }

    val pick = selected
    if (pick == null) {
        Column(Modifier.fillMaxSize()) {
            LabTopBar("Proof path", onBack)
            Text(
                "Pick a certificate to follow from the signed root hash down to where the tree lists it. Every certificate " +
                    "in the area is listed, also those whose body wasn't sent.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Space.lg, vertical = Space.sm)
            )
            val listings = model.listings.entries.sortedByDescending { (hash, _) -> model.certificate(hash) != null }
            LazyColumn {
                listings.forEach { (hash, places) ->
                    val id = model.certificate(hash)?.id
                    val types = places.map { typeText(it.second) }.distinct().joinToString(", ")
                    items(places.distinctBy { it.first }, key = { "$hash/${it.first.xy}/${it.first.z}" }) { (node, _) ->
                        val (low, high) = CellBounds.altitudeOf(node.z)
                        Column(
                            Modifier.fillMaxWidth().clickable { selected = hash to node }.heightIn(min = 56.dp)
                                .padding(horizontal = Space.lg, vertical = Space.sm)
                        ) {
                            Text(id ?: shortHash(hash), style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "$types · ${if (id == null) "body not sent" else "body sent"} · node at ${node.xy.length} + ${node.z.length} bits, altitude $low to $high m",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
        return
    }

    val (hash, target) = pick
    val levels = remember(pick) { pathTo(model, target.xy, target.z) }
    val certificate = model.certificate(hash)
    Column(Modifier.fillMaxSize()) {
        LabTopBar(certificate?.id ?: "Proof path", onBack = { selected = null })
        LazyColumn(
            Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(Space.sm),
            contentPadding = PaddingValues(Space.lg)
        ) {
            item {
                SectionCard("Signed root (map version ${model.version})") {
                    Text(model.rootHash, fontFamily = MonoFontFamily, style = MaterialTheme.typography.bodySmall)
                    Text(
                        "Signed by the map server at ${model.mapTimestamp}. Each level below is hashed together with the other " +
                            "children listed at it, level by level up to this hash. The app rebuilt it this way when it accepted the answer.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = Space.sm)
                    )
                }
            }
            items(rows(levels)) { row ->
                when (row) {
                    is PathRow.Quiet -> Text(
                        "Levels ${row.from}–${row.to} (${areaText(row.first)} down to ${areaText(row.last)}): " +
                            "nothing else is registered in the other halves; each is empty (the default hash).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = Space.sm)
                    )
                    is PathRow.Level -> LevelCard(row.index, row.level, hash, model)
                }
            }
            item {
                SectionCard("The certificate") {
                    Text("Entry: ${typeText(target.entries.firstOrNull { it.hash == hash }?.type ?: "")} | ${shortHash(hash)}",
                        fontFamily = MonoFontFamily, style = MaterialTheme.typography.bodySmall)
                    Text(
                        if (certificate != null) {
                            "The body was sent; its SHA-256 is ${shortHash(certificate.hash)}, the hash in the entry."
                        } else {
                            "The body wasn't sent (its type wasn't asked for), but the entry proves it is registered here."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = Space.sm)
                    )
                }
            }
        }
    }
}

@Composable
private fun LevelCard(index: Int, level: PathLevel, hash: String, model: AnswerModel) {
    val kind = if (level.z.isEmpty()) "surface" else "altitude"
    SectionCard("Level $index · $kind · ${areaText(level)}") {
        if (level.node == null) {
            Text("Not in the answer.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        }
        level.step?.let { Text("Path goes $it.", style = MaterialTheme.typography.bodyMedium) }
        level.others.forEach { (name, state) ->
            Text("$name: ${state.text()}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        level.node?.entries?.takeIf { it.isNotEmpty() }?.let { entries ->
            Text("Listed at this node:", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = Space.xs))
            entries.forEach { e ->
                Text(
                    "${if (e.hash == hash) "▶ " else "  "}${typeText(e.type)} | ${shortHash(e.hash)}" +
                        (model.certificate(e.hash)?.id?.let { " ($it)" } ?: ""),
                    fontFamily = MonoFontFamily,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = if (e.hash == hash) FontWeight.Bold else FontWeight.Normal
                )
            }
        }
    }
}
