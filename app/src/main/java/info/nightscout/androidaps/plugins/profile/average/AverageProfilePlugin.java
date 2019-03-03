package info.nightscout.androidaps.plugins.profile.average;

import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import info.nightscout.androidaps.Constants;
import info.nightscout.androidaps.MainApp;
import info.nightscout.androidaps.R;
import info.nightscout.androidaps.data.ProfileStore;
import info.nightscout.androidaps.events.EventProfileStoreChanged;
import info.nightscout.androidaps.interfaces.PluginBase;
import info.nightscout.androidaps.interfaces.PluginDescription;
import info.nightscout.androidaps.interfaces.PluginType;
import info.nightscout.androidaps.interfaces.ProfileInterface;
import info.nightscout.androidaps.logging.L;
import info.nightscout.androidaps.utils.DecimalFormatter;
import info.nightscout.androidaps.utils.SP;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AverageProfilePlugin extends PluginBase implements ProfileInterface {
    public static final String AVERAGE_PROFILE = "AverageProfile";
    private static Logger log = LoggerFactory.getLogger(L.PROFILE);

    private static AverageProfilePlugin averageProfilePlugin;

    public static AverageProfilePlugin getPlugin() {
        if (averageProfilePlugin == null)
            averageProfilePlugin = new AverageProfilePlugin();
        return averageProfilePlugin;
    }

    private ProfileStore convertedProfile = null;

    private static final String DEFAULTARRAY = "[{\"time\":\"00:00\",\"timeAsSeconds\":0,\"value\":0}]";

    boolean edited;
    boolean mgdl;
    boolean mmol;
    Double dia;
    Double ic;
    Double isf;
    JSONArray basal;
    JSONArray targetLow;
    JSONArray targetHigh;

    public boolean isEdited() {
        return edited;
    }

    public void setEdited(boolean edited) {
        this.edited = edited;
    }

    public AverageProfilePlugin() {
        super(new PluginDescription()
                .mainType(PluginType.PROFILE)
                .fragmentClass(AverageProfileFragment.class.getName())
                .pluginName(R.string.average_profile)
                .shortName(R.string.average_profile_short)
                .description(R.string.average_profile_description)
        );
        loadSettings();
    }

    public synchronized void storeSettings() {
        SP.putBoolean(AVERAGE_PROFILE + "mmol", mmol);
        SP.putBoolean(AVERAGE_PROFILE + "mgdl", mgdl);
        SP.putString(AVERAGE_PROFILE + "dia", dia.toString());
        SP.putString(AVERAGE_PROFILE + "ic", ic.toString());
        SP.putString(AVERAGE_PROFILE + "isf", isf.toString());
        SP.putString(AVERAGE_PROFILE + "basal", basal.toString());
        SP.putString(AVERAGE_PROFILE + "targetlow", targetLow.toString());
        SP.putString(AVERAGE_PROFILE + "targethigh", targetHigh.toString());

        createAndStoreConvertedProfile();
        edited = false;
        MainApp.bus().post(new EventProfileStoreChanged());
    }

    public synchronized void loadSettings() {
        mgdl = SP.getBoolean(AVERAGE_PROFILE + "mgdl", false);
        mmol = SP.getBoolean(AVERAGE_PROFILE + "mmol", true);
        dia = SP.getDouble(AVERAGE_PROFILE + "dia", Constants.defaultDIA);
        ic = SP.getDouble(AVERAGE_PROFILE + "ic", 0D);
        isf = SP.getDouble(AVERAGE_PROFILE + "isf", 0D);
        try {
            basal = new JSONArray(SP.getString(AVERAGE_PROFILE + "basal", DEFAULTARRAY));
        } catch (JSONException e1) {
            try {
                basal = new JSONArray(DEFAULTARRAY);
            } catch (JSONException ignored) {
            }
        }
        try {
            targetLow = new JSONArray(SP.getString(AVERAGE_PROFILE + "targetlow", DEFAULTARRAY));
        } catch (JSONException e1) {
            try {
                targetLow = new JSONArray(DEFAULTARRAY);
            } catch (JSONException ignored) {
            }
        }
        try {
            targetHigh = new JSONArray(SP.getString(AVERAGE_PROFILE + "targethigh", DEFAULTARRAY));
        } catch (JSONException e1) {
            try {
                targetHigh = new JSONArray(DEFAULTARRAY);
            } catch (JSONException ignored) {
            }
        }
        edited = false;
        createAndStoreConvertedProfile();
    }
    private void createAndStoreConvertedProfile() {
        convertedProfile = createProfileStore();
    }

    public synchronized boolean isValidEditState() {
        return createProfileStore().getDefaultProfile().isValid(MainApp.gs(R.string.average_profile), false);
    }

    @NonNull
    public ProfileStore createProfileStore() {
        JSONObject json = new JSONObject();
        JSONObject store = new JSONObject();
        JSONObject profile = new JSONObject();

        try {
            json.put("defaultProfile", AVERAGE_PROFILE);
            json.put("store", store);
            profile.put("dia", dia);
            profile.put("carbratio", calculateAlignedValues(basal, ic));
            profile.put("sens", calculateAlignedValues(basal, isf));
            profile.put("basal", basal);
            profile.put("target_low", targetLow);
            profile.put("target_high", targetHigh);
            profile.put("units", mgdl ? Constants.MGDL : Constants.MMOL);
            store.put(AVERAGE_PROFILE, profile);
        } catch (JSONException e) {
            log.error("Unhandled exception", e);
        }
        return new ProfileStore(json);
    }

    private JSONArray calculateAlignedValues(JSONArray basalValues, double averageValue) throws JSONException {
        double basalAverage = calculateAverageValue(basalValues);
        JSONArray alignedValues = new JSONArray();
        for (int i = 0; i < basalValues.length(); i++) {
            JSONObject basalObject = basalValues.getJSONObject(i);
            String time = basalObject.getString("time");
            int timeAsSeconds = basalObject.getInt("timeAsSeconds");
            double basalValue = basalObject.getDouble("value");
            double alignedValue = averageValue / (basalValue / basalAverage);
            if (basalValue == 0) alignedValue = 0;
            JSONObject alignedObject = new JSONObject();
            alignedObject.put("time", time);
            alignedObject.put("timeAsSeconds", timeAsSeconds);
            alignedObject.put("value", alignedValue);
            alignedValues.put(alignedObject);
        }
        return alignedValues;
    }

    private double calculateAverageValue(JSONArray values) throws JSONException {
        double total = 0;
        for (int i = 0; i < values.length(); i++) {
            JSONObject block = values.getJSONObject(i);
            JSONObject nextBlock = values.length() > i + 1 ? values.getJSONObject(i + 1) : null;
            int time = block.getInt("timeAsSeconds");
            int timeAfter = nextBlock == null ? 24 * 60 * 60 : nextBlock.getInt("timeAsSeconds");
            double value = block.getDouble("value");
            total += value * (timeAfter - time);
        }
        return total / (24 * 60 * 60);
    }

    @Override
    public ProfileStore getProfile() {
        if (!convertedProfile.getDefaultProfile().isValid(MainApp.gs(R.string.average_profile)))
            return null;
        return convertedProfile;
    }

    public ProfileStore getRawProfile() {
        return convertedProfile;
    }

    @Override
    public String getUnits() {
        return mgdl ? Constants.MGDL : Constants.MMOL;
    }

    @Override
    public String getProfileName() {
        return DecimalFormatter.to2Decimal(convertedProfile.getDefaultProfile().percentageBasalSum()) + "U ";
    }
}
