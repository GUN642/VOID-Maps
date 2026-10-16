package app.voidmaps.map

import app.voidmaps.data.Http
import org.json.JSONObject

/** Colours of one map design. */
data class MapPalette(
    val dark: Boolean,
    val background: String,
    val water: String,
    val waterLine: String,
    val park: String,
    val wood: String,
    val landuse: String,
    val building: String,
    /** Walls of the 3D buildings. */
    val building3d: String,
    val hillShadow: String,
    val hillHighlight: String,
    val minor: String,
    val secondary: String,
    val primary: String,
    val trunk: String,
    val motorway: String,
    /** Road outlines; null draws roads without casing (VOID look). */
    val casing: String?,
    val motorwayCasing: String?,
    val path: String,
    val rail: String,
    val boundary: String,
    val label: String,
    val labelHalo: String,
    val place: String,
    val route: String,
    val routeCasing: String,
    /** Alternative routes in the preview. */
    val routeAlt: String,
    val trafficFree: String?,
    val trafficSlow: String,
    val trafficHeavy: String,
    val trafficStop: String,
    /** Draw traffic as dot rows (Nothing dot-matrix look) instead of solid lines. */
    val trafficDots: Boolean,
    val warn: String,
)

object Palettes {
    val VOID = MapPalette(
        dark = true,
        background = "#000000",
        water = "#0F1D27",
        waterLine = "#1C2E3B",
        park = "#0A130A",
        wood = "#0B150B",
        landuse = "#080808",
        building = "#1C1C1C",
        building3d = "#2C2C2C",
        hillShadow = "#000000",
        hillHighlight = "#363636",
        minor = "#333333",
        secondary = "#474747",
        primary = "#5E5E5E",
        trunk = "#767676",
        motorway = "#9A9A9A",
        casing = null,
        motorwayCasing = null,
        path = "#2C2C2C",
        rail = "#383838",
        boundary = "#555555",
        label = "#C2C2C2",
        labelHalo = "#000000",
        place = "#FFFFFF",
        route = "#D71921",
        routeCasing = "#3A0407",
        routeAlt = "#707070",
        trafficFree = null,
        trafficSlow = "#FFB800",
        trafficHeavy = "#FF5A1F",
        trafficStop = "#FF1F3D",
        trafficDots = true,
        warn = "#D71921",
    )

    val HELL = MapPalette(
        dark = false,
        background = "#F1F1EE",
        water = "#D2DBE2",
        waterLine = "#BFCAD3",
        park = "#E1E7D9",
        wood = "#D8E1CF",
        landuse = "#ECEBE6",
        building = "#E1E0DB",
        building3d = "#E6E5E0",
        hillShadow = "#A9A9A4",
        hillHighlight = "#FFFFFF",
        minor = "#FFFFFF",
        secondary = "#FFFFFF",
        primary = "#FFFFFF",
        trunk = "#F7F7F7",
        motorway = "#DCDCDC",
        casing = "#D3D2CD",
        motorwayCasing = "#B5B5B2",
        path = "#CFCFCA",
        rail = "#C8C8C4",
        boundary = "#B0B0AC",
        label = "#6B6B6B",
        labelHalo = "#F1F1EE",
        place = "#111111",
        route = "#D71921",
        routeCasing = "#FFFFFF",
        routeAlt = "#A3A3A0",
        trafficFree = null,
        trafficSlow = "#F2A900",
        trafficHeavy = "#F25A1F",
        trafficStop = "#B0001E",
        trafficDots = true,
        warn = "#D71921",
    )
}

data class StyleConfig(
    val palette: MapPalette,
    /** TomTom key; traffic layers are only added when it is set and [traffic] is on. */
    val tomtomKey: String,
    val traffic: Boolean,
    val trafficOriginal: Boolean,
    val satellite: Boolean = false,
    val buildings3d: Boolean = true,
    val terrain: Boolean = true,
    /** TomTom imagery instead of Esri (TomTom's is older in many places). */
    val tomtomSatellite: Boolean = false,
    /** Official aerial photos (DOP20) of Baden-Württemberg and Bavaria over the base imagery. */
    val officialImagery: Boolean = true,
    /** Size factor for speed cameras (Android Auto draws them much larger). */
    val cameraScale: Double = 1.0,
    /** Size factor for the other warnings (jams, roadworks, …). */
    val hazardScale: Double = 1.0,
)

