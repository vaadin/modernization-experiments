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

import com.example.headlines.HeadlineFilter.Scope;
import com.example.headlines.NewsItem.State;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the RSSOwl-style Filter Bar matcher — the pure logic behind the live, local headline
 * filter (title-by-default, scopable to author / category / entire article).
 */
class HeadlineFilterTest {

    private static NewsItem item(String title, String author, String category, String feed, String content) {
        NewsItem n = new NewsItem(1, title, author, category, feed,
                LocalDateTime.of(2026, 1, 1, 0, 0), State.UNREAD, false, null, "https://x/1", false);
        n.setContent(content);
        return n;
    }

    private static final NewsItem KERNEL = item(
            "Linux kernel 6.14 released", "Linus Torvalds", "Software", "LWN",
            "<p>The kernel now supports more hardware.</p>");

    private static boolean m(NewsItem n, Scope s, String term) {
        return HeadlineFilter.matches(n, s, term == null ? null : term.toLowerCase());
    }

    @Test
    void titleScopeMatchesTitleOnlyCaseInsensitively() {
        assertTrue(m(KERNEL, Scope.TITLE, "linux"), "title contains 'linux' (case-insensitive)");
        assertTrue(m(KERNEL, Scope.TITLE, "KERNEL"), "case-insensitive both ways");
        assertFalse(m(KERNEL, Scope.TITLE, "torvalds"), "author text does NOT match the title scope");
        assertFalse(m(KERNEL, Scope.TITLE, "software"), "category text does NOT match the title scope");
    }

    @Test
    void blankOrNullTermMatchesEverything() {
        assertTrue(m(KERNEL, Scope.TITLE, ""), "blank term = no filtering");
        assertTrue(m(KERNEL, Scope.TITLE, "   "), "whitespace term = no filtering");
        assertTrue(m(KERNEL, Scope.TITLE, null), "null term = no filtering");
    }

    @Test
    void entireScopeMatchesTitleAndAllOtherFieldsIncludingBody() {
        assertTrue(m(KERNEL, Scope.ENTIRE, "linux"), "entire includes the title");
        assertTrue(m(KERNEL, Scope.ENTIRE, "torvalds"), "entire matches author");
        assertTrue(m(KERNEL, Scope.ENTIRE, "software"), "entire matches category");
        assertTrue(m(KERNEL, Scope.ENTIRE, "lwn"), "entire matches feed");
        assertTrue(m(KERNEL, Scope.ENTIRE, "hardware"), "entire matches the article body");
        assertFalse(m(KERNEL, Scope.ENTIRE, "windows"), "no field contains the term");
    }

    @Test
    void tolerantOfNullFields() {
        NewsItem sparse = item("Just a title", null, null, "Feed", null);
        assertTrue(m(sparse, Scope.TITLE, "title"), "title still matches");
        assertTrue(m(sparse, Scope.ENTIRE, "title"), "entire still matches the title");
        assertFalse(m(sparse, Scope.ENTIRE, "missing"), "null fields are skipped safely (no NPE)");
    }
}
