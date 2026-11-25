package com.doomscroller.tracker

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Heuristics for reading Instagram's view tree. Instagram renames view ids now and then, so every
 * check has a fallback, and the app's Debug section shows which ids are on screen when this breaks.
 */
data class Scan(
    /** Reels viewer is on screen. */
    val inReels: Boolean,
    /** Which rule decided that (shown in the Debug section). */
    val via: String,
    /** Identifies the reel currently filling the screen; blank if unknown. */
    val signature: String,
    val author: String?,
    val commentsOpen: Boolean,
    val likedNow: Boolean,
    val nodeCount: Int,
    val ids: List<String>,
    /** Scrollable surface outside Reels: "feed" (Home), "explore", or null (DMs, profile, etc.). */
    val surface: String? = null,
)

object IgScanner {
    private const val MAX_NODES = 3000

    // Ids Instagram has used for the full-screen reels pager over the years.
    private val PAGER_IDS = setOf(
        "clips_viewer_view_pager", "clips_viewer_pager", "clips_viewer_container",
        "clips_viewer_fragment_container", "clips_swipe_refresh_container", "reels_viewer_view_pager",
    )
    private val AUTHOR_HINTS = listOf("clips_author_username", "author_username", "clips_author_info", "reel_author_username")
    private val CAPTION_HINTS = listOf("clips_caption_component", "clips_caption", "caption_text")
    private val COMMENT_SHEET_HINTS = listOf(
        "comment_composer", "comment_thread", "row_comment", "comments_sheet", "comment_list",
        "comment_textview", "layout_comment",
    )
    // Bottom tab bar: the selected tab says which section of the app we're in.
    private val FEED_TAB_IDS = setOf("feed_tab", "home_tab")
    private val EXPLORE_TAB_IDS = setOf("search_tab", "explore_tab")
    private val FEED_TAB_DESC = setOf("home")
    private val EXPLORE_TAB_DESC = setOf("search and explore", "search", "explore")
    private val FEED_HINTS = listOf("row_feed", "main_feed", "feed_post", "reels_tray", "stories_tray", "title_logo", "feed_timeline")
    private val EXPLORE_HINTS = listOf("explore", "search_edit", "action_bar_search")
    private val COUNT_RE = Regex("^[\\d.,\\s]+[KkMm]?$")

    fun scan(root: AccessibilityNodeInfo, screenH: Int): Scan {
        var pagerVia: String? = null
        var clipsIdCount = 0
        var tallestClipsNode = 0
        var author: String? = null
        var caption: String? = null
        var commentsOpen = false
        var liked = false
        var selTab: String? = null
        var feedHint = false
        var exploreHint = false
        val ids = LinkedHashSet<String>()
        val genericTexts = ArrayList<String>()
        val r = Rect()

        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        var visited = 0
        while (stack.isNotEmpty() && visited < MAX_NODES) {
            val n = stack.removeLast()
            visited++
            if (!n.isVisibleToUser) continue

            val fullId = n.viewIdResourceName
            val id = fullId?.substringAfter(":id/")
            if (id != null) {
                if (ids.size < 80) ids += id
                if (id in PAGER_IDS || (id.contains("clips") && id.contains("viewer"))) {
                    if (pagerVia == null) pagerVia = "id:$id"
                }
                if (id.startsWith("clips_")) {
                    clipsIdCount++
                    n.getBoundsInScreen(r)
                    if (r.height() > tallestClipsNode) tallestClipsNode = r.height()
                }
                if (author == null && AUTHOR_HINTS.any { id.contains(it) }) {
                    author = (n.text ?: n.contentDescription)?.toString()?.takeIf { it.isNotBlank() }
                }
                if (caption == null && CAPTION_HINTS.any { id.contains(it) }) {
                    caption = (n.text ?: n.contentDescription)?.toString()?.takeIf { it.isNotBlank() }
                }
                if (!commentsOpen && COMMENT_SHEET_HINTS.any { id.contains(it) }) commentsOpen = true
                if (id.contains("like_button") && n.isSelected) liked = true
                if (n.isSelected && selTab == null) {
                    if (id in FEED_TAB_IDS) selTab = "feed"
                    if (id in EXPLORE_TAB_IDS) selTab = "explore"
                }
                if (!id.endsWith("_tab")) {
                    if (!feedHint && FEED_HINTS.any { id.contains(it) }) feedHint = true
                    if (!exploreHint && EXPLORE_HINTS.any { id.contains(it) }) exploreHint = true
                }
            }
            if (n.isSelected && selTab == null) {
                val d = n.contentDescription?.toString()?.lowercase()?.trim()
                if (d in FEED_TAB_DESC) selTab = "feed"
                if (d in EXPLORE_TAB_DESC) selTab = "explore"
            }

            val text = n.text?.toString()
            if (text != null) {
                if (!commentsOpen && text.startsWith("Add a comment")) commentsOpen = true
                val t = text.trim()
                if (genericTexts.size < 6 && t.length in 2..120 && !COUNT_RE.matches(t)) genericTexts += t
            }
            val desc = n.contentDescription?.toString()
            if (desc != null && (desc.equals("Unlike", true) || desc.equals("Liked", true))) liked = true

            for (i in n.childCount - 1 downTo 0) n.getChild(i)?.let { stack.addLast(it) }
        }

        val via = pagerVia
            ?: if (clipsIdCount >= 3 && tallestClipsNode >= screenH * 0.7f) "clips-ids($clipsIdCount)" else null

        val signature = when {
            // Usernames keep their digits (creator_1 vs creator_2); only free text gets normalized.
            author != null -> author.trim().lowercase() + "|" + norm(caption ?: "").take(24)
            else -> genericTexts.joinToString("|") { norm(it).take(24) }
        }

        // Feed needs real feed content (a profile opened from Home keeps the Home tab selected).
        // Anything reached from the Explore tab (grid, search results, posts) counts as Explore.
        val surface = when {
            via != null -> null
            selTab == "explore" -> "explore"
            selTab == "feed" && feedHint -> "feed"
            selTab == null && exploreHint -> "explore"
            selTab == null && feedHint -> "feed"
            else -> null
        }

        return Scan(
            surface = surface,
            inReels = via != null,
            via = via ?: "none(tab=$selTab feed=$feedHint explore=$exploreHint)",
            signature = signature,
            // Collabs read "alice and bob"; credit the first creator.
            author = author?.trim()?.removePrefix("@")?.substringBefore(" and ")?.trim(),
            commentsOpen = commentsOpen,
            likedNow = liked,
            nodeCount = visited,
            ids = ids.toList(),
        )
    }

    /** Strip things that change while you watch the same reel (counts, "… more", whitespace). */
    private fun norm(s: String): String =
        s.lowercase()
            .replace("… more", "").replace("... more", "").replace("…", "").replace("...", "")
            .replace(Regex("[\\d\\s.,]"), "")
}
