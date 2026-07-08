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
import jakarta.persistence.UniqueConstraint;

/**
 * One user's saved layout for a single headlines-grid column, so column order/width/visibility
 * survives logout/restart — RSSOwl persists the same per-column state on its {@code NewsColumn}
 * model. Columns with no row here fall back to the grid's declared defaults.
 */
@Entity
@Table(name = "column_pref",
        uniqueConstraints = @UniqueConstraint(columnNames = {"owner", "col_key"}),
        indexes = @Index(columnList = "owner"))
public class ColumnPref {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String owner; // Keycloak subject

    @Column(name = "col_key", nullable = false)
    private String colKey; // the Grid column key (status/title/author/feed/date/read/sticky)

    private int position; // visual order, left to right

    private String width; // e.g. "160px"; null = let the column flex/auto-size

    private boolean visible = true;

    protected ColumnPref() { }

    public ColumnPref(String owner, String colKey, int position, String width, boolean visible) {
        this.owner = owner;
        this.colKey = colKey;
        this.position = position;
        this.width = width;
        this.visible = visible;
    }

    public Long getId() { return id; }
    public String getOwner() { return owner; }
    public String getColKey() { return colKey; }
    public int getPosition() { return position; }
    public void setPosition(int position) { this.position = position; }
    public String getWidth() { return width; }
    public void setWidth(String width) { this.width = width; }
    public boolean isVisible() { return visible; }
    public void setVisible(boolean visible) { this.visible = visible; }
}
