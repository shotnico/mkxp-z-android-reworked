package com.hatkid.mkxpz.utils;

import android.animation.Animator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.graphics.drawable.BitmapDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.view.animation.LinearInterpolator;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.hatkid.mkxpz.R;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * La schermata che copre l'attesa dell'avvio: un'immagine che scorre piano, una
 * barra che avanza e un suggerimento che cambia.
 *
 * QUANTO DURA L'ATTESA, misurato sul telefono dell'utente. Dopo ogni copia dei
 * dati mkxp-z ricostruisce l'indice dei percorsi: il processo parte, e 79
 * secondi dopo finisce di scrivere .mkxp_pathcache. In quei 79 secondi il gioco
 * non disegna NIENTE. Negli avvii successivi l'indice c'e' gia' e il gioco
 * parte in circa 2 secondi.
 *
 * PERCHE' NON UN TIMER FISSO. La versione precedente durava 4,5 secondi fissi:
 * copriva i primi 4 e lasciava 75 secondi di schermo nero, cioe' proprio il
 * momento in cui uno pensa che l'app sia bloccata. Due attese che stanno fra 2 e
 * 80 secondi non si coprono con un numero. Qui si aspetta un SEGNALE VERO: il
 * gioco, al primo giro del suo ciclo, scrive .gioco_pronto (vedi
 * android_boot.rb) e questa schermata se ne va. Lo stesso meccanismo del file
 * .apri_opzioni che gia' esiste in questo progetto.
 *
 * LA BARRA NON MENTE, MA NON SA. Non esiste un avanzamento reale da leggere:
 * l'indice si costruisce dentro il C++, senza dire a che punto e'. Quindi la
 * barra sale fino al 92% sulla durata ATTESA e li' si ferma, e arriva a 100
 * solo quando il gioco dice davvero di essere pronto. Non torna indietro e non
 * resta ferma a 100 mentre l'attesa continua: sono i due modi in cui una barra
 * diventa una bugia. La durata attesa la impara da sola, misurando l'ultimo
 * avvio e ricordandolo (chiave DURATA_MS): la prima volta parte dai 79 secondi
 * misurati.
 *
 * IMMAGINI E SUGGERIMENTI STANNO SUL TELEFONO, in caricamento\ dentro la
 * cartella del gioco, non dentro l'APK: si aggiungono con un copia-incolla, e
 * un APK con disegni di Nintendo dentro non si potrebbe ridistribuire.
 * Se la cartella non c'e', resta l'immagine di prima.
 */
public class SchermataCaricamento
{
    private static final String TAG = "FireAshCaricamento";

    public static final String SEGNALE_PRONTO = ".gioco_pronto";
    private static final String CARTELLA = "caricamento";
    private static final String SUGGERIMENTI = "suggerimenti.txt";
    private static final String PREF_DURATA = "durata_avvio_ms";

    /** Quanto e' durato l'avvio lento misurato: il punto di partenza. */
    private static final long DURATA_PREDEFINITA_MS = 79000L;
    /** Non si mostra per meno di questo: un lampo e' peggio di niente. */
    private static final long MINIMO_MS = 2200L;
    /** Rete di sicurezza: se il segnale non arriva mai, non resta per sempre. */
    private static final long MASSIMO_MS = 200000L;
    private static final long CONTROLLO_MS = 250L;
    private static final long SUGGERIMENTO_MS = 6500L;
    private static final long DISSOLVENZA_MS = 600L;
    private static final int  QUASI = 920;      // su 1000: dove si fermerebbe

    /** Ogni quanto cambia immagine. Scelta dell'utente: quattro secondi. */
    private static final long CAMBIO_MS = 4000L;
    /** Quanto dura il passaggio fra un'immagine e l'altra. */
    private static final long DISSOLVENZA_IMG_MS = 700L;
    /**
     * Quanto scorre, in pixel di schermo al secondo.
     *
     * La prima versione muoveva "mezza larghezza di schermo sulla durata
     * attesa": 540 px in 79 secondi, cioe' 7 px al secondo, e l'utente l'ha
     * vista ferma. Una velocita' in px/s si legge e si cambia; una frazione
     * della larghezza dipendeva da quanto era larga l'immagine e dava numeri
     * diversi senza dirlo.
     */
    private static final float SPOSTAMENTO_PX_S = 45f;
    /** Quanto si ingrandisce oltre il riempire l'altezza: lo "zoom sul centro". */
    private static final float ZOOM = 1.12f;

    private final Activity mAtt;
    private final View mVista;
    private final File mCartellaGioco;
    private final SharedPreferences mPrefs;
    private final Handler mMano = new Handler(Looper.getMainLooper());
    private final Random mCaso = new Random();

