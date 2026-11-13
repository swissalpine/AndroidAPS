package app.aaps.plugins.aps.advisor.compose

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.Colorize
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.aaps.core.ui.compose.AapsCard
import app.aaps.core.ui.compose.AapsSpacing
import app.aaps.core.ui.compose.AapsTheme
import app.aaps.plugins.aps.R
import app.aaps.plugins.aps.advisor.AdvisorArea
import app.aaps.plugins.aps.advisor.AdvisorConfidence
import app.aaps.plugins.aps.advisor.AdvisorFinding
import app.aaps.plugins.aps.advisor.AdvisorOutcome
import app.aaps.plugins.aps.advisor.AdvisorPrompt
import app.aaps.plugins.aps.advisor.AdvisorReport
import app.aaps.plugins.aps.advisor.AdvisorSetting
import app.aaps.plugins.aps.advisor.ChangedSetting
import app.aaps.plugins.aps.advisor.GlucoseStats
import app.aaps.plugins.aps.advisor.ProfileWarning
import app.aaps.plugins.aps.advisor.ProposedChange

// Sizes of the pictures. The spacing and the colors come from the theme.
private val STRIPE_WIDTH = 6.dp
private val BAR_HEIGHT = 20.dp
private val LANE_HEIGHT = 18.dp
private val LANE_LABEL_WIDTH = 96.dp
private val LEGEND_DOT = 12.dp
private val POINT_MARK_WIDTH = 16.dp
private val TITLE_ICON = 24.dp
private val CHIP_ICON = 16.dp
private const val MINUTES_PER_DAY = 1_440f
private const val HOURS_PER_DAY = 24f
private val AXIS_HOURS = listOf(0, 6, 12, 18, 24)
private val GRID_HOURS = listOf(6, 12, 18)
private const val TRACK_ALPHA = 0.6f
private const val CHIP_ALPHA = 0.16f

/**
 * The report as a few pictures and cards: a summary, the glucose ranges, where the proposed changes are in the
 * day, and one card for each finding with the most important first.
 *
 * @param glucose null when the ranges are not known (a report from before they were kept)
 * @param profileWarnings large profile changes of the last days. They are shown first, whatever the AI said
 * @param confirmedWarnings keys ([ProfileWarning.key]) of the warnings that the person has read. They are not shown
 * @param onConfirmWarnings called with the warnings of the note when the person taps the button
 * @param onAskAbout null when the chat is not open. Else the button of a finding calls it with a question about that finding
 */
