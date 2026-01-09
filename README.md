# AAPS 3.4.0 | Modified version, especially for active people

There may be problems installing this version as an update over the previous one. Please uninstall the previous version before installation and then import your settings.

This version includes the following main modifications:
- Exercise Mode (→ setting: high tt raises sensitivity), toggleable via the overview icon for openAPSSMB, dynISF and autoISF.
- Activity Monitor (enable in settings): Uses phone step counter (ensure movement permission granted). Adjusts basal and ISF based on activity/inactivity.
- Ketoacidosis Protection (enable in settings):
(a) Reduces basal if IOB ≈ no insulin for 1h, or
(b) Ensures a minimum basal (e.g., 20%) to avoid 0% TBR.
- Additional hypo strategy: hypoTT + 50% TBR for 60 min.
- Possibility to reduce SMB: Set a threshold value (default: 100 mg/dl) below which no SMBs are released
- Temp targets up to 220 mg/dl (12 mmol/l) allowed.
- Layout fixes for small devices.
- autoISF no longer requires engineering mode.

autoISF 3.1.0 has more functions than in the official master branch; dynISF isn't modified anymore (imho dynISF in particular is not recommended!)

Otherwise the usual disclaimer applies:
This repo has some private changes concerning tbr management, iob and layout. This is experimental!
Please use the master branch Nightscout! URL: https://github.com/nightscout/AndroidAPS

# AAPS
* Check the wiki: https://wiki.aaps.app
* Everyone who’s been looping with AAPS needs to fill out the form after 3 days of looping  https://docs.google.com/forms/d/14KcMjlINPMJHVt28MDRupa4sz4DDIooI4SrW0P3HSN8/viewform?c=0&w=1

[![Support Server](https://img.shields.io/discord/629952586895851530.svg?label=Discord&logo=Discord&colorB=7289da&style=for-the-badge)](https://discord.gg/4fQUWHZ4Mw)

[![CircleCI](https://circleci.com/gh/nightscout/AndroidAPS/tree/master.svg?style=svg)](https://circleci.com/gh/nightscout/AndroidAPS/tree/master)
[![Crowdin](https://d322cqt584bo4o.cloudfront.net/androidaps/localized.svg)](https://translations.aaps.app/project/androidaps)
[![Documentation Status](https://readthedocs.org/projects/androidaps/badge/?version=latest)](https://wiki.aaps.app/en/latest/?badge=latest)
[![codecov](https://codecov.io/gh/nightscout/AndroidAPS/branch/master/graph/badge.svg?token=EmklfIV6bH)](https://codecov.io/gh/nightscout/AndroidAPS)

DEV: 
[![CircleCI](https://circleci.com/gh/nightscout/AndroidAPS/tree/dev.svg?style=svg)](https://circleci.com/gh/nightscout/AndroidAPS/tree/dev)
[![codecov](https://codecov.io/gh/nightscout/AndroidAPS/branch/dev/graph/badge.svg?token=EmklfIV6bH)](https://codecov.io/gh/nightscout/AndroidAPS/tree/dev)
