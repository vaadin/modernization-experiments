/*
 * Copyright (c) 2026 Vaadin Ltd.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at https://www.eclipse.org/legal/epl-v10.html
 *
 * SPDX-License-Identifier: EPL-1.0
 */

package com.example.headlines.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * OPML parsing tests for {@link DefaultFeeds} — the Vaadin equivalent of RSSOwl's importer tests.
 * Verifies the bundled {@code default_feeds.xml} (RSSOwl's real first-run tree) parses into its
 * taxonomy: nested folder paths, loose top-level ("Uncategorized") channels, every entry with a
 * usable title + URL.
 */
class DefaultFeedsTest {

    @Test
    void parsesBundledDefaultsIntoTaxonomy() {
        List<DefaultFeeds.Source> sources = DefaultFeeds.read();

        assertFalse(sources.isEmpty(), "default_feeds.xml should yield sources");
        assertTrue(sources.size() > 250, "RSSOwl's default tree has ~292 feeds, got " + sources.size());
        assertTrue(sources.stream().allMatch(s -> notBlank(s.url()) && notBlank(s.title())),
                "every source has a URL and a title");
        assertTrue(sources.stream().anyMatch(s -> !"Uncategorized".equals(s.category())),
                "some feeds live in category folders");
        assertTrue(sources.stream().anyMatch(s -> "Uncategorized".equals(s.category())),
                "some feeds are loose top-level channels (directly under the OPML body)");
    }

    @Test
    void capturesNestedFolderPaths() {
        List<DefaultFeeds.Source> sources = DefaultFeeds.read();
        // RSSOwl nests e.g. Computers > Windows; the path is captured with a '/' separator.
        assertTrue(sources.stream().anyMatch(s -> s.category().contains("/")),
                "nested sub-folders are captured as a path, e.g. Computers/Windows");
        assertTrue(sources.stream().anyMatch(s -> s.category().startsWith("Computers/")),
                "Computers has sub-folders in the original tree");
    }

    @Test
    void urlsAreUnique() {
        List<DefaultFeeds.Source> sources = DefaultFeeds.read();
        long distinct = sources.stream().map(DefaultFeeds.Source::url).distinct().count();
        assertTrue(distinct == sources.size(), "no duplicate feed URLs in the defaults");
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
