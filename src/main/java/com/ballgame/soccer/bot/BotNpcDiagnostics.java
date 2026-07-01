package com.ballgame.soccer.bot;

import java.util.logging.Logger;

/** Public hook so the plugin can probe bot visual backends at enable time. */
public final class BotNpcDiagnostics {

    private BotNpcDiagnostics() {
    }

    public static void logAtEnable(Logger log) {
        boolean ok = PlayerNpcSupport.isAvailable();
        if (ok) {
            log.info("Packet player NPC backend: ready.");
        } else {
            log.warning("Packet player NPC backend: unavailable — "
                    + PlayerNpcSupport.unavailableReason());
        }
    }
}
