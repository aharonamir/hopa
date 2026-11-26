# Design

Scope: the **operations console** (`/console`, server-rendered HTML + one
stylesheet). The Android and Windows apps have their own systems and are not
governed by this file. Product truth lives in [PRODUCT.md](PRODUCT.md).

## World

An instrument panel, not a marketing page. Desaturated blue-grey paper, one deep
green that only marks "alive" and "primary", and monospace for anything you
would copy — device ids, household ids, task ids, timestamps. The console is
read at speed by someone with a support ticket open in another window, so
hierarchy does the work and decoration does none.

Mode: **Operate**. Ambition shows as precision and restraint.

Reference: the palette is pinned to Octet Design Labs' "Client-focused
dashboards" set. Type, layout and components are ours.

## Palette

Every colour in the console is one of these tokens. No ad-hoc hex values in
views.

| Token | Value | Use |
|---|---|---|
| `--paper` | `#fdfdfe` | page background |
| `--surface` | `#f0f3f5` | cards, table head, rail |
| `--line` | `#d6d9df` | borders, dividers, table rules |
| `--muted` | `#bdc2c7` | offline dot, disabled, empty-state rules |
| `--accent` | `#3d766d` | online, primary button, focus ring, active nav |
| `--accent-ink` | `#2c5750` | accent text on light (hover, links) |
| `--ink` | `#1f2422` | body text (derived) |
| `--ink-2` | `#5b615e` | secondary text, labels (derived) |
| `--warn` | `#8a6320` | queued (derived) |
| `--danger` | `#8f3a30` | failed, danger zone (derived) |

Rules:

- `#8f9192` from the source palette is ≈2.9:1 on `--paper`. It is **never text**:
  meta rules, disabled glyphs, separators only.
- Body and secondary text clear 4.5:1 (`--ink`, `--ink-2`).
- Status never relies on colour: dot **and** word.
- Dark mode is not in scope. One surface, one lighting.

## Mark

The console's icon is `tools/assets/hopa-backend-workder.jpeg` — a teal plush
mascot in a hi-vis vest and hard hat on a near-paper ground, 1024 × 1024 JPEG,
no alpha. The filename's `workder` is as given; it is not renamed.

Measured on the delivered file: corners `#fdfbfc`–`#fefefe` (≈ `--paper`), zero
warm pixels, dominant tones `#407060` (Δ15 from `--accent`) and `#306050` (Δ10
from `--accent-ink`), panels `#b0c0c0` (Δ15 from `--muted`). It sits in the
palette's own green family.

- One place, one size: a 40px round crop in the rail header (28px in the narrow
  layout) and the tab icon. No page illustrations, no backgrounds, no empty-state
  art.
- Crop at `x=130, y=110, side=770` (12.7% inset) — the full figure with the
  raised paw. Tested at 40px: the full frame leaves the figure at ~24px, and a
  bust crop loses the tool belt, which is the second-strongest read after the
  hat.
- Masked, not knocked out: the ground is part of the image and there is no alpha.
- A 1px `--line` ring around the rail avatar, because its near-paper ground would
  otherwise sit as a faint lighter disc on the `--surface` rail.
- Never recoloured, redrawn or stretched. Do not sample or extend its tones into
  the UI.
- Small derivatives only (`mark-80.png`, `favicon-32.png`), produced once from
  the source; the 381 KB original is never served to a browser.

## Type

- Text: `"Segoe UI Variable Text", "Segoe UI", system-ui, sans-serif`.
- Ids, timestamps, task ids, counts: `"Cascadia Mono", ui-monospace, monospace`
  with `font-variant-numeric: tabular-nums`.
- Scale: 12 (meta) / 13 (table, labels) / 15 (body) / 20 (section) / 28 (page
  title). Line height 1.45 for prose, 1.2 for titles.
- Weights: 400 body, 600 titles and table headers. No 700+.
- Prose max width 68ch. Table cells never wrap an id; they ellipsize with a
  `title` attribute.

