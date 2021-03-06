package com.habitrpg.android.habitica.ui.activities;

import com.google.android.gms.auth.GoogleAuthException;
import com.google.android.gms.auth.GoogleAuthUtil;
import com.google.android.gms.auth.GooglePlayServicesAvailabilityException;
import com.google.android.gms.auth.UserRecoverableAuthException;
import com.google.android.gms.common.AccountPicker;
import com.google.android.gms.common.GooglePlayServicesUtil;
import com.google.android.gms.common.Scopes;

import com.facebook.AccessToken;
import com.facebook.CallbackManager;
import com.facebook.FacebookCallback;
import com.facebook.FacebookException;
import com.facebook.login.LoginResult;
import com.facebook.login.widget.LoginButton;
import com.habitrpg.android.habitica.APIHelper;
import com.habitrpg.android.habitica.HostConfig;
import com.habitrpg.android.habitica.R;
import com.habitrpg.android.habitica.callbacks.HabitRPGUserCallback;
import com.habitrpg.android.habitica.components.AppComponent;
import com.habitrpg.android.habitica.helpers.AmplitudeManager;
import com.habitrpg.android.habitica.prefs.scanner.IntentIntegrator;
import com.habitrpg.android.habitica.prefs.scanner.IntentResult;
import com.magicmicky.habitrpgwrapper.lib.models.HabitRPGUser;
import com.magicmicky.habitrpgwrapper.lib.models.UserAuthResponse;

import org.json.JSONException;
import org.json.JSONObject;

import android.accounts.AccountManager;
import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.support.design.widget.Snackbar;
import android.support.v7.app.AlertDialog;
import android.support.v7.preference.PreferenceManager;
import android.text.SpannableString;
import android.text.style.UnderlineSpan;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import javax.inject.Inject;

import butterknife.BindView;
import butterknife.ButterKnife;
import butterknife.OnClick;
import rx.Observable;
import rx.exceptions.Exceptions;
import rx.functions.Action1;

/**
 * @author Mickael Goubin
 */
