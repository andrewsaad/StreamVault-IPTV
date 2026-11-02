package com.streamvault.feature.live.sports

import java.text.Normalizer
import java.util.Locale

internal object SportsTextNormalizer {
    private val combiningMarkPattern = Regex("\\p{Mn}+")
    private val nonLetterOrNumberPattern = Regex("[^\\p{L}\\p{N}]+")
    private val whitespacePattern = Regex("\\s+")

    fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFD)
        .replace(combiningMarkPattern, "")
        .lowercase(Locale.ROOT)
        .replace('&', ' ')
        .replace(nonLetterOrNumberPattern, " ")
        .replace(whitespacePattern, " ")
        .trim()
}

internal object SportsAliasRegistry {
    private val clubPrefixPattern = Regex("\\b(?:fc|cf|sc|ac|club)\\b")
    private val nonNameWordPattern = Regex("\\b(?:football|basketball|the)\\b")
    private val whitespacePattern = Regex("\\s+")
    private val aliases = mapOf(
        "barca" to "barcelona",
        "barca fc" to "barcelona",
        "man utd" to "manchester united",
        "man united" to "manchester united",
        "manchester utd" to "manchester united",
        "man u" to "manchester united",
        "mufc" to "manchester united",
        "manchester united" to "manchester united",
        "man city" to "manchester city",
        "manchester city" to "manchester city",
        "atleti" to "atletico madrid",
        "atletico de madrid" to "atletico madrid",
        "atletico madrid" to "atletico madrid",
        "psg" to "paris saint germain",
        "paris sg" to "paris saint germain",
        "paris saint germain" to "paris saint germain",
        "bayern" to "bayern munich",
        "bayern munchen" to "bayern munich",
        "bayern munich" to "bayern munich",
        "borussia dortmund" to "borussia dortmund",
        "bvb" to "borussia dortmund",
        "juve" to "juventus",
        "juventus" to "juventus",
        "inter milan" to "inter milan",
        "la lakers" to "los angeles lakers",
        "los angeles lakers" to "los angeles lakers",
        "gs warriors" to "golden state warriors",
        "golden state" to "golden state warriors",
        "golden state warriors" to "golden state warriors",
    )

    fun normalizeTeamName(value: String): String {
        val normalized = SportsTextNormalizer.normalize(value)
            .replace(clubPrefixPattern, " ")
            .replace(nonNameWordPattern, " ")
            .replace(whitespacePattern, " ")
            .trim()
        return aliases[normalized] ?: normalized
    }

    fun aliasesForCanonical(value: String): Set<String> {
        val canonical = normalizeTeamName(value)
        return buildSet {
            add(canonical)
            aliases.forEach { (alias, target) -> if (target == canonical) add(alias) }
        }
    }
}

internal data class SportsLeague(
    val id: String,
    val displayName: String,
    val sport: SportType,
    val aliases: Set<String>,
)

internal object SportsLeagueRegistry {
    private data class Definition(
        val displayName: String,
        val sport: SportType,
        val aliases: List<String>,
    )

