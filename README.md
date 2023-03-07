# AAPS
* Check the wiki: https://wiki.aaps.app
Everyone who’s been looping with AAPS needs to fill out the form after 3 days of looping  https://docs.google.com/forms/d/14KcMjlINPMJHVt28MDRupa4sz4DDIooI4SrW0P3HSN8/viewform?c=0&w=1

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
* Layout corrections for small devices like Jelly
* Integration of autoISF 2.2.8 (Quick guide: https://github.com/ga-zelle/autoISF/blob/A3.1.0.3_ai2.2.8/autoISF2.2.8_Quick_Guide.pdf)
* Additions to dynISF:
  - Toggle autosens via overview icon (dynISF only)
  - LGS threshold (65-120 mg/dl; Preferences > Treatments safety)
* engineering_mode: enabled (to use dynISF)

Please note that these are personal changes that work well _for me_. You should definitely use the official [AAPS master branch](https://github.com/nightscout/AndroidAPS/tree/master)!

[![Support Server](https://img.shields.io/discord/629952586895851530.svg?label=Discord&logo=Discord&colorB=7289da&style=for-the-badge)](https://discord.gg/4fQUWHZ4Mw)