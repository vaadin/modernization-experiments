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
import jakarta.persistence.Table;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * A user's news bin — RSSOwl's News Bin: a container the user explicitly drops news into (unlike a feed,
 * a bin never fetches). Holds references to {@link Article}s by id; per-user, keyed by Keycloak subject.
 */
@Entity
@Table(name = "news_bin", indexes = @Index(columnList = "owner"))
public class NewsBin {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String owner; // Keycloak subject

    @Column(nullable = false)
    private String name;

    private int position;

    /** Ids of the articles the user has placed in this bin. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "news_bin_article", joinColumns = @JoinColumn(name = "bin_id"))
    @Column(name = "article_id")
    private Set<Long> articleIds = new LinkedHashSet<>();

    protected NewsBin() { }

    public NewsBin(String owner, String name, int position) {
        this.owner = owner;
        this.name = name;
        this.position = position;
    }

    public Long getId() { return id; }
    public String getOwner() { return owner; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public int getPosition() { return position; }
    public void setPosition(int position) { this.position = position; }
    public Set<Long> getArticleIds() { return articleIds; }
    public void setArticleIds(Set<Long> articleIds) { this.articleIds = articleIds; }
}
