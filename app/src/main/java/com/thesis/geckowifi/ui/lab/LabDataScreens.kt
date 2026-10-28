package com.thesis.geckowifi.ui.lab

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.thesis.geckowifi.data.local.PortalCheckLog
import com.thesis.geckowifi.data.remote.MapServerExchange
import com.thesis.geckowifi.data.remote.MapServerTrace
import com.thesis.geckowifi.di.AppModule
import com.thesis.geckowifi.ui.theme.MonoFontFamily
import com.thesis.geckowifi.ui.theme.Space
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** One expandable item of a lab data screen; [json] is computed when first opened. */
private class LabRecord(
    val key: String,
    val title: String,
    val subtitle: String,
    val tone: RecordTone,
    val json: () -> JsonElement,
    /** Further views of this record, shown when it is open: label to action. */
    val links: List<Pair<String, () -> Unit>> = emptyList(),
)

private enum class RecordTone { OK, FAILED, NEUTRAL }

private val prettyJson = Json { prettyPrint = true }

/**
 * The portal check log (lab mode): every check written to `checks-<date>.jsonl`
 * (see [PortalCheckLog]), newest first, each line as a tree. The file is still
 * what experiments keep (adb pull, the dashboard's "pull checks"); this only reads it.
 */
@Composable
fun CheckLogScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var reload by remember { mutableIntStateOf(0) }
    val records = remember(reload) {
        AppModule.portalCheckLog.recentLines().mapIndexed { i, line ->
            val obj = runCatching { Json.parseToJsonElement(line).jsonObject }.getOrNull()
            fun field(name: String) = (obj?.get(name) as? JsonPrimitive)?.contentOrNull
            val state = field("state") ?: "?"
            LabRecord(
                key = "$i:${field("run_id")}",
                title = "$state · ${field("ssid") ?: "(no SSID)"}",
                subtitle = listOfNotNull(field("time")?.let(::localTime), field("mode"), field("outcome")).joinToString(" · "),
                tone = when (state) {
                    "REGISTERED" -> RecordTone.OK
                    "CONFLICT" -> RecordTone.FAILED
                    else -> RecordTone.NEUTRAL
                },
                json = { obj ?: JsonPrimitive(line) }
            )
        }
    }
    LabRecordsScreen(
        title = "Check log",
        intro = "Every check, as written to the check log on this device (newest first, at most 200).",
        empty = "No checks logged yet.",
        records = records,
        onBack = onBack,
        actions = {
            IconButton(onClick = { reload++ }) { Icon(Icons.Outlined.Refresh, contentDescription = "Reload") }
            IconButton(onClick = { shareLatestLog(context) }) { Icon(Icons.Outlined.Share, contentDescription = "Share latest log file") }
        }
    )
}

/**
 * The last requests to the map server and their answers, decoded for reading
 * (lab mode, kept in memory only; see [MapServerTrace]).
 */
@Composable
fun MapServerTrafficScreen(onBack: () -> Unit, onOpenMap: (Long) -> Unit = {}, onOpenProof: (Long) -> Unit = {}) {
    val exchanges by MapServerTrace.exchanges.collectAsState()
    val records = remember(exchanges) { exchanges.map { exchangeRecord(it, onOpenMap, onOpenProof) } }
    LabRecordsScreen(
        title = "Map server traffic",
        intro = "The last ${MapServerTrace.CAPACITY} requests to the map server and what came back, decoded. The outcome is the " +
            "app's verdict; this screen only displays. Kept in memory until the app stops.",
        empty = "Nothing yet. GECKO asks the map server when it looks up a place or checks whether the map changed.",
        records = records,
        onBack = onBack,
        actions = { IconButton(onClick = MapServerTrace::clear) { Icon(Icons.Outlined.DeleteSweep, contentDescription = "Clear") } }
    )
}

