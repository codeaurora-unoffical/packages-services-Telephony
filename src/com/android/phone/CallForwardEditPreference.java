package com.android.phone;

import com.android.internal.telephony.CallForwardInfo;
import com.android.internal.telephony.CommandException;
import com.android.internal.telephony.CommandsInterface;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneBase;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.res.TypedArray;
import android.os.AsyncResult;
import android.os.Handler;
import android.os.Message;
import android.text.TextUtils;
import android.widget.Toast;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;

import static com.android.phone.TimeConsumingPreferenceActivity.RESPONSE_ERROR;

public class CallForwardEditPreference extends EditPhoneNumberPreference {
    private static final String LOG_TAG = "CallForwardEditPreference";
    private static final boolean DBG = (PhoneGlobals.DBG_LEVEL >= 2);

    private static final String SRC_TAGS[]       = {"{0}"};
    private CharSequence mSummaryOnTemplate;
    /**
     * Remembers which button was clicked by a user. If no button is clicked yet, this should have
     * {@link DialogInterface#BUTTON_NEGATIVE}, meaning "cancel".
     *
     * TODO: consider removing this variable and having getButtonClicked() in
     * EditPhoneNumberPreference instead.
     */
    private int mButtonClicked;
    private int mServiceClass;
    private MyHandler mHandler = new MyHandler();
    int reason;
    Phone phone;
    CallForwardInfo callForwardInfo;
    TimeConsumingPreferenceListener tcpListener;

    public CallForwardEditPreference(Context context, AttributeSet attrs) {
        super(context, attrs);

        phone = PhoneGlobals.getPhone();
        mSummaryOnTemplate = this.getSummaryOn();

        TypedArray a = context.obtainStyledAttributes(attrs,
                R.styleable.CallForwardEditPreference, 0, R.style.EditPhoneNumberPreference);
        reason = a.getInt(R.styleable.CallForwardEditPreference_reason,
                CommandsInterface.CF_REASON_UNCONDITIONAL);
        a.recycle();

        if (DBG) Log.d(LOG_TAG, "mServiceClass=" + mServiceClass + ", reason=" + reason);
    }

    public CallForwardEditPreference(Context context) {
        this(context, null);
    }

    void init(TimeConsumingPreferenceListener listener, boolean skipReading, int subscription,
            int serviceClass) {
        mServiceClass = serviceClass;
        // getting selected subscription
        if (DBG)
            Log.d(LOG_TAG, "Getting CallForwardEditPreference subscription =" + subscription);
        phone = PhoneGlobals.getInstance().getPhone(subscription);

        tcpListener = listener;
        if (!skipReading) {
            if (DBG) Log.d(LOG_TAG, "getCallForwardingOption for reason " + reason);
            Phone imsPhone = PhoneUtils.getImsPhone(PhoneGlobals.getInstance().mCM);
            if (imsPhone != null && (PhoneGlobals.isIMSRegisterd(subscription) ||
                    (imsPhone.isUtEnabled() &&
                    (imsPhone.getSubscription() == subscription)))) {
                if (DBG) Log.d(LOG_TAG, "UT interface, getCallForwardingOption for reason " + reason);
                //if (reason == CommandsInterface.CF_REASON_UNCONDITIONAL_TIMER
                    //|| reason == CommandsInterface.CF_REASON_UNCONDITIONAL){
                imsPhone.getCallForwardingOption(reason, mServiceClass,
                    mHandler.obtainMessage(MyHandler.MESSAGE_GET_CF,
                            CommandsInterface.CF_ACTION_DISABLE,
                            MyHandler.MESSAGE_GET_CF, null));
            } else {
                if (reason == CommandsInterface.CF_REASON_UNCONDITIONAL_TIMER){
                    if (DBG)
                        Log.d(LOG_TAG, "IMS is not registered, can not query for CFUT");
                    return;
                }
                phone.getCallForwardingOption(reason,
                        mHandler.obtainMessage(MyHandler.MESSAGE_GET_CF,
                                // unused in this case
                                CommandsInterface.CF_ACTION_DISABLE,
                                MyHandler.MESSAGE_GET_CF, null));
            }
            if (tcpListener != null) {
                tcpListener.onStarted(this, true);
            }
        }
    }

