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

import java.time.LocalDateTime;

/**
 * A single headline row. Port of the data behind RSSOwl's {@code NewsTableLabelProvider} /
 * {@code NewsColumn} model, reduced to the fields the headline-table slice actually renders.
 *
 * <p>Mutable on purpose: the in-cell toggles (read/unread, sticky) flip state in place and the
 * grid is told to {@code refreshItem(...)}. The Vaadin Signals docs prefer immutable records;
 * the POC uses a mutable bean because the interactive icon toggles are simpler that way. The
 * report notes this trade-off honestly.
 */
public class NewsItem {

    /** Mirrors {@code org.rssowl.core.persist.INews.State}. */
    public enum State { NEW, UPDATED, UNREAD, READ }

    private final long id;
    private final String title;
    private final String author;
    private final String category; // RSSOwl: feed's folder (e.g. "News"); drives Group-by-Category
    private final String feed;     // source feed title (e.g. "BBC News"); drives Group-by-Feed
    private final LocalDateTime date; // may be null -> sorts last, like RSSOwl
    private State state;
    private boolean sticky;
    private String labelColor; // CSS hex like "#1565c0", or null for none; user-assignable
    private final String link;
    private final boolean attachments; // RSS enclosure present -> "News with Attachments"
    private String content; // article HTML/summary from the feed; set after construction, may be null

    /** Fixture constructor (feed defaults to the category, no attachments). */
    public NewsItem(long id, String title, String author, String category, LocalDateTime date,
            State state, boolean sticky, String labelColor, String link) {
        this(id, title, author, category, category, date, state, sticky, labelColor, link, false);
    }

    /** Full constructor used by the live feed mapping. */
    public NewsItem(long id, String title, String author, String category, String feed,
            LocalDateTime date, State state, boolean sticky, String labelColor, String link,
            boolean attachments) {
        this.id = id;
        this.title = title;
        this.author = author;
        this.category = category;
        this.feed = feed;
        this.date = date;
        this.state = state;
        this.sticky = sticky;
        this.labelColor = labelColor;
        this.link = link;
        this.attachments = attachments;
    }

    public long id() { return id; }
    public String title() { return title; }
    public String author() { return author; }
    public String category() { return category; }
    public String feed() { return feed; }
    public LocalDateTime date() { return date; }
    public State state() { return state; }
    public boolean sticky() { return sticky; }
    public String labelColor() { return labelColor; }
    public String link() { return link; }
    public boolean attachments() { return attachments; }
    public String content() { return content; }
    public void setContent(String content) { this.content = content; }

    /** Bold in RSSOwl when NEW, UPDATED or UNREAD (see {@code NewsTableLabelProvider.getFont}). */
    public boolean unread() {
        return state == State.NEW || state == State.UPDATED || state == State.UNREAD;
    }

    public void toggleRead() {
        state = unread() ? State.READ : State.UNREAD;
    }

    /** Set the read state explicitly (used by multi-select bulk actions and the auto-mark-read timer). */
    public void setRead(boolean read) {
        state = read ? State.READ : State.UNREAD;
    }

    public void toggleSticky() {
        sticky = !sticky;
    }

    /** Set the sticky flag explicitly (used by multi-select bulk actions). */
    public void setSticky(boolean sticky) {
        this.sticky = sticky;
    }

    /** Assign or clear (null) this item's user label colour. */
    public void setLabelColor(String labelColor) {
        this.labelColor = labelColor;
    }

    /** Status sort rank, mirroring RSSOwl's NEW &gt; UPDATED &gt; UNREAD &gt; READ priority. */
    public int statusRank() {
        return switch (state) {
            case NEW -> 0;
            case UPDATED -> 1;
            case UNREAD -> 2;
            case READ -> 3;
        };
    }

    // Identity by id so Grid selection / refreshItem survive state changes (cf. the Person
    // example in Vaadin's TreeGrid docs, which also keys equals/hashCode on id).
    @Override public boolean equals(Object o) {
        return o instanceof NewsItem other && other.id == id;
    }
    @Override public int hashCode() { return Long.hashCode(id); }
}