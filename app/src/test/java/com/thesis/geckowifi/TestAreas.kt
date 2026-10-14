package com.thesis.geckowifi

import com.thesis.geckowifi.data.model.GeoCertArea

/**
 * Central Zürich, the same box as lab/certs/mango-a.json, in geopki's
 * `[exteriors, interiors]` layout. Covers every test location; certificates
 * without an area are dropped by `coversLocation`.
 */
val ZURICH_AREA = GeoCertArea(coordinates = listOf(
    listOf(listOf(
        listOf(8.515, 47.360), listOf(8.565, 47.360), listOf(8.565, 47.390),
        listOf(8.515, 47.390), listOf(8.515, 47.360)
    )),
    emptyList()
))
