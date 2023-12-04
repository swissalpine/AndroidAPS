# AAPS
* Check the wiki: https://wiki.aaps.app
Everyone who’s been looping with AAPS needs to fill out the form after 3 days of looping  https://docs.google.com/forms/d/14KcMjlINPMJHVt28MDRupa4sz4DDIooI4SrW0P3HSN8/viewform?c=0&w=1

This branch realise some changes which are very important for me:
* Ketoacidosis-Protection
  The ketoacidosis protection can be enabled in the OpenApsSMB settings. Two different strategies 
  are available for this:<br />
  (a) a reduced basal rate is delivered when the IOB is as small as if no insulin had been delivered for an hour _OR_
  (b) a minimum basal rate of f. e. 20% is always delivered and 0% TBR will be avoided.
* Set TempTargets up to 220 mg/dl / 12,2 mmol/l
* No smb if bg < 100 mg/dl (openAPS SMB and dynISF algorithm)
* Carbs Dialog with with an additional possibility to handle hypo situations (it sets a 
  TT and suspends the loop for 60 min with a TBR of 50%)
* Exercise mode enabled (-> setting: high tt raises sensitivity), can be toggled via overview icon for openAPSSMB, dynISF and autoISF
* Layout corrections for small devices like Jelly
* Integration of autoISF 3.0
* engineering_mode: enabled (to use dynISF)

Please note that these are personal changes that work well _for me_. You should definitely use the official [AAPS master branch](https://github.com/nightscout/AndroidAPS/tree/master)!

# AndroidAPS with autoISF
* For documentation about AndroidAPS without autoISF, check the wiki: https://androidaps.readthedocs.io
* Everyone who’s been looping with AndroidAPS needs to fill out the form after 3 days of looping  https://docs.google.com/forms/d/14KcMjlINPMJHVt28MDRupa4sz4DDIooI4SrW0P3HSN8/viewform?c=0&w=1

## What is autoISF?
AutoISF adds more power to the algorithm used in AndroidAPS by adjusting the insulin sensitivity based on different scenarios (e.g. high BG,
accelerating/decelerating BG, BG plateau). autoISF has many different settings to fine-tune these adjustments.
However, it is important to start with well-tested basal rate and settings for insulin sensitivity and carb ratios.

## Where to find documentation about autoISF
* Please visit ga-zelle’s repository [GitHub - ga-zelle/autoISF: extensions of AAPS for specific BG behaviours
  like stuck high or accelerating after meals with **documentation about autoISF**](https://github.com/ga-zelle/autoISF).
  The **Quick Guide (bzw. Kurzanleitung)** provides an overview of autoISF and its features

## What's new in AutoISF Version 3.0 when compared to 2.2.8.1
* Activity Monitor takes step counter from phone into consideration
* iob-Threshold allows to better limit/control the total amount of insulin on board
* New automation triggers and actions to allow for Automation rules specific for autoISF

[![Support Server](https://img.shields.io/discord/629952586895851530.svg?label=Discord&logo=Discord&colorB=7289da&style=for-the-badge)](https://discord.gg/4fQUWHZ4Mw)