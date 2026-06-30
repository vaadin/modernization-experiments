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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Model tests for {@link NewsItem} — the read/unread/sticky/status semantics ported from RSSOwl's
 * {@code INews.State} behaviour ({@code NewsTableLabelProvider.getFont} bold rule, status ordering).
 */
class NewsItemTest {

    private static NewsItem withState(State state) {
        return new NewsItem(1, "T", "A", "C", "F", LocalDateTime.now(), state, false, null, "https://x/1", false);
    }

    @Test
    void unreadIsTrueForNewUpdatedUnread_falseForRead() {
        assertTrue(withState(State.NEW).unread());
        assertTrue(withState(State.UPDATED).unread());
        assertTrue(withState(State.UNREAD).unread());
        assertFalse(withState(State.READ).unread());
    }

    @Test
    void toggleReadFlipsBothWays() {
        NewsItem n = withState(State.UNREAD);
        n.toggleRead();
        assertFalse(n.unread(), "unread -> read");
        n.toggleRead();
        assertTrue(n.unread(), "read -> unread");
    }

    @Test
    void toggleStickyFlips() {
        NewsItem n = withState(State.READ);
        assertFalse(n.sticky());
        n.toggleSticky();
        assertTrue(n.sticky());
    }

    @Test
    void setReadAndSetStickyAreExplicitAndIdempotent() {
        NewsItem n = withState(State.UNREAD);
        n.setRead(true);
        assertFalse(n.unread(), "explicitly marked read");
        n.setRead(true); // idempotent — bulk actions may set the same state repeatedly
        assertFalse(n.unread());
        n.setRead(false);
        assertTrue(n.unread(), "explicitly marked unread");

        n.setSticky(true);
        assertTrue(n.sticky());
        n.setSticky(false);
        assertFalse(n.sticky());
    }

    @Test
    void statusRankOrdersNewBeforeUpdatedBeforeUnreadBeforeRead() {
        assertTrue(withState(State.NEW).statusRank() < withState(State.UPDATED).statusRank());
        assertTrue(withState(State.UPDATED).statusRank() < withState(State.UNREAD).statusRank());
        assertTrue(withState(State.UNREAD).statusRank() < withState(State.READ).statusRank());
    }

    @Test
    void identityIsByIdSoStateChangesPreserveEquality() {
        NewsItem a = new NewsItem(7, "T", "A", "C", "F", null, State.UNREAD, false, null, "https://x", false);
        NewsItem sameId = new NewsItem(7, "different", "z", "z", "z", null, State.READ, true, "#fff", "https://y", true);
        NewsItem otherId = new NewsItem(8, "T", "A", "C", "F", null, State.UNREAD, false, null, "https://x", false);
        assertEquals(a, sameId, "equal by id regardless of other fields/state");
        assertEquals(a.hashCode(), sameId.hashCode());
        assertNotEquals(a, otherId);
    }
}
