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

    /** What the typed text is matched against — mirrors RSSOwl's {@code NewsFilter.SearchTarget}. */
    public enum Scope {
        TITLE("Title"),            // RSSOwl's HEADLINE (default)
        ENTIRE("Entire article"),  // RSSOwl's ALL / Entire News
        AUTHOR("Author"),
        CATEGORY("Category");

        private final String label;
        Scope(String label) { this.label = label; }
        public String label() { return label; }
    }

    /**
     * True if {@code n} matches {@code termLower} (assumed already lower-cased) under {@code scope}.
     * A null/blank term matches everything (no filtering).
     */
    public static boolean matches(NewsItem n, Scope scope, String termLower) {
        if (termLower == null || termLower.isBlank()) return true;
        return switch (scope) {
            case TITLE -> contains(n.title(), termLower);
            case AUTHOR -> contains(n.author(), termLower);
            case CATEGORY -> contains(n.category(), termLower);
            case ENTIRE -> contains(n.title(), termLower) || contains(n.author(), termLower)
                    || contains(n.category(), termLower) || contains(n.feed(), termLower)
                    || contains(n.content(), termLower); // raw HTML content — cheap superstring match
        };
    }

    private static boolean contains(String haystack, String needleLower) {
        return haystack != null && haystack.toLowerCase().contains(needleLower);
    }
}
