<!--
 Copyright (c) 2026 Vaadin Ltd.
 This program and the accompanying materials are made available under the
 terms of the Eclipse Public License v1.0 — SPDX-License-Identifier: EPL-1.0
-->

# Can an SWT/RCP master-detail screen move to Vaadin — and where does it break?

*An honest, timeboxed experiment. We took a real Eclipse RCP application, migrated a
representative slice to Vaadin 25 with AI assistance, shipped runnable code, and wrote down
what actually happened — including the parts that didn't work.*

> **The question, from the developer's side:**
> *Can an SWT master-detail screen — a tree of feeds, a sortable headlines table with a context
> menu, and an article reader — be moved to a Vaadin `Grid` + detail view, and where does it
> break?*

The interesting output of an honest experiment isn't the victory lap; it's the list of things
that don't map cleanly. So the headline word is **"where does it break"** — and the answer, up
front:

> **Verdict.** Yes — the slice moves, and the core of it (sortable table, selection→detail,
> dynamic context menu, custom rendering) comes across cleanly and fast. What "breaks" is
> narrow and specific: a few Vaadin APIs the AI misremembered, one theme-vs-CSS fight, and a
> handful of behaviours with genuinely **no web equivalent** (Eclipse's declarative
> extension-point menu contributions; a true embedded browser). The single biggest finding is
> about *method*, not widgets: **the AI reproduces structure fast but quietly under-delivers
> completeness — a source-literate human reviewer is the safety net that turns a
> plausible-looking skeleton into a faithful app.**

This write-up is the story and the honest findings. The blow-by-blow evidence — every dead end,
fix, and correction, dated — lives in the companion **[working journal](JOURNAL.md)**. The
runnable code is in [`../poc/headlines/`](../poc/headlines/).

*Timebox: two weeks, hard stop. We finished inside it (with off-days along the way). This
report is organized by theme, not by day; the day-by-day account is the journal.*

---

## Why this target: RSSOwl, a stranded RCP app

