/*
 * Copyright (c) 2026 Vaadin Ltd.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at https://www.eclipse.org/legal/epl-v10.html
 *
 * SPDX-License-Identifier: EPL-1.0
 */

package com.example.headlines;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Groups headlines into buckets, mirroring a representative subset of RSSOwl's
 * {@code NewsGrouping.Type}: by date (Today/Yesterday/Earlier this Week/Last Week/Older), status,
 * author, category, feed, or sticky. Pure logic — the view turns each {@link Bucket} into a
 * {@link Row.GroupRow} parent over {@link Row.ItemRow} children.
 */
final class Grouping {
    private Grouping() {}

    /** Mirrors RSSOwl's {@code NewsGrouping.Type} (labels verbatim, same order). Rating is omitted — we
     *  have no per-item ratings. */
    enum GroupBy {
        NONE("No Grouping"), DATE("Group by Date"), STATE("Group by State"), AUTHOR("Group by Author"),
        CATEGORY("Group by Category"), TITLE("Group by Title"), FEED("Group by Feed"),
        LABEL("Group by Label"), STICKY("Group by Stickyness");
        private final String label;
        GroupBy(String label) { this.label = label; }
        String label() { return label; }
    }

    /** A group header plus the items under it (already in the group's intended order). */
    record Bucket(String key, String label, String colorHint, int orderIndex, List<NewsItem> items) {}

    /** Returns ordered, non-empty buckets for the given dimension. NONE returns an empty list
     *  (the caller renders a flat list instead). */
    static List<Bucket> group(List<NewsItem> items, GroupBy by) {
        return switch (by) {
            case NONE -> List.of();
            case DATE -> byDate(items);
            case STATE -> byState(items);
            case STICKY -> bySticky(items);
            case AUTHOR -> byKey(items, NewsItem::author, "Unknown");
            // RSSOwl's Group-by-Category keys on the item's own <category> tags, not the folder.
            case CATEGORY -> byKey(items, NewsItem::categories, "Uncategorized");
            case TITLE -> byKey(items, NewsItem::title, "(untitled)");
            case FEED -> byKey(items, NewsItem::feed, "Unknown");
            case LABEL -> byKey(items, n -> n.labels().isEmpty() ? null : n.labels().get(0).name(),
                    "No Label");
        };
    }

    // --- fixed-order dimensions ---

    private static List<Bucket> byDate(List<NewsItem> items) {
        LocalDateTime today = LocalDate.now().atStartOfDay();
        LocalDateTime yesterday = today.minusDays(1);
        LocalDateTime weekStart = LocalDate.now().with(DayOfWeek.MONDAY).atStartOfDay();
        LocalDateTime lastWeekStart = weekStart.minusWeeks(1);
        String[] names = {"Today", "Yesterday", "Earlier this Week", "Last Week", "Older"};
        List<List<NewsItem>> buckets = new ArrayList<>();
        for (int i = 0; i < names.length; i++) buckets.add(new ArrayList<>());
        for (NewsItem n : items) {
            LocalDateTime d = n.date();
            int idx;
            if (d == null) idx = 4;
            else if (!d.isBefore(today)) idx = 0;
            else if (!d.isBefore(yesterday)) idx = 1;
            else if (!d.isBefore(weekStart)) idx = 2;
            else if (!d.isBefore(lastWeekStart)) idx = 3;
            else idx = 4;
            buckets.get(idx).add(n);
        }
        List<Bucket> out = new ArrayList<>();
        for (int i = 0; i < names.length; i++) {
            if (!buckets.get(i).isEmpty()) {
                out.add(new Bucket("date:" + i, names[i], null, i, buckets.get(i)));
            }
        }
        return out;
    }

    private static List<Bucket> byState(List<NewsItem> items) {
        NewsItem.State[] order = {NewsItem.State.NEW, NewsItem.State.UPDATED,
                NewsItem.State.UNREAD, NewsItem.State.READ};
        String[] names = {"New", "Updated", "Unread", "Read"};
        List<Bucket> out = new ArrayList<>();
        for (int i = 0; i < order.length; i++) {
            NewsItem.State st = order[i];
            List<NewsItem> in = items.stream().filter(n -> n.state() == st).toList();
            if (!in.isEmpty()) out.add(new Bucket("state:" + st, names[i], null, i, in));
        }
        return out;
    }

    private static List<Bucket> bySticky(List<NewsItem> items) {
        List<NewsItem> sticky = items.stream().filter(NewsItem::sticky).toList();
        List<NewsItem> not = items.stream().filter(n -> !n.sticky()).toList();
        List<Bucket> out = new ArrayList<>();
        if (!sticky.isEmpty()) out.add(new Bucket("sticky:1", "Sticky", null, 0, sticky));
        if (!not.isEmpty()) out.add(new Bucket("sticky:0", "Not sticky", null, 1, not));
        return out;
    }

    // --- dynamic, alphabetical dimensions ---

    private static List<Bucket> byKey(List<NewsItem> items,
            java.util.function.Function<NewsItem, String> keyFn, String unknown) {
        Map<String, List<NewsItem>> map = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (NewsItem n : items) {
            String k = keyFn.apply(n);
            if (k == null || k.isBlank()) k = unknown;
            map.computeIfAbsent(k, x -> new ArrayList<>()).add(n);
        }
        // Preserve alphabetical order from the TreeMap; assign orderIndex accordingly.
        Map<String, List<NewsItem>> ordered = new LinkedHashMap<>(map);
        List<Bucket> out = new ArrayList<>();
        int i = 0;
        for (var e : ordered.entrySet()) {
            out.add(new Bucket("k:" + e.getKey(), e.getKey(), null, i++, e.getValue()));
        }
        return out;
    }

    /** Comparator used to keep group rows in their intended order regardless of the sorted column. */
    static final Comparator<Row.GroupRow> GROUP_ORDER =
            Comparator.comparingInt(Row.GroupRow::orderIndex);
}