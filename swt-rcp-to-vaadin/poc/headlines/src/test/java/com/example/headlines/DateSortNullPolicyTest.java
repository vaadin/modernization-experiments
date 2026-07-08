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

import com.example.headlines.NewsItem.State;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Direction-aware null sorting for the Date column. Undated rows must stay at the BOTTOM in both
 * ascending and descending order. The grid reverses a column's comparator for a descending sort, so
 * {@link HeadlinesView#dateItemComparator(boolean)} hands it a nulls-first comparator for descending
 * (which, once reversed by the grid, lands nulls last). These tests simulate that reversal.
 */
class DateSortNullPolicyTest {

    private static NewsItem item(long id, LocalDateTime date) {
        return new NewsItem(id, "t" + id, "a", "C", "F", date, State.UNREAD, false, null, "https://x/" + id, false);
    }

    private static final NewsItem OLD = item(1, LocalDateTime.of(2020, 1, 1, 0, 0));
    private static final NewsItem NEW = item(2, LocalDateTime.of(2026, 1, 1, 0, 0));
    private static final NewsItem UNDATED = item(3, null);

    @Test
    void ascendingPutsUndatedLast_oldestFirst() {
        // Ascending: the grid uses the comparator as-is.
        List<NewsItem> rows = new ArrayList<>(List.of(UNDATED, NEW, OLD));
        rows.sort(HeadlinesView.dateItemComparator(false));
        assertEquals(List.of(OLD, NEW, UNDATED), rows, "oldest first, undated last");
        assertNull(rows.get(rows.size() - 1).date(), "undated row is last");
    }

    @Test
    void descendingPutsUndatedLast_newestFirst() {
        // Descending: the grid REVERSES the comparator. Simulate that with .reversed().
        Comparator<NewsItem> asGridSorts = HeadlinesView.dateItemComparator(true).reversed();
        List<NewsItem> rows = new ArrayList<>(List.of(UNDATED, OLD, NEW));
        rows.sort(asGridSorts);
        assertEquals(List.of(NEW, OLD, UNDATED), rows, "newest first, undated STILL last");
        assertNull(rows.get(rows.size() - 1).date(), "undated row is last even descending");
    }
}
