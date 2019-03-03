package info.nightscout.androidaps.plugins.profile.average;

import android.app.Activity;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.RadioButton;
import android.widget.TextView;
import com.squareup.otto.Subscribe;
import info.nightscout.androidaps.MainApp;
import info.nightscout.androidaps.R;
import info.nightscout.androidaps.data.ProfileStore;
import info.nightscout.androidaps.events.EventInitializationChanged;
import info.nightscout.androidaps.interfaces.PumpDescription;
import info.nightscout.androidaps.logging.L;
import info.nightscout.androidaps.plugins.common.SubscriberFragment;
import info.nightscout.androidaps.plugins.general.careportal.CareportalFragment;
import info.nightscout.androidaps.plugins.general.careportal.Dialogs.NewNSTreatmentDialog;
import info.nightscout.androidaps.plugins.general.careportal.OptionsToShow;
import info.nightscout.androidaps.utils.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.text.DecimalFormat;

public class AverageProfileFragment extends SubscriberFragment {
    private static Logger log = LoggerFactory.getLogger(L.PROFILE);

    NumberPicker diaView;
    RadioButton mgdlView;
    RadioButton mmolView;
    NumberPicker icView;
    NumberPicker isfView;
    TimeListEdit basalView;
    TimeListEdit targetView;
    Button profileswitchButton;
    Button resetButton;
    Button saveButton;

    TextView invalidProfile;

    Runnable save = () -> {
        doEdit();
        if (basalView != null) {
            basalView.updateLabel(MainApp.gs(R.string.nsprofileview_basal_label) + ": " + getSumLabel());
        }
    };

    TextWatcher textWatch = new TextWatcher() {

        @Override
        public void afterTextChanged(Editable s) {
        }

        @Override
        public void beforeTextChanged(CharSequence s, int start, int count, int after) {
        }

        @Override
        public void onTextChanged(CharSequence s, int start, int before, int count) {
            AverageProfilePlugin.getPlugin().dia = SafeParse.stringToDouble(diaView.getText().toString());
            AverageProfilePlugin.getPlugin().ic = SafeParse.stringToDouble(icView.getText().toString());
            AverageProfilePlugin.getPlugin().isf = SafeParse.stringToDouble(isfView.getText().toString());
            doEdit();
        }
    };

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        try {
            PumpDescription pumpDescription = info.nightscout.androidaps.plugins.configBuilder.ConfigBuilderPlugin.getPlugin().getActivePump().getPumpDescription();
            View layout = inflater.inflate(R.layout.average_profile_fragment, container, false);
            diaView = layout.findViewById(R.id.averageprofile_dia);
            diaView.setParams(AverageProfilePlugin.getPlugin().dia, 2d, 48d, 0.1d, new DecimalFormat("0.0"), false, textWatch);
            mgdlView = layout.findViewById(R.id.averageprofile_mgdl);
            mmolView = layout.findViewById(R.id.averageprofile_mmol);
            icView = layout.findViewById(R.id.averageprofile_ic);
            icView.setParams(AverageProfilePlugin.getPlugin().ic, 0.5d, 50d, 0.1d, new DecimalFormat("0.0"), false, textWatch);
            isfView = layout.findViewById(R.id.averageprofile_isf);
            isfView.setParams(AverageProfilePlugin.getPlugin().isf, 0.5d, 500d, 0.1d, new DecimalFormat("0.0"), false, textWatch);
            basalView = new TimeListEdit(getContext(), layout, R.id.averageprofile_basal, MainApp.gs(R.string.nsprofileview_basal_label) + ": " + getSumLabel(), AverageProfilePlugin.getPlugin().basal, null, pumpDescription.basalMinimumRate, 10, 0.01d, new DecimalFormat("0.00"), save);
            targetView = new TimeListEdit(getContext(), layout, R.id.averageprofile_target, MainApp.gs(R.string.nsprofileview_target_label) + ":", AverageProfilePlugin.getPlugin().targetLow, AverageProfilePlugin.getPlugin().targetHigh, 3d, 200, 0.1d, new DecimalFormat("0.0"), save);
            profileswitchButton = layout.findViewById(R.id.averageprofile_profileswitch);
            resetButton = layout.findViewById(R.id.averageprofile_reset);
            saveButton = layout.findViewById(R.id.averageprofile_save);


            invalidProfile = (TextView) layout.findViewById(R.id.invalidprofile);

            if (!info.nightscout.androidaps.plugins.configBuilder.ConfigBuilderPlugin.getPlugin().getActivePump().getPumpDescription().isTempBasalCapable) {
                layout.findViewById(R.id.averageprofile_basal).setVisibility(View.GONE);
            }

            mgdlView.setChecked(AverageProfilePlugin.getPlugin().mgdl);
            mmolView.setChecked(AverageProfilePlugin.getPlugin().mmol);

            mgdlView.setOnClickListener(v -> {
                AverageProfilePlugin.getPlugin().mgdl = mgdlView.isChecked();
                AverageProfilePlugin.getPlugin().mmol = !AverageProfilePlugin.getPlugin().mgdl;
                mmolView.setChecked(AverageProfilePlugin.getPlugin().mmol);
                doEdit();
            });
            mmolView.setOnClickListener(v -> {
                AverageProfilePlugin.getPlugin().mmol = mmolView.isChecked();
                AverageProfilePlugin.getPlugin().mgdl = !AverageProfilePlugin.getPlugin().mmol;
                mgdlView.setChecked(AverageProfilePlugin.getPlugin().mgdl);
                doEdit();
            });

            profileswitchButton.setOnClickListener(view -> {
                NewNSTreatmentDialog newDialog = new NewNSTreatmentDialog();
                final OptionsToShow profileswitch = CareportalFragment.PROFILESWITCHDIRECT;
                profileswitch.executeProfileSwitch = true;
                newDialog.setOptions(profileswitch, R.string.careportal_profileswitch);
                newDialog.show(getFragmentManager(), "NewNSTreatmentDialog");
            });

            resetButton.setOnClickListener(view -> {
                AverageProfilePlugin.getPlugin().loadSettings();
                mgdlView.setChecked(AverageProfilePlugin.getPlugin().mgdl);
                mmolView.setChecked(AverageProfilePlugin.getPlugin().mmol);
                diaView.setParams(AverageProfilePlugin.getPlugin().dia, 2d, 48d, 0.1d, new DecimalFormat("0.0"), false, textWatch);
                icView.setParams(AverageProfilePlugin.getPlugin().ic, 0.5d, 50d, 0.1d, new DecimalFormat("0.0"), false, textWatch);
                isfView.setParams(AverageProfilePlugin.getPlugin().isf, 0.5d, 500d, 0.1d, new DecimalFormat("0.0"), false, textWatch);
                basalView = new TimeListEdit(getContext(), layout, R.id.averageprofile_basal, MainApp.gs(R.string.nsprofileview_basal_label) + ": " + getSumLabel(), AverageProfilePlugin.getPlugin().basal, null, pumpDescription.basalMinimumRate, 10, 0.01d, new DecimalFormat("0.00"), save);
                targetView = new TimeListEdit(getContext(), layout, R.id.averageprofile_target, MainApp.gs(R.string.nsprofileview_target_label) + ":", AverageProfilePlugin.getPlugin().targetLow, AverageProfilePlugin.getPlugin().targetHigh, 3d, 200, 0.1d, new DecimalFormat("0.0"), save);
                updateGUI();
            });

            saveButton.setOnClickListener(view -> {
                if (!AverageProfilePlugin.getPlugin().isValidEditState()) {
                    return; //Should not happen as saveButton should not be visible if not valid
                }
                AverageProfilePlugin.getPlugin().storeSettings();
                updateGUI();
            });

            return layout;
        } catch (Exception e) {
            log.error("Unhandled exception: ", e);
            FabricPrivacy.logException(e);
        }

