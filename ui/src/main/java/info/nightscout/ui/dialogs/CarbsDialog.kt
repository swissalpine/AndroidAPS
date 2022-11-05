package info.nightscout.ui.dialogs

import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.google.common.base.Joiner
import info.nightscout.androidaps.data.DetailedBolusInfo
import info.nightscout.androidaps.data.PumpEnactResult
import info.nightscout.androidaps.database.AppRepository
import info.nightscout.androidaps.database.entities.TemporaryTarget
import info.nightscout.androidaps.database.entities.UserEntry
import info.nightscout.androidaps.database.entities.UserEntry.Action
import info.nightscout.androidaps.database.entities.UserEntry.Sources
import info.nightscout.androidaps.database.entities.ValueWithUnit
import info.nightscout.androidaps.database.transactions.InsertAndCancelCurrentTemporaryTargetTransaction
import info.nightscout.androidaps.dialogs.DialogFragmentWithDate
import info.nightscout.androidaps.events.EventRefreshOverview
import info.nightscout.androidaps.extensions.formatColor
import info.nightscout.androidaps.extensions.rawOrSmoothed
import info.nightscout.androidaps.interfaces.*
import info.nightscout.androidaps.logging.UserEntryLogger
import info.nightscout.androidaps.plugins.bus.RxBus
import info.nightscout.androidaps.plugins.iob.iobCobCalculator.GlucoseStatusProvider
import info.nightscout.androidaps.utils.DecimalFormatter
import info.nightscout.androidaps.utils.DefaultValueHelper
import info.nightscout.androidaps.utils.protection.ProtectionCheck
import info.nightscout.androidaps.utils.protection.ProtectionCheck.Protection.BOLUS
import info.nightscout.core.profile.toMgdl
import info.nightscout.core.ui.dialogs.OKDialog
import info.nightscout.core.ui.toast.ToastUtils
import info.nightscout.database.entities.TemporaryTarget
import info.nightscout.database.entities.UserEntry.Action
import info.nightscout.database.entities.UserEntry.Sources
import info.nightscout.database.entities.ValueWithUnit
import info.nightscout.database.impl.AppRepository
import info.nightscout.database.impl.transactions.InsertAndCancelCurrentTemporaryTargetTransaction
import info.nightscout.interfaces.BolusTimer
import info.nightscout.interfaces.CarbTimer
import info.nightscout.interfaces.GlucoseUnit
import info.nightscout.interfaces.constraints.Constraint
import info.nightscout.interfaces.constraints.Constraints
import info.nightscout.interfaces.iob.IobCobCalculator
import info.nightscout.interfaces.profile.Profile
import info.nightscout.interfaces.profile.ProfileFunction
import info.nightscout.interfaces.pump.DetailedBolusInfo
import info.nightscout.interfaces.queue.Callback
import info.nightscout.interfaces.queue.CommandQueue
import info.nightscout.interfaces.ui.ActivityNames
import info.nightscout.interfaces.utils.HtmlHelper
import info.nightscout.rx.logging.LTag
import info.nightscout.shared.interfaces.ResourceHelper
import info.nightscout.shared.utils.T
import info.nightscout.ui.R
import info.nightscout.ui.databinding.DialogCarbsBinding
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.kotlin.plusAssign
import java.text.DecimalFormat
import java.util.LinkedList
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlin.math.max

class CarbsDialog : DialogFragmentWithDate() {

    @Inject lateinit var ctx: Context
    @Inject lateinit var rh: ResourceHelper
    @Inject lateinit var loop: Loop
    @Inject lateinit var constraintChecker: Constraints
    @Inject lateinit var defaultValueHelper: DefaultValueHelper
    @Inject lateinit var profileFunction: ProfileFunction
    @Inject lateinit var iobCobCalculator: IobCobCalculator
    @Inject lateinit var glucoseStatusProvider: GlucoseStatusProvider
    @Inject lateinit var uel: UserEntryLogger
    @Inject lateinit var carbTimer: CarbTimer
    @Inject lateinit var bolusTimer: BolusTimer
    @Inject lateinit var commandQueue: CommandQueue
    @Inject lateinit var repository: AppRepository
    @Inject lateinit var protectionCheck: ProtectionCheck
    @Inject lateinit var activityNames: ActivityNames
    @Inject lateinit var activePlugin: ActivePlugin

