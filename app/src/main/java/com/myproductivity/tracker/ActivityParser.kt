package com.myproductivity.tracker

import java.time.DayOfWeek
import java.time.LocalDate

/**
 * Lightweight, offline, rule-based parser for English + Hinglish activity sentences.
 * No network and no paid AI. Pure Kotlin so it can be unit-tested on the JVM.
 *
 * Anything it is unsure about is returned as an [Issue]; blocking issues must be
 * resolved by the user on the confirmation screen before saving.
 */
object ActivityParser {

    // ---------------------------------------------------------------- public

    fun parse(input: String, today: LocalDate = Config.today()): List<ParsedActivity> {
        val text = input.trim()
        if (text.isEmpty()) return emptyList()
        return splitSegments(text)
            .map { parseSegment(it, today) }
            .filter { it.name.isNotBlank() || it.plannedMin != null || it.actualMin != null }
    }

    // ------------------------------------------------------------ splitting

    private val STRONG_SPLIT = Regex(
        "(?:\\.(?=\\s|$)|[;\\n]+|\\s+(?:and then|then|phir|fir|uske baad|after that)\\s+)",
        RegexOption.IGNORE_CASE
    )
    private val SOFT_SPLIT = Regex("\\s*(?:,|\\band\\b|\\baur\\b|\\+)\\s*", RegexOption.IGNORE_CASE)

    private fun splitSegments(text: String): List<String> {
        val strong = text.split(STRONG_SPLIT).map { it.trim() }.filter { it.isNotEmpty() }
        val out = mutableListOf<String>()
        for (piece in strong) {
            val lower = lc(piece)
            // "1 hour and 30 minutes" must stay together
            if (COMPOUND.containsMatchIn(lower) || SPECIAL_AN_HOUR_HALF.containsMatchIn(lower)) {
                out += piece
                continue
            }
            val parts = piece.split(SOFT_SPLIT).map { it.trim() }.filter { it.isNotEmpty() }
            // "Planned 2 hours coding, actual 90 minutes" is ONE activity, not two
            val continuation = parts.drop(1).any {
                ROLE_START.containsMatchIn(lc(it).trimStart())
            }
            val allHaveDuration = parts.size > 1 && !continuation && parts.all { hasDuration(lc(it)) }
            if (allHaveDuration) out += parts else out += piece
        }
        return out
    }

    private val ROLE_START = Regex(
        "^(?:actual|actually|planned|plan|target|spent|took|taken|total|but|approx|around)\\b"
    )

    private fun hasDuration(lower: String) = findDurations(lower).isNotEmpty()

