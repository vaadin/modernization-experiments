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
 * One user's preferred position for a category folder in the feeds tree, so folder drag-reordering
 * survives logout/restart (channel order lives on {@link Subscription}; folder order lives here).
 * Folders not represented here fall back to alphabetical order.
 */
@Entity
@Table(name = "folder_pref",
        uniqueConstraints = @UniqueConstraint(columnNames = {"owner", "name"}),
        indexes = @Index(columnList = "owner"))
public class FolderPref {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String owner; // Keycloak subject

    @Column(nullable = false)
    private String name; // folder/category name

    private int position;

    protected FolderPref() { }

    public FolderPref(String owner, String name, int position) {
        this.owner = owner;
        this.name = name;
        this.position = position;
    }

    public Long getId() { return id; }
    public String getOwner() { return owner; }
    public String getName() { return name; }
    public int getPosition() { return position; }
    public void setPosition(int position) { this.position = position; }
}
