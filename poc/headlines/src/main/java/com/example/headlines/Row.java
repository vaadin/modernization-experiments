/*
 * Copyright (c) 2026 Vaadin Ltd.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at https://www.eclipse.org/legal/epl-v10.html
 *
 * SPDX-License-Identifier: EPL-1.0
 */

package com.example.headlines;

/**
 * A row in the TreeGrid: either a group header ({@link GroupRow}) or a headline ({@link ItemRow}).
 * Mirrors RSSOwl's {@code EntityGroup} (parent) / {@code INews} (child) coexistence in one viewer.
 */
public sealed interface Row permits Row.GroupRow, Row.ItemRow {

    /** A group header. {@code orderIndex} keeps groups in their intended order under any sort. */
    record GroupRow(String key, String label, String colorHint, int orderIndex, int count)
            implements Row {}

    /** A headline. Identity delegates to {@link NewsItem#equals} (by id), so selection survives
     *  state toggles and refreshes. */
    record ItemRow(NewsItem news) implements Row {}
}