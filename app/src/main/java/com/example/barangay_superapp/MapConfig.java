package com.example.barangay_superapp;

import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase;
import org.osmdroid.util.MapTileIndex;

/**
 * Map tiles for the osmdroid MapView.
 *
 * CARTO basemaps now require an API key on every tile request
 * (https://carto.com/basemaps/apikey): https://basemaps.cartocdn.com/rastertiles/voyager/{z}/{x}/{y}.png?key=KEY
 * Without it every tile is an "API KEY REQUIRED" image.
 * Attribution "(c) OpenStreetMap contributors, (c) CARTO" must stay visible on the map.
 */
public final class MapConfig {
    private MapConfig() {}

    public static final String CARTO_API_KEY = "cb1_42nw_1_c5daf23a0f9ab0a9354a9996";

    public static final String ATTRIBUTION = "© OpenStreetMap contributors, © CARTO";

    /** CARTO Voyager raster tiles with the API key appended to each tile URL. */
    public static OnlineTileSourceBase cartoVoyager() {
        return new OnlineTileSourceBase(
                "CartoVoyagerKey", 0, 20, 256, ".png",
                new String[]{"https://basemaps.cartocdn.com/rastertiles/voyager/"},
                ATTRIBUTION) {
            @Override
            public String getTileURLString(long pMapTileIndex) {
                return getBaseUrl()
                        + MapTileIndex.getZoom(pMapTileIndex) + "/"
                        + MapTileIndex.getX(pMapTileIndex) + "/"
                        + MapTileIndex.getY(pMapTileIndex) + ".png"
                        + "?key=" + CARTO_API_KEY;
            }
        };
    }
}
