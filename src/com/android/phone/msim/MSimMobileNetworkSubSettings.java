/*
 * Copyright (c) 2011-2013 The Linux Foundation. All rights reserved.
 * Not a Contribution.
 *
 * Copyright (C) 2006 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.phone;

import android.app.ActionBar;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Uri;
import android.os.AsyncResult;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.SystemProperties;
import android.preference.CheckBoxPreference;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.PreferenceActivity;
import android.preference.PreferenceCategory;
import android.preference.PreferenceScreen;
import android.provider.Settings.SettingNotFoundException;
import android.provider.Settings;
import android.telephony.MSimTelephonyManager;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.Log;
import android.view.MenuItem;

import com.android.internal.telephony.MSimConstants;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneConstants;
import com.android.internal.telephony.PhoneFactory;
import com.android.internal.telephony.TelephonyIntents;
import com.android.internal.telephony.TelephonyProperties;
import com.android.phone.MSimPhoneGlobals;
import com.android.phone.R;
import com.android.internal.telephony.RILConstants;

import static com.android.internal.telephony.MSimConstants.SUBSCRIPTION_KEY;

/**
 * "Mobile network settings" screen.  This preference screen lets you
 * enable/disable mobile data, and control data roaming and other
 * network-specific mobile data features.  It's used on non-voice-capable
 * tablets as well as regular phone devices.
 *
 * Note that this PreferenceActivity is part of the phone app, even though
 * you reach it from the "Wireless & Networks" section of the main
 * Settings app.  It's not part of the "Call settings" hierarchy that's
 * available from the Phone app (see CallFeaturesSetting for that.)
 */
