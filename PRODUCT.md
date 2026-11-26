# Product

<!-- impeccable:product-schema 1 -->

## Platform

adaptive

## Users

Internal support and operations. They sign in to the console from a laptop with a
phone number or an id in hand — usually because someone asked for help — and need
to see that household's devices, their presence, and what is queued for them.

## Product Purpose

Say what you want in your own words. HOPA uses your real apps for you — opens
them, taps, reads and answers — then tells you what happened, and asks before
money moves. Success is the person finishing the job without tapping through
menus, and never losing money to a mistake.

## Positioning

It acts through the real apps, on the real screen, instead of a canned
integration per service. A neighbouring assistant can copy the chat; it cannot
truthfully copy the acting, or the gate in front of it.

## Operating Context

The household spans an Android phone, a Windows PC and (prototype stage) a
HarmonyOS phone; devices join by scanning a QR that the PC shows. The console is
a browser surface over the same backend: households, devices, presence, queue
depth, and admin actions. Support never has the person's screen in front of
them, so every conclusion has to come from a row on the page.

## Capabilities and Constraints

- **The server never sees household content.** Task bodies are sealed with the
  household content key; the backend stores only per-device wraps. Confirmed
  product truth: every future change preserves it.
- Repository rules (`CLAUDE.md`), binding regardless of this record: every device
  action goes through `ActionExecutor` (`guards → payment approval → execute →
  verify → record`); payment-class actions always require explicit confirmation;
  no flag, environment variable or trusted mode may bypass it.
- The console renders households, devices, presence and statuses. It does not
  render a task body, a turn or a chat.
- Unknown number, unknown device, revoked device and a cross-household id all
  answer the same refusal, so support cannot enumerate accounts.
- Working prototype: HarmonyOS restart-after-force-stop, local app control and
  Chinese are unfinished (`docs/project/status.md`).
- Undecided: a household-facing console. Not planned; the shape stays open.

## Brand Commitments

- The person's words are the interface; copy stays plain and short.
- Console palette is pinned to the Octet Design Labs "Client-focused dashboards"
  set: `#fdfdfe #f0f3f5 #d6d9df #bdc2c7 #8f9192 #3d766d`.
- Console icon is `tools/assets/hopa-backend-workder.jpeg` (the filename's
  `workder` is as given), used masked at one size and never recoloured.

## Evidence on Hand

`docs/project/evidence/` (live runs with ids and timestamps),
`docs/media/HOPA-demo-2026.mp4`, `docs/project/status.md`. No customers,
testimonials, benchmarks or deployment claims exist — none may be invented.

## Product Principles

1. Act, then report. A chat box that only answers is a different product.
2. Irreversible actions stop for a human.
3. The content is theirs; the server cannot read it.
4. One household, many devices; each device is a first-class participant.
5. Support fixes the account, never reads the conversation.

## Accessibility & Inclusion

No product-specific standard is established (confirmed 2026-11-26). The console
is still built to WCAG 2.1 AA as a craft floor, without claiming it as a product
requirement.
