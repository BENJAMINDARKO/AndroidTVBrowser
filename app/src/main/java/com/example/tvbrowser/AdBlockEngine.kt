package com.example.tvbrowser

import android.content.Context
import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger

class AdBlockEngine {
    @Volatile
    private var blockedDomains = HashSet<String>()
    @Volatile
    private var whitelistDomains = HashSet<String>()
    @Volatile
    private var pathRules = ArrayList<String>()

    val pageBlockCount = AtomicInteger(0)
    val lifetimeBlockCount = AtomicInteger(0)

    val filterLists = listOf(
        FilterList("EasyList", "easylist.txt", "https://easylist.to/easylist/easylist.txt"),
        FilterList("EasyPrivacy", "easyprivacy.txt", "https://easylist.to/easylist/easyprivacy.txt"),
        FilterList("Peter Lowe's List", "peterlowe.txt", "https://pgl.yoyo.org/adservers/serverlist.php?hostformat=hosts&showintro=0&mimetype=plaintext"),
        FilterList("uBlock Filters", "ublock_filters.txt", "https://raw.githubusercontent.com/uBlockOrigin/uAssets/master/filters/filters.txt"),
        FilterList("uBlock Filters Ads 2020", "ublock_ads_2020.txt", "https://raw.githubusercontent.com/uBlockOrigin/uAssets/master/filters/filters-2020.txt"),
        FilterList("uBlock Filters Ads 2021", "ublock_ads_2021.txt", "https://raw.githubusercontent.com/uBlockOrigin/uAssets/master/filters/filters-2021.txt"),
        FilterList("uBlock Filters Badware", "ublock_badware.txt", "https://raw.githubusercontent.com/uBlockOrigin/uAssets/master/filters/badware.txt"),
        FilterList("uBlock Filters Privacy", "ublock_privacy.txt", "https://raw.githubusercontent.com/uBlockOrigin/uAssets/master/filters/privacy.txt"),
        FilterList("uBlock Filters Quick Fixes", "ublock_quick_fixes.txt", "https://raw.githubusercontent.com/uBlockOrigin/uAssets/master/filters/quick-fixes.txt"),
        FilterList("uBlock Filters Unbreak", "ublock_unbreak.txt", "https://raw.githubusercontent.com/uBlockOrigin/uAssets/master/filters/unbreak.txt"),
        FilterList("URLhaus Blocklist", "urlhaus.txt", "https://curben.gitlab.io/urlhaus-filter/urlhaus-filter-online.txt")
    )

    data class FilterList(val name: String, val filename: String, val url: String)

    fun loadRules(context: Context) {
        Log.d("AdBlockEngine", "Starting loadRules")
        
        // Load lifetime count
        val prefs = context.getSharedPreferences("tvbrowser_data", Context.MODE_PRIVATE)
        lifetimeBlockCount.set(prefs.getInt("lifetime_blocked_count", 0))

        val tempBlocked = HashSet<String>()
        val tempWhitelist = HashSet<String>()
        val tempPathRules = ArrayList<String>()

        for (list in filterLists) {
            // Check if list is enabled (default is true)
            val isEnabled = prefs.getBoolean("adblock_list_${list.name}", true)
            if (!isEnabled) {
                Log.d("AdBlockEngine", "Skipping disabled filter list: ${list.name}")
                continue
            }

            val localFile = File(context.filesDir, list.filename)
            // Try to download/refresh the file asynchronously in background (handled locally or via initial trigger)
            tryDownload(list.url, localFile)
            
            if (localFile.exists()) {
                Log.d("AdBlockEngine", "Loading rules from cache: ${list.filename}")
                parseFile(localFile, tempBlocked, tempWhitelist, tempPathRules)
            } else {
                Log.w("AdBlockEngine", "No local cache found for: ${list.filename}")
            }
        }
        
        // Swap references
        blockedDomains = tempBlocked
        whitelistDomains = tempWhitelist
        pathRules = tempPathRules
        
        Log.d("AdBlockEngine", "Load complete. Blocked domains: ${blockedDomains.size}, Path rules: ${pathRules.size}")
    }

