# SWT/RCP → Vaadin: What Actually Happened

> **A living document.** This is written as the experiment progresses, not reconstructed
> afterwards. It will contain dead ends, wrong turns, and things that didn't work — on
> purpose. See [`../README.md`](../README.md) for the experiment brief and ground rules.
>
> _Status: target chosen; original binary diagnosed as dead; runnable "before" obtained (incl. a
> native-arm64 from-source build); the slice's RCP/JFace code mapped and the migration designed
> (plan mode); and a **runnable Vaadin 25.1 POC built and verified** in the browser
> (`poc/headlines/`, screenshots in `docs/after/`). End of **day 1**: the POC now reproduces
> RSSOwl's **three-pane layout** (feeds tree → headlines → reader) over **live RSS from RSSOwl's
> default feeds** — 15 category folders + ungrouped channels + 5 saved-search smart folders, with
> grouping, sorting, a context menu, and Signals-driven detail. Honest findings recorded below._

---

## The question

> **Can an SWT master-detail screen — a tree of feeds, a sortable headlines table with a
> context menu, and an article reader — be moved to a Vaadin Grid + detail view, and where
> does it break?**

Framed from the developer's side, not Vaadin's. The headline word is **"where does it
break"** — this is an honest experiment, so the interesting output is the list of things
that don't map cleanly, not a victory lap.

---

## Choosing the target: RSSOwl

We needed a *real* SWT/Eclipse RCP application, not a toy. The brief is explicit that "a
button that becomes a Vaadin button proves nothing; a real grid with interaction proves the
thing people doubt." So the slice had to be a genuine master-detail screen.

Two candidates made the shortlist:

| Candidate | For | Against |
|---|---|---|
| **RSSOwl** (`github.com/rssowl/RSSOwl`, EPL-1.0) | Iconic *stranded* SWT/RCP app. Textbook master-detail (feeds tree → sortable headlines table w/ context menu → article reader). Perfect fit for the "dying desktop toolkit, team wants to stay in Java" narrative. | Archived 2014/2019, Java 8 only. The original binary no longer runs on a current Mac (see below). |
| **Portfolio Performance** (`github.com/portfolio-performance/portfolio`, EPL) | Actively maintained e4 RCP, builds today with Maven/Tycho, rich grids and charts — easy to run for a live before/after. | Heavier; finance domain adds cognitive load; the "want out of the toolkit" story is weaker since its maintainer is happily on RCP. |

**Decision: RSSOwl.** The narrative fit and the clean, instantly-understood master-detail
slice are worth more to an *honest* experiment than easy build-ability. Portfolio Performance
was rejected for fit, not capability.

And — fittingly for this experiment — the very first thing we tried with RSSOwl didn't work.

---

## The original binary is dead (and that *is* a finding)

We downloaded the official **RSSOwl 2.2.1** macOS disk image and tried to run it. The DMG
mounts fine; the app refuses to launch.

Diagnosis. Everything in `RSSOwl.app` is 32-bit and built against a framework Apple has
removed:

| Component | Architecture |
|---|---|
| Launcher `Contents/MacOS/RSSOwl` | `i386` + `ppc` only |
| Native launcher `eclipse_1406.so` | `i386` + `ppc` only |
| SWT | `org.eclipse.swt.`**`carbon`**`.macosx_3.7.0` (Carbon, 32-bit) |

The host is **macOS 26.5.1 on Apple Silicon (arm64)**. Three independent, individually-fatal
reasons it can't run:

1. **No 64-bit slice exists.** The binary is `i386`/`ppc`. Apple removed all 32-bit support
   in macOS 10.15 Catalina (2019). macOS simply refuses to `exec` a 32-bit Mach-O.
2. **Rosetta can't help.** Rosetta 2 translates **x86_64** → arm64 only. It does *not*
   translate `i386` (32-bit Intel) or `ppc`. The 32-bit fallback path doesn't exist.
3. **Carbon is gone.** Even on a 64-bit Mac, this build links the Carbon SWT port — a UI
   framework Apple deprecated and removed. Modern SWT is the Cocoa port.

So the 2011-era binary is dead on any Mac newer than ~2019. No local configuration fixes it;
it would need an old macOS (≤10.14) or a VM.

**Why this matters.** This is the experiment's thesis in a single artifact: the flagship
binary of the app we're migrating **won't even launch on a 2026 developer's machine.** That
is the "before" — the cost of staying on a dying desktop toolkit, made concrete.

---

## Getting a runnable "before": RSSOwlnix

To show the original UI live (for before/after), we used **RSSOwlnix**
(`github.com/Xyrio/RSSOwlnix`), the maintained community fork. Latest release **2.10.0**.

A sharper framing emerged here, from Enver:

> "old software that was moved from 32 to 64 bit, but still needs x86_64 which will soon be
> dropped by Apple."

This indicts the *maintained* path too. RSSOwlnix ships **only** `macosx.cocoa.x86_64`
(confirmed across releases 2.8 / 2.9 / 2.10 — there is **no arm64 build**). So on Apple
Silicon it runs **only under Rosetta 2**, which Apple is itself phasing out. The desktop UI
survives by riding emulation layers that keep expiring underneath it:

- **2011 original:** 32-bit `i386` / Carbon → dead since macOS Catalina (2019).
- **2025 maintained fork (prebuilt release):** 64-bit, but **x86_64-only** → runs only via
  Rosetta, which Apple is phasing out.

> **Honest correction (added after the source dive — see below).** Our first instinct was to
> call this "a dying toolkit whose escape hatches keep closing." That over-claims. When we
> built the fork *from source*, it produced a **native arm64** app that runs **without Rosetta**
> — because modern SWT/RCP (Eclipse 4.30, Nov 2023) ships a current `cocoa.macosx.aarch64`
> fragment. The x86_64 lock-in is a **packaging choice in the release build**, not a limit of
> the toolkit. We left the original (wrong-leaning) framing visible here on purpose; correcting
> it *is* the experiment. The real, defensible pain is narrower: the *original* binary is dead,
> and getting any modern build at all requires a from-source Tycho rebuild that an end user
> won't do.

### It runs — but not out of the box

Getting RSSOwlnix 2.10.0 to launch on macOS 26 / Apple Silicon took several non-obvious steps.
None are hard individually, but a normal user dragging the app to `/Applications` hits a wall.

