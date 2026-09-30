![Icon](./app/src/main/res/mipmap-hdpi/ic_launcher.png)MatLog
=========
It's CatLog, but with material goodness.

Graphical log reader for Android.

Based on Nolan Lawson's CatLog

Overview
---------
MatLog is a free and open-source material-style log reader for Android based on CatLog.

It shows a scrolling (tailed) view of the Android "logcat" system log, 
hence the goofy name.  It also allows you to record logs in real time, send logs via email, 
and filter using a variety of criteria.

MatLog 3 adds:

- Android Studio's [key-value search][kv] - `tag:`, `message:`, `package:`,
  `process:`, `level:`, `is:`, `age:` and the rest, with `&`, `|` and
  brackets - with the terms tinted in the box and completed as you type
- **Find in log** (Ctrl+F), which marks every occurrence and walks through
  them instead of hiding the lines that do not match
- Reading the log through root, [Shizuku][shizuku] or `READ_LOGS`, whichever
  is available, so most devices never see Android's log-access prompt
- An **Auto** theme that follows the system's light/dark setting
- Saving logs with no folder picker on any Android version

FAQs
-------------
Taken from CatLog's FAQ:

#### Where are the logs saved?

No folder has to be picked first:

- **Android 10 and below:** in `/sdcard/matlog`, as MatLog always did. Android
  asks for the storage permission once.
- **Android 11 and later:** in `/Documents/matlog/`. Any app may save its own
  files there without a permission, but it only sees the files it saved
  itself, so MatLog does not list logs from before a reinstall, or ones that
  got there some other way. They are still there for a file manager.
- **Rooted, with the F-Droid build:** in `/sdcard/matlog`. MatLog uses root to
  give itself all-files access, and then it sees every log there. (The Google
  Play build does not ask for that permission, which Play restricts.)

To see every log in a folder without root, or to keep logs somewhere else, go
to **Settings → Log folder → Choose a folder…** and pick one; MatLog saves to a
`matlog` folder inside it from then on. Pick **Documents** to keep using
`/Documents/matlog/` and see all the logs in it.

#### How does the search box work?

It takes Android Studio's logcat queries and means the same thing by them, so
Studio's own reference is the documentation for it:
[Key-value search][kv].

`tag:`, `message:`, `line:`, `package:` and `process:` match a case-insensitive
substring; `tag~:` matches a regular expression and `tag=:` the whole field,
and a leading `-` negates any of those. `level:` matches that severity or
worse, `is:` takes `crash`, `stacktrace`, `firebase` or a level name, `age:`
takes `30s`, `5m`, `3h` or `1d`, and `name:` recalls a saved filter. A bare
word is looked for anywhere in the line, so a pid or a clock time is
searchable; quote it for a phrase. `&`, `|` and brackets combine terms. Key
terms are tinted as you type, and the box completes both the keys and their
values.

MatLog differs in three small ways: `package:mine` means MatLog itself, since
there is no project; `pid:` is an extra; and `package:` and `process:` need
root or Shizuku to resolve names at all.

#### Why does a dialog ask for access to all device logs?

That prompt belongs to the `READ_LOGS` permission, which recent Android
releases make you re-confirm every session. MatLog settles once per launch how
it is allowed to read the log, in this order:

1. **root** - logcat runs through `su`, with no prompt.
2. **[Shizuku][shizuku]** - logcat runs with Shizuku's shell privileges, also
   with no prompt. If Shizuku is running but has not granted MatLog access,
   MatLog offers to ask for it.
3. **`READ_LOGS`** - granted once over adb with
   `adb shell pm grant com.pluscubed.matloglibre android.permission.READ_LOGS`
   (`com.pluscubed.matlog` for the Play build), and then re-confirmed through
   that dialog.

Grant whichever you have; without any of the three, MatLog only sees its own
logs.

#### I can't see any logs!

This problem typically shows up on custom ROMs.  First off, try an alternative logging app, to verify that
the problem is with your ROM and not MatLog.

Next, see if your ROM offers system-wide settings to disable logging.  Be sure to reboot after you change anything.

If that still doesn't work, you can contact the creator of your ROM to file a bug/RFE.

Development
-------------
- Select `fdroid` build variants to build and run immediately
- For `play` variants:
    - Put `google-services.json` from Firebase in `app/src/play/`
    - Without `google-services.json` the play flavor still builds; Crashlytics
      just stays inert (the google-services plugin is applied conditionally)

### Release signing

A release build is signed with the debug key unless `local.properties`
(gitignored) names a keystore:

```
RELEASE_STORE_FILE=/path/to/keystore.jks
RELEASE_STORE_PASSWORD=...
RELEASE_KEY_ALIAS_MATLOG=...
RELEASE_KEY_PASSWORD_MATLOG=...
```

`-P` properties of the same names take precedence, for building somewhere
that keeps its secrets elsewhere. Check what came out with
`apksigner verify --print-certs`: a release signed with the debug key says
`CN=Android Debug`.

### Toolchain

| | |
|---|---|
| Android Gradle Plugin | 9.4.1 |
| Gradle | 9.6.1 |
| JDK | 17+ (built with 21) |
| compileSdk / targetSdk | 37 (Android 17) |
| minSdk | 23 |

`minSdk 23` rather than 21 because AndroidX moved its own floor to 23 in 2025
(appcompat 1.8.0, material 1.14.0). You only lose Android 5.0/5.1.

Note that `local.properties` must point at your SDK, e.g.
```
sdk.dir=/path/to/AndroidSDK
```

Credits
---------
- Developed by [Daniel Ciao (plusCubed)](http://pluscubed.com)
- This fork is maintained by [AndnixSH](https://github.com/AndnixSH)
- Based on [CatLog](https://github.com/nolanlawson/Catlog) by
  [Nolan Lawson](http://nolanlawson.com)
- MatLog is open source on [GitHub](https://github.com/AndnixSH/matlog)

License
---------
```
Copyright (C) 2018  Daniel Ciao

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program.  If not, see <http://www.gnu.org/licenses/>.

```

[kv]: https://developer.android.com/studio/debug/logcat#key-value-search
[shizuku]: https://shizuku.rikka.app/
