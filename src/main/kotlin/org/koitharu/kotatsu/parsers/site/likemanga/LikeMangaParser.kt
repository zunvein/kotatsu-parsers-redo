package org.koitharu.kotatsu.parsers.site.likemanga

import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.exception.ParseException
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaListFilterCapabilities
import org.koitharu.kotatsu.parsers.model.MangaListFilterOptions
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.model.MangaState
import org.koitharu.kotatsu.parsers.model.MangaTag
import org.koitharu.kotatsu.parsers.model.RATING_UNKNOWN
import org.koitharu.kotatsu.parsers.model.SortOrder
import org.koitharu.kotatsu.parsers.network.UserAgents
import org.koitharu.kotatsu.parsers.util.CryptoAES
import org.koitharu.kotatsu.parsers.util.attrAsAbsoluteUrlOrNull
import org.koitharu.kotatsu.parsers.util.attrAsRelativeUrl
import org.koitharu.kotatsu.parsers.util.await
import org.koitharu.kotatsu.parsers.util.generateUid
import org.koitharu.kotatsu.parsers.util.nullIfEmpty
import org.koitharu.kotatsu.parsers.util.oneOrThrowIfMany
import org.koitharu.kotatsu.parsers.util.parseHtml
import org.koitharu.kotatsu.parsers.util.parseJson
import org.koitharu.kotatsu.parsers.util.parseRaw
import org.koitharu.kotatsu.parsers.util.src
import org.koitharu.kotatsu.parsers.util.textOrNull
import org.koitharu.kotatsu.parsers.util.toAbsoluteUrl
import org.koitharu.kotatsu.parsers.util.toTitleCase
import org.koitharu.kotatsu.parsers.util.urlEncoded
import java.util.EnumSet

/**
 * Parser for the LikeManga network.
 *
 * The network left likemanga.ink behind and now runs on mgread.io with the "init-manga" theme,
 * so every legacy selector is gone: the synopsis moved to `#manga-description`, the chapter list
 * to `#chapter-list`, and chapter images are delivered as an AES blob (`InitMangaEncryptedChapter`)
 * that has to be unlocked through `/wp-json/initmanga/v1/chapter-key`.
 */
