package com.hatkid.mkxpz;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.widget.RadioButton;
import android.widget.RelativeLayout;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
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
    // utils/GameFolder.
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

    // Schermata iniziale (schermata_iniziale.png): quanto resta a schermo prima
    // di lasciare il posto alle impostazioni. Non aggiunge attesa: il gioco sta
    // caricando sotto per tutto questo tempo.
    private static final long SPLASH_MS = 4500;
    private static final long SPLASH_FADE_MS = 600;
    private View mSplash;

    // Frazione della larghezza occupata dal pannello impostazioni in orizzontale.
    // Meno di 1 perche' i tasti ai lati devono restare visibili: sono l'anteprima.
    private static final float OVERLAY_WIDTH_LAND = 0.60f;

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
            applySurfaceLayout();          // in verticale mette il gioco in alto
            mGamepad.attachTo(this, mLayout);
            showLoadingOverlay();
            // La schermata iniziale va aggiunta DOPO l'overlay, cosi' resta lei
            // davanti per i primi secondi. L'ordine dei figli e' l'ordine di
            // disegno: l'ultimo aggiunto sta sopra.
            showSplash();
        }
    }

    /**
     * Mostra schermata_iniziale.png per qualche secondo, poi la sfuma via
     * lasciando le impostazioni.
     *
     * Non allunga l'avvio di un secondo: il thread SDL sta gia' caricando il gioco
     * mentre l'immagine e' a schermo. Copre la parte di attesa in cui prima si
     * vedeva solo nero.
     */
    private void showSplash()
    {
        try {
            mSplash = getLayoutInflater().inflate(R.layout.splash_screen, mLayout, false);
            mLayout.addView(mSplash);

            // Un tocco la salta: se uno ha gia' visto l'immagine non deve subirla.
            mSplash.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { hideSplash(); }
            });

            new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                @Override public void run() { hideSplash(); }
            }, SPLASH_MS);
        } catch (Exception e) {
            Log.w(TAG, "Schermata iniziale non mostrata: " + e);
        }
    }

    private void hideSplash()
    {
        final View s = mSplash;
        if (s == null)
            return;
        mSplash = null;                      // niente doppie chiamate da tocco + timer

        s.animate().alpha(0f).setDuration(SPLASH_FADE_MS)
         .withEndAction(new Runnable() {
             @Override public void run() {
                 if (s.getParent() instanceof ViewGroup)
                     ((ViewGroup) s.getParent()).removeView(s);
             }
         }).start();
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
     * QUESTO E' IL MOTIVO PER CUI IL VERTICALE NON FUNZIONAVA.
     *
     * SDL, dopo aver creato la finestra, chiama setOrientation() dal lato nativo e
     * impone lui l'orientamento, sovrascrivendo quello che aveva chiesto l'activity.
     * Nel log si vedeva:
     *
     *     setOrientation() requestedOrientation=6 ... hint=LandscapeLeft LandscapeRight
     *
     * cioe' SCREEN_ORIENTATION_SENSOR_LANDSCAPE: mkxp-z dichiara a SDL un hint di
     * sole orientazioni orizzontali (la finestra e' 512x384, piu' larga che alta), e
     * il ramo "resizable con un solo orientamento permesso" di setOrientationBis
     * forza l'orizzontale. La scelta dell'utente veniva quindi annullata pochi
     * istanti dopo essere stata applicata.
     *
     * SDLActivity documenta questo metodo come sovrascrivibile ("This can be
     * overridden"), quindi qui facciamo vincere la preferenza dell'utente.
     */
    @Override
    public void setOrientationBis(int w, int h, boolean resizable, String hint)
    {
        int orient = getSharedPreferences(GamepadConfig.PREFS, MODE_PRIVATE)
                        .getInt(GamepadConfig.KEY_ORIENTATION, GamepadConfig.ORIENT_AUTO);

        if (orient == GamepadConfig.ORIENT_PORTRAIT) {
            Log.i(TAG, "setOrientationBis: forzo il verticale su richiesta dell'utente");
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT);
            return;
        }
        if (orient == GamepadConfig.ORIENT_LANDSCAPE) {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
            return;
        }
        // Automatico: SDL qui imporrebbe comunque il solo orizzontale, per via
        // dell'hint. Lasciamo entrambi gli orientamenti, cosi' ruotare funziona.
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR);
    }

    /**
     * In verticale mette il gioco IN ALTO invece che centrato.
     *
     * mkxp-z con fixedAspectRatio tiene il 4:3 centrato nella superficie, quindi
     * in verticale restavano due bande nere uguali sopra e sotto e il gioco stava
     * in mezzo. Qui la superficie SDL viene ridotta a esattamente 4:3 della
     * larghezza e ancorata in cima: il gioco la riempie tutta, sta in alto, e
     * tutto lo spazio che resta finisce sotto, dove ci sono i comandi. Come un
     * Game Boy.
     *
     * SDLActivity crea mLayout come RelativeLayout e vi aggiunge mSurface senza
     * parametri espliciti, quindi possiamo imporli noi. Cambiare la dimensione
     * della superficie provoca un surfaceChanged, che SDL e mkxp-z gestiscono da
     * soli: essendo la superficie gia' 4:3, non c'e' piu' niente da centrare.
     *
     * In orizzontale si torna a schermo pieno.
     */
    private void applySurfaceLayout()
    {
        if (mLayout == null || mSurface == null)
            return;

        try {
            boolean verticale = getResources().getConfiguration().orientation
                                == Configuration.ORIENTATION_PORTRAIT;

            RelativeLayout.LayoutParams p;
            if (verticale) {
                int w = getResources().getDisplayMetrics().widthPixels;
                p = new RelativeLayout.LayoutParams(
                        RelativeLayout.LayoutParams.MATCH_PARENT, w * 3 / 4);
                p.addRule(RelativeLayout.ALIGN_PARENT_TOP);
            } else {
                p = new RelativeLayout.LayoutParams(
                        RelativeLayout.LayoutParams.MATCH_PARENT,
                        RelativeLayout.LayoutParams.MATCH_PARENT);
            }
            mSurface.setLayoutParams(p);
        } catch (Exception e) {
            Log.w(TAG, "Layout della superficie non applicato: " + e);
        }
    }

    /**
     * Limita il pannello delle impostazioni, cosi' i tasti restano visibili.
     *
     * Il pannello copriva tutto lo schermo, quindi regolare l'opacita' era un
     * atto di fede: il valore cambiava davvero, ma nascosto sotto il pannello.
     * Ora il pannello occupa solo la zona del gioco e i tasti restano fuori:
     *
     *   verticale   -> la fascia ALTA, esattamente quanto la superficie di gioco
     *                  (larghezza x 3/4). I tasti sono nella fascia bassa, liberi.
     *   orizzontale -> una colonna centrale al 60% della larghezza. I tasti stanno
     *                  ai due lati, quindi restano scoperti.
     */
    private void applyOverlayLayout()
    {
        if (mLayout == null || mLoadingOverlay == null)
            return;

        try {
            boolean verticale = getResources().getConfiguration().orientation
                                == Configuration.ORIENTATION_PORTRAIT;
            int w = getResources().getDisplayMetrics().widthPixels;

            RelativeLayout.LayoutParams p;
            if (verticale) {
                p = new RelativeLayout.LayoutParams(
                        RelativeLayout.LayoutParams.MATCH_PARENT, w * 3 / 4);
                p.addRule(RelativeLayout.ALIGN_PARENT_TOP);
            } else {
                p = new RelativeLayout.LayoutParams(
                        Math.round(w * OVERLAY_WIDTH_LAND),
                        RelativeLayout.LayoutParams.MATCH_PARENT);
                p.addRule(RelativeLayout.CENTER_HORIZONTAL);
            }
            mLoadingOverlay.setLayoutParams(p);
        } catch (Exception e) {
            Log.w(TAG, "Layout del pannello impostazioni non applicato: " + e);
        }
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
            applyOverlayLayout();               // non copre i tasti: sono l'anteprima

            // I tasti si vedono e reagiscono al tocco, ma non arrivano al gioco:
            // il gioco sta caricando e un tocco a caso sulla schermata del titolo
            // potrebbe far partire una partita.
            mGamepad.setInputEnabled(false);

            final SharedPreferences prefs = getSharedPreferences(GamepadConfig.PREFS, MODE_PRIVATE);

            // --- ENTRA NEL GIOCO ---------------------------------------------
            View enter = mLoadingOverlay.findViewById(R.id.loading_skip);
            if (enter != null) {
                enter.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) { applySettingsAndEnter(); }
                });
            }

            // --- opacita' e dimensione ---------------------------------------
            final SeekBar op = mLoadingOverlay.findViewById(R.id.seek_opacity);
            final SeekBar sc = mLoadingOverlay.findViewById(R.id.seek_scale);
            op.setProgress(mGamepadConfig.opacity);
            sc.setProgress(mGamepadConfig.scale);
            updateOverlayLabels(op.getProgress(), sc.getProgress());

            SeekBar.OnSeekBarChangeListener l = new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar s, int v, boolean fromUser) {
                    updateOverlayLabels(op.getProgress(), sc.getProgress());
                    // ANTEPRIMA IN DIRETTA dell'opacita': si vede sui tasti veri,
                    // che il pannello lascia scoperti, mentre si trascina.
                    //
                    // Solo l'opacita', non la dimensione: ViewUtils.changeOpacity
                    // imposta un valore assoluto ed e' quindi ripetibile, mentre
                    // ViewUtils.resize MOLTIPLICA i parametri di layout attuali e
                    // chiamata a ogni pixel di trascinamento rimpicciolirebbe i
                    // tasti fino a farli sparire. La dimensione si applica al
                    // rilascio, ricostruendo i controlli.
                    if (s == op)
                        mGamepad.applyOpacity(op.getProgress());
                }
                @Override public void onStartTrackingTouch(SeekBar s) { }
                @Override public void onStopTrackingTouch(SeekBar s) {
                    prefs.edit().putInt(GamepadConfig.KEY_OPACITY, op.getProgress())
                                .putInt(GamepadConfig.KEY_SCALE, sc.getProgress())
                                .apply();
                    if (s == sc)
                        reloadGamepad();     // la dimensione richiede la ricostruzione
                    else
                        mGamepad.applyOpacity(op.getProgress());
                }
            };
            op.setOnSeekBarChangeListener(l);
            sc.setOnSeekBarChangeListener(l);

            // --- orientamento -------------------------------------------------
            int orient = prefs.getInt(GamepadConfig.KEY_ORIENTATION, GamepadConfig.ORIENT_AUTO);
            ((RadioButton) mLoadingOverlay.findViewById(
                orient == GamepadConfig.ORIENT_LANDSCAPE ? R.id.orient_land
              : orient == GamepadConfig.ORIENT_PORTRAIT  ? R.id.orient_port
              : R.id.orient_auto)).setChecked(true);

            ((RadioGroup) mLoadingOverlay.findViewById(R.id.group_orient))
                .setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
                    @Override public void onCheckedChanged(RadioGroup g, int id) {
                        int o = (id == R.id.orient_land) ? GamepadConfig.ORIENT_LANDSCAPE
                              : (id == R.id.orient_port) ? GamepadConfig.ORIENT_PORTRAIT
                              : GamepadConfig.ORIENT_AUTO;
                        prefs.edit().putInt(GamepadConfig.KEY_ORIENTATION, o).apply();
                        applyOrientationPreference();
                        // la rotazione effettiva arriva poco dopo: riapplichiamo
                        // il layout della superficie quando e' avvenuta
                        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                            @Override public void run() { applySurfaceLayout(); }
                        }, 600);
                    }
                });

            // --- lingua -------------------------------------------------------
            String lang = prefs.getString(GamepadConfig.KEY_LANGUAGE, GameFolder.LANG_IT);
            ((RadioButton) mLoadingOverlay.findViewById(
                GameFolder.LANG_EN.equals(lang) ? R.id.lang_en : R.id.lang_it)).setChecked(true);

            ((RadioGroup) mLoadingOverlay.findViewById(R.id.group_lang))
                .setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
                    @Override public void onCheckedChanged(RadioGroup g, int id) {
                        String cur = prefs.getString(GamepadConfig.KEY_LANGUAGE, GameFolder.LANG_IT);
                        String want = (id == R.id.lang_en) ? GameFolder.LANG_EN : GameFolder.LANG_IT;
                        if (want.equals(cur))
                            return;
                        // Il gioco sta gia' leggendo Data/: lo scambio vale dal
                        // prossimo avvio, non da adesso.
                        if (GameFolder.switchTo(cur, want)) {
                            prefs.edit().putString(GamepadConfig.KEY_LANGUAGE, want).apply();
                            Toast.makeText(MainActivity.this, R.string.lang_switched, Toast.LENGTH_LONG).show();
                        } else {
                            Toast.makeText(MainActivity.this, R.string.lang_missing, Toast.LENGTH_LONG).show();
                            ((RadioButton) mLoadingOverlay.findViewById(
                                GameFolder.LANG_EN.equals(cur) ? R.id.lang_en : R.id.lang_it)).setChecked(true);
                        }
                    }
                });

            // Passato il tempo tipico di caricamento, cambia il messaggio invece di
            // far sparire tutto: chi sta ancora regolando i tasti non viene buttato
            // dentro al gioco a tradimento.
            new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                @Override public void run() {
                    TextView st = (mLoadingOverlay != null)
                                ? (TextView) mLoadingOverlay.findViewById(R.id.loading_state) : null;
                    if (st != null)
                        st.setText(R.string.loading_ready);
                }
            }, LOADING_OVERLAY_MS);
        } catch (Exception e) {
            Log.w(TAG, "Loading overlay non mostrata: " + e);
        }
    }

    private void updateOverlayLabels(int opacity, int scale)
    {
        if (mLoadingOverlay == null)
            return;
        TextView a = mLoadingOverlay.findViewById(R.id.label_opacity);
        TextView b = mLoadingOverlay.findViewById(R.id.label_scale);
        if (a != null) a.setText(getString(R.string.opacity_value, opacity));
        if (b != null) b.setText(getString(R.string.scale_value, scale));
    }

    /** Ricostruisce i controlli a schermo con i valori salvati. */
    private void reloadGamepad()
    {
        if (mLayout == null)
            return;
        try {
            mGamepadConfig = GamepadConfig.load(this);
            mGamepad.init(mGamepadConfig, mGamepadInvisible);
            mGamepad.detach();
            mGamepad.attachTo(this, mLayout);
            // attachTo ricostruisce i tasti da zero, quindi il blocco dell'input
            // va rimesso: senza questo, muovere il cursore della dimensione
            // riabilitava i tasti mentre le impostazioni erano ancora aperte.
            mGamepad.setInputEnabled(mLoadingOverlay == null);
            // l'overlay deve restare sopra i controlli appena riattaccati
            if (mLoadingOverlay != null)
                mLoadingOverlay.bringToFront();
            if (mSplash != null)
                mSplash.bringToFront();
        } catch (Exception e) {
            Log.w(TAG, "Gamepad non ricostruito: " + e);
        }
    }

    private void applySettingsAndEnter()
    {
        reloadGamepad();
        hideLoadingOverlay();
    }

    private void hideLoadingOverlay()
    {
        if (mLoadingOverlay == null)
            return;

        if (mLoadingOverlay.getParent() instanceof ViewGroup)
            ((ViewGroup) mLoadingOverlay.getParent()).removeView(mLoadingOverlay);

        mLoadingOverlay = null;

        // da qui i tasti comandano il gioco
        mGamepad.setInputEnabled(true);
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
            applySurfaceLayout();          // la superficie va rimessa a posto per il nuovo orientamento
            applyOverlayLayout();          // e anche il pannello: cambia zona e misura
            mGamepad.detach();
            mGamepad.attachTo(this, mLayout);
            mGamepad.setInputEnabled(mLoadingOverlay == null);
            if (mGamepadInvisible)
                mGamepad.hideView();
            // Riattaccare il gamepad lo rimette come ultimo figlio, quindi sopra
            // all'overlay: dopo una rotazione i tasti finivano disegnati sopra le
            // impostazioni. L'overlay va riportato davanti.
            if (mLoadingOverlay != null)
                mLoadingOverlay.bringToFront();
            if (mSplash != null)
                mSplash.bringToFront();
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