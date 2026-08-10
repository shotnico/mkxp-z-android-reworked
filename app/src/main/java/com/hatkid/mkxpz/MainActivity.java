package com.hatkid.mkxpz;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.InputDevice;
import android.view.View;
import android.view.ViewGroup;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.Vibrator;
import android.os.VibrationEffect;
import android.os.storage.StorageManager;
import android.os.storage.OnObbStateChangeListener;
import android.net.Uri;
import android.provider.Settings;
import android.util.Log;
import java.util.Locale;
import java.io.File;

import org.libsdl.app.SDLActivity;
import com.hatkid.mkxpz.gamepad.Gamepad;
import com.hatkid.mkxpz.gamepad.GamepadConfig;
import com.hatkid.mkxpz.utils.GameFolder;

public class MainActivity extends SDLActivity
{
    // This activity inherits from SDLActivity activity.
    // Put your Java-side stuff here.

    private static final String TAG = "mkxp-z[Activity]";
    // Fire Ash ITA: cartella dedicata invece della generica "mkxp-z", cosi' l'app
    // trova il gioco senza configurazione e non va in conflitto con altre copie di
    // mkxp-z installate. Il valore viene letto lato nativo via JNI (src/main.cpp).
    //
    // Accettiamo anche la vecchia cartella "mkxp-z": chi ha gia' copiato il gioco
    // li' per provarlo con l'APK generico non deve rinominare 553 MB di file.
    // La risoluzione del percorso e lo scambio della lingua stanno in
    // utils/GameFolder, condivisi con LauncherActivity.
    //
    // ATTENZIONE: va risolto da runSDLThread(), NON da un inizializzatore statico:
    // prima che l'utente conceda "Accesso a tutti i file" ogni isDirectory() su
    // /sdcard ritorna false, e il risultato resterebbe congelato sbagliato.
    private static final String GAME_PATH_DEFAULT =
        Environment.getExternalStorageDirectory() + "/FireAshITA";
    private static String GAME_PATH = GAME_PATH_DEFAULT;
    private static String OBB_MAIN_FILENAME;
    private static boolean DEBUG = false;

    protected boolean mStarted = false;

    private StorageManager mStorageManager;

    // In-screen gamepad
    private final Gamepad mGamepad = new Gamepad();
    private GamepadConfig mGamepadConfig;
    private boolean mGamepadInvisible = false;

    // Schermata di caricamento sopra la superficie di gioco
    private static final long LOADING_OVERLAY_MS = 95000;   // ~85 s di avvio + margine
    private View mLoadingOverlay;

    private void runSDLThread()
    {
        if (!mStarted) {
            // Risolto qui e non prima: a questo punto il permesso di accesso allo
            // storage e' stato concesso, quindi le cartelle sono davvero visibili.
            // Se un OBB e' stato montato, GAME_PATH e' gia' stato impostato dal
            // listener e non va sovrascritto.
            if (GAME_PATH.equals(GAME_PATH_DEFAULT)) {
                GAME_PATH = GameFolder.resolve();
            }
            Log.i(TAG, "Game path: " + GAME_PATH);
        }

        mStarted = true;

        // Run (resume) native SDL thread
        if (mHasMultiWindow) {
            resumeNativeThread();
        }
    }