        return null;
    }

    public void doEdit() {
        AverageProfilePlugin.getPlugin().setEdited(true);
        updateGUI();
    }

    @NonNull
    public String getSumLabel() {
        ProfileStore profile = AverageProfilePlugin.getPlugin().createProfileStore();
        if (profile != null)
            return " ∑" + DecimalFormatter.to2Decimal(profile.getDefaultProfile().baseBasalSum()) + "U";
        else
            return MainApp.gs(R.string.average_profile);
    }

    @Subscribe
    public void onStatusEvent(final EventInitializationChanged e) {
        updateGUI();
    }

    @Override
    protected void updateGUI() {
        Activity activity = getActivity();
        if (activity != null)
            activity.runOnUiThread(() -> {
                boolean isValid = AverageProfilePlugin.getPlugin().isValidEditState();
                boolean isEdited = AverageProfilePlugin.getPlugin().isEdited();
                if (isValid) {
                    invalidProfile.setVisibility(View.GONE); //show invalid profile

                    if (isEdited) {
                        //edited profile -> save first
                        profileswitchButton.setVisibility(View.GONE);
                        saveButton.setVisibility(View.VISIBLE);
                    } else {
                        profileswitchButton.setVisibility(View.VISIBLE);
                        saveButton.setVisibility(View.GONE);
                    }
                } else {
                    invalidProfile.setVisibility(View.VISIBLE);
                    profileswitchButton.setVisibility(View.GONE);
                    saveButton.setVisibility(View.GONE); //don't save an invalid profile
                }

                //Show reset button iff data was edited
                if (isEdited) {
                    resetButton.setVisibility(View.VISIBLE);
                } else {
                    resetButton.setVisibility(View.GONE);
                }
            });
    }
}