    // due viste sovrapposte: si dissolve la nuova sopra quella in vista
    private ImageView mVisibile;
    private ImageView mNascosta;
    private List<File> mFile = new ArrayList<File>();
    private int mIndice = 0;
    private boolean mPrima = true;
    private boolean mVersoDestra = false;
    private ProgressBar mBarra;
    private TextView mSuggerimento;
    private ValueAnimator mScorrimento;
    private ValueAnimator mAvanzamento;
    private List<String> mFrasi = new ArrayList<String>();
    private int mFrase = 0;
    private long mInizio;
    private boolean mChiusa = false;
    private Runnable mAlTermine;

    public SchermataCaricamento(Activity att, View vista, String cartellaGioco,
                                SharedPreferences prefs)
    {
        mAtt = att;
        mVista = vista;
        mCartellaGioco = (cartellaGioco != null) ? new File(cartellaGioco) : null;
        mPrefs = prefs;
    }

    /** Da chiamare quando la schermata e' stata aggiunta al layout. */
    public void avvia(Runnable alTermine)
    {
        mAlTermine = alTermine;
        mInizio = SystemClock.elapsedRealtime();
        mVisibile = (ImageView) mVista.findViewById(R.id.splash_image);
        mNascosta = (ImageView) mVista.findViewById(R.id.splash_image2);
        mVersoDestra = mCaso.nextBoolean();   // da che parte comincia
        mBarra = (ProgressBar) mVista.findViewById(R.id.splash_barra);
        mSuggerimento = (TextView) mVista.findViewById(R.id.splash_suggerimento);

        cancellaSegnale();
        caricaFrasi();
        mostraProssimaFrase();
        avviaBarra(durataAttesa());
        // l'immagine si prepara quando la vista ha una misura: prima di allora
        // non si sa di quanto va ingrandita
        mVista.post(new Runnable() { @Override public void run() { prossimaImmagine(); } });
        mMano.postDelayed(mControllo, CONTROLLO_MS);
    }

    /** Un tocco: se il gioco e' pronto la salta, altrimenti non fa niente. */
    public boolean saltaSePossibile()
    {
        if (esistePronto()) {
            chiudi();
            return true;
        }
        return false;
    }

    public void chiudiSubito()
    {
        chiudi();
    }

    // ------------------------------------------------------------------ attesa

    private long durataAttesa()
    {
        long d = DURATA_PREDEFINITA_MS;
        try {
            if (mPrefs != null)
                d = mPrefs.getLong(PREF_DURATA, DURATA_PREDEFINITA_MS);
        } catch (Exception e) { /* valore vecchio di tipo diverso: si ignora */ }
        if (d < 4000L) d = 4000L;
        if (d > MASSIMO_MS) d = MASSIMO_MS;
        return d;
    }

    private void ricordaDurata(long ms)
    {
        // si tiene la piu' lunga fra le ultime due, cosi' un avvio veloce non
        // fa scendere la stima e la barra dell'avvio lento non arriva a 92% in
        // due secondi per poi restare ferma un minuto
        try {
            if (mPrefs == null) return;
            long vecchia = mPrefs.getLong(PREF_DURATA, DURATA_PREDEFINITA_MS);
            long nuova = Math.max(ms, (long) (vecchia * 0.7));
            mPrefs.edit().putLong(PREF_DURATA, nuova).apply();
        } catch (Exception e) { /* niente: e' solo una comodita' */ }
    }

    /**
     * Dove puo' comparire il segnale. Il gioco lo scrive in tutte queste, e qui
     * basta trovarlo in una: nella prima prova la schermata e' rimasta 200
     * secondi perche' guardava solo nella cartella del gioco e non lo vedeva.
     */
    private List<File> segnaliPossibili()
    {
        List<File> c = new ArrayList<File>();
        File gioco = cartellaGioco();
        if (gioco != null)
            c.add(new File(gioco, SEGNALE_PRONTO));
        try {
            File propria = mAtt.getExternalFilesDir(null);
            if (propria != null)
                c.add(new File(propria, SEGNALE_PRONTO));
        } catch (Exception e) { /* resta la prima */ }
        return c;
    }

    private void cancellaSegnale()
    {
        for (File f : segnaliPossibili()) {
            if (f.exists() && !f.delete())
                Log.w(TAG, "non riesco a cancellare " + f.getAbsolutePath());
        }
    }

    private boolean esistePronto()
    {
        for (File f : segnaliPossibili()) {
            if (f.exists())
                return true;
        }
        return false;
    }

