package com.foobnix.catalog.ui;

import com.foobnix.catalog.CatalogAcquisition;
import com.foobnix.catalog.CatalogEntry;
import org.junit.Test;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import static org.junit.Assert.*;

public class CatalogBrowseOptionsTest {
    private CatalogEntry book(String id, String title, String author, String format) {
        CatalogEntry e = new CatalogEntry(); e.id = id; e.title = title; e.author = author;
        CatalogAcquisition a = new CatalogAcquisition(); a.format = format; e.acquisitions.add(a);
        return e;
    }
    @Test public void filtersComposeWithoutMutatingTheSourcePage() {
        CatalogEntry a = book("1", "Онегин", "Александр Пушкин", "EPUB");
        CatalogEntry b = book("2", "Дубровский", "Александр Пушкин", "FB2.ZIP");
        CatalogEntry c = book("3", "Обломов", "Иван Гончаров", "EPUB");
        List<CatalogEntry> input = Arrays.asList(a,b,c);
        CatalogBrowseOptions o = new CatalogBrowseOptions();
        o.author = "  ПУШКИН "; o.format = "epub"; o.downloaded = true;
        assertEquals(Collections.singletonList(a), o.apply(input, Collections.singleton("1"), new Locale("ru")));
        assertEquals(3, input.size());
        assertTrue(o.active());
    }
    @Test public void navigationSurvivesFiltersAndPreservesItsOrder() {
        CatalogEntry nav = new CatalogEntry(); nav.title = "Жанры"; nav.navigationUrl = "/opds/genres";
        CatalogBrowseOptions o = new CatalogBrowseOptions(); o.format = "PDF"; o.sort = 1;
        assertEquals(Collections.singletonList(nav), o.apply(Arrays.asList(nav, book("1","A","B","EPUB")),
                Collections.emptySet(), Locale.ENGLISH));
    }
    @Test public void sortingUsesLocaleAndKeepsSourceOrderWhenUnset() {
        CatalogEntry b = book("1","Б","Я","EPUB"), a = book("2","А","Ю","EPUB");
        CatalogBrowseOptions o = new CatalogBrowseOptions();
        assertEquals(Arrays.asList(b,a), o.apply(Arrays.asList(b,a), Collections.emptySet(), new Locale("ru")));
        o.sort = 1;
        assertEquals(Arrays.asList(a,b), o.apply(Arrays.asList(b,a), Collections.emptySet(), new Locale("ru")));
        o.sort = 2;
        assertEquals(Arrays.asList(a,b), o.apply(Arrays.asList(b,a), Collections.emptySet(), new Locale("ru")));
    }
    @Test public void downloadedLocalBookWithoutAcquisitionsCanBeFilteredByAuthor() {
        CatalogEntry local = new CatalogEntry(); local.id = "local"; local.author = "Достоевский";
        CatalogBrowseOptions o = new CatalogBrowseOptions(); o.author = "Пушкин";
        assertTrue(o.apply(Collections.singletonList(local), Collections.singleton("local"), new Locale("ru")).isEmpty());
    }
}
