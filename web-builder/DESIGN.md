---
name: Personal OAL builder — Web
description: The implemented dark/mint browser companion to the OAL desktop builder.
colors:
  background: "#101213"
  panel: "#191c1d"
  text: "#f3f5f4"
  muted: "#aeb7b4"
  accent: "#99d8b6"
  accent-hover: "#b1e5ca"
  accent-text: "#101c15"
  link: "#b7e8d2"
  white: "#fff"
  current-step: "#1b2b24"
  divider: "#2d3232"
  line: "#626e68"
  control: "#222827"
  control-border: "#3a4340"
  control-hover: "#2b3430"
  control-hover-border: "#66776e"
  disabled: "#1a1e1c"
  disabled-text: "#9ba49f"
  primary-disabled: "#2d4437"
  primary-disabled-text: "#aec1b6"
  input: "#111415"
  error-text: "#f0b9a8"
  error-background: "#2c211e"
  error-border: "#715047"
  notice-text: "#e2cfaa"
  notice-background: "#2a261e"
  notice-border: "#655940"
  selection: "#32694f"
  scrollbar: "#414b46"
typography:
  body:
    fontFamily: 'system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif'
    fontSize: "15px"
  paragraph:
    fontSize: "15px"
    lineHeight: 1.6
  headline:
    fontSize: "28px"
    fontWeight: 650
    lineHeight: 1.25
    letterSpacing: "-.025em"
  title:
    fontSize: "21px"
    fontWeight: 650
    lineHeight: 1.35
  brand:
    fontSize: "17px"
    fontWeight: 650
  label:
    fontSize: "14px"
    fontWeight: 550
  control:
    fontSize: "15px"
    fontWeight: 550
    lineHeight: 1.4
  help:
    fontSize: "13px"
    lineHeight: 1.5
  identifier:
    fontFamily: 'ui-monospace, "Cascadia Code", Consolas, monospace'
    fontSize: "13px"
rounded:
  file-button: "4px"
  control: "6px"
  panel: "10px"
spacing:
  gap-6: "6px"
  gap-8: "8px"
  gap-10: "10px"
  gap-12: "12px"
  gap-14: "14px"
  gap-16: "16px"
  gap-18: "18px"
  gap-20: "20px"
  gap-24: "24px"
  gap-28: "28px"
  gutter: "32px"
components:
  button-primary:
    backgroundColor: "{colors.accent}"
    textColor: "{colors.accent-text}"
    typography: "{typography.control}"
    rounded: "{rounded.control}"
    padding: "11px 16px"
  button-primary-hover:
    backgroundColor: "{colors.accent-hover}"
  button-primary-disabled:
    backgroundColor: "{colors.primary-disabled}"
    textColor: "{colors.primary-disabled-text}"
  button-secondary:
    backgroundColor: "{colors.control}"
    textColor: "{colors.text}"
    typography: "{typography.control}"
    rounded: "{rounded.control}"
    padding: "11px 16px"
  button-secondary-hover:
    backgroundColor: "{colors.control-hover}"
  button-secondary-disabled:
    backgroundColor: "{colors.disabled}"
    textColor: "{colors.disabled-text}"
  button-session:
    backgroundColor: "transparent"
    textColor: "{colors.text}"
    rounded: "{rounded.control}"
    padding: "11px 16px"
  input:
    backgroundColor: "{colors.input}"
    textColor: "{colors.text}"
    typography: "{typography.body}"
    rounded: "{rounded.control}"
    padding: "11px 12px"
    width: "100%"
  step-current:
    backgroundColor: "{colors.current-step}"
    textColor: "{colors.link}"
    rounded: "{rounded.control}"
    padding: "12px"
  panel:
    backgroundColor: "{colors.panel}"
    textColor: "{colors.text}"
    rounded: "{rounded.panel}"
    padding: "28px"
  notice:
    backgroundColor: "{colors.notice-background}"
    textColor: "{colors.notice-text}"
    rounded: "{rounded.control}"
    padding: "14px 16px"
  error:
    backgroundColor: "{colors.error-background}"
    textColor: "{colors.error-text}"
    rounded: "{rounded.control}"
    padding: "16px"
---

# Design System: Personal OAL builder — Web

## Overview

The implemented interface inherits the desktop builder’s charcoal background, flat dark panel and mint actions. It is a restrained task interface: a persistent numbered step rail, one active work panel and direct explanatory copy, without marketing imagery or ornamental assets.

**Key Characteristics:**
- Dark tonal separation with fine borders, not shadows.
- System typography, readable labels and explicit status/error text.
- Mint reserved for actions, current progress, links and focus.

This is an extraction from `index.html`, `styles.css` and `app.mjs`, informed by `tests/ui-flow-review.md`; it is not a proposed redesign. The sidecar records extensions and representative implemented components, not invented tonal ramps or future controls.

