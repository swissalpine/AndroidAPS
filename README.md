# AAPS 3.3.1.2 modified (alpha status)

There may be problems installing this version as an update over the previous one. Please uninstall the previous version before installation and then import your settings.

This version does not have all changes of my branch sport-changes. Fully integrated are
- Activity mode (full integrated in OpenAPS SMB, partially in Auto ISF)
- Ketoacidosis protection
- Additional hypo strategy: hypoTT + tbr 50% @ 60 min
- Reduced SMB functionality (no SMB if blood glucose is below 100 mg/dl)
- Allow temp targets up to 220 mg/dl (12 mmol/l)

autoISF has the same status as in the official master branch 3.3.1, but does not yet have all the functions which are developed

The attempt to use steps from Wear OS watches for Activity Mode has not yet been tested.

Otherwise the usual disclaimer applies:
This repo has some private changes concerning tbr management, iob and layout. This is experimental! Please use the master branch Nightscout! URL: https://github.com/nightscout/AndroidAPS

# AAPS
* Check the wiki: https://wiki.aaps.app
*  Everyone who’s been looping with AAPS needs to fill out the form after 3 days of looping  https://docs.google.com/forms/d/14KcMjlINPMJHVt28MDRupa4sz4DDIooI4SrW0P3HSN8/viewform?c=0&w=1

[![Support Server](https://img.shields.io/discord/629952586895851530.svg?label=Discord&logo=Discord&colorB=7289da&style=for-the-badge)](https://discord.gg/4fQUWHZ4Mw)

[![CircleCI](https://circleci.com/gh/nightscout/AndroidAPS/tree/master.svg?style=svg)](https://circleci.com/gh/nightscout/AndroidAPS/tree/master)
[![Crowdin](https://d322cqt584bo4o.cloudfront.net/androidaps/localized.svg)](https://translations.aaps.app/project/androidaps)
[![Documentation Status](https://readthedocs.org/projects/androidaps/badge/?version=latest)](https://wiki.aaps.app/en/latest/?badge=latest)
[![codecov](https://codecov.io/gh/nightscout/AndroidAPS/branch/master/graph/badge.svg?token=EmklfIV6bH)](https://codecov.io/gh/nightscout/AndroidAPS)

DEV: 
[![CircleCI](https://circleci.com/gh/nightscout/AndroidAPS/tree/dev.svg?style=svg)](https://circleci.com/gh/nightscout/AndroidAPS/tree/dev)
[![codecov](https://codecov.io/gh/nightscout/AndroidAPS/branch/dev/graph/badge.svg?token=EmklfIV6bH)](https://codecov.io/gh/nightscout/AndroidAPS/tree/dev)

<img src="https://cdn.iconscout.com/icon/free/png-256/bitcoin-384-920569.png" srcset="https://cdn.iconscout.com/icon/free/png-512/bitcoin-384-920569.png 2x" alt="Bitcoin Icon" width="100">

3KawK8aQe48478s6fxJ8Ms6VTWkwjgr9f2
