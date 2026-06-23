package com.example.headlines;

/**
 * A row in the TreeGrid: either a group header ({@link GroupRow}) or a headline ({@link ItemRow}).
 * Mirrors RSSOwl's {@code EntityGroup} (parent) / {@code INews} (child) coexistence in one viewer.
 */
public sealed interface Row permits Row.GroupRow, Row.ItemRow {

    /** A group header. {@code orderIndex} keeps groups in their intended order under any sort. */
    record GroupRow(String key, String label, String colorHint, int orderIndex, int count)
            implements Row {}

    /** A headline. Identity delegates to {@link NewsItem#equals} (by id), so selection survives
     *  state toggles and refreshes. */
    record ItemRow(NewsItem news) implements Row {}
}
