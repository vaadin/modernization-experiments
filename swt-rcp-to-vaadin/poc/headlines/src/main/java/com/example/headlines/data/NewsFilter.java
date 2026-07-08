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
import jakarta.persistence.Embeddable;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;

import java.util.ArrayList;
import java.util.List;

/**
 * A per-user news filter — RSSOwl's News Filters / Search Filters: a set of match {@link Condition}s and
 * a set of {@link #actions} applied to matching news. Owned by a Keycloak subject. A real (if scoped)
 * rules engine: multiple conditions combined with {@link #matchMode} ALL/ANY, multiple additive actions.
 *
 * <p>Actions are stored as strings: {@code "MARK_READ"}, {@code "MARK_STICKY"}, or {@code "LABEL:#rrggbb"}.
 */
@Entity
@Table(name = "news_filter", indexes = @Index(columnList = "owner"))
public class NewsFilter {

    /** Where a condition looks. Matched case-insensitively as "contains". */
    public enum Field { TITLE, AUTHOR, FEED, CONTENT }

    /** How multiple conditions combine. */
    public enum MatchMode { ALL, ANY }

    /** One match condition: a {@link Field} that must contain {@link #value}. */
    @Embeddable
    public static class Condition {
        @jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
        @Column(name = "match_field")
        private Field field = Field.TITLE;
        // NB: column is "match_value", not "value" — VALUE is a reserved word in H2 and an unquoted
        // column of that name makes Hibernate's schema-update silently fail to create this table.
        @Column(name = "match_value")
        private String value = "";

        public Condition() { }
        public Condition(Field field, String value) { this.field = field; this.value = value; }

        public Field getField() { return field; }
        public void setField(Field field) { this.field = field; }
        public String getValue() { return value; }
        public void setValue(String value) { this.value = value; }
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String owner; // Keycloak subject

    @Column(nullable = false)
    private String name;

    private boolean enabled = true;

    @jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
    private MatchMode matchMode = MatchMode.ALL;

    private int position;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "news_filter_condition", joinColumns = @JoinColumn(name = "filter_id"))
    private List<Condition> conditions = new ArrayList<>();

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "news_filter_action", joinColumns = @JoinColumn(name = "filter_id"))
    @Column(name = "action")
    private List<String> actions = new ArrayList<>();

    protected NewsFilter() { }

    public NewsFilter(String owner, String name) {
        this.owner = owner;
        this.name = name;
    }

    public Long getId() { return id; }
    public String getOwner() { return owner; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public MatchMode getMatchMode() { return matchMode; }
    public void setMatchMode(MatchMode matchMode) { this.matchMode = matchMode; }
    public int getPosition() { return position; }
    public void setPosition(int position) { this.position = position; }
    public List<Condition> getConditions() { return conditions; }
    public void setConditions(List<Condition> conditions) { this.conditions = conditions; }
    public List<String> getActions() { return actions; }
    public void setActions(List<String> actions) { this.actions = actions; }
}
