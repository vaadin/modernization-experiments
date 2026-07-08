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
 * A user-defined label (name + colour) — RSSOwl's manageable labels. Per-user, so each user curates
 * their own set; seeded with RSSOwl's five defaults on first use. A news item may carry several labels
 * (see {@code ArticleState.labelIds}).
 */
@Entity
@Table(name = "label", indexes = @Index(columnList = "owner"))
public class Label {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String owner; // Keycloak subject

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String color; // CSS hex, e.g. "#c62828"

    private int position;

    protected Label() { }

    public Label(String owner, String name, String color, int position) {
        this.owner = owner;
        this.name = name;
        this.color = color;
        this.position = position;
    }

    public Long getId() { return id; }
    public String getOwner() { return owner; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getColor() { return color; }
    public void setColor(String color) { this.color = color; }
    public int getPosition() { return position; }
    public void setPosition(int position) { this.position = position; }
}
