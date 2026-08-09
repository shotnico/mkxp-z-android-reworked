package com.hatkid.mkxpz.gamepad;

import android.view.KeyEvent;

public class GamepadConfig
{
    /** In-screen gamepad settings **/

    // Opacity of view elements in percentage (default: 30)
    // Fire Ash: 30 e' troppo tenue sopra le mappe chiare dell'overworld.
    public Integer opacity = 45;

    // View elements scale in percentage (default: 100)
    public Integer scale = 100;

    // Whether use diagonal (8-way) movement on D-Pad (default: false)
    // Pokemon Essentials ha movimento a 4 direzioni: lasciare false, altrimenti
    // le diagonali generano input che il gioco non sa gestire.
    public Boolean diagonalMovement = false;

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
    // Il comportamento originale disegnava il nome del tasto di tastiera
    // ("Z", "X", "C", "A"...), incomprensibile per chi gioca. Qui usiamo le
    // funzioni reali in Fire Ash, come da "bindingNames" di mkxp.json.
    // Tenerle corte: il testo viene rimpicciolito fino a entrare nel pulsante.
    public final String labelA = "CORSA";
    public final String labelB = "MENU";
    public final String labelC = "USA";
    public final String labelX = "SU";
    public final String labelY = "GIU";
    public final String labelZ = "OGG";
    public final String labelL = "L";
    public final String labelR = "R";
}
