package com.beakon.companion.data

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaceNameStoreTest {

    @Test
    fun `saved names load back by coordinate key`() {
        val store = PlaceNameStore(FakeSharedPreferences())
        store.save("21.87380,-159.44970", "Poipu Beach")
        store.save("33.19512,-117.37948", "Oceanside, California")

        assertEquals(
            mapOf(
                "21.87380,-159.44970" to "Poipu Beach",
                "33.19512,-117.37948" to "Oceanside, California",
            ),
            store.loadAll(),
        )
    }

    @Test
    fun `saving the same key overwrites, and non-string values are ignored`() {
        val fake = FakeSharedPreferences()
        fake.edit().putInt("not-a-name", 42).apply()
        val store = PlaceNameStore(fake)
        store.save("33.19512,-117.37948", "Oceanside")
        store.save("33.19512,-117.37948", "Oceanside, California")

        assertEquals(
            mapOf("33.19512,-117.37948" to "Oceanside, California"),
            store.loadAll(),
        )
    }

    @Test
    fun `a fresh store over the same preferences sees earlier names`() {
        val fake = FakeSharedPreferences()
        PlaceNameStore(fake).save("33.19512,-117.37948", "Oceanside, California")

        assertEquals(
            "Oceanside, California",
            PlaceNameStore(fake).loadAll()["33.19512,-117.37948"],
        )
    }
}