## Space and shape

- 4px base scale: 4, 8, 12, 16, 24, 32, 48.
- Radius: 8px cards, 6px inputs and buttons, 999px status pills.
- Borders are 1px `--line`. Shadows: none on cards; a single 1px border carries
  the separation. The only shadow in the system is the sticky top bar's hairline.
- Table rows 44px, so a mis-click has somewhere to land.

## Layout

```
┌ topbar ──────────────────────────────────────
│ page title       version · db · admin · sign out
│
├────────────┬─────────────────────────────────
│ Overview   │ content, max 1180px, 32px gutters
│ Households │
│ Devices    │
└────────────┴─────────────────────────────────
```

- Rail 208px, `--surface`, sticky. Active item: `--accent` text plus a 2px inset
  marker on the left; hover is a 4% `--ink` wash.
- Under 900px the rail becomes a horizontal strip above the content; tables
  scroll horizontally rather than reflowing into cards.
- Page content is a single column. Cards sit in a 12-column grid only for the
  Overview stat row (4 × 3 columns) and the household panes (8 + 4).

## Components

| Component | Spec |
|---|---|
| Stat card | `--surface` fill, 1px `--line`, 16px padding; value 28/600 tabular; label 13 `--ink-2` |
| Table | head on `--surface`, 12 uppercase `--ink-2` with 0.04em tracking; rules `--line`; hover row = 3% `--ink` wash |
| Status pill | dot 8px + word, 12px, pill radius; online `--accent`, offline `--muted` dot with `--ink-2` word, queued `--warn`, failed `--danger` |
| Button primary | `--accent` fill, `--paper` text, 36px min height, hover `--accent-ink` |
| Button secondary | transparent, 1px `--line`, `--ink` text |
| Button danger | `--danger` text, transparent fill, 1px `--danger` border; armed state fills |
| Field | 36px, 1px `--line`, 6px radius, `--paper`; focus: 2px `--accent` ring at 40% and a solid 1px border |
| Danger zone | `--paper` card, 1px `--danger` at 40%, title in `--danger`, typed confirmation that echoes the masked tail |
| Empty state | one sentence in `--ink-2`, plus the next action as a link; never an illustration |

## Motion

Almost none. 120ms ease-out for hover and focus, 0 for everything else. No
skeletons, no spinners: the server answers in one round trip and the status page
refreshes itself while a task is open (`meta refresh`, 3s, non-terminal only).

## Accessibility and content

- WCAG 2.1 AA as a craft floor (no product requirement is on record).
- Every interactive element is reachable and visible in focus order; focus rings
  are never removed.
- Status text is spelled out, not implied by colour or an icon.
- Forms: real `<label for>`, `autocomplete` where it applies, an error message in
  prose that says what to do next.
- Copy: sentence case, no exclamation marks, no "Oops". Numbers and ids keep
  monospace. A destructive action names what disappears and what survives.

## Implementation

- One stylesheet at `/console/assets/app.css`, served by the backend from
  `services/backend/src/main/resources/console/`. No framework, no build step,
  no JavaScript, no webfont.
- `ConsoleViews.layout(active, title)` owns the rail, top bar and footer; pages
  contribute the content only.
- Assets come from one allowlisted route (`/console/assets/{name}` → `app.css`,
  `mark-80.png`, `favicon-32.png`), never a path join.
- Every value is emitted as a text node or an attribute — never `unsafe`.

## Bans

- Gradients, glass, glows, drop shadows on cards.
- Colour-only status, icon-only buttons without a label or `aria-label`.
- Invented metrics on the Overview (the device inventory and registry are the
  only sources).
- Rendering a task body, a turn or a chat anywhere in the console.
- A second stylesheet, a CSS reset library, or inline `style=` on a view.
- Recolouring, stretching or animating the mark; using it as an illustration,
  a background, or a colour source.