// To support Dialog interface, enhanced the class definition.
public class MSimMobileNetworkSubSettings extends PreferenceActivity
        implements Preference.OnPreferenceChangeListener,
        DialogInterface.OnClickListener,
        DialogInterface.OnDismissListener{

    // debug data
    private static final String LOG_TAG = "MSimMobileNetworkSubSettings";
    private static final boolean DBG = true;
    public static final int REQUEST_CODE_EXIT_ECM = 17;

    //String keys for preference lookup
    private static final String BUTTON_DATA_ENABLED_KEY = "button_data_enabled_key";
    private static final String BUTTON_ROAMING_KEY = "button_roaming_key";
    private static final String BUTTON_PREFERED_NETWORK_MODE = "preferred_network_mode_key";
    private static final String BUTTON_MANAGE_SUB_KEY = "button_settings_manage_sub";
    private static final String BUTTON_CDMA_LTE_DATA_SERVICE_KEY = "cdma_lte_data_service_key";
    private static final String BUTTON_UPLMN_KEY = "button_uplmn_key";
    private static final String BUTTON_CARRIER_SETTINGS_KEY = "carrier_settings_key";

    // Used for restoring the preference if APSS tune away is enabled
    private static final String KEY_PREF_NETWORK_MODE = "pre_network_mode_sub";
    private static final String PREF_FILE = "pre-network-mode";

    static final int preferredNetworkMode = Phone.PREFERRED_NT_MODE;

    //Information about logical "up" Activity
    private static final String UP_ACTIVITY_PACKAGE = "com.android.settings";
    private static final String UP_ACTIVITY_CLASS =
            "com.android.settings.Settings$WirelessSettingsActivity";

    //UI objects
    private ListPreference mButtonPreferredNetworkMode;
    private CheckBoxPreference mButtonDataRoam;
    private CheckBoxPreference mButtonDataEnabled;
    private Preference mLteDataServicePref;

    private static final String iface = "rmnet0"; //TODO: this will go away

    private Phone mPhone;
    private MyHandler mHandler;
    private boolean mOkClicked;
    private int mSubscription;

    //GsmUmts options and Cdma options
    GsmUmtsOptions mGsmUmtsOptions;
    CdmaOptions mCdmaOptions;

    private Preference mClickedPreference;
    private static final String NETWORK_MODE_SEPARATOR = "-";

    /**
     * This is a method implemented for DialogInterface.OnClickListener.
     * Used to dismiss the dialogs when they come up.
     */
    public void onClick(DialogInterface dialog, int which) {
        if (which == DialogInterface.BUTTON_POSITIVE) {
            // Update the db and then toggle
            multiSimSetDataRoaming(true, mSubscription);
            mOkClicked = true;
        } else {
            // Reset the toggle
            mButtonDataRoam.setChecked(false);
        }
    }

    public void onDismiss(DialogInterface dialog) {
        // Assuming that onClick gets called first
        if (!mOkClicked) {
            mButtonDataRoam.setChecked(false);
        }
    }

    /**
     * Invoked on each preference click in this hierarchy, overrides
     * PreferenceActivity's implementation.  Used to make sure we track the
     * preference click events.
     */
    @Override
    public boolean onPreferenceTreeClick(PreferenceScreen preferenceScreen, Preference preference) {
        /** TODO: Refactor and get rid of the if's using subclasses */
        if (mGsmUmtsOptions != null &&
                mGsmUmtsOptions.preferenceTreeClick(preference) == true) {
            return true;
        } else if (preference == mButtonDataRoam) {
            // Handles the click events for Data Roaming menu item.
            if (DBG) log("onPreferenceTreeClick: preference = mButtonDataRoam");

            //normally called on the toggle click
            if (mButtonDataRoam.isChecked()) {
                // First confirm with a warning dialog about charges
                mOkClicked = false;
                new AlertDialog.Builder(this).setMessage(
                        getResources().getString(R.string.roaming_warning))
                        .setTitle(android.R.string.dialog_alert_title)
                        .setIconAttribute(android.R.attr.alertDialogIcon)
                        .setPositiveButton(android.R.string.yes, this)
                        .setNegativeButton(android.R.string.no, this)
                        .show()
                        .setOnDismissListener(this);
            } else {
                 multiSimSetDataRoaming(false, mSubscription);
            }
            return true;
        } else if (preference == mButtonDataEnabled) {
            // Handles the click events for Mobile Data menu item.
            if (DBG) log("onPreferenceTreeClick: preference == mButtonDataEnabled.");
            multiSimSetMobileData(mButtonDataEnabled.isChecked(), mSubscription);
            return true;
        } else if (mCdmaOptions != null &&
                   mCdmaOptions.preferenceTreeClick(preference) == true) {
            if (Boolean.parseBoolean(
                    SystemProperties.get(TelephonyProperties.PROPERTY_INECM_MODE))) {

                mClickedPreference = preference;

                // In ECM mode launch ECM app dialog
                startActivityForResult(
                    new Intent(TelephonyIntents.ACTION_SHOW_NOTICE_ECM_BLOCK_OTHERS, null),
                    REQUEST_CODE_EXIT_ECM);
            }
            return true;
        } else if (preference == mButtonPreferredNetworkMode) {
            //displays the value taken from the Settings.System
            int settingsNetworkMode = getPreferredNetworkMode();
            setPreferredNetworkModeValue(settingsNetworkMode);
            return true;
        } else if (preference == mLteDataServicePref) {
            String tmpl = android.provider.Settings.Global.getString(getContentResolver(),
                    android.provider.Settings.Global.SETUP_PREPAID_DATA_SERVICE_URL);
            if (!TextUtils.isEmpty(tmpl)) {
                MSimTelephonyManager tm = (MSimTelephonyManager) getSystemService(
                        Context.MSIM_TELEPHONY_SERVICE);
                String imsi = tm.getSubscriberId(mSubscription);
                if (imsi == null) {
                    imsi = "";
                }
                final String url = TextUtils.isEmpty(tmpl) ? null
                        : TextUtils.expandTemplate(tmpl, imsi).toString();
                Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                startActivity(intent);
            } else {
                android.util.Log.e(LOG_TAG, "Missing SETUP_PREPAID_DATA_SERVICE_URL");
            }
            return true;
        } else {
            // if the button is anything but the simple toggle preference,
            // we'll need to disable all preferences to reject all click
            // events until the sub-activity's UI comes up.
            preferenceScreen.setEnabled(false);
            // Let the intents be launched by the Preference manager
            return false;
        }
    }

    /**
     * Receiver for ACTION_AIRPLANE_MODE_CHANGED and ACTION_SIM_STATE_CHANGED.
     */
    private BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            setScreenState();
        }
    };

    @Override
    protected void onCreate(Bundle icicle) {
        super.onCreate(icicle);

        PhoneGlobals app = PhoneGlobals.getInstance();
        addPreferencesFromResource(R.xml.msim_network_sub_setting);

        mSubscription = getIntent().getIntExtra(SUBSCRIPTION_KEY, app.getDefaultSubscription());
        log("Settings onCreate subscription =" + mSubscription);
        mPhone = app.getPhone(mSubscription);
        mHandler = new MyHandler();

        //Register for intent broadcasts
        IntentFilter intentFilter = new IntentFilter(Intent.ACTION_AIRPLANE_MODE_CHANGED);
        intentFilter.addAction(TelephonyIntents.ACTION_SIM_STATE_CHANGED);

        registerReceiver(mReceiver, intentFilter);

        //get UI object references
        PreferenceScreen prefSet = getPreferenceScreen();

        PreferenceCategory pcSettingsLabel = new PreferenceCategory(this);
        pcSettingsLabel.setTitle(R.string.settings_label);
        prefSet.addPreference(pcSettingsLabel);

        mButtonDataEnabled = (CheckBoxPreference) prefSet.findPreference(BUTTON_DATA_ENABLED_KEY);
        //Move data enable button to setting root screen
        prefSet.removePreference(mButtonDataEnabled);

        mButtonPreferredNetworkMode = (ListPreference) prefSet.findPreference(
                BUTTON_PREFERED_NETWORK_MODE);

        int networkFeature = SystemProperties.getInt("persist.radio.network_feature",
                Constants.NETWORK_MODE_DEFAULT);
        switch (networkFeature) {
            case Constants.NETWORK_MODE_HIDE:
                prefSet.removePreference(mButtonPreferredNetworkMode);
                break;
            case Constants.NETWORK_MODE_CMCC:
                mButtonPreferredNetworkMode
                        .setDialogTitle(R.string.preferred_network_mode_dialogtitle_cmcc);
                mButtonPreferredNetworkMode.setEntries(R.array.preferred_network_mode_choices_cmcc);
                mButtonPreferredNetworkMode
                        .setEntryValues(R.array.preferred_network_mode_values_cmcc);
                break;
            case Constants.NETWORK_MODE_TDCDMA:
                mButtonPreferredNetworkMode
                        .setEntries(R.array.preferred_network_mode_choices_tdscdma);
                mButtonPreferredNetworkMode
                        .setEntryValues(R.array.preferred_network_mode_values_tdscdma);
                break;
            case Constants.NETWORK_MODE_LTE:
                mButtonPreferredNetworkMode.setEntries(R.array.preferred_network_mode_choices_lte);
                mButtonPreferredNetworkMode
                        .setEntryValues(R.array.preferred_network_mode_values_lte);
                break;
            case Constants.NETWORK_MODE_DEFAULT:
            default:
                break;
        }

        Preference mUPLMNPref = prefSet.findPreference(BUTTON_UPLMN_KEY);
        if (!getResources().getBoolean(R.bool.config_uplmn_for_cta_test)) {
            prefSet.removePreference(mUPLMNPref);
            mUPLMNPref = null;
        } else {
            mUPLMNPref.getIntent().putExtra(MSimConstants.SUBSCRIPTION_KEY, mSubscription);
        }

        boolean isLteOnCdma = mPhone.getLteOnCdmaMode() == PhoneConstants.LTE_ON_CDMA_TRUE;
        if (getResources().getBoolean(R.bool.world_phone) == true) {
            // set the listener for the mButtonPreferredNetworkMode list preference so we can issue
            // change Preferred Network Mode.
            mButtonPreferredNetworkMode.setOnPreferenceChangeListener(this);

            //Get the networkMode from Settings.System and displays it
            int settingsNetworkMode = getPreferredNetworkMode();
            setPreferredNetworkModeValue(settingsNetworkMode);
            mCdmaOptions = new CdmaOptions(this, prefSet, mPhone, mSubscription);
            mGsmUmtsOptions = new GsmUmtsOptions(this, prefSet, mSubscription);
        } else {
            if (!isLteOnCdma) {
                prefSet.removePreference(mButtonPreferredNetworkMode);
            } else {
                mButtonPreferredNetworkMode.setOnPreferenceChangeListener(this);

                int settingsNetworkMode = getPreferredNetworkMode();
                setPreferredNetworkModeValue(settingsNetworkMode);
            }
            int phoneType = mPhone.getPhoneType();
            if (phoneType == PhoneConstants.PHONE_TYPE_CDMA) {
                mCdmaOptions = new CdmaOptions(this, prefSet, mPhone, mSubscription);
            } else if (phoneType == PhoneConstants.PHONE_TYPE_GSM) {
                mGsmUmtsOptions = new GsmUmtsOptions(this, prefSet, mSubscription);
            } else {
                throw new IllegalStateException("Unexpected phone type: " + phoneType);
            }
        }

        // Read platform settings for carrier settings
        final boolean isCarrierSettingsEnabled = getResources().getBoolean(
                R.bool.config_carrier_settings_enable);
        if (!isCarrierSettingsEnabled) {
            Preference pref = prefSet.findPreference(BUTTON_CARRIER_SETTINGS_KEY);
            if (pref != null) {
                prefSet.removePreference(pref);
                // Some times carrier settings added multiple times(ex: for world mode)
                // so, remove carrier settings if there a second one exists.
                pref = prefSet.findPreference(BUTTON_CARRIER_SETTINGS_KEY);
                if (pref != null) {
                    prefSet.removePreference(pref);
                }
            }
        }

        ActionBar actionBar = getActionBar();
        if (actionBar != null) {
            // android.R.id.home will be triggered in onOptionsItemSelected()
            actionBar.setDisplayHomeAsUpEnabled(true);
        }
        //add preference category
        PreferenceCategory pcDataSettings = new PreferenceCategory(this);
        pcDataSettings.setTitle(R.string.title_data_settings);
        prefSet.addPreference(pcDataSettings);

        this.addPreferencesFromResource(R.xml.msim_network_setting);
        //remove manage sub button
        prefSet.removePreference(prefSet.findPreference(BUTTON_MANAGE_SUB_KEY));

        mButtonDataRoam = (CheckBoxPreference) prefSet.findPreference(BUTTON_ROAMING_KEY);
        mLteDataServicePref = prefSet.findPreference(BUTTON_CDMA_LTE_DATA_SERVICE_KEY);

        final boolean missingDataServiceUrl = TextUtils.isEmpty(
                android.provider.Settings.Global.getString(getContentResolver(),
                android.provider.Settings.Global.SETUP_PREPAID_DATA_SERVICE_URL));
        if (!isLteOnCdma || missingDataServiceUrl) {
            prefSet.removePreference(mLteDataServicePref);
        } else {
            android.util.Log.d(LOG_TAG, "keep ltePref");
        }

        if (this.getResources().getBoolean(R.bool.hide_roaming)) {
            if (!isLteOnCdma || missingDataServiceUrl) {
                //none left so remove this category
                prefSet.removePreference(pcDataSettings);
                prefSet.removePreference(mButtonDataRoam);
            } else {
                prefSet.removePreference(mButtonDataRoam);
            }
        }
    }

    private int getAcqValue() {
        int acq = 0;
        try {
            acq = MSimTelephonyManager.getIntAtIndex(getContentResolver(), Constants.SETTINGS_ACQ,
                    mSubscription);
        } catch (SettingNotFoundException e) {
            Log.d(LOG_TAG, "failed to get acq", e);
        }
        return acq;
    }

    private void setPreferredNetworkModeValue(int settingsNetworkMode) {
        int networkFeature = SystemProperties.getInt("persist.radio.network_feature",
                Constants.NETWORK_MODE_DEFAULT);
        //only these cases need set acq
        if ((networkFeature == Constants.NETWORK_MODE_CMCC
                || networkFeature == Constants.NETWORK_MODE_LTE)
                && (settingsNetworkMode == RILConstants.NETWORK_MODE_TD_SCDMA_GSM_WCDMA_LTE)) {
            // default is 4G preferred mode
            int acq = getAcqValue();
            String acqString = (0 == acq) ? "1" : Integer.toString(acq);

            String networkmodeString = Integer.toString(settingsNetworkMode)
                    + NETWORK_MODE_SEPARATOR + acqString;
            Log.d(LOG_TAG, networkmodeString);
            mButtonPreferredNetworkMode.setValue(networkmodeString);
        } else {
            mButtonPreferredNetworkMode.setValue(Integer.toString(settingsNetworkMode));
        }
    }

    @Override
    protected void onResume() {
        super.onResume();

        setScreenState();

        // Set UI state in onResume because a user could go home, launch some
        // app to change this setting's backend, and re-launch this settings app
        // and the UI state would be inconsistent with actual state
        mButtonDataEnabled.setChecked(multiSimGetMobileData(mSubscription));
        mButtonDataRoam.setChecked(multiSimGetDataRoaming(mSubscription));

        if (getPreferenceScreen().findPreference(BUTTON_PREFERED_NETWORK_MODE) != null)  {
            mPhone.getPreferredNetworkType(mHandler.obtainMessage(
                    MyHandler.MESSAGE_GET_PREFERRED_NETWORK_TYPE));
        }
        if (mGsmUmtsOptions != null) mGsmUmtsOptions.enableScreen();
    }

    private void setScreenState() {
        int simState = MSimTelephonyManager.getDefault().getSimState(mSubscription);
        getPreferenceScreen().setEnabled(simState == TelephonyManager.SIM_STATE_READY);
    }

    @Override
    protected void onPause() {
        super.onPause();
    }

    /**
     * Implemented to support onPreferenceChangeListener to look for preference
     * changes specifically on CLIR.
     *
     * @param preference is the preference to be changed, should be mButtonCLIR.
     * @param objValue should be the value of the selection, NOT its localized
     * display value.
     */
    public boolean onPreferenceChange(Preference preference, Object objValue) {
        if (preference == mButtonPreferredNetworkMode) {
            String strMode = (String) objValue;
            String strAcq =  "0";
            boolean isContainAcq = strMode.contains(NETWORK_MODE_SEPARATOR);
            if (isContainAcq) {
                String[] values = strMode.split(NETWORK_MODE_SEPARATOR);
                strMode = values[0];
                strAcq = values[1];
            }
            //NOTE onPreferenceChange seems to be called even if there is no change
            //Check if the button value is changed from the System.Setting
            mButtonPreferredNetworkMode.setValue((String) objValue);
            int buttonNetworkMode;
            buttonNetworkMode = Integer.valueOf((String) strMode).intValue();
            int settingsNetworkMode = getPreferredNetworkMode();

            int buttonAcq = Integer.valueOf((String) strAcq).intValue();
            int settingsAcq = getAcqValue();
            if (buttonNetworkMode != settingsNetworkMode || buttonAcq != settingsAcq) {
                int modemNetworkMode = buttonNetworkMode;
                // if new mode is invalid set mode to default preferred
                if ((modemNetworkMode < Phone.NT_MODE_WCDMA_PREF)
                        || (modemNetworkMode > Phone.NT_MODE_TD_SCDMA_LTE_CDMA_EVDO_GSM_WCDMA)) {
                    log("Invalid Network Mode (" + modemNetworkMode + ") Chosen. Ignore mode");
                    return true;
                }

                UpdatePreferredNetworkModeSummary(buttonNetworkMode, buttonAcq);
                setPreferredNetworkMode(buttonNetworkMode);
                // Set the modem network mode
                // mPhone.setPreferredNetworkType(modemNetworkMode, mHandler
                // .obtainMessage(MyHandler.MESSAGE_SET_PREFERRED_NETWORK_TYPE));
                setPreferredNetworkType(isContainAcq, modemNetworkMode, strAcq);
            }
        }

        // always let the preference setting proceed.
        return true;
    }

    // now use phone feature service to set network mode
    private void setPreferredNetworkType(boolean containAcq, int networkMode, String strAcq) {
     // now use phone feature service to set network mode
        final int modemNetworkMode = networkMode;
        if (containAcq) {
            final int acq = Integer.valueOf(strAcq).intValue();
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    boolean success = PhoneGlobals.getInstance().mQcrilHook != null
                            && PhoneGlobals.getInstance().setPrefNetworkAcq(acq, mSubscription);
                    Log.d(LOG_TAG, "restore acq, success: " + success);
                    if (success) {
                        if (MSimPhoneGlobals.getInstance().mPhoneServiceClient != null) {
                            MSimPhoneGlobals.getInstance().setPrefNetwork(mSubscription,
                                    modemNetworkMode, mHandler.obtainMessage(
                                            MyHandler.MESSAGE_SET_PREFERRED_NETWORK_TYPE));
                        } else {
                            //Set the modem network mode
                            mPhone.setPreferredNetworkType(modemNetworkMode, mHandler
                                    .obtainMessage(MyHandler.MESSAGE_SET_PREFERRED_NETWORK_TYPE));
                        }
                    } else {
                        // recovery the status of before
                        mPhone.getPreferredNetworkType(mHandler
                                .obtainMessage(MyHandler.MESSAGE_GET_PREFERRED_NETWORK_TYPE));
                    }
                }
            });
        } else {
            if (MSimPhoneGlobals.getInstance().mPhoneServiceClient != null) {
                MSimPhoneGlobals.getInstance().setPrefNetwork(mSubscription, modemNetworkMode,
                        mHandler.obtainMessage(MyHandler.MESSAGE_SET_PREFERRED_NETWORK_TYPE));
            } else {
                //Set the modem network mode
                mPhone.setPreferredNetworkType(modemNetworkMode, mHandler
                        .obtainMessage(MyHandler.MESSAGE_SET_PREFERRED_NETWORK_TYPE));
            }
        }
    }

    private int getPreferredNetworkMode() {
        int nwMode;
        try {
            nwMode = android.telephony.MSimTelephonyManager.getIntAtIndex(
                    mPhone.getContext().getContentResolver(),
                    android.provider.Settings.Global.PREFERRED_NETWORK_MODE,
                    mSubscription);
        } catch (SettingNotFoundException snfe) {
            log("getPreferredNetworkMode: Could not find PREFERRED_NETWORK_MODE!!!");
            nwMode = preferredNetworkMode;
        }
        return nwMode;
    }

    private void setPreferredNetworkMode(int nwMode) {
        android.telephony.MSimTelephonyManager.putIntAtIndex(
                    mPhone.getContext().getContentResolver(),
                    android.provider.Settings.Global.PREFERRED_NETWORK_MODE,
                    mSubscription, nwMode);
    }

    private class MyHandler extends Handler {

        static final int MESSAGE_GET_PREFERRED_NETWORK_TYPE = 0;
        static final int MESSAGE_SET_PREFERRED_NETWORK_TYPE = 1;

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MESSAGE_GET_PREFERRED_NETWORK_TYPE:
                    handleGetPreferredNetworkTypeResponse(msg);
                    break;

                case MESSAGE_SET_PREFERRED_NETWORK_TYPE:
                    handleSetPreferredNetworkTypeResponse(msg);
                    break;
            }
        }

        private void handleGetPreferredNetworkTypeResponse(Message msg) {
            AsyncResult ar = (AsyncResult) msg.obj;

            if (ar.exception == null) {
                int modemNetworkMode = ((int[])ar.result)[0];

                if (DBG) {
                    log ("handleGetPreferredNetworkTypeResponse: modemNetworkMode = " +
                            modemNetworkMode);
                }

                int settingsNetworkMode = getPreferredNetworkMode();
                if (DBG) {
                    log("handleGetPreferredNetworkTypeReponse: settingsNetworkMode = " +
                            settingsNetworkMode);
                }

                //check that modemNetworkMode is from an accepted value
                if ((modemNetworkMode >= Phone.NT_MODE_WCDMA_PREF) &&
                        (modemNetworkMode <= Phone.NT_MODE_TD_SCDMA_LTE_CDMA_EVDO_GSM_WCDMA)) {
                    if (DBG) {
                        log("handleGetPreferredNetworkTypeResponse: if 1: modemNetworkMode = " +
                                modemNetworkMode);
                    }

                    //check changes in modemNetworkMode and updates settingsNetworkMode
                    if (modemNetworkMode != settingsNetworkMode) {
                        if (DBG) {
                            log("handleGetPreferredNetworkTypeResponse: if 2: " +
                                    "modemNetworkMode != settingsNetworkMode");
                        }

                        settingsNetworkMode = modemNetworkMode;

                        if (DBG) { log("handleGetPreferredNetworkTypeResponse: if 2: " +
                                "settingsNetworkMode = " + settingsNetworkMode);
                        }

                        //changes the Settings.System accordingly to modemNetworkMode
                        setPreferredNetworkMode(settingsNetworkMode);
                    }
                    int acq = getAcqValue();
                    UpdatePreferredNetworkModeSummary(modemNetworkMode, acq);
                    // changes the mButtonPreferredNetworkMode accordingly to modemNetworkMode
                    setPreferredNetworkModeValue(modemNetworkMode);
                } else {
                    if (DBG) log("handleGetPreferredNetworkTypeResponse: else: reset to default");
                    resetNetworkModeToDefault();
                }
            }
        }

        private void handleSetPreferredNetworkTypeResponse(Message msg) {
            AsyncResult ar = (AsyncResult) msg.obj;

            if (ar == null || ar.exception == null) {
                String strValue = mButtonPreferredNetworkMode.getValue();
                String strAcq = null;
                boolean isContainAcq = strValue.contains(NETWORK_MODE_SEPARATOR);
                if (isContainAcq) {
                    String[] values = strValue.split(NETWORK_MODE_SEPARATOR);
                    strValue = values[0];
                    strAcq = values[1];
                }

                setPreferredNetworkMode(Integer.valueOf(strValue).intValue());
                setPrefNetworkTypeInSp(Integer.valueOf(strValue).intValue());
                //only these cases need set acq
                if (isContainAcq) {
                    MSimTelephonyManager.putIntAtIndex(getContentResolver(),
                            Constants.SETTINGS_ACQ, mSubscription, Integer.valueOf(strAcq)
                                    .intValue());
                }
            } else {
                mPhone.getPreferredNetworkType(obtainMessage(MESSAGE_GET_PREFERRED_NETWORK_TYPE));
            }
        }

        private void resetNetworkModeToDefault() {
            //set the mButtonPreferredNetworkMode
            setPreferredNetworkModeValue(preferredNetworkMode);
            //set the Settings.System
            setPreferredNetworkMode(preferredNetworkMode);
            //Set the Modem
            mPhone.setPreferredNetworkType(preferredNetworkMode,
                    this.obtainMessage(MyHandler.MESSAGE_SET_PREFERRED_NETWORK_TYPE));
        }
    }

    private void UpdatePreferredNetworkModeSummary(int NetworkMode, int acq) {
        int networkFeature = SystemProperties.getInt("persist.radio.network_feature",
                Constants.NETWORK_MODE_DEFAULT);
        switch(NetworkMode) {
            case Phone.NT_MODE_WCDMA_PREF:
                mButtonPreferredNetworkMode.setSummary(
                        R.string.preferred_network_mode_wcdma_perf_summary);
                break;
            case Phone.NT_MODE_GSM_ONLY:
                mButtonPreferredNetworkMode.setSummary(
                        R.string.preferred_network_mode_gsm_only_summary);
                if ((networkFeature == Constants.NETWORK_MODE_CMCC
                        || networkFeature == Constants.NETWORK_MODE_LTE)
                        && (PhoneGlobals.getInstance().mPhoneServiceClient == null || PhoneGlobals
                                .getInstance().getPreferredLTESub() != mSubscription)) {
                    mButtonPreferredNetworkMode.setEnabled(false);
                }
                break;
            case Phone.NT_MODE_WCDMA_ONLY:
                mButtonPreferredNetworkMode.setSummary(
                        R.string.preferred_network_mode_wcdma_only_summary);
                break;
            case Phone.NT_MODE_GSM_UMTS:
                mButtonPreferredNetworkMode.setSummary(
                        R.string.preferred_network_mode_gsm_wcdma_summary);
                break;
            case Phone.NT_MODE_CDMA:
                switch (mPhone.getLteOnCdmaMode()) {
                    case PhoneConstants.LTE_ON_CDMA_TRUE:
                        mButtonPreferredNetworkMode.setSummary(
                            R.string.preferred_network_mode_cdma_summary);
                    break;
                    case PhoneConstants.LTE_ON_CDMA_FALSE:
                    default:
                        mButtonPreferredNetworkMode.setSummary(
                            R.string.preferred_network_mode_cdma_evdo_summary);
                        break;
                }
                break;
            case Phone.NT_MODE_CDMA_NO_EVDO:
                mButtonPreferredNetworkMode.setSummary(
                        R.string.preferred_network_mode_cdma_only_summary);
                break;
            case Phone.NT_MODE_EVDO_NO_CDMA:
                mButtonPreferredNetworkMode.setSummary(
                        R.string.preferred_network_mode_evdo_only_summary);
                break;
            case Phone.NT_MODE_LTE_ONLY:
                mButtonPreferredNetworkMode.setSummary(
                        R.string.preferred_network_mode_lte_summary);
                break;
            case Phone.NT_MODE_LTE_GSM_WCDMA:
                mButtonPreferredNetworkMode.setSummary(
                        R.string.preferred_network_mode_lte_gsm_wcdma_summary);
                break;
            case Phone.NT_MODE_LTE_CDMA_AND_EVDO:
                mButtonPreferredNetworkMode.setSummary(
                        R.string.preferred_network_mode_lte_cdma_evdo_summary);
                break;
            case Phone.NT_MODE_LTE_CMDA_EVDO_GSM_WCDMA:
                mButtonPreferredNetworkMode.setSummary(
                        R.string.preferred_network_mode_global_summary);
                break;
            case Phone.NT_MODE_GLOBAL:
                mButtonPreferredNetworkMode.setSummary(
                        R.string.preferred_network_mode_cdma_evdo_gsm_wcdma_summary);
                break;
            case Phone.NT_MODE_LTE_WCDMA:
                mButtonPreferredNetworkMode.setSummary(
                        R.string.preferred_network_mode_lte_wcdma_summary);
                break;
            case Phone.NT_MODE_TD_SCDMA_ONLY:
                mButtonPreferredNetworkMode.setSummary(
                        R.string.preferred_network_mode_td_scdma_only_summary);
                break;
            case Phone.NT_MODE_TD_SCDMA_WCDMA:
                mButtonPreferredNetworkMode.setSummary(
                        R.string.preferred_network_mode_td_scdma_wcdma_summary);
                break;
            case Phone.NT_MODE_TD_SCDMA_LTE:
                mButtonPreferredNetworkMode.setSummary(
                        R.string.preferred_network_mode_td_scdma_lte_summary);
                break;
            case Phone.NT_MODE_TD_SCDMA_GSM:
                mButtonPreferredNetworkMode.setSummary(
                        R.string.preferred_network_mode_td_scdma_gsm_summary);
                break;
            case Phone.NT_MODE_TD_SCDMA_GSM_LTE:
                mButtonPreferredNetworkMode.setSummary(
                        R.string.preferred_network_mode_td_scdma_gsm_lte_summary);
                break;
            case Phone.NT_MODE_TD_SCDMA_GSM_WCDMA:
                if (networkFeature == Constants.NETWORK_MODE_CMCC) {
                    mButtonPreferredNetworkMode.setSummary(
                            R.string.preferred_network_mode_3g_2g_auto_summary);
                } else {
                    mButtonPreferredNetworkMode.setSummary(
                            R.string.preferred_network_mode_td_scdma_gsm_wcdma_summary);
                }
                break;
            case Phone.NT_MODE_TD_SCDMA_WCDMA_LTE:
                mButtonPreferredNetworkMode.setSummary(
                        R.string.preferred_network_mode_td_scdma_wcdma_lte_summary);
                break;
            case Phone.NT_MODE_TD_SCDMA_GSM_WCDMA_LTE:
                if (networkFeature == Constants.NETWORK_MODE_CMCC) {
                    if (acq == 1) {
                        mButtonPreferredNetworkMode
                                .setSummary(R.string.preferred_network_mode_4g_3g_2g_4g);
                    } else if (acq == 2) {
                        mButtonPreferredNetworkMode
                                .setSummary(R.string.preferred_network_mode_4g_3g_2g_3g);
                    } else {
                        mButtonPreferredNetworkMode
                                .setSummary(R.string.preferred_network_mode_4g_3g_2g_auto_summary);
                    }
                } else if (networkFeature == Constants.NETWORK_MODE_LTE){
                    if (acq == 1) {
                        mButtonPreferredNetworkMode
                                .setSummary(R.string.preferred_network_mode_4g_3g_2g_lte);
                    } else if (acq == 2) {
                        mButtonPreferredNetworkMode
                                .setSummary(R.string.preferred_network_mode_4g_3g_2g_td);
                    } else {
                        mButtonPreferredNetworkMode.setSummary(
                                R.string.preferred_network_mode_td_scdma_gsm_wcdma_lte_summary);
                    }
                } else {
                    mButtonPreferredNetworkMode.setSummary(
                            R.string.preferred_network_mode_td_scdma_gsm_wcdma_lte_summary);
                }
                break;
            case Phone.NT_MODE_TD_SCDMA_CDMA_EVDO_GSM_WCDMA:
                mButtonPreferredNetworkMode.setSummary(
                        R.string.preferred_network_mode_td_scdma_cdma_evdo_gsm_wcdma_summary);
                break;
            case Phone.NT_MODE_TD_SCDMA_LTE_CDMA_EVDO_GSM_WCDMA:
                mButtonPreferredNetworkMode.setSummary(
                        R.string.preferred_network_mode_td_scdma_lte_cdma_evdo_gsm_wcdma_summary);
                break;
            default:
                mButtonPreferredNetworkMode.setSummary(
                        R.string.preferred_network_mode_global_summary);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        switch(requestCode) {
        case REQUEST_CODE_EXIT_ECM:
            Boolean isChoiceYes =
                data.getBooleanExtra(EmergencyCallbackModeExitDialog.EXTRA_EXIT_ECM_RESULT, false);
            if (isChoiceYes) {
                // If the phone exits from ECM mode, show the CDMA Options
                mCdmaOptions.showDialog(mClickedPreference);
            } else {
                // do nothing
            }
            break;

        default:
            break;
        }
    }

    private static void log(String msg) {
        Log.d(LOG_TAG, msg);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        final int itemId = item.getItemId();
        if (itemId == android.R.id.home) {  // See ActionBar#setDisplayHomeAsUpEnabled()
            // Commenting out "logical up" capability. This is a workaround for issue 5278083.
            //
            // Settings app may not launch this activity via UP_ACTIVITY_CLASS but the other
            // Activity that looks exactly same as UP_ACTIVITY_CLASS ("SubSettings" Activity).
            // At that moment, this Activity launches UP_ACTIVITY_CLASS on top of the Activity.
            // which confuses users.
            // TODO: introduce better mechanism for "up" capability here.
            /*Intent intent = new Intent(Intent.ACTION_MAIN);
            intent.setClassName(UP_ACTIVITY_PACKAGE, UP_ACTIVITY_CLASS);
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(intent);*/
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    // Get Data roaming flag, from DB, as per SUB.
    private boolean multiSimGetDataRoaming(int sub) {
        boolean enabled;

        enabled = android.provider.Settings.Global.getInt(mPhone.getContext().getContentResolver(),
                android.provider.Settings.Global.DATA_ROAMING + sub, 0) != 0;
        log("Get Data Roaming for SUB-" + sub + " is " + enabled);
        return enabled;
    }

    // Set Data roaming flag, in DB, as per SUB.
    private void multiSimSetDataRoaming(boolean enabled, int sub) {
        // as per SUB, set the individual flag
        android.provider.Settings.Global.putInt(mPhone.getContext().getContentResolver(),
                android.provider.Settings.Global.DATA_ROAMING + sub, enabled ? 1 : 0);
        log("Set Data Roaming for SUB-" + sub + " is " + enabled);

        // If current DDS is this SUB, update the Global flag also
        if (sub == android.telephony.MSimTelephonyManager.
                getDefault().getPreferredDataSubscription()) {
            mPhone.setDataRoamingEnabled(enabled);
            log("Set Data Roaming for DDS-" + sub + " is " + enabled);
        }
    }

    // Get Mobile Data flag, from DB, as per SUB.
    private boolean multiSimGetMobileData(int sub) {
        boolean enabled;

        enabled = android.provider.Settings.Global.getInt(mPhone.getContext().getContentResolver(),
                android.provider.Settings.Global.MOBILE_DATA + sub, 0) != 0;
        log("Get Mobile Data for SUB-" + sub + " is " + enabled);
        return enabled;
    }

    // Set Mobile Data option, in DB, as per SUB.
    private void multiSimSetMobileData(boolean enabled, int sub) {
        // as per SUB, set the individual flag
        android.provider.Settings.Global.putInt(mPhone.getContext().getContentResolver(),
                android.provider.Settings.Global.MOBILE_DATA + sub, enabled ? 1 : 0);
        log("Set Mobile Data for SUB-" + sub + " is " + enabled);

        // If current DDS is this SUB, update the Global flag also
        if (sub == android.telephony.MSimTelephonyManager.
                getDefault().getPreferredDataSubscription()) {
            ConnectivityManager cm =
                    (ConnectivityManager)getSystemService(Context.CONNECTIVITY_SERVICE);
            cm.setMobileDataEnabled(enabled);
            log("Set Mobile Data for DDS-" + sub + " is " + enabled);
        }
    }

    private void setPrefNetworkTypeInSp(int preNetworkType) {
        SharedPreferences sp = mPhone.getContext().getSharedPreferences(PREF_FILE,
                Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = sp.edit();
        editor.putInt(KEY_PREF_NETWORK_MODE + mSubscription, preNetworkType);
        editor.apply();
        log("updating network type : " + preNetworkType + " for Subscription: " + mSubscription +
            " in shared preference" + " context is : " + mPhone.getContext());
    }
}