```sh
# 1. Download + extract the only macOS build (x86_64)
curl -sL -o rssowlnix.tar.gz \
  https://github.com/Xyrio/RSSOwlnix/releases/download/2.10.0/RSSOwlnix-2-10-0-macosx.cocoa.x86_64.tar.gz
tar -xzf rssowlnix.tar.gz

# 2. The tarball loses the executable bit on the launcher — restore it
chmod +x RSSOwlnix.app/Contents/MacOS/RSSOwlnix

# 3. Pin the JVM to an x86_64 Java 17 (the SWT dylib is x86_64, so the JVM must be too;
#    an arm64 JVM cannot load it). Insert -vm BEFORE -vmargs in the .ini:
#    RSSOwlnix.app/Contents/Eclipse/RSSOwlnix.ini
#      -vm
#      /Library/Java/JavaVirtualMachines/amazon-corretto-17.jdk/Contents/Home/bin/java
#      -vmargs
#      ...

# 4. We edited the bundle, which invalidates its code signature — re-sign ad-hoc
codesign --force --deep --sign - RSSOwlnix.app

# 5. Launch (runs translated under Rosetta 2)
open -a RSSOwlnix.app
```

**Failure modes seen along the way, and what they actually meant:**

- `open` → `RBSRequestErrorDomain Code=5 "Launch failed" … NSPOSIXErrorDomain Code=111` —
  generic "launchd job spawn failed." Real cause: the launcher had lost its `+x` bit on
  extraction (`arch: … isn't executable`). Fixed by `chmod +x`.
- After editing `RSSOwlnix.ini`, the modified bundle's signature no longer matched — fixed
  with an ad-hoc `codesign --force --deep`.
- Requirements confirmed from the project itself: "runs with java 17 and 21" and "no 32bit
  support anymore because eclipse dropped 32bit."

Once running, the prebuilt release's JVM is x86_64 Corretto 17 under Rosetta, with SWT Cocoa
`org.eclipse.swt.cocoa.macosx.x86_64_3.124.200`.

### …but from source it builds native arm64 in 2.5 minutes (no Rosetta)

When we cloned the fork to read its code (next section), we checked *why* the releases are
x86_64-only. The cause is three lines. `releng/configuration/pom.xml` configures the Tycho
build's target environments, and every one is hardcoded to `x86_64`:

```xml
<environment><os>macosx</os><ws>cocoa</ws><arch>x86_64</arch></environment>
```

The target platform points at the **Eclipse 4.30** update site, whose `org.eclipse.rcp` feature
*does* contain the `org.eclipse.swt.cocoa.macosx.aarch64` fragment — it just isn't requested
(`includeAllPlatforms="false"` means Tycho only materializes the listed arches). So we changed
that one environment to `aarch64` and ran the documented build with an arm64 JDK 17:

```sh
# in the RSSOwlnix clone, after editing the <environment> arch to aarch64
export JAVA_HOME=…/corretto-17.0.10/Contents/Home   # arm64 JDK 17
mvn -B clean verify -Dmaven.test.skip=true          # Tycho 4.0.13
```

Result, in **2m23s**:

- output `releng/product/target/products/RSSOwlnix-macosx.cocoa.aarch64.tar.gz`,
- launcher `RSSOwlnix` is a **single-arch `arm64`** Mach-O (so it *cannot* be Rosetta-translated —
  Rosetta only handles x86_64; if it runs, it's native),
- it ships `org.eclipse.swt.cocoa.macosx.aarch64_3.124.200`,
- and it **launches and renders the full master-detail UI natively** (screenshot:
  [`before/rssowlnix-arm64-native.png`](before/rssowlnix-arm64-native.png)), pinned to an arm64
  JDK 17.

**The honest takeaway, both directions:**

- *Against the "stuck on dying tech" story:* SWT/RCP is **not** abandoned at the toolkit level.
  Eclipse ships current native arm64 SWT; a stranded app can be brought back to a modern,
  native desktop build quickly. "You can't run SWT on Apple Silicon" is false.
- *For the migration story:* the catch is **who does that rebuild.** It needs the source, a
  Tycho/Maven toolchain, the right JDK, and the knowledge to flip the target environment —
  none of which a typical end user (or even many app owners) has. The official channel still
  hands users an x86_64 binary on borrowed Rosetta time. So the case for moving to the web
  rests on **distribution, reach, and zero-install deployment**, *not* on a false claim that the
  desktop toolkit can't keep up.

---

## It's RCP, not just SWT — and that's the real scope

Before mapping anything, we checked a question that changes the whole size of the job: is
RSSOwl a bare **SWT** application (a `main()` that opens a `Shell` and lays out widgets), or a
full **Eclipse RCP** application (the OSGi/Equinox runtime, the JFace viewer framework, and the
Workbench with views/perspectives/commands)?

We inspected the running bundle. It is unambiguously **full RCP**, hosted on the modern
Eclipse 4 (e4) platform through the 3.x compatibility layer:

- The product boots **167 OSGi bundles**. `config.ini` declares an RCP product and
  application: `eclipse.product=org.rssowl.ui.product`,
  `eclipse.application=org.rssowl.ui.rssowl`. The RCP feature itself
  (`org.eclipse.rcp_4.30.0`) and the whole Equinox/JFace/Workbench/e4 stack are present.
- `org.rssowl.ui` ships the classic RCP advisor classes — `Application`,
  `ApplicationActionBarAdvisor` (with dozens of action inner-classes) — and plugs into the
  workbench extension model: `org.eclipse.ui.perspectives`, `.views`, `.commands`,
  `.actionSets`, **`.popupMenus`**, `.preferencePages`, `.newWizards`, `.themes`,
  `.splashHandlers`. (Correction, verified later in source: the headlines-table context menu is
  built *programmatically* via a JFace `MenuManager`; `.popupMenus` only *augments* it — see the
  context-menu row below and the design section.)
- RSSOwl even defines **its own** extension points (`org.rssowl.ui.ShareProvider`,
  `NewsActionPresentation`, `LinkTransformer`, `KeywordFeed`, `FeedSearch`,
  `EntityPropertyPage`) — it is itself extensible via the OSGi extension registry.

**Why this matters.** "Map SWT widgets to Vaadin components" undersells the work — SWT is the
easy bottom layer. The real surface is the RCP application model on top:

| Layer | RSSOwl uses | Migration reality (Vaadin 25.1, MCP-verified) |
|---|---|---|
| Widgets | SWT `Tree`, `Table` (the headlines "table" is itself a `TreeViewer`/`CTree` so it can group rows) | → Vaadin `TreeGrid` (it's a superset of `Grid`; flat list = a tree with no children) |
| Viewer framework | JFace `TreeViewer`/`TableViewer` + content/label providers, sorters, filters | → `Grid`/`TreeGrid` columns; label providers → renderers; `ViewerSorter` → `setSortable(true)` + `Column.setComparator(...)`; filters → `DataProvider` filtering. Conceptually similar, real rewrite |
| Master→detail wiring | Workbench **selection service** between ViewParts | → a `ValueSignal` holding the selected item; the detail binds reactively (Vaadin 25.1 **Signals**), *not* manual listener wiring — see the trap below |
| Context menu | **programmatic** JFace `MenuManager` (rebuilt per open), *augmented* by declarative `org.eclipse.ui.popupMenus` `objectContribution`s on `INews` | → `GridContextMenu` + `setDynamicContentHandler(...)` (per-target rebuild); the declarative `MB_ADDITIONS` extension contributions have **no** web equivalent |
| Actions / commands | `actionSets`, `commands`, `ApplicationActionBarAdvisor` | → `Button` / `MenuBar` items, hand-mapped |
| Modularity | OSGi bundles + extension registry | → dropped; collapses to a plain web module |

That a pure-SWT app would be a far smaller story is exactly why RCP is the version of this
question worth answering.

### The Vaadin side, grounded in the MCP server (not the model's memory)

Per the rule established below, these mappings were checked against Vaadin's MCP docs for
**25.1**, not recalled from training:

- **Sortable table.** Columns become sortable with `.setSortable(true)`; column types that are
  `Comparable` sort automatically, and a custom `Comparator` is supplied via
  `Column.setComparator(...)` (this is where RSSOwl's JFace `ViewerComparator`/`ViewerSorter`
  logic lands). Multi-column sort is `grid.setMultiSort(true, MultiSortPriority.APPEND)`;
  initial/programmatic sort uses `grid.sort(GridSortOrder.asc(col).thenDesc(col2).build())`.
- **Context menu.** `GridContextMenu<News> menu = grid.addContextMenu();` then
  `menu.addItem("Mark read", e -> ...)`. Submenus, separators and custom item components are
  supported. The right-clicked row is `event.getItem()` — the equivalent of resolving the
  selection an SWT `popupMenus` contribution acts on.
- **Feeds tree.** `TreeGrid` backed by `TreeData<T>` + `TreeDataProvider<>`. **Caveat (honest
  finding):** Tree Grid's data loading was **refactored with breaking changes in V25** — there
  are now two `HierarchyFormat`s (`NESTED` default, new `FLATTENED`), and `pageSize` now applies
  to the whole flattened hierarchy, not per level. Docs *recommend* `FLATTENED` so scroll
  position survives hierarchy edits. Following any pre-25 TreeGrid tutorial would be a trap.
- **Layout.** Vaadin ships a dedicated **Master-Detail Layout** component, a natural fit for the
  whole three-pane screen — worth evaluating instead of hand-building split panes.

So even on the Vaadin side the current facts differ from older knowledge (TreeGrid's V25
breaking changes, Signals replacing listeners) — reinforcing that the MCP query is the step,
not the fallback.

## The slice we're migrating

With RSSOwlnix running we captured the actual screen we intend to move. It is a clean
three-pane master-detail:

![RSSOwlnix master-detail](before/rssowlnix-masterdetail.png)

1. **Feeds tree (master)** — left pane: categories and feeds, selection drives the rest.
2. **Headlines "table" (detail list)** — top-right. Despite looking like a table it is actually a
   JFace `TreeViewer` over a custom `CTree`, because it can show rows **flat or grouped** (by
   feed/date/sticky). It has **sortable columns** (15 defined; Title / Date / Author / Category…
   visible), per-row state (bold = unread), custom owner-draw colors, in-cell interactive icons,
   and a context menu. *This is the component the whole experiment is really about.*
3. **Article reader (detail)** — bottom-right: renders the selected headline.

This maps onto Vaadin as: a `TreeGrid` (sorting, selection, renderers, context menu — and grouping
for free, since it's a superset of `Grid`) + a detail component bound to the grid's selection via a
Signal, wrapped in a `MasterDetailLayout`. The headline question — "can an SWT table with sorting
and a context menu move to a Vaadin Grid, and where does it break?" — is now concrete and visible,
and the design (and its lossy edges) is worked out below.

Other "before" screenshots captured:

- `before/rssowlnix-before.png` — first-run Welcome wizard (proves it launches under Rosetta).
- `before/rssowlnix-feeds.png` — the populated feeds tree mid-update.

### A tooling note worth recording

Capturing these screenshots was itself instructive about SWT on modern macOS: **the SWT window
does not expose an accessibility tree.** `System Events` button-clicks silently no-op and the
inner widgets (tree rows, table cells) aren't queryable. Only two things worked: resizing via
`set size of window 1`, and raw `key code` keystrokes to navigate the tree. To click a table
row we fell back to `cliclick` with coordinates (Retina 2× → logical point = screenshot pixel
÷ 2, verified with `screencapture -C` to show the cursor). Worth remembering if anyone tries to
UI-test a legacy SWT app.

---

## Scale of the slice (lines of code)

To keep "a representative slice, not the whole app" honest, here is the size of what we're actually
migrating versus the whole codebase (cloc, Java code lines only, in the RSSOwlnix source):

| Scope | Java code lines |
|---|---|
| Whole RSSOwl(nix) project | 121,261 |
| — of which **tests** (`org.rssowl.core.tests`) | 35,406 |
| — of which **production** code | **85,855** |
| `org.rssowl.ui` (the UI bundle) | 57,334 |
| `org.rssowl.core` (model/persistence/feed logic) | 28,521 |
| The master-detail slice (the 15 tree/table/reader files) | 9,000 |
| The headline table proper (6 files incl. `NewsComparator`) | 1,980 |

**A fair objection (raised in review): "how can this tiny app be 121k lines?"** Two things the
headline number hides. First, **~29% of it (35,406 lines) is test code** — production Java is
**~85,855 lines**. Second, that figure is what this 20-year-old **full Eclipse RCP** app already
*contains*, not what the product *needs*. The user-facing product is genuinely small (a feed
reader), but RCP RSSOwl ships its own infrastructure: object-database integration (db4o), full-text
search (Lucene), feed parsing (JDOM), an embedded browser, OPML import/export, a notification-popup
system, retention/cleanup, labels & stickies, saved searches, sync, and preferences — spread across
~167 OSGi bundles. (The bundled libraries db4o/Lucene/JDOM/HttpClient are JARs, so they add **0**
source lines here.) The UX is small; the machinery is not.

And that gap is the point. The slice we actually migrate is ~7% of the project; the headline table
at its center is ~2k lines — and *nobody rewrites 121k lines*. That ~2k lines is what the design
below sizes at **~13–18 person-days** to faithfully re-create in Vaadin — a useful ratio when
extrapolating: the visible widget is small, but its behavior (sorting, owner-draw, interactive
cells, context menu, selection wiring) is dense.

## Working method: the model's Vaadin knowledge is stale — query the MCP server

This experiment is as much about *how to use AI to do the migration* as about the migration
itself, so this trap is worth recording in full.

While sketching the master→detail mapping above, the assistant wrote that the Workbench
selection service "becomes explicit listener wiring" on the Vaadin side. That is **wrong for
Vaadin 25.** It reflects the model's training cutoff (January 2026) and the way Vaadin worked
for years — not the current platform.

The correction came from the human in the loop, and was then confirmed against Vaadin's own
**MCP server** (which the assistant had _not_ queried before making the claim — the first
mistake):

- Latest stable Vaadin is **25.1.8**, released **2026-06-15** — *after* the model's training
  cutoff. Anything the model "knows" about 25.1 is unreliable by construction.
- The Vaadin primer exists specifically to "address common AI misconceptions." Vaadin 25.1
  introduces **Signals**: reactive UI state with element-level binding.
- The docs demonstrate our *exact* use case — a Grid synchronized with a detail form via a
  `selectedItemSignal` plus `Signal.effect()` — and describe it as eliminating "complex event
  handling chains." So the modern wiring is:

  ```java
  // The selected headline is the single source of truth.
  ValueSignal<News> selected = new ValueSignal<>(null);
  grid.asSingleSelect().addValueChangeListener(e -> selected.set(e.getValue()));

  // The detail binds reactively — no manual "when selection changes, update X" plumbing.
  Span title = new Span();
  title.bindText(selected.map(News::getTitle));
  ```

**The lesson for the method:** treat the model's built-in framework knowledge as a *stale
cache*. For anything on the Vaadin side, query the MCP server **first** — `get_vaadin_primer`
and `search_vaadin_docs` for the target version — and prefer it over what the model
"remembers." We avoided shipping an outdated, listener-based design only because a human caught
it; the durable fix is to make the MCP query a required step, not an afterthought.

## Designing the headline-table migration (and switching to plan mode)

A process note, because this experiment is also about *how* to use AI well. Before writing any
Vaadin code for the headline table, the human deliberately switched the assistant into **plan
mode** — design first, code second. For a slice this dense (a `TreeViewer` masquerading as a
table, 15 columns, ~500 lines of custom comparator, heavy owner-draw, interactive in-cell icons,
a dynamically-rebuilt context menu), that paid off: planning surfaced the parts with *no clean
Vaadin equivalent* up front, instead of discovering them mid-implementation after the structure
was already committed. The design below was produced by exploring the real SWT/JFace source and
checking every Vaadin API against the MCP server (per the rule above).

**Target component — `TreeGrid<NewsRow>` for everything.** The SWT control is one `TreeViewer`
that is flat *or* grouped; Vaadin `TreeGrid` extends `Grid` and inherits all of its
sorting/selection/renderer/context-menu/styling features, so the flat case is just a tree with no
children — one codepath, no Grid↔TreeGrid swap. Backed by
`TreeDataProvider<>(treeData, HierarchyFormat.FLATTENED)` (FLATTENED keeps scroll position across
the frequent `refreshAll()` on mark-read/regroup). Rows modeled as a sealed
`NewsRow permits GroupRow, NewsItemRow`.

**The mapping, by concern:**

- **Data model** — immutable `record NewsItem(…)` with a stable `id` (Grid needs stable identity
  for selection/`refreshItem`). Filter/group/sort are pure functions over the master list,
  orchestrated by Signals; grouping = data-shaping into `TreeData`, sorting = per-column
  comparators (not pushed through the data provider for in-memory data).
- **Columns** — keep the `NewsColumn` enum as the metadata registry; build columns in a loop with
  `setKey(column.name())`. Visibility/order/width via `setVisible`/`setColumnOrder`/`setWidth`+
  `setFlexGrow`. The `CColumnLayoutData` fill-% widths are *approximated* by flexGrow ratios.
- **Sorting** — port each `NewsComparator` branch to a `Comparator` on
  `Column.setComparator(...).setSortable(true)` (dates `nullsLast`, `CASE_INSENSITIVE_ORDER`,
  status-rank, booleans, labels-by-order). Header-click toggle + sort triangle are built into
  Grid (the SWT `setSortColumn/Direction` code is *deleted*, not ported). Restore persisted sort
  via `grid.sort(GridSortOrder…)`.
- **Rendering — different mechanism per effect:** bold-unread and sticky full-row background →
  `setPartNameGenerator` + global CSS (row bg via `--vaadin-grid-cell-background`); state/feed/pin
  icons → renderers, with **`ComponentRenderer` confined to the TITLE column** (one component per
  cell is costly) and `LitRenderer` for static icons; arbitrary per-row **label color** → inline
  style on rendered content (can't be enumerated as CSS parts).
- **Interactive icons** — SWT pixel hit-testing becomes real clickable sub-elements: `LitRenderer`
  `@click`+`withFunction` for toggle-read / toggle-sticky (must `stopPropagation` so it doesn't
  also select the row), a small menu for attachments. Double-click → `addItemDoubleClickListener`.
- **Selection → detail** — drop the workbench selection-service indirection entirely; a
  `ValueSignal<NewsItem> selected` set by `grid.addSelectionListener`, with the reader reacting via
  `Signal.effect(...)`, all wrapped in `MasterDetailLayout`.
- **Context menu** — `GridContextMenu` + `setDynamicContentHandler(row -> …)` mirrors the JFace
  `MenuManager.setRemoveAllWhenShown`/`menuAboutToShow` rebuild.

**Lossy / hard / not 1:1 (the honest core, found *before* coding thanks to planning):**

1. **Full-row label-color-on-select owner-draw** — the hardest: arbitrary per-row hex *and*
   selected state can't compose through enumerable CSS parts. Expect a design compromise.
2. **Alternating per-row gradient** — only flat `ROW_STRIPES` is built-in; the gradient is lossy.
3. **In-cell icon click vs. row click** — needs `stopPropagation`; a behavioral edge to test.
4. **Auto-mark-read-after-delay** — SWT used a UI-thread timer; the web needs `@Push` +
   scheduled task + `ui.access` + cancel-on-reselect. Race-prone, not free.
5. **Multi-select range/anchor semantics** — reproducible, not identical.
6. **Column-state persistence** — no built-in Grid state store; must be re-implemented.
7. **`MB_ADDITIONS` extension contributions** — Eclipse let *other plugins* add menu items
   declaratively. **No web equivalent** — an architectural capability that is simply lost.

**Effort for this sub-slice, with AI assistance: ~13–18 person-days.** Top risks: (1) the
owner-draw selection paint, (2) auto-mark-read + multi-select over the wire, (3) discovering lost
extension-point menu contributions late.

## Building & verifying the POC (Vaadin 25.1)

The POC lives in [`../poc/headlines/`](../poc/headlines/): a Vaadin **25.1.8** Flow app
(Spring Boot 4.0.7, Aura theme, `@Push`), scaffolded from `start.vaadin.com/skeleton`. Run it with
`cd poc/headlines && ./mvnw spring-boot:run` → <http://localhost:8080> (build/run with a JDK 21).
It started as a flat `Grid<NewsItem>` over a 9-row fixture; it has since been **deepened** to a
`TreeGrid<Row>` showing **live headlines from RSSOwl's own default feeds**, with **grouping** — see
"Deepening" below. (The fixture remains as an offline fallback.)

We then drove the running app with the **Playwright MCP** as the verification harness. "Before"
(native SWT) on the left in spirit; "after" (Vaadin, in the browser) below:

| What | After (Vaadin 25.1) |
|---|---|
| Default view — bold-unread, state icons, label-coloured titles, **sticky rows highlighted**, toggle icons | ![initial](after/headlines-initial.png) |
| Sort by Title (header click) | ![sorted](after/headlines-sorted-title.png) |
| Master→detail via a Signal | ![detail](after/headlines-detail.png) |
| Dynamic `GridContextMenu` (per-row text) | ![context menu](after/headlines-contextmenu.png) |

### Deepening: real RSS feeds + grouping (TreeGrid)

Three follow-ups took the PoC from a fixture to the real, full RSSOwl shape:

**1. Real feeds — RSSOwl's own defaults.** RSSOwl's "Import Recommended Feeds" first-start option
loads a bundled OPML (`org.rssowl.ui/default_feeds.xml`, 77 feeds). That file is from **2009** and
most URLs are dead, so we ship our own `feeds.opml` mirroring its category structure — the same
alphabetical folders (Business, Entertainment, Health, Politics, Science, Sports, Technology, World)
— populated with **current** section feeds from reliable publishers (BBC, Guardian, NYT, NPR, …).
A `FeedService` (Rome parser) fetches them concurrently with per-feed timeouts, de-dupes, and maps
entries to `NewsItem`; if every feed fails (offline/CI) it falls back to the fixture behind a banner.
A live run logged **"Loaded 800 headlines from 26 feed(s)"** in ~5s.

**2. Grouping via `TreeGrid`.** The flat `Grid` became a single `TreeGrid<Row>` (sealed
`Row = GroupRow | ItemRow`), with a "Group by" selector (None / Date / Status / Author / Category /
Feed / Sticky). Grouping is data-shaping into group parents over item children — flat mode is just a
tree with no children, one codepath. This is exactly the design's "TreeGrid is a superset of Grid".

**3. The full three-pane RSSOwl layout.** On request, the PoC was restructured to match RSSOwl
structurally: a **left feeds-navigation tree** (`BookMarkExplorer` equivalent), a **top-right
headlines** grid, and a **bottom-right reader** — nested `SplitLayout`s. The tree mirrors RSSOwl's
`default_feeds.xml`: the same **category folders** (Business, Computers, Entertainment, Health,
News, Politics, Science, Sports, Technology, **Weblogs**) plus the **ungrouped top-level channels**
that sit beside them (Gizmodo, Engadget, Google News, MarketWatch, …), each with a count. Selecting
a category or feed filters the headlines; a second `TreeGrid` (the feeds tree) drives the first.

> *Self-correction:* the first cut of this invented my own category set (incl. a non-RSSOwl "World",
> no "Weblogs", and no ungrouped channels). Caught in review — now aligned to RSSOwl's actual taxonomy.

| What | After (Vaadin 25.1, live data) |
|---|---|
| **Three-pane layout** — full feeds tree (15 folders + channels + smart folders) ‖ headlines ‖ reader | ![three-pane](after/threepane-layout.png) |
| In use — select a headline, reader fills via a Signal | ![reading](after/threepane-reading.png) |
| Click a feed → headlines filter to it (BBC Business) | ![filtered](after/threepane-filtered.png) |
| Saved-search smart folder selected (News with Attachments) | ![smart folders](after/threepane-smartfolders.png) |
| Group by **Feed** — bold group headers with counts, items nested & date-sorted | ![grouped by feed](after/grouped-by-feed.png) |
| Live headlines from the default feeds | ![live feeds](after/feeds-live.png) |

Verified via Playwright: the left tree lists RSSOwl's category folders with real counts plus the
ungrouped channels below them; selecting a feed filters the middle pane; grouping nests items under
bold counted headers; selection→detail works on the TreeGrid (Signals); right-clicking a **group**
row shows **no** context menu (`setDynamicContentHandler` returns `false`). Honest notes: counts are
**real** (driven by what the live feeds return — not the literal "151" from RSSOwl's old screenshot);
some feeds fail and are skipped (e.g. **CNN's `rss.cnn.com` dropped the SSL handshake**, so it shows
no rows — the graceful-skip path in action); with all-fresh items **Date grouping collapses to one
"Today" bucket**; BBC sets no per-item author ("Unknown") and Engadget exposes a raw `staff@…`
author — real RSS is messy. One runtime bug surfaced only by running it: `TreeGrid` throws
*"Cannot add the same item multiple times"* when feeds repeat a link — fixed by de-duping items by a
stable 64-bit id before building the tree.

### Fidelity gap: fewer channels than the original (caught in review)

Comparing the running Vaadin tree against the RSSOwl "before" screenshot side by side, the human in
the loop noted: *"there are fewer channels in the Vaadin version. Why?"* — a fair hit, and worth
recording because it's the recurring shape of this experiment (the AI silently under-delivers
fidelity; a human who knows the original has to keep checking). The first cut had ~14 top-level rows
vs RSSOwl's ~32. Four reasons, none "Vaadin can't" — and three are now closed in review:

1. **~~Dropped 5 of 15 category folders~~ (Fixed.)** Food, Internet, Music, Podcast, Software are now
   present too — all **15 RSSOwl categories** with current best-effort feeds (a few feeds are flaky,
   e.g. Smitten Kitchen returns malformed XML and is skipped).
2. **Ungrouped channels under-represented (Fixed.)** RSSOwl lists BBC News, NYT, Guardian, TechCrunch,
   Wired *both* inside folders *and* as loose channels; a `FEATURED` set now surfaces them as
   top-level channels too (no data duplication). Still absent: **CNN** (SSL handshake fails) and
   **Reuters** (killed public RSS).
3. **~~No saved-search smart folders~~ (Fixed.)** RSSOwl's Unread News / Today's News / News with
   Attachments / Sticky News / Labeled News are *saved searches*; the tree now shows all five at the
   bottom as computed `FeedNode.Saved` filters over all items (Attachments uses RSS enclosures —
   so podcasts/media match). **"Labeled News" is an honest stub** (count 0): the PoC has no
   user-label feature — the title colours are per-category, not user labels.
4. **~~Counts arbitrary~~ (Fixed — copied RSSOwl's actual limit.)** Asked "is there a MAX_ITEMS in the
   old software?", we found RSSOwl's `PreferencesInitializer`: count-based cleanup is **on by default**
   at **200 news per feed** (`DEL_NEWS_BY_COUNT_VALUE = 200`). The PoC now applies that exact
   **per-feed 200 cap** instead of an arbitrary global number, so per-category counts are real and
   RSSOwl-scaled (Business 142, Podcast 213, Sports 175, Weblogs 167, …). Still differs from RSSOwl's
   *accumulated unread over time* — one live fetch ≠ years of retained articles.

So the tree now matches RSSOwl structurally: **15 category folders + ~10 ungrouped channels + 5
saved-search smart folders**, governed by RSSOwl's own 200/feed limit. What's left is genuinely
unavailable, not unbuilt: two dead endpoints (CNN's SSL, Reuters' removed RSS) and the "Labeled
News" stub (no user-label feature in the PoC). All four review-caught reasons are now closed or
explained.

The honest takeaway for "can AI do this migration?": yes mechanically, but it will quietly produce a
*plausible-looking* approximation that's missing pieces only someone familiar with the original will
spot. The migration tooling reproduces structure; faithful *completeness* still needs human review.

### Where it landed (end of day 1)

The last step closed the loop from "a flat list of headlines" to **the recognizable RSSOwl screen**:
a three-pane layout whose left tree carries all 15 category folders, the loose top-level channels,
and the five saved-search smart folders — over **live** headlines from RSSOwl's own default feeds,
capped by RSSOwl's own 200/feed rule. Set the "before" (native SWT, `docs/before/`) beside the
"after" (`docs/after/threepane-layout.png`, `threepane-reading.png`) and they read as the same
application — one desktop, one in a browser tab, no install.

What that took, honestly: the *structure* came quickly with AI + the Vaadin MCP, but **every gap
between "looks right" and "is right" was closed by human review**, not by the model noticing — the
stale Signals API, the dead-toolkit over-claim, the invented feed taxonomy, fewer channels, and the
arbitrary item cap. That is the experiment's central finding, and the most useful thing to tell an
architect weighing this migration: the tooling gets you a faithful skeleton fast; a person who knows
the source app turns it into a faithful *app*. The behavioral long-pole items (multi-select range
semantics, the auto-mark-read timer, column persistence, real user-labels, owner-draw selection
paint) remain — sized in the design section, and the honest reason the full sub-slice is ~13–18 days,
not an afternoon.

### What the AI got right, first try
- The whole structure compiled after **two** real API fixes (below) and ran first launch.
- **Signals master→detail just worked**: `grid.addSelectionListener` sets a `ValueSignal<NewsItem>`,
  `Signal.effect(detail, …)` rebuilds the reader. No listener plumbing on the detail side — exactly
  what the MCP docs promised, and visibly cleaner than the RCP selection-service indirection.
- **Sorting**: header-click sort + the sort triangle came free from `Grid` once each column had a
  `setComparator(...)` — the ~500-line `NewsComparator` collapses into a handful of one-liners.
- **`GridContextMenu` + `setDynamicContentHandler`** reproduced the per-open menu rebuild faithfully
  ("Mark read"↔"Mark unread", "Make/Remove sticky").
- **bold-unread** (part-name generator + `::part(unread){font-weight}`) and **label colours**
  (inline style on the title cell) rendered correctly.

### What it got wrong / what running it surfaced (the honest part)
- **Two compile errors from stale-model API guesses** (caught by `javac`, not by me): `new
  ValueSignal<>()` won't infer from a field (needs `new ValueSignal<NewsItem>((NewsItem) null)`);
  and the Grid **item-double-click** event's `getItem()` returns `T` directly while the
  **context-menu** event's returns `Optional<T>` — a genuine Grid API inconsistency.
- **The documented row-background technique is wrong under Aura.** The MCP styling docs say set
  `--vaadin-grid-cell-background`; we did, the custom property *was* set on the cell — but the
  computed background stayed white. Under the new **Aura** theme the cell background is painted
  independently; only `background-color: … !important` on `::part(sticky)` worked. This is the
  "owner-draw is the hardest" prediction coming true — milder than feared (a one-line CSS fix) but
  it cost a real debugging loop and only surfaced by *running and inspecting the shadow DOM*.
- **`nullsLast` flips under descending sort.** RSSOwl always sorts null dates last; in Vaadin the
  comparator is reversed for descending, so the null-date row sorts *first* under the default
  date-desc sort. Faithful behaviour needs a direction-aware comparator — a subtle fidelity gap.
- **Lumo theme variants don't apply under Aura** (`GridVariant.LUMO_ROW_STRIPES` showed no stripes).
- **`MasterDetailLayout` has no Java API in the MCP** (lookup errored), so the POC used
  `SplitLayout`. Re-confirms the MCP is current but not exhaustive for the newest components.
- **Dev-loop friction:** editing `styles.css` did nothing until an app restart — `spring-boot:run`
  serves the stale `target/classes` copy. Worth knowing before chasing phantom CSS bugs.

### Effort, honestly
This POC is a *subset*: flat (no grouping/TreeGrid), single-select, no auto-mark-read timer, no
column persistence, a trimmed column set. Even so it exercised the core question and took roughly
half a focused day with AI assistance. The full ~13–18-day estimate for the complete sub-slice
(§ design) still stands — the long pole is the items this POC deliberately left out plus the
owner-draw fidelity, not the parts shown here.

### Day 2 — drag-and-drop feed reordering (and a doubt worth recording)

The original RSSOwl lets you drag channels around the feeds tree with the mouse; the POC's tree was
static. Added it: only **channels** (Feed nodes) are draggable — category folders and saved-search
folders stay put (`setDragFilter`) — and a drop either reorders a channel among its siblings, drops
it *into* a folder (release on top of one), or pops it back out to the top level. Implemented by
moving the tree off the immutable `setItems(roots, childProvider)` form onto a mutable
`TreeData` + `TreeDataProvider`, then `setParent` / `moveAfterSibling` + `refreshAll()` on drop.
Verified by driving a real HTML5 drag in the browser: RSSOwlnix News reordered above BBC News, and
Wired dragged into the Business folder (and left the top level).

Two things this surfaced, both honest:

- **A latency trap in the documented pattern.** Vaadin's official example sets the drop mode *inside*
  `dragStart`. Because `setDropMode` only reaches the client after a server round-trip, the very first
  drop after grabbing a row can be missed if you release quickly. Setting the drop mode **once at
  setup** (it has no visible effect except during an active drag) removed the flakiness. This only
  became visible by testing the drag programmatically with no human-scale pause between grab and drop.

- **A doubt the user raised, recorded verbatim:** *"you are removing HierarchyFormat.FLATTENED and I
  wonder why? We do want a stable tree, especially when // FLATTENED keeps scroll/expansion stable
  across refreshAll() after a drag."* The doubt was correct and caught a real mistake. I had dropped
  `HierarchyFormat.FLATTENED` only because the import wouldn't compile — the wrong reason to abandon a
  feature we want. Investigating showed the class **does** exist in the resolved jar (flow-data
  25.1.7); it is a **nested** type, `HierarchicalDataProvider.HierarchyFormat`, not the top-level
  `com.vaadin.flow.data.provider.hierarchy.HierarchyFormat` the MCP doc's import comment implied
  (`javap` confirmed both the constant and the `TreeDataProvider(TreeData, HierarchyFormat)`
  constructor). FLATTENED was restored with the correct import. **Finding:** the MCP docs were right
  that the feature exists, but the *import path shown was wrong* — and "it didn't compile" is not a
  valid reason to remove desired behaviour; the fix is to find out *why* it didn't compile.

### Day 3 — multi-user (Keycloak SSO + per-user data), on branch `multi-user`

The single-state PoC became a real multi-user app: **OIDC login against an existing Keycloak** (Spring
Security OAuth2 client; identity = the Keycloak subject) over **Spring Data JPA + H2**, with everything
per-user — subscriptions, feed folders, drag order, and read/sticky/label state — seeded from the
default `feeds.opml` on first login. The data model splits *shared* (`Feed`, `Article`, fetched once)
from *per-user* (`Subscription`, `ArticleState`, keyed by subject). The view (`HeadlinesView`) reads
the logged-in user via Vaadin's `AuthenticationContext`, with a "Signed in as … / Log out" header.

Verified end-to-end in the browser with two Keycloak users: alice and bob each get their own
subscriptions; alice marking an article read **persists across reload** (it's in H2) and does **not**
affect bob; dragging a channel to a new position/folder **persists per user** (confirmed in the DB —
each owner has independent `Subscription` positions); adding a feed (`https://hnrss.org/frontpage` →
"Hacker News") fetched 52 articles and appeared in the tree, surviving reload.

Honest findings from this slice:
- **Vaadin 25 changed the security API.** The base class `VaadinWebSecurity` is gone; you configure a
  `SecurityFilterChain` with `VaadinSecurityConfigurer` + `oauth2LoginPage(...)`. Following a pre-25
  tutorial would not compile. (Confirmed against the MCP docs and the jar.)
- **Views need an explicit access annotation.** After enabling security, an authenticated user still
  got "Consider adding @AnonymousAllowed / @PermitAll" — the view must be annotated (`@PermitAll`).
- **Keycloak's default `sslRequired=external` blocks plain-HTTP OIDC** from non-localhost — both the
  server's discovery fetch *and* the browser login redirect. For a dev realm over http you must set
  `sslRequired=NONE` (or put TLS in front). Diagnosed from a `403 {"error_description":"HTTPS
  required"}`; a one-shot `kcadm` script (`keycloak/setup-keycloak.sh`) creates the realm/client/users
  and relaxes it.
- **Secrets discipline:** the auto-mode safety classifier (correctly) blocked putting the client
  secret inline on a command line; it now lives in a git-ignored `.env.local` sourced by `run.sh`.
- **Restart auto-SSO gotcha:** after a server restart the browser silently re-logs-in the *last*
  Keycloak user (SSO cookie still valid). This briefly looked like a data leak until the DB showed each
  user's rows were correctly independent — a reminder to check identity, not assume it, when verifying
  multi-user behaviour.
- **Vaadin `GridContextMenu` doesn't open under Playwright's synthetic right-click** (reproduced on
  the known-working headlines menu), so the "Unsubscribe" item is verified by parity with that menu
  rather than by automation — a real right-click works for a user. A genuine limitation of the test
  harness, not the app.

**Per-feed authentication** (parity with RSSOwl's per-bookmark credentials). Feeds can require a
login: each `Subscription` carries optional username/password; the fetcher sends HTTP Basic auth and,
on a 401, raises `AuthenticationRequiredException` (parsing the `WWW-Authenticate` realm) so the UI
prompts "Feed requires authentication". Credentials are set in the Add-feed dialog or a "Set
credentials…" context item; authenticated feeds show a 🔒. Verified against `httpbin.org/basic-auth`:
no credentials → 401 → the auth-failed prompt; correct credentials → the request is accepted (the
header works; httpbin's JSON then fails RSS parsing, as expected).

**Per-user isolation of authenticated feeds — a security fix caught in review** ("No way is it
acceptable to share feed credentials between users"). The first cut fetched auth-gated feeds in the
*shared* background refresh using one subscriber's stored credentials, into the shared article pool —
leaking both the credentials (used on others' behalf) and the private content they unlock. Corrected:
the shared refresh now fetches every feed **anonymously** (never with stored credentials), so
auth-gated feeds 401 there and are skipped; `Article` gained an `owner` (null = public/shared, else
the Keycloak subject) with `(feed, link, owner)` uniqueness; authenticated feeds are fetched **per
user with their own credentials** and stored private to that subject; a user's headlines are public
articles **plus only their own** private ones. Verified end-to-end with a local Basic-auth RSS server:
alice (with credentials) sees the feed's items, stored `owner = alice` in the DB; bob — same URL,
*no* credentials — sees the feed with **0** articles and **none** of alice's content. (Credentials are
still stored in plain columns in the PoC; a real deployment would encrypt them / use a secret store.)

## Honest findings so far

_(This section is the point of the experiment and grows as we go.)_

- **The AI reproduces *structure* but quietly under-delivers *completeness*; human review is the
  safety net.** Across this experiment a knowledgeable human repeatedly caught gaps the model didn't
  surface on its own: the stale Signals API, the over-claim about the dead toolkit, an invented feed
  taxonomy (a made-up "World" category, missing "Weblogs"), and **fewer channels than the original**
  (10 of 15 folders, no ungrouped channels, no saved-search smart folders). Each was a plausible-
  looking approximation. The migration is very doable *with* a reviewer who knows the source; without
  one it would ship something that looks right and isn't.
- **Running a *prebuilt* stranded SWT/RCP app on a current Mac is a project.** The official
  2.2.1 binary is unrunnable (32-bit Carbon); even the maintained fork's release needs an
  x86_64 JVM, an exec-bit fix, an ad-hoc re-sign, and Rosetta — which Apple is phasing out.
- **But the toolkit is not the problem — distribution is.** Built from source against current
  Eclipse (4.30), the fork produces a **native arm64** app that runs without Rosetta, in
  ~2.5 min, by changing one Tycho `<environment>` arch. So "SWT can't run on Apple Silicon" is
  false. The durable argument for moving to the web is **reach and zero-install distribution**,
  not a dead toolkit — and overstating the toolkit's death would have been a credibility-losing
  mistake we caught by actually building it.
- **The migration target is well-defined and credible** — a genuine master-detail with a
  sortable table, not a toy.
- **The job is "migrate an RCP app," not "migrate SWT widgets."** RSSOwl is full Eclipse RCP
  (167 OSGi bundles, JFace viewers, Workbench advisors, declarative menus/commands, its own
  extension points). The widget toolkit is the easy bottom layer; the workbench/JFace/command/
  OSGi model is the real surface area — and the part with no direct Vaadin equivalent.
- **The AI's Vaadin knowledge is a stale cache — the MCP server is not optional.** The model
  proposed an outdated listener-based design for master→detail; Vaadin 25.1 (released after the
  model's training cutoff) does this with **Signals**. Only a human catching it, plus a query to
  Vaadin's MCP server, surfaced the modern approach. Required step, not afterthought.
- **Plan-before-code paid off on a dense slice.** Switching to plan mode forced the no-clean-
  equivalent parts (owner-draw selection paint, auto-mark-read timing, lost `MB_ADDITIONS`
  contributions) to surface during design rather than mid-build. The visible widget is ~2k lines
  but its behavior is dense — estimated ~13–18 person-days for this one sub-slice.
- **A runnable POC confirms the core question: yes, the SWT table moves to a Vaadin Grid.**
  Sorting, selection→detail (via Signals), a dynamic context menu, and most rendering came across
  cleanly and quickly. Where it "breaks" is narrow and specific: per-row **owner-draw backgrounds**
  fight the Aura theme (the documented CSS property is ignored; needs `background-color !important`),
  `nullsLast` sort semantics flip under descending order, and the densest behaviours (grouping,
  multi-select, auto-mark-read timing, column persistence, extension-point menu contributions) are
  the real work — see "Building & verifying the POC".
- **AI + MCP is productive but not autopilot.** Two stale-API compile errors and one wrong
  (doc-sanctioned) styling approach were caught by the compiler and by *running and inspecting the
  app* — not by the model. The MCP server made the design current; the running app made the
  findings true.

---

## What's next

- [x] Clone the source (`Xyrio/RSSOwlnix` at `~/git/RSSOwlnix`), build it, and settle the
      x86_64 question — done: native arm64 builds and runs from source (see above).
- [x] Locate the slice's SWT/JFace classes. Master tree:
      `org.rssowl.ui.internal.views.explorer.BookMarkExplorer` (+ `BookMarkViewer`,
      `BookMarkContentProvider`, `BookMarkLabelProvider`, `BookMarkSorter`, `BookMarkFilter`).
      Detail list + reader: `…editors.feed.FeedView` hosting `NewsTableControl`/`NewsTableViewer`
      (+ `NewsColumn`, `NewsColumnViewModel`, `NewsTableLabelProvider`) and
      `NewsBrowserControl`/`NewsBrowserViewer`.
- [x] Read those classes and map each SWT/JFace construct to its Vaadin 25.1 equivalent (MCP-
      verified); note where the mapping is lossy — see "Designing the headline-table migration".
- [x] Build the Vaadin POC of the slice so it actually runs (`poc/headlines/`, runs at :8080).
- [x] Capture the "after" screenshots (`docs/after/`) and verify behaviour via the Playwright MCP.
- [x] Fill in the honest-findings section with specifics and an effort estimate.
- [x] Deepen: **real RSS feeds** (RSSOwl's defaults via `FeedService`/Rome) + **grouping** via
      `TreeGrid` (None/Date/Status/Author/Category/Feed/Sticky) — done; see "Deepening" above.
- [ ] Stretch (still beyond the timebox): multi-select, auto-mark-read timer, column persistence,
      direction-aware null sorting; then re-measure effort.