public class LoginActivity extends BaseActivity
        implements Action1<UserAuthResponse>, HabitRPGUserCallback.OnUserReceived {
    static final int REQUEST_CODE_PICK_ACCOUNT = 1000;
    private final static String TAG_ADDRESS = "address";
    private final static String TAG_USERID = "user";
    private final static String TAG_APIKEY = "key";
    private static final int REQUEST_CODE_RECOVER_FROM_PLAY_SERVICES_ERROR = 1001;


    @Inject
    public APIHelper apiHelper;
    @Inject
    public SharedPreferences sharedPrefs;
    @Inject
    public HostConfig hostConfig;
    public String mTmpUserToken;
    public String mTmpApiToken;
    public Boolean isRegistering;
    @BindView(R.id.login_btn)
    Button mLoginNormalBtn;
    @BindView(R.id.PB_AsyncTask)
    ProgressBar mProgressBar;
    @BindView(R.id.username)
    EditText mUsernameET;
    @BindView(R.id.password)
    EditText mPasswordET;
    @BindView(R.id.email)
    EditText mEmail;
    @BindView(R.id.confirm_password)
    EditText mConfirmPassword;
    @BindView(R.id.login_button)
    LoginButton mFacebookLoginBtn;
    @BindView(R.id.forgot_pw_tv)
    TextView mForgotPWTV;
    private Menu menu;
    private CallbackManager callbackManager;
    private String googleEmail;
    private View.OnClickListener mLoginNormalClick = new View.OnClickListener() {
        @Override
        public void onClick(View v) {
            mProgressBar.setVisibility(View.VISIBLE);
            if (isRegistering) {
                String username, email, password, cpassword;
                username = String.valueOf(mUsernameET.getText()).trim();
                email = String.valueOf(mEmail.getText()).trim();
                password = String.valueOf(mPasswordET.getText());
                cpassword = String.valueOf(mConfirmPassword.getText());
                if (username.length() == 0 || password.length() == 0 || email.length() == 0 || cpassword.length() == 0) {
                    showValidationError(R.string.login_validation_error_fieldsmissing);
                    return;
                }
                apiHelper.registerUser(username, email, password, cpassword)
                        .compose(apiHelper.configureApiCallObserver())
                        .subscribe(LoginActivity.this, throwable -> {
                            hideProgress();
                        });
            } else {
                String username, password;
                username = String.valueOf(mUsernameET.getText()).trim();
                password = String.valueOf(mPasswordET.getText());
                if (username.length() == 0 || password.length() == 0) {
                    showValidationError(R.string.login_validation_error_fieldsmissing);
                    return;
                }
                apiHelper.connectUser(username, password)
                        .compose(apiHelper.configureApiCallObserver())
                        .subscribe(LoginActivity.this, throwable -> {
                            hideProgress();
                        });
            }
        }
    };
    private View.OnClickListener mForgotPWClick = v -> {
        String url = hostConfig.getAddress();
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setData(Uri.parse(url));
        startActivity(i);
    };

    public static void show(final View v) {
        v.setVisibility(View.VISIBLE);
    }

    public static void hide(final View v) {
        v.setVisibility(View.GONE);
    }

    @Override
    protected int getLayoutResId() {
        return R.layout.activity_login;
    }

    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        //Set default values to avoid null-responses when requesting unedited settings
        PreferenceManager.setDefaultValues(this, R.xml.preferences_fragment, false);

        ButterKnife.bind(this);

        mLoginNormalBtn.setOnClickListener(mLoginNormalClick);

        mFacebookLoginBtn.setReadPermissions("user_friends");

        mForgotPWTV.setOnClickListener(mForgotPWClick);
        SpannableString content = new SpannableString(mForgotPWTV.getText());
        content.setSpan(new UnderlineSpan(), 0, content.length(), 0);
        mForgotPWTV.setText(content);

        callbackManager = CallbackManager.Factory.create();

        mFacebookLoginBtn.registerCallback(callbackManager, new FacebookCallback<LoginResult>() {
            @Override
            public void onSuccess(LoginResult loginResult) {
                AccessToken accessToken = AccessToken.getCurrentAccessToken();
                apiHelper.connectSocial("facebook", accessToken.getUserId(), accessToken.getToken())
                        .compose(apiHelper.configureApiCallObserver())
                        .subscribe(LoginActivity.this, throwable -> {
                            hideProgress();
                        });
            }

            @Override
            public void onCancel() {
                Log.d("FB Login", "Cancelled");
            }

            @Override
            public void onError(FacebookException exception) {
                Log.e("FB Login", "Error", exception);
            }
        });

        this.isRegistering = true;

        Map<String, Object> additionalData = new HashMap<>();
        additionalData.put("page", this.getClass().getSimpleName());
        AmplitudeManager.sendEvent("navigate", AmplitudeManager.EVENT_CATEGORY_NAVIGATION, AmplitudeManager.EVENT_HITTYPE_PAGEVIEW, additionalData);
    }

    @Override
    protected void injectActivity(AppComponent component) {
        component.inject(this);
    }

    private void resetLayout() {
        if (this.isRegistering) {
            if (this.mEmail.getVisibility() == View.GONE) {
                show(this.mEmail);
            }
            if (this.mConfirmPassword.getVisibility() == View.GONE) {
                show(this.mConfirmPassword);
            }
        } else {
            if (this.mEmail.getVisibility() == View.VISIBLE) {
                hide(this.mEmail);
            }
            if (this.mConfirmPassword.getVisibility() == View.VISIBLE) {
                hide(this.mConfirmPassword);
            }
        }
    }

    private void startMainActivity() {
        Intent intent = new Intent(LoginActivity.this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
        finish();
    }

    private void startSetupActivity() {
        Intent intent = new Intent(LoginActivity.this, SetupActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
        finish();
    }

    private void toggleRegistering() {
        this.isRegistering = !this.isRegistering;
        this.setRegistering();
    }

    private void setRegistering() {
        MenuItem menuItem = menu.findItem(R.id.action_toggleRegistering);
        if (this.isRegistering) {
            this.mLoginNormalBtn.setText(getString(R.string.register_btn));
            menuItem.setTitle(getString(R.string.login_btn));
            mUsernameET.setHint(R.string.username);
            mPasswordET.setImeOptions(EditorInfo.IME_ACTION_NEXT);
        } else {
            this.mLoginNormalBtn.setText(getString(R.string.login_btn));
            menuItem.setTitle(getString(R.string.register_btn));
            mUsernameET.setHint(R.string.email_username);
            mPasswordET.setImeOptions(EditorInfo.IME_ACTION_DONE);
        }
        this.resetLayout();
    }

    public void onActivityResult(int requestCode, int resultCode, Intent intent) {
        super.onActivityResult(requestCode, resultCode, intent);
        callbackManager.onActivityResult(requestCode, resultCode, intent);
        IntentResult scanResult = IntentIntegrator.parseActivityResult(requestCode, resultCode, intent);
        if (scanResult != null) {
            try {
                Log.d("scanresult", scanResult.getContents());
                this.parse(scanResult.getContents());
            } catch (Exception e) {
                Log.e("scanresult", "Could not parse scanResult", e);
            }
        }

        if (requestCode == REQUEST_CODE_PICK_ACCOUNT) {
            if (resultCode == RESULT_OK) {
                googleEmail = intent.getStringExtra(AccountManager.KEY_ACCOUNT_NAME);
                handleGoogleLoginResult();
            }
        }
        if (requestCode == REQUEST_CODE_RECOVER_FROM_PLAY_SERVICES_ERROR) {
            handleGoogleLoginResult();
        }
    }

    private void parse(String contents) {
        String adr, user, key;
        try {
            JSONObject obj;

            obj = new JSONObject(contents);
            adr = obj.getString(TAG_ADDRESS);
            user = obj.getString(TAG_USERID);
            key = obj.getString(TAG_APIKEY);
            Log.d("", "adr" + adr + " user:" + user + " key" + key);
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
            SharedPreferences.Editor editor = prefs.edit();
            boolean ans = editor.putString(getString(R.string.SP_address), adr)
                    .putString(getString(R.string.SP_APIToken), key)
                    .putString(getString(R.string.SP_userID), user)
                    .commit();
            if (!ans) {
                throw new Exception("PB_string_commit");
            }
            startMainActivity();
        } catch (JSONException e) {
            showSnackbar(getString(R.string.ERR_pb_barcode));
            e.printStackTrace();
        } catch (Exception e) {
            if ("PB_string_commit".equals(e.getMessage())) {
                showSnackbar(getString(R.string.ERR_pb_barcode));
            }
        }
    }

    private void showSnackbar(String content) {
        Snackbar snackbar = Snackbar
                .make(this.findViewById(R.id.login_linear_layout), content, Snackbar.LENGTH_LONG);

        View snackbarView = snackbar.getView();
        snackbarView.setBackgroundColor(Color.RED);//change Snackbar's background color;
        snackbar.show(); // Don’t forget to show!
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.login, menu);
        this.menu = menu;
        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.action_toggleRegistering:
                toggleRegistering();
                break;
        }
        return super.onOptionsItemSelected(item);
    }

    private void saveTokens(String api, String user) throws Exception {
        this.apiHelper.updateAuthenticationCredentials(user, api);
        SharedPreferences.Editor editor = sharedPrefs.edit();
        boolean ans = editor.putString(getString(R.string.SP_APIToken), api)
                .putString(getString(R.string.SP_userID), user)
                .commit();
        if (!ans) {
            throw new Exception("PB_string_commit");
        }
    }

    @Override
    public void onUserReceived(HabitRPGUser user) {
        try {
            saveTokens(mTmpApiToken, mTmpUserToken);
        } catch (Exception e) {
            e.printStackTrace();
        }
        this.startMainActivity();
    }

    private void hideProgress() {
        mProgressBar.setVisibility(View.GONE);
    }

    private void showValidationError(int resourceMessageString) {
        mProgressBar.setVisibility(View.GONE);
        new android.support.v7.app.AlertDialog.Builder(this)
                .setTitle(R.string.login_validation_error_title)
                .setMessage(resourceMessageString)
                .setNeutralButton(android.R.string.ok, (dialog, which) -> {
                })
                .setIcon(R.drawable.ic_warning_black)
                .show();
    }

    @Override
    public void call(UserAuthResponse userAuthResponse) {
        try {
            saveTokens(userAuthResponse.getToken(), userAuthResponse.getId());
        } catch (Exception e) {
            e.printStackTrace();
        }

        if (userAuthResponse.getNewUser()) {
            this.startSetupActivity();
        } else {
            AmplitudeManager.sendEvent("login", AmplitudeManager.EVENT_CATEGORY_BEHAVIOUR, AmplitudeManager.EVENT_HITTYPE_EVENT);
            this.startMainActivity();
        }
    }

    @OnClick(R.id.google_login_button)
    public void handleGoogleLogin() {
        String[] accountTypes = new String[]{"com.google"};
        Intent intent = AccountPicker.newChooseAccountIntent(null, null,
                accountTypes, false, null, null, null, null);
        try {
            startActivityForResult(intent, REQUEST_CODE_PICK_ACCOUNT);
        } catch (ActivityNotFoundException e) {
            Dialog dialog = new AlertDialog.Builder(this)
                    .setTitle(R.string.authentication_error_title)
                    .setMessage(R.string.google_services_missing)
                    .setNegativeButton(R.string.close, (dialogInterface, i) -> dialogInterface.dismiss())
                    .create();
            dialog.show();
        }
    }

    private void handleGoogleLoginResult() {
        String scopesString = Scopes.PLUS_LOGIN + " " + Scopes.PROFILE + " " + Scopes.EMAIL;
        String scopes = "oauth2:" + scopesString;
        Observable.defer(() -> {
            try {
                return Observable.just(GoogleAuthUtil.getToken(LoginActivity.this, googleEmail, scopes));
            } catch (IOException | GoogleAuthException e) {
                throw Exceptions.propagate(e);
            }
        })
                .flatMap(token -> apiHelper.connectSocial("google", googleEmail, token))
                .compose(apiHelper.configureApiCallObserver())
                .subscribe(LoginActivity.this, throwable -> {
                    hideProgress();
                    if (throwable.getCause() != null && GoogleAuthException.class.isAssignableFrom(throwable.getCause().getClass())) {
                        handleGoogleAuthException((GoogleAuthException) throwable.getCause());
                    }
                });
    }

    private void handleGoogleAuthException(final Exception e) {
        if (e instanceof GooglePlayServicesAvailabilityException) {
            // The Google Play services APK is old, disabled, or not present.
            // Show a dialog created by Google Play services that allows
            // the user to update the APK
            int statusCode = ((GooglePlayServicesAvailabilityException) e)
                    .getConnectionStatusCode();
            Dialog dialog = GooglePlayServicesUtil.getErrorDialog(statusCode,
                    LoginActivity.this,
                    REQUEST_CODE_RECOVER_FROM_PLAY_SERVICES_ERROR);
            dialog.show();
        } else if (e instanceof UserRecoverableAuthException) {
            // Unable to authenticate, such as when the user has not yet granted
            // the app access to the account, but the user can fix this.
            // Forward the user to an activity in Google Play services.
            Intent intent = ((UserRecoverableAuthException) e).getIntent();
            startActivityForResult(intent,
                    REQUEST_CODE_RECOVER_FROM_PLAY_SERVICES_ERROR);
        }
    }
}
