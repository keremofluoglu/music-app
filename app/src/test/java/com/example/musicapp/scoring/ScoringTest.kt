package com.example.musicapp.scoring

import com.example.musicapp.data.db.EndReason
import com.example.musicapp.data.db.EventSource
import com.example.musicapp.data.db.InitiatedBy
import com.example.musicapp.data.db.ListenMode
import com.example.musicapp.data.db.PlayEventEntity
import com.example.musicapp.data.db.QueueTag
import com.example.musicapp.data.db.RepeatMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.exp

private const val MIN = 60_000L
private const val DAY = 86_400_000L

internal fun event(
    song: String,
    startedAt: Long,
    listenedMs: Long,
    durationMs: Long = 4 * MIN,
    end: EndReason = EndReason.COMPLETED,
    by: InitiatedBy = InitiatedBy.MANUAL,
    contextSize: Int = 1_000,
    repeat: RepeatMode = RepeatMode.OFF,
    session: Long = 1,
    mode: ListenMode = ListenMode.NEW,
    tag: QueueTag? = null,
) = PlayEventEntity(
    songId = song, sessionId = session, startedAt = startedAt, tzOffsetMin = 180,
    listenedMs = listenedMs, durationMs = durationMs, endReason = end, initiatedBy = by,
    modeAtPlay = mode, queueTag = tag, queueContext = "home", contextSize = contextSize,
    repeatMode = repeat, source = EventSource.APP,
)

class FormulasTest {
    private val p = ScoringParams()

    private val dayMin = 24 * 60.0

    @Test
    fun waitBonusTableForNeverPlayedSong() {
        // docs/algoritma.md section 4: 0 / 7 / 14 / 30 days → 1.00 / 1.20 / 1.32 / 1.44
        val expected = mapOf(0.0 to 1.00, 7.0 to 1.20, 14.0 to 1.32, 30.0 to 1.44)
        for ((days, nov) in expected) {
            val w = Formulas.waitBonus(addedClock = 0.0, everPlayed = false, now = days * dayMin, p = p)
            assertEquals("after $days days", nov, Formulas.discovery(1.0, 1.0, w, p), 0.01)
        }
        assertEquals(0.0, Formulas.waitBonus(0.0, everPlayed = true, now = 30 * dayMin, p = p), 0.0)
    }

    @Test
    fun playedSongDiscoveryRecoversSlowlyThroughPlayCountDecay() {
        // 30 listens; L recovers as the decayed count halves every 90 days. Never drops to 0.
        val expected = mapOf(0.0 to 0.61, 7.0 to 0.61, 30.0 to 0.63, 365.0 to 0.88)
        for ((days, nov) in expected) {
            val n = 30 * Formulas.halfLife(days * dayMin, p.lowPlayHalfLifeMin)
            val l = Formulas.lowPlay(n, nTotal = 1_000.0, librarySize = 5_000)
            assertEquals("after $days days", nov, Formulas.discovery(1.0, l, 0.0, p), 0.02)
        }
    }

    @Test
    fun repeatPenaltyTable() {
        // Report: ψ = 4, h_p = 12 h → just played 2 %, 12 h 14 %, 1 day 37 %, 2 days 78 %.
        val fact = EventInterpreter(p).add(event("a", 0, 4 * MIN))
        val base = fact.clock
        val expected = mapOf(0.0 to 0.02, 720.0 to 0.14, 1_440.0 to 0.37, 2_880.0 to 0.78)
        for ((after, weight) in expected) {
            val pen = Formulas.penalty(listOf(fact), qualifiedCount = 5, now = base + after, p = p)
            assertEquals("after $after", weight, Formulas.penaltyWeight(pen, p), 0.01)
        }
    }

    @Test
    fun penaltyRampsUpOverFirstFiveListens() {
        val fact = EventInterpreter(p).add(event("a", 0, 4 * MIN))
        val first = Formulas.penalty(listOf(fact), 1, fact.clock, p)
        val fifth = Formulas.penalty(listOf(fact), 5, fact.clock, p)
        assertEquals(fifth / 5, first, 1e-9)
    }

    @Test
    fun genreGateRange() {
        assertEquals(p.genreFloor, Formulas.genreGate(0.0, p), 1e-9)
        assertEquals(1.0, Formulas.genreGate(1.0, p), 1e-9)
        assertEquals(0.36, Formulas.affinityFromGate(Formulas.genreGate(0.36, p), p), 1e-9)
    }
}

