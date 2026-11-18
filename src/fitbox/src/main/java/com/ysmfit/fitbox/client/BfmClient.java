package com.ysmfit.fitbox.client;

import com.ysmfit.fitbox.Bfm;
import com.ysmfit.fitbox.FitboxMod;

/** Installs Battlefield Movement's client-side reader ({@link Bfm.Reflected}) when the mod is present. */
final class BfmClient {
    private BfmClient() {}

    static void install() {
        if (!Bfm.loaded()) {
            return;
        }
        try {
            Bfm.client = new Bfm.Reflected(true);
            FitboxMod.LOG.info("Battlefield Movement found: its prone, slide and dive states pick fitted shapes");
        } catch (ReflectiveOperationException | LinkageError | ClassCastException e) {
            FitboxMod.LOG.warn("Unsupported Battlefield Movement version; its states are not recognised", e);
        }
    }
}
