package info.nightscout.androidaps.historyBrowser

import android.content.Context
import dagger.android.HasAndroidInjector
import info.nightscout.androidaps.database.AppRepository
import info.nightscout.androidaps.interfaces.ActivePluginProvider
import info.nightscout.androidaps.interfaces.DatabaseHelperInterface
import info.nightscout.androidaps.interfaces.ProfileFunction
import info.nightscout.androidaps.interfaces.UploadQueueInterface
import info.nightscout.androidaps.logging.AAPSLogger
import info.nightscout.androidaps.plugins.bus.RxBusWrapper
import info.nightscout.androidaps.plugins.general.nsclient.NSUpload
import info.nightscout.androidaps.plugins.treatments.TreatmentService
import info.nightscout.androidaps.plugins.treatments.TreatmentsPlugin
import info.nightscout.androidaps.utils.DateUtil
import info.nightscout.androidaps.utils.FabricPrivacy
import info.nightscout.androidaps.utils.resources.ResourceHelper
import info.nightscout.androidaps.utils.rx.AapsSchedulers
import info.nightscout.androidaps.utils.sharedPreferences.SP
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TreatmentsPluginHistory @Inject constructor(
    injector: HasAndroidInjector,
    aapsLogger: AAPSLogger,
    aapsSchedulers: AapsSchedulers,
    rxBus: RxBusWrapper,
    resourceHelper: ResourceHelper,
    context: Context,
    sp: SP,
    profileFunction: ProfileFunction,
    activePlugin: ActivePluginProvider,
    nsUpload: NSUpload,
    fabricPrivacy: FabricPrivacy,
    dateUtil: DateUtil,
    uploadQueue: UploadQueueInterface,
    databaseHelper: DatabaseHelperInterface,
    repository: AppRepository
) : TreatmentsPlugin(injector, aapsLogger, rxBus, aapsSchedulers, resourceHelper, context, sp, profileFunction, activePlugin, nsUpload, fabricPrivacy, dateUtil, uploadQueue, databaseHelper, repository) {

    init {
        onStart()
    }

    override fun onStart() {
        service = TreatmentService(injector)
        initializeData(range())
    }
}