@Composable
internal fun ReportView(
    success: AdvisorOutcome.Success,
    glucose: GlucoseStats?,
    profileWarnings: List<ProfileWarning>,
    sentJson: String?,
    confirmedWarnings: Set<String>,
    onConfirmWarnings: (List<ProfileWarning>) -> Unit,
    onAskAbout: ((String) -> Unit)?
) {
    val report = success.report
    val openWarnings = profileWarnings.filter { it.key !in confirmedWarnings }
    if (openWarnings.isNotEmpty()) ProfileWarningCard(openWarnings, onConfirm = { onConfirmWarnings(openWarnings) })
    SummaryCard(report)
    if (glucose != null) GlucoseRangeCard(glucose)
    val changes = report.findings.mapNotNull { it.change }
    if (changes.isNotEmpty()) TimelineCard(changes)
    Text(text = stringResource(R.string.advisor_findings), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
    if (report.findings.isEmpty()) {
        Text(text = stringResource(R.string.advisor_no_findings), style = MaterialTheme.typography.bodyMedium)
    }
    // sortedBy keeps the order of the AI for findings with the same priority.
    report.findings.sortedBy { it.priority }.forEach { FindingCard(it, onAskAbout) }
    if (report.notEnoughDataFor.isNotEmpty()) PointsCard(R.string.advisor_not_enough_data_for, report.notEnoughDataFor)
    if (report.questionsForTeam.isNotEmpty()) PointsCard(R.string.advisor_questions_for_team, report.questionsForTeam)
    success.usage?.let { usage ->
        Text(
            text = stringResource(R.string.advisor_usage, usage.inputTokens, usage.outputTokens, success.model.orEmpty()),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    if (sentJson != null) SentData(sentJson)
}

// ---- Note about large profile changes ----

/**
 * A fixed note from the app, not from the AI: the profile was changed by more than the advisor proposes. The
 * person may have meant it. The note asks to check.
 */
@Composable
private fun ProfileWarningCard(warnings: List<ProfileWarning>, onConfirm: () -> Unit) {
    Surface(shape = RoundedCornerShape(AapsSpacing.chipCornerRadius), color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.padding(AapsSpacing.large)) {
            Icon(imageVector = Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.size(TITLE_ICON))
            Spacer(modifier = Modifier.width(AapsSpacing.medium))
            Column(verticalArrangement = Arrangement.spacedBy(AapsSpacing.small)) {
                Text(
                    text = stringResource(R.string.advisor_profile_warning_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
                warnings.forEach { warning ->
                    val amount = if (warning.changePercent > 0) {
                        stringResource(R.string.advisor_change_up, warning.changePercent)
                    } else {
                        stringResource(R.string.advisor_change_down, -warning.changePercent)
                    }
                    Text(
                        text = stringResource(
                            R.string.advisor_profile_warning_line,
                            stringResource(changedSettingLabel(warning.setting)),
                            warning.startMinute / 60, warning.startMinute % 60,
                            warning.endMinute / 60, warning.endMinute % 60,
                            amount
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Text(
                        text = stringResource(R.string.advisor_profile_warning_age, warning.madeDaysAgo, warning.madeAtHour),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
                Text(
                    text = stringResource(R.string.advisor_profile_warning_hint, AdvisorPrompt.MAX_CHANGE_PERCENT),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
                // A filled button in the error colors, so it is not read as part of the text.
                Button(
                    onClick = onConfirm,
                    modifier = Modifier.align(Alignment.End),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    )
                ) {
                    Text(text = stringResource(R.string.advisor_profile_warning_confirm))
                }
            }
        }
    }
}

private fun changedSettingLabel(setting: ChangedSetting): Int = when (setting) {
    ChangedSetting.BASAL      -> R.string.advisor_area_basal
    ChangedSetting.CARB_RATIO -> R.string.advisor_area_carb_ratio
    ChangedSetting.ISF        -> R.string.advisor_area_isf
}

// ---- Summary ----

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SummaryCard(report: AdvisorReport) {
    AapsCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(AapsSpacing.large), verticalArrangement = Arrangement.spacedBy(AapsSpacing.medium)) {
            MarkdownText(text = report.overall, style = MaterialTheme.typography.bodyLarge)
            if (report.keyPoints.isNotEmpty()) Points(report.keyPoints)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(AapsSpacing.medium), verticalArrangement = Arrangement.spacedBy(AapsSpacing.small)) {
                AdvisorConfidence.entries.reversed().forEach { confidence ->
                    val count = report.findings.count { it.confidence == confidence }
                    if (count > 0) {
                        Chip(text = stringResource(R.string.advisor_count_label, stringResource(confidenceLabel(confidence)), count), color = confidenceColor(confidence))
                    }
                }
            }
            if (!report.enoughData) {
                Text(text = stringResource(R.string.advisor_summary_not_enough), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            }
            if (report.dataNote.isNotBlank()) {
                MarkdownText(text = report.dataNote, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

// ---- Glucose ranges ----

/** One bar with the five glucose ranges of the days, and a legend with the words and the numbers. */
@Composable
private fun GlucoseRangeCard(glucose: GlucoseStats) {
    val colors = AapsTheme.generalColors
    val ranges = listOf(
        Triple(R.string.advisor_range_very_low, glucose.veryLowPercent, colors.bgVeryLow),
        Triple(R.string.advisor_range_low, glucose.lowPercent, colors.bgLow),
        Triple(R.string.advisor_range_in, glucose.inRangePercent, colors.bgInRange),
        Triple(R.string.advisor_range_high, glucose.highPercent, colors.bgHigh),
        Triple(R.string.advisor_range_very_high, glucose.veryHighPercent, colors.bgVeryHigh)
    )
    AapsCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(AapsSpacing.large), verticalArrangement = Arrangement.spacedBy(AapsSpacing.medium)) {
            Text(text = stringResource(R.string.advisor_ranges_title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Row(modifier = Modifier.fillMaxWidth().height(BAR_HEIGHT).clip(RoundedCornerShape(AapsSpacing.chipCornerRadius))) {
                // A weight must be above zero, so a range without readings has no part in the bar.
                ranges.filter { it.second > 0.0 }.forEach { (_, percent, color) ->
                    Box(modifier = Modifier.weight(percent.toFloat()).fillMaxHeight().background(color))
                }
            }
            ranges.forEach { (label, percent, color) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.size(LEGEND_DOT).clip(CircleShape).background(color))
                    Spacer(modifier = Modifier.width(AapsSpacing.medium))
                    Text(text = stringResource(label), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Text(text = stringResource(R.string.advisor_percent_value, percent), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

// ---- Time of day ----

/**
 * A line for each setting that has a proposed change, with the time ranges as bars over the 24 hours. The name of
 * the setting is at the left of its line, and the list below has the words of every change, so the colors are
 * not the only information.
 */
@Composable
private fun TimelineCard(changes: List<ProposedChange>) {
    val trackColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = TRACK_ALPHA)
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val settings = AdvisorSetting.entries.filter { setting -> changes.any { it.setting == setting } }
    AapsCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(AapsSpacing.large), verticalArrangement = Arrangement.spacedBy(AapsSpacing.medium)) {
            Text(text = stringResource(R.string.advisor_timeline_title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            settings.forEach { setting ->
                val barColor = settingColor(setting)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text = stringResource(settingLabel(setting)), style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(LANE_LABEL_WIDTH))
                    Canvas(modifier = Modifier.weight(1f).height(LANE_HEIGHT)) {
                        val corner = CornerRadius(AapsSpacing.small.toPx())
                        drawRoundRect(color = trackColor, cornerRadius = corner)
                        GRID_HOURS.forEach { hour ->
                            val x = size.width * hour / HOURS_PER_DAY
                            drawLine(color = gridColor, start = Offset(x, 0f), end = Offset(x, size.height), strokeWidth = 1.dp.toPx())
                        }
                        changes.filter { it.setting == setting }.forEach { change ->
                            drawRoundRect(
                                color = barColor,
                                topLeft = Offset(size.width * change.startMinute / MINUTES_PER_DAY, 0f),
                                size = Size(size.width * (change.endMinute - change.startMinute) / MINUTES_PER_DAY, size.height),
                                cornerRadius = corner
                            )
                        }
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Spacer(modifier = Modifier.width(LANE_LABEL_WIDTH))
                Row(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.SpaceBetween) {
                    AXIS_HOURS.forEach { hour ->
                        Text(text = stringResource(R.string.advisor_hour_label, hour), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            changes.forEach { change ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.size(LEGEND_DOT).clip(CircleShape).background(settingColor(change.setting)))
                    Spacer(modifier = Modifier.width(AapsSpacing.medium))
                    Text(text = changeText(change), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

// ---- One finding ----

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FindingCard(finding: AdvisorFinding, onAskAbout: ((String) -> Unit)?) {
    var expanded by rememberSaveable(finding.title) { mutableStateOf(false) }
    val accent = confidenceColor(finding.confidence)
    AapsCard(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.height(IntrinsicSize.Min)) {
            // The stripe has the color of the confidence. The chip next to the title says it in words.
            Box(modifier = Modifier.width(STRIPE_WIDTH).fillMaxHeight().background(accent))
            Column(modifier = Modifier.padding(AapsSpacing.large), verticalArrangement = Arrangement.spacedBy(AapsSpacing.medium)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(imageVector = areaIcon(finding.area), contentDescription = null, tint = accent, modifier = Modifier.size(TITLE_ICON))
                    Spacer(modifier = Modifier.width(AapsSpacing.medium))
                    Text(text = finding.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(AapsSpacing.medium), verticalArrangement = Arrangement.spacedBy(AapsSpacing.small)) {
                    Chip(text = stringResource(priorityLabel(finding.priority)), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Chip(text = stringResource(areaLabel(finding.area)), color = MaterialTheme.colorScheme.primary)
                    Chip(text = stringResource(confidenceLabel(finding.confidence)), color = accent)
                    if (finding.timeOfDay.isNotBlank()) {
                        Chip(text = finding.timeOfDay, color = MaterialTheme.colorScheme.onSurfaceVariant, icon = Icons.Default.Schedule)
                    }
                }
                finding.change?.let { ChangeBadge(it) }
                finding.heldBackDaysAgo?.let { days ->
                    Text(
                        text = stringResource(R.string.advisor_change_held_back, days),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }
                if (finding.changeRemoved) {
                    Text(text = stringResource(R.string.advisor_change_removed), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                TextButton(onClick = { expanded = !expanded }) {
                    Text(stringResource(if (expanded) R.string.advisor_details_hide else R.string.advisor_details_show))
                }
                if (expanded) {
                    if (finding.evidencePoints.isNotEmpty()) {
                        Text(text = stringResource(R.string.advisor_evidence), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Points(finding.evidencePoints)
                    }
                    if (finding.suggestion.isNotBlank()) {
                        Text(text = stringResource(R.string.advisor_idea), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        MarkdownText(text = finding.suggestion)
                    }
                }
                if (onAskAbout != null) {
                    val question = stringResource(R.string.advisor_chat_about, finding.title)
                    TextButton(onClick = { onAskAbout(question) }) { Text(stringResource(R.string.advisor_chat_ask_about)) }
                }
            }
        }
    }
}

/** The proposed change in its own box, with an arrow for more or less. */
@Composable
private fun ChangeBadge(change: ProposedChange) {
    Surface(shape = RoundedCornerShape(AapsSpacing.chipCornerRadius), color = MaterialTheme.colorScheme.primaryContainer) {
        Row(modifier = Modifier.padding(AapsSpacing.large), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = if (change.changePercent > 0) Icons.Default.ArrowUpward else Icons.Default.ArrowDownward,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(TITLE_ICON)
            )
            Spacer(modifier = Modifier.width(AapsSpacing.medium))
            Text(text = changeText(change), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
        }
    }
}

@Composable
private fun Chip(text: String, color: Color, icon: ImageVector? = null) {
    Surface(shape = RoundedCornerShape(AapsSpacing.chipCornerRadius), color = color.copy(alpha = CHIP_ALPHA)) {
        Row(modifier = Modifier.padding(horizontal = AapsSpacing.medium, vertical = AapsSpacing.small), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(imageVector = icon, contentDescription = null, tint = color, modifier = Modifier.size(CHIP_ICON))
                Spacer(modifier = Modifier.width(AapsSpacing.small))
            }
            Text(text = text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

/** A list of short points. Each has a dot and its own lines, so a long point does not run under the dot. */
@Composable
private fun Points(items: List<String>) {
    Column(verticalArrangement = Arrangement.spacedBy(AapsSpacing.small)) {
        items.forEach { item ->
            Row {
                Text(text = "•", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(POINT_MARK_WIDTH))
                MarkdownText(text = item, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun PointsCard(title: Int, items: List<String>) {
    AapsCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(AapsSpacing.large), verticalArrangement = Arrangement.spacedBy(AapsSpacing.small)) {
            Text(text = stringResource(title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Points(items)
        }
    }
}

/** The JSON that was sent, so the user can see that it has no names and no exact times. */
@Composable
private fun SentData(json: String) {
    var visible by rememberSaveable { mutableStateOf(false) }
    TextButton(onClick = { visible = !visible }) {
        Text(stringResource(if (visible) R.string.advisor_hide_sent else R.string.advisor_show_sent))
    }
    if (visible) {
        AapsCard(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = json,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(AapsSpacing.large)
            )
        }
    }
}

// ---- Colors, icons and words ----

@Composable
private fun confidenceColor(confidence: AdvisorConfidence): Color = when (confidence) {
    AdvisorConfidence.HIGH   -> MaterialTheme.colorScheme.primary
    AdvisorConfidence.MEDIUM -> MaterialTheme.colorScheme.tertiary
    AdvisorConfidence.LOW    -> MaterialTheme.colorScheme.outline
}

@Composable
private fun settingColor(setting: AdvisorSetting): Color = when (setting) {
    AdvisorSetting.BASAL      -> MaterialTheme.colorScheme.primary
    AdvisorSetting.CARB_RATIO -> MaterialTheme.colorScheme.tertiary
    AdvisorSetting.ISF        -> MaterialTheme.colorScheme.secondary
}

private fun areaIcon(area: AdvisorArea): ImageVector = when (area) {
    AdvisorArea.BASAL        -> Icons.Default.WaterDrop
    AdvisorArea.CARB_RATIO   -> Icons.Default.Restaurant
    AdvisorArea.ISF          -> Icons.Default.Colorize
    AdvisorArea.DIA          -> Icons.Default.Timer
    AdvisorArea.BOLUS_TIMING -> Icons.Default.Schedule
    AdvisorArea.ACTIVITY     -> Icons.Default.FitnessCenter
    AdvisorArea.LOOP         -> Icons.Default.Autorenew
    AdvisorArea.OTHER        -> Icons.Default.Info
}

private fun priorityLabel(priority: Int): Int = when (priority) {
    1    -> R.string.advisor_priority_high
    3    -> R.string.advisor_priority_low
    else -> R.string.advisor_priority_medium
}

private fun areaLabel(area: AdvisorArea): Int = when (area) {
    AdvisorArea.BASAL        -> R.string.advisor_area_basal
    AdvisorArea.CARB_RATIO   -> R.string.advisor_area_carb_ratio
    AdvisorArea.ISF          -> R.string.advisor_area_isf
    AdvisorArea.DIA          -> R.string.advisor_area_dia
    AdvisorArea.BOLUS_TIMING -> R.string.advisor_area_bolus_timing
    AdvisorArea.ACTIVITY     -> R.string.advisor_area_activity
    AdvisorArea.LOOP         -> R.string.advisor_area_loop
    AdvisorArea.OTHER        -> R.string.advisor_area_other
}

private fun confidenceLabel(confidence: AdvisorConfidence): Int = when (confidence) {
    AdvisorConfidence.LOW    -> R.string.advisor_confidence_low
    AdvisorConfidence.MEDIUM -> R.string.advisor_confidence_medium
    AdvisorConfidence.HIGH   -> R.string.advisor_confidence_high
}

private fun settingLabel(setting: AdvisorSetting): Int = when (setting) {
    AdvisorSetting.BASAL      -> R.string.advisor_area_basal
    AdvisorSetting.CARB_RATIO -> R.string.advisor_area_carb_ratio
    AdvisorSetting.ISF        -> R.string.advisor_area_isf
}

@Composable
private fun changeText(change: ProposedChange): String {
    val amount = if (change.changePercent > 0) {
        stringResource(R.string.advisor_change_up, change.changePercent)
    } else {
        stringResource(R.string.advisor_change_down, -change.changePercent)
    }
    return stringResource(
        R.string.advisor_change_line,
        stringResource(settingLabel(change.setting)),
        change.startMinute / 60, change.startMinute % 60,
        change.endMinute / 60, change.endMinute % 60,
        amount
    )
}
