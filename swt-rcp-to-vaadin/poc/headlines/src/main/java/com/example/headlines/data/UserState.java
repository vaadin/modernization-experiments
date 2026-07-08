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

import java.time.LocalDateTime;

/**
 * Small per-user bookkeeping row, keyed by Keycloak subject. Holds {@code lastSeen} — when the user last
 * opened the app, so we can tell them how many articles are new since their last visit (RSSOwl pops a
 * notification when a refresh brings in new news) — and {@code readDelayMs}, the user's auto-read delay
 * for keyboard navigation (RSSOwl's {@code MARK_READ_STATE}/{@code MARK_READ_IN_MILLIS}).
 */
@Entity
@Table(name = "user_state", uniqueConstraints = @UniqueConstraint(columnNames = "owner"))
public class UserState {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String owner; // Keycloak subject

    private LocalDateTime lastSeen; // null until the first visit is recorded

    // Auto-read delay for keyboard navigation, in ms: -1 = off (never auto-mark), 0 = instant,
    // else the dwell before the focused article is marked read. Null until the user changes it (→ default).
    private Integer readDelayMs;

    // The headline grid's sort order, as a compact CSV of "columnKey:asc|desc" in priority order
    // (e.g. "feed:asc,date:desc"). Null/empty until the user sorts (→ default date desc).
    @Column(length = 512)
    private String newsSort;

    protected UserState() { }

    public UserState(String owner) {
        this.owner = owner;
    }

    public Long getId() { return id; }
    public String getOwner() { return owner; }
    public LocalDateTime getLastSeen() { return lastSeen; }
    public void setLastSeen(LocalDateTime lastSeen) { this.lastSeen = lastSeen; }
    public Integer getReadDelayMs() { return readDelayMs; }
    public void setReadDelayMs(Integer readDelayMs) { this.readDelayMs = readDelayMs; }
    public String getNewsSort() { return newsSort; }
    public void setNewsSort(String newsSort) { this.newsSort = newsSort; }
}
