package com.example.headlines;

/**
 * A node in the left feeds-navigation tree (RSSOwl's {@code BookMarkExplorer}): a {@link Category}
 * folder or a {@link Feed} under it. The label carries an unread-style count in parentheses, like
 * RSSOwl ("Business (151)").
 */
public sealed interface FeedNode permits FeedNode.Category, FeedNode.Feed {

    String label();

    record Category(String name, int count) implements FeedNode {
        @Override public String label() { return name + "  (" + count + ")"; }
    }

    record Feed(String name, String category, int count) implements FeedNode {
        @Override public String label() { return name + "  (" + count + ")"; }
    }
}
