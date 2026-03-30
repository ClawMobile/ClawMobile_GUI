---
name: frontend-design
description: Create distinctive, production-grade frontend interfaces for websites, landing pages, dashboards, React components, HTML/CSS layouts, posters, UI redesigns, and any request to style, beautify, or visually upgrade a web interface. Use when Codex needs to design or implement polished frontend code with a clear aesthetic direction, strong typography, deliberate motion, and non-generic visual taste.
---

# Frontend Design

## Overview

Build real, shippable frontend code with a strong point of view. Pick an intentional visual direction, execute it consistently, and avoid generic AI-looking layouts, colors, and typography.

## Workflow

1. Define the job.
   Clarify the interface type, audience, constraints, and whether the work is a net-new build or an upgrade of an existing UI.

2. Commit to one memorable direction before coding.
   Pick a clear aesthetic such as editorial, brutalist, toy-like, luxury, retro-futurist, industrial, organic, or ultra-minimal.
   State the one thing a user should remember about the interface.

3. Decide the visual system.
   Lock typography, palette, spacing, shape language, motion style, and background treatment before filling in components.

4. Implement the real UI.
   Produce working code, not mockups. Keep the design coherent from the first screen to the last detail.

5. Refine the sharp edges.
   Check hierarchy, contrast, spacing rhythm, hover/focus states, responsiveness, and whether the result still feels intentional after the first pass.

## Design Rules

- Choose boldness through clarity, not noise.
- Prefer one strong concept over many weak flourishes.
- Use expressive type pairings. Avoid default stacks and overused choices like Arial, Roboto, Inter, or habitual fallback-to-safe design.
- Use color with conviction. Build around a dominant base and a small set of sharp accents.
- Give backgrounds atmosphere. Use gradients, textures, patterns, silhouettes, framing devices, or layered surfaces when they support the concept.
- Use asymmetry, overlap, and scale shifts when they improve memorability.
- Use motion sparingly but intentionally. A few strong transitions beat many decorative micro-animations.
- Make each component feel part of the same world.

## Anti-Patterns

- Do not produce purple-on-white SaaS boilerplate unless the user explicitly wants it.
- Do not use cookie-cutter hero sections, card grids, or dashboard shells without changing the visual language in a meaningful way.
- Do not add random gradients, glassmorphism, or shadow piles with no relationship to the concept.
- Do not overuse `useMemo`/`useCallback` in React just to look sophisticated.
- Do not break accessibility, readability, or mobile layout to chase novelty.

## Implementation Guidance

### Existing Products

Preserve the established design system when working inside an existing codebase unless the user explicitly asks for a redesign. Improve quality without breaking the product's visual identity.

### New Builds

Start by defining:
- display font and body font
- 4-8 core colors as CSS variables
- spacing rhythm
- border, radius, and shadow rules
- animation timing and easing

Then build the page around those decisions instead of styling component-by-component.

### React

- Prefer modern React patterns already used by the repo.
- Avoid adding memoization by default.
- Keep components readable; move repeated design tokens into CSS variables or shared theme objects.

### HTML/CSS

- Prefer CSS variables for color, spacing, and motion tokens.
- Use layered backgrounds and pseudo-elements before reaching for extra markup.
- Keep the DOM clean enough that the aesthetic is maintainable.

## Quality Bar

Before finishing, check:

- Is the design direction obvious within three seconds?
- Is there a memorable visual idea rather than a generic template?
- Does the typography feel chosen, not defaulted?
- Does the interface hold up on mobile and desktop?
- Are hover, focus, and loading states consistent with the visual system?
- If the layout were stripped of content, would the composition still feel designed?

## Output Style

- Implement the code directly when the request is actionable.
- Explain the chosen aesthetic briefly and concretely when useful.
- If the request is broad, choose a strong direction instead of presenting many timid options.
- When revising an existing UI, call out the main design moves rather than listing every tiny styling change.
