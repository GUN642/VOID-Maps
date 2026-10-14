# Übergabe: Private Navigations-App für Android

Arbeitstitel: **VOID Nav**
Status: Machbarkeit geprüft, Konzept abgestimmt. Noch kein Code.
Nutzung: Ausschließlich privat. Kein Store-Release geplant, Installation per Sideload.

## Ziel

Eine eigene Auto-Navigations-App für Android auf Basis von OpenStreetMap. App und Karte komplett im dunklen Nothing-Stil wie die bestehende **VOID Files App**. Live-Verkehrsdaten von TomTom. Warnungen vor Verkehrsereignissen sowie eine per Schalter aktivierbare Blitzer-Ebene.

## Zielgeräte

| Gerät | Rolle |
|---|---|
| Samsung Galaxy S25 Ultra | Hauptgerät |
| VW ID.5 GTX (Android Auto) | Anzeige im Fahrzeug, späterer Ausbauschritt |
| MacBook | Entwicklungsrechner (Android Studio) |

## Getroffene Entscheidungen

1. **Kartenbasis:** OpenStreetMap-Vektorkacheln, gerendert mit MapLibre Native Android.
2. **Design:** Eigener MapLibre-Style (JSON) im VOID Files Look. Schwarzer Hintergrund, Straßen in Grautönen, sparsame rote Akzente, Punktmatrix-Typografie. Die übrige App-Oberfläche im selben Stil.
3. **Verkehrsdaten:** TomTom. Google Maps ist ausgeschlossen, da die Google-Nutzungsbedingungen die Verwendung von Google-Inhalten zusammen mit einer Nicht-Google-Karte verbieten.
4. **Blitzer:** Implementieren, aber nur über einen Schalter aktivierbar. Standard ist AUS.
5. **Sonstige Warnungen** (Unfälle, Baustellen, Stauenden, Sperrungen) sind immer verfügbar.

## Empfohlener Tech-Stack

Vor der Umsetzung bitte aktuelle Doku, Versionen und Lizenzen prüfen.

| Bereich | Vorschlag |
|---|---|
| Sprache / UI | Kotlin, Jetpack Compose |
| Kartendarstellung | MapLibre Native Android |
| Kartenkacheln | OpenMapTiles-Schema. Quelle z. B. OpenFreeMap oder eigenes PMTiles-Extrakt (Deutschland) für Offline-Nutzung. Nicht die Standard-Kacheln von tile.openstreetmap.org verwenden (Nutzungsrichtlinie). |
| Routing | Valhalla oder GraphHopper. Alternativ TomTom Routing API für staubewusste Routen. |
| Navigationslogik | Ferrostar (Open Source Turn-by-Turn-Kern für MapLibre) als Option prüfen |
| Sprachansagen | Android TextToSpeech, Deutsch |
| Verkehr | TomTom Traffic Flow Tiles (Overlay) und Traffic Incidents API |
| Android Auto | Car App Library, NavigationTemplate |

## TomTom

Freemium-Tarif reicht für private Nutzung: täglich 50.000 Tile-Anfragen und 2.500 Nicht-Tile-Anfragen kostenlos.

1. Traffic Flow Tiles als farbiges Overlay auf der OSM-Karte (grün, gelb, rot passend ins Farbschema übersetzen).
2. Traffic Incidents API für Warnungen entlang der Route.
3. Optional TomTom Routing API, damit Staus in Route und Ankunftszeit einfließen.
4. Lizenzbedingungen für die Kombination TomTom-Daten auf OSM-Karte vor Start prüfen.
5. API-Key in `local.properties`, niemals ins Repo committen.

## Blitzer-Funktion

**Rechtlicher Hintergrund:** § 23 Abs. 1c StVO verbietet dem Fahrer in Deutschland, während der Fahrt ein Gerät zu betreiben oder betriebsbereit mitzuführen, das Verkehrsüberwachung anzeigt. Das gilt ausdrücklich auch für Smartphone-Apps und Navis. Bußgeld 75 Euro und ein Punkt.

**Umsetzung:**

1. Eigener Schalter "Blitzer anzeigen" in den Einstellungen und als Schnellschalter in der Kartenansicht.
2. Standardwert AUS. Zustand wird gespeichert.
3. Ist der Schalter aus, werden Blitzerdaten weder angezeigt noch angesagt.
4. Optional später: automatische Deaktivierung, sobald die Position in Deutschland liegt.
5. Datenquellen: feste Anlagen aus OSM (`highway=speed_camera`), z. B. per Overpass oder aus dem Kartenextrakt. Optional kostenpflichtige Datenbank wie SCDB importieren.

## Meilensteine

1. **M1 Karte:** App-Gerüst, MapLibre mit eigenem VOID-Style, GPS-Position, Kartenausrichtung in Fahrtrichtung.
2. **M2 Suche und Route:** Adresssuche (z. B. Nominatim oder Photon), Routenberechnung, Routenlinie.
3. **M3 Navigation:** Turn-by-Turn, Sprachansagen, Neuberechnung bei Abweichung, Ankunftszeit.
4. **M4 Verkehr:** TomTom Flow Overlay, Incidents entlang der Route, staubewusste Route.
5. **M5 Warnungen und Blitzer:** Warnhinweise im UI, Blitzer-Ebene mit Schalter.
6. **M6 Android Auto:** Darstellung im ID.5 über die Car App Library. Für die private App in Android Auto die Entwickleroptionen aktivieren (unbekannte Quellen).
7. **M7 Offline (optional):** Kacheln und Routing für Deutschland offline auf dem Gerät.

## Offene Punkte

1. **VOID Files Design:** Repo oder Screenshots der VOID Files App beilegen, damit Farben, Schrift, Abstände und Komponenten exakt übernommen werden.
2. Endgültiger App-Name.
3. Online- oder Offline-Karten als Standard.
4. TomTom Developer Account anlegen und API-Key erzeugen.
5. Routing-Engine festlegen: eigene Engine (Valhalla/GraphHopper) oder TomTom Routing.

## Hinweis für Claude Code

Bitte mit M1 beginnen und nach jedem Meilenstein eine installierbare Debug-APK für das S25 Ultra erzeugen. Design-Vorgaben aus der VOID Files App haben Vorrang vor generischen Material-Defaults.
