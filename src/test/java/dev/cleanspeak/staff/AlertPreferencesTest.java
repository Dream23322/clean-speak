package dev.cleanspeak.staff;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AlertPreferencesTest {

    @TempDir
    Path temp;

    @Test
    void togglesAndPersistsPerPlayer() {
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        AlertPreferences preferences = new AlertPreferences(temp, Logger.getLogger("test"));
        assertTrue(preferences.enabled(alice));

        assertFalse(preferences.toggle(alice));
        assertFalse(preferences.enabled(alice));
        assertTrue(preferences.enabled(bob));

        AlertPreferences reloaded = new AlertPreferences(temp, Logger.getLogger("test"));
        assertFalse(reloaded.enabled(alice));
        assertTrue(reloaded.toggle(alice));
        assertTrue(reloaded.enabled(alice));

        reloaded.set(bob, false);
        assertFalse(new AlertPreferences(temp, Logger.getLogger("test")).enabled(bob));
    }
}
