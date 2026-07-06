<!--
 Copyright (c) 2026 Vaadin Ltd.
 This program and the accompanying materials are made available under the
 terms of the Eclipse Public License v1.0 — SPDX-License-Identifier: EPL-1.0
-->

# Prompts, skills & tooling used

> Part of the experiment's deliverable is *"the AI prompts / skills / instructions used,
> included in the repo"* — because **"can AI do this?"** is one of the questions being tested.
> This file is that log: the human prompts that drove the work, and the AI tooling behind it.

**Honesty note.** Prompts from **2026-06-29 onward** are reproduced **verbatim** (typos and all).
Prompts from the earlier sessions (2026-06-23 → 26) are **reconstructed from session notes** — the
intent and most exact phrasings are preserved, but treat those as close paraphrases, not transcripts.
The detailed account of *what each prompt produced* — including the dead ends — lives in the
day-by-day [`JOURNAL.md`](JOURNAL.md); the story and honest findings are in [`REPORT-INHOUSE.md`](REPORT-INHOUSE.md).
This file is the index of inputs.

---

## The standing setup (skills, tools, instructions)

The same toolchain was used throughout:

- **Model / agent:** Claude (Claude Code CLI), with a **persistent file-based memory** across sessions
  so each day could resume without re-explaining the project.
- **Vaadin MCP server** — the single most important tool. Queried for **current** Vaadin 25.1 APIs
  instead of trusting the model's training data (which predates 25.1): `search_vaadin_docs`,
  `get_component_java_api`, `get_full_document`, `get_latest_vaadin_version`. This repeatedly caught
  stale-API mistakes (Signals, `VaadinSecurityConfigurer`, column reorder/resize). See the report's
  *"Working method"* section for why this is non-optional.
- **Playwright MCP** — drove the running app in a real browser for verification
  (`browser_navigate/snapshot/click/type/evaluate/drag/press_key/tabs`). Also where the harness's
  **limits** showed up: it can't synthesise Vaadin column-header drags or reliably open overlay menus.
- **Plan mode** — used before building the dense headline-table slice, so the no-clean-equivalent
  parts surfaced during design rather than mid-build.
- **Structured decisions** — an "ask the user" prompt was used at genuine forks (migration scope,
  which gaps to close next) rather than guessing.

**Standing instruction from the human, paraphrased:** *keep it an honest experiment — document the
wrong turns and the things that don't map cleanly, don't polish it into a fake-perfect demo.* That
instruction shaped every report entry.

---

## Chronological prompt log

### Day 1 — 2026-06-23 *(reconstructed from notes)*
- Frame the experiment: pick a **real** SWT/Eclipse RCP app and migrate a representative master-detail
  slice to Vaadin; write the one-sentence question from the developer's side.
- Choose the target and get a runnable "before"; build the Vaadin PoC of the slice so it actually runs.
- Deepen it to a faithful three-pane match (real RSS feeds, grouping, smart folders), and **fix the
  fidelity gaps I point out** (invented feed taxonomy, fewer channels than the original).

### Day 2 — 2026-06-24 *(reconstructed from notes)*
- Launch the original RSSOwlnix app and reconcile it against the PoC; debug the "missing RSSOwlnix News"
  in the Vaadin tree (placement + a stale instance, not actually missing).

### Day 3 — 2026-06-25 *(reconstructed from notes; quoted phrases preserved)*
- *"create a branch - let us migrate to multi-user."*
- *"Let us re-use an existing KeyCloak."*  → OIDC via Keycloak rather than a local user store.
- *"the original app does this, so please add the same set of features"* → per-feed authentication.
- *"No way is it acceptable to share feed credentials between users"* → the per-user isolation security fix.
- *"merge to master, then the other lower-pri tasks."*
- *"No gap [vs the original] any more?"* → the honest feature-gap answer.
- *"oh yes, please can you add both"* → headline search + per-user labels.

### Day 4 — 2026-06-29 *(verbatim)*
- *"Please read the prohect and let's discuss next steps."*
- *(at the "what next?" fork)* — *"What are the known missing bits between the original application and
  the Vaadin one?"* → the two-tier feature-gap list.
