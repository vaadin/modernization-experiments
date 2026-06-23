package com.example.headlines;

import com.example.headlines.NewsItem.State;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * A small in-memory fixture so the rendering rules are exercised without a real feed backend:
 * a mix of read/unread/new/updated, some sticky, some label-coloured, one with a null date.
 */
final class DemoData {
    private DemoData() {}

    static List<NewsItem> sample() {
        List<NewsItem> l = new ArrayList<>();
        l.add(new NewsItem(1, "Vaadin 25.1 released with Signals", "Vaadin Team", "Releases",
                LocalDateTime.of(2026, 6, 15, 12, 19), State.NEW, false, "#1565c0",
                "https://vaadin.com/releases/vaadin-25"));
        l.add(new NewsItem(2, "Apple to phase out Rosetta 2", "Press", "Platform",
                LocalDateTime.of(2026, 6, 10, 9, 0), State.UNREAD, true, null,
                "https://example.com/rosetta"));
        l.add(new NewsItem(3, "Eclipse 4.30 ships native arm64 SWT", "Eclipse Foundation", "Tools",
                LocalDateTime.of(2023, 11, 30, 1, 10), State.READ, false, "#2e7d32",
                "https://eclipse.dev/eclipse/news"));
        l.add(new NewsItem(4, "RSSOwl: a retrospective", "Community", "Opinion",
                LocalDateTime.of(2019, 5, 8, 0, 0), State.READ, false, null,
                "https://example.com/rssowl"));
        l.add(new NewsItem(5, "Migrating SWT tables to Vaadin Grid", "E. Haase", "Migration",
                LocalDateTime.of(2026, 6, 23, 8, 30), State.UPDATED, true, "#1565c0",
                "https://example.com/swt-grid"));
        l.add(new NewsItem(6, "JFace viewers explained", "Docs", "Tools",
                LocalDateTime.of(2012, 1, 2, 0, 0), State.READ, false, null,
                "https://example.com/jface"));
        l.add(new NewsItem(7, "Owner-draw rendering: the hard parts", "E. Haase", "Migration",
                LocalDateTime.of(2026, 6, 22, 17, 45), State.UNREAD, false, "#c62828",
                "https://example.com/owner-draw"));
        l.add(new NewsItem(8, "A headline with no date", "Anonymous", "Misc",
                null, State.UNREAD, false, null, "https://example.com/no-date"));
        l.add(new NewsItem(9, "Zero-install distribution wins", "Marketing", "Opinion",
                LocalDateTime.of(2026, 6, 1, 11, 0), State.READ, true, "#2e7d32",
                "https://example.com/zero-install"));
        return l;
    }
}