fun styleConfigFor(s: app.voidmaps.data.AppSettings, palette: MapPalette) = StyleConfig(
    palette, s.tomtomKey, s.trafficLayer, s.trafficOriginalColors,
    satellite = s.satellite, buildings3d = s.buildings3d, terrain = s.terrain,
    tomtomSatellite = s.satelliteSource == app.voidmaps.data.SatelliteSource.TOMTOM,
    officialImagery = s.officialImagery,
)

/** Builds the MapLibre style JSON for the OpenMapTiles schema (OpenFreeMap tiles, no key needed). */
object MapStyle {
    const val ROUTE = "route"
    const val HAZARDS = "hazards"
    const val HAZARD_LINES = "hazard-lines"
    const val DEST = "dest"
    const val POIS = "pois"
    const val ROUTE_TRAFFIC = "route-traffic"
    const val ROUTE_HEAD = "route-head"
    const val SIGNALS = "signals"
    const val LABEL_ANCHOR = "place-village"

    private const val TILES = "https://tiles.openfreemap.org/planet"
    private const val GLYPHS = "https://tiles.openfreemap.org/fonts/{fontstack}/{range}.pbf"
    private val REGULAR = listOf("Noto Sans Regular")
    private val BOLD = listOf("Noto Sans Bold")
    private val EMPTY = mapOf("type" to "FeatureCollection", "features" to emptyList<Any>())

    private fun zoomExp(vararg stops: Number): List<Any> =
        listOf("interpolate", listOf("exponential", 1.5), listOf("zoom")) + stops.toList()

    private fun cls(vararg values: String): List<Any> =
        if (values.size == 1) listOf("==", listOf("get", "class"), values[0])
        else listOf("match", listOf("get", "class"), values.toList(), true, false)

    private val isLine = listOf("==", listOf("geometry-type"), "LineString")
    private val isPolygon = listOf("==", listOf("geometry-type"), "Polygon")
    private val name = listOf("coalesce", listOf("get", "name:de"), listOf("get", "name_de"), listOf("get", "name"))