    @Override
    protected void onBindDialogView(View view) {
        // default the button clicked to be the cancel button.
        mButtonClicked = DialogInterface.BUTTON_NEGATIVE;
        super.onBindDialogView(view);
    }

    @Override
    public void onClick(DialogInterface dialog, int which) {
        super.onClick(dialog, which);
        mButtonClicked = which;
    }

    @Override
    protected void onDialogClosed(boolean positiveResult) {
        super.onDialogClosed(positiveResult);

        if (DBG) Log.d(LOG_TAG, "mButtonClicked=" + mButtonClicked
                + ", positiveResult=" + positiveResult);
        // Ignore this event if the user clicked the cancel button, or if the dialog is dismissed
        // without any button being pressed (back button press or click event outside the dialog).
        if (this.mButtonClicked != DialogInterface.BUTTON_NEGATIVE) {
            int action = (isToggled() || (mButtonClicked == DialogInterface.BUTTON_POSITIVE)) ?
                    CommandsInterface.CF_ACTION_REGISTRATION :
                    CommandsInterface.CF_ACTION_DISABLE;
            int time = (reason != CommandsInterface.CF_REASON_NO_REPLY) ? 0 : 20;
            final String number = getPhoneNumber();
            final int editStartHour = isAllDayChecked()? 0 : getStartTimeHour();
            final int editStartMinute = isAllDayChecked()? 0 : getStartTimeMinute();
            final int editEndHour = isAllDayChecked()? 0 : getEndTimeHour();
            final int editEndMinute = isAllDayChecked()? 0 : getEndTimeMinute();
            if (DBG) Log.d(LOG_TAG, "callForwardInfo=" + callForwardInfo);

            boolean isCFSettingChanged = true;
            if (action == CommandsInterface.CF_ACTION_REGISTRATION
                    && callForwardInfo != null
                    && callForwardInfo.status == 1
                    && number.equals(callForwardInfo.number)) {
                if (reason == CommandsInterface.CF_REASON_UNCONDITIONAL_TIMER
                        /*|| reason == CommandsInterface.CF_REASON_UNCONDITIONAL*/){
                    // need to check if the time period for CFUT is changed
                    if (isAllDayChecked()){
                        isCFSettingChanged = isTimerValid(callForwardInfo);
                    } else {
                        isCFSettingChanged = callForwardInfo.startHour != editStartHour
                                || callForwardInfo.startHour != editStartMinute
                                || callForwardInfo.endHour != editEndHour
                                || callForwardInfo.endMinute != editEndMinute;
                    }
                } else {
                    // no change, do nothing
                    if (DBG) Log.d(LOG_TAG, "no change, do nothing");
                    isCFSettingChanged = false;
                }
            }
            if (DBG) Log.d(LOG_TAG, "onDialogClosed: "
                    + ", reason=" + reason
                    + ", action=" + action
                    + ", number=" + number
                    + ", isCFSettingChanged" + isCFSettingChanged);
            if (isCFSettingChanged) {
                // set to network
                if (DBG) Log.d(LOG_TAG, "reason=" + reason + ", action=" + action
                        + ", number=" + number);

                // Display no forwarding number while we're waiting for
                // confirmation
                setSummaryOn("");

                // the interface of Phone.setCallForwardingOption has error:
                // should be action, reason...
                Phone imsPhone =
                        PhoneUtils.getImsPhone(PhoneGlobals.getInstance().mCM);
                if (imsPhone != null && (PhoneGlobals.isIMSRegisterd(phone.getSubscription()) ||
                        (imsPhone.isUtEnabled() &&
                        (imsPhone.getSubscription() == phone.getSubscription())))) {
                    Log.d(LOG_TAG, "onDialogClosed, set CallForwarding on UT");
                    if (reason == CommandsInterface.CF_REASON_UNCONDITIONAL_TIMER){
                        if (DBG) {
                            Log.d(LOG_TAG, "onDialogClosed, setCallForwardingTimerOption"
                                + ", StartHour=" + editStartHour
                                + ", StartMinute=" + editStartMinute
                                + ", EndHour=" + editEndHour
                                + ", EndMinute=" + editEndMinute);
                        }
                        ((PhoneBase)imsPhone).setCallForwardingTimerOption(editStartHour,
                            editStartMinute,
                            editEndHour,
                            editEndMinute,
                            action,
                            reason,
                            number,
                            time,
                            mHandler.obtainMessage(MyHandler.MESSAGE_SET_CF,
                                    action,
                                    MyHandler.MESSAGE_SET_CF));
                    } else {
                        imsPhone.setCallForwardingOption(action,
                                reason,
                                mServiceClass,
                                number,
                                time,
                                mHandler.obtainMessage(MyHandler.MESSAGE_SET_CF,
                                        action,
                                        MyHandler.MESSAGE_SET_CF));
                    }
                } else {
                    phone.setCallForwardingOption(action,
                            reason,
                            number,
                            time,
                            mHandler.obtainMessage(MyHandler.MESSAGE_SET_CF,
                                    action,
                                    MyHandler.MESSAGE_SET_CF));
                }

                if (tcpListener != null) {
                    tcpListener.onStarted(this, false);
                }
            }
        }
    }

