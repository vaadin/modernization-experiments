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

/**
 * One user's subscription to a {@link Feed}: which folder it sits in and its drag-and-drop position,
 * persisted so the feeds tree survives logout/restart. {@code owner} is the Keycloak subject.
 * A {@code folder} that is null or blank means a top-level ungrouped channel (like RSSOwl).
 */
@Entity
@Table(name = "subscription",
        uniqueConstraints = @UniqueConstraint(columnNames = {"owner", "feed_id"}),
        indexes = @Index(columnList = "owner"))
public class Subscription {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String owner; // Keycloak subject (sub)

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    private Feed feed;

    private String folder; // category/folder name; null/blank = top-level channel

    private int position; // order within the folder (or among top-level channels)

    private String titleOverride; // optional per-user rename; falls back to feed title

    // Per-feed HTTP authentication (like RSSOwl's per-bookmark credentials), stored per user.
    // The password is encrypted at rest via CredentialCipher (AES-256-GCM).
    private String authUsername;

    @jakarta.persistence.Convert(converter = CredentialCipher.class)
    private String authPassword;

    protected Subscription() { }

    public Subscription(String owner, Feed feed, String folder, int position) {
        this.owner = owner;
        this.feed = feed;
        this.folder = folder;
        this.position = position;
    }

    public Long getId() { return id; }
    public String getOwner() { return owner; }
    public Feed getFeed() { return feed; }
    public String getFolder() { return folder; }
    public void setFolder(String folder) { this.folder = folder; }
    public int getPosition() { return position; }
    public void setPosition(int position) { this.position = position; }
    public String getTitleOverride() { return titleOverride; }
    public void setTitleOverride(String titleOverride) { this.titleOverride = titleOverride; }
    public String getAuthUsername() { return authUsername; }
    public void setAuthUsername(String authUsername) { this.authUsername = authUsername; }
    public String getAuthPassword() { return authPassword; }
    public void setAuthPassword(String authPassword) { this.authPassword = authPassword; }

    /** True when this subscription carries HTTP credentials to use when fetching the feed. */
    public boolean hasCredentials() {
        return authUsername != null && !authUsername.isBlank();
    }

    /** Display title: the per-user override if set, else the feed's own title. */
    public String displayTitle() {
        return (titleOverride != null && !titleOverride.isBlank()) ? titleOverride : feed.getTitle();
    }
}