    fun build(c: StyleConfig): String {
        // Over satellite imagery labels are white with a dark halo, whatever the design.
        val p = if (c.satellite) c.palette.copy(label = "#F2F2F2", labelHalo = "#000000", place = "#FFFFFF") else c.palette
        val sources = linkedMapOf<String, Any>(
            "omt" to mapOf("type" to "vector", "url" to TILES),
            ROUTE to mapOf("type" to "geojson", "data" to EMPTY, "lineMetrics" to false),
            HAZARDS to mapOf("type" to "geojson", "data" to EMPTY),
            HAZARD_LINES to mapOf("type" to "geojson", "data" to EMPTY),
            DEST to mapOf("type" to "geojson", "data" to EMPTY),
            POIS to mapOf("type" to "geojson", "data" to EMPTY),
            ROUTE_TRAFFIC to mapOf("type" to "geojson", "data" to EMPTY),
            ROUTE_HEAD to mapOf("type" to "geojson", "data" to EMPTY),
            SIGNALS to mapOf("type" to "geojson", "data" to EMPTY),
        )
        if (c.satellite) {
            // Esri World Imagery by default (in Germany largely recent official aerial photos), TomTom on request.
            sources["satellite"] = if (c.tomtomSatellite && c.tomtomKey.isNotBlank()) {
                mapOf(
                    "type" to "raster", "tileSize" to 256, "maxzoom" to 19, "attribution" to "© TomTom",
                    "tiles" to listOf("https://api.tomtom.com/map/1/tile/sat/main/{z}/{x}/{y}.jpg?key=${Http.enc(c.tomtomKey)}"),
                )
            } else {
                mapOf(
                    "type" to "raster", "tileSize" to 256, "maxzoom" to 19, "attribution" to "Esri, Maxar, Earthstar Geographics",
                    "tiles" to listOf("https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}"),
                )
            }
        }
        if (c.satellite && c.officialImagery) {
            // Digital orthophotos, 20 cm, from the surveying offices (open data, transparent outside the state).
            sources["dop-bw"] = mapOf(
                "type" to "raster", "tileSize" to 512, "minzoom" to 12, "maxzoom" to 20,
                "bounds" to listOf(7.4, 47.5, 10.6, 49.85),
                "attribution" to "© LGL-BW, dl-de/by-2-0",
                "tiles" to listOf(
                    "https://owsproxy.lgl-bw.de/owsproxy/ows/WMS_LGL-BW_ATKIS_DOP_20_C?SERVICE=WMS&VERSION=1.3.0&REQUEST=GetMap" +
                        "&LAYERS=IMAGES_DOP_20_RGB&STYLES=&FORMAT=image/png&TRANSPARENT=TRUE&CRS=EPSG:3857" +
                        "&WIDTH=512&HEIGHT=512&BBOX={bbox-epsg-3857}",
                ),
            )
            sources["dop-by"] = mapOf(
                "type" to "raster", "tileSize" to 512, "minzoom" to 12, "maxzoom" to 20,
                "bounds" to listOf(8.95, 47.25, 13.9, 50.6),
                "attribution" to "© Bayerische Vermessungsverwaltung, CC BY 4.0",
                "tiles" to listOf(
                    "https://geoservices.bayern.de/od/wms/dop/v1/dop20?SERVICE=WMS&VERSION=1.1.1&REQUEST=GetMap" +
                        "&LAYERS=by_dop20c&STYLES=&FORMAT=image/png&TRANSPARENT=TRUE&SRS=EPSG:3857" +
                        "&WIDTH=512&HEIGHT=512&BBOX={bbox-epsg-3857}",
                ),
            )
        }
        val relief = c.terrain && !c.satellite
        if (relief) {
            // AWS Terrain Tiles (public dataset, Terrarium encoding).
            sources["dem"] = mapOf(
                "type" to "raster-dem", "encoding" to "terrarium", "tileSize" to 256, "maxzoom" to 14,
                "tiles" to listOf("https://s3.amazonaws.com/elevation-tiles-prod/terrarium/{z}/{x}/{y}.png"),
                "attribution" to "Terrain Tiles: Mapzen, AWS",
            )
        }
        val showTraffic = c.traffic && c.tomtomKey.isNotBlank()
        if (showTraffic) {
            val key = Http.enc(c.tomtomKey)
            sources["traffic"] = if (c.trafficOriginal) {
                val style = if (p.dark) "relative0-dark" else "relative0"
                mapOf(
                    "type" to "raster",
                    "tiles" to listOf("https://api.tomtom.com/traffic/map/4/tile/flow/$style/{z}/{x}/{y}.png?key=$key&tileSize=256"),
                    "tileSize" to 256,
                    "minzoom" to 6,
                    "maxzoom" to 18,
                    "attribution" to "© TomTom",
                )
            } else {
                mapOf(
                    "type" to "vector",
                    "tiles" to listOf("https://api.tomtom.com/traffic/map/4/tile/flow/relative/{z}/{x}/{y}.pbf?key=$key"),
                    "minzoom" to 6,
                    "maxzoom" to 18,
                    "attribution" to "© TomTom",
                )
            }
        }

        val layers = ArrayList<Map<String, Any>>()
        layers += mapOf("id" to "background", "type" to "background", "paint" to mapOf("background-color" to p.background))
        if (c.satellite) {
            layers += mapOf("id" to "satellite", "type" to "raster", "source" to "satellite", "paint" to mapOf("raster-fade-duration" to 150))
            if (c.officialImagery) {
                layers += mapOf("id" to "dop-bw", "type" to "raster", "source" to "dop-bw", "minzoom" to 12, "paint" to mapOf("raster-fade-duration" to 150))
                layers += mapOf("id" to "dop-by", "type" to "raster", "source" to "dop-by", "minzoom" to 12, "paint" to mapOf("raster-fade-duration" to 150))
            }
        } else {
            layers += fill("landuse", "landuse", p.landuse, cls("residential", "suburb", "neighbourhood", "commercial", "industrial", "retail"), minzoom = 9)
            layers += fill("landcover-wood", "landcover", p.wood, cls("wood", "forest"))
            layers += fill("landcover-grass", "landcover", p.park, cls("grass", "wetland"))
            layers += fill("park", "park", p.park, null)
            if (relief) {
                layers += mapOf(
                    "id" to "relief", "type" to "hillshade", "source" to "dem",
                    "paint" to mapOf(
                        "hillshade-shadow-color" to p.hillShadow,
                        "hillshade-highlight-color" to p.hillHighlight,
                        "hillshade-accent-color" to p.hillShadow,
                        "hillshade-exaggeration" to if (p.dark) 0.45 else 0.3,
                    ),
                )
            }
            layers += fill("water", "water", p.water, null)
            layers += mapOf(
                "id" to "waterway", "type" to "line", "source" to "omt", "source-layer" to "waterway", "minzoom" to 8,
                "paint" to mapOf("line-color" to p.waterLine, "line-width" to zoomExp(8, 0.5, 14, 2, 18, 6)),
            )
            if (!c.buildings3d) layers += fill("building", "building", p.building, null, minzoom = 14)
        }

        // Roads: casings first, then fills, ordered from minor to major so motorways stay on top.
        val roads = listOf(
            Triple("minor", cls("minor", "service"), p.minor) to zoomExp(12, 0.5, 14, 2.5, 16, 7, 18, 18),
            Triple("tertiary", cls("tertiary"), p.secondary) to zoomExp(9, 0.4, 12, 1.2, 14, 3.5, 16, 9, 18, 22),
            Triple("secondary", cls("secondary"), p.secondary) to zoomExp(8, 0.5, 12, 1.6, 14, 4.5, 16, 11, 18, 26),
            Triple("primary", cls("primary"), p.primary) to zoomExp(7, 0.5, 10, 1.4, 12, 2.4, 14, 5.5, 16, 13, 18, 30),
            Triple("trunk", cls("trunk"), p.trunk) to zoomExp(5, 0.5, 9, 1.4, 12, 3, 14, 6.5, 16, 15, 18, 34),
            Triple("motorway", cls("motorway"), p.motorway) to zoomExp(4, 0.6, 8, 1.4, 12, 3.4, 14, 7, 16, 16, 18, 36),
        )
        val minZoom = mapOf("minor" to 12, "tertiary" to 9, "secondary" to 8, "primary" to 7, "trunk" to 5, "motorway" to 4)
        val roadOpacity = if (c.satellite) 0.55 else 1.0
        if (p.casing != null && !c.satellite) {
            for ((t, width) in roads) {
                val (id, filter, _) = t
                val color = if (id == "motorway" || id == "trunk") p.motorwayCasing ?: p.casing else p.casing
                layers += mapOf(
                    "id" to "road-$id-casing", "type" to "line", "source" to "omt", "source-layer" to "transportation",
                    "minzoom" to (minZoom[id] ?: 0) + 1,
                    "filter" to listOf("all", isLine, filter),
                    "layout" to mapOf("line-cap" to "round", "line-join" to "round"),
                    "paint" to mapOf("line-color" to color, "line-width" to scaleWidth(width, 1.5, 1.15)),
                )
            }
        }
        layers += mapOf(
            "id" to "road-path", "type" to "line", "source" to "omt", "source-layer" to "transportation", "minzoom" to 14,
            "filter" to listOf("all", isLine, cls("path", "track")),
            "paint" to mapOf("line-color" to p.path, "line-width" to zoomExp(14, 0.8, 18, 2.5), "line-dasharray" to listOf(2, 1.5)),
        )
        for ((t, width) in roads) {
            val (id, filter, color) = t
            layers += mapOf(
                "id" to "road-$id", "type" to "line", "source" to "omt", "source-layer" to "transportation",
                "minzoom" to (minZoom[id] ?: 0),
                "filter" to listOf("all", isLine, filter),
                "layout" to mapOf("line-cap" to "round", "line-join" to "round"),
                "paint" to mapOf("line-color" to color, "line-width" to width, "line-opacity" to roadOpacity),
            )
        }
        layers += mapOf(
            "id" to "rail", "type" to "line", "source" to "omt", "source-layer" to "transportation", "minzoom" to 11,
            "filter" to listOf("all", isLine, cls("rail", "transit")),
            "paint" to mapOf("line-color" to p.rail, "line-width" to zoomExp(11, 0.6, 18, 2.4), "line-dasharray" to listOf(3, 2)),
        )
        layers += mapOf(
            "id" to "boundary", "type" to "line", "source" to "omt", "source-layer" to "boundary",
            "filter" to listOf("all", listOf("<=", listOf("get", "admin_level"), 4), listOf("!=", listOf("get", "maritime"), 1)),
            "paint" to mapOf("line-color" to p.boundary, "line-width" to zoomExp(3, 0.6, 10, 1.4), "line-dasharray" to listOf(1, 2)),
        )

        if (c.buildings3d && !c.satellite) {
            layers += mapOf(
                "id" to "building-3d", "type" to "fill-extrusion", "source" to "omt", "source-layer" to "building", "minzoom" to 14,
                "filter" to isPolygon,
                "paint" to mapOf(
                    "fill-extrusion-color" to p.building3d,
                    "fill-extrusion-height" to listOf("interpolate", listOf("linear"), listOf("zoom"), 14, 0, 15, listOf("coalesce", listOf("get", "render_height"), 6)),
                    "fill-extrusion-base" to listOf("coalesce", listOf("get", "render_min_height"), 0),
                    "fill-extrusion-opacity" to 0.88,
                ),
            )
        }

        if (showTraffic) layers += trafficLayer(p, c.trafficOriginal)

        // Route and hazards sit above the roads but below the labels. Alternatives (sel = 0) are drawn first.
        val alt = listOf("==", listOf("get", "sel"), 0)
        val sel = listOf("==", listOf("get", "sel"), 1)
        layers += mapOf(
            "id" to "route-alt-casing", "type" to "line", "source" to ROUTE, "filter" to alt,
            "layout" to mapOf("line-cap" to "round", "line-join" to "round"),
            "paint" to mapOf("line-color" to p.background, "line-width" to zoomExp(5, 4, 12, 8, 16, 14, 18, 24)),
        )
        layers += mapOf(
            "id" to "route-alt", "type" to "line", "source" to ROUTE, "filter" to alt,
            "layout" to mapOf("line-cap" to "round", "line-join" to "round"),
            "paint" to mapOf("line-color" to p.routeAlt, "line-width" to zoomExp(5, 2.5, 12, 5, 16, 9, 18, 16)),
        )
        layers += mapOf(
            "id" to "route-casing", "type" to "line", "source" to ROUTE, "filter" to sel,
            "layout" to mapOf("line-cap" to "round", "line-join" to "round"),
            "paint" to mapOf("line-color" to p.routeCasing, "line-width" to zoomExp(5, 5, 12, 9, 16, 16, 18, 28)),
        )
        // Short piece from the (smoothed) position to the rest of the route, updated ~20 times a second.
        layers += mapOf(
            "id" to "route-head-casing", "type" to "line", "source" to ROUTE_HEAD,
            "layout" to mapOf("line-cap" to "round", "line-join" to "round"),
            "paint" to mapOf("line-color" to p.routeCasing, "line-width" to zoomExp(5, 5, 12, 9, 16, 16, 18, 28)),
        )
        layers += mapOf(
            "id" to "route-line", "type" to "line", "source" to ROUTE, "filter" to sel,
            "layout" to mapOf("line-cap" to "round", "line-join" to "round"),
            "paint" to mapOf("line-color" to p.route, "line-width" to zoomExp(5, 3, 12, 6, 16, 11, 18, 20)),
        )
        layers += mapOf(
            "id" to "route-head-line", "type" to "line", "source" to ROUTE_HEAD,
            "layout" to mapOf("line-cap" to "round", "line-join" to "round"),
            "paint" to mapOf("line-color" to p.route, "line-width" to zoomExp(5, 3, 12, 6, 16, 11, 18, 20)),
        )
        // Jams, roadworks and closures as a dotted line right beside the route (the red route stays readable).
        layers += mapOf(
            "id" to "route-traffic", "type" to "line", "source" to ROUTE_TRAFFIC,
            "layout" to mapOf("line-cap" to "round", "line-join" to "round"),
            "paint" to mapOf(
                "line-color" to listOf(
                    "match", listOf("get", "level"),
                    "1", p.trafficSlow, "2", p.trafficHeavy, "works", "#FFB800", p.trafficStop,
                ),
                "line-width" to zoomExp(5, 3, 12, 5, 16, 8, 18, 12),
                "line-offset" to zoomExp(5, 5, 12, 9, 16, 15, 18, 24),
                "line-dasharray" to listOf(0.01, 1.8),
            ),
        )
        layers += mapOf(
            "id" to "hazard-lines", "type" to "line", "source" to HAZARD_LINES,
            "layout" to mapOf("line-cap" to "round", "line-join" to "round"),
            "paint" to mapOf(
                "line-color" to p.warn, "line-opacity" to 0.85,
                "line-width" to zoomExp(8, 2, 14, 4, 18, 8),
                "line-offset" to zoomExp(5, 5, 12, 9, 16, 15, 18, 24),
                "line-dasharray" to listOf(0.1, 2.2),
            ),
        )

        layers += mapOf(
            "id" to "road-label", "type" to "symbol", "source" to "omt", "source-layer" to "transportation_name", "minzoom" to 13,
            "filter" to isLine,
            "layout" to mapOf(
                "symbol-placement" to "line", "text-field" to name, "text-font" to REGULAR,
                "text-size" to zoomExp(13, 10, 18, 14), "text-letter-spacing" to 0.04,
            ),
            "paint" to mapOf("text-color" to p.label, "text-halo-color" to p.labelHalo, "text-halo-width" to 1.4),
        )
        layers += mapOf(
            "id" to "road-ref", "type" to "symbol", "source" to "omt", "source-layer" to "transportation_name",
            "minzoom" to 8, "maxzoom" to 14,
            "filter" to listOf("all", isLine, cls("motorway", "trunk"), listOf("has", "ref")),
            "layout" to mapOf(
                "symbol-placement" to "line", "symbol-spacing" to 500, "text-field" to listOf("get", "ref"),
                "text-font" to BOLD, "text-size" to 10, "text-rotation-alignment" to "viewport",
            ),
            "paint" to mapOf("text-color" to p.place, "text-halo-color" to p.labelHalo, "text-halo-width" to 2),
        )
        layers += placeLabel("place-suburb", cls("suburb", "quarter", "neighbourhood"), 12, 11, 15, p.label, p.labelHalo, REGULAR, false)
        layers += placeLabel(LABEL_ANCHOR, cls("village", "hamlet"), 11, 11, 15, p.label, p.labelHalo, REGULAR, false)
        layers += placeLabel("place-town", cls("town"), 8, 11, 17, p.place, p.labelHalo, REGULAR, true)
        layers += placeLabel("place-city", cls("city"), 4, 12, 22, p.place, p.labelHalo, BOLD, true)

        layers += mapOf(
            "id" to "signal-icons", "type" to "symbol", "source" to SIGNALS, "minzoom" to 14,
            "layout" to mapOf(
                "icon-image" to "signal",
                "icon-allow-overlap" to true,
                "icon-size" to listOf("interpolate", listOf("linear"), listOf("zoom"), 14, 0.6, 17, 1.0),
            ),
        )
        layers += mapOf(
            "id" to "hazard-icons", "type" to "symbol", "source" to HAZARDS,
            "layout" to mapOf(
                "icon-image" to listOf("get", "icon"),
                "icon-allow-overlap" to true,
                "icon-ignore-placement" to true,
                "icon-size" to run {
                    val f = listOf("case", listOf("==", listOf("get", "rank"), 1), c.cameraScale, c.hazardScale)
                    listOf("interpolate", listOf("linear"), listOf("zoom"), 8, listOf("*", 0.6, f), 14, listOf("*", 1.0, f))
                },
                "symbol-sort-key" to listOf("get", "rank"),
            ),
        )
        layers += mapOf(
            "id" to "poi-icons", "type" to "symbol", "source" to POIS, "minzoom" to 7,
            "layout" to mapOf(
                "icon-image" to listOf("get", "icon"),
                "icon-allow-overlap" to true,
                "icon-size" to listOf("interpolate", listOf("linear"), listOf("zoom"), 7, 0.55, 13, 1.0),
            ),
        )
        layers += mapOf(
            "id" to "dest-icon", "type" to "symbol", "source" to DEST,
            "layout" to mapOf("icon-image" to listOf("get", "icon"), "icon-allow-overlap" to true, "icon-ignore-placement" to true, "icon-anchor" to "center"),
        )

        val style = mapOf(
            "version" to 8,
            "name" to "VOID",
            "glyphs" to GLYPHS,
            "sources" to sources,
            "layers" to layers,
        )
        return JSONObject(style).toString()
    }