## Colors

**Primary:** Mint accent fills primary controls and marks focus, checkbox/radio selection and input carets. The lighter link tone is shared by links, status text and the current step; mint is not a guarantee of successful qualification.

**Neutral:** Charcoal background, slightly lighter panel and recessed input surfaces establish hierarchy. Main text is near-white; paragraphs, metadata labels and help use the muted tone. Panel dividers and input/control borders are deliberately different tokens.

**Feedback:** Warm amber notices carry cautions; pale terracotta text on a dark warm error surface carries failures. Both include text, padding and a border rather than relying on hue alone. Primary and secondary disabled controls have separate subdued palettes. Text selection uses dark green with white text; links turn white on hover.

## Typography

Use the inherited system sans stack; there are no downloaded fonts. The page heading and section title use the headline/title roles. Brand text is medium-large; download subheadings are 17px with browser-default heading weight, not the brand’s explicit weight. Paragraphs have a 72ch maximum measure. Help and identifiers are smaller; identifiers use the monospace stack and wrap when needed. Definition-list values and step numbers use tabular numerals. Footer copy is 12px. Do not impose an unimplemented global line-height: paragraph, status, choice and control line-heights are set independently.

## Layout

The centered main container is at most 1100px wide, including 34px vertical/32px horizontal padding. The header is a bordered, horizontally split brand/edition row. The workspace uses a 192px step rail and a flexible work area with a 28px gap; the footer aligns beneath the work area with 220px left padding.

The work panel has 28px padding. Form rows have two equal columns separated by 16px. Actions wrap with a 10px gap; field labels, help and expandable details keep instructions beside their controls. Definition lists use a 125px label column and a wrapping value column. Longer forms scroll normally; no fixed-height wizard or sticky action bar is implemented.

At **760px and below**, the rail becomes a wrapping row, the workspace becomes one column, the rail reminder hides and the desktop-browser notice appears. Main padding becomes 24px 18px, panel padding 22px and footer indentation disappears. At **480px and below**, form columns and mode choices stack, actions fill their row, and definition lists become single-column. These are desktop-task accommodations, not a mobile qualification claim.

## Elevation & Depth

No box shadows, gradients, blur or decorative elevation are defined. Tonal surfaces and one-pixel borders separate the header, panel, feedback and controls. Focus is a two-pixel mint outline offset by four pixels; it is not a shadow. Buttons/download-style controls transition only background and border color for 0.12s ease-out, and only under `prefers-reduced-motion: no-preference`.

## Shapes

Controls, step highlights and feedback have softly rounded corners; the main panel is slightly rounder, and the native file-selector button is tighter. Preserve the extracted radii rather than introducing pill buttons. Buttons and non-choice inputs have a 44px minimum height. Native checkbox/radio controls are 18px square with mint accent and adjacent wrapping labels.

## Components

- **Step rail:** an ordered list with `aria-current="step"` on the active item. It indicates progress; it is not clickable navigation. Active text is brighter and heavier on a green-dark inset surface.
- **Actions:** mint primary buttons, dark bordered secondary buttons, and the smaller transparent “Clear & start over” session action. Hover changes fill/border; disabled states change palette and cursor. Downloads are currently triggered by buttons; the stylesheet also supports download-styled links, but those are not the rendered download UI.
- **Fields and choices:** full-width recessed inputs, visible labels and adjacent help; side-by-side create/update radios; native file pickers and checkboxes. No custom inline input-error border treatment is defined. Validation errors use the shared alert, alongside native required/pattern validation.
- **Feedback:** a persistent polite live status region separated by a bottom rule; a focusable alert shown on failure; amber notices for consequential cautions. Heading focus moves with wizard steps; failures focus the alert. The skip link appears on keyboard focus.
- **Disclosure:** native details/summary for optional CarPlay, separated by a top border. It expands in place, not in a modal.
- **Backup gates:** download-request buttons followed by initially disabled confirmation checkboxes. Downloading enables confirmation; confirmation enables the next consequential action. Preserve this distinction visually and behaviorally.
- **Metadata:** two-column definition lists, monospace application/source identifiers and wrapping values. No dashboard cards, badges, charts or decorative icons are present.

## Do's and Don'ts

- **Do** preserve the inherited charcoal/mint palette and flat bordered surfaces.
- **Do** retain visible labels, keyboard focus, current-step semantics and textual status/error feedback.
- **Do** let long forms and identifiers wrap or scroll without hiding essential instructions.
- **Don't** turn the step indicator into navigation or imply a disabled backup gate is complete.
- **Don't** add decorative imagery, shadow-based elevation or a competing visual identity to this extraction.
- **Don't** place internal review dispositions or raw design-decision metadata in the shipping interface.
