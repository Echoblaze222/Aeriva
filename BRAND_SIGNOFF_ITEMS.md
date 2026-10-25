# AERIVA brand sign off items

Branch `design/advisor-ui-and-brand-signoff`. Owner: Agent 6 (design and UX).
Documentation only. Nothing here changes a file in the app.

## 1. Status and sources (read this first)

Revision 2. Revision 1 was written without the brand documents. They have now been supplied and read:
* `AERIVA_BRAND_IDENTITY_AND_LOGO_DESIGN.md`, version 1.0 (cited below as "the brand doc", by section number).
* `AERIVA_BRAND_HANDOFF.md`.
* Five raster boards from `AERIVA_Brand_Assets.zip`: master, logo, app icon, splash, design system. I viewed the master, logo and design system boards. I did not inspect the app icon and splash boards in detail.

Still not available: `AERIVA_Brand_Guidelines.md` (named in the brief) and `AERIVA_LOGO_CONCEPT.png` (named in brand doc section 21). The handoff file and the brand doc are the closest sources. Anything that would only be in the missing file stays UNCONFIRMED.

Limits on the boards, CONFIRMED by viewing: they are raster references with visible garbling (for example "Netcome back" on the sign in mock) and blurred small text. The brand doc itself says the concept is a reference, not final vector artwork (section 21). I treat the boards as visual direction only, never as a source of copy or measurements.

Evidence labels: CONFIRMED (read in a document, the repository or an official page), LIKELY (reasoned), UNCONFIRMED (could not check).

## 2. Summary

| Id | Item | Status after reading the brand doc | Recommendation |
|---|---|---|---|
| B1 | Tagline versus "CONNECT, MANAGE, OPTIMIZE" | CONFIRMED conflict between the doc and the boards | The doc wins for the lockup. Keep "OPTIMIZE" and any optimization wording out of A1 UI. |
| B2 | Clear space | The doc defines it by node diameter. No bounding box rule found. | Keep the doc rule. Ask the formal grid to publish the equivalent bounding box proportion. |
| B3 | Symbol and font | CONFIRMED that the app icon is a hand drawn signal arc mark that the doc forbids. Font is a system font. | Replace the icon from final vector art. Choose a licensed geometric sans. |
| B4 | "Network Quality: Excellent" badge | CONFIRMED on the boards. Not in the doc text. | Do not build it. |
| B5 | Palette | RESOLVED in principle. The doc, the handoff and the UI skill agree. `colors.xml` is the outlier. | Correct `colors.xml` in a leader approved task. |
| B6 | Boards depict data the app does not have (new) | CONFIRMED | Treat as illustration, not requirement. |

## 3. B1 Tagline

What the sources say (CONFIRMED):
* Brand doc sections 1.2 and 9: the promise and the lockup tagline is "Stay connected. Use less. Go further." The lockup is symbol, then AERIVA, then that tagline. Section 9 adds that the tagline should not be attached to the logo in every product context.
* The master, logo and splash boards show a different descriptor under the wordmark: "CONNECT  MANAGE  OPTIMIZE". It also appears in the typography panel.
* The brand doc never mentions "CONNECT, MANAGE, OPTIMIZE". The two sources conflict.
* Brand doc section 18 lists "Optimize your connection." as acceptable language. Section 17 says AERIVA improves how available connectivity is used and does not increase signal strength.
* The app already shows the doc tagline (`brand_tagline` in `strings.xml`, used in `activity_main.xml`).

Analysis: the doc is the written specification and the boards are references (doc section 21), so the doc decides the lockup. The doc also permits "optimize" in brand voice. The project rules still limit what the app may say: no claim of an improvement that was not measured (spec section 2, core engineering rule 10, leader decision L7). A1 measures nothing, so no A1 string may say or imply optimization. A board settings menu item named "Optimization" with no behavior behind it would also break the rule that a screen without real behavior is not done.

Recommendation (LIKELY):
1. Lockup tagline is "Stay connected. Use less. Go further." Remove the three word descriptor from every exported asset.
2. Keep "optimize" and "Optimization" out of app UI until a measured capability exists.
3. Do not place the tagline directly beside a measurement or a recommendation.

