package com.cleilson04.stremioconta

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class StremioContaProvider : MainAPI() {
    override var name = "Stremio (Conta)"
    override var mainUrl = "https://api.strem.io"
    override var lang = "pt"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    data class LoginResp(val result: LoginResult? = null)
    data class LoginResult(val authKey: String? = null)
    data class AddonsResp(val result: AddonsResult? = null)
    data class AddonsResult(val addons: List<AddonDesc> = emptyList())
    data class AddonDesc(val transportUrl: String, val manifest: Manifest)
    data class Manifest(
        val id: String = "",
        val name: String = "",
        val resources: List<Any> = emptyList(),
        val types: List<String> = emptyList(),
        val catalogs: List<Catalog> = emptyList(),
    )
    data class Catalog(
        val type: String = "",
        val id: String = "",
        val name: String? = null,
        val extra: List<Extra>? = null,
    )
    data class Extra(val name: String = "", val isRequired: Boolean? = null)
    data class CatalogResp(val metas: List<Meta> = emptyList())
    data class MetaResp(val meta: Meta? = null)
    data class Meta(
        val id: String = "",
        val type: String = "",
        val name: String = "",
        val poster: String? = null,
        val background: String? = null,
        val description: String? = null,
        val releaseInfo: String? = null,
        val videos: List<Video>? = null,
    )
    data class Video(
        val id: String = "",
        val title: String? = null,
        val name: String? = null,
        val season: Int? = null,
        val episode: Int? = null,
        val thumbnail: String? = null,
    )
    data class StreamResp(val streams: List<StreamItem> = emptyList())
    data class StreamItem(
        val url: String? = null,
        val name: String? = null,
        val title: String? = null,
        val infoHash: String? = null,
        val subtitles: List<Sub>? = null,
    )
    data class Sub(val url: String = "", val lang: String? = null)

    private suspend fun authKey(): String? {
        Prefs.authKey?.let { return it }
        val email = Prefs.pendingEmail ?: return null
        val pass = Prefs.pendingPassword ?: return null
        val r = app.post(
            "$mainUrl/api/login",
            json = mapOf("type" to "Login", "email" to email, "password" to pass, "facebook" to false)
        ).parsedSafe<LoginResp>()
        Prefs.pendingPassword = null
        return r?.result?.authKey?.also { Prefs.authKey = it }
    }

    private suspend fun addons(): List<AddonDesc> {
        val key = authKey() ?: return emptyList()
        return app.post(
            "$mainUrl/api/addonCollectionGet",
            json = mapOf("type" to "AddonCollectionGet", "authKey" to key, "update" to true)
        ).parsedSafe<AddonsResp>()?.result?.addons ?: emptyList()
    }

    private fun AddonDesc.base() = transportUrl.removeSuffix("/manifest.json").trimEnd('/')

    private fun AddonDesc.has(resource: String) = manifest.resources.any {
        (it is String && it == resource) || (it is Map<*, *> && it["name"] == resource)
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val lists = ArrayList<HomePageList>()
        for (a in addons()) {
            for (c in a.manifest.catalogs) {
                if (c.extra?.any { it.isRequired == true } == true) continue
                val metas = runCatching {
                    app.get("${a.base()}/catalog/${c.type}/${c.id}.json").parsedSafe<CatalogResp>()?.metas
                }.getOrNull() ?: continue
                if (metas.isEmpty()) continue
                lists += HomePageList(
                    "${c.name ?: c.id} · ${a.manifest.name}",
                    metas.mapNotNull { it.toSearch() }
                )
            }
        }
        return if (lists.isEmpty()) null else newHomePageResponse(lists, false)
    }

    private fun Meta.toSearch(): SearchResponse? {
        val url = "${type}/${id}"
        return when (type) {
            "movie" -> newMovieSearchResponse(name, url, TvType.Movie) { posterUrl = poster }
            "series" -> newTvSeriesSearchResponse(name, url, TvType.TvSeries) { posterUrl = poster }
            else -> null
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = java.net.URLEncoder.encode(query, "UTF-8")
        val out = ArrayList<SearchResponse>()
        for (a in addons()) for (c in a.manifest.catalogs) {
            if (c.extra?.any { it.name == "search" } != true) continue
            runCatching {
                app.get("${a.base()}/catalog/${c.type}/${c.id}/search=$q.json")
                    .parsedSafe<CatalogResp>()?.metas?.mapNotNullTo(out) { it.toSearch() }
            }
        }
        return out.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse? {
        val (type, id) = url.split("/", limit = 2)
        val meta = addons().filter { it.has("meta") && type in it.manifest.types }.firstNotNullOfOrNull { a ->
            runCatching {
                app.get("${a.base()}/meta/$type/$id.json").parsedSafe<MetaResp>()?.meta
            }.getOrNull()
        } ?: return null

        return if (type == "series") {
            val eps = meta.videos.orEmpty().map {
                newEpisode("$type/${it.id}") {
                    this.name = it.title ?: it.name
                    this.season = it.season
                    this.episode = it.episode
                    this.posterUrl = it.thumbnail
                }
            }
            newTvSeriesLoadResponse(meta.name, url, TvType.TvSeries, eps) {
                posterUrl = meta.poster
                backgroundPosterUrl = meta.background
                plot = meta.description
                year = meta.releaseInfo?.take(4)?.toIntOrNull()
            }
        } else {
            newMovieLoadResponse(meta.name, url, TvType.Movie, "$type/${meta.id}") {
                posterUrl = meta.poster
                backgroundPosterUrl = meta.background
                plot = meta.description
                year = meta.releaseInfo?.take(4)?.toIntOrNull()
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val (type, id) = data.split("/", limit = 2)
        var found = false
        for (a in addons().filter { it.has("stream") }) {
            val streams = runCatching {
                app.get("${a.base()}/stream/$type/$id.json").parsedSafe<StreamResp>()?.streams
            }.getOrNull() ?: continue
            for (s in streams) {
                s.subtitles?.forEach { subtitleCallback(SubtitleFile(it.lang ?: "und", it.url)) }
                val link = s.url ?: continue
                val label = listOfNotNull(a.manifest.name, s.name, s.title)
                    .joinToString(" · ").replace("\n", " ")
                callback(
                    newExtractorLink(a.manifest.name, label, link) {
                        this.type = if (link.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    }
                )
                found = true
            }
        }
        return found
    }
}