class EventInterpreterTest {
    private val p = ScoringParams()

    @Test
    fun skipStrengthIsProportionalToPosition() {
        val i = EventInterpreter(p)
        val early = i.add(event("a", 0, 5_000, end = EndReason.SKIPPED))
        val half = i.add(event("b", DAY, 2 * MIN, end = EndReason.SKIPPED))
        val late = i.add(event("c", 2 * DAY, (3.4 * MIN).toLong(), end = EndReason.SKIPPED))
        assertEquals(-0.98, early.outcome, 0.01)
        assertEquals(-0.5, half.outcome, 1e-9)
        assertEquals(-0.15, late.outcome, 0.01)
    }

    @Test
    fun smallPlaylistCountsLess() {
        val f = EventInterpreter(p).add(event("a", 0, 4 * MIN, contextSize = 10))
        assertEquals(0.2, f.weight, 1e-9)
        val auto = EventInterpreter(p).add(event("a", 0, 4 * MIN, by = InitiatedBy.AUTO))
        assertEquals(0.5, auto.weight, 1e-9)
    }

    @Test
    fun manualPickThenImmediateSkipIsNetNegative() {
        val f = EventInterpreter(p).add(event("a", 0, 2_000, end = EndReason.SKIPPED))
        val (a, b) = Formulas.evidence(listOf(f), f.clock, p)
        assertEquals(0.25, a, 1e-9)
        assertEquals(0.99, b, 0.01)
    }

    @Test
    fun notNowSkips() {
        val i = EventInterpreter(p)
        val repeatOne = i.add(event("a", 0, 5_000, end = EndReason.SKIPPED, repeat = RepeatMode.ONE))
        assertTrue(repeatOne.notNow)
        assertEquals(0.0, repeatOne.outcome, 0.0)

        val j = EventInterpreter(p)
        j.add(event("b", 0, 4 * MIN))
        val again = j.add(event("b", 10 * MIN, 5_000, end = EndReason.SKIPPED))
        assertTrue(again.notNow)
        val later = j.add(event("b", 2 * DAY, 5_000, end = EndReason.SKIPPED))
        assertFalse(later.notNow)
    }

    @Test
    fun skipWaveSoftensNegativeEvidence() {
        val i = EventInterpreter(p)
        repeat(3) { k -> i.add(event("s$k", k * MIN, 3_000, end = EndReason.SKIPPED)) }
        val fourth = i.add(event("x", 5 * MIN, 3_000, end = EndReason.SKIPPED))
        assertEquals(0.5, fourth.sigma, 0.0)
        val otherSession = i.add(event("y", 6 * MIN, 3_000, end = EndReason.SKIPPED, session = 2))
        assertEquals(1.0, otherSession.sigma, 0.0)
    }

    @Test
    fun sleepTimerAndStopAreNeutral() {
        val i = EventInterpreter(p)
        assertEquals(0.0, i.add(event("a", 0, MIN, end = EndReason.SLEEP_TIMER)).outcome, 0.0)
        assertEquals(0.0, i.add(event("b", DAY, MIN, end = EndReason.STOPPED)).outcome, 0.0)
    }

    @Test
    fun clockIsWallTimeAtTheEndOfThePlay() {
        val f = EventInterpreter(p).add(event("a", 2 * DAY, 3 * MIN))
        assertEquals((2 * DAY + 3 * MIN) / MIN.toDouble(), f.clock, 1e-9)
    }
}

class ScoreEngineTest {
    private val p = ScoringParams()
    private val engine = ScoreEngine(p)

    private fun input(songs: List<SongInfo>, events: List<PlayEventEntity>): EngineInput {
        val i = EventInterpreter(p)
        val facts = i.addAll(events.sortedBy { it.startedAt }).groupBy { it.songId }
        return EngineInput(songs, facts, emptyMap())
    }

    @Test
    fun neverPlayedSongHasZeroRepeatAndGrowingWait() {
        val songs = listOf(SongInfo("played", 0), SongInfo("new", 0))
        val events = (0 until 100).map { event("played", it * 10 * MIN, 4 * MIN) }
        val stats = engine.computeAll(input(songs, events), 14 * DAY).associateBy { it.songId }
        assertEquals(0.0, stats.getValue("new").repeatScore, 0.0)
        assertTrue(stats.getValue("new").waitBonus > 0.3)
        assertEquals(1.0, stats.getValue("played").repeatScore, 0.0)
        assertTrue(stats.getValue("new").discoveryScore > stats.getValue("played").discoveryScore)
    }