- *"Please tackle column persistence"* → per-user column order/width/visibility (`ColumnPref`).

### Day 5 — 2026-06-30 *(verbatim)*
- *"commit, then close the other two gaps."* → committed column persistence; implemented multi-select
  and the auto-mark-read timer.
- *"Where is the RSSOwl application again?"*
- *"Can you start the original application ?"*
- *"Are you still writing decisions and prompts into the report?"*
- *"yes, please do add it."* → this file.

### Days 6–17 — the feature-parity build *(2026-06-30 → 2026-07-04)*

> These sessions worked down the "what's still missing vs. the original?" gap list one subsystem at a
> time. The driving prompt was the same recurring pattern each time — *"close the next gap"* /
> *"what's missing?"* / *"the original does X, add it"* — so rather than repeat it, here is what each
> session closed. The full account (and the bugs each surfaced) is in [`JOURNAL.md`](JOURNAL.md),
> "Day 6" … "Day 17". (Journal "Day N" is a work-session index, not a strict calendar day.)

- **Seed a user 1:1 from RSSOwl's own OPML** (`default_feeds.xml`) instead of a hand-built taxonomy →
  nested folders, exact original order.
- **"Make the reader actually read"** → inline feed-HTML rendering, sanitized through jsoup.
- **Direction-aware null date sorting** → the last in-slice stretch item.
- **"Add the search RSSOwl bundles Lucene for"** → real full-text search (H2 `FullTextLucene`); a
  four-bug debugging trail recorded verbatim in the journal.
- **OPML import/export UI**, **"new since last visit" notifications**, **periodic background refresh**,
  and **live in-session notifications** → the notifications story, completed.
- **"The biggest missing feature — the rules engine"** → news filters/actions (conditions → actions).
- **Label management** (custom + multi-label), **saved searches**, and **news bins** → the last
  out-of-slice subsystems. *"every RSSOwl subsystem that makes sense on the web is now built."*

### Day 18 — the nitpicking reviewer pass *(verbatim)*

The pass that produced the defect table in the report. Prompt reproduced verbatim (abbreviated):

> *"assume the role of a thorough reviewer… use screenshots and playwright and computer use to operate
> both the old and the new application. Be nitpicking… why it seems impossible that the old application
> and the Alice account can never have the same feeds… the number of news items seems to never be the
> same… when reading one, the number of unread items does not go down… the font is always bold [old] but
> often not in Vaadin… Why did I not see 'Login:' dialogs… Why is the 'add feed' dialog so narrow…? Why
> does a single click not select…? Can we mimic the original with multi-select on Command/Shift instead
> of the checkbox? … maintain a list with defects … and how they can be mitigated."*

Earlier fidelity checks in the same spirit, recorded verbatim in the journal:
- *"there are fewer channels in the Vaadin version. Why?"* → the missing-channels fidelity fix.
- *"you are removing HierarchyFormat.FLATTENED and I wonder why?"* → caught a feature dropped for the
  wrong reason (it wouldn't compile) rather than fixing the import.

### Days 19–21 — feed-parsing divergence, keyboard nav, the three "filters" *(verbatim)*

- **Day 19** — on why the original fumbles a modern CDN feed the Vaadin app handles: *"how does the
  original mess this up?"* → the header-/URL-driven-identification vs. content-driven-parsing finding.
- **Day 20** — settling auto-read behaviour. The human killed a wrong first cut: *"showing the text
  after 5 s and marking it read by then makes no sense, whereas showing it immediately and assuming it
  was read after that time is sound."* And the scoping call to drop per-feed granularity: per-feed
  reading config is *"labour of love, not anything people would use on a regular basis — probably
  nobody needs it."*
- **Day 21** — disambiguating three features all called "filter"; the fix aligned the toolbar box to
  RSSOwl's live, local **Filter Bar** (default scope = title), and added a Playwright **E2E test tier**
  for UI-wiring bugs that headless JUnit structurally can't catch.

---

*Skills/CLI features available in the environment but not central to this work (e.g. `/code-review`,
`/verify`, `/security-review`) are omitted; the work above was done through direct prompting plus the
MCP and Playwright tools listed above.*
