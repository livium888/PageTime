package com.pagetime.app.ui.screens.reader

import com.pagetime.app.data.Sentences
import org.json.JSONObject
import org.readium.r2.navigator.epub.EpubNavigatorFragment

/**
 * Grabbing a whole sentence in an EPUB, which means reaching into Readium's web
 * view and back.
 *
 * A long press in an EPUB selects one word. The reader wants the sentence it
 * belongs to, and to be able to pull in the sentences on either side. Readium
 * cannot help with either end of that:
 *
 *  - `SelectableNavigator` exposes `currentSelection()` and `clearSelection()`
 *    and nothing that sets a selection, so a span cannot be widened from Kotlin.
 *  - The sentence's edges cannot be found from the locator either. Readium
 *    builds the selection's `before`/`after` text from a 200-character window,
 *    so a long sentence arrives cut in half — and a boundary computed from half
 *    a sentence is wrong in a way that looks right.
 *
 * So the work is split. JavaScript reads the *text block* the caret sits in and
 * where the caret is inside it; Kotlin finds the sentence with the same
 * [Sentences] kernel the plain-text reader uses; JavaScript writes the span back
 * through the page's own `Selection` object. One set of sentence rules for both
 * readers, and no dependence on `Selection.modify("sentence")` — a Blink
 * extension whose behaviour at a boundary (does moving "back a sentence" from
 * the start of a sentence land on the previous one?) is not something to build a
 * menu on.
 *
 * Everything here fails closed. A block that cannot be read, a selector that no
 * longer resolves, a resource that is not reflowable: the snap does not happen
 * and the reader keeps the ordinary word selection and the ordinary menu.
 */
object ReadiumSentenceGrab {

    /**
     * One text block, its text, and the offsets in it that matter.
     *
     * [selectionStart] and [selectionEnd] are the *current* selection's offsets
     * in the same text. They exist so the snap can tell whether it has already
     * run: setting a selection fires `selectionchange`, which makes Readium
     * rebuild its own selection state, and a snap with no way to notice that it
     * had already been applied could ping-pong with it forever.
     */
    data class Block(
        val key: String,
        val text: String,
        val at: Int,
        val selectionStart: Int,
        val selectionEnd: Int
    ) {
        /** Enough text to recognise this block again, and no more. */
        val head: String get() = text.take(24)
    }

    /** Which span to select, and whether selecting it would change anything. */
    data class Decision(val start: Int, val end: Int, val needsApply: Boolean)

    /**
     * The sentence around the caret, or null when the block cannot be used.
     *
     * Already being exactly the sentence is a decision rather than a no-op: it
     * is how the caller knows to leave the page alone.
     */
    fun decide(block: Block): Decision? {
        val span = Sentences.spanAt(block.text, block.at) ?: return null
        return Decision(
            start = span.start,
            end = span.end,
            needsApply = block.selectionStart != span.start || block.selectionEnd != span.end
        )
    }

    /** The next or previous sentence from a span already held, or null at the edge. */
    fun decideStep(text: String, start: Int, end: Int, forward: Boolean): Decision? {
        if (start < 0 || end <= start || end > text.length) return null
        val held = Sentences.Span(start, end)
        val span = if (forward) Sentences.next(text, held) else Sentences.previous(text, held)
        return span?.let { Decision(it.start, it.end, needsApply = true) }
    }

    fun parseRead(raw: String?): Block? {
        val json = jsonOf(raw) ?: return null
        val key = json.optString("key")
        val text = json.optString("text")
        val at = json.optInt("at", -1)
        if (key.isBlank() || text.isEmpty() || at < 0 || at > text.length) return null
        return Block(
            key = key,
            text = text,
            at = at,
            selectionStart = json.optInt("selStart", -1),
            selectionEnd = json.optInt("selEnd", -1)
        )
    }

    fun parseApplied(raw: String?): Boolean = raw?.trim() == "true"

    /**
     * Reads the block around the current selection, or null when there is
     * nothing to read — no selection, a fixed-layout resource, a block too large
     * to carry across the bridge.
     */
    suspend fun read(navigator: EpubNavigatorFragment): Block? {
        // try/catch rather than runCatching, the same as the reader's other
        // Readium calls: the lambda of an inline helper is not a suspend
        // context, and `evaluateJavascript` suspends.
        val raw = try {
            navigator.evaluateJavascript(READ_SCRIPT)
        } catch (t: Throwable) {
            return null
        }
        return parseRead(raw)
    }

    /** Writes a span back into the page. False when the page refused or moved on. */
    suspend fun apply(
        navigator: EpubNavigatorFragment,
        key: String,
        head: String,
        start: Int,
        end: Int
    ): Boolean {
        val raw = try {
            navigator.evaluateJavascript(applyScript(key, head, start, end))
        } catch (t: Throwable) {
            return false
        }
        return parseApplied(raw)
    }

