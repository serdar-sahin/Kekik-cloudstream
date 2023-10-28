// ! Bu araç @keyiflerolsun tarafından | @KekikAkademi için yazılmıştır.

package com.keyiflerolsun

import android.util.Log
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities


class DiziMom : MainAPI() {
    override var mainUrl            = "https://www.dizimom.pro"
    override var name               = "DiziMom"
    override val hasMainPage        = true
    override var lang               = "tr"
    override val hasQuickSearch     = false
    override val hasDownloadSupport = true
    override val supportedTypes     = setOf(TvType.TvSeries)

    override val mainPage =
        mainPageOf(
            "$mainUrl/turkce-dublaj-diziler/page/"      to "Dublajlı Diziler",
            "$mainUrl/netflix-dizileri-izle/page/"      to "Netflix Dizileri",
            "$mainUrl/yabanci-dizi-izle/page/"          to "Yabancı Diziler",
            "$mainUrl/yerli-dizi-izle/page/"            to "Yerli Diziler",
            "$mainUrl/kore-dizileri-izle/page/"         to "Kore Dizileri",
            "$mainUrl/full-hd-hint-dizileri-izle/page/" to "Hint Dizileri",
            "$mainUrl/tv-programlari-izle/page/"        to "TV Programları",
        )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("${request.data}${page}/").document
        val home     = document.select("div.single-item").mapNotNull { it.toSearchResult() }

        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title     = this.selectFirst("div.categorytitle a")?.text()?.substringBefore(" izle") ?: return null
        val href      = fixUrlNull(this.selectFirst("div.categorytitle a")?.attr("href")) ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("div.cat-img img")?.attr("src"))

        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = posterUrl }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query").document

        return document.select("div.single-item").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title       = document.selectFirst("div.title h1")?.text()?.substringBefore(" izle") ?: return null
        val poster      = fixUrlNull(document.selectFirst("div.category_image img")?.attr("src")) ?: return null
        val year        = document.selectXpath("//div[span[contains(text(), 'Yapım Yılı')]]").text().substringAfter("Yapım Yılı : ").trim().toIntOrNull()
        val description = document.selectFirst("div.category_desc")?.text()?.trim()
        val tags        = document.select("div.genres a").mapNotNull { it?.text()?.trim() }
        val rating      = document.selectXpath("//div[span[contains(text(), 'IMDB')]]").text().substringAfter("IMDB : ").trim().toRatingInt()
        val actors      = document.selectXpath("//div[span[contains(text(), 'Oyuncular')]]").text().substringAfter("Oyuncular : ").split(", ").map {
            Actor(it.trim())
        }

        val episodes    = document.select("div.bolumust").mapNotNull {
            val ep_name    = it.selectFirst("div.baslik")?.text()?.trim() ?: return@mapNotNull null
            val ep_href    = fixUrlNull(it.selectFirst("a")?.attr("href")) ?: return@mapNotNull null
            val ep_episode = Regex("""(\d+)\.Bölüm""").find(ep_name)?.groupValues?.get(1)?.toIntOrNull()
            val ep_season  = Regex("""(\d+)\.Sezon""").find(ep_name)?.groupValues?.get(1)?.toIntOrNull() ?: 1

            Episode(
                data        = ep_href,
                name        = ep_name,
                season      = ep_season,
                episode     = ep_episode,
                posterUrl   = null,
                rating      = null,
                date        = null
            )
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.year      = year
            this.plot      = description
            this.tags      = tags
            this.rating    = rating
            addActors(actors)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
        ): Boolean {

            Log.d("DZM", "_data » $data")
            val document = app.get(data).document
            val iframe   = document.selectFirst("div#vast iframe")?.attr("src") ?: return false
            Log.d("DZM", "_iframe » $iframe")

            if (iframe.contains("hdmomplayer")) {
                val i_source = app.get("$iframe", referer="$mainUrl/").text
                val m3u_link = Regex("""file:\"([^\"]+)""").find(i_source)?.groupValues?.get(1)
                Log.d("DZM", "_m3u_link » $m3u_link")
                if (m3u_link == null) {
                    Log.d("DZM", "_i_source » $i_source")
                    return false
                }
    
                callback.invoke(
                    ExtractorLink(
                        source  = this.name,
                        name    = this.name,
                        url     = m3u_link,
                        referer = iframe,
                        quality = Qualities.Unknown.value,
                        isM3u8  = !m3u_link.contains(".mp4")
                    )
                )
            }


            if (iframe.contains("hdplayersystem")) {
                val vid_id   = iframe.substringAfter("video/")
                val i_source = app.post(
                    "https://hdplayersystem.live/player/index.php?data=${vid_id}&do=getVideo",
                    data = mapOf(
                        "hash" to vid_id,
                        "r"    to "$mainUrl/",
                    )
                ).text
                val vid_extract = Regex("""securedLink\":\"([^\"]+)""").find(i_source)?.groupValues?.get(1)
                if (vid_extract == null) {
                    Log.d("DZM", "_i_source » $i_source")
                    return false
                }
                val m3u_link    = vid_extract.replace("\\", "")
                Log.d("DZM", "_m3u_link » $m3u_link")
    
                callback.invoke(
                    ExtractorLink(
                        source  = this.name,
                        name    = this.name,
                        url     = m3u_link,
                        referer = iframe,
                        quality = Qualities.Unknown.value,
                        isM3u8  = !m3u_link.contains(".mp4")
                    )
                )
            }

            if (iframe.contains("peacemakerst")) {
                val vid_id   = iframe.substringAfter("video/")
                val i_source = app.post(
                    "https://peacemakerst.com/tv/video/${vid_id}?do=getVideo",
                    data = mapOf(
                        "hash" to vid_id,
                        "r"    to "$mainUrl/",
                        "s"    to ""
                    )
                ).text
                val vid_extract = Regex("""file\":\"([^\"]+)""").find(i_source)?.groupValues?.get(-1)
                if (vid_extract == null) {
                    Log.d("DZM", "_i_source » $i_source")
                    return false
                }
                val m3u_link    = vid_extract.replace("\\", "")
                Log.d("DZM", "_m3u_link » $m3u_link")
    
                callback.invoke(
                    ExtractorLink(
                        source  = this.name,
                        name    = this.name,
                        url     = m3u_link,
                        referer = iframe,
                        quality = Qualities.Unknown.value,
                        isM3u8  = !m3u_link.contains(".mp4")
                    )
                )
            }

            return true
    }
}