    // Arabic aliases are the exact category names beIN's Arabic guide uses; bare forms such as
    // "دوري أبطال أوروبا" are left out because they would also match the youth and lower leagues.
    private val definitions = listOf(
        Definition(
            "UEFA Champions League",
            SportType.FOOTBALL,
            listOf("UEFA Champions League", "Champions League", "UCL", "دوري أبطال أوروبا لكرة القدم"),
        ),
        Definition(
            "AFC Champions League",
            SportType.FOOTBALL,
            listOf("AFC Champions League Elite", "AFC Champions League Two", "AFC Champions League", "دوري أبطال آسيا"),
        ),
        Definition("UEFA Youth League", SportType.FOOTBALL, listOf("UEFA Youth League", "دوري أبطال أوروبا للشباب")),
        Definition("UEFA Europa League", SportType.FOOTBALL, listOf("UEFA Europa League", "Europa League", "UEL", "الدوري الأوروبي")),
        Definition(
            "UEFA Conference League",
            SportType.FOOTBALL,
            listOf("UEFA Conference League", "UEFA Europa Conference League", "Conference League", "UECL"),
        ),
        Definition(
            "EFL Championship",
            SportType.FOOTBALL,
            listOf(
                "EFL Championship", "English Football League Championship",
                "Sky Bet Championship", "SkyBet Championship",
                "EFL English Football League SkyBet Championship",
                "English League - Championship", "دوري البطولة الإنجليزية - الدرجة الأولى",
            ),
        ),
        Definition("EFL Cup", SportType.FOOTBALL, listOf("Carabao Cup", "English League - Cup", "كأس الرابطة الإنكليزي")),
        Definition(
            "Premier League",
            SportType.FOOTBALL,
            listOf("Premier League", "English Premier League", "EPL", "الدوري الإنجليزي الممتاز"),
        ),
        Definition(
            "Segunda División",
            SportType.FOOTBALL,
            listOf("Segunda División", "Spanish League - Segunda Division", "الدوري الأسباني للدرجة الثانية"),
        ),
        Definition("La Liga", SportType.FOOTBALL, listOf("La Liga", "LaLiga", "الدوري الإسباني لكرة القدم")),
        Definition("Serie A", SportType.FOOTBALL, listOf("Serie A")),
        // Série B precedes Série A: equal longest aliases keep list order, and Série A owns the bare "Brasileirão".
        Definition(
            "Brasileirão Série B",
            SportType.FOOTBALL,
            listOf(
                "Brasileirão Série B", "Campeonato Brasileiro Série B", "Série B do Brasileirão",
                "Série B do futebol masculino brasileiro",
            ),
        ),
        Definition(
            "Brasileirão Série A",
            SportType.FOOTBALL,
            listOf(
                "Brasileirão Série A", "Brasileirão", "Campeonato Brasileiro Série A",
                "Série A do Brasileirão", "Série A do futebol masculino brasileiro",
            ),
        ),
        Definition("Copa do Brasil", SportType.FOOTBALL, listOf("Copa do Brasil")),
        Definition(
            "Copa Libertadores",
            SportType.FOOTBALL,
            listOf("Copa Libertadores", "CONMEBOL Libertadores", "Libertadores"),
        ),
        Definition(
            "Copa Sul-Americana",
            SportType.FOOTBALL,
            listOf("Copa Sul-Americana", "Sul-Americana", "Sulamericana", "Copa Sudamericana", "CONMEBOL Sudamericana"),
        ),
        Definition("Bundesliga", SportType.FOOTBALL, listOf("Bundesliga", "1 Bundesliga")),
        Definition("Ligue 2", SportType.FOOTBALL, listOf("Ligue 2", "الدوري الفرنسي - الدرجة الثانية")),
        Definition("Ligue 1", SportType.FOOTBALL, listOf("Ligue 1", "الدوري الفرنسي")),
        Definition("FA Cup", SportType.FOOTBALL, listOf("FA Cup")),
        Definition("Eredivisie", SportType.FOOTBALL, listOf("Eredivisie")),
        Definition("Primeira Liga", SportType.FOOTBALL, listOf("Primeira Liga")),
        Definition("Major League Soccer", SportType.FOOTBALL, listOf("Major League Soccer", "MLS")),
        Definition("NBA", SportType.BASKETBALL, listOf("NBA", "الدوري الأميركي لكرة السلة")),
        Definition("WNBA", SportType.BASKETBALL, listOf("WNBA")),
        Definition("EuroLeague", SportType.BASKETBALL, listOf("EuroLeague", "Euro League")),
        Definition("LNB Pro A", SportType.BASKETBALL, listOf("LNB Pro A")),
        Definition("NFL", SportType.AMERICAN_FOOTBALL, listOf("NFL")),
        Definition("MLB", SportType.BASEBALL, listOf("MLB")),
        Definition("NHL", SportType.HOCKEY, listOf("NHL")),
        Definition("ATP Tour", SportType.TENNIS, listOf("ATP Tour", "ATP Masters")),
        Definition("WTA Tour", SportType.TENNIS, listOf("WTA Tour")),
        Definition("Wimbledon", SportType.TENNIS, listOf("Wimbledon")),
        Definition("Formula 1", SportType.MOTORSPORT, listOf("Formula 1", "Formula One", "F1")),
        Definition("NASCAR", SportType.MOTORSPORT, listOf("NASCAR")),
        Definition("MotoGP", SportType.MOTORSPORT, listOf("MotoGP")),
        Definition("UFC", SportType.MMA, listOf("UFC")),
        Definition("PGA Tour", SportType.GOLF, listOf("PGA Tour")),
        Definition("LPGA Tour", SportType.GOLF, listOf("LPGA Tour")),
        Definition("Indian Premier League", SportType.CRICKET, listOf("Indian Premier League", "IPL")),
        Definition("Six Nations", SportType.RUGBY, listOf("Six Nations", "Six Nations Championship")),
        Definition("World Surf League", SportType.OTHER, listOf("World Surf League", "WSL")),
    ).map { definition ->
        definition to definition.aliases.map(SportsTextNormalizer::normalize)
    }.sortedByDescending { (_, aliases) ->
        // Priority comes from the Latin aliases only, so adding a long Arabic name does not reorder leagues.
        aliases.filter { alias -> alias.all { it.code < ARABIC_BLOCK_START } }.maxOfOrNull(String::length) ?: 0
    }

    fun detect(text: String): SportsLeague? = detectNormalized(SportsTextNormalizer.normalize(text))

    /**
     * First matching league in priority order, except that a match whose alias sits inside another
     * match's alias is dropped: "AFC Champions League" beats the bare "Champions League", and
     * "Ligue 2"'s full Arabic name beats Ligue 1's prefix of it.
     */
    fun detectNormalized(normalizedText: String): SportsLeague? {
        val searchableText = " " + normalizedText + " "
        val matches = definitions.mapNotNull { entry ->
            entry.second.filter { alias -> searchableText.contains(" $alias ") }
                .maxByOrNull(String::length)
                ?.let { matchedAlias -> entry to matchedAlias }
        }
        return matches
            .firstOrNull { (_, alias) ->
                matches.none { (_, other) -> other.length > alias.length && " $other ".contains(" $alias ") }
            }
            ?.first
            ?.toLeague()
    }

    private const val ARABIC_BLOCK_START = 0x0600

    fun aliasesFor(displayName: String?): Set<String> {
        val normalized = displayName?.let(SportsTextNormalizer::normalize).orEmpty()
        if (normalized.isBlank()) return emptySet()
        return definitions.firstOrNull { (definition, aliases) ->
            SportsTextNormalizer.normalize(definition.displayName) == normalized ||
                aliases.any { alias -> alias == normalized }
        }?.toLeague()?.aliases.orEmpty()
    }

    private fun Pair<Definition, List<String>>.toLeague(): SportsLeague {
        val definition = first
        return SportsLeague(
            id = SportsTextNormalizer.normalize(definition.displayName).replace(' ', '-'),
            displayName = definition.displayName,
            sport = definition.sport,
            aliases = second.toSet(),
        )
    }
}