    private fun jsonOf(raw: String?): JSONObject? {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty() || value == "null") return null
        return try {
            JSONObject(value)
        } catch (t: Throwable) {
            null
        }
    }

    /**
     * Reads the caret's block: its CSS path, its text, and the caret's offset in
     * it, plus the current selection's span in the same coordinates.
     *
     * The walk is over text nodes only, so its offsets are offsets into
     * `textContent` — the same string [Sentences] is handed. Anchoring to the
     * caret's nearest block rather than the whole document keeps a sentence from
     * being measured across a chapter, and keeps the string crossing the bridge
     * small.
     */
    val READ_SCRIPT: String = """
(function () {
  var blocks = /^(P|DIV|LI|BLOCKQUOTE|DD|DT|TD|SECTION|ARTICLE|H1|H2|H3|H4|H5|H6)${'$'}/;
  // An element offset is a child index, not a character offset, so it is
  // resolved to the text node the caret is actually inside.
  var textNodeOf = function (node, offset) {
    if (!node) { return null; }
    if (node.nodeType === 3) { return [node, offset]; }
    var child = node.childNodes[offset] || node.childNodes[offset - 1];
    if (!child) { return null; }
    return [child, child.nodeType === 3 ? child.nodeValue.length : 0];
  };
  try {
    var sel = window.getSelection();
    if (!sel || !sel.anchorNode) { return null; }
    var caret = textNodeOf(sel.anchorNode, sel.anchorOffset);
    if (!caret) { return null; }
    var block = caret[0].parentElement;
    while (block && block !== document.body && !blocks.test(block.tagName)) {
      block = block.parentElement;
    }
    if (!block) { return null; }
    var anchor = textNodeOf(sel.anchorNode, sel.anchorOffset);
    var focus = textNodeOf(sel.focusNode, sel.focusOffset);
    var walker = document.createTreeWalker(block, NodeFilter.SHOW_TEXT, null);
    var text = "";
    var at = -1;
    var selStart = -1;
    var selEnd = -1;
    var n;
    while ((n = walker.nextNode())) {
      if (n === caret[0]) { at = text.length + caret[1]; }
      if (anchor && n === anchor[0]) { selStart = text.length + anchor[1]; }
      if (focus && n === focus[0]) { selEnd = text.length + focus[1]; }
      text += n.nodeValue;
    }
    if (selStart > selEnd) {
      var swap = selStart;
      selStart = selEnd;
      selEnd = swap;
    }
    if (at < 0 || text.length === 0 || text.length > 120000) { return null; }
    var parts = [];
    var el = block;
    while (el && el !== document.body && el.parentElement) {
      var i = 1;
      var sib = el;
      while ((sib = sib.previousElementSibling)) {
        if (sib.tagName === el.tagName) { i++; }
      }
      parts.unshift(el.tagName.toLowerCase() + ":nth-of-type(" + i + ")");
      el = el.parentElement;
    }
    var key = parts.length ? "body>" + parts.join(">") : "body";
    return { key: key, text: text, at: at, selStart: selStart, selEnd: selEnd };
  } catch (e) {
    return null;
  }
})()
    """.trimIndent()

    /**
     * Selects `start`..`end` of the named block.
     *
     * [head] is checked against the block's own opening text before anything is
     * selected: a CSS path can outlive the DOM it described — Readium re-renders
     * a resource when the settings change — and a stale path that resolved to a
     * different paragraph would silently highlight the wrong sentence.
     */
    fun applyScript(key: String, head: String, start: Int, end: Int): String = """
(function () {
  try {
    var block = ${jsString(key)} === "body" ? document.body : document.querySelector(${jsString(key)});
    if (!block) { return false; }
    if (${jsString(head)} && block.textContent.indexOf(${jsString(head)}) !== 0) { return false; }
    var walker = document.createTreeWalker(block, NodeFilter.SHOW_TEXT, null);
    var pos = 0;
    var sn = null;
    var so = 0;
    var en = null;
    var eo = 0;
    var n;
    while ((n = walker.nextNode())) {
      var len = n.nodeValue.length;
      if (sn === null && $start < pos + len) { sn = n; so = $start - pos; }
      if (en === null && $end <= pos + len) { en = n; eo = $end - pos; }
      pos += len;
      if (sn !== null && en !== null) { break; }
    }
    if (sn === null || en === null) { return false; }
    var range = document.createRange();
    range.setStart(sn, so);
    range.setEnd(en, eo);
    if (range.collapsed) { return false; }
    var sel = window.getSelection();
    if (!sel) { return false; }
    sel.removeAllRanges();
    sel.addRange(range);
    return true;
  } catch (e) {
    return false;
  }
})()
    """.trimIndent()

    /** A JavaScript string literal, escaped. */
    private fun jsString(value: String): String {
        val out = StringBuilder("\"")
        for (c in value) {
            when {
                c == '\\' -> out.append("\\\\")
                c == '"' -> out.append("\\\"")
                c == '\n' -> out.append("\\n")
                c == '\r' -> out.append("\\r")
                c == '\t' -> out.append("\\t")
                // A line separator would end a JavaScript string literal early.
                c.code == 0x2028 || c.code == 0x2029 -> out.append(' ')
                c.code < 0x20 -> out.append("\\u%04x".format(c.code))
                else -> out.append(c)
            }
        }
        return out.append("\"").toString()
    }
}