    /** Length-preserving lowercase, so indexes line up with the original text. */
    private fun lc(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s) sb.append(c.lowercaseChar())
        return sb.toString()
    }

    // ------------------------------------------------------------ durations

    private val NUM_WORDS: Map<String, Double> = mapOf(
        "ek" to 1.0, "one" to 1.0, "do" to 2.0, "two" to 2.0, "teen" to 3.0, "three" to 3.0,
        "char" to 4.0, "chaar" to 4.0, "four" to 4.0, "paanch" to 5.0, "panch" to 5.0, "five" to 5.0,
        "chhe" to 6.0, "chhah" to 6.0, "six" to 6.0, "saat" to 7.0, "seven" to 7.0,
        "aath" to 8.0, "eight" to 8.0, "nau" to 9.0, "nine" to 9.0, "das" to 10.0, "ten" to 10.0,
        "pandrah" to 15.0, "fifteen" to 15.0, "bees" to 20.0, "twenty" to 20.0,
        "tees" to 30.0, "thirty" to 30.0, "chalis" to 40.0, "forty" to 40.0, "pachaas" to 50.0, "fifty" to 50.0
    )
    private val NUM = "(\\d+(?:\\.\\d+)?|" +
        NUM_WORDS.keys.sortedByDescending { it.length }.joinToString("|") + ")"
    private const val HOUR_U = "(?:hours?|hrs?|ghantey|ghante|ghanton|ghanta|h)"
    private const val MIN_U = "(?:minutes?|mins?|minat|min|m)"

    private val COMPOUND = Regex("(?<![\\d.])$NUM\\s*$HOUR_U\\s*(?:and\\s+|aur\\s+|,\\s*)?$NUM\\s*$MIN_U\\b")
    private val SPECIAL_AN_HOUR_HALF = Regex("\\ban?\\s+hour\\s+(?:and\\s+)?a\\s+half\\b")
    private val SPECIAL_FRACTION = Regex("\\b(dedh|dhai|adha|aadha|sava|pauna)\\s*(?:ghanta|ghante|ghanton)\\b")
    private val SPECIAL_HALF = Regex("\\bhalf\\s*(?:an?\\s*)?hour\\b")
    private val SPECIAL_QUARTER = Regex("\\bquarter\\s*(?:of\\s*)?(?:an?\\s*)?hour\\b")
    private val SPECIAL_AN_HOUR = Regex("\\ban?\\s+hour\\b")
    private val HOURS = Regex("(?<![\\d.])$NUM\\s*$HOUR_U\\b")
    private val MINUTES = Regex("(?<![\\d.])$NUM\\s*$MIN_U\\b")

    private enum class Role { PLANNED, ACTUAL }

    private data class DurHit(val start: Int, val end: Int, val minutes: Int, var role: Role? = null)

    private fun num(s: String): Double = s.toDoubleOrNull() ?: NUM_WORDS[s] ?: 0.0

    private fun findDurations(t: String): List<DurHit> {
        val hits = mutableListOf<DurHit>()
        val work = StringBuilder(t)

        fun scan(re: Regex, toMin: (MatchResult) -> Double) {
            for (m in re.findAll(work.toString())) {
                val minutes = Math.round(toMin(m)).toInt()
                if (minutes <= 0) continue
                hits += DurHit(m.range.first, m.range.last + 1, minutes)
                for (i in m.range) work.setCharAt(i, ' ')
            }
        }

        scan(COMPOUND) { m -> num(m.groupValues[1]) * 60 + num(m.groupValues[2]) }
        scan(SPECIAL_AN_HOUR_HALF) { 90.0 }
        scan(SPECIAL_FRACTION) { m ->
            when (m.groupValues[1]) {
                "dedh" -> 90.0; "dhai" -> 150.0; "sava" -> 75.0; "pauna" -> 45.0; else -> 30.0
            }
        }
        scan(SPECIAL_HALF) { 30.0 }
        scan(SPECIAL_QUARTER) { 15.0 }
        scan(SPECIAL_AN_HOUR) { 60.0 }
        scan(HOURS) { m -> num(m.groupValues[1]) * 60 }
        scan(MINUTES) { m -> num(m.groupValues[1]) }
        return hits.sortedBy { it.start }
    }

    private val PLANNED_KW = Regex("planned|plan|planning|target|goal|schedule|budget|expected|estimate|chahiye|allotted")
    private val ACTUAL_KW = Regex(
        "actual|actually|spent|took|taken|did|done|completed|finished|worked|studied|practiced|practised|" +
            "lagaye|lagaya|laga|lage|kiya|kiye|managed|ended up"
    )

    private fun classify(t: String, hits: List<DurHit>) {
        for ((i, h) in hits.withIndex()) {
            val prevEnd = if (i == 0) 0 else hits[i - 1].end
            val nextStart = if (i == hits.lastIndex) t.length else hits[i + 1].start
            val before = t.substring(maxOf(prevEnd, h.start - 24), h.start)
            val after = t.substring(h.end, minOf(nextStart, h.end + 14))
            val p = PLANNED_KW.containsMatchIn(before) || PLANNED_KW.containsMatchIn(after)
            val a = ACTUAL_KW.containsMatchIn(before) || ACTUAL_KW.containsMatchIn(after)
            h.role = when {
                p && !a -> Role.PLANNED
                a && !p -> Role.ACTUAL
                else -> null
            }
        }
    }

    // ----------------------------------------------------- tense / status

    private val INPROG = Regex(
        "\\b(?:working on|in progress|ongoing|currently|right now|abhi|chal raha|chal rahi|chal rha|" +
            "kar raha|kar rahi|kar rha|raha hoon|rahi hoon|started|shuru|begun|half done|partly|partially|continuing)\\b"
    )
    private val DONE = Regex("\\b(?:completed?|done|finished|finish|khatam|ho gaya|hogaya|ho gayi|kar liya|karliya|kar li)\\b")
    private val FUTURE = Regex(
        "\\b(?:plan|planned|planning|plans|will|going to|gonna|want to|need to|have to|must|todo|to-do|to do|" +
            "schedule|scheduled|karunga|karungi|karenge|karna hai|karni hai|karne hai|karna h|jana hai|jaunga|" +
            "jaungi|padhunga|padhungi|chahiye|tomorrow|later|baad mein|upcoming)\\b"
    )
    private val PAST = Regex(
        "\\b(?:did|studied|practiced|practised|attended|worked|ran|walked|read|wrote|went|spent|had|was|were|" +
            "kiya|kiye|padha|padhi|dauda|daudi|gaya|gayi|liya|diya|yesterday|last night)\\b"
    )

    // ----------------------------------------------------------------- date

    private val MONTHS = mapOf(
        "jan" to 1, "feb" to 2, "mar" to 3, "apr" to 4, "may" to 5, "jun" to 6,
        "jul" to 7, "aug" to 8, "sep" to 9, "oct" to 10, "nov" to 11, "dec" to 12
    )
    private const val MONTH_RE =
        "(jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|june?|july?|aug(?:ust)?|sep(?:t(?:ember)?)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?)"

    private val ISO_DATE = Regex("\\b(\\d{4})-(\\d{2})-(\\d{2})\\b")
    private val SLASH_DATE = Regex("(?<![\\d/])(\\d{1,2})/(\\d{1,2})(?:/(\\d{2,4}))?(?![\\d/])")
    private val DAY_MONTH = Regex("\\b(\\d{1,2})(?:st|nd|rd|th)?\\s*(?:of\\s+)?$MONTH_RE\\b")
    private val MONTH_DAY = Regex("\\b$MONTH_RE\\s+(\\d{1,2})(?:st|nd|rd|th)?\\b")

    private val DAY_BEFORE_YESTERDAY = Regex("\\bday before yesterday\\b")
    private val DAY_AFTER_TOMORROW = Regex("\\bday after tomorrow\\b")
    private val YESTERDAY = Regex("\\byesterday\\b")
    private val TOMORROW = Regex("\\btomorrow\\b")
    private val TODAY = Regex("\\b(?:today|aaj|tonight|this morning|this evening|this afternoon)\\b")
    private val PARSO = Regex("\\bparso\\b")
    private val KAL = Regex("\\bkal\\b")

    private val WEEKDAYS: Map<String, DayOfWeek> = mapOf(
        "monday" to DayOfWeek.MONDAY, "somvar" to DayOfWeek.MONDAY, "somwar" to DayOfWeek.MONDAY,
        "tuesday" to DayOfWeek.TUESDAY, "mangalvar" to DayOfWeek.TUESDAY, "mangalwar" to DayOfWeek.TUESDAY,
        "wednesday" to DayOfWeek.WEDNESDAY, "budhvar" to DayOfWeek.WEDNESDAY, "budhwar" to DayOfWeek.WEDNESDAY,
        "thursday" to DayOfWeek.THURSDAY, "guruvar" to DayOfWeek.THURSDAY, "guruwar" to DayOfWeek.THURSDAY,
        "veervar" to DayOfWeek.THURSDAY, "friday" to DayOfWeek.FRIDAY, "shukravar" to DayOfWeek.FRIDAY,
        "shukrawar" to DayOfWeek.FRIDAY, "saturday" to DayOfWeek.SATURDAY, "shanivar" to DayOfWeek.SATURDAY,
        "shaniwar" to DayOfWeek.SATURDAY, "sunday" to DayOfWeek.SUNDAY, "ravivar" to DayOfWeek.SUNDAY,
        "raviwar" to DayOfWeek.SUNDAY
    )
    private val WEEKDAY_RE = Regex(
        "\\b(?:(last|previous|pichhle|pichle|next|agle|aglay|this|is)\\s+)?(" +
            WEEKDAYS.keys.sortedByDescending { it.length }.joinToString("|") + ")\\b"
    )

    private class DateResult(val date: LocalDate, val issue: Issue? = null)

    private fun blank(work: StringBuilder, range: IntRange) {
        for (i in range) work.setCharAt(i, ' ')
    }

    private fun safeDate(y: Int, m: Int, d: Int): LocalDate? =
        try { LocalDate.of(y, m, d) } catch (e: Exception) { null }

    private fun resolveDate(
        work: StringBuilder,
        today: LocalDate,
        past: Boolean,
        future: Boolean
    ): DateResult {
        var s = work.toString()

        ISO_DATE.find(s)?.let { m ->
            val d = safeDate(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())
            if (d != null) { blank(work, m.range); return DateResult(d) }
        }
        SLASH_DATE.find(s)?.let { m ->
            var y = m.groupValues[3].ifEmpty { today.year.toString() }.toInt()
            if (y < 100) y += 2000
            val d = safeDate(y, m.groupValues[2].toInt(), m.groupValues[1].toInt()) // India: dd/mm
            if (d != null) { blank(work, m.range); return DateResult(d) }
        }
        DAY_MONTH.find(s)?.let { m ->
            val mon = MONTHS[m.groupValues[2].take(3)]!!
            val d = safeDate(today.year, mon, m.groupValues[1].toInt())
            if (d != null) { blank(work, m.range); return DateResult(d) }
        }
        MONTH_DAY.find(s)?.let { m ->
            val mon = MONTHS[m.groupValues[1].take(3)]!!
            val d = safeDate(today.year, mon, m.groupValues[2].toInt())
            if (d != null) { blank(work, m.range); return DateResult(d) }
        }

        DAY_BEFORE_YESTERDAY.find(s)?.let { blank(work, it.range); return DateResult(today.minusDays(2)) }
        DAY_AFTER_TOMORROW.find(s)?.let { blank(work, it.range); return DateResult(today.plusDays(2)) }
        YESTERDAY.find(s)?.let { blank(work, it.range); return DateResult(today.minusDays(1)) }
        TOMORROW.find(s)?.let { blank(work, it.range); return DateResult(today.plusDays(1)) }
        TODAY.find(s)?.let { blank(work, it.range); return DateResult(today) }

        // Hindi "kal" / "parso" mean either past or future — use the tense to decide.
        for ((re, n, word) in listOf(Triple(PARSO, 2L, "parso"), Triple(KAL, 1L, "kal"))) {
            val m = re.find(s) ?: continue
            blank(work, m.range)
            return when {
                future && !past -> DateResult(today.plusDays(n))
                past && !future -> DateResult(today.minusDays(n))
                else -> DateResult(
                    today.minusDays(n),
                    Issue(
                        "date",
                        "\"$word\" can mean ${if (n == 1L) "yesterday or tomorrow" else "2 days ago or 2 days ahead"}. Please pick the date.",
                        true
                    )
                )
            }
        }

        s = work.toString()
        WEEKDAY_RE.find(s)?.let { m ->
            blank(work, m.range)
            val target = WEEKDAYS[m.groupValues[2]]!!
            val back = (today.dayOfWeek.value - target.value + 7) % 7
            val ahead = (target.value - today.dayOfWeek.value + 7) % 7
            return when (m.groupValues[1]) {
                "last", "previous", "pichhle", "pichle" ->
                    DateResult(today.minusDays(if (back == 0) 7L else back.toLong()))
                "next", "agle", "aglay" ->
                    DateResult(today.plusDays(if (ahead == 0) 7L else ahead.toLong()))
                else -> when {
                    past && !future -> DateResult(today.minusDays(back.toLong()))
                    future && !past -> DateResult(today.plusDays(if (ahead == 0) 7L else ahead.toLong()))
                    else -> DateResult(
                        today.plusDays(ahead.toLong()),
                        Issue("date", "Is that last ${m.groupValues[2]} or the coming one? Please pick the date.", true)
                    )
                }
            }
        }
        return DateResult(today)
    }

    // ------------------------------------------------------------- priority

    private val LOW_P = Regex("\\b(?:low\\s+priority|priority\\s*(?:is\\s*)?low|p3|optional|not\\s+urgent|kam\\s+zaroori|kam\\s+zaruri)\\b")
    private val MED_P = Regex("\\b(?:medium\\s+priority|priority\\s*(?:is\\s*)?medium|normal\\s+priority|p2)\\b")
    private val HIGH_P = Regex(
        "\\b(?:high\\s+priority|priority\\s*(?:is\\s*)?high|top\\s+priority|urgent|critical|asap|p1|important|" +
            "bahut\\s+zaroori|zaroori|zaruri|jaruri|jaroori)\\b"
    )

    private fun resolvePriority(work: StringBuilder): String {
        val s = work.toString()
        LOW_P.find(s)?.let { blank(work, it.range); return "Low" }
        MED_P.find(s)?.let { blank(work, it.range); return "Medium" }
        HIGH_P.find(s)?.let { blank(work, it.range); return "High" }
        return "Medium"
    }

    // ------------------------------------------------------------- category

    private val CATEGORY_PATTERNS: Map<String, Regex> = mapOf(
        "Career & Learning" to Regex(
            "\\b(?:study|studied|studying|course|learn|learning|learned|tutorial|lecture|class|exam|certification|" +
                "interview|leetcode|dsa|kotlin|java|python|coding|programming|android|sql|resume|linkedin|upskill|" +
                "padhai|padha|padhi|padhna|seekh|seekha|assignment|homework|mock test|udemy|coursera|revision|jobs?)\\b"
        ),
        "Fitness & Health" to Regex(
            "\\b(?:gym|workout|work out|exercise|run|running|ran|jog|jogging|walk|walking|yoga|cardio|swim|swimming|" +
                "cycling|cycle|sports?|football|cricket|badminton|stretch|stretching|pushups?|push-ups?|squats?|sleep|" +
                "nap|diet|doctor|medicine|physio|health|steps|kasrat|vyayam|dauda|daudi|tehelna|protein|hiit|" +
                "weights|trek|hike)\\b"
        ),
        "Personal Development" to Regex(
            "\\b(?:journal|journaling|meditation|meditate|mindfulness|gratitude|reflection|reflect|habit|book|books|" +
                "reading|read|podcast|hobby|guitar|piano|drawing|painting|writing|blog|self-improvement|affirmations?|" +
                "breathing|pranayam|dhyaan|diary)\\b"
        ),
        "Work" to Regex(
            "\\b(?:office|meetings?|clients?|project|projects|report|reports|emails?|standup|stand-up|presentation|" +
                "deadline|sprint|code review|deploy|deployment|manager|boss|ticket|tickets|jira|work|at work)\\b"
        ),
        "Personal Tasks" to Regex(
            "\\b(?:shopping|groceries|grocery|sabzi|kharidari|cleaning|clean|safai|laundry|bills?|bank|cooking|cook|" +
                "khana|family|errands?|repair|mom|dad|mummy|papa|travel|appointment|recharge|pay|paying|rent|ghar|" +
                "house|garden|pet|dog|car|passport|visa|tax|insurance)\\b"
        )
    )

    private fun resolveCategory(t: String, issues: MutableList<Issue>): String? {
        val scores = CATEGORY_PATTERNS.mapValues { (_, re) -> re.findAll(t).count() }
        val max = scores.values.maxOrNull() ?: 0
        if (max == 0) {
            issues += Issue("category", "I couldn't tell the category. Please pick one.", true)
            return null
        }
        val top = scores.filter { it.value == max }.keys.toList()
        if (top.size > 1) {
            issues += Issue("category", "Could be ${top.joinToString(" or ")}. Please pick one.", true)
            return null
        }
        return top.first()
    }

    // --------------------------------------------------------------- naming

    private val STRONG_NOISE = setOf(
        "priority", "planned", "plan", "actual", "actually", "spent", "took", "completed", "complete",
        "finished", "done", "kiya", "kiye", "kar", "liya", "raha", "rahi", "rha", "hoon", "hu", "hai", "hain",
        "tha", "thi", "gaya", "gayi", "ho", "karunga", "karungi", "karenge", "karna", "karni", "will", "going",
        "gonna", "aaj", "today", "kal", "parso", "yesterday", "tomorrow", "tonight", "abhi", "currently",
        "started", "maine", "mene", "main", "mai", "mujhe", "i", "my", "me", "we", "ne", "approx",
        "approximately", "around", "roughly", "also", "bhi", "aur", "and", "then", "phir", "fir", "toh", "did"
    )
    private val WEAK_NOISE = setOf(
        "a", "an", "the", "for", "of", "to", "on", "in", "at", "with", "ke", "ki", "ka", "liye", "se", "ko",
        "mein", "me", "ek", "some", "session", "time", "wala", "wali", "ye", "yeh", "woh", "wo", "is", "was",
        "were", "do", "does", "just", "only", "total", "about", "minutes", "minute", "min", "mins", "hours",
        "hour", "hr", "hrs", "ghante", "ghanta", "but", "that", "it", "this"
    )

    private fun cleanName(original: String, work: StringBuilder): String {
        val sb = StringBuilder(original.length)
        for (i in original.indices) sb.append(if (work[i] == ' ') ' ' else original[i])
        val tokens = sb.toString()
            .split(Regex("[\\s,;:!?()\"]+"))
            .map { it.trim('.', '-', '\'') }
            .filter { it.isNotEmpty() }
            .filter { it.lowercase() !in STRONG_NOISE }
            .toMutableList()
        while (tokens.isNotEmpty() && tokens.first().lowercase() in WEAK_NOISE) tokens.removeAt(0)
        while (tokens.isNotEmpty() && tokens.last().lowercase() in WEAK_NOISE) tokens.removeAt(tokens.lastIndex)
        var name = tokens.joinToString(" ")
        if (name.length > 80) name = name.take(80).trimEnd()
        return name.replaceFirstChar { it.uppercaseChar() }
    }

    // --------------------------------------------------------------- one segment

    private fun parseSegment(segment: String, today: LocalDate): ParsedActivity {
        val t = lc(segment)
        val issues = mutableListOf<Issue>()
        val work = StringBuilder(t)

        // 1) durations (and consume them from `work`)
        val hits = findDurations(t)
        classify(t, hits)
        for (h in hits) blank(work, h.start until h.end)

        // 2) tense signals
        val hasActualDur = hits.any { it.role == Role.ACTUAL }
        val inProg = INPROG.containsMatchIn(t)
        val done = DONE.containsMatchIn(t)
        val future = FUTURE.containsMatchIn(t)
        val past = PAST.containsMatchIn(t) || done || hasActualDur

        // 3) date, priority
        val dateResult = resolveDate(work, today, past, future)
        dateResult.issue?.let { issues += it }
        val date = dateResult.date
        val priority = resolvePriority(work)

        // 4) status
        val status = when {
            inProg -> Config.STATUS_IN_PROGRESS
            done || hasActualDur -> Config.STATUS_COMPLETED
            future -> Config.STATUS_PLANNED
            past -> Config.STATUS_COMPLETED
            date.isAfter(today) -> Config.STATUS_PLANNED
            else -> {
                issues += Issue(
                    "status",
                    "Not sure if this is planned, in progress or completed — I assumed Completed. Change it if needed.",
                    false
                )
                Config.STATUS_COMPLETED
            }
        }

        // 5) assign planned / actual
        var planned: Int? = null
        var actual: Int? = null
        when (hits.size) {
            0 -> if (status == Config.STATUS_COMPLETED) {
                issues += Issue("duration", "No duration found.", false)
            }
            1 -> {
                val h = hits[0]
                when {
                    h.role == Role.PLANNED -> planned = h.minutes
                    h.role == Role.ACTUAL -> actual = h.minutes
                    status == Config.STATUS_PLANNED -> planned = h.minutes
                    else -> actual = h.minutes
                }
            }
            else -> {
                val p = hits.firstOrNull { it.role == Role.PLANNED }
                val a = hits.firstOrNull { it.role == Role.ACTUAL }
                when {
                    p != null && a != null -> { planned = p.minutes; actual = a.minutes }
                    p != null -> { planned = p.minutes; actual = hits.first { it !== p }.minutes }
                    a != null -> { actual = a.minutes; planned = hits.first { it !== a }.minutes }
                    else -> {
                        planned = hits[0].minutes
                        actual = hits[1].minutes
                        issues += Issue(
                            "duration",
                            "I took the first time as planned and the second as actual. Please check.",
                            false
                        )
                    }
                }
                if (hits.size > 2) {
                    issues += Issue("duration", "Only the first two durations were used.", false)
                }
            }
        }

        // 6) category (from the whole sentence) and name (what is left)
        val category = resolveCategory(t, issues)
        val name = cleanName(segment, work)
        if (name.isBlank()) {
            issues += Issue("name", "I couldn't find an activity name. Please type one.", true)
        }

        return ParsedActivity(name, category, date, planned, actual, priority, status, issues)
    }
}
