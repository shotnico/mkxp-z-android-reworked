package com.hatkid.mkxpz;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.widget.ImageView;
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
import android.view.WindowManager;
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

    // Pannello delle impostazioni, aperto in gioco dal tasto con l'ingranaggio.
    // Non c'e' piu' una schermata di attesa all'avvio: serviva quando il gioco
    // impiegava oltre un minuto a partire, adesso e' pronto in pochi secondi.
    private View mPannello;
    private ImageView mBottoneImpostazioni;

    // Sfondo decorativo attorno alla scena di gioco (effetto console portatile).
    // @drawable/bg_console, in due versioni scelte da Android in base
    // all'orientamento: verticale in res/drawable-nodpi/, orizzontale in
    // res/drawable-land-nodpi/.
    private ImageView mSfondo;

    // Schermata iniziale: quanto resta a schermo. Non aggiunge attesa, il gioco
    // sta caricando sotto per tutto questo tempo.
    // L'immagine e' @drawable/schermata_iniziale e ne esistono DUE versioni,
    // scelte da Android in base all'orientamento: quella verticale in
    // res/drawable-nodpi/ e quella orizzontale in res/drawable-land-nodpi/.
    private static final long SPLASH_MS = 4500;
    private static final long SPLASH_FADE_MS = 600;
    private View mSplash;

    // Frazione della larghezza occupata dal pannello impostazioni in orizzontale.
    // Meno di 1 perche' i tasti ai lati devono restare visibili: sono l'anteprima.
    // 0,50 e non 0,60: a 0,60 il pannello arrivava sopra al tasto CORSA e sopra
    // SALVA/SPEED, che risultavano tagliati (verificato a schermo).
    private static final float OVERLAY_WIDTH_LAND = 0.50f;

    // In verticale il pannello lascia libera la fascia BASSA, quella dei tasti.
    // Misurato sul telefono (1080x2340): i tasti stanno da y=1697 a y=2178, cioe'
    // l'ultimo 27% dell'altezza. Il pannello prende il resto.
    //
    // Prima il pannello era alto quanto la sola zona del gioco (larghezza x 3/4 =
    // 810 px su 2340): i tasti si vedevano, ma nel pannello restavano fuori
    // Orientamento e Lingua, raggiungibili solo scorrendo. Le voci non erano
    // nemmeno visibili nella gerarchia delle view, che e' come me ne sono accorto.
    private static final float OVERLAY_HEIGHT_PORT = 0.72f;

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

        // L'app segue SEMPRE il telefono: girarlo passa in verticale, rimetterlo
        // di lato torna orizzontale. Vedi applyOrientationPreference per il
        // motivo per cui la scelta manuale e' stata rimossa.
        disegnaSottoIlForo();
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
            addSfondo();                   // va aggiunto PRIMA di tutto il resto
            mGamepad.attachTo(this, mLayout);
            // Il tasto dell'ingranaggio resta a schermo per tutta la partita: le
            // impostazioni si aprono da li'. Aggiunto prima della schermata
            // iniziale, che deve stare davanti a tutto nei primi secondi.
            addPulsanteImpostazioni();
            showSplash();
        }
    }

    /**
     * Sfondo decorativo attorno alla scena: effetto console portatile.
     *
     * PERCHE' FUNZIONA. La scena di gioco e' una SurfaceView, e una SurfaceView
     * non viene disegnata dentro la finestra: sta SOTTO, e la finestra viene resa
     * trasparente nella sua area. Di conseguenza tutto quello che sta dietro di
     * lei nella gerarchia -- come questa immagine, aggiunta come PRIMO figlio --
     * si vede soltanto FUORI dalla zona del gioco. La scena non puo' mai essere
     * coperta dal disegno, qualunque cosa il disegno contenga in quell'area.
     * E' anche il motivo per cui l'immagine orizzontale funziona pur avendo il
     * pannello destro che sborda di 42 px dentro la zona di gioco: quei pixel
     * finiscono dietro la scena e non si vedono.
     *
     * COME VIENE POSIZIONATA. Le immagini sono disegnate a schermo pieno
     * (1080x2340 e 2340x1080, o comunque nelle stesse proporzioni), quindi vanno
     * mappate sullo SCHERMO, non sul layout: il layout parte sotto la barra di
     * stato, e usare le sue misure sposterebbe tutto in alto di ~100 px. Percio'
     * la view viene dimensionata quanto lo schermo intero e spostata indietro dei
     * margini di sistema, che si leggono a runtime con getLocationOnScreen. La
     * parte che finisce sotto la barra di stato viene tagliata dal layout.
     */
    /**
     * Disegna anche nella striscia riservata al foro della fotocamera.
     *
     * Misurato su questo telefono: il sistema tiene fuori dalla finestra 98 px in
     * alto in verticale e 96 px a sinistra in orizzontale. E' la stessa riserva,
     * ruotata. Quelle strisce restavano nere e nessun disegno poteva coprirle,
     * perche' stavano FUORI dal layout: lo sfondo viene ritagliato dal layout, e
     * il layout non ci arrivava. Con SHORT_EDGES la finestra le include e lo
     * sfondo arriva ai bordi.
     *
     * E' la causa unica delle due bande nere che si vedevano: quella in cima in
     * verticale e quella a sinistra in orizzontale. Se il sistema ignorasse la
     * richiesta, si torna al comportamento di prima senza rompere niente.
     */
    private void disegnaSottoIlForo()
    {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P)
            return;
        try {
            WindowManager.LayoutParams a = getWindow().getAttributes();
            a.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            getWindow().setAttributes(a);
        } catch (Exception e) {
            Log.w(TAG, "Area del foro non abilitata: " + e);
        }
    }

    /**
     * Di quanto scende la scena dal bordo alto dello schermo, in verticale.
     *
     * Serve a fare spazio alla cornice superiore disegnata nello sfondo. Il valore
     * e' una FRAZIONE dell'altezza dello schermo e non un numero fisso di pixel,
     * perche' lo sfondo viene stirato sull'altezza reale (FIT_XY): il bordo alto
     * della scena e il bordo basso della cornice disegnata devono cadere sulla
     * stessa frazione, altrimenti ricompare una striscia nera.
     *
     * Il numero NON viene dal prompt ma dall'immagine vera, misurata al pixel: il
     * rettangolo nero di bg_console.png (852x1846) comincia alla riga 153 e finisce
     * alla 772. Il generatore non rispetta le quote al decimale -- il prompt
     * chiedeva 10,3% e ha prodotto 8,3% -- e sono i 2 punti di differenza che
     * farebbero ricomparire una striscia nera fra cornice e scena. Adattare il
     * codice all'immagine e' l'unico verso che funziona: l'immagine e' fissa.
     *
     * 153/1846 = 8,288% -> su 2340 px la scena parte a 194 e finisce a 1004, cioe'
     * 25 px oltre il punto in cui il disegno smette di essere nero (979). Quei
     * 25 px di scocca finiscono DIETRO la scena e non si vedono: e' il verso
     * innocuo dell'errore. Al contrario si vedrebbe del nero.
     *
     * ATTENZIONE alla fonte delle misure: va usata quella REALE dello schermo, la
     * stessa con cui viene dimensionato lo sfondo. getResources() qui riporta
     * 2111 e non 2340, e con quella la scena partiva da 175 invece che da 194:
     * 19 px di nero fra cornice e gioco. Due misure diverse per la stessa cosa
     * sono un errore anche quando entrambe "sembrano" l'altezza dello schermo.
     */
    private int cornicePx()
    {
        android.util.DisplayMetrics dm = new android.util.DisplayMetrics();
        getWindowManager().getDefaultDisplay().getRealMetrics(dm);
        return Math.round(dm.heightPixels * 153f / 1846f);
    }

    private void addSfondo()
    {
        try {
            mSfondo = new ImageView(this);
            mSfondo.setScaleType(ImageView.ScaleType.FIT_XY);
            mSfondo.setImageResource(R.drawable.bg_console);
            mLayout.addView(mSfondo, 0);   // primo figlio = dietro a tutto
            // Ogni volta che il layout cambia posizione o misura, sfondo e scena si
            // riallineano. Serve perche' la posizione del layout sullo schermo
            // cambia DOPO onCreate: la richiesta di disegnare sotto il foro della
            // fotocamera viene applicata dal sistema in un secondo giro di layout.
            // Leggendola una volta sola si prendeva il valore vecchio (97 px) e lo
            // sfondo restava spostato in alto di 97 px: da cui la striscia nera fra
            // cornice e gioco. Le guardie di idempotenza dentro allineaSfondo e
            // applicaMisureSuperficie evitano che il nuovo setLayoutParams inneschi
            // un ciclo di layout senza fine.
            mLayout.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
                @Override public void onLayoutChange(View v, int l, int t, int r, int b,
                                                    int vl, int vt, int vr, int vb) {
                    allineaSfondo();
                    applicaMisureSuperficie();
                }
            });
            posizionaSfondo();
        } catch (Exception e) {
            Log.w(TAG, "Sfondo non aggiunto: " + e);
        }
    }

    private void posizionaSfondo()
    {
        if (mSfondo == null || mLayout == null)
            return;
        // dopo il layout: prima di allora getLocationOnScreen non sa ancora dove
        // si trova il layout e i margini di sistema risulterebbero zero
        mLayout.post(new Runnable() {
            @Override public void run() { allineaSfondo(); }
        });
    }

    /**
     * Mappa lo sfondo sullo SCHERMO, non sul layout.
     *
     * Il layout puo' cominciare piu' in basso o piu' a destra dello schermo, e di
     * quanto lo si sa solo a runtime: si legge con getLocationOnScreen e si
     * compensa con margini negativi. Va rifatto ad ogni cambio di layout, perche'
     * quel numero cambia quando il sistema applica la richiesta sul foro della
     * fotocamera o quando il telefono ruota.
     *
     * La guardia in mezzo non e' un'ottimizzazione: senza di essa ogni chiamata
     * cambierebbe i parametri, il cambio innescherebbe un nuovo layout e il layout
     * richiamerebbe questo metodo, per sempre.
     */
    private void allineaSfondo()
    {
        if (mSfondo == null || mLayout == null)
            return;
        try {
            android.util.DisplayMetrics dm = new android.util.DisplayMetrics();
            getWindowManager().getDefaultDisplay().getRealMetrics(dm);
            int[] pos = new int[2];
            mLayout.getLocationOnScreen(pos);

            ViewGroup.LayoutParams att = mSfondo.getLayoutParams();
            if (att instanceof RelativeLayout.LayoutParams) {
                RelativeLayout.LayoutParams a = (RelativeLayout.LayoutParams) att;
                if (a.width == dm.widthPixels && a.height == dm.heightPixels
                        && a.leftMargin == -pos[0] && a.topMargin == -pos[1])
                    return;                 // gia' allineato: non ritoccare
            }

            RelativeLayout.LayoutParams p =
                    new RelativeLayout.LayoutParams(dm.widthPixels, dm.heightPixels);
            p.leftMargin = -pos[0];
            p.topMargin = -pos[1];
            mSfondo.setLayoutParams(p);
            mSfondo.setImageResource(R.drawable.bg_console);  // versione dell'orientamento attuale
        } catch (Exception e) {
            Log.w(TAG, "Sfondo non allineato: " + e);
        }
    }

    /**
     * Tasto con l'ingranaggio in alto a sinistra: apre le impostazioni in gioco.
     *
     * Perche' esiste. Prima le impostazioni erano una schermata all'avvio, e
     * aveva senso finche' il gioco impiegava oltre un minuto a partire: quel
     * tempo andava occupato. Ora il gioco e' pronto in pochi secondi, quindi una
     * schermata di attesa non serve piu' e le impostazioni stanno dove le vuoi
     * davvero, cioe' mentre giochi.
     *
     * E' piccolo e semitrasparente per non dare fastidio: sopra c'e' la scena del
     * gioco. Il cerchio scuro dell'icona serve a renderlo visibile su qualsiasi
     * sfondo.
     */
    private void addPulsanteImpostazioni()
    {
        try {
            mBottoneImpostazioni = new ImageView(this);
            mBottoneImpostazioni.setImageResource(R.drawable.ic_ingranaggio);
            mBottoneImpostazioni.setContentDescription(getString(R.string.settings_open));
            mBottoneImpostazioni.setAlpha(0.55f);

            mBottoneImpostazioni.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { showSettingsPanel(); }
            });

            mLayout.addView(mBottoneImpostazioni);
            posizionaIngranaggio();
        } catch (Exception e) {
            Log.w(TAG, "Tasto impostazioni non aggiunto: " + e);
        }
    }

    /**
     * Dove sta l'ingranaggio, secondo l'orientamento.
     *
     *   verticale   -> SOTTO la scena del gioco, a sinistra. In verticale il
     *                  gioco occupa la fascia alta e in cima non c'e' spazio
     *                  libero: l'ingranaggio starebbe sopra l'immagine. Sotto
     *                  invece c'e' la fascia vuota fra gioco e tasti.
     *   orizzontale -> in alto a sinistra, sopra la scena: li' non c'e' altro
     *                  spazio disponibile, il gioco riempie tutta l'altezza.
     *
     * La quota "sotto il gioco" e' la stessa altezza usata da applySurfaceLayout:
     * larghezza x 3/4, perche' la scena e' 4:3.
     */
    private void posizionaIngranaggio()
    {
        if (mBottoneImpostazioni == null)
            return;
        try {
            float d = getResources().getDisplayMetrics().density;
            int lato = Math.round(d * 34);
            int bordo = Math.round(d * 6);
            boolean verticale = getResources().getConfiguration().orientation
                                == Configuration.ORIENTATION_PORTRAIT;

            RelativeLayout.LayoutParams p =
                    new RelativeLayout.LayoutParams(lato, lato);
            p.addRule(RelativeLayout.ALIGN_PARENT_TOP);
            p.addRule(RelativeLayout.ALIGN_PARENT_START);
            if (verticale) {
                // Sotto la scena, che ora parte da cornicePx() e non dal bordo.
                int altezzaGioco = getResources().getDisplayMetrics().widthPixels * 3 / 4;
                p.setMargins(bordo, cornicePx() + altezzaGioco + bordo, 0, 0);
            } else {
                p.setMargins(bordo, bordo, 0, 0);
            }
            mBottoneImpostazioni.setLayoutParams(p);
        } catch (Exception e) {
            Log.w(TAG, "Ingranaggio non riposizionato: " + e);
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

    /**
     * L'app segue sempre il telefono. Niente scelta manuale.
     *
     * PERCHE' LA SCELTA E' STATA RIMOSSA. C'era una voce Orientamento con
     * Automatico / Orizzontale / Verticale. Con "Verticale" il gioco NON PARTIVA
     * AFFATTO: nel log comparivano "surface is not valid" e "skip updating
     * surface, 5", il thread nativo non veniva mai avviato, e il layer della
     * finestra restava 2340x1080 (orizzontale) nonostante la richiesta di
     * verticale. Misurato: con Automatico Ruby parte in 1,1 s, con Verticale non
     * parte mai, nemmeno entro 110 secondi.
     *
     * Verificato che non dipendeva da pathCache, ne' dalla rotazione fisica del
     * telefono, ne' dalle modifiche recenti (anche l'APK precedente lo faceva).
     * Un tentativo di correzione -- evitare di riscrivere l'orientamento quando
     * era gia' quello giusto -- non ha risolto.
     *
     * Chiedere a SDL un orientamento diverso da quello che ha gia' negoziato con
     * la finestra e' fragile, e non serve: con FULL_SENSOR basta girare il
     * telefono. In verticale il gioco va in alto (applySurfaceLayout) e i tasti
     * restano in basso, che era l'effetto voluto.
     *
     * FULL_SENSOR e' comunque necessario esplicitamente: mkxp-z dichiara a SDL
     * una finestra 512x384, piu' larga che alta, e SDL da solo bloccherebbe il
     * solo orizzontale.
     */
    private void applyOrientationPreference()
    {
        if (getRequestedOrientation() != ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR)
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR);
    }

    /**
     * SDL, dopo aver creato la finestra, chiama questo metodo dal lato nativo e
     * imporrebbe lui l'orientamento. Nel log si vedeva:
     *
     *     setOrientation() requestedOrientation=6 ... hint=LandscapeLeft LandscapeRight
     *
     * cioe' il solo orizzontale: mkxp-z dichiara a SDL una finestra 512x384, piu'
     * larga che alta, e SDL ne deduce che il gioco sia solo orizzontale. Senza
     * questa sovrascrittura, girare il telefono non avrebbe effetto.
     *
     * SDLActivity documenta il metodo come sovrascrivibile ("This can be
     * overridden"). Qui si lascia libero l'orientamento e decide il telefono.
     */
    @Override
    public void setOrientationBis(int w, int h, boolean resizable, String hint)
    {
        applyOrientationPreference();
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
     * ANCHE IN ORIZZONTALE la superficie viene ridotta al 4:3 e centrata, e non
     * lasciata a schermo pieno. Non e' una questione estetica: una SurfaceView
     * BUCA la finestra nella propria area, cioe' tutto quello che le sta dietro
     * -- compreso lo sfondo decorativo -- non viene disegnato li'. Con la
     * superficie a schermo pieno lo sfondo era invisibile in orizzontale, e le
     * bande nere ai lati non erano spazio libero del layout ma il letterbox
     * interno di mkxp-z. Riducendola, quelle bande diventano spazio del layout e
     * lo sfondo si vede.
     *
     * La scena non cambia dimensione: era gia' 1440x1080 dentro una superficie
     * piu' larga, e ora la superficie coincide con lei.
     */
    private void applySurfaceLayout()
    {
        applicaMisureSuperficie();
        /* Di nuovo dopo il layout: in onCreate mLayout non e' ancora misurato e
         * alla rotazione riporta ancora l'altezza vecchia. La seconda passata usa
         * il valore reale; la guardia dentro applicaMisureSuperficie evita che
         * un nuovo setLayoutParams inneschi un ciclo di layout senza fine. */
        if (mLayout != null)
            mLayout.post(new Runnable() {
                @Override public void run() { applicaMisureSuperficie(); }
            });
    }

    private void applicaMisureSuperficie()
    {
        if (mLayout == null || mSurface == null)
            return;

        try {
            boolean verticale = getResources().getConfiguration().orientation
                                == Configuration.ORIENTATION_PORTRAIT;

            int larghezza, altezza, regola, margineAlto = 0;
            if (verticale) {
                int w = (mLayout.getWidth() > 0)
                        ? mLayout.getWidth()
                        : getResources().getDisplayMetrics().widthPixels;
                larghezza = RelativeLayout.LayoutParams.MATCH_PARENT;
                altezza = w * 3 / 4;
                regola = RelativeLayout.ALIGN_PARENT_TOP;
                // La scena non parte piu' dal bordo: sopra ci va la cornice dello
                // sfondo. Sotto, il nero che restava fra scena e disegno sparisce
                // perche' il bordo basso della scena scende alla stessa quota a cui
                // il disegno smette di essere nero.
                margineAlto = cornicePx();
            } else {
                int h = (mLayout.getHeight() > 0)
                        ? mLayout.getHeight()
                        : getResources().getDisplayMetrics().heightPixels;
                larghezza = h * 4 / 3;
                altezza = RelativeLayout.LayoutParams.MATCH_PARENT;
                regola = RelativeLayout.CENTER_HORIZONTAL;
            }

            ViewGroup.LayoutParams attuali = mSurface.getLayoutParams();
            if (attuali instanceof RelativeLayout.LayoutParams
                    && attuali.width == larghezza && attuali.height == altezza
                    && ((RelativeLayout.LayoutParams) attuali).topMargin == margineAlto)
                return;                     // gia' a posto: non ritoccare

            RelativeLayout.LayoutParams p =
                    new RelativeLayout.LayoutParams(larghezza, altezza);
            p.addRule(regola);
            p.topMargin = margineAlto;
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
     * Ora i tasti restano fuori e fanno da anteprima:
     *
     *   verticale   -> il pannello prende il 72% dell'altezza dall'alto e lascia
     *                  libera la fascia bassa, dove stanno i tasti (misurati fra
     *                  y=1697 e y=2178 su uno schermo di 2340).
     *   orizzontale -> una colonna centrale al 50% della larghezza. I tasti stanno
     *                  ai due lati, quindi restano scoperti.
     */
    private void applyPanelLayout()
    {
        if (mLayout == null || mPannello == null)
            return;

        try {
            boolean verticale = getResources().getConfiguration().orientation
                                == Configuration.ORIENTATION_PORTRAIT;
            int w = getResources().getDisplayMetrics().widthPixels;
            int h = getResources().getDisplayMetrics().heightPixels;

            RelativeLayout.LayoutParams p;
            if (verticale) {
                p = new RelativeLayout.LayoutParams(
                        RelativeLayout.LayoutParams.MATCH_PARENT,
                        Math.round(h * OVERLAY_HEIGHT_PORT));
                p.addRule(RelativeLayout.ALIGN_PARENT_TOP);
            } else {
                p = new RelativeLayout.LayoutParams(
                        Math.round(w * OVERLAY_WIDTH_LAND),
                        RelativeLayout.LayoutParams.MATCH_PARENT);
                p.addRule(RelativeLayout.CENTER_HORIZONTAL);
            }
            mPannello.setLayoutParams(p);
        } catch (Exception e) {
            Log.w(TAG, "Layout del pannello impostazioni non applicato: " + e);
        }
    }

    /**
     * Apre le impostazioni durante la partita, dal tasto con l'ingranaggio.
     *
     * Il gioco NON viene messo in pausa: continua a girare dietro al pannello.
     * Quello che viene sospeso e' l'invio dei tasti, cosi' regolare l'opacita' non
     * fa camminare il personaggio.
     */
    private void showSettingsPanel()
    {
        if (mPannello != null)        // gia' aperto
            return;
        try {
            mPannello = getLayoutInflater().inflate(R.layout.settings_panel, mLayout, false);
            mLayout.addView(mPannello);   // aggiunta per ultima = sopra a tutto
            applyPanelLayout();                 // non copre i tasti: sono l'anteprima

            // I tasti si vedono e reagiscono al tocco, ma non arrivano al gioco:
            // altrimenti mentre regoli i cursori il personaggio si muoverebbe.
            mGamepad.setInputEnabled(false);

            final SharedPreferences prefs = getSharedPreferences(GamepadConfig.PREFS, MODE_PRIVATE);

            // --- TORNA AL GIOCO ----------------------------------------------
            View chiudi = mPannello.findViewById(R.id.settings_close);
            if (chiudi != null) {
                chiudi.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) { closeSettingsPanel(); }
                });
            }

            // --- opacita' e dimensione ---------------------------------------
            final SeekBar op = mPannello.findViewById(R.id.seek_opacity);
            final SeekBar sc = mPannello.findViewById(R.id.seek_scale);
            op.setProgress(mGamepadConfig.opacity);
            sc.setProgress(mGamepadConfig.scale);
            updatePanelLabels(op.getProgress(), sc.getProgress());

            SeekBar.OnSeekBarChangeListener l = new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar s, int v, boolean fromUser) {
                    updatePanelLabels(op.getProgress(), sc.getProgress());
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

            // Niente scelta dell'orientamento: l'app segue il telefono.
            // Vedi applyOrientationPreference per il perche'.

            // --- lingua -------------------------------------------------------
            String lang = prefs.getString(GamepadConfig.KEY_LANGUAGE, GameFolder.LANG_IT);
            ((RadioButton) mPannello.findViewById(
                GameFolder.LANG_EN.equals(lang) ? R.id.lang_en : R.id.lang_it)).setChecked(true);

            ((RadioGroup) mPannello.findViewById(R.id.group_lang))
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
                            ((RadioButton) mPannello.findViewById(
                                GameFolder.LANG_EN.equals(cur) ? R.id.lang_en : R.id.lang_it)).setChecked(true);
                        }
                    }
                });

        } catch (Exception e) {
            Log.w(TAG, "Pannello impostazioni non mostrato: " + e);
        }
    }

    private void updatePanelLabels(int opacity, int scale)
    {
        if (mPannello == null)
            return;
        TextView a = mPannello.findViewById(R.id.label_opacity);
        TextView b = mPannello.findViewById(R.id.label_scale);
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
            mGamepad.setInputEnabled(mPannello == null);
            // Riattaccare il gamepad lo rende l'ultimo figlio, quindi il piu' in
            // alto: tutto quello che deve stargli sopra va riportato davanti,
            // nell'ordine in cui deve apparire.
            portaDavantiSovrapposizioni();
        } catch (Exception e) {
            Log.w(TAG, "Gamepad non ricostruito: " + e);
        }
    }

    /** Rimette in cima ingranaggio, pannello e schermata iniziale, in quest'ordine. */
    private void portaDavantiSovrapposizioni()
    {
        if (mBottoneImpostazioni != null)
            mBottoneImpostazioni.bringToFront();
        if (mPannello != null)
            mPannello.bringToFront();
        if (mSplash != null)
            mSplash.bringToFront();
    }

    private void closeSettingsPanel()
    {
        reloadGamepad();
        hideSettingsPanel();
    }

    private void hideSettingsPanel()
    {
        if (mPannello == null)
            return;

        if (mPannello.getParent() instanceof ViewGroup)
            ((ViewGroup) mPannello.getParent()).removeView(mPannello);

        mPannello = null;

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
            applyPanelLayout();            // e anche il pannello: cambia zona e misura
            posizionaIngranaggio();        // in verticale va sotto il gioco, in orizzontale in alto
            posizionaSfondo();             // e lo sfondo cambia versione e misure
            mGamepad.detach();
            mGamepad.attachTo(this, mLayout);
            mGamepad.setInputEnabled(mPannello == null);
            if (mGamepadInvisible)
                mGamepad.hideView();
            // Riattaccare il gamepad lo rimette come ultimo figlio, quindi sopra
            // a tutto: dopo una rotazione i tasti finivano disegnati sopra le
            // impostazioni. Va ripristinato l'ordine di sovrapposizione.
            portaDavantiSovrapposizioni();
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