# AERIVA store listing and Play Console answers (draft)

Status: DRAFT for the owner. Wording follows the product rules already in the repository: say what Android reports, never promise performance, never claim AERIVA measured anything.

Items marked CHECK depend on current Google Play Console wording or policy, which this draft did not verify. Read them in the Console before answering.

## Listing text

**App name:** AERIVA

**Short description (80 characters or fewer):**
Plain words about your connection, from what Android reports.
(Count before pasting: it must fit the Console's limit. CHECK.)

**Full description:**

AERIVA tells you what Android reports about your connection, in plain words.

Stay connected. Use less. Go further.

- Connection status: see whether Android's own connectivity check passed on the network you are using.
- Your networks: every network Android reports to the app, with only the facts Android reports (check passed or not, metered or not, sign-in page or device policy block).
- Network Advisor: when the network you are using did not pass Android's check and another one did, AERIVA suggests the other one and opens Android's settings. You decide whether to switch.
- Recent changes: the latest changes in your connection, saved only on your phone for 30 days. Turn it off or clear it in Settings.
- Share status: send a short text summary of your connection to a friend or your provider.

Private by design: no account, no ads, no analytics, and no internet permission. What AERIVA shows stays on your phone.

What AERIVA does not do: it does not measure your speed, it does not change your connection, and a passed check from Android does not guarantee every app or website will load.

## Screenshots to capture (real device, not the emulator)

1. Home with a connected network and the advisor card idle.
2. Home with the advisor recommending the other network (needs a Wi-Fi with no internet and mobile data on).
3. Your networks with two networks listed.
4. Recent changes with several entries.
5. Settings.
6. About.

Use a phone with a plain status bar and no personal notifications. Do not show real Wi-Fi names.

## Category and rating (CHECK all of these in the Console)

- Category suggestion: Tools.
- Content rating questionnaire: the app has no user generated content, no violence, no purchases and no ads. Answer from the questionnaire itself.
- Target audience: general. CHECK the Families policy wording before choosing any child age range.

## Permissions declared by the release app

| Permission | Why | Notes |
|---|---|---|
| `ACCESS_NETWORK_STATE` | Read which networks exist and what Android reports about them. | A normal permission, granted at install. Declared by the network monitor module and merged into the app. |

The release app declares no internet permission, no location permission and no usage access permission.

The debug build used for testing adds `PACKAGE_USAGE_STATS` and a package visibility entry for the Data Usage probe. They live only in the debug source set (`app/src/debug`) and are not in a release build. Confirm this by inspecting the merged release manifest before every upload.

## Data safety form (CHECK the exact questions)

As the app is today, the honest answers are expected to be:

- Does the app collect or share any of the required user data types? No.
- Is all of the user data encrypted in transit? Not applicable, because no data leaves the device.
- Can users request that their data be deleted? There is no data held off the device. The user can clear the history in Settings or uninstall the app.

The form must be reviewed again when Data Usage ships.

## Privacy policy

The Console needs a public URL. The draft is `PRIVACY_POLICY.md`. The owner must host it at a stable URL and fill in its contact line. UNCONFIRMED: where it will be hosted.

## Not ready yet (do not submit until each is resolved)

- Real device checks of the advisor recommendation state, TalkBack, large font and the new screens have not been done.
- The brand guideline documents are not in the repository, so brand sign-off items remain open (see `BRAND_SIGNOFF_ITEMS.md` on branch `design/advisor-ui-and-brand-signoff`).
- A release signing key and Play App Signing enrollment do not exist yet.