    @Test
    fun everyPlayResetsWaitBonusButErrorDoesNot() {
        val songs = listOf(SongInfo("a", 0), SongInfo("b", 0), SongInfo("filler", 0))
        val filler = (0 until 50).map { event("filler", it * 10 * MIN, 4 * MIN) }
        val skipped = event("a", 600 * MIN, 2_000, end = EndReason.SKIPPED)
        val broken = event("b", 601 * MIN, 0, end = EndReason.ERROR)
        val stats = engine.computeAll(input(songs, filler + skipped + broken), 10 * DAY)
            .associateBy { it.songId }
        assertEquals(0.0, stats.getValue("a").waitBonus, 0.0)
        assertTrue(stats.getValue("b").waitBonus > 0.4)
    }

    @Test
    fun playedSongKeepsADiscoveryScoreAndOnlyThePenaltyPushesItBack() {
        // The bug this replaces: discovery dropping to 0 after a play made "Yeni bir şeyler dene"
        // rank the most familiar songs first once everything had been played.
        val songs = listOf(SongInfo("a", 0), SongInfo("b", 0))
        val events = listOf(event("a", DAY, 4 * MIN), event("b", DAY + 10 * MIN, 4 * MIN))
        val now = engine.computeAll(input(songs, events), DAY + 20 * MIN).associateBy { it.songId }
        assertTrue(now.getValue("b").discoveryScore > 0.5)
        assertTrue(now.getValue("b").penalty > now.getValue("a").penalty) // played more recently
        val later = engine.computeAll(input(songs, events), 3 * DAY).associateBy { it.songId }
        assertTrue(later.getValue("b").penalty < now.getValue("b").penalty) // recovers with time
    }

    @Test
    fun favouriteMixesShortAndLongTermMemory() {
        // proje.txt line 25: favori = 0.6 · short_term + 0.4 · long_term.
        // "fresh": one listen just now. "loyal": 40 listens over ~200 days, the last one 10 days ago.
        val songs = listOf(SongInfo("fresh", 0), SongInfo("loyal", 0))
        val now = 300 * DAY
        val events = (0 until 40).map { event("loyal", now - (10 + it * 5) * DAY, 4 * MIN) } +
            event("fresh", now - 10 * MIN, 4 * MIN)
        val stats = engine.computeAll(input(songs, events.sortedBy { it.startedAt }), now).associateBy { it.songId }
        // fresh tops short-term memory, loyal tops long-term memory; neither reaches 1.0 alone.
        val fresh = stats.getValue("fresh").repeatScore
        val loyal = stats.getValue("loyal").repeatScore
        assertTrue("fresh $fresh", fresh >= 0.6 && fresh < 1.0)
        assertTrue("loyal $loyal", loyal >= 0.4 && loyal < 1.0)
    }

    @Test
    fun favouritesStandOutFromTheRest() {
        // 200 songs, Zipf-like listening over the last 60 days: song k is played about 120/k times,
        // on days spread pseudo-randomly (a tail song was not necessarily played yesterday).
        val songs = (1..200).map { SongInfo("s$it", 0) }
        val now = 100 * DAY
        val events = (1..200).flatMap { k ->
            val plays = maxOf(1, 120 / k)
            (0 until plays).map { i ->
                val day = 1 + ((k * 37L + i * 53L) % 60)
                event("s$k", now - day * DAY - (k * 7L + i) * MIN, 4 * MIN)
            }
        }.sortedBy { it.startedAt }
        val fav = engine.computeAll(input(songs, events), now).associate { it.songId to it.repeatScore }
        val favourites = fav.values.count { it >= 0.5 }
        assertTrue("favourite group should be small, was $favourites", favourites in 2..30)
        val tail = (60..200).map { fav.getValue("s$it") }.sorted()
        assertTrue("the tail should sit far below, median was ${tail[tail.size / 2]}", tail[tail.size / 2] < 0.1)
        assertTrue("no tail song reaches the favourite group, max was ${tail.last()}", tail.last() < 0.5)
    }

