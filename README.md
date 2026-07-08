# Modernization Experiments

A collection of honest, end-to-end experiments in modernizing legacy Java desktop
UIs to the web with [Vaadin](https://vaadin.com) — keeping the logic in Java while
replacing a dying or dated UI stack.

Each experiment takes a real application, migrates a meaningful slice (or more), and
keeps a candid record of what worked, what didn't, and how far the approach scales.
Every subdirectory is a self-contained project with its own README, build, and report.

## Experiments

| Experiment | From → To | Source app | Status |
|---|---|---|---|
| [`swt-rcp-to-vaadin`](./swt-rcp-to-vaadin) | Eclipse SWT/RCP → Vaadin Flow | RSSOwl (RSS reader) | ✅ Complete |
| _experiment 2_ | _TBD_ | _TBD_ | 🔜 Planned |
| _experiment 3_ | _TBD_ | _TBD_ | 🔜 Planned |
| _experiment 4_ | _TBD_ | _TBD_ | 🔜 Planned |
| _experiment 5_ | _TBD_ | _TBD_ | 🔜 Planned |

## How to read these

Each experiment folder contains:

- a **README** describing the source app, the migrated slice, and how to run it,
- a **report** capturing the findings — the honest account of the migration, and
- the **full commit history** of the work, preserved as it was built day by day.

## Layout

Experiments live side by side as subdirectories so they can be browsed and compared
at a glance. History for each was brought in with `git subtree`, so every experiment's
original commit history is preserved under its path.
