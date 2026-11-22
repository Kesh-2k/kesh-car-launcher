# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses [Semantic Versioning](https://semver.org/).

## [1.0.0] - 2026-11-22

First public release.

### Added
- Home screen built for landscape head units (1280×720): sidebar with clock, date and six app shortcuts, a live
  OpenStreetMap map in the middle and quick tiles + now-playing card on the right.
- Map that follows the GPS with heading-up rotation, speed bubble, "re-center" button and automatic night mode
  (18:00–06:00); the last position is remembered so the map shows up instantly when the car starts.
- Current street and city name: offline from an optional `streets.bin` (build it with `tools/build-streets.py`,
  import it by long-pressing the street bar), otherwise online through the system geocoder or Nominatim (rate limited).
- Now-playing card for any app that publishes a MediaSession (Spotify, YouTube Music, radio apps...), with
  artwork, progress and controls; remembers the last track and can resume it when the car starts.
- Quick tiles for Wi-Fi, Bluetooth (connected device) and weather from Open-Meteo.
- "My car" page (swipe the map sideways): car name, details and photo of your choice (a generic illustration by
  default) plus GPS trip stats — distance, driving time, top speed and total distance.
- Shortcut picker (long-press a sidebar icon) and full app drawer.
- Smart default shortcuts: Waze, Google Maps, YouTube, Spotify, plus the head unit's own radio and phone apps when
  they exist (Jancar, FYT/SYU, MTCD) and the stock dialer otherwise.
- Comes back to the front by itself on Autochips/Jancar units after power-on.
- Asks for location on first start; the music card links to the notification access screen until it is granted.
- English and Brazilian Portuguese translations.
