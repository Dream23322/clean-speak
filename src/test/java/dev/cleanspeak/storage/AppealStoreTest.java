package dev.cleanspeak.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class AppealStoreTest {

    @TempDir
    Path temp;

    @Test
    void appealsCanBeCreatedListedAndResolved() {
        AppealStore store = new AppealStore(temp, Logger.getLogger("test"));
        var appeal = store.create("request-1", "player-1", "Tester", "please review");
        assertTrue(appeal.open());
        assertEquals(1, store.open().size());
        assertTrue(store.findOpenForPlayer("request-1", "player-1").isPresent());
        assertTrue(store.resolve(appeal.id(), "Staff", "reviewed"));
        assertTrue(store.open().isEmpty());

        AppealStore reloaded = new AppealStore(temp, Logger.getLogger("test"));
        assertTrue(reloaded.find(appeal.id()).isPresent());
        assertFalse(reloaded.find(appeal.id()).get().open());
    }

    @Test
    void duplicateOpenAppealIsReused() {
        AppealStore store = new AppealStore(temp, Logger.getLogger("test"));
        var first = store.create("request-1", "player-1", "Tester", "one");
        var second = store.create("request-1", "player-1", "Tester", "two");
        assertEquals(first.id(), second.id());
    }
}