Decision needed from the owner: confirm that the descriptor line is dropped.

## 4. B2 Clear space

What the sources say (CONFIRMED): brand doc section 10 defines the minimum clear space using the diameter of the connection node, and says the design team should create a formal clear space grid (also item 3 of the production list in section 22). I found no bounding box definition in the doc, the handoff or the boards. Where the brief's second definition comes from is UNCONFIRMED.

Analysis: the node rule is the only written rule, so it stands. The weakness I noted in revision 1 still applies, but is now smaller: the final vector artwork and exact geometry do not exist yet (doc section 22, items 1 and 2), so a node based number cannot be fixed today. Once the final artwork exists, the node diameter and the symbol bounding box are both measurable, and one can be stated in terms of the other.

Recommendation (LIKELY): keep the node diameter rule. When the formal grid is made, publish the same margin also as a proportion of the symbol bounding box, so engineers can apply it from an exported asset without measuring a node. Both numbers then describe one rule and the conflict disappears.

Decision needed from the owner: the minimum size below which the symbol is not shown (doc section 7.2 lists sizes the symbol must survive, but gives no minimum).

## 5. B3 Symbol and font

### 5.1 Symbol (CONFIRMED rule breach)

What the doc requires:
* The symbol is a geometric A with an ice blue flowing element that ends in a node. It must not become a literal Wi-Fi symbol (sections 2.1 and 2.2). Misuse list: do not add a Wi-Fi symbol, do not recreate the symbol with a generic icon font (section 11).
* "No developer should manually redraw the logo inside the application" (section 22). The handoff repeats it: do not redraw, stretch, rotate, recolor or decorate the mark.

What the repository has (CONFIRMED): `ic_launcher_foreground.xml` is a hand authored vector of two concentric arcs and a dot, commented "Signal-arc mark". It contains no A.

Two findings:
1. It is not the approved mark. CONFIRMED by comparing the vector with the doc and the boards.
2. Two concentric arcs over a dot is the common form of a Wi-Fi glyph. That is my visual judgment from the path data, not a rendered check, so LIKELY. It is exactly the resemblance the doc rules out.

The launcher background is `#000000`, while doc section 7.3 says the icon background should stay close to `#0A0A0A` (CONFIRMED difference).

Recommendation:
1. Treat the current launcher icon as a placeholder, not as the logo.
2. Replace it with adaptive icon assets exported from the final vector master, per doc sections 20 and 22. No agent redraws it.
3. Until then, no store listing, website or document uses the current icon as the AERIVA mark.

### 5.2 Font

What the sources say: doc section 8 asks for a geometric sans serif and says the application UI should prioritize readability over branding. The master board names "SF Pro Display (or similar modern sans-serif)" (CONFIRMED on the board).

What the repository has (CONFIRMED): the wordmark in `activity_main.xml` is a `TextView` in the platform default font, bold, letter spacing 0.3, uppercase, which follows doc section 9 loosely. No font file exists under `app/src/main/res`.

Analysis: SF Pro is an Apple typeface. Whether its license allows use in an Android app was not checked here, so UNCONFIRMED. I would not assume it does. The doc also says not to use the wordmark as normal application text, so only the single brand line on Home should use wordmark styling.

Recommendation (LIKELY): the owner picks an openly licensed geometric sans, and the license is reviewed before a font file is added. A font file is a resource and release change and needs leader approval. I did not add one. Keep the system font for body text until then.

## 6. B4 "Network Quality: Excellent" badge

What the sources say (CONFIRMED): the master board and the design system board show a component reading "Network Quality" with the value "Excellent". The brand doc text has no such component. Doc section 18 prefers factual wording such as "Connection quality changed."

What the product rules say (CONFIRMED): spec section 2 lists no single generic quality score as a non goal. Spec section 3.2 item 6 records `NetworkQuality.Measured(score, label)` as an open risk that Network Advisor must not use.

