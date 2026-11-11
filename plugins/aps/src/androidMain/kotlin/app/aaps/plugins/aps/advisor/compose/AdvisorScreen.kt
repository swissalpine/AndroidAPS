package app.aaps.plugins.aps.advisor.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import app.aaps.core.ui.compose.AapsCard
import app.aaps.core.ui.compose.AapsSpacing
import app.aaps.plugins.aps.R
import app.aaps.plugins.aps.advisor.AdvisorArea
import app.aaps.plugins.aps.advisor.AdvisorConfidence
import app.aaps.plugins.aps.advisor.AdvisorFailure
import app.aaps.plugins.aps.advisor.AdvisorFinding
import app.aaps.plugins.aps.advisor.AdvisorOutcome
import app.aaps.plugins.aps.advisor.AdvisorPhase
import app.aaps.plugins.aps.advisor.AdvisorSetting
import app.aaps.plugins.aps.advisor.AdvisorState
import app.aaps.plugins.aps.advisor.ProposedChange

/**
 * The screen of the AI advisor: a card to start an analysis, and below it the answer or the error.
 *
 * @param days how many days the next analysis looks at (for the note about what is sent)
 * @param ready the user agreed to send data and has entered an API key
 */
@Composable
fun AdvisorScreen(
    state: AdvisorState,
    days: Int,
    ready: Boolean,
    onStart: () -> Unit,
    onCancel: () -> Unit,
    onSettings: (() -> Unit)?
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(AapsSpacing.medium),
        verticalArrangement = Arrangement.spacedBy(AapsSpacing.medium)
    ) {
        AapsCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(AapsSpacing.large),
                verticalArrangement = Arrangement.spacedBy(AapsSpacing.medium)
            ) {
                Text(text = stringResource(R.string.advisor_disclaimer), style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = stringResource(R.string.advisor_privacy_note, days),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                when (state.phase) {
                    AdvisorPhase.COLLECTING, AdvisorPhase.WAITING -> Running(state.phase, onCancel)
                    AdvisorPhase.IDLE, AdvisorPhase.DONE          -> Start(finished = state.phase == AdvisorPhase.DONE, ready = ready, onStart = onStart, onSettings = onSettings)
                }
            }
        }
        when (val outcome = state.outcome) {
            is AdvisorOutcome.Success -> Report(outcome, state.sentJson)
            is AdvisorOutcome.Failure -> ErrorCard(outcome)
            null                      -> Unit
        }
    }
}

@Composable
private fun Running(phase: AdvisorPhase, onCancel: () -> Unit) {
    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    Text(
        text = stringResource(if (phase == AdvisorPhase.COLLECTING) R.string.advisor_collecting else R.string.advisor_waiting),
        style = MaterialTheme.typography.bodyMedium
    )
    TextButton(onClick = onCancel) { Text(stringResource(R.string.advisor_cancel)) }
}

@Composable
private fun Start(finished: Boolean, ready: Boolean, onStart: () -> Unit, onSettings: (() -> Unit)?) {
    if (!ready) {
        Text(text = stringResource(R.string.advisor_not_ready), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(AapsSpacing.medium)) {
        Button(onClick = onStart, enabled = ready) {
            Text(stringResource(if (finished) R.string.advisor_new_analysis else R.string.advisor_start))
        }
        if (onSettings != null) {
            TextButton(onClick = onSettings) { Text(stringResource(R.string.advisor_settings)) }
        }
    }
}

@Composable
private fun ErrorCard(failure: AdvisorOutcome.Failure) {
    AapsCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(AapsSpacing.large), verticalArrangement = Arrangement.spacedBy(AapsSpacing.small)) {
            Text(text = stringResource(failureText(failure.kind)), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            if (failure.detail.isNotBlank()) {
                Text(text = stringResource(R.string.advisor_error_detail, failure.detail), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private fun failureText(kind: AdvisorFailure): Int = when (kind) {
    AdvisorFailure.NOT_READY    -> R.string.advisor_not_ready
    AdvisorFailure.DATA_ERROR   -> R.string.advisor_error_data
    AdvisorFailure.UNAUTHORIZED -> R.string.advisor_error_unauthorized
    AdvisorFailure.RATE_LIMITED -> R.string.advisor_error_rate_limited
    AdvisorFailure.SERVER       -> R.string.advisor_error_server
    AdvisorFailure.NETWORK      -> R.string.advisor_error_network
    AdvisorFailure.REFUSED      -> R.string.advisor_error_refused
    AdvisorFailure.TRUNCATED    -> R.string.advisor_error_truncated
    AdvisorFailure.BAD_RESPONSE -> R.string.advisor_error_bad_response
}

@Composable
private fun Report(success: AdvisorOutcome.Success, sentJson: String?) {
    val report = success.report
    AapsCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(AapsSpacing.large), verticalArrangement = Arrangement.spacedBy(AapsSpacing.small)) {
            Text(text = report.overall, style = MaterialTheme.typography.bodyMedium)
            if (report.dataNote.isNotBlank()) {
                Text(text = report.dataNote, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    Text(text = stringResource(R.string.advisor_findings), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
    if (report.findings.isEmpty()) {
        Text(text = stringResource(R.string.advisor_no_findings), style = MaterialTheme.typography.bodyMedium)
    }
    report.findings.forEach { FindingCard(it) }
    if (report.notEnoughDataFor.isNotEmpty()) TextListCard(R.string.advisor_not_enough_data_for, report.notEnoughDataFor)
    if (report.questionsForTeam.isNotEmpty()) TextListCard(R.string.advisor_questions_for_team, report.questionsForTeam)
    success.usage?.let { usage ->
        Text(
            text = stringResource(R.string.advisor_usage, usage.inputTokens, usage.outputTokens, success.model.orEmpty()),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    if (sentJson != null) SentData(sentJson)
}

@Composable
private fun FindingCard(finding: AdvisorFinding) {
    AapsCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(AapsSpacing.large), verticalArrangement = Arrangement.spacedBy(AapsSpacing.small)) {
            Text(text = finding.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(AapsSpacing.medium)) {
                Text(text = stringResource(areaLabel(finding.area)), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Text(text = stringResource(confidenceLabel(finding.confidence)), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (finding.timeOfDay.isNotBlank()) {
                    Text(text = finding.timeOfDay, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            LabeledText(R.string.advisor_evidence, finding.evidence)
            LabeledText(R.string.advisor_idea, finding.suggestion)
            finding.change?.let { change ->
                Text(text = changeText(change), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
            }
            if (finding.changeRemoved) {
                Text(text = stringResource(R.string.advisor_change_removed), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun LabeledText(label: Int, text: String) {
    if (text.isBlank()) return
    Column {
        Text(text = stringResource(label), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text = text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun TextListCard(title: Int, items: List<String>) {
    AapsCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(AapsSpacing.large), verticalArrangement = Arrangement.spacedBy(AapsSpacing.small)) {
            Text(text = stringResource(title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            items.forEach { Text(text = it, style = MaterialTheme.typography.bodyMedium) }
        }
    }
}

/** The JSON that was sent, so the user can see that it has no names and no exact times. */
@Composable
private fun SentData(json: String) {
    var visible by remember { mutableStateOf(false) }
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
