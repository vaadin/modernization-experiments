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
 * One user's per-article state: read/unread, sticky, and label colour. Rows are created lazily, only
 * when a user's state diverges from the default (unread, not sticky, no label). {@code owner} is the
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

    private String labelColor; // CSS hex, or null for none

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
    public String getLabelColor() { return labelColor; }
    public void setLabelColor(String labelColor) { this.labelColor = labelColor; }
}
