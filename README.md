# VOID Maps

Private Auto-Navigation für Android auf Basis von OpenStreetMap – im Nothing-Stil der VOID Files App.
Konzept und Entscheidungen: [docs/Uebergabe_Navi_App.md](docs/Uebergabe_Navi_App.md).

## Funktionen
- **Karte:** OpenStreetMap-Vektorkacheln (OpenFreeMap, Schema OpenMapTiles), gerendert mit MapLibre Native, eigener Kartenstil
- **Drei Designs:** *Void* (schwarz, Grautöne, rote Akzente, Dot-Matrix), *Hell* (Papier-Weiß) und *Farbe* (farbige Karte, violette Route) – plus *Auto* (folgt dem Dunkelmodus des Systems)
- **GPS:** Position mit Fahrtrichtung, Karte dreht in Fahrtrichtung (abschaltbar), 3D-Ansicht in der Navigation
- **Suche:** Adressen, Orte und Koordinaten über Photon; Zuhause/Arbeit, Verlauf; langer Druck auf die Karte setzt ein Ziel; `geo:`-Links aus anderen Apps
- **Routing:** Valhalla (OSM, ohne Schlüssel) oder TomTom (mit Live-Verkehr); Maut/Autobahn vermeiden
- **Navigation:** Abbiegehinweise, deutsche Sprachansagen (Musik wird leiser), Neuberechnung bei Abweichung, Ankunftszeit, Hinweis auf schnellere Route bei Stau, läuft im Hintergrund weiter
- **Verkehr (TomTom):** Verkehrslage als Overlay in den Farben des Designs (oder TomTom-Originalfarben), Unfälle, Stauenden, Baustellen und Sperrungen auf der Karte und als Warnung mit Ansage entlang der Route
- **Blitzer:** feste Anlagen aus OSM (`highway=speed_camera`), **standardmäßig AUS**, Schalter in den Einstellungen und als Schnellschalter auf der Karte. Ist er aus, werden keine Blitzerdaten geladen, gezeigt oder angesagt (§ 23 Abs. 1c StVO beachten)
- **Updates:** automatische Prüfung beim Start gegen die GitHub-Releases, Changelog, Download und Installation direkt aus der App

## APK herunterladen
Jeder Push auf `main` baut automatisch eine signierte APK – zu finden unter **Releases** (neueste Version) bzw. im Actions-Lauf als Artifact.
Erstinstallation per Sideload („Unbekannte Apps installieren“ erlauben), danach aktualisiert sich die App selbst.

## TomTom-Schlüssel
Kostenlos auf developer.tomtom.com anlegen (Freemium: 50.000 Kachel- und 2.500 sonstige Anfragen pro Tag) und in der App unter
**Einstellungen → Verkehr · TomTom** eintragen. Der Schlüssel wird nur auf dem Gerät gespeichert.
Weil die Release-APKs öffentlich sind, wird **kein** Schlüssel in CI-Builds eingebaut. Für eigene lokale Builds kann er optional in
`local.properties` stehen (wird nie committet):

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
| M6 Android Auto | offen |
| M7 Offline | offen |

## Quellen & Lizenzen
Kartendaten © OpenStreetMap-Mitwirkende (ODbL) · Kacheln OpenFreeMap / OpenMapTiles · MapLibre Native (BSD) ·
Valhalla-Server der FOSSGIS · Photon (komoot) · Overpass API · Verkehr © TomTom · Schriften Doto & Space Mono (OFL, siehe `licenses/`).
