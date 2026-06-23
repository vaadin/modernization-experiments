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

    record Feed(String name, String category, int count) implements FeedNode {
        @Override public String label() { return name + "  (" + count + ")"; }
    }

    /** A saved-search "smart folder" (RSSOwl: Unread News, Today's News, …). {@code key} selects the
     *  predicate; count shown only when &gt; 0, like RSSOwl. */
    record Saved(String name, String key, int count) implements FeedNode {
        @Override public String label() { return count > 0 ? name + "  (" + count + ")" : name; }
    }
}