    void handleCallForwardResult(CallForwardInfo cf) {
        callForwardInfo = cf;
        if (DBG) Log.d(LOG_TAG, "handleGetCFResponse done, callForwardInfo=" + callForwardInfo);

        setToggled(callForwardInfo.status == 1);
        setPhoneNumber(callForwardInfo.number);
        //for cfu time based case, need to set time.
        if (callForwardInfo.reason == CommandsInterface.CF_REASON_UNCONDITIONAL_TIMER
                /*|| callForwardInfo.reason == CommandsInterface.CF_REASON_UNCONDITIONAL*/){
            if (DBG){
                Log.e(LOG_TAG, "handleCallForwardResult, reason " + callForwardInfo.reason
                        + ", status " + callForwardInfo.status
                        + ", number " + callForwardInfo.number
                        + ", startHour " + callForwardInfo.startHour
                        + ", startMinute " + callForwardInfo.startMinute
                        + ", endHour " + callForwardInfo.endHour
                        + ", endMinute " + callForwardInfo.endMinute);
            }
            //set all day not checked if cfut is enabled
            setAllDayCheckBox(!isTimerValid(callForwardInfo));
            setPhoneNumberWithTimePeriod(callForwardInfo.number,
                    callForwardInfo.startHour, callForwardInfo.startMinute,
                    callForwardInfo.endHour, callForwardInfo.endMinute);
        }
    }

    private void updateSummaryText() {
        if (DBG) Log.e(LOG_TAG, "updateSummaryText, complete fetching for reason " + reason);
        if (isToggled()) {
            CharSequence summaryOn;
            String number = getRawPhoneNumber();
            if (reason == CommandsInterface.CF_REASON_UNCONDITIONAL_TIMER
                /*|| reason == CommandsInterface.CF_REASON_UNCONDITIONAL*/){
                number = getRawPhoneNumberWithTime();
            }
            if (number != null && number.length() > 0) {
                String values[] = { number };
                summaryOn = TextUtils.replace(mSummaryOnTemplate, SRC_TAGS, values);
            } else {
                summaryOn = getContext().getString(R.string.sum_cfu_enabled_no_number);
            }
            setSummaryOn(summaryOn);
        }

    }

