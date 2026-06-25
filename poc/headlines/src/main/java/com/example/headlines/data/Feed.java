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
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

/**
 * A feed source, deduplicated globally by URL and shared across all subscribers. The articles it
 * yields ({@link Article}) are fetched once and shared too; only {@link Subscription} (which user
 * follows it, in which folder, at which position) and {@link ArticleState} (per-user read/sticky)
 * are per-user.
 */
@Entity
@Table(name = "feed", uniqueConstraints = @UniqueConstraint(columnNames = "url"))
public class Feed {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 1024)
    private String url;

    @Column(nullable = false)
    private String title;

    /** The folder this feed lands in when a new user is seeded from the defaults (from feeds.opml). */
    private String defaultCategory;

    private Instant lastFetched;

    protected Feed() { }

    public Feed(String url, String title, String defaultCategory) {
        this.url = url;
        this.title = title;
        this.defaultCategory = defaultCategory;
    }

    public Long getId() { return id; }
    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getDefaultCategory() { return defaultCategory; }
    public void setDefaultCategory(String defaultCategory) { this.defaultCategory = defaultCategory; }
    public Instant getLastFetched() { return lastFetched; }
    public void setLastFetched(Instant lastFetched) { this.lastFetched = lastFetched; }
}
