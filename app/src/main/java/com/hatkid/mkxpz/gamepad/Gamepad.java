package com.hatkid.mkxpz.gamepad;

import android.annotation.SuppressLint;
import android.content.Context;
import android.view.ViewGroup;
import android.view.LayoutInflater;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.animation.AlphaAnimation;
import android.widget.RelativeLayout;

import com.hatkid.mkxpz.R;
import com.hatkid.mkxpz.utils.ViewUtils;

public class Gamepad
{
    private GamepadConfig mGamepadConfig = null;
    private boolean mInvisible = false;

    // Quando le impostazioni sono aperte i tasti restano VISIBILI (servono da
    // anteprima: si vede l'opacita' che si sta regolando e si prova la posizione
    // col pollice) ma non devono arrivare al gioco, che intanto sta caricando e
    // potrebbe essere sulla schermata del titolo. La pressione si vede comunque,
    // perche' l'animazione del tasto e' indipendente dall'invio del tasto.
    private boolean mInputEnabled = true;

    private OnKeyDownListener mOnKeyDownListener = key -> {};
    private OnKeyUpListener mOnKeyUpListener = key -> {};

    public interface OnKeyDownListener
    {
        void onKeyDown(int key);
    }

    public interface OnKeyUpListener
    {
        void onKeyUp(int key);
    }

    public void setOnKeyDownListener(OnKeyDownListener onKeyDownListener)
    {
        mOnKeyDownListener = onKeyDownListener;
    }

    public void setOnKeyUpListener(OnKeyUpListener onKeyUpListener)
    {
        mOnKeyUpListener = onKeyUpListener;
    }

    private RelativeLayout mGamepadLayout;

    // Gamepad buttons
    private GamepadButton gpadBtnA;
    private GamepadButton gpadBtnB;
    private GamepadButton gpadBtnC;
    private GamepadButton gpadBtnX;
    private GamepadButton gpadBtnY;
    private GamepadButton gpadBtnZ;
    private GamepadButton gpadBtnL;
    private GamepadButton gpadBtnR;
    // CTRL / ALT / SHIFT non esistono nel layout di Fire Ash: il gioco non li usa
    // e stavano sopra la finestra dei dialoghi. Vedi gamepad_layout.xml.

    public void init(GamepadConfig gpadConfig, boolean invisible)
    {
        mGamepadConfig = gpadConfig;
        mInvisible = invisible;
    }

    @SuppressLint("ClickableViewAccessibility")
    public void attachTo(Context context, ViewGroup viewGroup)
    {
        // Setup layout of in-screen gamepad
        LayoutInflater inflater = (LayoutInflater) context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        ViewGroup layout = (ViewGroup) inflater.inflate(R.layout.gamepad_layout, viewGroup);
        mGamepadLayout = layout.findViewById(R.id.gamepad_layout);

        if (mInvisible) {
            mGamepadLayout.setAlpha(0);
        }

        // Setup D-Pad and buttons
        GamepadDPad gpadDPad = layout.findViewById(R.id.dpad);
        gpadBtnA = layout.findViewById(R.id.button_A);
        gpadBtnB = layout.findViewById(R.id.button_B);
        gpadBtnC = layout.findViewById(R.id.button_C);
        gpadBtnX = layout.findViewById(R.id.button_X);
        gpadBtnY = layout.findViewById(R.id.button_Y);
        gpadBtnZ = layout.findViewById(R.id.button_Z);
        gpadBtnL = layout.findViewById(R.id.button_L);
        gpadBtnR = layout.findViewById(R.id.button_R);

        // Setup in-screen gamepad listeners
        mGamepadLayout.setOnTouchListener((view, motionEvent) -> false);
        gpadDPad.setOnKeyDownListener(key -> { if (mInputEnabled) mOnKeyDownListener.onKeyDown(key); });
        gpadDPad.setOnKeyUpListener(key -> { if (mInputEnabled) mOnKeyUpListener.onKeyUp(key); });

        // Configure gamepad
        gpadDPad.isDiagonal = mGamepadConfig.diagonalMovement;

        // Setup buttons for gamepad
        initGamepadButtons();

        // Apply scale and opacity from gamepad config
        ViewUtils.resize(mGamepadLayout, mGamepadConfig.scale);
        ViewUtils.changeOpacity(mGamepadLayout, mGamepadConfig.opacity);
    }

    private void setGamepadButtonKey(GamepadButton gpadBtn, Integer keycode, String label)
    {
        // Un pulsante assente dal layout non e' un errore: il layout di Fire Ash
        // omette CTRL/ALT/SHIFT, che il gioco non usa.
        if (gpadBtn == null)
            return;

        // Prepare label for gamepad button
        String btnLabel = (label != null) ? label
            : KeyEvent.keyCodeToString(keycode)
                .replace("KEYCODE_", "")
                .replace("_LEFT", "")
                .replace("_RIGHT", "");

        // Set gamepad button
        gpadBtn.setForegroundText(btnLabel);
        gpadBtn.setKey(keycode);
        gpadBtn.setOnKeyDownListener(key -> { if (mInputEnabled) mOnKeyDownListener.onKeyDown(key); });
        gpadBtn.setOnKeyUpListener(key -> { if (mInputEnabled) mOnKeyUpListener.onKeyUp(key); });
    }

    /**
     * Accende o spegne l'invio dei tasti al gioco, lasciandoli visibili.
     * Usato mentre la schermata delle impostazioni e' aperta.
     */
    public void setInputEnabled(boolean enabled)
    {
        mInputEnabled = enabled;
    }