private fun exchangeRecord(e: MapServerExchange, onOpenMap: (Long) -> Unit, onOpenProof: (Long) -> Unit) = LabRecord(
    key = "${e.startedMillis}:${e.kind}:${System.identityHashCode(e)}",
    title = "${e.kind.replaceFirstChar { it.uppercase() }} · ${e.outcome}",
    subtitle = listOfNotNull(
        localTime(Instant.ofEpochMilli(e.startedMillis).toString()),
        e.responseSize?.let(::sizeText),
        "${e.durationMillis} ms"
    ).joinToString(" · "),
    tone = when {
        e.outcome.startsWith("OK") -> RecordTone.OK
        e.outcome.startsWith("Proof failure") -> RecordTone.FAILED
        else -> RecordTone.NEUTRAL
    },
    json = { MapServerDecoder.decode(e) },
    links = if (e.kind == "lookup" && e.response != null) {
        listOf("Map" to { onOpenMap(e.startedMillis) }, "Proof path" to { onOpenProof(e.startedMillis) })
    } else emptyList()
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LabRecordsScreen(
    title: String,
    intro: String,
    empty: String,
    records: List<LabRecord>,
    onBack: () -> Unit,
    actions: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val open = remember { mutableStateMapOf<String, Boolean>() }
    // per record: the paths of the opened containers ("" = the record's root)
    val expanded = remember { mutableStateMapOf<String, Boolean>() }
    val trees = remember { HashMap<String, JsonElement>() }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(title) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back") } },
            actions = { actions() }
        )
        LazyColumn(Modifier.fillMaxSize()) {
            item {
                Text(
                    intro,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = Space.lg, vertical = Space.sm)
                )
                if (records.isEmpty()) {
                    Text(empty, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(Space.lg))
                }
            }
            records.forEach { record ->
                val isOpen = open[record.key] == true
                item(key = record.key) {
                    RecordHeader(
                        record, isOpen,
                        onToggle = { open[record.key] = !isOpen },
                        onCopy = {
                            val tree = trees.getOrPut(record.key, record.json)
                            copy(context, record.title, prettyJson.encodeToString(JsonElement.serializer(), tree))
                        }
                    )
                }
                if (isOpen && record.links.isNotEmpty()) {
                    item(key = "${record.key}/links") {
                        Row(Modifier.padding(horizontal = Space.lg), horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                            record.links.forEach { (label, action) ->
                                OutlinedButton(onClick = action, modifier = Modifier.heightIn(min = 48.dp)) { Text(label) }
                            }
                        }
                    }
                }
                if (isOpen) {
                    val tree = trees.getOrPut(record.key, record.json)
                    val rows = flatten(tree) { path -> path.isEmpty() || expanded["${record.key}/$path"] == true }
                    items(rows, key = { "${record.key}/${it.path}" }) { row ->
                        TreeRow(row, expanded = row.path.isEmpty() || expanded["${record.key}/${row.path}"] == true,
                            onToggle = { expanded["${record.key}/${row.path}"] = expanded["${record.key}/${row.path}"] != true },
                            onCopyValue = { copy(context, row.key ?: "value", it) })
                    }
                }
                item(key = "${record.key}/divider") { HorizontalDivider() }
            }
        }
    }
}

@Composable
private fun RecordHeader(record: LabRecord, isOpen: Boolean, onToggle: () -> Unit, onCopy: () -> Unit) {
    val color = when (record.tone) {
        RecordTone.OK -> MaterialTheme.colorScheme.primary
        RecordTone.FAILED -> MaterialTheme.colorScheme.error
        RecordTone.NEUTRAL -> MaterialTheme.colorScheme.onSurface
    }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onToggle).heightIn(min = 56.dp).padding(start = Space.lg, end = Space.xs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f).padding(vertical = Space.sm)) {
            Text(record.title, style = MaterialTheme.typography.bodyLarge, color = color)
            Text(record.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (isOpen) IconButton(onClick = onCopy) { Icon(Icons.Outlined.ContentCopy, contentDescription = "Copy as JSON") }
        Icon(
            if (isOpen) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
            contentDescription = if (isOpen) "Collapse" else "Expand",
            modifier = Modifier.padding(Space.sm)
        )
    }
}

