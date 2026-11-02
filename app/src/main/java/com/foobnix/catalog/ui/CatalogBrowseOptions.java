package com.foobnix.catalog.ui;

import com.foobnix.catalog.CatalogAcquisition;
import com.foobnix.catalog.CatalogEntry;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Filters only the supplied page. Server navigation remains available independently. */
public final class CatalogBrowseOptions {
    public String author = "";
    public String format = "";
    public boolean downloaded;
    public int sort; // 0: source order, 1: title, 2: author

    public boolean active() { return !author.trim().isEmpty() || !format.isEmpty() || downloaded; }

    public List<CatalogEntry> apply(List<CatalogEntry> input, Set<String> localIds, Locale locale) {
        List<CatalogEntry> navigation = new ArrayList<>(), books = new ArrayList<>();
        String query = author.trim().toLowerCase(Locale.ROOT);
        for (CatalogEntry entry : input) {
            if (entry.acquisitions.isEmpty() && !localIds.contains(entry.id)) {
                navigation.add(entry); // A format filter must never hide the way to another section.
                continue;
            }
            if (downloaded && !localIds.contains(entry.id)) continue;
            if (!query.isEmpty() && !safe(entry.author).toLowerCase(Locale.ROOT).contains(query)) continue;
            if (!format.isEmpty()) {
                boolean matches = false;
                for (CatalogAcquisition acquisition : entry.acquisitions) {
                    if (format.equalsIgnoreCase(acquisition.format)) { matches = true; break; }
                }
                if (!matches) continue;
            }
            books.add(entry);
        }
        Collator collator = Collator.getInstance(locale);
        collator.setStrength(Collator.PRIMARY);
        Comparator<CatalogEntry> titles = (a, b) -> collator.compare(safe(a.title), safe(b.title));
        if (sort == 1) books.sort(titles);
        else if (sort == 2) books.sort(((Comparator<CatalogEntry>) (a, b) ->
                collator.compare(safe(a.author), safe(b.author))).thenComparing(titles));
        navigation.addAll(books);
        return navigation;
    }

    private static String safe(String value) { return value == null ? "" : value; }
}