    /**
     * Minimal style used as the definition of offline downloads: it references the same tile source and
     * fonts as the real styles, so downloaded tiles and glyphs are found again by every design.
     */
    fun offlineStyle(): String = JSONObject(
        mapOf(
            "version" to 8,
            "name" to "VOID offline",
            "glyphs" to GLYPHS,
            "sources" to mapOf("omt" to mapOf("type" to "vector", "url" to TILES)),
            "layers" to listOf(
                mapOf("id" to "background", "type" to "background", "paint" to mapOf("background-color" to "#000000")),
                mapOf("id" to "water", "type" to "fill", "source" to "omt", "source-layer" to "water"),
                mapOf(
                    "id" to "labels-regular", "type" to "symbol", "source" to "omt", "source-layer" to "place",
                    "layout" to mapOf("text-field" to name, "text-font" to REGULAR),
                ),
                mapOf(
                    "id" to "labels-bold", "type" to "symbol", "source" to "omt", "source-layer" to "transportation_name",
                    "layout" to mapOf("text-field" to listOf("get", "ref"), "text-font" to BOLD),
                ),
            ),
        ),
    ).toString()

    /** Widens the stop values of a zoom interpolation (used for road casings). */
    private fun scaleWidth(expr: List<Any>, add: Double, factor: Double): List<Any> {
        val head = expr.take(3)
        val stops = expr.drop(3)
        val out = ArrayList<Any>(head)
        stops.forEachIndexed { i, v ->
            out += if (i % 2 == 1) (v as Number).toDouble() * factor + add else v
        }
        return out
    }