/** One visible line of a JSON tree: a container (opened or not) or a value. */
private class TreeLine(val path: String, val depth: Int, val key: String?, val element: JsonElement)

/** The lines of [root] to show, opening the containers whose path [isOpen] says. */
private fun flatten(root: JsonElement, isOpen: (String) -> Boolean): List<TreeLine> {
    val out = ArrayList<TreeLine>()
    fun walk(e: JsonElement, path: String, depth: Int, key: String?) {
        if (path.isNotEmpty()) out += TreeLine(path, depth, key, e)
        if (e is JsonPrimitive || !isOpen(path)) return
        val next = if (path.isEmpty()) 0 else depth + 1
        when (e) {
            is JsonObject -> e.forEach { (k, v) -> walk(v, "$path/$k", next, k) }
            is JsonArray -> e.forEachIndexed { i, v -> walk(v, "$path/$i", next, "$i") }
            else -> {}
        }
    }
    walk(root, "", 0, null)
    if (root is JsonPrimitive) out += TreeLine("/", 0, null, root)
    return out
}

@Composable
private fun TreeRow(line: TreeLine, expanded: Boolean, onToggle: () -> Unit, onCopyValue: (String) -> Unit) {
    val e = line.element
    val keyColor = MaterialTheme.colorScheme.onSurfaceVariant
    val text = buildAnnotatedString {
        if (e !is JsonPrimitive) append(if (expanded) "▾ " else "▸ ")
        line.key?.let { withStyle(SpanStyle(color = keyColor)) { append(it); append(if (e is JsonPrimitive) ": " else " ") } }
        when (e) {
            is JsonObject -> withStyle(SpanStyle(color = keyColor)) { append(if (expanded) "{" else "{ ${e.size} }") }
            is JsonArray -> withStyle(SpanStyle(color = keyColor)) { append(if (expanded) "[" else "[ ${e.size} ]") }
            is JsonPrimitive -> withStyle(SpanStyle(color = primitiveColor(e))) { append(if (e.isString) "\"${e.content}\"" else e.content) }
        }
    }
    Text(
        text,
        fontFamily = MonoFontFamily,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { if (e is JsonPrimitive) onCopyValue(e.content) else onToggle() }
            .heightIn(min = 32.dp)
            .padding(start = Space.lg + (line.depth * 14).dp, end = Space.lg, top = 6.dp, bottom = 6.dp)
    )
}

@Composable
private fun primitiveColor(p: JsonPrimitive): Color = when {
    p is JsonNull -> MaterialTheme.colorScheme.onSurfaceVariant
    p.isString -> MaterialTheme.colorScheme.primary
    else -> MaterialTheme.colorScheme.tertiary
}

private fun copy(context: Context, label: String, text: String) {
    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(label, text))
    Toast.makeText(context, "Copied $label", Toast.LENGTH_SHORT).show()
}

private fun shareLatestLog(context: Context) {
    val file = AppModule.portalCheckLog.latestFile()
    if (file == null) {
        Toast.makeText(context, "No checks logged yet", Toast.LENGTH_SHORT).show()
        return
    }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val share = Intent(Intent.ACTION_SEND).apply {
        type = "application/json"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(share, "Share check log"))
}

private fun localTime(iso: String): String =
    runCatching {
        DateTimeFormatter.ofPattern("d MMM, HH:mm:ss").withZone(ZoneId.systemDefault()).format(Instant.parse(iso))
    }.getOrDefault(iso)

private fun sizeText(bytes: Int): String = when {
    bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1e6)
    bytes >= 1_000 -> "%.1f KB".format(bytes / 1e3)
    else -> "$bytes B"
}
