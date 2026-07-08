<!--
 Copyright (c) 2026 Vaadin Ltd.
 This program and the accompanying materials are made available under the
 terms of the Eclipse Public License v1.0 — SPDX-License-Identifier: EPL-1.0

 DRAFT blog post. First-person, developer-audience narrative.
-->

# I quit the algorithm for RSS. The classic reader won't run in 2026 — so I rebuilt it in a browser tab.

*I came to RSS about twenty years late. When I finally went looking for a good reader, one beloved
old Eclipse desktop app kept turning up on every "best of" list — [RSSOwl](https://github.com/rssowl/RSSOwl) —
and it wouldn't even launch on my Mac. I'm a developer, so instead of shrugging I got nosy: how
hard would it actually be to rebuild its main screen for the browser? Here's what I found — what
came across in an afternoon, what fought me, and what turned out to be impossible.*

---

Quitting the algorithm sounds grand; for me it started with something dumber — newsletters. I
follow a fair amount of developer stuff (the Vaadin blog, the Spring blog, a couple of Java ones,
Hacker News), and half of it only really arrives as email now. My inbox had turned into an
unread-content graveyard. RSS promised to pull all of it into one place, newest-first, without
handing anyone my email address — and let me actually get through it.

So I went looking for something to *read* it in. One name kept turning up, on nearly every "best
RSS reader" list and in half the old forum threads: RSSOwl, a gorgeous Eclipse desktop reader with
a classic three-pane layout — a tree of feeds on the left, a sortable table of headlines
top-right, an article reader below. The lists tended to add the same asterisk — *needs Java, looks
dated* — which I ignored. I downloaded it. Nothing happened. It simply won't launch on a 2026 Mac.

## The dead binary (and the twist)

RSSOwl's official Mac build **won't launch on a current Mac at all.** It's an ancient 32-bit binary
linked against Carbon, a UI layer Apple deleted years ago. Rosetta can't rescue it — that only
translates 64-bit Intel code. The flagship download of the app simply cannot run on a 2026 machine.
That's what "stranded on a dying desktop toolkit" actually feels like.

[RSSOwlnix](https://github.com/Xyrio/RSSOwlnix), the maintained community fork, then came along —
distributed on GitHub, building a 64-bit Intel binary. That one *does* launch on a current Mac, but
it runs under Rosetta, which is itself on the way out.

So I built it myself **from source** — and in a little over two minutes it produced a **native
Apple Silicon** app that runs with no Rosetta at all, because modern SWT ships a current `aarch64`
build. It took changing *one line* of build config (a Tycho target environment from `x86_64` to
`aarch64`) to get it.

> "You can't run SWT on Apple Silicon" is just false. The toolkit isn't the problem —
> *distribution* is.

That distinction stuck with me, and it's a good reason to move something like this to the web. It's
not that the desktop toolkit can't keep up — it can. It's that the only build a normal person can
actually get is a crumbling 32-bit relic (or an Intel one riding Rosetta, itself on the way out),
and producing a modern native build takes source access, a whole Maven/Tycho toolchain, the right
JDK, and knowing exactly which knob to turn. Nobody downloading a feed reader is going to do that.
A web app, though, you just… open.

So, as a web developer, I couldn't help but ask myself:

> **Could I take that exact screen — a feeds tree, a sortable headlines table with a right-click
> menu, and an article reader — and have an AI rebuild it in the browser? Maybe even make it
> multi-user, so friends could keep their own feed lists? And where would it fall apart?**

I decided to find out, leaning on Claude Code to do the typing and keeping an honest tally of what
worked and what didn't. I targeted Vaadin 25 (Java, server-side UI — no JavaScript rewrite, which
was the whole point). The short version: the screen moves, and most of it moves surprisingly fast.
But "where does it fall apart" has real answers — and the most interesting one has nothing to do
with widgets.

## What came across cleanly — and fast

The heart of RSSOwl is that headlines table. It looks like a plain table, but it's secretly a tree
(so it can group rows), with sortable columns, bold unread rows, custom row colours, clickable
in-cell icons, and a right-click menu. That's the part I doubted would move easily.

![The original RSSOwl: three-pane master-detail on the desktop](before/rssowlnix-masterdetail.png)

Most of it came over quickly:

- **Selecting a headline updating the reader "just worked" — via Signals.** This is where the AI
  first tried to sell me something stale: it wired the master→detail link with old-style change
  listeners. Vaadin 25 has a better answer — **Signals**. The selected headline lives in a
  `ValueSignal<NewsItem>`, and the reader binds to it reactively (`Signal.effect(...)`); there's no
  manual "when the selection changes, go update the reader" plumbing. Cleaner than the desktop
  original, honestly — but I only found it because I *checked*, which is a theme below.
- **Sorting was almost free.** Click-to-sort headers and the little sort arrow are built into the
  `Grid`; you just hand each column a `Comparator`.
- **The right-click menu ported faithfully.** A `GridContextMenu` rebuilt on each open reproduced
  "Mark read" flipping to "Mark unread," and correctly shows nothing when you right-click a group
  header.

And then the thing that genuinely surprised me: how much code just *evaporated*. Counting real
lines (comments excluded):

> RSSOwl's **306-line** `NewsComparator` became about **10 lines** of `Grid` column comparators.
> Its **467-line** `NewsTableLabelProvider` — bold unread, highlighted "sticky" rows, label
> colours — became about **10 lines of CSS**.

All that imperative desktop ceremony — comparators, content providers, label providers, hand-drawn
row painting — collapses into a bit of declarative `Grid` config and some CSS. (To be fair, some of
the shrink is because my version does a little less than the original in the edge cases. But even
accounting for that, the difference is enormous.)

![The same screen, rebuilt for the browser, running in a tab](after/threepane-layout.png)

## Where the AI let me down

This is the part most write-ups skip, and it's the part I'd most want to read. The common thread:
none of these came from what the AI *knew* — they surfaced from the compiler, from *running the
app* (sometimes the AI itself caught them, by driving the app with Playwright), or from me
comparing against how the original behaved.

- **It confidently wrote APIs that don't exist — because it *had* to.** Here's the mechanism, and
  it's the single most important thing to internalize before you let an AI touch a modern framework:
  the model's training data has a cutoff, and Vaadin 25 shipped *after* it. So the model has never
  seen the current API. Ask it anyway and it doesn't say "I don't know" — it pattern-matches on the
  older Vaadin it *did* train on and hands you confident, plausible, wrong code (deprecated
  constructors, methods that moved, the pre-Signals listener style). It looks right and won't
  compile.

  > Without live docs, the AI answers your Vaadin 25 questions from pre-25 (Vaadin 24-era) training
  > data — fluently, and wrong. **This is why you need the MCP server.**

  The fix is that **Vaadin ships an MCP server that feeds its *current* docs straight to the AI.**
  Treat the model's built-in framework knowledge as a stale cache and make querying the MCP server
  for the real 25.x API a required step, not an afterthought — that one habit is what surfaced
  Signals (above), the current security config, and the modern `Upload`/`Download` handlers instead
  of fiction. Even *with* the right API, there are sharp edges the AI face-planted on: a `Grid`
  **row double-click** event hands you the item directly, but the **context-menu** event hands you
  an `Optional<T>` — the same "get the clicked row," written two different ways.
- **A styling trick straight from the docs silently did nothing.** To colour a row's background,
  the docs say set the `--vaadin-grid-cell-background` custom property. I did; it *was* applied to
  the cell; the background stayed stubbornly white. Under the current **Aura** theme the cell
  background is painted independently, so the property is ignored — the only thing that worked was
  `background-color: … !important` on the cell's `::part(...)`. I only figured that out by
  inspecting the live shadow DOM. A one-line fix — and a reminder
  that even doc-sanctioned advice needs to be checked against the running app.
  This would probably have taken an hour to find out — luckily, the AI caught it using Playwright.
- **A Signals gotcha you can't guess.** Reading the selection from a background timer with
  `signal.get()` throws — `get()` sets up reactive dependency tracking and is illegal outside an
  effect. From a plain callback you must use `signal.peek()`. Obvious in hindsight, invisible
  beforehand. Again, the AI caught it using Playwright.
- **A sorting subtlety it got quietly wrong.** RSSOwl always sorts undated items to the bottom,
  either direction. Vaadin gives a column one comparator and *reverses* it for a descending sort, so
  my `nullsLast` flipped to `nullsFirst` and empty-date rows leapt to the top of the default
  newest-first view. Wrong in a way you'd only notice if you knew the original.

But the biggest lesson was bigger than any single bug. Left on its own, the AI produced things that
*looked* right and weren't. It invented a set of feed categories that didn't match RSSOwl's real
ones. It quietly shipped fewer feeds than the original and never mentioned it. It made up an
arbitrary limit for how many articles to keep — until I pointed it at RSSOwl's actual source, where
the real default (200 per feed) was sitting in plain sight. Every one of those looked completely
plausible.

> The AI got me a faithful *skeleton* in hours. Turning it into a faithful *app* took someone who
> would compare to the original's behaviour — which, this time, was me.

That's the one thing I'd tell anyone trying this. The tooling reproduces *structure* astonishingly
fast, and under-delivers *completeness* just as reliably. It nails the broad 80% in an afternoon;
the last 20% — the fidelity that makes it genuinely the same app and not a convincing lookalike —
needs someone who remembers what the original actually did (or who would figure it out).
Not as a nice-to-have. As the thing that decides whether the result is correct.

## What I just couldn't do

A few things didn't fight me so much as slam a door. Plainly:

- **Eclipse's pluggable menus have no web equivalent.** In RCP, *other plugins* can inject items
  into a menu through Eclipse's extension registry. That's an architecture, not a widget, and
  there's simply nothing on the web to map it onto. If an app leans on that kind of extensibility,
  that's a redesign, not a port.
- **A real embedded browser is blocked by the web itself.** RSSOwl's reader embeds an actual
  browser showing the live page. Drop a news site into an `<iframe>` today and it renders blank —
  sites forbid it with `X-Frame-Options` / CSP headers. I render the feed's own article HTML inline
  instead — cleaned through a jsoup allow-list first, because Vaadin's `Html` component doesn't
  sanitize (a documented footgun). Close in spirit, not the same thing.
- **Some features are just gone.** RSSOwl syncs with Google Reader — a service Google shut down in
  2013. You can't move a feature whose other half no longer exists.
- **Custom row colour *plus* the selection highlight** is the awkward rendering case — an arbitrary
  per-row colour and the selected-row state don't compose cleanly through CSS parts, so I settled
  for a compromise rather than a perfect match.

None of these are really the web framework's fault. They're the honest edges of dragging a
20-year-old desktop app onto today's platform. Knowing where those edges are before you start is
worth more than any feature list.

## The nice surprise: a decade of fixes, for free

One story cuts the other way, and it's my favourite. Remember the Vaadin blog — one of the feeds I
wanted from the start? I tried adding it (`vaadin.com/blog/feed`) to both apps. The *original*
RSSOwl fumbles it: it can't even confidently decide the URL *is* a feed, because its 2009-era logic
identifies feeds by an HTTP `Content-Type` header and a file extension that this modern, CDN-hosted
endpoint just doesn't provide. Perfectly reasonable assumptions back then; broken on today's web.

My browser version doesn't even notice the problem — not because I was clever, but because the
modern library it uses (ROME) figures out a feed from its actual *content*, sniffing the XML from
the bytes rather than trusting HTTP labels. Just by using a current stack, the rebuild inherited a
decade of the ecosystem quietly getting more robust. That's an underrated part of modernizing: you
don't only move the app, you leave a pile of accumulated brittleness behind.

And a bonus I didn't plan for. I only set out to prove the *main screen* could move. But every time
I checked "could this part move too?" — logins, full-text search, filters, labels, keyboard
shortcuts — it turned into a working feature, and I ended up with the multi-user feed reader I'd
half-jokingly asked for, one I'd actually use. That the real thing kind of *fell out* of just
poking at it says a lot. (With the caveats above firmly attached: I still had to catch every
completeness gap myself, and one polished screen is not the same as porting a whole application
shell.)

## So how much work was it?

- That dense headlines table alone — sorting, custom rendering, clickable cells, the dynamic menu,
  multi-select, the timing behaviours — was a solid **couple of weeks** of focused evenings with the
  AI helping. It's only ~2,000 lines of code, but the *behaviour* is dense. That ratio — small
  widget, heavy behaviour — is the thing to remember if you're estimating your own.
- The whole three-pane screen, done faithfully, was realistic in that same couple-of-weeks window —
  with the AI, the Vaadin MCP server answering API questions, and me watching for the things it got
  subtly wrong.
- A **whole** RCP application would be a different beast — and most of what's left isn't widgets at
  all. The workbench, the perspectives, the command framework, the plugin system: *that's* the real
  cost, and the part with no clean equivalent. Redrawing screens is the fast, cheap part.

If I distilled the method to a few rules: **don't trust the AI's memory of the framework — point it
at the vendor's live docs (for Vaadin, the MCP server) first**; **plan the gnarly parts before
writing code**, so the "there's no equivalent for this" cases surface early; **verify by running
the app, not by reading the diff** (the CSS bug, the Signals throw, and the sort flip were all
invisible on paper); and **stay in the loop yourself on anything where fidelity to the original
matters** — the AI won't.

## Want to poke at it?

It's all up on GitHub, and the fun part is you can run it in about a minute:

```sh
git clone <this-repo>
cd swt-rcp-to-vaadin-modernization/poc/headlines
./mvnw spring-boot:run        # needs a JDK 21+; the first run pulls the frontend toolchain
```

Then open <http://localhost:8080>.

If you've got your own stranded desktop app, the prompts I used are in the repo too — the part
that's genuinely reusable: build your app from source first to prove it still runs, map its tables
and trees to `Grid`/`TreeGrid` the same way, query the framework's live docs (the MCP server)
instead of trusting the AI's memory, and verify everything by actually running it. They're not a
magic recipe, but take them as inspiration for your own run at it.

I went in expecting to prove it *couldn't* really be done. I came out with the old screen running
in a browser tab — no install, no Rosetta, no 32-bit ghosts — a much clearer sense of exactly where
the hard parts are, and, fittingly, a feed reader I'll actually use. Late adopter, indeed.
