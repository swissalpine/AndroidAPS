package info.nightscout.androidaps.core.di

import dagger.Module
import dagger.android.ContributesAndroidInjector
import info.nightscout.androidaps.data.GlucoseValueDataPoint
import info.nightscout.androidaps.data.Profile
import info.nightscout.androidaps.data.PumpEnactResult
import info.nightscout.androidaps.data.TherapyEventDataPoint
import info.nightscout.androidaps.db.ExtendedBolus
import info.nightscout.androidaps.db.ProfileSwitch
import info.nightscout.androidaps.db.TemporaryBasal
import info.nightscout.androidaps.db.Treatment
import info.nightscout.androidaps.interfaces.ProfileStore
import info.nightscout.androidaps.plugins.aps.loop.APSResult
import info.nightscout.androidaps.plugins.general.nsclient.data.NSMbg
import info.nightscout.androidaps.plugins.iob.iobCobCalculator.data.AutosensData

@Module
@Suppress("unused")
abstract class CoreDataClassesModule {

    @ContributesAndroidInjector abstract fun nsMbgInjector(): NSMbg

    @ContributesAndroidInjector abstract fun pumpEnactResultInjector(): PumpEnactResult
    @ContributesAndroidInjector abstract fun apsResultInjector(): APSResult
    @ContributesAndroidInjector abstract fun autosensDataInjector(): AutosensData

    @ContributesAndroidInjector abstract fun profileInjector(): Profile
    @ContributesAndroidInjector abstract fun profileStoreInjector(): ProfileStore
    @ContributesAndroidInjector abstract fun treatmentInjector(): Treatment
    @ContributesAndroidInjector abstract fun profileSwitchInjector(): ProfileSwitch
    @ContributesAndroidInjector abstract fun temporaryBasalInjector(): TemporaryBasal
    @ContributesAndroidInjector abstract fun extendedBolusInjector(): ExtendedBolus
    @ContributesAndroidInjector abstract fun glucoseValueDataPointInjector(): GlucoseValueDataPoint
    @ContributesAndroidInjector abstract fun therapyEventDataPointInjector(): TherapyEventDataPoint
}