We needed a *real* SWT/Eclipse RCP application, not a toy — the brief is explicit that "a
button that becomes a Vaadin button proves nothing; a real grid with interaction proves the
thing people doubt." We chose **[RSSOwl](https://github.com/rssowl/RSSOwl)** (EPL-1.0), the
iconic feed reader, and its maintained fork [RSSOwlnix](https://github.com/Xyrio/RSSOwlnix). It
is a textbook master-detail screen and a perfect fit for the narrative this whole wedge is
about: *a dying desktop UI, a team that would rather stay in Java than rewrite into a
JavaScript stack.*

And — fittingly — the very first thing we tried didn't work, which turned into the experiment's
thesis in a single artifact.

**The original binary is dead, and that *is* a finding.** RSSOwl's official 2.2.1 macOS build
won't launch on a 2026 Mac: it's 32-bit `i386`/`ppc`, linked against Carbon, a UI framework
Apple removed. Rosetta can't help (it only translates 64-bit x86_64). The flagship binary of
the app we're migrating **cannot run on a current developer's machine.** That is the "before" —
the cost of staying on a dying desktop toolkit, made concrete.

**But the honest correction matters more than the scare story.** Our first instinct was to call
SWT "a dying toolkit whose escape hatches keep closing." Then we built the fork *from source* —
and in 2m23s it produced a **native arm64** app that runs on Apple Silicon **without Rosetta**,
because modern SWT (Eclipse 4.30) ships a current `cocoa.macosx.aarch64` fragment. "You can't
run SWT on Apple Silicon" is **false**. We changed one line in a Tycho `<environment>` and it
built native.

So the honest case for moving to the web is *not* "the toolkit is dead." It's **distribution**:
the official channel still hands users an x86_64 binary on borrowed Rosetta time, and getting a
modern native build at all requires the source, a Tycho/Maven toolchain, the right JDK, and the
knowledge to flip a build target — none of which a typical user (or many app owners) has.
Overstating the toolkit's death would have been a credibility-losing mistake; we caught it by
actually building the thing. *(Full diagnosis and build log: [journal](JOURNAL.md).)*

---

## What we actually migrated (and how big it really is)

The slice is RSSOwl's core screen: a clean **three-pane master-detail**.

![RSSOwlnix, the original — three-pane master-detail](before/rssowlnix-masterdetail.png)

1. **Feeds tree (master)** — categories and feeds; selection drives everything.
2. **Headlines "table" (detail list)** — *the component this experiment is really about.*
   Despite looking like a table it is a JFace `TreeViewer` over a custom `CTree`, so it can
   show rows flat *or* grouped. It has sortable columns, per-row state (bold = unread),
   owner-draw colours, in-cell interactive icons, and a context menu.
3. **Article reader (detail)** — renders the selected headline.

**On "a slice, not the whole app," honestly.** RSSOwl is ~121k lines — but ~29% of that is
tests, and the rest is what a 20-year-old **full Eclipse RCP** app *contains*: db4o, Lucene,
JDOM feed parsing, an embedded browser, OPML, notifications, retention, sync, ~167 OSGi
bundles. The UX is small; the machinery is not. The slice we migrate is ~7% of the project; the
headline table at its center is ~2k lines. *Nobody rewrites 121k lines* — and that ratio (small
visible widget, dense behaviour) is exactly what makes the effort estimate below useful.

**It's RCP, not just SWT — and that's the real scope.** "Map SWT widgets to Vaadin components"
undersells the job. SWT is the easy bottom layer. The real surface is the RCP application model
on top: the JFace viewer framework, the Workbench selection service, declarative
menus/commands, and RSSOwl's *own* OSGi extension points. That is the version of the question
worth answering — and the part with no direct Vaadin equivalent.

---

## The mapping: SWT/RCP → Vaadin 25

Every Vaadin-side API below was checked against Vaadin's **MCP documentation server** for 25.1,
not recalled from the model's training data — for reasons that became the experiment's central
method finding (see [The method](#the-method-how-to-actually-use-ai-for-this)).

| Layer | RSSOwl (SWT/RCP) | Vaadin 25.1 | Verdict |
|---|---|---|---|
| Widgets | SWT `Tree`, `Table` (the headlines table is itself a `TreeViewer`) | `TreeGrid` (a superset of `Grid`; a flat list is a tree with no children) | Clean |
| Viewer framework | JFace `TableViewer` + content/label providers, sorters, filters | `Grid` columns; label providers → renderers; `ViewerSorter` → `setComparator(...)`; filters → `DataProvider` | Conceptually 1:1, real rewrite |
| Master → detail | Workbench **selection service** between ViewParts | a `ValueSignal` holding the selection; detail binds reactively (**Signals**) | Cleaner than the original |
| Sorting | ~300-line `NewsComparator` | `Column.setComparator(...)` + `setSortable(true)` | Collapses to a few lines |
| Owner-draw render | ~470-line `NewsTableLabelProvider` | CSS `::part(...)` + a `Grid` renderer | Collapses to ~10 lines CSS |
| Context menu | programmatic JFace `MenuManager`, *augmented* by declarative `popupMenus` contributions | `GridContextMenu` + `setDynamicContentHandler(...)` | Programmatic part: clean. **Declarative part: no equivalent** |
| Actions / commands | `actionSets`, `commands`, advisors | `Button` / `MenuBar`, hand-mapped | Manual |
| Modularity | OSGi bundles + extension registry | dropped — a plain web module | Lost by design |

---

## What the AI + tooling did well

- **The structure came up fast and ran.** The whole slice compiled after **two** real API
  fixes and ran on first launch. Scaffolded from `start.vaadin.com`, backed by live RSS from
  RSSOwl's own default feeds.

- **Selection→detail via Signals "just worked."** `grid.addSelectionListener` sets a
  `ValueSignal<NewsItem>`; the reader reacts via `Signal.effect(...)`. No listener plumbing on
  the detail side — visibly cleaner than the RCP selection-service indirection it replaces.

- **The imperative SWT/JFace plumbing collapses dramatically.** This is the single strongest
  pro-Vaadin data point, measured with `cloc` (Java code lines, comments excluded):
  - **Sorting:** `NewsComparator` (306 code lines) → ~10 lines of `setComparator(...)`.
  - **Owner-draw** (bold-unread, sticky highlight, label colour): `NewsTableLabelProvider`
    (467 code lines) → ~10 lines of CSS + a touch of inline style.
  - Header-click sort and the sort-direction triangle came **free** from `Grid`; the SWT
    `setSortColumn/Direction` code is *deleted*, not ported.
  - *Honest caveat:* part of the shrink is reduced scope — the original comparator also handles
    group-aware sorting and cases our slice doesn't. But even allowing for that, the
    order-of-magnitude compression of the UI-plumbing layer is real and repeatable.

- **`GridContextMenu` + `setDynamicContentHandler`** reproduced the per-open menu rebuild
  faithfully ("Mark read" ↔ "Mark unread"), including returning *no* menu on group rows.

- **`TreeGrid` is genuinely a superset of `Grid`.** Grouping (by date/status/author/feed) is
  just data-shaping into a tree; flat mode is a tree with no children — one codepath, no
  Grid↔TreeGrid swap. The design predicted this and the build confirmed it.

![After: the three-pane layout in Vaadin, live data, in a browser tab](after/threepane-layout.png)

---

## What it got wrong or half-right (and needed hand-fixing)

None of these were caught by the AI proposing them; they were caught by the **compiler**, by
**running and inspecting the app**, or by a **human who knew the original**.

- **Stale API guesses (caught by `javac`).** `new ValueSignal<>()` won't infer from a field;
  and the Grid double-click event's `getItem()` returns `T` while the context-menu event's
  returns `Optional<T>` — a real Grid API inconsistency the model papered over.

- **The documented row-background technique is wrong under the Aura theme.** The MCP styling
  docs say set `--vaadin-grid-cell-background`. We did; the property *was* set on the cell — but
  the computed background stayed white, because Aura paints the cell background independently.
  Only `background-color: … !important` on `::part(sticky)` worked. This is the "owner-draw is
  the hardest part" prediction coming true — milder than feared, but it cost a real debugging
  loop and only surfaced by inspecting the live shadow DOM.

- **`nullsLast` sort semantics flip under descending order.** Vaadin gives a column *one*
  comparator and **reverses it** for descending, so RSSOwl's always-undated-last rule inverted
  and undated rows jumped to the top of the default newest-first view. Fixed with a
  direction-aware comparator swapped in a `SortListener`. A subtle behavioural bug a naïve port
  ships silently.

- **`Signal.get()` throws outside a reactive context.** Reading the selection with
  `selected.get()` from a plain timer callback throws `IllegalStateException`; from outside a
  `Signal.effect` you must use `selected.peek()`. The *second* distinct Signals surprise —
  reinforcing that the model's pre-25 Signals knowledge is unreliable.

- **The MCP is current but not exhaustive.** `MasterDetailLayout` had no Java API entry
  (lookup errored), so we used `SplitLayout`; Lumo theme variants silently don't apply under
  Aura. The MCP made the *design* current; it didn't cover every newest component.

- **The AI silently under-delivered *fidelity*, repeatedly.** Left to itself, the model
  produced a plausible-looking approximation that a source-literate reviewer had to correct: an
  **invented feed taxonomy** (a made-up "World" category, missing RSSOwl's real "Weblogs"),
  **fewer channels than the original** (10 of 15 folders, no ungrouped channels, no
  saved-search smart folders), and an **arbitrary item cap** until we found RSSOwl's actual
  200-per-feed retention default in its source. Every one of these looked right and wasn't.

> This is the recurring shape of the whole experiment, and the most important thing to tell an
> architect: **the tooling gets you a faithful skeleton fast; a person who knows the source app
> turns it into a faithful *app*.** Nearly every gap between "looks right" and "is right" was
> closed by human review, not by the model noticing.

---

## What is genuinely hard, awkward, or not yet possible

Stated plainly, no spin.

- **Eclipse's declarative extension-point menu contributions (`MB_ADDITIONS`) have no web
  equivalent.** RCP let *other plugins* inject menu items into RSSOwl's context menu
  declaratively, via the OSGi extension registry. That's an architectural capability, not a
  widget — and there is simply nothing to map it to. A real loss, not a workaround.

- **A true embedded browser is blocked by the web itself.** RSSOwl's reader is an embedded SWT
  `Browser` showing the live page. An `<iframe>` of a news site renders blank —
  `X-Frame-Options: DENY` / CSP `frame-ancestors` are near-universal now. We render the feed's
  own article HTML inline instead (sanitized through jsoup — Vaadin's `Html` component does
  **not** sanitize, a documented footgun), with "Open original ↗" for the full page. Faithful
  in spirit, not identical.

- **Google-Reader sync is impossible because the target is dead** (shut down 2013). Not a
  Vaadin limitation — the feature's counterpart no longer exists.

- **Owner-draw selection paint is the hardest rendering case.** Arbitrary per-row hex colour
  *composed with* selected state can't be expressed cleanly through enumerable CSS parts. It's a
  design compromise, not a straight port.

- **Behaviours that are "reproducible, not identical."** Auto-mark-read (a UI-thread timer
  becomes `@Push` + a scheduled task + `ui.access` + cancel-on-reselect — race-prone, not free);
  multi-select range/anchor semantics; column-state persistence (no built-in Grid state store —
  we re-implemented it per-user). All doable; none free.

- **A parity gap we recorded rather than papered over.** RSSOwl has *one* smart-folder model:
  every saved search is a condition builder over structured fields (`HAS_ATTACHMENTS`, `STATE`,
  `IS_FLAGGED`, …). Our app ended up with *two* models — five hard-coded predicates plus
  user saved-searches that are full-text-only — so a user can't recreate the built-in five
  through the UI. A deliberate scope decision, logged as a known divergence, not hidden.

**A note on faithfulness vs. granularity.** A recurring right-call was to **port the original's
behaviour *model* faithfully while dropping rarely-used *granularity*.** RSSOwl exposes per-feed
reading-behaviour preferences; we implemented its mark-read model exactly but as a single
per-user setting, not a per-feed property page — "labour of love… probably nobody needs it." A
faithful migration is allowed to leave 2009's seldom-touched knobs on the floor.

---

## The method: how to actually use AI for this

"Can AI do this migration?" was an explicit question. The answer is **yes, but not on
autopilot** — and the *how* is the most transferable finding here.

1. **Treat the model's framework knowledge as a stale cache; query the MCP server first.** The
   model confidently proposed a pre-25, listener-based design for master→detail. Vaadin 25.1
   (released *after* the model's training cutoff) does this with **Signals**. Only a human
   catching it, then confirming against Vaadin's MCP server, surfaced the modern approach. This
   repeated for security config (`VaadinSecurityConfigurer` replaced the old base class), file
   upload/download APIs, and TreeGrid's V25 breaking changes. **The MCP query is a required
   step, not a fallback.**

2. **Plan before code on a dense slice.** Switching to plan mode *before* writing the headline
   table forced the no-clean-equivalent parts (owner-draw selection paint, auto-mark-read
   timing, the lost `MB_ADDITIONS` contributions) to surface during design instead of mid-build,
   after the structure was already committed.

3. **Verify by running the app, not by reading the diff.** Every behavioural finding above came
   from driving the running app (with the Playwright MCP) and reading logs/shadow DOM — the two
   compile errors, the Aura CSS fight, the flipped null-sort, the Signals context rule. One
   exploration agent confidently asserted a bold-unread style "wouldn't reach the slotted
   title"; inspecting the live DOM proved it did. Running it beats reasoning about it.

4. **Keep a source-literate human in the loop for completeness.** See the finding above: the AI
   reproduces structure; a reviewer who knows the original catches the invented taxonomy, the
   missing channels, the phantom attachments, the smart-folder split. Without that reviewer, the
   result *looks* right and isn't.

*The exact prompts and tooling are in [`PROMPTS.md`](PROMPTS.md).*

---

## Honest effort estimate for a full-size app

- **The headline-table sub-slice** — sorting, owner-draw, interactive cells, dynamic context
  menu, selection wiring, multi-select, auto-mark-read, column persistence — is **~13–18
  person-days** with AI assistance. It's ~2k source lines but behaviourally dense; the long pole
  is the owner-draw fidelity and the over-the-wire timing behaviours, not the parts that came
  easily.

- **The whole three-pane slice** (feeds tree + headlines + reader, faithfully) is realistic in
  a **timeboxed two weeks** with AI + the MCP + a source-literate reviewer — which is what this
  experiment demonstrates.

- **A full RCP application is a different order of magnitude, and most of the remaining mass is
  *not* widgets.** The Workbench/perspective/command model, the OSGi extension registry, and
  RSSOwl's own extension points are the real surface area — and the part with no direct Vaadin
  equivalent. Budget for **re-architecting the application shell**, not just re-drawing screens.
  The widget layer is the fast, cheap part; the RCP application model is the slow, expensive
  part.

**A finding worth its own line:** the report was the deliverable, but the PoC quietly became a
genuinely usable multi-user feed reader (Keycloak SSO, per-user state, full-text search,
filters, labels, bins, keyboard nav) — because each "let's verify *this* migrates" turned into a
working subsystem. That the by-product is usable is itself evidence for the thesis: the
migration is fast enough that a faithful result falls out of the verification work — *with* the
two standing caveats kept next to it: (1) completeness needs a source-literate reviewer, and
(2) a usable *slice* is not a migrated *RCP app*.

---

## Try it on your own codebase

The point of an honest experiment isn't "trust us" — it's "here, verify it, then do it
yourself." Two reusable assets, and neither alone is enough:

- **The repo is a worked example.** Clone it, run it, and see a real SWT/RCP master-detail
  actually migrated and running in a browser — the reference for what the Vaadin side of a
  `TreeGrid` + Signals + `GridContextMenu` migration looks like.

  ```sh
  git clone <this-repo>
  cd swt-rcp-to-vaadin-modernization/poc/headlines
  ./mvnw spring-boot:run        # JDK 21+; first run downloads the frontend toolchain
  ```
  Then open <http://localhost:8080>.

- **The prompts are the method.** [`PROMPTS.md`](PROMPTS.md) plus [The method](#the-method-how-to-actually-use-ai-for-this)
  above are the recipe you point at your *own* app: clone your SWT/RCP source, build it from
  source first to confirm it still runs, map your JFace viewers to `Grid`/`TreeGrid` the same
  way, **query the Vaadin MCP server for current APIs**, plan the dense parts before coding, and
  verify by running the app.

The repo proves it's possible; the prompts make it repeatable. Aim the same method at your own
SWT/RCP app.

---

*Every claim here is traceable to the dated, blow-by-blow account in the
**[working journal](JOURNAL.md)**. "Before"/"after" screenshots are in
[`before/`](before/) and [`after/`](after/).*
