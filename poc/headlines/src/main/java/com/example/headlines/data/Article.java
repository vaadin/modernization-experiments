/*
 * Copyright (c) 2026 Vaadin Ltd.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at https://www.eclipse.org/legal/epl-v10.html
 *
 * SPDX-License-Identifier: EPL-1.0
 */

package com.example.headlines.data;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.LocalDateTime;

/**
 * A single article from a {@link Feed}. Global and shared: fetched once per feed, deduplicated by
 * (feed, link, owner). Per-user read/sticky/label state lives separately in {@link ArticleState}.
 *
 * <p><b>Owner scoping (security):</b> {@code owner == null} is a <em>public</em> article fetched
 * anonymously by the shared refresh — visible to everyone. {@code owner == <subject>} is a
 * <em>private</em> article fetched with that user's own credentials from an authenticated feed —
 * visible only to them. A user's credentials are NEVER used to fetch on anyone else's behalf, and the
 * private content they unlock is never placed in the shared pool.
 */
@Entity
@Table(name = "article",
        uniqueConstraints = @UniqueConstraint(columnNames = {"feed_id", "link", "owner"}),
        indexes = @Index(columnList = "feed_id, owner"))
public class Article {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    private Feed feed;

    @Column(nullable = false, length = 2048)
    private String link;

    /** null = public (anonymous shared fetch); else the Keycloak subject who owns this private copy. */
    private String owner;

    @Column(length = 1024)
    private String title;

    private String author;

    private LocalDateTime publishedDate; // nullable -> sorts last, like RSSOwl

    private boolean attachments;

    /** The article's HTML body/summary as supplied by the feed (RSS description / Atom content),
     *  shown in the reader pane. Sanitized at render time, not here. Nullable; can be large -> CLOB. */
    @Lob
    private String content;

    /** Plain-text projection of {@link #content} (HTML stripped), maintained only to feed the full-text
     *  index — indexing raw HTML breaks Lucene's max-term-length. Not shown in the UI. */
    @Lob
    private String contentText;

    protected Article() { }

    public Article(Feed feed, String owner, String link, String title, String author,
            LocalDateTime publishedDate, boolean attachments) {
        this.feed = feed;
        this.owner = owner;
        this.link = link;
        this.title = title;
        this.author = author;
        this.publishedDate = publishedDate;
        this.attachments = attachments;
    }

    public Long getId() { return id; }
    public Feed getFeed() { return feed; }
    public String getOwner() { return owner; }
    public String getLink() { return link; }
    public String getTitle() { return title; }
    public String getAuthor() { return author; }
    public LocalDateTime getPublishedDate() { return publishedDate; }
    public boolean isAttachments() { return attachments; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public String getContentText() { return contentText; }
    public void setContentText(String contentText) { this.contentText = contentText; }
}
