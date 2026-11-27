package com.nifty.sellerfootprint.model

import java.time.LocalDate
import java.time.LocalDateTime

enum class OptionSide {
    CE, PE
}

enum class StrikeBelt(val label: String) {
    CORE_GAMMA("Zone I: Core Gamma Belt (0% to 1.2%)"),
    PRIMARY_WALL("Zone II: Primary Seller Wall Belt (1.2% to 3.5%)"),
    TAIL_HEDGE("Zone III: Tail-Hedge Margin Wing (> 3.5%)")
}

data class OptionContractRecord(
    val tradeDate: LocalDate,
    val expiryDate: LocalDate,
    val strikePrice: Double,
    val optionType: OptionSide,
    val openPrice: Double,
    val highPrice: Double,
    val lowPrice: Double,
    val closePrice: Double,
    val prevClosePrice: Double,
    val settlePrice: Double,
    val underlyingPrice: Double,
    val openInterestUnits: Long,
    val changeInOiUnits: Long,
    val totalTradedVolumeUnits: Long,
    val totalTurnoverValue: Double,
    val totalTradesExecuted: Long,
    val lotSize: Int
) {
    val openInterestLots: Double
        get() = if (lotSize > 0) openInterestUnits.toDouble() / lotSize else openInterestUnits.toDouble()

    val changeInOiLots: Double
        get() = if (lotSize > 0) changeInOiUnits.toDouble() / lotSize else changeInOiUnits.toDouble()

    val tradedVolumeLots: Double
        get() = if (lotSize > 0) totalTradedVolumeUnits.toDouble() / lotSize else totalTradedVolumeUnits.toDouble()

    val effectivePrice: Double
        get() = if (settlePrice > 0.0) settlePrice else closePrice
}

data class ParticipantOiRecord(
    val tradeDate: LocalDate,
    val clientType: String, // Client, DII, FII, Pro, TOTAL
    val optionIndexCallLong: Long,
    val optionIndexPutLong: Long,
    val optionIndexCallShort: Long,
    val optionIndexPutShort: Long
)

data class IndexSnapshotRecord(
    val tradeDate: LocalDate,
    val niftyOpen: Double,
    val niftyHigh: Double,
    val niftyLow: Double,
    val niftyClose: Double,
    val pointsChange: Double,
    val percentChange: Double,
    val indiaVixClose: Double?
)

data class DailyVolatilityRecord(
    val tradeDate: LocalDate,
    val underlyingClose: Double,
    val dailyVolatility: Double,
    val annualizedVolatility: Double
)

data class SessionMarketData(
    val sessionIndex: Int, // 1..22
    val tradeDate: LocalDate,
    val contracts: List<OptionContractRecord>,
    val participantOi: List<ParticipantOiRecord>,
    val indexSnapshot: IndexSnapshotRecord?,
    val volatilityRecord: DailyVolatilityRecord?,
    val missingOptionalReports: List<String> = emptyList()
) {
    val underlyingClose: Double
        get() = indexSnapshot?.niftyClose
            ?: contracts.firstOrNull { it.underlyingPrice > 0.0 }?.underlyingPrice
            ?: 0.0

    val underlyingHigh: Double
        get() = indexSnapshot?.niftyHigh?.takeIf { it > 0.0 } ?: underlyingClose

    val underlyingLow: Double
        get() = indexSnapshot?.niftyLow?.takeIf { it > 0.0 } ?: underlyingClose
}

data class ExpiryOption(
    val expiryDate: LocalDate,
    val dteCalendarDays: Long,
    val isMonthly: Boolean,
    val totalActiveContracts: Int,
    val totalOpenInterestLots: Long
) {
    val displayLabel: String
        get() {
            val typeLabel = if (isMonthly) "Monthly" else "Weekly"
            return "$expiryDate ($typeLabel • ${dteCalendarDays}d DTE)"
        }
}

data class StrikeMicrostructureMetrics(
    val tradeDate: LocalDate,
    val expiryDate: LocalDate,
    val strikePrice: Double,
    val optionType: OptionSide,
    val distanceFromSpotPct: Double,
    val belt: StrikeBelt,
    val oiLots: Double,
    val deltaOiLots: Double,
    val zDeltaOi: Double,
    val settlePrice: Double,
    val priceChange: Double,
    val excessPriceDecay: Double,
    val positionalRetentionRatio: Double, // PRR = |deltaOI| / volume
    val averageTradeSizeLots: Double,
    val atsRatioToMedian: Double,
    val persistenceSessions: Int,
    val activeDefenseSessions: Int,
    val isPairedWithOppositeRoll: Boolean,
    val oppositeRollSourceStrike: Double?
)

enum class PatternId(
    val code: String,
    val title: String,
    val originPhase: String
) {
    P1_ABSORPTION_WRITING("P1", "Absorption Writing (Price-OI Divergence)", "Phase A (Sessions 1–17)"),
    P2_MULTI_SESSION_ANCHOR("P2", "Multi-Session Active Anchor Wall", "Phase A (Sessions 1–17)"),
    P3_INTRADAY_REJECTION_OI("P3", "Intraday Rejection Wick + OI Addition", "Phase A (Sessions 1–17)"),
    P4_STRANGLE_SKEW_PIN("P4", "Asymmetric Strangle Skew Pin", "Phase A (Sessions 1–17)"),
    P5_VERTICAL_DEFENSIVE_ROLL("P5", "Vertical Defensive Roll", "Phase A (Sessions 1–17)"),
    P6_SHORT_COVERING_CAPITULATION("P6", "Short-Covering Capitulation (Veto Signal)", "Phase A (Sessions 1–17)"),
    P7_TAIL_WING_MARGIN_HEDGE("P7", "Tail-Wing Margin Hedge (Noise Filter)", "Phase A (Sessions 1–17)"),
    NP1_CALENDAR_ROLLOVER_WALL("NP1", "Monthly-to-Weekly Calendar Rollover Wall", "Phase B (Sessions 18–21)"),
    NP2_VOL_EXPANSION_HARVEST("NP2", "Volatility-Expansion Premium Harvest", "Phase B (Sessions 18–21)"),
    NP3_GAMMA_PIN_MAGNET("NP3", "Near-Expiry ATM Gamma-Pin Magnet", "Phase B (Sessions 18–21)")
}

