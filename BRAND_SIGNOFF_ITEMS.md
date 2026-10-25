# AERIVA brand sign off items

Branch `design/advisor-ui-and-brand-signoff`. Owner: Agent 6 (design and UX).
Documentation only. Nothing here changes a file in the app.

## 1. Status and limits (read this first)

The brief asks for recommendations on four open brand conflicts. The documents that define them were not available to me:
* `AERIVA_Brand_Assets_Production/Brand/AERIVA_Brand_Guidelines.md`: not found.
* `AERIVA_BRAND_IDENTITY_AND_LOGO_DESIGN.md`: not found.
* The logo lockup file and the concept board: not found.

CONFIRMED absent: I searched `main` @ `9cff60c` and every remote branch for file names containing brand, logo or identity. They are likely only in a local checkout.

Consequence for every item below:
* What the brief says about a conflict is recorded as "Reported in the brief". I could not read the source, so I label it UNCONFIRMED.
* What the repository shows is recorded as "Seen in the repository" and labeled CONFIRMED.
* Recommendations are reasoned from the repository, the product rules in `NETWORK_ADVISOR_PHASE_A_SPEC.md` and `.claude/06-ui-ux/SKILL.md`. They are LIKELY at best until the owner or leader supplies the brand documents and I recheck. I would rather say that than invent what the documents contain.

Evidence labels: CONFIRMED, LIKELY, UNCONFIRMED.

## 2. Summary

| Id | Item | Recommendation | Confidence in recommendation |
|---|---|---|---|
| B1 | Tagline conflict | Keep "Stay connected. Use less. Go further." in the app. Drop "OPTIMIZE" from the lockup. | LIKELY |
| B2 | Clear space definition | Define clear space against the symbol bounding box, not the node diameter. | LIKELY |
| B3 | Reconstructed symbol and substituted font | Do not call either final. Sign off on the original artwork and the licensed font, or accept the substitutes in writing. | CONFIRMED that both substitutes are in the app, LIKELY on the advice |
| B4 | "Network Quality" badge | Do not build a single quality badge. Use per fact labels. | CONFIRMED on the rule, LIKELY on the replacement |
| B5 | Palette values differ between the UI skill and the app (new, found by this agent) | Owner to name one set as canonical. | CONFIRMED discrepancy |

## 3. B1 Tagline

Reported in the brief (UNCONFIRMED): the tagline "Stay connected. Use less. Go further." conflicts with "CONNECT, MANAGE, OPTIMIZE" shown in the logo lockup.

Seen in the repository (CONFIRMED): `strings.xml` defines `brand_tagline` as "Stay connected. Use less. Go further." and `activity_main.xml` shows it under the screen title in silver 14sp. The shipped Milestone 1 screen therefore already uses the first tagline. The second tagline appears nowhere in the app.

Analysis:
1. The two lines are doing different jobs. The first is a promise to the user, spoken in sentences. The second is a three word descriptor, set in capitals, made for a lockup. They can coexist only if the owner declares which is the tagline and which is a descriptor.
2. "OPTIMIZE" is the riskier word. The product rules say AERIVA cannot create signal, amplify radio or connect to networks it has no credentials for, and must not claim an improvement it did not measure (spec section 2, `.claude/00-core-engineering` rule 10). The documentation standards also name "optimized" as a vague claim to avoid. A logo lockup is not an engineering spec, so the documentation rule is not binding on it, but the product rule behind it is.
3. "Use less" and "Go further" also read as outcomes. Today the app only observes the connection. No data usage feature exists yet. As an aspiration line this is normal. It becomes a problem if it is placed next to a measurement, or in a store listing that implies a result.

Recommendation (LIKELY):
* Keep "Stay connected. Use less. Go further." as the single tagline. It is already in the shipped screen.
* Remove "CONNECT, MANAGE, OPTIMIZE" from the lockup, or reduce it to "CONNECT, MANAGE" until an optimizing capability exists and has been measured on devices.
* Add a written rule: the tagline never appears directly beside a number, a chart or a recommendation, so it cannot be read as a measured claim.

Decision needed from the owner: which line is the tagline, and whether the lockup carries a descriptor at all.

## 4. B2 Clear space

Reported in the brief (UNCONFIRMED): the clear space is defined in two ways, by node diameter and by the symbol bounding box.

Seen in the repository (CONFIRMED): the launcher foreground `ic_launcher_foreground.xml` draws an outer silver arc, an inner ice blue arc and one ice blue node (a circle of diameter 10 units on a 108 unit canvas), inside the adaptive icon safe zone. This is the only symbol artwork in the repository.

Analysis:
* A node diameter rule ("keep one node of space on every side") is easy to say but depends on the node being a stable, measurable part of the symbol. B3 says the symbol is reconstructed. If the sign off changes the node size or arc spacing, a node based clear space changes with it, and every placed asset has to be rechecked.
* A bounding box rule depends only on the outer extent of the symbol. It can be measured on an exported asset, tested by a script, and written into an Android layout as a margin. It stays valid if the inner geometry changes.
* In this symbol the node is small relative to the whole mark (10 of roughly 40 units of mark width on the canvas). A node based margin would be tight. That figure is read from the vector paths and is CONFIRMED arithmetic, but whether it matches the intended rule is UNCONFIRMED.

