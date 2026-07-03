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
import java.util.ArrayList;
import java.util.List;

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

    /** A label assigned to this item, resolved from the user's {@code Label}s for display. */
    public record LabelRef(long id, String name, String color) {}

    private final long id;
    private final String title;
    private final String author;
    private final String category; // feed's FOLDER path (e.g. "News"); drives folder aggregation + Group-by
    private final String feed;     // source feed title (e.g. "BBC News"); drives Group-by-Feed
    private String categories;     // RSSOwl "Category": item's own tags (<category>); set after construction
    private final LocalDateTime date; // may be null -> sorts last, like RSSOwl
    private State state;
    private boolean sticky;
    private String labelColor; // legacy single colour (fixture/back-compat); see labels for the real set
    private final List<LabelRef> labels = new ArrayList<>(); // user labels assigned (multi-label)
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
    /** The feed's folder path (e.g. "Business" / "Computers/Windows"); "" for a loose feed. Drives the
     *  tree's folder aggregation and Group-by. This is NOT RSSOwl's "Category" column — see
     *  {@link #categories()} for that and {@link #location()} for the folder/feed shown to the user. */
    public String category() { return category; }
    public String feed() { return feed; }
    /** RSSOwl's "Category" column: the item's own tags (RSS/Atom {@code <category>}), comma-joined; "" when
     *  none (many feeds carry none). Distinct from the folder ({@link #category()}). */
    public String categories() { return categories == null ? "" : categories; }
    /** RSSOwl's "Location" column: where the item lives — its folder path plus feed, e.g.
     *  "Business/Fast Company"; just the feed when the feed sits at the root. */
    public String location() {
        return (category == null || category.isBlank()) ? feed : category + "/" + feed;
    }
    public LocalDateTime date() { return date; }
    public State state() { return state; }
    public boolean sticky() { return sticky; }
    /** The item's assigned labels (may be empty). */
    public List<LabelRef> labels() { return labels; }
    /** Convenience: the first assigned label's colour, else the legacy single colour, else null. Keeps
     *  single-colour render sites and the "Labeled" smart-folder predicate (labelColor()!=null) working. */
    public String labelColor() { return labels.isEmpty() ? labelColor : labels.get(0).color(); }
    public String link() { return link; }
    public boolean attachments() { return attachments; }
    public String content() { return content; }
    public void setContent(String content) { this.content = content; }
    public void setCategories(String categories) { this.categories = categories; }

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

    /** Assign or clear (null) this item's legacy single label colour (fixtures/back-compat). */
    public void setLabelColor(String labelColor) {
        this.labelColor = labelColor;
    }

    /** Replace this item's assigned labels (multi-label). */
    public void setLabels(List<LabelRef> newLabels) {
        labels.clear();
        if (newLabels != null) labels.addAll(newLabels);
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