# AAPS 4.0.1 Sport Mod – a modified version for active people

> **Warning:** This is a private, experimental version of AAPS. It changes how insulin is
> delivered. It is not tested or supported by the AAPS team. You use it at your own risk.
> For normal use, take the official version: https://github.com/nightscout/AndroidAPS

## Before you install
- Export your settings first.
- Going back to an older version can delete your data.
- The version check is turned off: this build does not expire and does not stop the loop
  when it is old. You must look for updates yourself.
- Engineering mode is always on.

## Changes to the loop (OpenAPS SMB only, not AMA or autoISF)
- **Activity Tracker** (off by default): uses steps from a Garmin or Wear OS watch.
  - With activity, the sensitivity ratio goes down (up to 30 %): less basal, weaker corrections.
  - With inactivity, it goes up (up to 20 %).
  - No inactivity rule from 22:00 to 08:00 or when glucose is below target.
  - Not active while a temp target is running.
- **Ketoacidosis Protection** (off by default): the loop does not set a temp basal below a
  minimum (default 20 % of profile basal, range 10–40 %). This is also true when the loop
  would stop insulin because of low glucose.
- **SMB threshold** (always active, default 100 mg/dl): no SMB below this value.

## Garmin
For watch apps or watch faces that send these requests:
- Steps from the watch.
- Set and cancel temp targets.
- Bolus from the watch (off by default, turn it on in the Garmin settings).

## AI features (your own Anthropic API key, costs money)
- **AI advisor:** looks at your last days and shows hints about patterns. It never changes
  your profile or loop. This is not medical advice.
- **Food help in the bolus wizard:** estimate a meal from text or photos, or scan a barcode
  (Open Food Facts). Always check the numbers.

Data is sent to external services when you use these features. Read the text in the app first.

## Other changes
- 10BE CarbCam can open the bolus wizard with carbs filled in (1–80 g).
- Shorter time texts ("5m" instead of "5m ago") and other loop colors on Wear OS.
- Easier to read script debug output on the OpenAPS tab.

## About AAPS
This is based on AAPS by the Nightscout community.
- Documentation: https://wiki.aaps.app
- Original code: https://github.com/nightscout/AndroidAPS
- Discord: https://discord.gg/4fQUWHZ4Mw