Recommendation (LIKELY): define clear space as a fraction of the symbol bounding box (for example, a stated fraction of its height on all sides), and keep the node diameter only as an illustration. The exact fraction is a design decision I cannot make without the guideline, so it is left open here.

Decision needed from the owner: the fraction, and the minimum size below which the symbol is not shown.

## 5. B3 Reconstructed symbol and substituted font

Reported in the brief (UNCONFIRMED): the symbol is reconstructed, and the font is a substitute, both pending sign off.

Seen in the repository (CONFIRMED):
* Symbol: `ic_launcher_foreground.xml` is a hand drawn vector of two arcs and a node, with the comment "Signal-arc mark in Ice Blue / Silver". Whether this matches the approved symbol is UNCONFIRMED.
* Font: the in app wordmark in `activity_main.xml` is a plain `TextView` with the platform default font, bold, letter spacing 0.3. No font file exists under `app/src/main/res`. So the wordmark shown to users today is a system font, not a brand typeface.

Recommendation (CONFIRMED that both substitutes exist, LIKELY on the advice):
1. Record both substitutes as "provisional" in writing, and do not export them into store assets, a website or print until signed.
2. The owner chooses one of: (a) supply the original symbol artwork and the licensed font file, (b) approve the current reconstruction and system font as final, or (c) approve them for the app only.
3. If a font file is added later, that is a resource and licensing change. It must be checked against the dependency and release rules before it is added. I did not add one.
4. Until sign off, the launcher icon and the wordmark should not be described as "the logo" in any document.

## 6. B4 "Network Quality" badge

Reported in the brief (UNCONFIRMED): the concept board shows a single "Network Quality" badge, while the product has a rule against one generic score.

Seen in the repository (CONFIRMED):
* The rule: spec section 2 lists "No single generic quality score" as a non goal. Spec section 3.2 item 6 records `NetworkQuality.Measured(score, label)` as an open Phase 3A risk that Network Advisor must not use, with an owner decision on record. The UI skill requires showing units, period and limitations, and forbids decorative fake charts.
* What exists: the Home screen shows facts, one row each (network type, internet check, data cost, VPN, device policy). There is no badge, no score.

Analysis: a single badge called "Network Quality" asks the user to trust one verdict. In A1 the product has no measurement at all, only Android's validation flag. A badge would present a platform flag as a quality judgment. That is the claim the rules forbid. Even after A2, quality differs by activity (calls, gaming, streaming, downloads), which is why spec section 6 plans per activity outputs.

Recommendation (LIKELY):
* Do not build the badge for A1 or for any version that has no measurement.
* If the concept board's badge must survive as a visual idea, make it a container for facts, not a verdict: for example a badge reading "Validated by Android" (the existing `value_internet_validated` wording), never "Quality".
* After A2, any grouped label must name the activity and show evidence, sample count and freshness, as spec section 6 requires. Never a bare score or word such as "Excellent".
* Rename the concept board item so that the word "Quality" does not appear on a single indicator.

Decision needed from the owner: confirm the badge is dropped or reframed.

## 7. B5 Palette mismatch (new item)

Found by this agent. Not in the brief.

Seen in the repository (CONFIRMED):

| Role | `.claude/06-ui-ux/SKILL.md` | `app/src/main/res/values/colors.xml` |
|---|---|---|
| Dark base | Midnight Black `#0A0A0A` | `aeriva_black` `#000000` |
| Silver | `#C0C6D1` | `aeriva_silver` `#B4BBC5` |
| Ice blue accent | `#5EC8FF` | `aeriva_ice_blue` `#8FD3FF` |

The app also defines surface `#0E1114`, surface stroke `#1E242B`, white `#F4F6F8`, and three state colors (warning `#F2B84B`, offline `#FF7A7A`, unknown `#8A9099`) that `colors.xml` itself says are not brand colors. The launcher icon background is `#000000`.

Accessibility is not the deciding factor. On the card surface `#0E1114`, silver measures 9.79 (app) against 11.04 (skill), and ice blue measures 11.66 (app) against 10.08 (skill). All pass 4.5:1 (computed by this agent, see `ADVISOR_UI_SPEC.md` section 7).

Recommendation: the owner names one set as canonical, ideally from the brand guideline I could not read. Then either `colors.xml` or the skill file is corrected. Until then the new advisor resources only alias existing `colors.xml` values, so they follow whichever set is chosen.

## 8. What I need to close this out

1. The two brand documents, the lockup file and the concept board, or a statement from the owner that the repository is the only source.
2. Owner answers to the four decisions in sections 3 to 6, and the palette choice in section 7.
3. After that, I will recheck every "Reported in the brief" line, change its label from UNCONFIRMED, and correct any recommendation the documents contradict.

## 9. Sources

* Repository at `f53134c` and `9cff60c`: `strings.xml`, `colors.xml`, `activity_main.xml`, `ic_launcher_foreground.xml`, `ic_launcher_background.xml`, `.claude/06-ui-ux/SKILL.md`, `.claude/00-core-engineering/SKILL.md`, `.claude/17-documentation-standards/SKILL.md`.
* `NETWORK_ADVISOR_PHASE_A_SPEC.md`, branch `network-advisor-phase-a-spec`, sections 2, 3.2 and 6.
* AERIVA team briefs, Agent 6 section.
