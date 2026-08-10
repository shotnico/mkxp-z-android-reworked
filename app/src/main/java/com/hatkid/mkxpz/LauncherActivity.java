package com.hatkid.mkxpz;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.RadioButton;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import com.hatkid.mkxpz.gamepad.GamepadConfig;
import com.hatkid.mkxpz.utils.GameFolder;

/**
 * Schermata iniziale: si apre prima del gioco.
 *
 * Esiste per tre motivi:
 *
 *  1. Copre l'attesa. L'avvio del gioco richiede ~85 s, di cui ~80 in cui
 *     mkxp-z non presenta ancora nulla e lo schermo resta nero (misurato: nemmeno
 *     un rettangolo disegnato a z=999999 compare). Le View Android invece si
 *     vedono, perche' non passano dalla presentazione di mkxp-z: da qui la
 *     schermata di caricamento in MainActivity.
 *  2. Permette di regolare opacita', dimensione dei tasti e orientamento senza
 *     ricompilare l'APK: i valori finiscono in SharedPreferences e li rilegge
 *     GamepadConfig.load().
 *  3. Permette di scegliere la lingua, scambiando le cartelle Data (GameFolder).
 *
 * Qui viene anche chiesto il permesso di accesso a tutti i file: serve sia per
 * leggere il gioco sia per rinominare le cartelle dei dati.
 */
public class LauncherActivity extends Activity
{
    private static final int REQ_ALL_FILES = 110;

    private SharedPreferences mPrefs;
    private SeekBar mOpacity;
    private SeekBar mScale;
    private TextView mOpacityLabel;
    private TextView mScaleLabel;
    private TextView mStatus;

    @Override
    protected void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_launcher);

        mPrefs = getSharedPreferences(GamepadConfig.PREFS, MODE_PRIVATE);

        mOpacityLabel = findViewById(R.id.label_opacity);
        mScaleLabel   = findViewById(R.id.label_scale);
        mStatus       = findViewById(R.id.status);
        mOpacity      = findViewById(R.id.seek_opacity);
        mScale        = findViewById(R.id.seek_scale);

        GamepadConfig cfg = GamepadConfig.load(this);
        mOpacity.setProgress(cfg.opacity);
        mScale.setProgress(cfg.scale);
        updateLabels();

        SeekBar.OnSeekBarChangeListener l = new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int v, boolean fromUser) { updateLabels(); }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { save(); }
        };
        mOpacity.setOnSeekBarChangeListener(l);
        mScale.setOnSeekBarChangeListener(l);

        // orientamento
        int orient = mPrefs.getInt(GamepadConfig.KEY_ORIENTATION, GamepadConfig.ORIENT_AUTO);
        ((RadioButton) findViewById(orient == GamepadConfig.ORIENT_LANDSCAPE ? R.id.orient_land
                                  : orient == GamepadConfig.ORIENT_PORTRAIT  ? R.id.orient_port
                                  : R.id.orient_auto)).setChecked(true);

        // lingua
        String lang = mPrefs.getString(GamepadConfig.KEY_LANGUAGE, GameFolder.LANG_IT);
        ((RadioButton) findViewById(GameFolder.LANG_EN.equals(lang) ? R.id.lang_en : R.id.lang_it))
            .setChecked(true);

        findViewById(R.id.button_play).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { play(); }
        });

        requestAllFilesAccess();
        refreshStatus();
    }

    private void updateLabels()
    {
        mOpacityLabel.setText(getString(R.string.opacity_value, mOpacity.getProgress()));
        mScaleLabel.setText(getString(R.string.scale_value, mScale.getProgress()));
    }

    /** Salva opacita', dimensione e orientamento. La lingua no: la gestisce play(). */
    private void save()
    {
        int orient = GamepadConfig.ORIENT_AUTO;
        if (((RadioButton) findViewById(R.id.orient_land)).isChecked())
            orient = GamepadConfig.ORIENT_LANDSCAPE;
        else if (((RadioButton) findViewById(R.id.orient_port)).isChecked())
            orient = GamepadConfig.ORIENT_PORTRAIT;

        mPrefs.edit()
              .putInt(GamepadConfig.KEY_OPACITY, mOpacity.getProgress())
              .putInt(GamepadConfig.KEY_SCALE, mScale.getProgress())
              .putInt(GamepadConfig.KEY_ORIENTATION, orient)
              .apply();
    }

    private void refreshStatus()
    {
        if (!hasAllFilesAccess()) {
            mStatus.setText(R.string.status_no_permission);
            return;
        }
        if (!GameFolder.isPresent()) {
            mStatus.setText(R.string.status_no_game);
            return;
        }
        mStatus.setText(getString(R.string.status_ok, GameFolder.resolve()));
    }

    private void play()
    {
        if (!hasAllFilesAccess()) {
            requestAllFilesAccess();
            return;
        }

        if (!GameFolder.isPresent()) {
            Toast.makeText(this, R.string.status_no_game, Toast.LENGTH_LONG).show();
            return;
        }

        save();

        // Lingua: se cambiata, si scambiano le cartelle Data prima di avviare.
        String current = mPrefs.getString(GamepadConfig.KEY_LANGUAGE, GameFolder.LANG_IT);
        String wanted  = ((RadioButton) findViewById(R.id.lang_en)).isChecked()
                       ? GameFolder.LANG_EN : GameFolder.LANG_IT;

        if (!wanted.equals(current)) {
            if (GameFolder.switchTo(current, wanted)) {
                mPrefs.edit().putString(GamepadConfig.KEY_LANGUAGE, wanted).apply();
            } else {
                Toast.makeText(this, R.string.lang_missing, Toast.LENGTH_LONG).show();
                return;
            }
        }

        startActivity(new Intent(this, MainActivity.class));
        finish();
    }

    // ---- permesso di accesso a tutti i file ---------------------------------

    private boolean hasAllFilesAccess()
    {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R)
            return true;
        return Environment.isExternalStorageManager();
    }

    private void requestAllFilesAccess()
    {
        if (hasAllFilesAccess())
            return;

        try {
            Uri uri = Uri.parse("package:" + getPackageName());
            startActivityForResult(
                new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, uri),
                REQ_ALL_FILES);
        } catch (Exception e) {
            startActivityForResult(
                new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION),
                REQ_ALL_FILES);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data)
    {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_ALL_FILES)
            refreshStatus();
    }

    @Override
    protected void onResume()
    {
        super.onResume();
        refreshStatus();
    }
}