enum class PatternValidationStatus(val label: String) {
    VALIDATED_TIER_1("Validated Tier-1 (Held/Reversed in Sessions 18–21)"),
    FILTER_RULE("Active Noise-Exclusion Filter"),
    VETO_RULE("Active Contradiction Veto Rule"),
    NOVEL_PHASE_B("Novel Phase-B Pattern (Sessions 18–21)"),
    REJECTED_OVERFITTED("Rejected (Failed Out-of-Sample Validation)")
}

enum class ZoneOutcomeClass(val label: String) {
    HELD("Held"),
    REVERSED("Probed & Reversed"),
    BROKE("Broke (False Positive)")
}

data class FeaturePredictiveContribution(
    val featureName: String,
    val calibratedWeight: Double,
    val empiricalDefenseLift: Double,
    val sampleObservations: Int
)

data class DiscoveredPatternRecord(
    val patternId: PatternId,
    val occurrencesInDiscovery: Int,
    val inSampleHoldRate: Double,
    val inSampleReversalRate: Double,
    val outOfSampleOccurrences: Int,
    val outOfSampleHoldRate: Double?,
    val outOfSampleReversalRate: Double?,
    val outOfSampleBreakRate: Double?,
    val status: PatternValidationStatus,
    val evidenceSummary: String
)

enum class ZoneEvidenceStrength(val label: String) {
    STRONG("Strong"),
    MODERATE("Moderate"),
    WEAK("Weak"),
    INCONCLUSIVE("Inconclusive")
}

enum class ZoneRole(val code: String, val title: String) {
    S1("S1", "S1 Support"),
    S2("S2", "S2 Support"),
    R1("R1", "R1 Resistance"),
    R2("R2", "R2 Resistance")
}

data class FootprintZone(
    val role: ZoneRole,
    val isEstablished: Boolean,
    val coreStrike: Double?,
    val lowerBoundary: Double?,
    val upperBoundary: Double?,
    val widthPoints: Double?,
    val strength: ZoneEvidenceStrength,
    val confidenceScore: Double?, // Null unless statistically justified (N >= 5 validation trials)
    val confidenceBasis: String,
    val relevantStrikes: List<Double>,
    val optionSide: OptionSide?,
    val supportingPatterns: List<String>,
    val sellerFootprintExplanation: String,
    val historicalValidationResult: String,
    val totalOiLots: Long,
    val deltaOiLots: Long,
    val settlePriceAvg: Double,
    val positionalRetentionRatio: Double,
    val atsRatio: Double,
    val persistenceSessions: Int,
    val participantAlignment: String,
    val validationSummary: String,
    val session22TestResult: String,
    val selectionReason: String
)

data class ResearchAuditSummary(
    val sessionWindowDates: List<LocalDate>,
    val activeStrikeRangeMin: Double,
    val activeStrikeRangeMax: Double,
    val totalStrikesScanned: Int,
    val strikesRetainedInEnvelope: Int,
    val featureContributions: List<FeaturePredictiveContribution>,
    val phase1Patterns: List<DiscoveredPatternRecord>,
    val phase2ValidationNotes: List<String>,
    val phase2NewPatterns: List<DiscoveredPatternRecord>,
    val combinedPatternSet: List<DiscoveredPatternRecord>,
    val session22MatchedPatterns: List<String>,
    val session22UnvalidatedObservations: List<String>,
    val strongestZoneSummary: String,
    val weakestZoneSummary: String,
    val persistentAreasSummary: String,
    val newlyDetectedFootprintsSummary: String,
    val shiftingPositioningSummary: String,
    val limitationsSummary: List<String>
)

data class AnalysisResult(
    val selectedTradingDate: LocalDate,
    val selectedExpiry: ExpiryOption,
    val availableExpiries: List<ExpiryOption>,
    val niftyClose: Double,
    val niftyPointsChange: Double,
    val niftyPercentChange: Double,
    val niftyHigh: Double,
    val niftyLow: Double,
    val indiaVix: Double?,
    val dataStatusMessage: String,
    val lastAnalyzedTimestamp: LocalDateTime,
    val s1: FootprintZone,
    val s2: FootprintZone,
    val r1: FootprintZone,
    val r2: FootprintZone,
    val auditSummary: ResearchAuditSummary
)

data class TempStorageStats(
    val fileCount: Int,
    val totalBytes: Long,
    val sessionFoldersCount: Int,
    val storagePathDisplay: String
) {
    val formattedSize: String
        get() = when {
            totalBytes < 1024L -> "$totalBytes B"
            totalBytes < 1024L * 1024L -> String.format("%.1f KB", totalBytes / 1024.0)
            else -> String.format("%.2f MB", totalBytes / (1024.0 * 1024.0))
        }
}