internal abstract class LikeMangaParser(
	context: MangaLoaderContext,
	source: MangaParserSource,
	domain: String,
	pageSize: Int = 24,
) : PagedMangaParser(context, source, pageSize) {

	override val configKeyDomain = ConfigKey.Domain(domain)
	override val userAgentKey = ConfigKey.UserAgent(UserAgents.CHROME_MOBILE)

	private val cryptoAes = CryptoAES(context)

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
	}

	override fun getRequestHeaders(): Headers = super.getRequestHeaders().newBuilder()
		.set("Referer", "https://$domain/")
		.build()

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.UPDATED,
		SortOrder.NEWEST,
		SortOrder.POPULARITY,
		SortOrder.RATING,
	)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isSearchSupported = true,
			isSearchWithFiltersSupported = true,
		)

	override suspend fun getFilterOptions(): MangaListFilterOptions = MangaListFilterOptions(
		availableTags = fetchAvailableTags(),
		availableStates = EnumSet.of(
			MangaState.ONGOING,
			MangaState.FINISHED,
			MangaState.PAUSED,
			MangaState.ABANDONED,
		),
	)

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val query = filter.query?.trim().orEmpty()
		if (query.isNotEmpty()) {
			if (page > 1) {
				// the search endpoint is not paginated
				return emptyList()
			}
			val url = buildString {
				append("https://")
				append(domain)
				append("/?act=search&f%5Bkeyword%5D=")
				append(query.urlEncoded())
			}
			return parseMangaList(webClient.httpGet(url, getRequestHeaders()).parseHtml())
		}

		val url = buildString {
			append("https://")
			append(domain)
			append("/advanced-filter/")
			if (page > 1) {
				append("page/")
				append(page)
				append('/')
			}
			append('?')
			filter.tags.oneOrThrowIfMany()?.let {
				append("genre%5B%5D=")
				append(it.key.urlEncoded())
				append('&')
			}
			filter.states.oneOrThrowIfMany()?.let {
				append("status=")
				append(
					when (it) {
						MangaState.ONGOING -> "ongoing"
						MangaState.FINISHED -> "completed"
						MangaState.PAUSED -> "source_hiatus"
						MangaState.ABANDONED -> "dropped"
						else -> "ongoing"
					},
				)
				append('&')
			}
			append("sort=")
			append(
				when (order) {
					SortOrder.NEWEST -> "new"
					SortOrder.POPULARITY -> "views"
					SortOrder.RATING -> "rating"
					else -> "updated"
				},
			)
		}
		return parseMangaList(webClient.httpGet(url, getRequestHeaders()).parseHtml())
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val doc = webClient.httpGet(manga.url.toAbsoluteUrl(domain), getRequestHeaders()).parseHtml()
		val description = doc.getElementById("manga-description")?.let { element ->
			element.select("a, span, script").remove()
			element.html().trim().nullIfEmpty()
		}
		return manga.copy(
			title = doc.selectFirst("#manga-title")?.text()?.trim().orEmpty().ifEmpty { manga.title },
			description = description,
			coverUrl = doc.selectFirst("div.story-cover-wrap img, a.story-cover img")?.src() ?: manga.coverUrl,
			tags = doc.select("#genre-tags a").mapNotNullTo(LinkedHashSet()) { a ->
				val href = a.attr("href")
				if (!href.contains("/genre/")) {
					return@mapNotNullTo null
				}
				MangaTag(
					key = href.removeSuffix("/").substringAfterLast('/'),
					title = a.text().toTitleCase(),
					source = source,
				)
			},
			state = parseState(doc) ?: manga.state,
			chapters = loadChapters(manga),
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val url = chapter.url.toAbsoluteUrl(domain)
		val raw = webClient.httpGet(url, getRequestHeaders()).parseRaw()
		val doc = Jsoup.parse(raw, "https://$domain/")

		val encrypted = extractJsonVar(raw, "InitMangaEncryptedChapter")
		val ciphertext = encrypted?.optString("ciphertext")?.takeIf { it.isNotEmpty() }
		if (encrypted == null || ciphertext == null) {
			return parseDirectPages(doc)
		}

		val cid = encrypted.optInt("cid", 0)
		val epoch = encrypted.optLong("e", 0L)
		val grant = encrypted.optString("g").orEmpty()
		val iv = encrypted.optString("iv").orEmpty()
		if (cid == 0 || iv.isEmpty()) {
			return parseDirectPages(doc)
		}
		val nonce = extractJsonVar(raw, "InitMangaData")?.optString("nonce")?.takeIf { it.isNotEmpty() }
		val key = requestChapterKey(cid, epoch, grant, nonce, url)
		val decrypted = cryptoAes.decrypt(ciphertext, key.hexToBytes(), iv.hexToBytes())
		return Jsoup.parseBodyFragment(decrypted, "https://$domain/")
			.select("img")
			.mapNotNull { img ->
				val src = img.attrAsAbsoluteUrlOrNull("data-original-src")
					?: img.attrAsAbsoluteUrlOrNull("data-src")
					?: img.attrAsAbsoluteUrlOrNull("src")?.takeUnless { it.startsWith("data:") }
				src?.let {
					MangaPage(
						id = generateUid(it),
						url = it,
						preview = null,
						source = source,
					)
				}
			}
	}

	private fun parseDirectPages(doc: Document): List<MangaPage> = doc.select("#chapter-content img")
		.mapNotNull { img ->
			val src = img.attrAsAbsoluteUrlOrNull("data-original-src")
				?: img.attrAsAbsoluteUrlOrNull("data-src")
				?: img.attrAsAbsoluteUrlOrNull("src")?.takeUnless { it.startsWith("data:") }
			src?.let {
				MangaPage(
					id = generateUid(it),
					url = it,
					preview = null,
					source = source,
				)
			}
		}

	private suspend fun requestChapterKey(
		cid: Int,
		epoch: Long,
		grant: String,
		nonce: String?,
		referer: String,
	): String {
		val endpoint = "https://$domain/wp-json/initmanga/v1/chapter-key"
		val body = JSONObject()
			.put("chapter_id", cid)
			.put("epoch", epoch)
			.put("grant", grant)
		var response = postJson(endpoint, body, nonce, referer)
		response.optString("key").nullIfEmpty()?.let { return it }

		val token = response.optJSONObject("data")?.optString("token").orEmpty()
		body.put("challenge_token", token)
		body.put("verdict", "human")
		body.put("score", 100)
		body.put("codes", JSONArray())
		response = postJson(endpoint, body, nonce, referer)
		return response.optString("key").nullIfEmpty()
			?: throw ParseException("Chapter key not issued", referer)
	}

	private suspend fun postJson(
		url: String,
		body: JSONObject,
		nonce: String?,
		referer: String,
	): JSONObject {
		val request = Request.Builder()
			.url(url)
			.post(body.toString().toByteArray().toRequestBody(JSON_MEDIA_TYPE))
			.header("Referer", referer)
			.header("Origin", "https://$domain")
			.header("Accept", "application/json")
			.apply {
				if (!nonce.isNullOrEmpty()) {
					header("X-WP-Nonce", nonce)
				}
			}
			.tag(MangaSource::class.java, source)
			.build()
		val response = context.httpClient.newCall(request).await()
		if (response.isSuccessful) {
			return response.parseJson()
		}
		val payload = response.use { it.body?.string().orEmpty() }
		return runCatching { JSONObject(payload) }.getOrDefault(JSONObject())
	}

	private fun parseMangaList(doc: Document): List<Manga> {
		val result = ArrayList<Manga>(pageSize)
		val seen = HashSet<String>(pageSize)
		for (panel in doc.select("div.uk-panel")) {
			val link = panel.selectFirst("h2 a[href*=\"/manga/\"], h3 a[href*=\"/manga/\"], a.uk-link-heading[href*=\"/manga/\"], a[href*=\"/manga/\"]")
				?: continue
			val href = runCatching { link.attrAsRelativeUrl("href") }.getOrNull() ?: continue
			if (!seen.add(href)) {
				continue
			}
			val title = panel.selectFirst("h2, h3, h4")?.textOrNull()
				?: panel.selectFirst("img[alt]")?.attr("alt")?.removePrefix("Cover image of ")?.trim()?.takeIf { it.isNotEmpty() }
				?: continue
			result.add(
				Manga(
					id = generateUid(href),
					title = title,
					altTitles = emptySet(),
					url = href,
					publicUrl = href.toAbsoluteUrl(domain),
					rating = RATING_UNKNOWN,
					contentRating = null,
					coverUrl = panel.selectFirst("img")?.src(),
					tags = emptySet(),
					state = null,
					authors = emptySet(),
					source = source,
				),
			)
		}
		return result
	}

	private suspend fun loadChapters(manga: Manga): List<MangaChapter> {
		val base = manga.url.toAbsoluteUrl(domain).removeSuffix("/")
		val chapters = LinkedHashMap<String, MangaChapter>()
		var lastPage = 1
		var page = 1
		while (page <= lastPage && page <= MAX_CHAPTER_PAGES) {
			val url = if (page == 1) "$base/" else "$base/chapter/page/$page/"
			val doc = webClient.httpGet(url, getRequestHeaders()).parseHtml()
			for (anchor in doc.select("ul.uk-pagination a[href*=\"/chapter/page/\"]")) {
				val number = anchor.attr("href").substringAfter("/chapter/page/").substringBefore('/').toIntOrNull()
				if (number != null && number > lastPage) {
					lastPage = number
				}
			}
			for (anchor in doc.select("#chapter-list a[href*=\"/chapter-\"]")) {
				val href = runCatching { anchor.attrAsRelativeUrl("href") }.getOrNull() ?: continue
				val number = href.substringAfterLast("/chapter-").removeSuffix("/").toFloatOrNull() ?: continue
				chapters[href] = MangaChapter(
					id = generateUid(href),
					title = anchor.text().trim().nullIfEmpty(),
					number = number,
					volume = 0,
					url = href,
					scanlator = null,
					uploadDate = 0L,
					branch = null,
					source = source,
				)
			}
			page++
		}
		return chapters.values.sortedByDescending { it.number }
	}

	private suspend fun fetchAvailableTags(): Set<MangaTag> {
		val doc = webClient.httpGet("https://$domain/advanced-filter/", getRequestHeaders()).parseHtml()
		return doc.select("input[name=\"genre[]\"]").mapNotNullTo(LinkedHashSet()) { input ->
			val key = input.attr("value").nullIfEmpty() ?: return@mapNotNullTo null
			MangaTag(
				key = key,
				title = key.replace('-', ' ').toTitleCase(),
				source = source,
			)
		}
	}

	private fun parseState(doc: Document): MangaState? {
		for (element in doc.select("span, div.uk-h6, li")) {
			val text = element.ownText().trim().lowercase()
			STATE_MAP[text]?.let { return it }
		}
		return null
	}

	private fun extractJsonVar(html: String, name: String): JSONObject? {
		val regex = Regex("""var\s+$name\s*=\s*(\{.*?\});""", RegexOption.DOT_MATCHES_ALL)
		val raw = regex.find(html)?.groupValues?.getOrNull(1) ?: return null
		return runCatching { JSONObject(raw) }.getOrNull()
	}

	private fun String.hexToBytes(): ByteArray {
		check(length % 2 == 0) { "Hex string must have an even length" }
		return ByteArray(length / 2) { index ->
			substring(index * 2, index * 2 + 2).toInt(16).toByte()
		}
	}

	private companion object {
		private val JSON_MEDIA_TYPE = "application/json".toMediaType()
		private const val MAX_CHAPTER_PAGES = 60
		private val STATE_MAP = mapOf(
			"ongoing" to MangaState.ONGOING,
			"completed" to MangaState.FINISHED,
			"season end" to MangaState.PAUSED,
			"source hiatus" to MangaState.PAUSED,
			"caught up" to MangaState.PAUSED,
			"dropped" to MangaState.ABANDONED,
		)
	}
}