    private final Runnable mControllo = new Runnable() {
        @Override public void run()
        {
            if (mChiusa) return;
            long passati = SystemClock.elapsedRealtime() - mInizio;
            if (esistePronto() && passati >= MINIMO_MS) {
                ricordaDurata(passati);
                Log.i(TAG, "gioco pronto dopo " + passati + " ms");
                completaEChiudi();
                return;
            }
            if (passati > MASSIMO_MS) {
                Log.w(TAG, "segnale mai arrivato in " + passati + " ms: chiudo comunque");
                completaEChiudi();
                return;
            }
            mMano.postDelayed(this, CONTROLLO_MS);
        }
    };

    // ----------------------------------------------------------------- immagini

    /**
     * Mostra l'immagine successiva, dissolvendola sopra quella in vista, e
     * riprogramma il cambio.
     *
     * PERCHE' UNA SEQUENZA E NON UNA SOLA IMMAGINE. La prima versione teneva la
     * stessa immagine per tutto il caricamento e la faceva derivare di mezza
     * larghezza di schermo sulla durata attesa: erano 7 px al secondo, e a
     * schermo sembrava ferma. Ora l'immagine cambia ogni CAMBIO_MS e ogni cambio
     * INVERTE la direzione, cosi' il movimento si vede anche in quattro secondi.
     *
     * Lo spostamento e' a velocita' fissa (SPOSTAMENTO_PX_S), non "una frazione
     * della larghezza": una frazione dava velocita' diverse a seconda di quanto
     * era larga l'immagine, e la lentezza che si vedeva non era quella che avevo
     * calcolato.
     */
    private void prossimaImmagine()
    {
        if (mChiusa || mVisibile == null || mNascosta == null) return;
        final int vw = mVisibile.getWidth();
        final int vh = mVisibile.getHeight();
        if (vw <= 0 || vh <= 0) {           // la vista non ha ancora una misura
            mMano.postDelayed(new Runnable() {
                @Override public void run() { prossimaImmagine(); }
            }, 60);
            return;
        }
        if (mFile.isEmpty()) {
            mFile = elencoImmagini();
            if (mFile.isEmpty()) {          // niente cartella: l'immagine dell'APK
                mostra(riserva(), vw, vh, true);
                return;
            }
        }
        File f = mFile.get(mIndice % mFile.size());
        mIndice++;
        Bitmap bmp = leggi(f);
        if (bmp == null) {
            mFile.remove(f);                // illeggibile: non ci si torna
            prossimaImmagine();
            return;
        }
        mostra(bmp, vw, vh, mPrima);
        mPrima = false;
        mMano.postDelayed(new Runnable() {
            @Override public void run() { prossimaImmagine(); }
        }, CAMBIO_MS);
    }