    OnObbStateChangeListener obbListener = new OnObbStateChangeListener()
    {
        @Override
        public void onObbStateChange(String path, int state)
        {
            super.onObbStateChange(path, state);

            Log.v(TAG, "OBB state of " + path + " changed to " + state);

            switch (state)
            {
                case OnObbStateChangeListener.MOUNTED:
                    String obbPath = mStorageManager.getMountedObbPath(path);
                    Log.v(TAG, "OBB " + path + " is mounted to " + obbPath);
                    GAME_PATH = obbPath;
                    break;

                case OnObbStateChangeListener.UNMOUNTED:
                    Log.v(TAG, "OBB " + path + " is unmounted");
                    GAME_PATH = GAME_PATH_DEFAULT;
                    break;

                default:
                    Log.e(TAG, "Failed to mount OBB " + path + ": Got state " + state);
                    break;
            }

            runSDLThread();
        }
    };

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data)
    {
        if (requestCode == 110) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
                // Close the App because the User did not allow the all files access permission to be used.
                mSingleton.finish();
            }
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);

        mStorageManager = (StorageManager) getSystemService(STORAGE_SERVICE);

        // Get main OBB filepath
        final String obbPrefix = "main"; // "main", "patch"
        final int obbVersion = 1;
        OBB_MAIN_FILENAME = getObbDir() + "/" + obbPrefix + "." + obbVersion + "." + getPackageName() + ".obb";

        // Get Debug flag
        try {
            ActivityInfo actInfo = getPackageManager().getActivityInfo(this.getComponentName(), PackageManager.GET_META_DATA);
            DEBUG = actInfo.metaData.getBoolean("mkxp_debug");
        } catch (PackageManager.NameNotFoundException e) {
            Log.w(TAG, "Failed to set debug flag: " + e);
            e.printStackTrace();
        }

        // Check for all files access permission (Android 11+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                // Request all files access permission
                // TODO: AlertDialog: polite notice that mkxp-z requires All Files Access permission.
                Uri uri = Uri.parse("package:" + getPackageName());
                Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, uri);
                startActivityForResult(intent, 110);
            }
        }

        // Orientamento scelto nella schermata iniziale. In verticale mkxp-z tiene
        // il gioco 4:3 centrato e i tasti stanno nella banda bassa (stile Game Boy):
        // vedi res/layout-port/gamepad_layout.xml.
        applyOrientationPreference();

        // Setup in-screen gamepad
        mGamepadInvisible = (isAndroidTV() || isChromebook());
        // Opacita' e dimensione arrivano da SharedPreferences, cosi' si regolano dal
        // telefono senza ricompilare. Il valore originale del port era 45, cioe'
        // alpha 115 su 255: i tasti sparivano con un po' di luce.
        mGamepadConfig = GamepadConfig.load(this);
        mGamepad.init(mGamepadConfig, mGamepadInvisible);
        mGamepad.setOnKeyDownListener(SDLActivity::onNativeKeyDown);
        mGamepad.setOnKeyUpListener(SDLActivity::onNativeKeyUp);

        if (mLayout != null) {
            mGamepad.attachTo(this, mLayout);
            showLoadingOverlay();
        }
    }

    private void applyOrientationPreference()
    {
        int orient = getSharedPreferences(GamepadConfig.PREFS, MODE_PRIVATE)
                        .getInt(GamepadConfig.KEY_ORIENTATION, GamepadConfig.ORIENT_AUTO);

        if (orient == GamepadConfig.ORIENT_LANDSCAPE)
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        else if (orient == GamepadConfig.ORIENT_PORTRAIT)
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT);
        else
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR);
    }

    /**
     * Schermata di caricamento sopra la superficie di gioco.
     *
     * Serve perche' l'avvio richiede ~85 s, di cui ~80 in cui mkxp-z non presenta
     * ancora nulla: misurato che in quella fase non compare NIENTE, nemmeno un
     * rettangolo disegnato dal gioco a z=999999. Le View Android invece si vedono,
     * perche' non passano dalla presentazione di mkxp-z -- e' l'unico modo di
     * mostrare qualcosa durante l'attesa.
     *
     * Non sappiamo dall'esterno quando il gioco inizia a presentare, quindi la
     * schermata si toglie al tocco oppure da sola dopo il tempo tipico.
     */
    private void showLoadingOverlay()
    {
        try {
            mLoadingOverlay = getLayoutInflater().inflate(R.layout.loading_overlay, mLayout, false);
            mLayout.addView(mLoadingOverlay);   // aggiunta per ultima = sopra a tutto

            View.OnClickListener dismiss = new View.OnClickListener() {
                @Override public void onClick(View v) { hideLoadingOverlay(); }
            };
            mLoadingOverlay.setOnClickListener(dismiss);
            View skip = mLoadingOverlay.findViewById(R.id.loading_skip);
            if (skip != null)
                skip.setOnClickListener(dismiss);

            new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                @Override public void run() { hideLoadingOverlay(); }
            }, LOADING_OVERLAY_MS);
        } catch (Exception e) {
            Log.w(TAG, "Loading overlay non mostrata: " + e);
        }
    }

    private void hideLoadingOverlay()
    {
        if (mLoadingOverlay == null)
            return;

        if (mLoadingOverlay.getParent() instanceof ViewGroup)
            ((ViewGroup) mLoadingOverlay.getParent()).removeView(mLoadingOverlay);

        mLoadingOverlay = null;
    }

    /**
     * L'activity dichiara configChanges="orientation|screenSize", quindi NON viene
     * ricreata alla rotazione: senza questo il layout orizzontale resterebbe anche
     * in verticale. Si stacca e si riattacca, cosi' Android rigonfia la risorsa
     * giusta (res/layout-port oppure res/layout).
     */
    @Override
    public void onConfigurationChanged(Configuration newConfig)
    {
        super.onConfigurationChanged(newConfig);

        if (mLayout == null)
            return;

        try {
            mGamepad.detach();
            mGamepad.attachTo(this, mLayout);
            if (mGamepadInvisible)
                mGamepad.hideView();
        } catch (Exception e) {
            Log.w(TAG, "Rotazione: gamepad non riattaccato: " + e);
        }
    }

    @Override
    protected void onStart()
    {
        super.onStart();

        if (!mStarted) {
            // Check for main OBB file
            if (new File(OBB_MAIN_FILENAME).exists()) {
                Log.v(TAG, "Main OBB file found, starting with main OBB mount");

                // Try to mount main OBB file
                mStorageManager.mountObb(OBB_MAIN_FILENAME, null, obbListener);
            } else {
                Log.v(TAG, "Main OBB file not found, starting without main OBB mount");

                // Run from default game directory
                runSDLThread();
            }
        } else {
            // onStart: Resume SDL thread
            runSDLThread();
        }
    }

    @Override
    protected void onDestroy()
    {
        super.onDestroy();

        // HACK: Exiting the JVM (process) since Ruby does not likes when we
        // trying to re-initialize Ruby VM in mkxp-z (JNI native library)
        // that leads to segmentation fault, even we have cleanup the Ruby VM.
        System.exit(0);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent evt)
    {
        if (
            evt.getKeyCode() != KeyEvent.KEYCODE_BACK &&
            evt.getKeyCode() != KeyEvent.KEYCODE_VOLUME_UP &&
            evt.getKeyCode() != KeyEvent.KEYCODE_VOLUME_DOWN &&
            evt.getKeyCode() != KeyEvent.KEYCODE_VOLUME_MUTE
        ) {
            // Hide gamepad view on key events when visible
            if (!mGamepadInvisible) {
                mGamepad.hideView();
                mGamepadInvisible = true;
            }
        }

        if (mGamepad.processGamepadEvent(evt))
            return true;

        return super.dispatchKeyEvent(evt);
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent evt)
    {
        // Show gamepad view on touch when hidden
        if (mGamepadInvisible) {
            mGamepad.showView();
            mGamepadInvisible = false;
        }

        return super.dispatchTouchEvent(evt);
    }

    @Override
    public boolean onGenericMotionEvent(MotionEvent evt)
    {
        if (mGamepad.processDPadEvent(evt))
            return true;

        return super.onGenericMotionEvent(evt);
    }

    /**
     * This method is for arguments for launching native mkxp-z.
     * 
     * @return arguments for the mkxp-z
     */
    @Override
    protected String[] getArguments()
    {
        String[] args;

        if (DEBUG) {
            // Arguments in Debug mode
            args = new String[] { "debug" };
        } else {
            // Arguments in normal mode
            args = new String[] {};
        }

        return args;
    }

    /**
     * This static method is used in native mkxp-z. (see systemImpl.cpp)
     * This method returns a string of current device locale tag. (e.g. "en_US")
     * 
     * @return string of locale tag
     */
    @SuppressWarnings("unused")
    private static String getSystemLanguage()
    {
        return Locale.getDefault().toString();
    }

    /**
     * This static method is used in native mkxp-z. (see android-binding.cpp)
     * This method returns a boolean indicating that the device has a vibrator or not.
     * 
     * @return boolean
     */
    @SuppressWarnings("unused")
    private static boolean hasVibrator()
    {
        Vibrator vib = (Vibrator) getContext().getSystemService(Context.VIBRATOR_SERVICE);
        return vib.hasVibrator();
    }

    /**
     * This static method is used in native mkxp-z. (see android-binding.cpp)
     * This method makes device vibrating with given milliseconds duration.
     * 
     * @param duration milliseconds duration of vibration
     */
    @SuppressWarnings("unused")
    private static void vibrate(int duration)
    {
        Vibrator vib = (Vibrator) getContext().getSystemService(Context.VIBRATOR_SERVICE);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vib.vibrate(VibrationEffect.createOneShot(duration, VibrationEffect.EFFECT_HEAVY_CLICK));
        } else {
            vib.vibrate(duration);
        }
    }

    /**
     * This static method is used in native mkxp-z. (see android-binding.cpp)
     * This method turns off the current device vibration.
     */
    @SuppressWarnings("unused")
    private static void vibrateStop()
    {
        Vibrator vib = (Vibrator) getContext().getSystemService(Context.VIBRATOR_SERVICE);
        vib.cancel();
    }

    /**
     * This static method is used in native mkxp-z. (see android-binding.cpp)
     * This method returns a boolean indicating the app is in multi window mode or not.
     * (Multi-window mode supports from Android 7.0 Nougat (API 24) and higher.)
     * 
     * @param activity current MainActivity instance
     * @return boolean
     */
    @SuppressWarnings("unused")
    private static boolean inMultiWindow(Activity activity)
    {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && activity.isInMultiWindowMode();
    }
}