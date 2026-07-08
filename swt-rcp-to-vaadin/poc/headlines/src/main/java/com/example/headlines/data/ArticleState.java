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

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * One user's per-article state: read/unread, sticky, and assigned labels. Rows are created lazily, only
 * when a user's state diverges from the default (unread, not sticky, no labels). {@code owner} is the
 * Keycloak subject. This is the multi-user split of what was mutable state on {@code NewsItem}.
 */
@Entity
@Table(name = "article_state",
        uniqueConstraints = @UniqueConstraint(columnNames = {"owner", "article_id"}),
        indexes = @Index(columnList = "owner"))
public class ArticleState {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String owner; // Keycloak subject (sub)

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    private Article article;

    private boolean read;

    private boolean sticky;

    /** Ids of the user's {@link Label}s assigned to this article (multi-label, RSSOwl-style). */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "article_state_label", joinColumns = @JoinColumn(name = "state_id"))
    @Column(name = "label_id")
    private Set<Long> labelIds = new LinkedHashSet<>();

    protected ArticleState() { }

    public ArticleState(String owner, Article article) {
        this.owner = owner;
        this.article = article;
    }

    public Long getId() { return id; }
    public String getOwner() { return owner; }
    public Article getArticle() { return article; }
    public boolean isRead() { return read; }
    public void setRead(boolean read) { this.read = read; }
    public boolean isSticky() { return sticky; }
    public void setSticky(boolean sticky) { this.sticky = sticky; }
    public Set<Long> getLabelIds() { return labelIds; }
    public void setLabelIds(Set<Long> labelIds) { this.labelIds = labelIds; }
}
