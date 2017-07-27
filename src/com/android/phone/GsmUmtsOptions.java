/*
 * Copyright (C) 2008 The Android Open Source Project
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

import android.content.Intent;
import android.content.res.Resources;
import android.os.PersistableBundle;
import android.preference.Preference;
import android.preference.PreferenceFragment;
import android.preference.PreferenceScreen;
import android.preference.TwoStatePreference;
import android.provider.Settings;
import android.telephony.CarrierConfigManager;

import com.android.internal.telephony.PhoneConstants;
import com.android.internal.telephony.PhoneFactory;

/**
 * List of Network-specific settings screens.
 */
public class GsmUmtsOptions {
    private static final String LOG_TAG = "GsmUmtsOptions";

    private Preference mButtonAPNExpand;
    private Preference mCategoryAPNExpand;
    private TwoStatePreference mButtonAutoSelect;
    private NetworkSelectListPreference mButtonOperatorSelection;

    private NetworkOperators mNetworkOperator;

    private static final String BUTTON_APN_EXPAND_KEY = "button_apn_key";
    private static final String CATEGORY_APN_EXPAND_KEY = "category_apn_key";
    private static final String BUTTON_CARRIER_SETTINGS_KEY = "carrier_settings_key";

    public static final String EXTRA_SUB_ID = "sub_id";
    private PreferenceFragment mPrefFragment;
    private PreferenceScreen mPrefScreen;
    INetworkQueryService mNetworkQueryService;
    private int mSubId;

    public GsmUmtsOptions(PreferenceFragment prefFragment, PreferenceScreen prefScreen,
            final int subId, INetworkQueryService queryService) {
        mPrefFragment = prefFragment;
        mPrefScreen = prefScreen;
        mSubId = subId;
        mNetworkQueryService = queryService;
        create();
    }

    protected void create() {
        mPrefFragment.addPreferencesFromResource(R.xml.gsm_umts_options);
        mButtonAPNExpand = mPrefScreen.findPreference(BUTTON_APN_EXPAND_KEY);
        mCategoryAPNExpand = mPrefScreen.findPreference(CATEGORY_APN_EXPAND_KEY);

        mNetworkOperator = (NetworkOperators) mPrefScreen
                .findPreference(NetworkOperators.CATEGORY_NETWORK_OPERATORS_KEY);
        mNetworkOperator.initialize(mPrefScreen, mSubId, mNetworkQueryService);

        boolean removedAPNExpand = false;
        boolean removedNetworkOperatorsCategory = false;
        if (PhoneFactory.getDefaultPhone().getPhoneType() != PhoneConstants.PHONE_TYPE_GSM) {
            log("Not a GSM phone");
            mCategoryAPNExpand.setEnabled(false);
            mNetworkOperator.setEnabled(false);
        } else {
            log("Not a CDMA phone");
            Resources res = mPrefFragment.getResources();
            PersistableBundle carrierConfig =
                    PhoneGlobals.getInstance().getCarrierConfigForSubId(mSubId);

            // Determine which options to display. For GSM these are defaulted to true in
            // CarrierConfigManager, but they maybe overriden by DefaultCarrierConfigService or a
            // carrier app.
            // Note: these settings used to be controlled with overlays in
            // Telephony/res/values/config.xml
            if (!carrierConfig.getBoolean(CarrierConfigManager.KEY_APN_EXPAND_BOOL)
                    && mCategoryAPNExpand != null) {
                removedAPNExpand = true;
            }
            if (!carrierConfig.getBoolean(
                    CarrierConfigManager.KEY_OPERATOR_SELECTION_EXPAND_BOOL)) {
                mPrefScreen.removePreference(mPrefScreen
                        .findPreference(NetworkOperators.CATEGORY_NETWORK_OPERATORS_KEY));
                removedNetworkOperatorsCategory = true;
            }

            if (carrierConfig.getBoolean(CarrierConfigManager.KEY_CSP_ENABLED_BOOL)) {
                if (PhoneFactory.getDefaultPhone().isCspPlmnEnabled()) {
                    log("[CSP] Enabling Operator Selection menu.");
                    mNetworkOperator.setEnabled(true);
                } else {
                    log("[CSP] Disabling Operator Selection menu.");
                    mPrefScreen.removePreference(mPrefScreen
                            .findPreference(NetworkOperators.CATEGORY_NETWORK_OPERATORS_KEY));
                    removedNetworkOperatorsCategory = true;
                }
            }

            // Read platform settings for carrier settings
            final boolean isCarrierSettingsEnabled = carrierConfig.getBoolean(
                CarrierConfigManager.KEY_CARRIER_SETTINGS_ENABLE_BOOL);
            if (!isCarrierSettingsEnabled) {
                Preference pref = mPrefScreen.findPreference(BUTTON_CARRIER_SETTINGS_KEY);
                if (pref != null) {
                    mPrefScreen.removePreference(pref);
                }
            }
        }
        if (!removedAPNExpand) {
            mButtonAPNExpand.setOnPreferenceClickListener(
                    new Preference.OnPreferenceClickListener() {
                        @Override
                        public boolean onPreferenceClick(Preference preference) {
                            // We need to build the Intent by hand as the Preference Framework
                            // does not allow to add an Intent with some extras into a Preference
                            // XML file
                            final Intent intent = new Intent(Settings.ACTION_APN_SETTINGS);
                            // This will setup the Home and Search affordance
                            intent.putExtra(":settings:show_fragment_as_subsetting", true);
                            intent.putExtra(EXTRA_SUB_ID, mSubId);
                            mPrefFragment.startActivity(intent);
                            return true;
                        }
            });
        } else {
            mPrefScreen.removePreference(mCategoryAPNExpand);
        }

        if (!removedNetworkOperatorsCategory) {
            mButtonAutoSelect = (TwoStatePreference) mPrefScreen
                    .findPreference(NetworkOperators.BUTTON_AUTO_SELECT_KEY);
            mButtonOperatorSelection = (NetworkSelectListPreference) mPrefScreen
                    .findPreference(NetworkOperators.BUTTON_NETWORK_SELECT_KEY);
        }
    }

    public boolean preferenceTreeClick(Preference preference) {
        if (preference == mButtonAutoSelect || preference == mButtonOperatorSelection) {
            return true;
        } else {
            log("preferenceTreeClick: return false");
            return false;
        }
    }

    protected void log(String s) {
        android.util.Log.d(LOG_TAG, s);
    }
}