    companion object {

        const val FAV1_DEFAULT = 5
        const val FAV2_DEFAULT = 10
        const val FAV3_DEFAULT = 20
    }

    private var queryingProtection = false
    private val disposable = CompositeDisposable()

    private val textWatcher: TextWatcher = object : TextWatcher {
        override fun afterTextChanged(s: Editable) {
            validateInputs()
        }

        override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {}
    }

    private fun validateInputs() {
        val maxCarbs = constraintChecker.getMaxCarbsAllowed().value().toDouble()
        val time = binding.time.value.toInt()
        if (time > 12 * 60 || time < -7 * 24 * 60) {
            binding.time.value = 0.0
            ToastUtils.warnToast(ctx, R.string.constraint_applied)
        }
        if (binding.duration.value > 10) {
            binding.duration.value = 0.0
            ToastUtils.warnToast(ctx, R.string.constraint_applied)
        }
        if (binding.carbs.value.toInt() > maxCarbs) {
            binding.carbs.value = 0.0
            ToastUtils.warnToast(ctx, R.string.carbs_constraint_applied)
        }
    }

    private var _binding: DialogCarbsBinding? = null

    // This property is only valid between onCreateView and
    // onDestroyView.
    private val binding get() = _binding!!

    override fun onSaveInstanceState(savedInstanceState: Bundle) {
        super.onSaveInstanceState(savedInstanceState)
        savedInstanceState.putDouble("time", binding.time.value)
        savedInstanceState.putDouble("duration", binding.duration.value)
        savedInstanceState.putDouble("carbs", binding.carbs.value)
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        onCreateViewGeneral()
        _binding = DialogCarbsBinding.inflate(inflater, container, false)
        binding.time.setOnValueChangedListener { timeOffset: Double ->
            run {
                val newTime = eventTimeOriginal + timeOffset.toLong() * 1000 * 60
                updateDateTime(newTime)
            }
        }
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (sp.getBoolean(R.string.key_usebolusreminder, false)) {
            glucoseStatusProvider.glucoseStatusData?.let { glucoseStatus ->
                if (glucoseStatus.glucose + 3 * glucoseStatus.delta < 70.0)
                    binding.bolusReminder.visibility = View.VISIBLE
            }
        }
        val maxCarbs = constraintChecker.getMaxCarbsAllowed().value().toDouble()
        binding.time.setParams(
            savedInstanceState?.getDouble("time")
                ?: 0.0, -7 * 24 * 60.0, 12 * 60.0, 5.0, DecimalFormat("0"), false, binding.okcancel.ok, textWatcher
        )

        binding.duration.setParams(
            savedInstanceState?.getDouble("duration")
                ?: 0.0, 0.0, 10.0, 1.0, DecimalFormat("0"), false, binding.okcancel.ok, textWatcher
        )

        binding.carbs.setParams(
            savedInstanceState?.getDouble("carbs")
                ?: 0.0, 0.0, maxCarbs, 1.0, DecimalFormat("0"), false, binding.okcancel.ok, textWatcher
        )
        val plus1text = toSignedString(sp.getInt(R.string.key_carbs_button_increment_1, FAV1_DEFAULT))
        binding.plus1.text = plus1text
        binding.plus1.contentDescription = rh.gs(R.string.carbs) + " " + plus1text
        binding.plus1.setOnClickListener {
            binding.carbs.value = max(
                0.0, binding.carbs.value
                    + sp.getInt(R.string.key_carbs_button_increment_1, FAV1_DEFAULT)
            )
            validateInputs()
            binding.carbs.announceValue()
        }

        val plus2text = toSignedString(sp.getInt(R.string.key_carbs_button_increment_2, FAV2_DEFAULT))
        binding.plus2.text = plus2text
        binding.plus2.contentDescription = rh.gs(R.string.carbs) + " " + plus2text
        binding.plus2.setOnClickListener {
            binding.carbs.value = max(
                0.0, binding.carbs.value
                    + sp.getInt(R.string.key_carbs_button_increment_2, FAV2_DEFAULT)
            )
            validateInputs()
            binding.carbs.announceValue()
        }
        val plus3text = toSignedString(sp.getInt(R.string.key_carbs_button_increment_3, FAV3_DEFAULT))
        binding.plus3.text = plus3text
        binding.plus2.contentDescription = rh.gs(R.string.carbs) + " " + plus3text
        binding.plus3.setOnClickListener {
            binding.carbs.value = max(
                0.0, binding.carbs.value
                    + sp.getInt(R.string.key_carbs_button_increment_3, FAV3_DEFAULT)
            )
            validateInputs()
            binding.carbs.announceValue()
        }

        setOnValueChangedListener { eventTime: Long ->
            run {
                val timeOffset = ((eventTime - eventTimeOriginal) / (1000 * 60)).toDouble()
                if (_binding != null) binding.time.value = timeOffset
            }
        }

        iobCobCalculator.ads.actualBg()?.let { bgReading ->
            if (bgReading.value < 72)
                binding.hypoTt.isChecked = true
        }
        binding.hypoTt.setOnClickListener {
            binding.activityTt.isChecked = false
            binding.eatingSoonTt.isChecked = false
            binding.hypoAction.isChecked = false
        }
        binding.activityTt.setOnClickListener {
            binding.hypoTt.isChecked = false
            binding.eatingSoonTt.isChecked = false
            binding.hypoAction.isChecked = false
        }
        binding.eatingSoonTt.setOnClickListener {
            binding.hypoTt.isChecked = false
            binding.activityTt.isChecked = false
            binding.hypoAction.isChecked = false
        }
        binding.hypoAction.setOnClickListener {
            binding.hypoTt.isChecked = false
            binding.eatingSoonTt.isChecked = false
            binding.activityTt.isChecked = false
        }
        binding.durationLabel.labelFor = binding.duration.editTextId
        binding.timeLabel.labelFor = binding.time.editTextId
        binding.carbsLabel.labelFor = binding.carbs.editTextId
    }

