# AERIVA privacy policy (draft)

Status: DRAFT for the owner to review and host. It describes the app as it is on `main` @ `f5b42f7` and must be re-checked before every release that changes what the app does.

Last updated: [date to be set by the owner when published]
Contact: [contact email to be set by the owner]

## The short version

AERIVA shows you what Android reports about your internet connection, in plain words. It has no account, it does not ask for the internet permission, and it does not send anything off your phone on its own.

## What AERIVA reads

AERIVA uses Android's network status information, which Android makes available through the "view network connections" permission (`ACCESS_NETWORK_STATE`). From it AERIVA reads, for each network your phone currently has:

- the type of network (Wi-Fi, mobile data, Ethernet, VPN or other);
- whether Android's own connectivity check passed on that network;
- whether the network is metered;
- whether Android reported a sign-in page or a device policy block.

AERIVA does not read your location, contacts, messages, files, browsing, the names of Wi-Fi networks or mobile carriers, the content of anything you send or receive, or the list of apps on your phone.

## What AERIVA stores on your phone

- **Recent changes.** The latest changes in your connection (the time, the type of network, whether Android's check passed, and whether it is metered). They stay in the app's private storage on your phone and are removed after 30 days. You can turn this off or clear it in Settings at any time.
- **Your settings.** Whether history is on, and whether you have dismissed the welcome card.

Nothing stored is sent anywhere. Uninstalling the app removes it.

## What AERIVA sends

Nothing. The app does not declare the internet permission, so it cannot connect to the internet. If you use **Share status**, AERIVA hands a short text summary of what the screen shows to Android's share sheet. It is sent only to the app you choose, and only because you chose it.

## Third parties

None. AERIVA contains no advertising, analytics, crash reporting or tracking library.

## Children

AERIVA does not collect personal information from anyone, including children.

## Changes to this policy

If a later version of AERIVA changes what it reads, stores or sends, this policy will be updated before that version is released, and the change will be described in the release notes.

## Not yet covered (and why)

The planned Data Usage feature would ask you to grant Usage access so the app can read how much data each app has used. It is not in the app today. This policy must be extended before that feature ships, including how the app-level figures are shown and stored.
