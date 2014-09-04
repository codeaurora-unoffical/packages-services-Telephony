/*
 * Copyright (c) 2014, The Linux Foundation. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *     * Redistributions of source code must retain the above copyright
 *       notice, this list of conditions and the following disclaimer.
 *     * Redistributions in binary form must reproduce the above
 *       copyright notice, this list of conditions and the following
 *       disclaimer in the documentation and/or other materials provided
 *       with the distribution.
 *     * Neither the name of The Linux Foundation nor the names of its
 *       contributors may be used to endorse or promote products derived
 *       from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED "AS IS" AND ANY EXPRESS OR IMPLIED
 * WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NON-INFRINGEMENT
 * ARE DISCLAIMED.  IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS
 * BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR
 * BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
 * WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE
 * OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN
 * IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 *
 */

package com.android.phone;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.AsyncResult;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout.LayoutParams;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import com.android.internal.telephony.MSimConstants;
import com.android.internal.telephony.Phone;
import com.codeaurora.telephony.msim.SubscriptionManager;

public class PreferredLTESubSelector extends Activity implements View.OnClickListener {

    private TextView mRecognizeText;
    private RadioGroup mRadioGroup;
    private Button mOKbutton;
    private CheckBox mDdsChecBox;
    private ProgressDialog mProgressDialog;
    private PrimarySubSelectionController mPrimarySubSelectionController;
    private boolean mIsPrimaryLteSubEnabled;

    private static final int SET_LTE_SUB_MSG = 1;

    private static final String CONFIG_ACTION = "com.android.phone.LTE_CONFIGURE";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.lte_sub_select);
        mPrimarySubSelectionController = PrimarySubSelectionController.getInstance();
        mIsPrimaryLteSubEnabled = mPrimarySubSelectionController.isPrimaryLteSubEnabled()
                && mPrimarySubSelectionController.isPrimarySetable()
                && mPrimarySubSelectionController.getPrimarySub() == -1;
        mRecognizeText = (TextView) findViewById(R.id.recognize_text);

        mRadioGroup = (RadioGroup) findViewById(R.id.radiogroup);
        for (int i = 0; i < PrimarySubSelectionController.PHONE_COUNT; i++) {
            RadioButton radioButton = new RadioButton(this);
            mRadioGroup.addView(radioButton, new LayoutParams(LayoutParams.WRAP_CONTENT,
                    LayoutParams.WRAP_CONTENT));
            radioButton.setEnabled(mIsPrimaryLteSubEnabled);
            radioButton.setTag(i);
            radioButton.setText(getSimName(i));
            radioButton.setOnClickListener(this);
        }
        mDdsChecBox = (CheckBox) findViewById(R.id.lte_checkBox);
        mDdsChecBox.setEnabled(mIsPrimaryLteSubEnabled);
        if (CONFIG_ACTION.equals(getIntent().getAction())) {
            setTitle(R.string.lte_select_title);
            mRecognizeText.setVisibility(View.GONE);
        } else {
            setTitle(R.string.lte_recognition_title);
            mDdsChecBox.setVisibility(View.GONE);
        }

        mOKbutton = (Button) findViewById(R.id.select_ok_btn);
        mOKbutton.setOnClickListener(this);

        mProgressDialog = new ProgressDialog(this);
        mProgressDialog.setMessage(this.getString(R.string.lte_setting));
        mProgressDialog.setCancelable(false);
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateState();
    }

    private void updateState() {
        int current = mPrimarySubSelectionController.getCurrentSub();
        mRadioGroup.clearCheck();
        for (int i = 0; i < mRadioGroup.getChildCount(); i++) {
            RadioButton radioButton = (RadioButton) mRadioGroup.getChildAt(i);
            radioButton.setChecked(current == i);
        }
        mOKbutton.setEnabled(false);
        if (!mIsPrimaryLteSubEnabled) {
            Toast.makeText(
                    this, getString(R.string.lte_switch_unavailable), Toast.LENGTH_LONG).show();
        }
    }

    private String getSimName(int sub) {
        return Settings.System.getString(getContentResolver(), "perferred_name_sub" + (sub + 1));
    }

    @Override
    public boolean onNavigateUp() {
        finish();
        return true;
    }

    public void onClick(View v) {
        int current = mPrimarySubSelectionController.getCurrentSub();
        if (v instanceof RadioButton) {
            int sub = (Integer) v.getTag();
            mOKbutton.setEnabled(sub != current);
            mOKbutton.setTag(sub);
        } else if (v == mOKbutton) {
            mProgressDialog.show();
            mPrimarySubSelectionController.setPreferredNetwork((Integer) mOKbutton.getTag(),
                    mHandler.obtainMessage(SET_LTE_SUB_MSG));
        }
    }

    private Handler mHandler = new Handler() {
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case SET_LTE_SUB_MSG:
                    int targetSub = (Integer) mOKbutton.getTag();
                    if (targetSub != mPrimarySubSelectionController.getCurrentSub()) {
                        showFailedDialog(targetSub);
                    } else {
                        if (mDdsChecBox.isChecked()) {
                            // After set NW mode done, in any case set dds to
                            // primary sub,
                            // if failed, then restore dds to primary sub once
                            // icc loaded done.
                            android.util.Log.d("PreferredLTESubSelector",
                                  " Set dds to primary sub, if failed, restore dds once icc loaded");
                            SubscriptionManager.getInstance().setDataSubscription(targetSub,
                                    null);
                            mPrimarySubSelectionController.setRestoreDdsToPrimarySub(true);
                        }
                        Toast.makeText(PreferredLTESubSelector.this, getString(R.string.reg_suc),
                                Toast.LENGTH_LONG).show();
                    }
                    updateState();
                    mProgressDialog.dismiss();
                    break;
                default:
                    break;
            }
        }
    };

    private void showFailedDialog(int sub) {
        if (CONFIG_ACTION.equals(getIntent().getAction())) {
            AlertDialog alertDialog = new AlertDialog.Builder(this)
                    .setTitle(R.string.reg_failed)
                    .setMessage(getString(R.string.reg_failed_msg, getSimName(sub)))
                    .setNeutralButton(R.string.select_ok, null)
                    .create();
            alertDialog.show();
        } else {
            AlertDialog alertDialog = new AlertDialog.Builder(this)
                    .setTitle(R.string.reg_failed)
                    .setMessage(getString(R.string.reg_failed_msg, getSimName(sub)))
                    .setNeutralButton(R.string.lte_set, new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface dialog, int which) {
                            String action = "com.android.settings.MULTI_SIM_SETTINGS";
                            Intent intent = new Intent(action);
                            startActivity(intent);
                            finish();
                        }
                    })
                    .setNegativeButton(R.string.select_cancel,
                            new DialogInterface.OnClickListener() {
                                public void onClick(DialogInterface dialog, int which) {
                                    finish();
                                }
                            })
                    .create();
            alertDialog.show();
        }
    }
}
