package dev.cleanspeak.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class ReviewStoreTest {

    @TempDir
    Path temp;

    @Test
    void reviewsPersistAndCanRecordVoiceMuteRemoval() {
        ReviewStore store = new ReviewStore(temp, Logger.getLogger("test"));
        store.mark("request-1", "Staff", "false positive", true);
        ReviewStore reloaded = new ReviewStore(temp, Logger.getLogger("test"));
        assertTrue(reloaded.find("request-1").isPresent());
        assertTrue(reloaded.find("request-1").get().voiceMuteRemoved());
        assertEquals(1, reloaded.all().size());
    }
}
