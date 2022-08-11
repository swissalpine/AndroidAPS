# AndroidAPS
* Check the wiki: https://androidaps.readthedocs.io
* Everyone who’s been looping with AndroidAPS needs to fill out the form after 3 days of looping  https://docs.google.com/forms/d/14KcMjlINPMJHVt28MDRupa4sz4DDIooI4SrW0P3HSN8/viewform?c=0&w=1

The branch [sport-changes](https://github.com/swissalpine/AndroidAPS-1/tree/sport-changes) 
realise some changes which are very important for me:
* Ketoacidosis-Protection
  The ketoacidosis protection can be enabled in the OpenApsSMB settings. Two different strategies 
  are available for this:<br />
  (a) a reduced basal rate is delivered when the IOB is as small as if no insulin had been delivered for an hour _OR_
  (b) a minimum basal rate of f. e. 20% is always delivered and 0% TBR will be avoided.
* Set TempTargets up to 220 mg/dl / 12,2 mmol/l
* No smb if bg < 100 mg/dl
* Carbs Dialog with with an additional possibility to handle hypo situations (it sets a 
  TT and suspends the loop for 60 min with a TBR of 50%)
* Exercise mode enabled (-> setting: high tt raises sensitivity), can be toggled via overview icon for openAPSSMB, dynISF and autoISF
* No use of autosens limitations to the exercise mode result
* Integrate ISF in exercise mode
* Disable autosens for openAPSSMB and autoISF, as this feature can cause problems when interacting with exercise mode
* Toggle autosens via overview icon (dynISF only)
* dynISF with adjusted, extrapolated TDD based on the weighted last 24 hours and some safety restrictions
* LGS threshold (65-120 mg/dl; Preferences > Treatments safety)
* Layout corrections for small devices like Jelly
* Integration of autoISF (Quick guide: https://github.com/ga-zelle/autoISF/tree/A3.1.0.2_ai2.2.7)
- modified for the use of exercise mode
- another modification is that every odd target (normal target AND tt) disables smb

[![Support Server](https://img.shields.io/discord/629952586895851530.svg?label=Discord&logo=Discord&colorB=7289da&style=for-the-badge)](https://discord.gg/4fQUWHZ4Mw)

[![CircleCI](https://circleci.com/gh/nightscout/AndroidAPS/tree/master.svg?style=svg)](https://circleci.com/gh/nightscout/AndroidAPS/tree/master)
[![Crowdin](https://d322cqt584bo4o.cloudfront.net/androidaps/localized.svg)](https://translations.androidaps.org/project/androidaps)
[![Documentation Status](https://readthedocs.org/projects/androidaps/badge/?version=latest)](https://androidaps.readthedocs.io/en/latest/?badge=latest)
[![codecov](https://codecov.io/gh/nightscout/AndroidAPS/branch/master/graph/badge.svg)](https://codecov.io/gh/MilosKozak/AndroidAPS)

DEV: 
[![CircleCI](https://circleci.com/gh/nightscout/AndroidAPS/tree/dev.svg?style=svg)](https://circleci.com/gh/nightscout/AndroidAPS/tree/dev)
[![codecov](https://codecov.io/gh/nightscout/AndroidAPS/branch/dev/graph/badge.svg)](https://codecov.io/gh/MilosKozak/AndroidAPS)
