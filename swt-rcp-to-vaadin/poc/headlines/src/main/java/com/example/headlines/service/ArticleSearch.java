/*
 * Copyright (c) 2026 Vaadin Ltd.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at https://www.eclipse.org/legal/epl-v10.html
 *
 * SPDX-License-Identifier: EPL-1.0
 */

package com.example.headlines.service;

import java.util.List;

/**
 * Full-text search over the article corpus. An interface so the per-user service can be unit-tested
 * with a stub (the real implementation is Lucene-backed and needs a live database connection).
 */
public interface ArticleSearch {

    /** Article IDs matching the full-text {@code query}, best-match first (Lucene relevance order),
     *  capped at {@code limit}. Returns empty for a blank or unparseable query. NOT owner-scoped —
     *  callers must filter results to what the current user may see. */
    List<Long> searchIds(String query, int limit);
}
