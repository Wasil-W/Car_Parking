package dev.wasil.permit.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.wasil.permit.parking.ParkRecord
import dev.wasil.permit.ui.theme.HandoffShapes
import dev.wasil.permit.ui.theme.LocalHandoffColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Every park, and what it cost.
 *
 * The record is the reference apps' record — a badge, a place, a time range, a
 * cost — with one deliberate difference that is the whole point of the screen:
 * **the badge carries settlement, not zone.** Q-Park's badge is a zone code
 * because a zone code is what they sell. Ours says how the obligation was met,
 * which is the split the app is built around and which has, until now, been
 * visible nowhere.
 *
 * Newest first, grouped by month. No monthly total: it is genuinely unsettled
 * whether such a number should count what you paid or what the permit saved
 * you, and the second is flattering enough to be a bad default.
 */
@Composable
fun HistoryScreen(records: List<ParkRecord>) {
    Column(Modifier.fillMaxSize()) {
        // Outside the list on purpose. It used to live inside it, and an empty
        // log therefore rendered a tab with no name on it — seen on screen. A
        // screen must say what it is whether or not it has anything in it.
        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 4.dp)) {
            Text("History", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Every park, and what it cost.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (records.isEmpty()) {
            EmptyHistory()
            return@Column
        }

        // Stored oldest-first (it is an append-only log); read newest-first,
        // because the park you are asking about is almost always the last one.
        val newestFirst = records.asReversed()

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 16.dp),
        ) {
            // A month header whenever the month changes going down the list. The
            // mockup put a total beside it; that number is left out on purpose.
            var lastMonth: String? = null
            newestFirst.forEach { record ->
                val month = monthLabel(record.startedAtMs)
                if (month != lastMonth) {
                    lastMonth = month
                    item(key = "month-$month") { MonthHeader(month) }
                }
                item(key = "park-${record.startedAtMs}") { RecordCard(record) }
            }
        }
    }
}

@Composable
private fun MonthHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 20.dp, top = 20.dp, bottom = 6.dp),
    )
}

@Composable
private fun RecordCard(record: ParkRecord) {
    val row = historyRowFor(
        record = record,
        dayDate = dayDate(record.startedAtMs),
        startClock = clock(record.startedAtMs),
        endClock = record.endedAtMs?.let(::clock),
    )
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        shape = HandoffShapes.Control,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Badge(row.badge, row.kind)
                Text(
                    row.cost,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (row.costIsQuiet) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
            }
            Text(
                row.place,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp),
            )
            Text(
                row.whenText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 1.dp),
            )
        }
    }
}

/**
 * The badge, and the one place in the app outside the permit card where
 * identity colour is legitimate: a permit record belongs to a specific car.
 *
 * It stays out of the other three kinds deliberately. "Free" and "unsettled"
 * are states of a place, not of a person, and colouring them with a brother's
 * hue is precisely how blue stops meaning "Wasil". Those use the state colours
 * the app already has — `fine` for nothing owed, `alert` for a spot that was
 * charging and went uncovered — at container strength so a list of them is
 * readable rather than a row of traffic lights.
 */
@Composable
private fun Badge(text: String, kind: BadgeKind) {
    val colors = LocalHandoffColors.current
    val container: Color
    val content: Color
    when (kind) {
        BadgeKind.PERMIT_WASIL -> {
            container = colors.wasilContainer
            content = colors.wasilOnContainer
        }
        BadgeKind.PERMIT_WALID -> {
            container = colors.walidContainer
            content = colors.walidOnContainer
        }
        BadgeKind.FREE -> {
            container = MaterialTheme.colorScheme.surfaceContainerHighest
            content = colors.fine
        }
        BadgeKind.OWED -> {
            container = MaterialTheme.colorScheme.errorContainer
            content = MaterialTheme.colorScheme.onErrorContainer
        }
        BadgeKind.UNKNOWN -> {
            container = MaterialTheme.colorScheme.surfaceContainerHighest
            content = MaterialTheme.colorScheme.onSurfaceVariant
        }
    }
    Surface(shape = RoundedCornerShape(6.dp), color = container, contentColor = content) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
        )
    }
}

/**
 * The empty state earns its space here, unlike the one-line empties elsewhere
 * in the app: this tab's only job is history, so an empty one has to say when
 * something would appear in it or it reads as a bug.
 */
@Composable
private fun EmptyHistory() {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            HISTORY_EMPTY_TITLE,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Text(
            HISTORY_EMPTY_BODY,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

// Formatting only. Every wording decision lives in HistoryPresentation.kt so a
// JVM test can hold it still; locale and timezone are the platform's job.
private fun clock(ms: Long): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms))

private fun dayDate(ms: Long): String =
    SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(Date(ms))

private fun monthLabel(ms: Long): String =
    SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(Date(ms))
