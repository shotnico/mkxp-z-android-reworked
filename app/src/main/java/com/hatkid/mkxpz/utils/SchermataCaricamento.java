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

    private final Activity mAtt;
    private final View mVista;
    private final File mCartellaGioco;
    private final SharedPreferences mPrefs;
    private final Handler mMano = new Handler(Looper.getMainLooper());
    private final Random mCaso = new Random();

    private ImageView mImmagine;
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
        mImmagine = (ImageView) mVista.findViewById(R.id.splash_image);
        mBarra = (ProgressBar) mVista.findViewById(R.id.splash_barra);
        mSuggerimento = (TextView) mVista.findViewById(R.id.splash_suggerimento);

        cancellaSegnale();
        caricaFrasi();
        mostraProssimaFrase();
        avviaBarra(durataAttesa());
        // l'immagine si prepara quando la vista ha una misura: prima di allora
        // non si sa di quanto va ingrandita
        mVista.post(new Runnable() { @Override public void run() { preparaImmagine(); } });
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
        if (mCartellaGioco != null)
            c.add(new File(mCartellaGioco, SEGNALE_PRONTO));
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

    // ----------------------------------------------------------------- immagine

    private void preparaImmagine()
    {
        if (mImmagine == null || mChiusa) return;
        final int vw = mImmagine.getWidth();
        final int vh = mImmagine.getHeight();
        if (vw <= 0 || vh <= 0) return;

        Bitmap bmp = scegliImmagine();
        if (bmp == null) return;
        BitmapDrawable d = new BitmapDrawable(mAtt.getResources(), bmp);
        d.setFilterBitmap(false);   // disegni a pixel: meglio nitidi che sfumati
        mImmagine.setImageDrawable(d);

        // Riempie l'ALTEZZA e ingrandisce ancora un po': e' la parte "zoomata sul
        // centro". Il Pokemon sta al centro dell'immagine, quindi l'inquadratura
        // parte da li'.
        final float scala = Math.max((float) vh / bmp.getHeight(),
                                     (float) vw / bmp.getWidth()) * 1.12f;
        final float largaDavvero = bmp.getWidth() * scala;
        final float eccedenza = Math.max(0f, largaDavvero - vw);
        final float centro = -eccedenza / 2f;

        // QUANTO SCORRE, e perche' non da un capo all'altro. Le immagini sono
        // larghe 3072 px: attraversarle tutte porterebbe il Pokemon fuori
        // dall'inquadratura per la meta' del tempo -- si vedeva nella prova a
        // tre fotogrammi. Quindi si scorre di mezza larghezza di schermo intorno
        // al centro: su 79 secondi sono circa 7 px al secondo, il soggetto non
        // esce mai e il movimento si nota solo se lo guardi.
        final float corsa = Math.min(eccedenza, vw * 0.5f);
        final boolean versoSinistra = mCaso.nextBoolean();
        final float da = centro + (versoSinistra ? corsa / 2f : -corsa / 2f);
        final float a  = centro + (versoSinistra ? -corsa / 2f : corsa / 2f);
        final float dy = (vh - bmp.getHeight() * scala) / 2f;

        final Matrix m = new Matrix();
        applica(m, scala, da, dy);

        long durata = Math.max(durataAttesa(), 30000L);
        mScorrimento = ValueAnimator.ofFloat(da, a);
        mScorrimento.setDuration(durata);
        mScorrimento.setInterpolator(new LinearInterpolator());
        mScorrimento.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(ValueAnimator an)
            {
                if (mImmagine == null) return;
                applica(m, scala, (Float) an.getAnimatedValue(), dy);
            }
        });
        mScorrimento.start();
    }

    private void applica(Matrix m, float scala, float dx, float dy)
    {
        m.setScale(scala, scala);
        m.postTranslate(dx, dy);
        mImmagine.setImageMatrix(m);
    }

    /**
     * Le cartelle dove cercare, in ordine. Sono due perche' la prima prova ha
     * dato una risposta che non tornava: dal lato Java la cartella del gioco
     * risultava vuota, mentre il Ruby dentro LO STESSO PROCESSO ci leggeva 11
     * file. Quindi la cartella privata dell'app fa da riserva: li' l'accesso non
     * dipende da come Android filtra la vista di /sdcard.
     */
    private List<File> cartelleCandidate()
    {
        List<File> c = new ArrayList<File>();
        if (mCartellaGioco != null)
            c.add(new File(mCartellaGioco, CARTELLA));
        try {
            File propria = mAtt.getExternalFilesDir(null);
            if (propria != null)
                c.add(new File(propria, CARTELLA));
        } catch (Exception e) { /* niente: resta la prima */ }
        return c;
    }

    private Bitmap scegliImmagine()
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
                boolean immagine = n.endsWith(".png") || n.endsWith(".jpg")
                                   || n.endsWith(".jpeg") || n.endsWith(".webp");
                if (!immagine)
                    continue;
                // NON si filtra piu' su isFile(): se Android nasconde i metadati
                // di un file, isFile() risponde false anche quando il file c'e'
                // e si apre benissimo. Era il candidato numero uno per il difetto
                // della prima prova, e comunque un controllo che non serve:
                // se poi non si apre, se ne accorge chi decodifica.
                Log.i(TAG, "   trovata " + f.getName() + " (" + f.length() + " byte)");
                file.add(f);
            }
            if (!file.isEmpty())
                break;
        }
        if (file.isEmpty()) {
            Log.i(TAG, "nessuna immagine in " + CARTELLA + ": resta quella dell'APK");
            return BitmapFactory.decodeResource(mAtt.getResources(),
                                                R.drawable.schermata_iniziale);
        }
        File scelta = file.get(mCaso.nextInt(file.size()));
        Log.i(TAG, "immagine di caricamento: " + scelta.getName()
                   + " (fra " + file.size() + ")");
        try {
            return leggiBitmap(scelta, 1);
        } catch (OutOfMemoryError e) {
            Log.w(TAG, "immagine troppo grande, la dimezzo: " + scelta.getName());
            try { return leggiBitmap(scelta, 2); } catch (Throwable t) { return null; }
        } catch (Exception e) {
            Log.w(TAG, "immagine illeggibile " + scelta.getName() + ": " + e);
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
        // si prova ad APRIRLO, senza chiedere prima se esiste: nella prima prova
        // i controlli sui metadati rispondevano di no su file che poi si leggono
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
                      mImmagine = null;
                      if (mAlTermine != null) mAlTermine.run();
                  }
              }).start();
    }
}
