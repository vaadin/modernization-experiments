# SWT/RCP → Vaadin Modernization Experiment

> An honest experiment, not a demo. We pick a real slice of an SWT/RCP application,
> migrate it to Vaadin with AI assistance, ship runnable code, and publish what
> actually happened — including the parts that didn't work.

This repo is one of four comparable migration experiments. The shape of every
experiment is standardized so the results can be read side by side.

## What's being migrated

**SWT / Eclipse RCP** — desktop Java, in the same family as Swing. The trigger is
familiar: a dying desktop UI toolkit and a team that wants to stay in Java rather than
rewrite into a JavaScript stack. That makes this a strong net-new wedge — the audience
isn't already on Vaadin, so the upside of a credible migration path is high.

## The question

> **Can _\<the chosen SWT/RCP slice\>_ be moved to Vaadin, and where does it break?**

Write this from the developer's side, not Vaadin's. Not "Vaadin supports SWT" but,
for example: _"Can an SWT table with sorting and a context menu be moved to a Vaadin
Grid, and where does it break?"_ This sentence is the headline of the write-up — get
it right on day one.

## Ground rules

- **Two weeks, hard stop.** If the migration isn't done in the timebox, the honest
  finding is "this is how far we got and why." That is still a valid, publishable
  result. Do not extend.
- **A representative slice, not a toy and not the whole app.** Something complex
  enough to be credible — e.g. a master-detail screen: a data table with sorting and
  a context menu, a form with validation, some custom rendering. A button that
  becomes a Vaadin button proves nothing; a real grid with interaction proves the
  thing people doubt.
- **Use AI assistance and document it.** Using AI to derive the migration path and
  generate code is encouraged — the prompts and skills used are part of the
  deliverable, because "can AI do this?" is one of the questions being tested.

## The five-step assignment

1. **Frame the question.** One sentence from the developer's side (see above).
2. **Research the migration path.** Understand the source technology's patterns and
   the realistic mapping to Vaadin. AI recommended.
3. **Build the POC.** Migrate the chosen slice so it actually runs. Don't polish it
   into a fake-perfect demo — leave the rough edges visible and documented.
4. **Publish the code** here (Vaadin org). A clean, runnable repo with a README that
   lets a developer reproduce it on their own code. Include the AI prompts / skills
   used.
5. **Write down what happened.** A plain account of the steps, what the tooling did
   well, what it got wrong, what needed hand-fixing, and what is genuinely hard or
   impossible. This is the most important section.

## Honest findings (the part that matters most)

Every vendor publishes the success path; almost none publish "here's what you'll
still have to do yourself." That honesty is what the developer audience rewards and
what an architect needs before committing. Naming the limitations clearly _increases_
trust and conversion. Cover, briefly and specifically:

- What the AI / tooling handled automatically and well.
- What it got wrong or only half-right, and what manual fixing was needed.
- What is genuinely hard, awkward, or not yet possible — stated plainly, no spin.
- An honest estimate of effort for a real, full-size app of this kind.

_(Filled in as the experiment progresses.)_

## Deliverables

- [ ] Runnable code in this repo with a reproducible README.
- [ ] The AI prompts / skills / instructions used, included in the repo.
- [ ] A write-up draft (the story + the honest findings) for the curator and marketing.
- [ ] One clear call-to-action defined with marketing — ideally "run this on your own
      codebase" (repo + prompts) rather than "contact sales."

## What makes an experiment fail its purpose

- It's polished into a flawless demo — reads as marketing, loses the credibility that
  is the whole point.
- The migrated slice is trivial — proves nothing a skeptic would care about.
- There's no runnable code — the claim can't be verified, so it carries no weight.
- It runs over two weeks chasing completeness — the timebox and an honest "here's how
  far we got" beat a perfect result delivered late.

## Running it

_TBD — once the POC exists, this section must let a developer clone, build, and run it
from scratch._