    // Message protocol:
    // what: get vs. set
    // arg1: action -- register vs. disable
    // arg2: get vs. set for the preceding request
    private class MyHandler extends Handler {
        static final int MESSAGE_GET_CF = 0;
        static final int MESSAGE_SET_CF = 1;

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MESSAGE_GET_CF:
                    handleGetCFResponse(msg);
                    break;
                case MESSAGE_SET_CF:
                    handleSetCFResponse(msg);
                    break;
            }
        }

        private void handleGetCFResponse(Message msg) {
            if (DBG) Log.d(LOG_TAG, "handleGetCFResponse: done");

            if (msg.arg2 == MESSAGE_SET_CF) {
                tcpListener.onFinished(CallForwardEditPreference.this, false);
            } else {
                tcpListener.onFinished(CallForwardEditPreference.this, true);
            }

            AsyncResult ar = (AsyncResult) msg.obj;

            callForwardInfo = null;
            if (ar.exception != null) {
                if (DBG) Log.d(LOG_TAG, "handleGetCFResponse: ar.exception=" + ar.exception);
                if (ar.exception instanceof CommandException){
                    tcpListener.onException(CallForwardEditPreference.this,
                            (CommandException) ar.exception);
                } else {
                    tcpListener.onException(CallForwardEditPreference.this,
                            CommandException.fromRilErrno(2));
                }
            } else {
                if (ar.userObj instanceof Throwable) {
                    tcpListener.onError(CallForwardEditPreference.this, RESPONSE_ERROR);
                }
                CallForwardInfo cfInfoArray[] = (CallForwardInfo[]) ar.result;
                if (cfInfoArray.length == 0) {
                    if (DBG) Log.d(LOG_TAG, "handleGetCFResponse: cfInfoArray.length==0");
                    setEnabled(false);
                    tcpListener.onError(CallForwardEditPreference.this, RESPONSE_ERROR);
                } else {
                    for (int i = 0, length = cfInfoArray.length; i < length; i++) {
                        if (DBG) Log.d(LOG_TAG, "handleGetCFResponse, cfInfoArray[" + i + "]="
                                + cfInfoArray[i]);
                        if ((mServiceClass & cfInfoArray[i].serviceClass) != 0) {
                            // corresponding class
                            CallForwardInfo info = cfInfoArray[i];
                            handleCallForwardResult(info);

                            // Show an alert if we got a success response but
                            // with unexpected values.
                            // Currently only handle the fail-to-disable case
                            // since we haven't observed fail-to-enable.
                            if (msg.arg2 == MESSAGE_SET_CF &&
                                    msg.arg1 == CommandsInterface.CF_ACTION_DISABLE &&
                                    info.status == 1) {
                                CharSequence s;
                                switch (reason) {
                                    case CommandsInterface.CF_REASON_BUSY:
                                        s = getContext().getText(R.string.disable_cfb_forbidden);
                                        break;
                                    case CommandsInterface.CF_REASON_NO_REPLY:
                                        s = getContext().getText(R.string.disable_cfnry_forbidden);
                                        break;
                                    default: // not reachable
                                        s = getContext().getText(R.string.disable_cfnrc_forbidden);
                                }
                                AlertDialog.Builder builder = new AlertDialog.Builder(getContext());
                                builder.setNeutralButton(R.string.close_dialog, null);
                                builder.setTitle(getContext().getText(R.string.error_updating_title));
                                builder.setMessage(s);
                                builder.setCancelable(true);
                                builder.create().show();
                            }
                        }
                    }
                }
            }

            // Now whether or not we got a new number, reset our enabled
            // summary text since it may have been replaced by an empty
            // placeholder.
            updateSummaryText();
        }

        private void handleSetCFResponse(Message msg) {
            AsyncResult ar = (AsyncResult) msg.obj;

            if (ar.exception != null) {
                if (DBG) Log.d(LOG_TAG, "handleSetCFResponse: ar.exception=" + ar.exception);
                // setEnabled(false);
            }
            if (DBG) Log.d(LOG_TAG, "handleSetCFResponse: re get, reason = " + reason);
            if (reason == CommandsInterface.CF_REASON_UNCONDITIONAL_TIMER){
                PhoneBase pb =
                    (PhoneBase) PhoneUtils.getImsPhone(PhoneGlobals.getInstance().mCM);
                pb.getCallForwardingOption(reason,
                    obtainMessage(MESSAGE_GET_CF, msg.arg1, MESSAGE_SET_CF, ar.exception));
            } else {
            phone.getCallForwardingOption(reason, mServiceClass,
                    obtainMessage(MESSAGE_GET_CF, msg.arg1, MESSAGE_SET_CF, ar.exception));
            }
        }
    }

    //used to check if timer infor is valid
    private boolean isTimerValid(CallForwardInfo cfinfo) {
        return cfinfo.startHour != 0 || cfinfo.startMinute != 0
                  || cfinfo.endHour != 0 || cfinfo.endMinute != 0;
    }
}