    private fun tryDownload(urlString: String, outputFile: File) {
        try {
            val url = URL(urlString)
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.requestMethod = "GET"
            connection.connect()

            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                connection.inputStream.use { input ->
                    FileOutputStream(outputFile).use { output ->
                        input.copyTo(output)
                    }
                }
                Log.d("AdBlockEngine", "Downloaded successfully: ${outputFile.name}")
            }
            connection.disconnect()
        } catch (e: Exception) {
            Log.w("AdBlockEngine", "Failed to download update for ${outputFile.name}: ${e.message}")
        }
    }

    private fun parseFile(
        file: File,
        tempBlocked: HashSet<String>,
        tempWhitelist: HashSet<String>,
        tempPathRules: ArrayList<String>
    ) {
        try {
            BufferedReader(InputStreamReader(FileInputStream(file))).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val cleanLine = line!!.trim()
                    if (cleanLine.isEmpty() || cleanLine.startsWith("!") || cleanLine.startsWith("[")) {
                        continue
                    }
                    if (cleanLine.contains("##")) {
                        continue // Skip cosmetic rules as they are handled via custom injection or skipped for CPU overhead
                    }
                    parseRule(cleanLine, tempBlocked, tempWhitelist, tempPathRules)
                }
            }
        } catch (e: Exception) {
            Log.e("AdBlockEngine", "Error parsing file ${file.name}", e)
        }
    }

    private fun parseRule(
        rule: String,
        tempBlocked: HashSet<String>,
        tempWhitelist: HashSet<String>,
        tempPathRules: ArrayList<String>
    ) {
        var cleanRule = rule
        var isWhitelist = false

        if (cleanRule.startsWith("@@")) {
            isWhitelist = true
            cleanRule = cleanRule.substring(2)
        }

        if (cleanRule.startsWith("||") && cleanRule.endsWith("^")) {
            val domain = cleanRule.substring(2, cleanRule.length - 1)
            if (isWhitelist) {
                tempWhitelist.add(domain)
            } else {
                tempBlocked.add(domain)
            }
            return
        }

        if (!cleanRule.contains("/") && !cleanRule.contains("*")) {
            if (cleanRule.startsWith("127.0.0.1 ") || cleanRule.startsWith("0.0.0.0 ")) {
                val host = cleanRule.substringAfter(" ").trim()
                if (isWhitelist) tempWhitelist.add(host) else tempBlocked.add(host)
            } else {
                if (isWhitelist) tempWhitelist.add(cleanRule) else tempBlocked.add(cleanRule)
            }
            return
        }

        if (!isWhitelist) {
            val parsedPath = cleanRule.replace("*", "")
            if (parsedPath.startsWith("/")) {
                tempPathRules.add(parsedPath)
            }
        }
    }

    fun incrementBlocked(context: Context) {
        pageBlockCount.incrementAndGet()
        val total = lifetimeBlockCount.incrementAndGet()
        val prefs = context.getSharedPreferences("tvbrowser_data", Context.MODE_PRIVATE)
        prefs.edit().putInt("lifetime_blocked_count", total).apply()
    }

    fun shouldBlock(url: String, host: String): Boolean {
        val currentWhitelist = whitelistDomains
        val currentBlocked = blockedDomains
        val currentPathRules = pathRules

        if (currentWhitelist.contains(host)) return false
        for (wDomain in currentWhitelist) {
            if (host.endsWith(".$wDomain")) return false
        }

        if (currentBlocked.contains(host)) return true
        for (bDomain in currentBlocked) {
            if (host.endsWith(".$bDomain")) return true
        }

        for (rule in currentPathRules) {
            if (url.contains(rule)) return true
        }

        return false
    }
}
