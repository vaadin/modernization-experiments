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
import jakarta.persistence.Index;
import jakarta.persistence.Table;

/**
 * A user's saved search — RSSOwl's persisted search shown as a smart folder. Here it stores a Lucene
 * full-text {@code query} (the same syntax the toolbar search uses); selecting it re-runs the search.
 * Per-user, keyed by Keycloak subject.
 */
@Entity
@Table(name = "saved_search", indexes = @Index(columnList = "owner"))
public class SavedSearch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String owner; // Keycloak subject

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, length = 2048)
    private String query;

    private int position;

    protected SavedSearch() { }

    public SavedSearch(String owner, String name, String query, int position) {
        this.owner = owner;
        this.name = name;
        this.query = query;
        this.position = position;
    }

    public Long getId() { return id; }
    public String getOwner() { return owner; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getQuery() { return query; }
    public void setQuery(String query) { this.query = query; }
    public int getPosition() { return position; }
    public void setPosition(int position) { this.position = position; }
}