    /** 60 days, 30 plays a day; `coreShare` of the plays go to the first `core` songs of 300. */
    private fun rotationUser(core: Int, seed: Int, coreShare: Double = 0.8): Pair<List<SongInfo>, List<PlayEventEntity>> {
        val rnd = kotlin.random.Random(seed)
        val now = 100 * DAY
        val songs = (0 until 300).map { SongInfo("s$it", 0) }
        val events = (0 until 60).flatMap { d ->
            (0 until 30).map { k ->
                val song = if (rnd.nextDouble() < coreShare) rnd.nextInt(core) else core + rnd.nextInt(300 - core)
                event("s$song", now - (d + 1) * DAY + k * 20 * MIN, 4 * MIN, by = InitiatedBy.MANUAL)
            }
        }.sortedBy { it.startedAt }
        return songs to events
    }

    @Test
    fun coreRotationIsFoundWhateverItsSize() {
        // proje.txt: some users keep coming back to 20 songs, some to 40 or 60 — no fixed number.
        for (core in listOf(20, 40, 60)) {
            val (songs, events) = rotationUser(core, seed = core)
            val all = EventInterpreter(p).addAll(events)
            val facts = all.groupBy { it.songId }
            val now = 100 * DAY
            val core0 = Formulas.coreScores(Formulas.windowedCounts(all, now, p), p)
            val found = core0.count { it.value >= 0.5 }
            val hits = (0 until core).count { (core0["s$it"] ?: 0.0) >= 0.5 }
            assertTrue("core $core: found $found songs, $hits of the real core", hits == core && found <= core + 3)

            // The favourite score keeps a gap between the whole core and the rest.
            val fav = engine.computeAll(EngineInput(songs, facts, emptyMap()), now).associate { it.songId to it.repeatScore }
            val coreMin = (0 until core).minOf { fav.getValue("s$it") }
            val restMax = (core until 300).maxOf { fav.getValue("s$it") }
            assertTrue("core $core: weakest core song $coreMin vs strongest other $restMax", coreMin > restMax)
        }
    }

    @Test
    fun noCoreIsInventedWhenListeningIsFlat() {
        val (_, events) = rotationUser(core = 300 - 1, seed = 3, coreShare = 1.0)
        val counts = Formulas.windowedCounts(EventInterpreter(p).addAll(events), 100 * DAY, p)
        val scores = Formulas.coreScores(counts, p)
        // Fallback to count / highest count: no artificial split, not even at a drop deep in the tail.
        val max = counts.values.max()
        for ((id, s) in scores) assertEquals(counts.getValue(id) / max, s, 1e-9)
    }

    @Test
    fun oneNightBingeIsNotAFavourite() {
        // 30-song core, plus 5 songs each played 25 times in a single night (repeat-one).
        val (_, base) = rotationUser(core = 30, seed = 7)
        val binges = (0 until 5).flatMap { b ->
            (0 until 25).map { k -> event("s${299 - b}", 100 * DAY - (10 + b) * DAY + k * 5 * MIN, 4 * MIN, repeat = RepeatMode.ONE) }
        }
        val all = EventInterpreter(p).addAll((base + binges).sortedBy { it.startedAt })
        val scores = Formulas.coreScores(Formulas.windowedCounts(all, 100 * DAY, p), p)
        assertTrue("whole core found", (0 until 30).all { (scores["s$it"] ?: 0.0) >= 0.5 })
        assertTrue("no binge song is a favourite", (295..299).all { (scores["s$it"] ?: 0.0) < 0.5 })
    }

    @Test
    fun sharpestDropDoesNotCutBetweenTwoFavourites() {
        // The user's example: 80 and 60 are both favourites; the real break is 30 → 6.
        val counts = listOf(80, 60, 55, 50, 48, 45, 40, 30, 6, 5, 4, 4, 3, 3, 2, 2, 2, 1, 1, 1, 1, 1)
            .mapIndexed { i, c -> "s$i" to c.toDouble() }.toMap()
        val scores = Formulas.coreScores(counts, p)
        assertEquals((0..7).map { "s$it" }.toSet(), scores.filterValues { it >= 0.5 }.keys)
    }

