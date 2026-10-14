# VOID Maps

Private Auto-Navigation für Android auf Basis von OpenStreetMap – im Nothing-Stil der VOID Files App.
Konzept und Entscheidungen: [docs/Uebergabe_Navi_App.md](docs/Uebergabe_Navi_App.md).

## Funktionen
- **Karte:** OpenStreetMap-Vektorkacheln (OpenFreeMap, Schema OpenMapTiles), gerendert mit MapLibre Native, eigener Kartenstil
- **Designs:** *Void* (schwarz, Grautöne, rote Akzente, Dot-Matrix) und *Hell* (Papier-Weiß) – plus *Auto* (folgt dem Dunkelmodus des Systems)
- **Kartenebenen:** Satellitenbild per Knopf auf der Karte (Esri World Imagery, wahlweise TomTom; in Baden-Württemberg und Bayern ab Straßenzoom die amtlichen 20-cm-Orthofotos der Landesvermessung), 3D-Gebäude, Gelände-Relief aus Höhendaten, Ampeln aus OpenStreetMap
- **GPS:** Position mit Fahrtrichtung, Karte dreht in Fahrtrichtung (abschaltbar), 3D-Ansicht in der Navigation
- **Suche:** mit TomTom-Schlüssel schnelle TomTom-Suche, sonst Photon; Treffer aus Favoriten/Verlauf sofort; die 5 zuletzt gesuchten Ziele; Zuhause/Arbeit; langer Druck auf die Karte setzt ein Ziel; `geo:`-Links aus anderen Apps
- **Sonderziele:** Tankstelle, Ladestation, Parkplatz, Rastplatz, Supermarkt, Restaurant, Café, Apotheke, Krankenhaus, Hotel, Werkstatt u. a. – in der Nähe oder während der Navigation entlang der Route
- **Routing:** immer bis zu 3 Routen zur Auswahl, die schnellste ist vorausgewählt und startet nach 5 Sekunden ohne Eingabe; Zwischenziele; Valhalla (OSM) oder TomTom (Live-Verkehr); Maut/Autobahn vermeiden
- **Navigation:** Abbiegehinweise, Fahrspurassistent (zeigt vor Kreuzungen und Ausfahrten, welche Spuren richtig sind – mit TomTom-Routing, auch in Android Auto), Sprachansagen (kurz oder ausführlich, Kurzansagen standardmäßig an), Knopf für Laut / Nur Warnungen / Stumm, Ausrichtung Route oben / Norden oben / Übersicht, Neuberechnung bei Abweichung, regelmäßige Suche nach schnelleren Routen mit Annehmen/Ablehnen, läuft im Hintergrund weiter
- **Ladesäulen:** Tesla, EnBW, Ionity und EWE Go aus OpenStreetMap, einzeln wählbar
- **Offline-Karten:** Bereich auf der Karte wählen und herunterladen (Suche und Routing brauchen weiter Internet)
- **Android Auto:** Karte, Abbiegehinweise und Ankunftszeit im Auto-Display, Zielsuche und Favoriten
- **Verkehr (TomTom):** Verkehrslage als Overlay in den Farben des Designs (oder TomTom-Originalfarben), Unfälle, Stauenden, Baustellen und Sperrungen auf der Karte und als Warnung mit Ansage entlang der Route
- **Blitzer:** feste Anlagen aus OSM (`highway=speed_camera`) und optional feste und mobile Blitzer von Blitzer.de (inoffizielle Schnittstelle der öffentlichen Karte, nur privat), **standardmäßig AUS**, Schalter im Menü. Geladen wird in Kacheln von ca. 20 × 20 km, damit beim Verschieben keine Lücken entstehen.
- **Fortschrittsbalken:** am linken Rand während der Navigation – Blitzer, Staus, Baustellen und Meldungen der nächsten 30 km, Zwischenziele und Ziel Ist er aus, werden keine Blitzerdaten geladen, gezeigt oder angesagt (§ 23 Abs. 1c StVO beachten)
- **Updates:** automatische Prüfung beim Start gegen die GitHub-Releases, Changelog, Download und Installation direkt aus der App

## APK herunterladen
Jeder Push auf `main` baut automatisch eine signierte APK – zu finden unter **Releases** (neueste Version) bzw. im Actions-Lauf als Artifact.
Erstinstallation per Sideload („Unbekannte Apps installieren“ erlauben), danach aktualisiert sich die App selbst.

## TomTom-Schlüssel
Ein Schlüssel ist fest in die App eingebaut (`app/build.gradle.kts`). Achtung: Repository und Release-APKs sind öffentlich,
der Schlüssel ist damit für jeden lesbar. Im Freemium-Tarif ohne hinterlegte Zahlungsdaten entstehen keine Kosten; wird das
Kontingent von anderen verbraucht, im TomTom-Dashboard einen neuen Schlüssel erzeugen und hier tauschen.
Ein in der App unter **Einstellungen → Verkehr · TomTom** eingetragener Schlüssel hat Vorrang. Alternativ kann er als
Repository-Secret `TOMTOM_API_KEY` oder lokal in `local.properties` stehen:

```
tomtom.apiKey=DEIN_SCHLÜSSEL
```

## Bauen
Android Studio öffnen oder `./gradlew assembleRelease` bzw. `./gradlew assembleDebug`.

## Signatur
Standardmäßig wird mit dem Schlüssel in `signing/` signiert, damit Updates sich über die alte Version installieren lassen.
Für einen privaten Schlüssel die Secrets `VOID_KEYSTORE_BASE64`, `VOID_KEYSTORE_PASSWORD`, `VOID_KEY_ALIAS` und `VOID_KEY_PASSWORD` im Repository hinterlegen.

## Stand der Meilensteine
| Meilenstein | Stand |
|---|---|
| M1 Karte, Style, GPS, Fahrtrichtung | umgesetzt |
| M2 Suche und Route | umgesetzt |
| M3 Turn-by-Turn, Ansagen, Neuberechnung, Ankunftszeit | umgesetzt |
| M4 Verkehr (Flow, Incidents, staubewusste Route) | umgesetzt, braucht TomTom-Schlüssel |
| M5 Warnungen und Blitzer-Schalter | umgesetzt |
| M6 Android Auto | umgesetzt (Sideload: in Android Auto die Entwickleroptionen öffnen und „Unbekannte Quellen“ aktivieren) |
| M7 Offline | Karten umgesetzt, Offline-Routing offen |

## Quellen & Lizenzen
Kartendaten © OpenStreetMap-Mitwirkende (ODbL) · Kacheln OpenFreeMap / OpenMapTiles · MapLibre Native (BSD) ·
Valhalla-Server der FOSSGIS · Photon (komoot) · Overpass API · Verkehr © TomTom · Schriften Doto & Space Mono (OFL, siehe `licenses/`).
