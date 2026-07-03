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

/**
 * RSSOwl's news-view <em>Filter Bar</em>: a live, LOCAL text filter over the currently displayed
 * headlines, scoped like RSSOwl's {@code SearchTarget} (Headline / Entire News / Author / Category),
 * defaulting to the headline (title). This is distinct from the global Lucene search (saved searches)
 * and from the News Filters rules engine (conditions → actions).
 */
public final class HeadlineFilter {
    private HeadlineFilter() { }

    /** What the typed text is matched against. {@code ENTIRE} always includes the title. Each scope
     *  carries the placeholder hint shown in the (empty) filter box. */
    public enum Scope {
        TITLE("Title", "Filter by title…"),
        ENTIRE("Entire article", "Filter the whole article…");

        private final String label;
        private final String placeholder;
        Scope(String label, String placeholder) { this.label = label; this.placeholder = placeholder; }
        public String label() { return label; }
        public String placeholder() { return placeholder; }
    }

    /**
     * True if {@code n} matches {@code termLower} (assumed already lower-cased) under {@code scope}.
     * A null/blank term matches everything (no filtering). {@code ENTIRE} includes the title as well as
     * the author, category, feed and body.
     */
    public static boolean matches(NewsItem n, Scope scope, String termLower) {
        if (termLower == null || termLower.isBlank()) return true;
        return switch (scope) {
            case TITLE -> contains(n.title(), termLower);
            case ENTIRE -> contains(n.title(), termLower) || contains(n.author(), termLower)
                    || contains(n.category(), termLower) || contains(n.feed(), termLower)
                    || contains(n.content(), termLower); // raw HTML content — cheap superstring match
        };
    }

    private static boolean contains(String haystack, String needleLower) {
        return haystack != null && haystack.toLowerCase().contains(needleLower);
    }
}