    @Test
    fun shareScoresLeaveAGapAndHandleTies() {
        val s = Formulas.shareScores(mapOf("a" to 6.0, "b" to 2.0, "c" to 1.0, "d" to 1.0, "zero" to 0.0))
        assertEquals(1.0, s.getValue("a"), 1e-9)
        assertEquals(1.0 - 0.6, s.getValue("b"), 1e-9)
        assertEquals(1.0 - 0.8, s.getValue("c"), 1e-9)
        assertEquals(s.getValue("c"), s.getValue("d"), 0.0)
        assertTrue("zero" !in s)
    }

    @Test
    fun modesPickTheRightSongWhenEverythingHasBeenPlayed() {
        // "often" played 20 times over three weeks, "once" played a single time; both last played 3 days ago.
        val songs = listOf(SongInfo("often", 0), SongInfo("once", 0))
        val events = (0 until 20).map { event("often", it * DAY, 4 * MIN) } + event("once", 19 * DAY, 4 * MIN)
        val stats = engine.computeAll(input(songs, events), 22 * DAY).associateBy { it.songId }
        val cands = stats.values.map {
            Candidate(it.songId, emptyList(), it.discoveryScore, it.repeatScore, it.penalty, it.betaA, it.betaB, it.genreAffinity)
        }
        fun firstShare(mode: ListenMode, lambda: Double, id: String): Double {
            val qb = QueueBuilder(p, kotlin.random.Random(11))
            return (0 until 4_000).count { qb.build(cands, mode, lambda).first().songId == id } / 4_000.0
        }
        assertTrue("discovery mode should favour the rarely played song", firstShare(ListenMode.NEW, p.lambdaNew, "once") > 0.6)
        assertTrue("favourites mode should favour the often played song", firstShare(ListenMode.FAV, p.lambdaFav, "often") > 0.6)
    }

    @Test
    fun clockMovedBackwardsGivesFiniteScores() {
        val songs = listOf(SongInfo("a", 0))
        val stats = engine.computeAll(input(songs, listOf(event("a", 10 * DAY, 4 * MIN))), 0)
        val s = stats.single()
        assertTrue(s.discoveryScore.isFinite() && s.repeatScore.isFinite() && s.penalty.isFinite())
    }

    @Test
    fun likedGenreGetsHigherDiscovery() {
        val songs = listOf(SongInfo("rock1", 0), SongInfo("rock2", 0), SongInfo("jazz", 0))
        val genres = mapOf("rock1" to listOf(1L), "rock2" to listOf(1L), "jazz" to listOf(2L))
        val i = EventInterpreter(p)
        val facts = i.addAll((0 until 20).map { event("rock1", it * 5 * MIN, 4 * MIN) }).groupBy { it.songId }
        val stats = engine.computeAll(EngineInput(songs, facts, genres), 120 * MIN)
            .associateBy { it.songId }
        assertTrue(stats.getValue("rock2").discoveryScore > stats.getValue("jazz").discoveryScore)
        assertTrue(stats.getValue("jazz").genreAffinity >= p.genreFloor)
    }

    @Test
    fun songsWithoutGenreAreNeutral() {
        val songs = listOf(SongInfo("a", 0))
        val stats = engine.computeAll(input(songs, emptyList()), 0).single()
        assertEquals(1.0, stats.genreAffinity, 0.0)
        assertEquals(1.0, stats.discoveryScore, 1e-9)
    }

    @Test
    fun fullLibraryIsFast() {
        val songs = (0 until 5_000).map { SongInfo("s$it", 0) }
        val events = (0 until 20_000).map { event("s${(it * 7919) % 5_000}", it * 4 * MIN, 3 * MIN) }
        val inp = input(songs, events)
        engine.computeAll(inp, 90_000 * MIN) // warm-up
        val t0 = System.nanoTime()
        engine.computeAll(inp, 90_000 * MIN)
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue("took $ms ms", ms < 1_000)
    }
}

class QueueBuilderTest {
    /** No Thompson noise, no penalty: the shuffle weight is exactly exp(discovery / T). */
    private val plain = ScoringParams(wQ = 0.0)

    private fun cand(id: String, discovery: Double, artists: List<Long> = emptyList()) =
        Candidate(id, artists, discovery, 0.0, 0.0, 0.0, 0.0, 1.0)