    private void mostra(Bitmap bmp, int vw, int vh, boolean senzaDissolvenza)
    {
        if (bmp == null) return;
        final ImageView vista = mNascosta;
        BitmapDrawable d = new BitmapDrawable(mAtt.getResources(), bmp);
        d.setFilterBitmap(false);      // disegni a pixel: nitidi, non sfumati
        vista.setImageDrawable(d);

        // riempie l'altezza e ingrandisce ancora un po': la parte "zoomata sul
        // centro", dove sta il Pokemon
        final float scala = Math.max((float) vh / bmp.getHeight(),
                                     (float) vw / bmp.getWidth()) * ZOOM;
        final float eccedenza = Math.max(0f, bmp.getWidth() * scala - vw);
        final float centro = -eccedenza / 2f;
        final float voluta = SPOSTAMENTO_PX_S * (CAMBIO_MS + DISSOLVENZA_IMG_MS) / 1000f;
        final float corsa = Math.min(eccedenza, voluta);
        mVersoDestra = !mVersoDestra;   // un'immagine da una parte, la prossima dall'altra
        final float da = centro + (mVersoDestra ? -corsa / 2f : corsa / 2f);
        final float a  = centro + (mVersoDestra ? corsa / 2f : -corsa / 2f);
        final float dy = (vh - bmp.getHeight() * scala) / 2f;
        final Matrix m = new Matrix();
        applica(vista, m, scala, da, dy);

        ValueAnimator an = ValueAnimator.ofFloat(da, a);
        an.setDuration(CAMBIO_MS + DISSOLVENZA_IMG_MS + 400L);
        an.setInterpolator(new LinearInterpolator());
        an.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(ValueAnimator av)
            {
                applica(vista, m, scala, (Float) av.getAnimatedValue(), dy);
            }
        });
        an.start();

        final ImageView vecchia = mVisibile;
        if (senzaDissolvenza) {
            vista.setAlpha(1f);
            vecchia.setAlpha(0f);
        } else {
            vista.animate().alpha(1f).setDuration(DISSOLVENZA_IMG_MS).start();
            vecchia.animate().alpha(0f).setDuration(DISSOLVENZA_IMG_MS).start();
        }
        if (mScorrimento != null) mScorrimento.cancel();
        mScorrimento = an;

        // scambio i ruoli: la vista appena riempita e' quella in vista
        mVisibile = vista;
        mNascosta = vecchia;
    }

    private void applica(ImageView vista, Matrix m, float scala, float dx, float dy)
    {
        m.setScale(scala, scala);
        m.postTranslate(dx, dy);
        vista.setImageMatrix(m);
    }

    /**
     * La cartella del gioco, RISOLTA ADESSO.
     *
     * IL DIFETTO CHE HA COSTATO UNA PROVA. Questa schermata riceveva il percorso
     * che MainActivity ha in mano, ma quando parte quel percorso e' ancora il
     * valore predefinito: GAME_PATH viene risolto piu' tardi, in
     * runSDLThread(). Risultato, nel log: "cartella
     * /storage/emulated/0/FireAshITA/caricamento esiste=false". Cercava in una
     * cartella che non esiste, mentre le immagini erano in mkxp-z.
     * Quindi qui si chiede a GameFolder, che guarda quale cartella contiene
     * davvero la cartella Data -- la stessa domanda, fatta al momento giusto.
     */
    private File cartellaGioco()
    {
        try {
            String g = GameFolder.resolve();
            if (g != null)
                return new File(g);
        } catch (Exception e) { /* si prova col percorso ricevuto */ }
        return mCartellaGioco;
    }

    /**
     * Dove cercare, in ordine: la cartella del gioco e, come riserva, quella
     * privata dell'app. La riserva serve perche' l'accesso a /sdcard dipende da
     * come Android lo filtra, mentre alla propria un'app entra sempre.
     */
    private List<File> cartelleCandidate()
    {
        List<File> c = new ArrayList<File>();
        File gioco = cartellaGioco();
        if (gioco != null)
            c.add(new File(gioco, CARTELLA));
        try {
            File propria = mAtt.getExternalFilesDir(null);
            if (propria != null)
                c.add(new File(propria, CARTELLA));
        } catch (Exception e) { /* niente: resta la prima */ }
        return c;
    }

    /**
     * L'elenco delle immagini, mischiato. Si legge UNA VOLTA e poi si scorre in
     * ordine: pescando a caso ogni quattro secondi la stessa immagine tornerebbe
     * due volte di fila, che a schermo sembra un difetto.
     */
    private List<File> elencoImmagini()
    {
        List<File> file = new ArrayList<File>();
        boolean gestoreStorage = false;
        try {
            if (android.os.Build.VERSION.SDK_INT >= 30)
                gestoreStorage = android.os.Environment.isExternalStorageManager();
        } catch (Exception e) { /* si sta senza */ }
        Log.i(TAG, "accesso a tutti i file: " + gestoreStorage);

        for (File dir : cartelleCandidate()) {
            File[] tutti = dir.listFiles();
            Log.i(TAG, "cartella " + dir.getAbsolutePath()
                       + " esiste=" + dir.exists()
                       + " cartella=" + dir.isDirectory()
                       + " leggibile=" + dir.canRead()
                       + " elenco=" + (tutti == null ? "null" : String.valueOf(tutti.length)));
            if (tutti == null)
                continue;
            for (File f : tutti) {
                String n = f.getName().toLowerCase();
                // Niente isFile(): non e' un controllo che serve -- se il file
                // non si apre se ne accorge chi decodifica -- e una domanda in
                // meno e' una risposta sbagliata in meno.
                // (Per la cronaca: avevo incolpato isFile() del difetto della
                // prima prova. Non era lui: era la cartella sbagliata, vedi
                // cartellaGioco(). Il log lo ha detto, il sospetto no.)
                if (n.endsWith(".png") || n.endsWith(".jpg")
                        || n.endsWith(".jpeg") || n.endsWith(".webp"))
                    file.add(f);
            }
            if (!file.isEmpty())
                break;
        }
        Collections.shuffle(file, mCaso);
        Log.i(TAG, "immagini di caricamento: " + file.size());
        return file;
    }

    private Bitmap riserva()
    {
        Log.i(TAG, "nessuna immagine in " + CARTELLA + ": resta quella dell'APK");
        try {
            return BitmapFactory.decodeResource(mAtt.getResources(),
                                                R.drawable.schermata_iniziale);
        } catch (Throwable t) {
            return null;
        }
    }

    private Bitmap leggi(File f)
    {
        try {
            return leggiBitmap(f, 1);
        } catch (OutOfMemoryError e) {
            Log.w(TAG, "immagine troppo grande, la dimezzo: " + f.getName());
            try { return leggiBitmap(f, 2); } catch (Throwable t) { return null; }
        } catch (Exception e) {
            Log.w(TAG, "immagine illeggibile " + f.getName() + ": " + e);
            return null;
        }
    }

    private Bitmap leggiBitmap(File f, int riduzione)
    {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inSampleSize = riduzione;
        o.inPreferredConfig = Bitmap.Config.RGB_565;   // meta' memoria, nessun canale alfa da tenere
        return BitmapFactory.decodeFile(f.getAbsolutePath(), o);
    }

    // -------------------------------------------------------- barra e suggerimenti

    private void avviaBarra(long durata)
    {
        if (mBarra == null) return;
        mAvanzamento = ValueAnimator.ofInt(0, QUASI);
        mAvanzamento.setDuration(durata);
        // piu' veloce all'inizio e lenta verso la fine: se l'attesa e' piu'
        // lunga della stima la barra rallenta invece di inchiodarsi
        mAvanzamento.setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f));
        mAvanzamento.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(ValueAnimator an)
            {
                if (mBarra != null) mBarra.setProgress((Integer) an.getAnimatedValue());
            }
        });
        mAvanzamento.start();
    }

    private void caricaFrasi()
    {
        mFrasi.clear();
        // si prova ad APRIRLO invece di chiedere prima se esiste: se non c'e',
        // l'eccezione lo dice, e con quale percorso -- che e' proprio il dato
        // che serviva per trovare il difetto della prima prova
        for (File dir : cartelleCandidate()) {
            File f = new File(dir, SUGGERIMENTI);
            BufferedReader r = null;
            try {
                r = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
                String riga;
                while ((riga = r.readLine()) != null) {
                    riga = riga.trim();
                    if (riga.length() > 0 && !riga.startsWith("#"))
                        mFrasi.add(riga);
                }
                Log.i(TAG, "suggerimenti: " + mFrasi.size() + " da " + f.getAbsolutePath());
            } catch (Exception e) {
                Log.i(TAG, "suggerimenti non letti da " + f.getAbsolutePath() + ": " + e);
            } finally {
                try { if (r != null) r.close(); } catch (Exception ignored) {}
            }
            if (!mFrasi.isEmpty())
                break;
        }
        Collections.shuffle(mFrasi, mCaso);   // ordine diverso a ogni avvio
    }

    private void mostraProssimaFrase()
    {
        if (mChiusa || mSuggerimento == null || mFrasi.isEmpty()) return;
        final String testo = mFrasi.get(mFrase % mFrasi.size());
        mFrase++;
        mSuggerimento.animate().alpha(0f).setDuration(220).withEndAction(new Runnable() {
            @Override public void run()
            {
                if (mChiusa || mSuggerimento == null) return;
                mSuggerimento.setText(testo);
                mSuggerimento.animate().alpha(1f).setDuration(320).start();
            }
        }).start();
        mMano.postDelayed(new Runnable() {
            @Override public void run() { mostraProssimaFrase(); }
        }, SUGGERIMENTO_MS);
    }

    // ------------------------------------------------------------------ chiusura

    private void completaEChiudi()
    {
        if (mChiusa) return;
        if (mAvanzamento != null) mAvanzamento.cancel();
        if (mBarra != null) {
            ValueAnimator fine = ValueAnimator.ofInt(mBarra.getProgress(), 1000);
            fine.setDuration(380);
            fine.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                @Override public void onAnimationUpdate(ValueAnimator an)
                {
                    if (mBarra != null) mBarra.setProgress((Integer) an.getAnimatedValue());
                }
            });
            fine.addListener(new android.animation.AnimatorListenerAdapter() {
                @Override public void onAnimationEnd(Animator a) { chiudi(); }
            });
            fine.start();
        } else {
            chiudi();
        }
    }

    private void chiudi()
    {
        if (mChiusa) return;
        mChiusa = true;
        mMano.removeCallbacksAndMessages(null);
        if (mScorrimento != null) mScorrimento.cancel();
        if (mAvanzamento != null) mAvanzamento.cancel();
        mVista.animate().alpha(0f).setDuration(DISSOLVENZA_MS)
              .withEndAction(new Runnable() {
                  @Override public void run()
                  {
                      mVisibile = null;
                      mNascosta = null;
                      if (mAlTermine != null) mAlTermine.run();
                  }
              }).start();
    }
}
