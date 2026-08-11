package com.hatkid.mkxpz.gamepad;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.KeyEvent;

/**
 * Configurazione dei controlli a schermo.
 *
 * I valori regolabili (opacita', dimensione, disposizione, orientamento) sono
 * salvati in SharedPreferences dalla schermata iniziale, cosi' si cambiano dal
 * telefono senza ricompilare l'APK.
 */
public class GamepadConfig
{
    /** Nome del file di preferenze, condiviso con il pannello impostazioni. */
    public static final String PREFS = "fireash";

    public static final String KEY_OPACITY  = "gamepad_opacity";
    public static final String KEY_SCALE    = "gamepad_scale";
    public static final String KEY_LANGUAGE = "language";

    // La preferenza "orientation" NON esiste piu' e non viene piu' letta.
    // C'era una scelta Automatico / Orizzontale / Verticale, ma imporre
    // l'orientamento con setRequestedOrientation impediva alla superficie di
    // disegno di stabilizzarsi: con "Verticale" il gioco non partiva affatto
    // (misurato: Ruby non parte nemmeno entro 110 secondi, mentre in automatico
    // parte in 1,1 s). Ora l'app segue sempre il telefono. Vedi
    // MainActivity.applyOrientationPreference.
    //
    // Un valore vecchio rimasto nelle preferenze e' innocuo: nessuno lo legge.

    // Opacita' in percentuale. ViewUtils.changeOpacity la mappa su 0-255, quindi
    // il 45 originale dava 115 su 255: i tasti sparivano con un po' di luce.
    public Integer opacity = 80;

    // Dimensione in percentuale.
    public Integer scale = 100;

    // Pokemon Essentials ha movimento a 4 direzioni: lasciare false, altrimenti
    // le diagonali generano input che il gioco non sa gestire.
    public Boolean diagonalMovement = false;

    /** Legge i valori salvati; i campi non presenti restano ai valori predefiniti. */
    public static GamepadConfig load(Context context)
    {
        GamepadConfig c = new GamepadConfig();

        if (context == null)
            return c;

        SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        c.opacity = p.getInt(KEY_OPACITY, c.opacity);
        c.scale   = p.getInt(KEY_SCALE,   c.scale);

        // limiti di sicurezza: un valore assurdo salvato renderebbe i tasti
        // invisibili o giganti, senza modo di rimediare dal gioco
        if (c.opacity < 20)  c.opacity = 20;
        if (c.opacity > 100) c.opacity = 100;
        if (c.scale < 60)    c.scale = 60;
        if (c.scale > 160)   c.scale = 160;

        return c;
    }

    /** Key bindings for each RGSS input **/
    // Questi keycode sono la mappatura tastiera predefinita di RPG Maker XP:
    // Z->A, X->B, C->C, A->X, S->Y, D->Z, Q->L, W->R.
    // mkxp-z li traduce nei corrispondenti pulsanti RGSS, che sono quelli
    // elencati in "bindingNames" dentro mkxp.json.
    public final Integer keycodeA = KeyEvent.KEYCODE_Z;
    public final Integer keycodeB = KeyEvent.KEYCODE_X;
    public final Integer keycodeC = KeyEvent.KEYCODE_C;
    public final Integer keycodeX = KeyEvent.KEYCODE_A;
    public final Integer keycodeY = KeyEvent.KEYCODE_S;
    public final Integer keycodeZ = KeyEvent.KEYCODE_D;
    public final Integer keycodeL = KeyEvent.KEYCODE_Q;
    public final Integer keycodeR = KeyEvent.KEYCODE_W;
    public final Integer keycodeCTRL = KeyEvent.KEYCODE_CTRL_LEFT;
    public final Integer keycodeALT = KeyEvent.KEYCODE_ALT_LEFT;
    public final Integer keycodeSHIFT = KeyEvent.KEYCODE_SHIFT_LEFT;

    /** Etichette mostrate sui pulsanti a schermo **/
    // A e B sono i nomi che ci si aspetta da un gioco Pokemon, NON i nomi RGSS:
    // il pulsante RGSS chiamato "A" e' quello della corsa (etichettato CORSA).
    //   labelC -> "A"  = conferma / interagisci
    //   labelB -> "B"  = indietro / menu
    public final String labelA = "CORSA";
    public final String labelB = "B";
    public final String labelC = "A";
    public final String labelX = "SU";
    public final String labelY = "GIU";
    public final String labelZ = "OGG";
    // L e R sono il salvataggio rapido e la velocizzazione: sistemati in basso al
    // centro come Select e Start, con la funzione scritta sopra invece della
    // lettera, che non diceva niente.
    public final String labelL = "SALVA";
    public final String labelR = "SPEED";
}
