/*
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

import com.android.ims.ImsManager;
import com.android.ims.ImsException;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneConstants;
import com.android.internal.telephony.TelephonyIntents;
import com.android.internal.telephony.TelephonyProperties;
import com.android.internal.telephony.uicc.IccCardApplicationStatus.AppType;
import com.android.internal.telephony.uicc.UiccController;

import android.app.ActionBar;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager.NameNotFoundException;
import android.net.Uri;
import android.os.AsyncResult;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.os.UserManager;
import android.preference.CheckBoxPreference;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.PreferenceActivity;
import android.preference.PreferenceScreen;
import android.preference.SwitchPreference;
import android.provider.Settings;
import android.provider.Settings.SettingNotFoundException;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.Log;
import android.view.MenuItem;

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
public class MobileNetworkSettings extends PreferenceActivity
        implements DialogInterface.OnClickListener,
        DialogInterface.OnDismissListener, Preference.OnPreferenceChangeListener{

    // debug data
    private static final String LOG_TAG = "NetworkSettings";
    private static final boolean DBG = true;
    public static final int REQUEST_CODE_EXIT_ECM = 17;

    //String keys for preference lookup
    private static final String BUTTON_PREFERED_NETWORK_MODE = "preferred_network_mode_key";
    private static final String BUTTON_ROAMING_KEY = "button_roaming_key";
    private static final String BUTTON_CDMA_LTE_DATA_SERVICE_KEY = "cdma_lte_data_service_key";
    private static final String BUTTON_UPLMN_KEY = "button_uplmn_key";
    private static final String BUTTON_ENABLED_NETWORKS_KEY = "enabled_networks_key";
    private static final String BUTTON_4G_LTE_KEY = "enhanced_4g_lte";
    private static final String BUTTON_CELL_BROADCAST_SETTINGS = "cell_broadcast_settings";
    private static final String KEY_PREFERRED_LTE = "toggle_preferred_lte";
    static final int preferredNetworkMode = Phone.PREFERRED_NT_MODE;

    //Information about logical "up" Activity
    private static final String UP_ACTIVITY_PACKAGE = "com.android.settings";
    private static final String UP_ACTIVITY_CLASS =
            "com.android.settings.Settings$WirelessSettingsActivity";

    //UI objects
    private ListPreference mButtonPreferredNetworkMode;
    private ListPreference mButtonEnabledNetworks;
    private SwitchPreference mButtonDataRoam;
    private SwitchPreference mButton4glte;
    private Preference mLteDataServicePref;
    private CheckBoxPreference mButtonPreferredLte;

    private static final String iface = "rmnet0"; //TODO: this will go away

    private UserManager mUm;
    private Phone mPhone;
    private MyHandler mHandler;
    private boolean mOkClicked;

    //GsmUmts options and Cdma options
    GsmUmtsOptions mGsmUmtsOptions;
    CdmaOptions mCdmaOptions;

    private Preference mClickedPreference;
    private boolean mShow4GForLTE;
    private boolean mIsGlobalCdma;
    private boolean mUnavailable;

    //This is a method implemented for DialogInterface.OnClickListener.
    //  Used to dismiss the dialogs when they come up.
    public void onClick(DialogInterface dialog, int which) {
        if (which == DialogInterface.BUTTON_POSITIVE) {
            mPhone.setDataRoamingEnabled(true);
            mOkClicked = true;
        } else {
            // Reset the toggle
            mButtonDataRoam.setChecked(false);
        }
    }

    @Override
    public void onDismiss(DialogInterface dialog) {
        // Assuming that onClick gets called first
        mButtonDataRoam.setChecked(mOkClicked);
    }

    /**
     * Invoked on each preference click in this hierarchy, overrides
     * PreferenceActivity's implementation.  Used to make sure we track the
     * preference click events.
     */
    @Override
    public boolean onPreferenceTreeClick(PreferenceScreen preferenceScreen, Preference preference) {
        /** TODO: Refactor and get rid of the if's using subclasses */
        if (preference.getKey().equals(BUTTON_4G_LTE_KEY)) {
            return true;
        } else if (mGsmUmtsOptions != null &&
                mGsmUmtsOptions.preferenceTreeClick(preference) == true) {
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
            int settingsNetworkMode = android.provider.Settings.Global.getInt(mPhone.getContext().
                    getContentResolver(), android.provider.Settings.Global.PREFERRED_NETWORK_MODE,
                    preferredNetworkMode);
            mButtonPreferredNetworkMode.setValue(Integer.toString(settingsNetworkMode));
            return true;
        } else if (preference == mLteDataServicePref) {
            String tmpl = android.provider.Settings.Global.getString(getContentResolver(),
                        android.provider.Settings.Global.SETUP_PREPAID_DATA_SERVICE_URL);
            if (!TextUtils.isEmpty(tmpl)) {
                TelephonyManager tm = (TelephonyManager) getSystemService(
                        Context.TELEPHONY_SERVICE);
                String imsi = tm.getSubscriberId();
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
        }  else if (preference == mButtonEnabledNetworks) {
            int settingsNetworkMode = android.provider.Settings.Global.getInt(mPhone.getContext().
                    getContentResolver(), android.provider.Settings.Global.PREFERRED_NETWORK_MODE,
                    preferredNetworkMode);
            mButtonEnabledNetworks.setValue(Integer.toString(settingsNetworkMode));
            return true;
        } else if (preference == mButtonDataRoam) {
            // Do not disable the preference screen if the user clicks Data roaming.
            return true;
        } else if (preference == mButtonPreferredLte) {
            setPreferredLte(mButtonPreferredLte.isChecked());
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

    private void setIMS(boolean turnOn) {
        SharedPreferences imsPref =
            getSharedPreferences(ImsManager.IMS_SHARED_PREFERENCES, Context.MODE_WORLD_READABLE);

        imsPref.edit().putBoolean(ImsManager.KEY_IMS_ON, turnOn).commit();
        Settings.System.putInt(getContentResolver(), ImsManager.KEY_IMS_ON, turnOn ? 1: 0);
    }

    @Override
    protected void onCreate(Bundle icicle) {
        setTheme(R.style.Theme_Material_Settings);
        super.onCreate(icicle);

        mPhone = PhoneUtils.getPhoneFromIntent(getIntent());
        log("Settings onCreate phoneId =" + mPhone.getPhoneId());

        mHandler = new MyHandler();
        mUm = (UserManager) getSystemService(Context.USER_SERVICE);

        if (mUm.hasUserRestriction(UserManager.DISALLOW_CONFIG_MOBILE_NETWORKS)) {
            mUnavailable = true;
            setContentView(R.layout.telephony_disallowed_preference_screen);
            return;
        }

        addPreferencesFromResource(R.xml.network_setting);

        mButton4glte = (SwitchPreference)findPreference(BUTTON_4G_LTE_KEY);

        mButton4glte.setOnPreferenceChangeListener(this);
        mButton4glte.setChecked(ImsManager.isEnhanced4gLteModeSettingEnabledByUser(this));

        try {
            Context con = createPackageContext("com.android.systemui", 0);
            int id = con.getResources().getIdentifier("config_show4GForLTE",
                    "bool", "com.android.systemui");
            mShow4GForLTE = con.getResources().getBoolean(id);
        } catch (NameNotFoundException e) {
            loge("NameNotFoundException for show4GFotLTE");
            mShow4GForLTE = false;
        }

        //Register for intent broadcasts
        IntentFilter intentFilter = new IntentFilter(Intent.ACTION_AIRPLANE_MODE_CHANGED);
        intentFilter.addAction(TelephonyIntents.ACTION_SIM_STATE_CHANGED);

        registerReceiver(mReceiver, intentFilter);

        //get UI object references
        PreferenceScreen prefSet = getPreferenceScreen();

        mButtonDataRoam = (SwitchPreference) prefSet.findPreference(BUTTON_ROAMING_KEY);
        mButtonPreferredNetworkMode = (ListPreference) prefSet.findPreference(
                BUTTON_PREFERED_NETWORK_MODE);
        mButtonEnabledNetworks = (ListPreference) prefSet.findPreference(
                BUTTON_ENABLED_NETWORKS_KEY);
        mButtonDataRoam.setOnPreferenceChangeListener(this);

        mLteDataServicePref = prefSet.findPreference(BUTTON_CDMA_LTE_DATA_SERVICE_KEY);

        if (!getResources().getBoolean(R.bool.config_uplmn_for_usim)) {
            Preference mUPLMNPref = prefSet.findPreference(BUTTON_UPLMN_KEY);
            prefSet.removePreference(mUPLMNPref);
            mUPLMNPref = null;
        }

        mButtonPreferredLte = (CheckBoxPreference) prefSet.findPreference(KEY_PREFERRED_LTE);
        if (Constants.NW_BAND_LTE_DEFAULT == Constants.NW_BAND_LTE_NV
            || !PhoneGlobals.getInstance().isPhoneFeatureEnabled()) {
            prefSet.removePreference(mButtonPreferredLte);
            mButtonPreferredLte = null;
        }

        int networkFeature = SystemProperties.getInt(Constants.PERSIST_RADIO_NETWORK_FEATURE,
                Constants.NETWORK_MODE_DEFAULT);

        switch (networkFeature) {
            case Constants.NETWORK_MODE_CMCC:
                if (UiccController.getInstance().getUiccCard() != null &&
                        UiccController.getInstance().getUiccCard()
                                .isApplicationOnIcc(AppType.APPTYPE_USIM)) {
                    mButtonPreferredNetworkMode
                            .setDialogTitle(R.string.preferred_network_mode_dialogtitle_cmcc);
                    mButtonPreferredNetworkMode.setEntries(
                            R.array.preferred_network_mode_choices_cmcc);
                    mButtonPreferredNetworkMode.setEntryValues(
                            R.array.preferred_network_mode_values_cmcc);
                } else {
                    prefSet.removePreference(mButtonPreferredNetworkMode);
                }
                break;
            default:
                break;
        }

        boolean isLteOnCdma = mPhone.getLteOnCdmaMode() == PhoneConstants.LTE_ON_CDMA_TRUE;
        mIsGlobalCdma = isLteOnCdma && getResources().getBoolean(R.bool.config_show_cdma);
        TelephonyManager tm = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
        // mButtonEnabledNetworks is not needed anymore
        prefSet.removePreference(mButtonEnabledNetworks);
        if (tm.getSimplifiedNetworkSettingsEnabledForSubscriber(SubscriptionManager.getDefaultSubId())) {
            prefSet.removePreference(mButtonPreferredNetworkMode);
            prefSet.removePreference(mButtonEnabledNetworks);
            prefSet.removePreference(mLteDataServicePref);
        } else if (getResources().getBoolean(R.bool.world_phone) == true) {
            // set the listener for the mButtonPreferredNetworkMode list preference so we can issue
            // change Preferred Network Mode.
            mButtonPreferredNetworkMode.setOnPreferenceChangeListener(this);

            //Get the networkMode from Settings.System and displays it
            int settingsNetworkMode = android.provider.Settings.Global.getInt(mPhone.getContext().
                    getContentResolver(),android.provider.Settings.Global.PREFERRED_NETWORK_MODE,
                    preferredNetworkMode);
            mButtonPreferredNetworkMode.setValue(Integer.toString(settingsNetworkMode));
            mCdmaOptions = new CdmaOptions(this, prefSet, mPhone);
            mGsmUmtsOptions = new GsmUmtsOptions(this, prefSet);
        } else {
            if (!isLteOnCdma) {
                prefSet.removePreference(mButtonPreferredNetworkMode);
            } else {
                mButtonPreferredNetworkMode.setOnPreferenceChangeListener(this);
                int settingsNetworkMode = android.provider.Settings.Global.getInt(
                        mPhone.getContext().getContentResolver(),
                        android.provider.Settings.Global.PREFERRED_NETWORK_MODE,
                        preferredNetworkMode);
                mButtonPreferredNetworkMode.setValue(
                        Integer.toString(settingsNetworkMode));
            }
            int phoneType = mPhone.getPhoneType();
            if (phoneType == PhoneConstants.PHONE_TYPE_CDMA) {
                if (isLteOnCdma) {
                    mButtonEnabledNetworks.setEntries(
                            R.array.enabled_networks_cdma_choices);
                    mButtonEnabledNetworks.setEntryValues(
                            R.array.enabled_networks_cdma_values);
                }
                mCdmaOptions = new CdmaOptions(this, prefSet, mPhone);
            } else if (phoneType == PhoneConstants.PHONE_TYPE_GSM) {
                if (!getResources().getBoolean(R.bool.config_prefer_2g)
                        && !getResources().getBoolean(R.bool.config_enabled_lte)) {
                    mButtonEnabledNetworks.setEntries(
                            R.array.enabled_networks_except_gsm_lte_choices);
                    mButtonEnabledNetworks.setEntryValues(
                            R.array.enabled_networks_except_gsm_lte_values);
                } else if (!getResources().getBoolean(R.bool.config_prefer_2g)) {
                    int select = (mShow4GForLTE == true) ?
                        R.array.enabled_networks_except_gsm_4g_choices
                        : R.array.enabled_networks_except_gsm_choices;
                    mButtonEnabledNetworks.setEntries(select);
                    mButtonEnabledNetworks.setEntryValues(
                            R.array.enabled_networks_except_gsm_values);
                } else if (!getResources().getBoolean(R.bool.config_enabled_lte)) {
                    mButtonEnabledNetworks.setEntries(
                            R.array.enabled_networks_except_lte_choices);
                    mButtonEnabledNetworks.setEntryValues(
                            R.array.enabled_networks_except_lte_values);
                } else if (mIsGlobalCdma) {
                    mButtonEnabledNetworks.setEntries(
                            R.array.enabled_networks_cdma_choices);
                    mButtonEnabledNetworks.setEntryValues(
                            R.array.enabled_networks_cdma_values);
                } else {
                    int select = (mShow4GForLTE == true) ? R.array.enabled_networks_4g_choices
                        : R.array.enabled_networks_choices;
                    mButtonEnabledNetworks.setEntries(select);
                    mButtonEnabledNetworks.setEntryValues(
                            R.array.enabled_networks_values);
                }
                mGsmUmtsOptions = new GsmUmtsOptions(this, prefSet);
            } else {
                throw new IllegalStateException("Unexpected phone type: " + phoneType);
            }
            mButtonEnabledNetworks.setOnPreferenceChangeListener(this);
            int settingsNetworkMode = android.provider.Settings.Global.getInt(
                    mPhone.getContext().getContentResolver(),
                    android.provider.Settings.Global.PREFERRED_NETWORK_MODE,
                    preferredNetworkMode);
            if (DBG) log("settingsNetworkMode: " + settingsNetworkMode);
            mButtonEnabledNetworks.setValue(Integer.toString(settingsNetworkMode));
        }

        final boolean missingDataServiceUrl = TextUtils.isEmpty(
                android.provider.Settings.Global.getString(getContentResolver(),
                        android.provider.Settings.Global.SETUP_PREPAID_DATA_SERVICE_URL));
        if (!isLteOnCdma || missingDataServiceUrl) {
            prefSet.removePreference(mLteDataServicePref);
        } else {
            android.util.Log.d(LOG_TAG, "keep ltePref");
        }

        // Enable enhanced 4G LTE mode settings depending on whether exists on platform
        if (!ImsManager.isEnhanced4gLteModeSettingEnabledByPlatform(this)) {
            Preference pref = prefSet.findPreference(BUTTON_4G_LTE_KEY);
            if (pref != null) {
                prefSet.removePreference(pref);
            }
        }

        ActionBar actionBar = getActionBar();
        if (actionBar != null) {
            // android.R.id.home will be triggered in onOptionsItemSelected()
            actionBar.setDisplayHomeAsUpEnabled(true);
        }

        final boolean isSecondaryUser = UserHandle.myUserId() != UserHandle.USER_OWNER;
        // Enable link to CMAS app settings depending on the value in config.xml.
        final boolean isCellBroadcastAppLinkEnabled = this.getResources().getBoolean(
                com.android.internal.R.bool.config_cellBroadcastAppLinks);
        if (isSecondaryUser || !isCellBroadcastAppLinkEnabled
                || mUm.hasUserRestriction(UserManager.DISALLOW_CONFIG_CELL_BROADCASTS)) {
            PreferenceScreen root = getPreferenceScreen();
            Preference ps = findPreference(BUTTON_CELL_BROADCAST_SETTINGS);
            if (ps != null) {
                root.removePreference(ps);
            }
        }

        if (this.getResources().getBoolean(R.bool.hide_roaming)) {
            prefSet.removePreference(mButtonDataRoam);
        }
    }

    private void updateButtonPreferredLte() {
        if (mButtonPreferredLte == null) {
            return;
        }
        mButtonPreferredLte.setEnabled(PhoneUtils.isLTE(Settings.Global.getInt(getContentResolver(),
                Settings.Global.PREFERRED_NETWORK_MODE, mPhone.getPhoneId())));
        mButtonPreferredLte.setChecked(Settings.Global.getInt(getContentResolver(),
                Constants.SETTING_NW_BAND, mPhone.getPhoneId()) == Constants.NW_BAND_LTE_TDD);
    }

    @Override
    protected void onResume() {
        super.onResume();

        if (mUnavailable) {
            return;
        }

        // upon resumption from the sub-activity, make sure we re-enable the
        // preferences.
        setScreenState();

        // Set UI state in onResume because a user could go home, launch some
        // app to change this setting's backend, and re-launch this settings app
        // and the UI state would be inconsistent with actual state
        mButtonDataRoam.setChecked(mPhone.getDataRoamingEnabled());

        updateButtonPreferredLte();

        if (getPreferenceScreen().findPreference(BUTTON_PREFERED_NETWORK_MODE) != null)  {
            mPhone.getPreferredNetworkType(mHandler.obtainMessage(
                    MyHandler.MESSAGE_GET_PREFERRED_NETWORK_TYPE));
        }

        if (getPreferenceScreen().findPreference(BUTTON_ENABLED_NETWORKS_KEY) != null)  {
            mPhone.getPreferredNetworkType(mHandler.obtainMessage(
                    MyHandler.MESSAGE_GET_PREFERRED_NETWORK_TYPE));
        }
    }

    private void setScreenState() {
        int simState = TelephonyManager.getDefault().getSimState();
        getPreferenceScreen().setEnabled(simState != TelephonyManager.SIM_STATE_ABSENT);
    }

    /**
     * Receiver for ACTION_AIRPLANE_MODE_CHANGED and ACTION_SIM_STATE_CHANGED.
     */
    private BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();

            if (action.equals(Intent.ACTION_AIRPLANE_MODE_CHANGED) ||
                    action.equals(TelephonyIntents.ACTION_SIM_STATE_CHANGED)) {
                setScreenState();
            }
        }
    };


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
            //NOTE onPreferenceChange seems to be called even if there is no change
            //Check if the button value is changed from the System.Setting
            mButtonPreferredNetworkMode.setValue((String) objValue);
            int buttonNetworkMode;
            buttonNetworkMode = Integer.valueOf((String) objValue).intValue();
            int settingsNetworkMode = android.provider.Settings.Global.getInt(
                    mPhone.getContext().getContentResolver(),
                    android.provider.Settings.Global.PREFERRED_NETWORK_MODE, preferredNetworkMode);
            if (buttonNetworkMode != settingsNetworkMode) {
                int modemNetworkMode;
                // if new mode is invalid ignore it
                switch (buttonNetworkMode) {
                    case Phone.NT_MODE_WCDMA_PREF:
                    case Phone.NT_MODE_GSM_ONLY:
                    case Phone.NT_MODE_WCDMA_ONLY:
                    case Phone.NT_MODE_GSM_UMTS:
                    case Phone.NT_MODE_CDMA:
                    case Phone.NT_MODE_CDMA_NO_EVDO:
                    case Phone.NT_MODE_EVDO_NO_CDMA:
                    case Phone.NT_MODE_GLOBAL:
                    case Phone.NT_MODE_LTE_CDMA_AND_EVDO:
                    case Phone.NT_MODE_LTE_GSM_WCDMA:
                    case Phone.NT_MODE_LTE_CDMA_EVDO_GSM_WCDMA:
                    case Phone.NT_MODE_LTE_ONLY:
                    case Phone.NT_MODE_LTE_WCDMA:
                    case Phone.NT_MODE_TD_SCDMA_ONLY:
                    case Phone.NT_MODE_TD_SCDMA_WCDMA:
                    case Phone.NT_MODE_TD_SCDMA_LTE:
                    case Phone.NT_MODE_TD_SCDMA_GSM:
                    case Phone.NT_MODE_TD_SCDMA_GSM_LTE:
                    case Phone.NT_MODE_TD_SCDMA_GSM_WCDMA:
                    case Phone.NT_MODE_TD_SCDMA_WCDMA_LTE:
                    case Phone.NT_MODE_TD_SCDMA_GSM_WCDMA_LTE:
                    case Phone.NT_MODE_TD_SCDMA_CDMA_EVDO_GSM_WCDMA:
                    case Phone.NT_MODE_TD_SCDMA_LTE_CDMA_EVDO_GSM_WCDMA:
                        // This is one of the modes we recognize
                        modemNetworkMode = buttonNetworkMode;
                        break;
                    default:
                        loge("Invalid Network Mode (" + buttonNetworkMode + ") chosen. Ignore.");
                        return true;
                }

                UpdatePreferredNetworkModeSummary(buttonNetworkMode);

                android.provider.Settings.Global.putInt(mPhone.getContext().getContentResolver(),
                        android.provider.Settings.Global.PREFERRED_NETWORK_MODE,
                        buttonNetworkMode );
                //Set the modem network mode
                setPreferredNetworkType(modemNetworkMode);
            }
        } else if (preference == mButtonEnabledNetworks) {
            mButtonEnabledNetworks.setValue((String) objValue);
            int buttonNetworkMode;
            buttonNetworkMode = Integer.valueOf((String) objValue).intValue();
            if (DBG) log("buttonNetworkMode: " + buttonNetworkMode);
            int settingsNetworkMode = android.provider.Settings.Global.getInt(
                    mPhone.getContext().getContentResolver(),
                    android.provider.Settings.Global.PREFERRED_NETWORK_MODE, preferredNetworkMode);
            if (buttonNetworkMode != settingsNetworkMode) {
                int modemNetworkMode;
                // if new mode is invalid ignore it
                switch (buttonNetworkMode) {
                    case Phone.NT_MODE_WCDMA_PREF:
                    case Phone.NT_MODE_GSM_ONLY:
                    case Phone.NT_MODE_LTE_GSM_WCDMA:
                    case Phone.NT_MODE_LTE_CDMA_EVDO_GSM_WCDMA:
                    case Phone.NT_MODE_CDMA:
                    case Phone.NT_MODE_CDMA_NO_EVDO:
                    case Phone.NT_MODE_LTE_CDMA_AND_EVDO:
                        // This is one of the modes we recognize
                        modemNetworkMode = buttonNetworkMode;
                        break;
                    default:
                        loge("Invalid Network Mode (" + buttonNetworkMode + ") chosen. Ignore.");
                        return true;
                }

                UpdateEnabledNetworksValueAndSummary(buttonNetworkMode);

                android.provider.Settings.Global.putInt(mPhone.getContext().getContentResolver(),
                        android.provider.Settings.Global.PREFERRED_NETWORK_MODE,
                        buttonNetworkMode );
                //Set the modem network mode
                mPhone.setPreferredNetworkType(modemNetworkMode, mHandler
                        .obtainMessage(MyHandler.MESSAGE_SET_PREFERRED_NETWORK_TYPE));
            }
        } else if (preference == mButton4glte) {
            SwitchPreference ltePref = (SwitchPreference)preference;
            ltePref.setChecked(!ltePref.isChecked());
            setIMS(ltePref.isChecked());

            ImsManager imsMan = ImsManager.getInstance(getBaseContext(),
                    SubscriptionManager.getDefaultVoiceSubId());
            if (imsMan != null) {

                try {
                    imsMan.setAdvanced4GMode(ltePref.isChecked());
                } catch (ImsException ie) {
                    // do nothing
                }
            }
        } else if (preference == mButtonDataRoam) {
            if (DBG) log("onPreferenceTreeClick: preference == mButtonDataRoam.");

            //normally called on the toggle click
            if (!mButtonDataRoam.isChecked()) {
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
                mPhone.setDataRoamingEnabled(false);
            }
            return true;
        }

        // always let the preference setting proceed.
        return true;
    }

    // now use phone feature service to set network mode
    private void setPreferredNetworkType(int networkMode) {
        if (PhoneGlobals.getInstance().isPhoneFeatureEnabled()) {
            PhoneGlobals.getInstance().setPrefNetwork(0, networkMode, mHandler.obtainMessage(
                MyHandler.MESSAGE_SET_PREFERRED_NETWORK_TYPE_BY_PLUGIN));
        } else {
            // Set the modem network mode
            mPhone.setPreferredNetworkType(networkMode,
                    mHandler.obtainMessage(MyHandler.MESSAGE_SET_PREFERRED_NETWORK_TYPE));
        }
    }

    private class MyHandler extends Handler {

        static final int MESSAGE_GET_PREFERRED_NETWORK_TYPE = 0;
        static final int MESSAGE_SET_PREFERRED_NETWORK_TYPE = 1;
        static final int MESSAGE_SET_PREFERRED_NETWORK_TYPE_BY_PLUGIN = 2;
        static final int MESSAGE_SET_PREFERRED_LTE = 3;

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MESSAGE_GET_PREFERRED_NETWORK_TYPE:
                    handleGetPreferredNetworkTypeResponse(msg);
                    break;

                case MESSAGE_SET_PREFERRED_NETWORK_TYPE:
                    handleSetPreferredNetworkTypeResponse(msg);
                    break;
                case MESSAGE_SET_PREFERRED_NETWORK_TYPE_BY_PLUGIN:
                    handleSetPreferredNetworkTypePluginResponse(msg);
                    break;
                case MESSAGE_SET_PREFERRED_LTE:
                    handleSetPreferredLTEResponse();
            }
        }

        private void handleSetPreferredLTEResponse() {
            updateButtonPreferredLte();
            if (mButtonPreferredNetworkMode != null) {
                int networkMode = android.provider.Settings.Global.getInt(mPhone
                    .getContext().getContentResolver(),
                    android.provider.Settings.Global.PREFERRED_NETWORK_MODE, preferredNetworkMode);
                UpdatePreferredNetworkModeSummary(networkMode);
            }
        }
        private void handleSetPreferredNetworkTypePluginResponse(Message msg) {
            if (mButtonPreferredNetworkMode != null) {
                UpdatePreferredNetworkModeSummary(android.provider.Settings.Global
                        .getInt(mPhone.getContext().getContentResolver(),
                        android.provider.Settings.Global.PREFERRED_NETWORK_MODE,
                        preferredNetworkMode));
            }
            updateButtonPreferredLte();
        }

        private void handleGetPreferredNetworkTypeResponse(Message msg) {
            AsyncResult ar = (AsyncResult) msg.obj;

            if (ar.exception == null) {
                int modemNetworkMode = ((int[])ar.result)[0];

                if (DBG) {
                    log ("handleGetPreferredNetworkTypeResponse: modemNetworkMode = " +
                            modemNetworkMode);
                }

                int settingsNetworkMode = android.provider.Settings.Global.getInt(
                        mPhone.getContext().getContentResolver(),
                        android.provider.Settings.Global.PREFERRED_NETWORK_MODE,
                        preferredNetworkMode);

                if (DBG) {
                    log("handleGetPreferredNetworkTypeReponse: settingsNetworkMode = " +
                            settingsNetworkMode);
                }

                //check that modemNetworkMode is from an accepted value
                if (modemNetworkMode == Phone.NT_MODE_WCDMA_PREF ||
                        modemNetworkMode == Phone.NT_MODE_GSM_ONLY ||
                        modemNetworkMode == Phone.NT_MODE_WCDMA_ONLY ||
                        modemNetworkMode == Phone.NT_MODE_GSM_UMTS ||
                        modemNetworkMode == Phone.NT_MODE_CDMA ||
                        modemNetworkMode == Phone.NT_MODE_CDMA_NO_EVDO ||
                        modemNetworkMode == Phone.NT_MODE_EVDO_NO_CDMA ||
                        modemNetworkMode == Phone.NT_MODE_GLOBAL ||
                        modemNetworkMode == Phone.NT_MODE_LTE_CDMA_AND_EVDO ||
                        modemNetworkMode == Phone.NT_MODE_LTE_GSM_WCDMA ||
                        modemNetworkMode == Phone.NT_MODE_LTE_CDMA_EVDO_GSM_WCDMA ||
                        modemNetworkMode == Phone.NT_MODE_LTE_ONLY ||
                        modemNetworkMode == Phone.NT_MODE_LTE_WCDMA ||
                        modemNetworkMode == Phone.NT_MODE_TD_SCDMA_ONLY ||
                        modemNetworkMode == Phone.NT_MODE_TD_SCDMA_WCDMA ||
                        modemNetworkMode == Phone.NT_MODE_TD_SCDMA_LTE ||
                        modemNetworkMode == Phone.NT_MODE_TD_SCDMA_GSM ||
                        modemNetworkMode == Phone.NT_MODE_TD_SCDMA_GSM_LTE ||
                        modemNetworkMode == Phone.NT_MODE_TD_SCDMA_GSM_WCDMA ||
                        modemNetworkMode == Phone.NT_MODE_TD_SCDMA_WCDMA_LTE ||
                        modemNetworkMode == Phone.NT_MODE_TD_SCDMA_GSM_WCDMA_LTE ||
                        modemNetworkMode == Phone.NT_MODE_TD_SCDMA_CDMA_EVDO_GSM_WCDMA ||
                        modemNetworkMode == Phone.NT_MODE_TD_SCDMA_LTE_CDMA_EVDO_GSM_WCDMA) {
                    if (DBG) {
                        log("handleGetPreferredNetworkTypeResponse: if 1: modemNetworkMode = " +
                                modemNetworkMode);
                    }

                    //check changes in modemNetworkMode
                    if (modemNetworkMode != settingsNetworkMode) {
                        if (DBG) {
                            log("handleGetPreferredNetworkTypeResponse: if 2: " +
                                    "modemNetworkMode != settingsNetworkMode");
                        }

                        settingsNetworkMode = modemNetworkMode;

                        if (DBG) { log("handleGetPreferredNetworkTypeResponse: if 2: " +
                                "settingsNetworkMode = " + settingsNetworkMode);
                        }
                    }

                    if (mButtonPreferredNetworkMode != null) {
                        UpdatePreferredNetworkModeSummary(modemNetworkMode);
                        // changes the mButtonPreferredNetworkMode accordingly to modemNetworkMode
                        mButtonPreferredNetworkMode.setValue(Integer.toString(modemNetworkMode));
                    } else if (mButtonEnabledNetworks != null) {
                        UpdateEnabledNetworksValueAndSummary(modemNetworkMode);
                        // changes the mButtonEnabledNetworks accordingly to modemNetworkMode
                        mButtonEnabledNetworks.setValue(Integer.toString(modemNetworkMode));
                    }
                } else {
                    if (DBG) log("handleGetPreferredNetworkTypeResponse: else: reset to default");
                    resetNetworkModeToDefault();
                }
                updateButtonPreferredLte();
            }
        }

        private void handleSetPreferredNetworkTypeResponse(Message msg) {
            AsyncResult ar = (AsyncResult) msg.obj;
            int networkMode;

            if (ar.exception == null) {
                if (mButtonPreferredNetworkMode != null) {
                    networkMode = Integer.valueOf(
                            mButtonPreferredNetworkMode.getValue()).intValue();
                    android.provider.Settings.Global.putInt(mPhone.getContext()
                            .getContentResolver(),
                            android.provider.Settings.Global.PREFERRED_NETWORK_MODE, networkMode);
                } else if (mButtonEnabledNetworks != null) {
                    networkMode = Integer.valueOf(
                            mButtonEnabledNetworks.getValue()).intValue();
                    android.provider.Settings.Global.putInt(mPhone.getContext()
                            .getContentResolver(),
                            android.provider.Settings.Global.PREFERRED_NETWORK_MODE, networkMode);
                }
                updateButtonPreferredLte();
            } else {
                mPhone.getPreferredNetworkType(obtainMessage(MESSAGE_GET_PREFERRED_NETWORK_TYPE));
            }
        }

        private void resetNetworkModeToDefault() {
            //set the mButtonPreferredNetworkMode
            mButtonPreferredNetworkMode.setValue(Integer.toString(preferredNetworkMode));
            mButtonEnabledNetworks.setValue(Integer.toString(preferredNetworkMode));
            //set the Settings.System
            android.provider.Settings.Global.putInt(mPhone.getContext().getContentResolver(),
                        android.provider.Settings.Global.PREFERRED_NETWORK_MODE,
                        preferredNetworkMode );
            //Set the Modem
            mPhone.setPreferredNetworkType(preferredNetworkMode,
                    this.obtainMessage(MyHandler.MESSAGE_SET_PREFERRED_NETWORK_TYPE));
        }
    }

    private void UpdatePreferredNetworkModeSummary(int NetworkMode) {
        int networkFeature = SystemProperties.getInt(Constants.PERSIST_RADIO_NETWORK_FEATURE,
                Constants.NETWORK_MODE_DEFAULT);
        switch(NetworkMode) {
            case Phone.NT_MODE_WCDMA_PREF:
                mButtonPreferredNetworkMode.setSummary(
                        R.string.preferred_network_mode_wcdma_perf_summary);
                break;
            case Phone.NT_MODE_GSM_ONLY:
                mButtonPreferredNetworkMode.setSummary(
                        R.string.preferred_network_mode_gsm_only_summary);
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
            case Phone.NT_MODE_LTE_CDMA_EVDO_GSM_WCDMA:
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
                                R.string.preferred_network_mode_3g_2g_auto);
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
                    mButtonPreferredNetworkMode.setSummary(
                            R.string.preferred_network_mode_4g_3g_2g_auto);
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

    private void UpdateEnabledNetworksValueAndSummary(int NetworkMode) {
        switch (NetworkMode) {
            case Phone.NT_MODE_WCDMA_ONLY:
            case Phone.NT_MODE_GSM_UMTS:
            case Phone.NT_MODE_WCDMA_PREF:
                if (!mIsGlobalCdma) {
                    mButtonEnabledNetworks.setValue(
                            Integer.toString(Phone.NT_MODE_WCDMA_PREF));
                    mButtonEnabledNetworks.setSummary(R.string.network_3G);
                } else {
                    mButtonEnabledNetworks.setValue(
                            Integer.toString(Phone.NT_MODE_LTE_CDMA_EVDO_GSM_WCDMA));
                    mButtonEnabledNetworks.setSummary(R.string.network_global);
                }
                break;
            case Phone.NT_MODE_GSM_ONLY:
                if (!mIsGlobalCdma) {
                    mButtonEnabledNetworks.setValue(
                            Integer.toString(Phone.NT_MODE_GSM_ONLY));
                    mButtonEnabledNetworks.setSummary(R.string.network_2G);
                } else {
                    mButtonEnabledNetworks.setValue(
                            Integer.toString(Phone.NT_MODE_LTE_CDMA_EVDO_GSM_WCDMA));
                    mButtonEnabledNetworks.setSummary(R.string.network_global);
                }
                break;
            case Phone.NT_MODE_LTE_GSM_WCDMA:
            case Phone.NT_MODE_LTE_ONLY:
            case Phone.NT_MODE_LTE_WCDMA:
                if (!mIsGlobalCdma) {
                    mButtonEnabledNetworks.setValue(
                            Integer.toString(Phone.NT_MODE_LTE_GSM_WCDMA));
                    mButtonEnabledNetworks.setSummary((mShow4GForLTE == true)
                            ? R.string.network_4G : R.string.network_lte);
                } else {
                    mButtonEnabledNetworks.setValue(
                            Integer.toString(Phone.NT_MODE_LTE_CDMA_EVDO_GSM_WCDMA));
                    mButtonEnabledNetworks.setSummary(R.string.network_global);
                }
                break;
            case Phone.NT_MODE_LTE_CDMA_AND_EVDO:
                mButtonEnabledNetworks.setValue(
                        Integer.toString(Phone.NT_MODE_LTE_CDMA_AND_EVDO));
                mButtonEnabledNetworks.setSummary(R.string.network_lte);
                break;
            case Phone.NT_MODE_CDMA:
            case Phone.NT_MODE_EVDO_NO_CDMA:
            case Phone.NT_MODE_GLOBAL:
                mButtonEnabledNetworks.setValue(
                        Integer.toString(Phone.NT_MODE_CDMA));
                mButtonEnabledNetworks.setSummary(R.string.network_3G);
                break;
            case Phone.NT_MODE_CDMA_NO_EVDO:
                mButtonEnabledNetworks.setValue(
                        Integer.toString(Phone.NT_MODE_CDMA_NO_EVDO));
                mButtonEnabledNetworks.setSummary(R.string.network_1x);
                break;
            case Phone.NT_MODE_LTE_CDMA_EVDO_GSM_WCDMA:
                mButtonEnabledNetworks.setValue(
                        Integer.toString(Phone.NT_MODE_LTE_CDMA_EVDO_GSM_WCDMA));
                mButtonEnabledNetworks.setSummary(R.string.network_global);
                break;
            default:
                String errMsg = "Invalid Network Mode (" + NetworkMode + "). Ignore.";
                loge(errMsg);
                mButtonEnabledNetworks.setSummary(errMsg);
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

    private static void loge(String msg) {
        Log.e(LOG_TAG, msg);
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
    // Set preferred LTE mode
    private void setPreferredLte(boolean mode) {
        final Message msg = mHandler.obtainMessage(
                    MyHandler.MESSAGE_SET_PREFERRED_LTE);
        try {
            int band = mode ? Constants.NW_BAND_LTE_TDD : Constants.NW_BAND_LTE_FDD;
            int network = mode ? Phone.NT_MODE_LTE_ONLY :
                    TelephonyManager.getIntAtIndex(getContentResolver(),
                    Constants.SETTING_PRE_NW_MODE_DEFAULT, mPhone.getPhoneId());
            PhoneGlobals.getInstance().setPrefNetwork(mPhone.getPhoneId(), network, band, msg);
        } catch (SettingNotFoundException snfe) {
            log("multiSimSetPreferredLte: Could not find PREFERRED_NETWORK_MODE!!!");
        }
    }
}