    private fun fill(id: String, layer: String, color: String, filter: Any?, minzoom: Int = 0): Map<String, Any> {
        val m = linkedMapOf<String, Any>(
            "id" to id, "type" to "fill", "source" to "omt", "source-layer" to layer, "minzoom" to minzoom,
            "paint" to mapOf("fill-color" to color, "fill-antialias" to false),
        )
        m["filter"] = if (filter != null) listOf("all", isPolygon, filter) else isPolygon
        return m
    }

    private fun placeLabel(
        id: String, filter: Any, minzoom: Int, minSize: Int, maxSize: Int, color: String, halo: String, font: List<String>, upper: Boolean,
    ): Map<String, Any> = mapOf(
        "id" to id, "type" to "symbol", "source" to "omt", "source-layer" to "place", "minzoom" to minzoom,
        "filter" to filter,
        "layout" to mapOf(
            "text-field" to name,
            "text-font" to font,
            "text-size" to listOf("interpolate", listOf("linear"), listOf("zoom"), minzoom, minSize, minzoom + 8, maxSize),
            "text-transform" to if (upper) "uppercase" else "none",
            "text-letter-spacing" to if (upper) 0.12 else 0.02,
            "text-max-width" to 8,
        ),
        "paint" to mapOf("text-color" to color, "text-halo-color" to halo, "text-halo-width" to 1.5),
    )

