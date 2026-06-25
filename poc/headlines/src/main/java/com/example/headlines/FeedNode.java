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
 * A node in the left feeds-navigation tree (RSSOwl's {@code BookMarkExplorer}): a {@link Category}
 * folder or a {@link Feed} under it. The label carries an unread-style count in parentheses, like
 * RSSOwl ("Business (151)").
 */
public sealed interface FeedNode permits FeedNode.Category, FeedNode.Feed, FeedNode.Saved {

    String label();

    record Category(String name, int count) implements FeedNode {
        @Override public String label() { return name + "  (" + count + ")"; }
    }

    /** {@code subscriptionId} ties the node to its persisted {@code Subscription} so drag-and-drop
     *  reordering can be saved per user. {@code url} is the feed URL (for re-fetching after a
     *  credentials change); {@code authUsername} is non-null when the feed has stored HTTP
     *  credentials (a lock is shown in the label, like an authenticated bookmark). */
    record Feed(String name, String category, int count, long subscriptionId, String url,
            String authUsername) implements FeedNode {
        boolean hasAuth() { return authUsername != null && !authUsername.isBlank(); }
        @Override public String label() { return (hasAuth() ? "🔒 " : "") + name + "  (" + count + ")"; }
    }

    /** A saved-search "smart folder" (RSSOwl: Unread News, Today's News, …). {@code key} selects the
     *  predicate; count shown only when &gt; 0, like RSSOwl. */
    record Saved(String name, String key, int count) implements FeedNode {
        @Override public String label() { return count > 0 ? name + "  (" + count + ")" : name; }
    }
}