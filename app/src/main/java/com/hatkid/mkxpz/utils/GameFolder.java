package com.hatkid.mkxpz.utils;

import android.os.Environment;

import java.io.File;

/**
 * Individua la cartella del gioco e gestisce lo scambio della lingua.
 *
 * Lo scambio funziona rinominando le cartelle dei dati: fra italiano e inglese
 * cambia SOLO Data/ (Graphics e Audio sono identici, ~51 MB contro ~550), e
 * rinominare una cartella dentro la stessa memoria e' istantaneo, non copia nulla.
 *
 *   italiano attivo:  Data (contenuto IT)  +  Data-EN
 *   inglese  attivo:  Data (contenuto EN)  +  Data-IT
 *
 * I salvataggi stanno in "Save Files", fuori da Data/, quindi sopravvivono al
 * cambio lingua.
 */
public class GameFolder
{
    public static final String LANG_IT = "it";
    public static final String LANG_EN = "en";

    private static final String[] CANDIDATES = {
        Environment.getExternalStorageDirectory() + "/FireAshITA",
        Environment.getExternalStorageDirectory() + "/mkxp-z",
    };

    /**
     * Prima cartella che contiene davvero Data/. Se nessuna esiste ritorna la
     * prima, cosi' mkxp-z mostra il suo errore normale.
     *
     * Va chiamata quando il permesso di accesso allo storage e' gia' concesso:
     * prima, ogni isDirectory() su /sdcard ritorna false.
     */
    public static String resolve()
    {
        for (String c : CANDIDATES) {
            if (new File(c, "Data").isDirectory())
                return c;
        }
        return CANDIDATES[0];
    }

    /** true se la cartella di gioco e' presente e utilizzabile. */
    public static boolean isPresent()
    {
        for (String c : CANDIDATES) {
            if (new File(c, "Data").isDirectory())
                return true;
        }
        return false;
    }

    /** Nome della cartella di riserva per una lingua: Data-IT / Data-EN. */
    private static File spare(String root, String lang)
    {
        return new File(root, LANG_EN.equals(lang) ? "Data-EN" : "Data-IT");
    }

    /** true se il pacchetto dell'altra lingua e' disponibile sul telefono. */
    public static boolean hasLanguage(String lang, String current)
    {
        String root = resolve();
        if (lang.equals(current))
            return new File(root, "Data").isDirectory();
        return spare(root, lang).isDirectory();
    }

    /**
     * Rende attiva la lingua richiesta.
     *
     * @param current lingua attualmente attiva (da SharedPreferences)
     * @param wanted  lingua da attivare
     * @return true se ora la lingua attiva e' quella richiesta
     */
    public static boolean switchTo(String current, String wanted)
    {
        if (wanted == null || wanted.equals(current))
            return true;

        String root = resolve();
        File active = new File(root, "Data");
        File incoming = spare(root, wanted);          // Data-EN o Data-IT
        File outgoing = spare(root, current);         // dove parcheggiare l'attuale

        if (!incoming.isDirectory())
            return false;                             // pacchetto non presente
        if (outgoing.exists())
            return false;                             // stato incoerente: non forzare

        // Due rinomine, nell'ordine: prima si libera il nome "Data".
        if (active.isDirectory() && !active.renameTo(outgoing))
            return false;

        if (!incoming.renameTo(active)) {
            outgoing.renameTo(active);                // tentativo di ripristino
            return false;
        }

        return true;
    }
}
