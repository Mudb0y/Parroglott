# Parroglott

## What is this?

An Android text-to-speech engine that speaks with the Ivona voices Amazon ships for its Fire tablets: the classic Ivona voices plenty of us grew up with, like Jacek, Ewa, Maja, Joey and Brian, and the small neural voices Amazon made for VoiceView in 2025, like Ola, Salli and Amy. That's 60 voices in 29 languages. It works with TalkBack, its forks such as Sandfly, and anything else that uses Android's speech settings.

## Install

* Grab the APK from releases and install it.
* Open Parroglott, pick a language, pick a voice and download it. Voices come straight from Amazon's own servers, the same files a Fire tablet downloads.
* Once you have a voice, set Parroglott as the preferred engine under Settings, Accessibility, Text-to-speech output. The exact path differs between phones. Download a voice before switching, or your screen reader has nothing to speak with.

The first voice you download for a language becomes its default. Pick another from the language's list with "Use it for".

## Voices

Neural voices are marked as such in the app; everything else is a classic Ivona voice.

* Arabic: Sara
* Czech: Jitka
* Danish: Mads, Naja
* Dutch: Laura, Lotte, Ruben
* English, Australia: Nicole, Olivia, Russell
* English, India: Kajal, Raveena
* English, Ireland: Niamh
* English, New Zealand: Nicole
* English, UK: Amy, Brian
* English, US: Joey, Salli
* Finnish: Suvi
* French, Canada: Chantal, Gabrielle
* French, France: Céline, Lea, Mathieu
* German: Hans, Marlene, Vicki
* Hindi: Aditi
* Icelandic: Dóra, Karl
* Italian: Bianca, Carla, Giorgio
* Japanese: Mizuki, Tomoko
* Norwegian: Liv
* Polish: Agnieszka, Ewa, Jacek, Jan, Maja, Ola
* Portuguese, Brazil: Ricardo, Vitória
* Portuguese, Portugal: Cristiano, Inês
* Romanian: Carmen
* Russian: Maxim, Tatyana
* Spanish, Mexico: Mia
* Spanish, Spain: Conchita, Enrique, Lucia
* Spanish, US: Lupe, Miguel, Penélope
* Swedish: Astrid, Elin
* Turkish: Filiz
* Welsh: Geraint, Gwyneth

## Good to know

* It needs a 64-bit ARM phone, which is anything from the last several years.
* The first time a voice speaks after the phone starts, it takes a second or so to load. Parroglott keeps the three voices you used last loaded, so a screen reader hopping between languages doesn't wait again.
* Ivona's own speed control stops getting faster somewhere around three times normal speed. Above 400 percent, Parroglott speeds the audio up itself with Sonic, so the rate slider keeps working all the way up.
* Apps that follow along word by word, such as readers that highlight text, get word positions.
* Amazon built its code for phones with 4 KB memory pages. On phones with 16 KB pages, Parroglott asks Android to run it in its compatibility mode, but nobody has tried that on a real 16 KB phone yet. If you have one, tell us whether it speaks.
* If Parroglott ever stops unexpectedly, the app shows a row offering a report you can share. It stays on the phone unless you share it.
* Updates come from this repository. The Updates row in the app checks once a day, on the stable channel (tagged releases) or the development channel (every change).

## How it works

The speech engine is Amazon's own, taken from the public Fire OS 8.3.3.8 update for the Fire HD 10. It goes into the APK unchanged, and on the phone a copy is rewritten to look for voices in Parroglott's storage instead of a Fire tablet's. The voices download from the bucket that the same firmware's catalogue points Fire tablets at. Nothing of Amazon's lives in this repository; the build fetches the engine from Amazon's download page.

Amazon's engine can only hold one voice per process, so Parroglott runs three small engine processes, each keeping a voice loaded. If Amazon's code ever crashes, it takes down one of those processes and one sentence, not your screen reader's speech.

Parroglott has nothing to do with Amazon. Amazon, Fire and Ivona are Amazon's names.

## Building

You need Nix with flakes.

```
nix develop
scripts/fetch-firmware.sh
scripts/stage-android.sh
cd android && gradle assembleDebug
```

`fetch-firmware.sh` reads only the part of Amazon's update it needs, about 580 MB of 1.3 GB, and checks what it gets against pinned hashes. `nix develop .#emulator` gives a headless Android emulator that runs the arm64 engine through ARM translation; `scripts/device-selftest.sh` and `scripts/sweep-voices.sh` drive a debug build on it or on a phone.
