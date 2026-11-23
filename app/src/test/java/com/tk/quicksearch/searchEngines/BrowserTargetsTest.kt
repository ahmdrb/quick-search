package com.tk.quicksearch.searchEngines

import com.tk.quicksearch.search.core.BrowserApp
import com.tk.quicksearch.search.core.SearchTarget
import org.junit.Assert.assertEquals
import org.junit.Test

class BrowserTargetsTest {
    private fun browser(packageName: String): SearchTarget =
        SearchTarget.Browser(BrowserApp(packageName = packageName, label = packageName))

    private fun packages(targets: List<SearchTarget.Browser>): List<String> =
        targets.map { it.app.packageName }

    @Test
    fun builtInBrowsersAppearBeforeExternalBrowsersWithoutSystemDefault() {
        val targets = listOf(
            browser("org.example.second"),
            browser(FLOATING_BROWSER_PACKAGE),
            browser("org.example.first"),
            browser(IN_APP_BROWSER_PACKAGE),
        )

        assertEquals(
            listOf(
                IN_APP_BROWSER_PACKAGE,
                FLOATING_BROWSER_PACKAGE,
                "org.example.second",
                "org.example.first",
            ),
            packages(orderedBrowserTargets(targets, defaultBrowserPackage = null)),
        )
    }

    @Test
    fun systemDefaultStaysFirstAndBothBuiltInBrowsersRemainAvailable() {
        val targets = listOf(
            browser(FLOATING_BROWSER_PACKAGE),
            browser("org.example.other"),
            browser(IN_APP_BROWSER_PACKAGE),
            browser("org.example.default"),
        )

        assertEquals(
            listOf(
                "org.example.default",
                IN_APP_BROWSER_PACKAGE,
                FLOATING_BROWSER_PACKAGE,
                "org.example.other",
            ),
            packages(orderedBrowserTargets(targets, defaultBrowserPackage = "org.example.default")),
        )
    }

    @Test
    fun missingSystemDefaultFallsBackToBuiltInBrowserChoices() {
        val targets = listOf(
            browser("org.example.external"),
            browser(FLOATING_BROWSER_PACKAGE),
            browser(IN_APP_BROWSER_PACKAGE),
        )

        assertEquals(
            listOf(
                IN_APP_BROWSER_PACKAGE,
                FLOATING_BROWSER_PACKAGE,
                "org.example.external",
            ),
            packages(orderedBrowserTargets(targets, defaultBrowserPackage = "org.example.missing")),
        )
    }
}
