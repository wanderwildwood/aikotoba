# Privacy

Passwords sends nothing anywhere. It cannot: it has no permission to use the internet.

That is the whole policy. The rest of this page is the evidence for it, because a privacy
policy that cannot be checked is just a promise.

## What it asks for, and why

`app/src/main/AndroidManifest.xml` declares three permissions, and `INTERNET` is not among them.

- **Alarms** (`SCHEDULE_EXACT_ALARM`) — to empty the clipboard thirty seconds after a copy, even
  if the app was closed in between. Android 12 grants it without asking.
- **Camera** (`CAMERA`) — only for the QR scanner, asked the first time a code is scanned. The
  live picture is read for a code and nothing of it is kept.
- **Fingerprint** (`USE_BIOMETRIC`) — only when Settings → Unlock with fingerprint is on. The
  reading happens in the phone's own fingerprint sheet; the app never sees the fingerprint, only
  whether the phone accepted it.

## Where your passwords are

In the KeePass file you chose, wherever you keep it, encrypted with your master password. The
app reads it through Android's file picker and writes back only to that file. While the vault is
unlocked its contents are in the app's memory; locking (by hand, when the screen goes dark, or
after the minutes chosen in Settings) drops them.

What is kept inside the app:

- **Its settings**: which file is the vault, how long until it locks, which list opens first, and
  the password generator's choices. No password, key or entry.
- **The vault file as it was before the last save**, which is as encrypted as the file itself, so
  a save that goes wrong can be undone. It is deleted when the vault is closed in Settings.
- **With fingerprint unlock on, the master password encrypted** (AES-GCM) by a key that lives in
  the phone's hardware key store and never leaves it. That key can only be used right after a
  registered fingerprint is read, and the phone destroys it if a fingerprint is added or removed.
  Turning the setting off, or closing the vault, deletes both the key and the ciphertext. Nothing
  that could open the vault is ever written in the clear.

Both are left out of Android's backups and device-to-device transfers (`allowBackup="false"`, and
data-extraction rules that exclude everything).

## Other apps

- **Autofill.** When you choose Passwords as the phone's autofill service, other apps ask it for
  sign-ins. It answers only with entries that name that app or that web site, or with the entry
  you pick yourself, and never keeps what you type in those apps.
- **Shared to it.** Links and pictures shared to Passwords are read for two-step codes and not
  kept.
- **The clipboard.** What you copy is marked sensitive and cleared after thirty seconds.

No screen of the app can be captured in a screenshot or shown in Recents (`FLAG_SECURE`), and
none of its fields are offered to any autofill service, its own included.

<!-- shared-about privacy section -->
## Who we are

Passwords is made by wander wildwood, an independent developer: one person, not a company. wander wildwood is the data controller for anything the app itself handles, and that is very little. Passwords keeps what you put in it on your phone and sends nothing to the developer, so the developer holds no data about you.

## How to contact us

Write to email@wanderthe.dev about your data, this policy or anything else. The same address is in the app, under About.

## Your rights

If you are in the EU, the EEA or the UK, the GDPR gives you the right to:

- see the personal data held about you (access)
- have it corrected (rectification) or deleted (erasure)
- limit how it is used (restriction)
- object to its use (objection)
- get a copy to take elsewhere (portability)
- withdraw your consent at any time, where something rests on it
- complain to a data protection supervisory authority, for example the one where you live

Similar laws elsewhere, such as the CCPA in California, give you the right to know, delete and correct, and to opt out of the sale or sharing of your data. Nothing from Passwords is sold or shared.

Since Passwords keeps what it stores only on your phone, you can exercise most of these rights yourself: edit or delete any entry in the app, close the vault in Settings (which also deletes the copy kept from before the last save and any fingerprint key), clear the app's data in Android's settings, or uninstall the app, which deletes all of it. For anything you cannot do yourself, write to the address above.
<!-- /shared-about privacy section -->