Analysis: "Excellent" is a verdict. A1 has no measurement, only Android's validation flag. The brand doc itself warns against fake network dashboards (section 15) and exaggerated claims (section 18). Even after A2, quality depends on the activity.

Recommendation (LIKELY): do not build the badge. If the visual shape is wanted, use it for a fact, for example "Internet check: Validated by Android" (existing `value_internet_validated`). After A2, any grouped label must name the activity and show evidence, sample count and freshness (spec section 6). Never a bare verdict word.

Decision needed from the owner: confirm the badge is dropped or reframed.

## 7. B5 Palette

What the sources say (CONFIRMED):

| Role | Brand doc section 4 | Handoff | UI skill | `colors.xml` |
|---|---|---|---|---|
| Dark base | `#0A0A0A` ("Charcoal Black") | `#0A0A0A` ("Midnight Black") | `#0A0A0A` | `aeriva_black` `#000000` |
| Silver | `#C0C6D1` | `#C0C6D1` | `#C0C6D1` | `aeriva_silver` `#B4BBC5` |
| Ice blue | `#5EC8FF` | `#5EC8FF` | `#5EC8FF` | `aeriva_ice_blue` `#8FD3FF` |

Three sources agree, so `colors.xml` is the outlier, and the launcher background `#000000` matches it. Minor: the doc and the handoff name the dark base differently (Charcoal versus Midnight).

Not defined by any brand source (UNCONFIRMED whether intended): the card surface `#0E1114`, stroke `#1E242B`, white `#F4F6F8`, and the warning, offline and unknown state colors. Brand doc section 22 item 12 still lists brand color tokens as to be created.

Accessibility does not block the change. On the card surface `#0E1114`, brand silver measures 11.04 and brand ice blue 10.08, against 9.79 and 11.66 for the current values (computed by this agent, `ADVISOR_UI_SPEC.md` section 7). All pass 4.5:1.

Recommendation: correct `colors.xml` and the launcher background to the brand values in a leader approved task. I did not change them: it alters the shipped Home screen, and the leader asked for the wiring to wait. The advisor resources alias `colors.xml`, so they follow automatically.

## 8. B6 Boards depict data the app does not have (new)

CONFIRMED on the boards: a signal graph, "Signal Strength: Excellent", a "Wi-Fi 5 GHz" line, a data usage bar of "2.4 GB of 10 GB", a permission screen asking to allow network access, and an "Optimization" settings entry.

Against the repository: A1 reads no signal strength, no band and no network name, because spec section 5.1 says reading these needs location permission, which A1 must not request. The data usage pillar does not exist yet. A1 asks for no permission.

Recommendation: treat these panels as visual direction for later phases. They are not requirements, and none may reach the UI as a placeholder with fake values (core engineering rule 9).

## 9. Decisions needed from the owner

1. B1: drop the descriptor line "CONNECT, MANAGE, OPTIMIZE" from the lockup.
2. B2: minimum symbol size, and publish the clear space also as a bounding box proportion.
3. B3: supply final vector master artwork, and choose a licensed geometric sans.
4. B4: drop or reframe the badge.
5. B5: approve correcting `colors.xml` to the brand values, and define tokens for surface, stroke and state colors.

## 10. Sources

* `AERIVA_BRAND_IDENTITY_AND_LOGO_DESIGN.md` v1.0, sections 1.2, 2, 4, 7, 8, 9, 10, 11, 13, 15, 17, 18, 21, 22.
* `AERIVA_BRAND_HANDOFF.md`.
* `AERIVA_BRAND_MASTER_BOARD.png`, `AERIVA_LOGO_BOARD.png`, `AERIVA_DESIGN_SYSTEM_BOARD.png`.
* Repository at `9cff60c`: `strings.xml`, `colors.xml`, `activity_main.xml`, `ic_launcher_foreground.xml`, `ic_launcher_background.xml`, `.claude/06-ui-ux/SKILL.md`.
* `NETWORK_ADVISOR_PHASE_A_SPEC.md`, sections 2, 3.2, 5.1 and 6.
