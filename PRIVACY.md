# Privacy

Passwords sends nothing anywhere. It cannot: it has no permission to use the internet.

That is the whole policy. The rest of this page is the evidence for it, because a privacy
policy that cannot be checked is just a promise.

## What it asks for, and why

`app/src/main/AndroidManifest.xml` declares one permission, and `INTERNET` is not it.

- **Alarms** (`SCHEDULE_EXACT_ALARM`) — to empty the clipboard thirty seconds after a copy, even
  if the app was closed in between. Android 12 grants it without asking.

There is no camera permission: a QR code is photographed by the phone's own camera app, which
hands the one picture back, and the picture is deleted once read.

## Where your passwords are

In the KeePass file you chose, wherever you keep it, encrypted with your master password. The
app reads it through Android's file picker and writes back only to that file. While the vault is
unlocked its contents are in the app's memory; locking (by hand, when the screen goes dark, or
after the minutes chosen in Settings) drops them.

Two things are kept inside the app:

- **Its settings**: which file is the vault, how long until it locks, which list opens first, and
  the password generator's choices. No password, key or entry.
- **The vault file as it was before the last save**, which is as encrypted as the file itself, so
  a save that goes wrong can be undone. It is deleted when the vault is closed in Settings.

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