    override fun onDestroyView() {
        super.onDestroyView()
        disposable.clear()
        _binding = null
    }

    private fun toSignedString(value: Int): String {
        return if (value > 0) "+$value" else value.toString()
    }

    override fun submit(): Boolean {
        if (_binding == null) return false
        val carbs = binding.carbs.value.toInt()
        val carbsAfterConstraints = constraintChecker.applyCarbsConstraints(Constraint(carbs)).value()
        val units = profileFunction.getUnits()
        val activityTTDuration = defaultValueHelper.determineActivityTTDuration()
        val activityTT = defaultValueHelper.determineActivityTT()
        val eatingSoonTTDuration = defaultValueHelper.determineEatingSoonTTDuration()
        val eatingSoonTT = defaultValueHelper.determineEatingSoonTT()
        val hypoTTDuration = defaultValueHelper.determineHypoTTDuration()
        val hypoTT = defaultValueHelper.determineHypoTT()
        val actions: LinkedList<String?> = LinkedList()
        val unitLabel = if (units == GlucoseUnit.MMOL) rh.gs(R.string.mmol) else rh.gs(R.string.mgdl)
        val useAlarm = binding.alarmCheckBox.isChecked
        val remindBolus = binding.bolusReminderCheckBox.isChecked

        //Anpassung 1
        val profile = profileFunction.getProfile() ?: return false
        //Ende Anpassung

        val activitySelected = binding.activityTt.isChecked
        if (activitySelected)
            actions.add(
                rh.gs(R.string.temp_target_short) + ": " + (DecimalFormatter.to1Decimal(activityTT) + " " + unitLabel + " (" + rh.gs(R.string.format_mins, activityTTDuration) + ")").formatColor(
                    context,
                    rh,
                    R.attr.tempTargetConfirmation
                )
            )
        val eatingSoonSelected = binding.eatingSoonTt.isChecked
        if (eatingSoonSelected)
            actions.add(
                rh.gs(R.string.temp_target_short) + ": " + (DecimalFormatter.to1Decimal(eatingSoonTT) + " " + unitLabel + " (" + rh.gs(
                    R.string.format_mins,
                    eatingSoonTTDuration
                ) + ")").formatColor(context, rh, R.attr.tempTargetConfirmation)
            )
        val hypoSelected = binding.hypoTt.isChecked
        if (hypoSelected)
            actions.add(
                rh.gs(R.string.temp_target_short) + ": " + (DecimalFormatter.to1Decimal(hypoTT) + " " + unitLabel + " (" + rh.gs(R.string.format_mins, hypoTTDuration) + ")").formatColor(
                    context,
                    rh,
                    R.attr.tempTargetConfirmation
                )
            )
        // Anpassung 2
        val hypoActionSelected = binding.hypoAction.isChecked
        if (hypoActionSelected)
            actions.add(
                rh.gs(R.string.temp_target_short) + ": " + (DecimalFormatter.to1Decimal(hypoTT) + " " + unitLabel +
                    " (" + rh.gs(R.string.format_mins, hypoTTDuration) + ")  + TBR: 50% (60 min)").formatColor( context, rh,
                    R.attr.tempTargetConfirmation
                )
            )
        // Ende Anpassung
        val timeOffset = binding.time.value.toInt()
        if (useAlarm && carbs > 0 && timeOffset > 0)
            actions.add(rh.gs(R.string.alarminxmin, timeOffset).formatColor(context, rh, R.attr.infoColor))
        val duration = binding.duration.value.toInt()
        if (duration > 0)
            actions.add(rh.gs(R.string.duration) + ": " + duration + rh.gs(R.string.shorthour))
        if (carbsAfterConstraints > 0) {
            actions.add(rh.gs(R.string.carbs) + ": " + "<font color='" + rh.gac(context, R.attr.carbsColor) + "'>" + rh.gs(R.string.format_carbs, carbsAfterConstraints) + "</font>")
            if (carbsAfterConstraints != carbs)
                actions.add("<font color='" + rh.gac(context, R.attr.warningColor) + "'>" + rh.gs(R.string.carbs_constraint_applied) + "</font>")
        }
        val notes = binding.notesLayout.notes.text.toString()
        if (notes.isNotEmpty())
            actions.add(rh.gs(R.string.notes_label) + ": " + notes)

        if (eventTimeChanged)
            actions.add(rh.gs(R.string.time) + ": " + dateUtil.dateAndTimeString(eventTime))

        if (carbsAfterConstraints > 0 || activitySelected || eatingSoonSelected || hypoSelected || hypoActionSelected) {
            activity?.let { activity ->
                OKDialog.showConfirmation(activity, rh.gs(R.string.carbs), HtmlHelper.fromHtml(Joiner.on("<br/>").join(actions)), {
                    when {
                        activitySelected -> {
                            uel.log(
                                Action.TT, Sources.CarbDialog,
                                ValueWithUnit.TherapyEventTTReason(TemporaryTarget.Reason.ACTIVITY),
                                ValueWithUnit.fromGlucoseUnit(activityTT, units.asText),
                                ValueWithUnit.Minute(activityTTDuration)
                            )
                            disposable += repository.runTransactionForResult(
                                InsertAndCancelCurrentTemporaryTargetTransaction(
                                    timestamp = System.currentTimeMillis(),
                                    duration = TimeUnit.MINUTES.toMillis(activityTTDuration.toLong()),
                                    reason = TemporaryTarget.Reason.ACTIVITY,
                                    lowTarget = Profile.toMgdl(activityTT, profileFunction.getUnits()),
                                    highTarget = Profile.toMgdl(activityTT, profileFunction.getUnits())
                                )
                            ).subscribe({ result ->
                                            result.inserted.forEach { aapsLogger.debug(LTag.DATABASE, "Inserted temp target $it") }
                                            result.updated.forEach { aapsLogger.debug(LTag.DATABASE, "Updated temp target $it") }
                                        }, {
                                            aapsLogger.error(LTag.DATABASE, "Error while saving temporary target", it)
                                        })
                        }

                        eatingSoonSelected -> {
                            uel.log(
                                Action.TT, Sources.CarbDialog,
                                ValueWithUnit.TherapyEventTTReason(TemporaryTarget.Reason.EATING_SOON),
                                ValueWithUnit.fromGlucoseUnit(eatingSoonTT, units.asText),
                                ValueWithUnit.Minute(eatingSoonTTDuration)
                            )
                            disposable += repository.runTransactionForResult(
                                InsertAndCancelCurrentTemporaryTargetTransaction(
                                    timestamp = System.currentTimeMillis(),
                                    duration = TimeUnit.MINUTES.toMillis(eatingSoonTTDuration.toLong()),
                                    reason = TemporaryTarget.Reason.EATING_SOON,
                                    lowTarget = Profile.toMgdl(eatingSoonTT, profileFunction.getUnits()),
                                    highTarget = Profile.toMgdl(eatingSoonTT, profileFunction.getUnits())
                                )
                            ).subscribe({ result ->
                                            result.inserted.forEach { aapsLogger.debug(LTag.DATABASE, "Inserted temp target $it") }
                                            result.updated.forEach { aapsLogger.debug(LTag.DATABASE, "Updated temp target $it") }
                                        }, {
                                            aapsLogger.error(LTag.DATABASE, "Error while saving temporary target", it)
                                        })
                        }

                        hypoSelected -> {
                            uel.log(
                                Action.TT, Sources.CarbDialog,
                                ValueWithUnit.TherapyEventTTReason(TemporaryTarget.Reason.HYPOGLYCEMIA),
                                ValueWithUnit.fromGlucoseUnit(hypoTT, units.asText),
                                ValueWithUnit.Minute(hypoTTDuration)
                            )
                            disposable += repository.runTransactionForResult(
                                InsertAndCancelCurrentTemporaryTargetTransaction(
                                    timestamp = System.currentTimeMillis(),
                                    duration = TimeUnit.MINUTES.toMillis(hypoTTDuration.toLong()),
                                    reason = TemporaryTarget.Reason.HYPOGLYCEMIA,
                                    lowTarget = Profile.toMgdl(hypoTT, profileFunction.getUnits()),
                                    highTarget = Profile.toMgdl(hypoTT, profileFunction.getUnits())
                                )
                            ).subscribe({ result ->
                                            result.inserted.forEach { aapsLogger.debug(LTag.DATABASE, "Inserted temp target $it") }
                                            result.updated.forEach { aapsLogger.debug(LTag.DATABASE, "Updated temp target $it") }
                                        }, {
                                            aapsLogger.error(LTag.DATABASE, "Error while saving temporary target", it)
                                        })
                        }
                        // Anpassung 3 (und Text start_hypo_tt in strings.xml
                        hypoActionSelected       -> {
                            loop.suspendLoop(60)
                            rxBus.send(EventRefreshOverview("ActionLoopSuspend"))
                            val callback: Callback = object : Callback() {
                                override fun run() {
                                    if (!result.success) {
                                        activityNames.runAlarm(ctx, result.comment, rh.gs(R.string.tempbasaldeliveryerror), R.raw.boluserror)
                                    }
                                }
                            }
                            // only percent pump!!!
                            var isPercentPump = true
                            val pumpDescription = activePlugin.activePump.pumpDescription
                            isPercentPump = pumpDescription.tempBasalStyle and PumpDescription.PERCENT == PumpDescription.PERCENT
                            if (isPercentPump) {
                                uel.log(Action.TEMP_BASAL, Sources.TempBasalDialog,
                                        ValueWithUnit.Percent(50),
                                        ValueWithUnit.Minute(60))
                                commandQueue.tempBasalPercent(50, 60, true, profile, PumpSync.TemporaryBasalType.NORMAL, callback)
                            } else {
                                uel.log(Action.TEMP_BASAL, Sources.TempBasalDialog,
                                        ValueWithUnit.Insulin(profile.getBasal() * 0.5),
                                        ValueWithUnit.Minute(60))
                                commandQueue.tempBasalAbsolute(profile.getBasal() * 0.5, 60, true, profile, PumpSync.TemporaryBasalType.NORMAL, callback)
                            }

                            uel.log(Action.TT, Sources.CarbDialog,
                                ValueWithUnit.TherapyEventTTReason(TemporaryTarget.Reason.HYPOGLYCEMIA),
                                ValueWithUnit.fromGlucoseUnit(hypoTT, units.asText),
                                ValueWithUnit.Minute(hypoTTDuration))
                            disposable += repository.runTransactionForResult(InsertAndCancelCurrentTemporaryTargetTransaction(
                                timestamp = System.currentTimeMillis(),
                                duration = TimeUnit.MINUTES.toMillis(hypoTTDuration.toLong()),
                                reason = TemporaryTarget.Reason.HYPOGLYCEMIA,
                                lowTarget = Profile.toMgdl(hypoTT, profileFunction.getUnits()),
                                highTarget = Profile.toMgdl(hypoTT, profileFunction.getUnits())
                            )).subscribe({ result ->
                                result.inserted.forEach { aapsLogger.debug(LTag.DATABASE, "Inserted temp target $it") }
                                result.updated.forEach { aapsLogger.debug(LTag.DATABASE, "Updated temp target $it") }
                            }, {
                                aapsLogger.error(LTag.DATABASE, "Error while saving temporary target", it)
                            })
                        }
                        // Ende Anpassung
                    }
                    if (carbsAfterConstraints > 0) {
                        val detailedBolusInfo = DetailedBolusInfo()
                        detailedBolusInfo.eventType = DetailedBolusInfo.EventType.CORRECTION_BOLUS
                        detailedBolusInfo.carbs = carbsAfterConstraints.toDouble()
                        detailedBolusInfo.context = context
                        detailedBolusInfo.notes = notes
                        detailedBolusInfo.carbsDuration = T.hours(duration.toLong()).msecs()
                        detailedBolusInfo.carbsTimestamp = eventTime
                        uel.log(if (duration == 0) Action.CARBS else Action.EXTENDED_CARBS, Sources.CarbDialog,
                                notes,
                                ValueWithUnit.Timestamp(eventTime).takeIf { eventTimeChanged },
                                ValueWithUnit.Gram(carbsAfterConstraints),
                                ValueWithUnit.Minute(timeOffset).takeIf { timeOffset != 0 },
                                ValueWithUnit.Hour(duration).takeIf { duration != 0 })
                        commandQueue.bolus(detailedBolusInfo, object : Callback() {
                            override fun run() {
                                carbTimer.removeAutomationEventEatReminder()
                                if (!result.success) {
                                    activityNames.runAlarm(ctx, result.comment, rh.gs(R.string.treatmentdeliveryerror), R.raw.boluserror)
                                } else if (sp.getBoolean(R.string.key_usebolusreminder, false) && remindBolus)
                                    bolusTimer.scheduleAutomationEventBolusReminder()
                            }
                        })
                    }
                    if (useAlarm && carbs > 0 && timeOffset > 0) {
                        carbTimer.scheduleTimeToEatReminder(T.mins(timeOffset.toLong()).secs().toInt())
                    }
                }, null)
            }
        } else
            activity?.let { activity ->
                OKDialog.show(activity, rh.gs(R.string.carbs), rh.gs(R.string.no_action_selected))
            }
        return true
    }

    override fun onResume() {
        super.onResume()
        if (!queryingProtection) {
            queryingProtection = true
            activity?.let { activity ->
                val cancelFail = {
                    queryingProtection = false
                    aapsLogger.debug(LTag.APS, "Dialog canceled on resume protection: ${this.javaClass.simpleName}")
                    ToastUtils.warnToast(ctx, R.string.dialog_canceled)
                    dismiss()
                }
                protectionCheck.queryProtection(activity, BOLUS, { queryingProtection = false }, cancelFail, cancelFail)
            }
        }
    }
}