    /**
     * Cambia SOLO l'opacita', subito, senza ricostruire i controlli.
     *
     * Serve per l'anteprima mentre si trascina il cursore. Si puo' chiamare
     * quante volte si vuole perche' ViewUtils.changeOpacity imposta un valore
     * assoluto.
     *
     * ATTENZIONE: con la DIMENSIONE non si puo' fare lo stesso.
     * ViewUtils.resize e' CUMULATIVA (moltiplica i parametri di layout attuali),
     * quindi chiamarla due volte all'80% da' il 64%. Per la dimensione serve
     * ricostruire i controlli da zero, cioe' detach + attachTo.
     */
    public void applyOpacity(int opacity)
    {
        if (mGamepadLayout == null || mInvisible)
            return;
        ViewUtils.changeOpacity(mGamepadLayout, opacity);
    }

    /**
     * Stacca i controlli dalla gerarchia di view.
     *
     * Serve alla rotazione dello schermo: MainActivity dichiara
     * configChanges="orientation|screenSize", quindi NON viene ricreata, e senza
     * questo il layout orizzontale resterebbe anche in verticale. Si stacca e si
     * richiama attachTo, che rigonfia la risorsa giusta (res/layout-port o
     * res/layout).
     */
    public void detach()
    {
        if (mGamepadLayout == null)
            return;

        if (mGamepadLayout.getParent() instanceof ViewGroup)
            ((ViewGroup) mGamepadLayout.getParent()).removeView(mGamepadLayout);

        mGamepadLayout = null;
    }

    public void showView()
    {
        if (mGamepadLayout != null) {
            if (mGamepadLayout.getAlpha() == 0)
                mGamepadLayout.setAlpha(1);

            AlphaAnimation anim = new AlphaAnimation(0.0f, 1.0f);
            anim.setDuration(250);
            anim.setFillAfter(true);
            mGamepadLayout.startAnimation(anim);
        }
    }

    public void hideView()
    {
        if (mGamepadLayout != null) {
            AlphaAnimation anim = new AlphaAnimation(1.0f, 0.0f);
            anim.setDuration(500);
            anim.setFillAfter(true);
            mGamepadLayout.startAnimation(anim);
        }
    }

    private void initGamepadButtons()
    {
        setGamepadButtonKey(gpadBtnA, mGamepadConfig.keycodeA, mGamepadConfig.labelA);
        setGamepadButtonKey(gpadBtnB, mGamepadConfig.keycodeB, mGamepadConfig.labelB);
        setGamepadButtonKey(gpadBtnC, mGamepadConfig.keycodeC, mGamepadConfig.labelC);
        setGamepadButtonKey(gpadBtnX, mGamepadConfig.keycodeX, mGamepadConfig.labelX);
        setGamepadButtonKey(gpadBtnY, mGamepadConfig.keycodeY, mGamepadConfig.labelY);
        setGamepadButtonKey(gpadBtnZ, mGamepadConfig.keycodeZ, mGamepadConfig.labelZ);
        setGamepadButtonKey(gpadBtnL, mGamepadConfig.keycodeL, mGamepadConfig.labelL);
        setGamepadButtonKey(gpadBtnR, mGamepadConfig.keycodeR, mGamepadConfig.labelR);
    }

    public boolean processGamepadEvent(KeyEvent evt)
    {
        InputDevice device = evt.getDevice();

        if (device == null)
            return false;

        int sources = device.getSources();

        if (
            ((sources & InputDevice.SOURCE_GAMEPAD) != InputDevice.SOURCE_GAMEPAD) &&
            ((sources & InputDevice.SOURCE_DPAD) != InputDevice.SOURCE_DPAD)
        )
            return false;

        int keycode = evt.getKeyCode();

        switch (evt.getAction())
        {
            case MotionEvent.ACTION_DOWN:
                mOnKeyDownListener.onKeyDown(keycode);
                break;

            case MotionEvent.ACTION_CANCEL:
            case MotionEvent.ACTION_UP:
                mOnKeyUpListener.onKeyUp(keycode);
                break;
        }

        return true;
    }

    public boolean processDPadEvent(MotionEvent evt)
    {
        InputDevice device = evt.getDevice();

        if (device == null)
            return false;

        int sources = device.getSources();

        if (((sources & InputDevice.SOURCE_DPAD) != InputDevice.SOURCE_DPAD))
            return false;

        float xAxis = evt.getAxisValue(MotionEvent.AXIS_HAT_X);
        float yAxis = evt.getAxisValue(MotionEvent.AXIS_HAT_Y);

        Integer keycode = null;

        if (Float.compare(yAxis, -1.0f) == 0)
            keycode = KeyEvent.KEYCODE_DPAD_UP;
        else if (Float.compare(yAxis, 1.0f) == 0)
            keycode = KeyEvent.KEYCODE_DPAD_DOWN;
        else if (Float.compare(xAxis, -1.0f) == 0)
            keycode = KeyEvent.KEYCODE_DPAD_LEFT;
        else if (Float.compare(xAxis, 1.0f) == 0)
            keycode = KeyEvent.KEYCODE_DPAD_RIGHT;

        if (keycode == null)
            return false;

        switch (evt.getAction())
        {
            case MotionEvent.ACTION_DOWN:
                mOnKeyDownListener.onKeyDown(keycode);
                break;

            case MotionEvent.ACTION_CANCEL:
            case MotionEvent.ACTION_UP:
                mOnKeyUpListener.onKeyUp(keycode);
                break;
        }

        return true;
    }
}