    private fun trafficLayer(p: MapPalette, original: Boolean): Map<String, Any> {
        if (original) {
            return mapOf("id" to "traffic", "type" to "raster", "source" to "traffic", "paint" to mapOf("raster-opacity" to 0.9))
        }
        // traffic_level: current speed relative to free flow (0 = standing, 1 = free).
        val level = listOf("get", "traffic_level")
        val colorStops = mutableListOf<Any>("interpolate", listOf("linear"), level, 0.0, p.trafficStop, 0.35, p.trafficHeavy, 0.65, p.trafficSlow)
        colorStops.add(0.85)
        colorStops.add(p.trafficFree ?: p.trafficSlow)
        val paint = linkedMapOf<String, Any>(
            "line-color" to colorStops,
            "line-width" to zoomExp(6, 1.5, 10, 2.5, 14, 4.5, 18, 9),
            "line-opacity" to 0.95,
        )
        if (p.trafficDots) paint["line-dasharray"] = listOf(0.01, 2.0)
        val layer = linkedMapOf<String, Any>(
            "id" to "traffic", "type" to "line", "source" to "traffic", "source-layer" to "Traffic flow",
            "layout" to mapOf("line-cap" to "round", "line-join" to "round"),
            "paint" to paint,
        )
        // Minimal designs only show congestion; the colourful one shows free flow in green too.
        if (p.trafficFree == null) layer["filter"] = listOf("<", level, 0.85)
        return layer
    }
}
