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

import com.example.headlines.Grouping.Bucket;
import com.example.headlines.Grouping.GroupBy;
import com.example.headlines.NewsItem.State;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-logic tests for {@link Grouping} — the Vaadin equivalent of RSSOwl's headless grouping/filter
 * tests (e.g. {@code NewsGroupFilterTest}, {@code NewsComparator} ordering). No Spring, no browser.
 */
class GroupingTest {

    private static NewsItem item(long id, String author, String category, String feed,
            State state, boolean sticky) {
        return new NewsItem(id, "Title " + id, author, category, feed,
                LocalDateTime.of(2026, 1, 1, 0, 0), state, sticky, null, "https://example/" + id, false);
    }

    @Test
    void none_returnsNoBuckets() {
        assertTrue(Grouping.group(List.of(item(1, "a", "c", "f", State.UNREAD, false)), GroupBy.NONE).isEmpty());
    }

    @Test
    void byFeed_isAlphabeticalCaseInsensitiveAndPartitions() {
        var items = List.of(
                item(1, "a", "c", "Zebra", State.UNREAD, false),
                item(2, "a", "c", "alpha", State.READ, false),
                item(3, "a", "c", "alpha", State.NEW, false));
        var buckets = Grouping.group(items, GroupBy.FEED);
        assertEquals(List.of("alpha", "Zebra"), buckets.stream().map(Bucket::label).toList());
        assertEquals(2, buckets.get(0).items().size()); // both "alpha" items
    }

    @Test
    void byState_ordersNewUpdatedUnreadRead_andSkipsEmpty() {
        var items = List.of(
                item(1, "a", "c", "f", State.READ, false),
                item(2, "a", "c", "f", State.NEW, false),
                item(3, "a", "c", "f", State.UNREAD, false));
        var labels = Grouping.group(items, GroupBy.STATE).stream().map(Bucket::label).toList();
        assertEquals(List.of("New", "Unread", "Read"), labels); // "Updated" bucket omitted (empty)
    }

    @Test
    void bySticky_splitsStickyFirst() {
        var items = List.of(
                item(1, "a", "c", "f", State.UNREAD, true),
                item(2, "a", "c", "f", State.UNREAD, false));
        var labels = Grouping.group(items, GroupBy.STICKY).stream().map(Bucket::label).toList();
        assertEquals(List.of("Sticky", "Not sticky"), labels);
    }

    @Test
    void byCategory_usesTheItemsRealTags_notTheFolder() {
        NewsItem tech = item(1, "a", "Business", "f", State.UNREAD, false); // "Business" is the FOLDER
        tech.setCategories("Tech");
        NewsItem none = item(2, "a", "Business", "f", State.UNREAD, false); // no tags
        var buckets = Grouping.group(List.of(tech, none), GroupBy.CATEGORY);
        // Grouped by the real <category> tag "Tech" and the "Uncategorized" fallback — NOT by "Business".
        assertEquals(List.of("Tech", "Uncategorized"), buckets.stream().map(Bucket::label).sorted().toList());
    }

    @Test
    void byLabel_groupsByFirstLabel_withNoLabelFallback() {
        NewsItem flagged = item(1, "a", "c", "f", State.UNREAD, false);
        flagged.setLabels(List.of(new NewsItem.LabelRef(1, "Important", "#f00")));
        NewsItem plain = item(2, "a", "c", "f", State.UNREAD, false);
        var labels = Grouping.group(List.of(flagged, plain), GroupBy.LABEL).stream().map(Bucket::label).toList();
        assertEquals(List.of("Important", "No Label"), labels); // alphabetical: "Important" < "No Label"
    }
}
