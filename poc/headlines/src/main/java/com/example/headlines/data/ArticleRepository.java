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

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ArticleRepository extends JpaRepository<Article, Long> {
    // Dedup lookups, owner-aware (public = owner null; private = a user's subject).
    Optional<Article> findByFeedAndLinkAndOwnerIsNull(Feed feed, String link);
    Optional<Article> findByFeedAndLinkAndOwner(Feed feed, String link, String owner);

    // The articles a user may see for a feed: public ones plus their own private copies.
    List<Article> findByFeedAndOwnerIsNull(Feed feed);
    List<Article> findByFeedAndOwner(Feed feed, String owner);
}