    @Test
    fun gumbelTopKMatchesWeights() {
        val t = plain.tempNew
        val cs = listOf(cand("a", t * kotlin.math.ln(1.0)), cand("b", t * kotlin.math.ln(2.0)), cand("c", t * kotlin.math.ln(7.0)))
        val qb = QueueBuilder(plain, kotlin.random.Random(42))
        val firsts = HashMap<String, Int>()
        val runs = 100_000
        repeat(runs) { firsts.merge(qb.build(cs, ListenMode.NEW, 1.0).first().songId, 1, Int::plus) }
        assertEquals(0.1, firsts.getValue("a") / runs.toDouble(), 0.01)
        assertEquals(0.2, firsts.getValue("b") / runs.toDouble(), 0.01)
        assertEquals(0.7, firsts.getValue("c") / runs.toDouble(), 0.01)
    }

    @Test
    fun sameArtistIsSpacedOut() {
        val cs = listOf(
            cand("a1", 40.0, listOf(1)), cand("a2", 30.0, listOf(1)),
            cand("b", 20.0, listOf(2)), cand("c", 10.0, listOf(3)),
        )
        val order = QueueBuilder(plain, kotlin.random.Random(1)).build(cs, ListenMode.NEW, 1.0).map { it.songId }
        assertEquals(listOf("a1", "b", "c", "a2"), order)
    }

    @Test
    fun wrapGuardKeepsLastPlayedOutOfTheStart() {
        val cs = (0 until 10).map { cand("s$it", 100.0 - it * 10) }
        val order = QueueBuilder(plain, kotlin.random.Random(3))
            .build(cs, ListenMode.NEW, 1.0, avoidFirst = listOf("s0", "s1", "s2")).map { it.songId }
        assertTrue(order.take(3).none { it in setOf("s0", "s1", "s2") })
        assertEquals(10, order.toSet().size)
    }

    @Test
    fun recentlyPlayedSongIsPushedBack() {
        val p = ScoringParams(wQ = 0.0)
        val fresh = Candidate("fresh", emptyList(), 1.0, 0.0, penalty = 0.0, 0.0, 0.0, 1.0)
        val justPlayed = Candidate("played", emptyList(), 1.0, 0.0, penalty = 1.0, 0.0, 0.0, 1.0)
        val qb = QueueBuilder(p, kotlin.random.Random(7))
        val firstIsPlayed = (0 until 10_000).count { qb.build(listOf(fresh, justPlayed), ListenMode.NEW, 1.0).first().songId == "played" }
        // Weight ratio e^-4 ≈ 1.8 %.
        assertEquals(exp(-4.0) / (1 + exp(-4.0)), firstIsPlayed / 10_000.0, 0.01)
    }

    @Test
    fun sameSeedSameOrder() {
        val cs = (0 until 50).map { cand("s$it", it / 50.0, listOf(it % 5L)) }
        val p = ScoringParams()
        val a = QueueBuilder(p, kotlin.random.Random(9)).build(cs, ListenMode.FAV, 0.3)
        val b = QueueBuilder(p, kotlin.random.Random(9)).build(cs, ListenMode.FAV, 0.3)
        assertEquals(a, b)
    }

    @Test
    fun lambdaAdaptsWithinBand() {
        val p = ScoringParams()
        val i = EventInterpreter(p)
        val events = (0 until 20).map {
            event("n$it", it * 10 * MIN, 4 * MIN, by = InitiatedBy.AUTO, tag = QueueTag.NOVEL)
        } + (0 until 20).map {
            event("f$it", (20 + it) * 10 * MIN, 5_000, end = EndReason.SKIPPED, by = InitiatedBy.AUTO, tag = QueueTag.FAMILIAR)
        }
        val facts = i.addAll(events)
        assertEquals(0.85, LambdaTuner.lambda(ListenMode.NEW, facts, p), 1e-9)
        assertEquals(0.7, LambdaTuner.lambda(ListenMode.NEW, facts.take(5), p), 1e-9)
    }

    @Test
    fun betaSamplerMean() {
        val rng = kotlin.random.Random(5)
        val mean = (0 until 20_000).sumOf { BetaSampler.sample(2.0, 5.0, rng) } / 20_000
        assertEquals(2.0 / 7.0, mean, 0.01)
        val small = (0 until 20_000).sumOf { BetaSampler.sample(0.5, 0.5, rng) } / 20_000
        assertEquals(0.5, small, 0.01)
    }
}
