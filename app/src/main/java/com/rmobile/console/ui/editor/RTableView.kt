package com.rmobile.console.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.rmobile.console.data.model.RTable

@Composable
fun RTableView(table: RTable, modifier: Modifier = Modifier) {
    var filter by remember { mutableStateOf("") }
    var sortColumn by remember { mutableStateOf<Int?>(null) }
    var ascending by remember { mutableStateOf(true) }
    var expandedCell by remember { mutableStateOf<String?>(null) }

    val widths = remember(table) {
        mutableStateListOf<Dp>().apply {
            for (c in table.columns.indices) {
                val maxLen = (listOf(table.columns.getOrElse(c) { "" }) +
                    table.rows.map { it.getOrElse(c) { "" } }).maxOf { it.length }
                add((maxLen * 8).coerceIn(56, 240).dp)
            }
        }
    }

    val displayed = remember(table, filter, sortColumn, ascending) {
        RTableViewOps.display(table.rows, table.columnTypes, filter, sortColumn, ascending)
    }

    // Total width of one row, used for the row-separator lines. We can't use
    // HorizontalDivider() inside the horizontalScroll container below: it applies
    // fillMaxWidth() internally, which resolves to an *infinite* width in the
    // unbounded scroll constraints and breaks the column's layout so no rows after
    // the first separator get placed (invisible until this fix). A width-bounded Box
    // avoids that. Each cell is width(w).padding(6.dp), i.e. w + 12.dp wide.
    val rowWidth = widths.fold(0.dp) { acc, w -> acc + w + 12.dp }
    val dividerColor = MaterialTheme.colorScheme.outlineVariant

    Column(modifier = modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = filter,
            onValueChange = { filter = it },
            label = { Text("Filter") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        )
        Column(modifier = Modifier.horizontalScroll(rememberScrollState())) {
            Row {
                table.columns.forEachIndexed { c, name ->
                    val numeric = table.columnTypes.getOrElse(c) { "" } in RTableViewOps.NUMERIC_TYPES
                    val indicator = if (sortColumn == c) (if (ascending) " ▲" else " ▼") else ""
                    Box(
                        modifier = Modifier
                            .width(widths.getOrElse(c) { 120.dp })
                            .clickable {
                                if (sortColumn == c) ascending = !ascending
                                else { sortColumn = c; ascending = true }
                            }
                            .padding(6.dp),
                    ) {
                        Text(
                            text = name + indicator,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = if (numeric) TextAlign.End else TextAlign.Start,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Box(
                            modifier = Modifier
                                .align(Alignment.CenterEnd)
                                .width(10.dp)
                                .fillMaxHeight()
                                .pointerInput(c) {
                                    detectDragGestures { _, drag ->
                                        widths[c] = (widths[c] + drag.x.toDp()).coerceAtLeast(40.dp)
                                    }
                                },
                        )
                    }
                }
            }
            Box(Modifier.width(rowWidth).height(1.dp).background(dividerColor))
            displayed.forEach { row ->
                Row {
                    row.forEachIndexed { c, cell ->
                        val numeric = table.columnTypes.getOrElse(c) { "" } in RTableViewOps.NUMERIC_TYPES
                        Text(
                            text = cell,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = if (numeric) TextAlign.End else TextAlign.Start,
                            modifier = Modifier
                                .width(widths.getOrElse(c) { 120.dp })
                                .clickable { expandedCell = cell }
                                .padding(6.dp),
                        )
                    }
                }
                Box(Modifier.width(rowWidth).height(1.dp).background(dividerColor))
            }
        }
        if (table.totalRows > table.rows.size) {
            Text(
                "Showing ${table.rows.size} of ${table.totalRows} rows",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 4.dp),
            )
        }
    }

    expandedCell?.let { value ->
        AlertDialog(
            onDismissRequest = { expandedCell = null },
            confirmButton = { TextButton(onClick = { expandedCell = null }) { Text("Close") } },
            title = { Text("Cell value") },
            text = { SelectionContainer { Text(value, fontFamily = FontFamily.Monospace) } },
        )
    }
}
