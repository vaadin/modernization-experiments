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
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.LocalDateTime;

/**
 * A single article from a {@link Feed}. Global and shared: fetched once per feed, deduplicated by
 * (feed, link). Per-user read/sticky/label state lives separately in {@link ArticleState}.
 */
@Entity
@Table(name = "article",
        uniqueConstraints = @UniqueConstraint(columnNames = {"feed_id", "link"}),
        indexes = @Index(columnList = "feed_id"))
public class Article {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    private Feed feed;

    @Column(nullable = false, length = 2048)
    private String link;

    @Column(length = 1024)
    private String title;

    private String author;

    private LocalDateTime publishedDate; // nullable -> sorts last, like RSSOwl

    private boolean attachments;

    protected Article() { }

    public Article(Feed feed, String link, String title, String author,
            LocalDateTime publishedDate, boolean attachments) {
        this.feed = feed;
        this.link = link;
        this.title = title;
        this.author = author;
        this.publishedDate = publishedDate;
        this.attachments = attachments;
    }

    public Long getId() { return id; }
    public Feed getFeed() { return feed; }
    public String getLink() { return link; }
    public String getTitle() { return title; }
    public String getAuthor() { return author; }
    public LocalDateTime getPublishedDate() { return publishedDate; }
    public boolean isAttachments() { return attachments; }
}
