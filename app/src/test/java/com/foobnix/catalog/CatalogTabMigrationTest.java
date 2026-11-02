package com.foobnix.catalog;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class CatalogTabMigrationTest {
    @Test public void preservesCustomOrderAndHiddenTabs() {
        assertEquals("2#1,0#0,5#1,6#0,8#1",
                CatalogTabMigration.migrate("2#1,0#0,5#1,6#0", "8#1"));
    }
    @Test public void respectsExplicitlyHiddenCatalog() {
        assertEquals("8#0,0#1", CatalogTabMigration.migrate("8#0,0#1", "8#1"));
    }
    @Test public void migrationIsIdempotent() {
        String once = CatalogTabMigration.migrate("0#1", "8#1");
        assertEquals(once, CatalogTabMigration.migrate(once, "8#1"));
    }
    @Test public void missingSettingsUseDefaults() {
        assertEquals("0#1,8#1", CatalogTabMigration.migrate(null, "0#1,8#1"));
        assertEquals("0#1,8#1", CatalogTabMigration.migrate("", "0#1,8#1"));
    }
}
