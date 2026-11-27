package com.nifty.sellerfootprint.engine

import com.nifty.sellerfootprint.model.OptionContractRecord
import com.nifty.sellerfootprint.model.OptionSide
import com.nifty.sellerfootprint.model.SessionMarketData
import com.nifty.sellerfootprint.model.StrikeBelt
import com.nifty.sellerfootprint.model.StrikeMicrostructureMetrics
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

object MultiStrikeAnalyzer {

    data class ChainEnvelopeResult(
        val metrics: List<StrikeMicrostructureMetrics>,
        val envelopeMinStrike: Double,
        val envelopeMaxStrike: Double,
        val totalStrikesInExpiry: Int,
        val retainedStrikesCount: Int
    )

    private fun isReliableContract(c: OptionContractRecord): Boolean {
        if (!c.strikePrice.isFinite() || c.strikePrice <= 0.0) return false
        if (c.lotSize <= 0 || c.openInterestUnits < 0L) return false
        if (!c.effectivePrice.isFinite() || c.effectivePrice <= 0.0) return false
        if (c.highPrice > 0.0 && c.lowPrice > 0.0 && c.highPrice < c.lowPrice) return false
        return true
    }

    /**
     * Dynamically discovers the relevant active strike envelope for [session] and [targetExpiry]
     * without restricting to ATM or a fixed strike count:
     * 1. Validates market data integrity (spot > 0, valid positive prices/lot sizes/non-negative OI).
     * 2. Retains contiguous strikes centered on spot capturing 95% of cumulative CE+PE OI mass.
     * 3. Bounds outer tail noise via a 2.5-sigma volatility horizon derived from FOVOLT or realized returns.
     * 4. Computes per-strike seller microstructure features, distinguishing active seller defense
     *    from passive/stale high OI.
     */
    fun analyzeSessionChain(
        session: SessionMarketData,
        targetExpiry: LocalDate,
        priorSessions: List<SessionMarketData>
    ): ChainEnvelopeResult {
        val spot = session.underlyingClose
        if (!spot.isFinite() || spot <= 0.0) {
            return ChainEnvelopeResult(emptyList(), 0.0, 0.0, 0, 0)
        }

        val expiryContracts = session.contracts
            .filter { it.expiryDate == targetExpiry && isReliableContract(it) }
        if (expiryContracts.isEmpty()) {
            return ChainEnvelopeResult(emptyList(), 0.0, 0.0, 0, 0)
        }

        val allStrikesSorted = expiryContracts.map { it.strikePrice }.distinct().sorted()
        val totalOiAllStrikes = expiryContracts.sumOf { max(0.0, it.openInterestLots) }
        if (totalOiAllStrikes <= 0.0) {
            return ChainEnvelopeResult(emptyList(), 0.0, 0.0, allStrikesSorted.size, 0)
        }

        // 1. 95% Cumulative OI Mass envelope centered around spot
        val strikesByDistance = allStrikesSorted.sortedBy { abs(it - spot) }
        val oiByStrike = expiryContracts.groupBy { it.strikePrice }
            .mapValues { (_, list) -> list.sumOf { max(0.0, it.openInterestLots) } }

        var cumulativeOi = 0.0
        val massSelectedStrikes = mutableSetOf<Double>()
        for (k in strikesByDistance) {
            massSelectedStrikes.add(k)
            cumulativeOi += oiByStrike[k] ?: 0.0
            if (cumulativeOi / totalOiAllStrikes >= 0.95) {
                break
            }
        }

        val minMassStrike = massSelectedStrikes.minOrNull() ?: (spot * 0.965)
        val maxMassStrike = massSelectedStrikes.maxOrNull() ?: (spot * 1.035)

        // 2. 2.5-Sigma Volatility Horizon Envelope
        val dailyVol = resolveDailyVolatility(session, priorSessions)
        val dte = max(1L, ChronoUnit.DAYS.between(session.tradeDate, targetExpiry))
        val sigmaMove = spot * dailyVol * sqrt(dte.toDouble()) * 2.5
        val volLower = spot - max(sigmaMove, spot * 0.025)
        val volUpper = spot + max(sigmaMove, spot * 0.025)

        val envelopeMin = max(min(minMassStrike, volLower), allStrikesSorted.first())
        val envelopeMax = min(max(maxMassStrike, volUpper), allStrikesSorted.last())

        val retainedContracts = expiryContracts.filter {
            it.strikePrice in envelopeMin..envelopeMax
        }
        val retainedStrikesCount = retainedContracts.map { it.strikePrice }.distinct().size

        if (retainedContracts.isEmpty()) {
            return ChainEnvelopeResult(emptyList(), envelopeMin, envelopeMax, allStrikesSorted.size, 0)
        }

        // Cross-sectional statistics for Z-score & median ATS normalization
        val deltaOiValues = retainedContracts.map { it.changeInOiLots }
        val meanDeltaOi = deltaOiValues.average()
        val stdDeltaOi = standardDeviation(deltaOiValues, meanDeltaOi).let { if (it < 1.0) 1.0 else it }

        val atsValues = retainedContracts.mapNotNull { c ->
            if (c.totalTradesExecuted > 0 && c.tradedVolumeLots > 0.0) {
                c.tradedVolumeLots / c.totalTradesExecuted.toDouble()
            } else null
        }.sorted()
        val medianAts = if (atsValues.isNotEmpty()) atsValues[atsValues.size / 2] else 1.0

        val prevSession = priorSessions.lastOrNull()
        val spotDelta = if (prevSession != null && prevSession.underlyingClose > 0.0) {
            spot - prevSession.underlyingClose
        } else {
            session.indexSnapshot?.pointsChange ?: 0.0
        }

        val zBySideAndStrike = retainedContracts.associate { c ->
            Pair(c.optionType, c.strikePrice) to ((c.changeInOiLots - meanDeltaOi) / stdDeltaOi)
        }

        val metricsList = retainedContracts.map { contract ->
            val distPct = ((contract.strikePrice - spot) / spot) * 100.0
            val absDistPct = abs(distPct)
            val belt = when {
                absDistPct <= 1.2 -> StrikeBelt.CORE_GAMMA
                absDistPct <= 3.5 -> StrikeBelt.PRIMARY_WALL
                else -> StrikeBelt.TAIL_HEDGE
            }

            val zDelta = (contract.changeInOiLots - meanDeltaOi) / stdDeltaOi
            val actualPriceChange = if (contract.prevClosePrice > 0.0) {
                contract.effectivePrice - contract.prevClosePrice
            } else {
                0.0
            }

            val moneynessDecay = exp(-0.22 * absDistPct)
            val approxDelta = when (contract.optionType) {
                OptionSide.CE -> if (contract.strikePrice <= spot) min(0.95, 0.5 + 0.45 * (1.0 - moneynessDecay)) else 0.5 * moneynessDecay
                OptionSide.PE -> if (contract.strikePrice >= spot) -min(0.95, 0.5 + 0.45 * (1.0 - moneynessDecay)) else -0.5 * moneynessDecay
            }
            val expectedPriceChange = spotDelta * approxDelta
            // Negative excessPriceDecay means option premium decayed faster than delta predicts (seller absorption)
            val excessDecay = actualPriceChange - expectedPriceChange

            val prr = if (contract.tradedVolumeLots > 0.0) {
                abs(contract.changeInOiLots) / contract.tradedVolumeLots
            } else {
                0.0
            }

            val ats = if (contract.totalTradesExecuted > 0) {
                contract.tradedVolumeLots / contract.totalTradesExecuted.toDouble()
            } else {
                0.0
            }
            val atsRatio = if (medianAts > 0.0) ats / medianAts else 1.0

            val (persistence, activeDefense) = computePersistenceAndActiveDefense(
                strike = contract.strikePrice,
                side = contract.optionType,
                targetExpiry = targetExpiry,
                priorSessions = priorSessions
            )

            val rollSource = detectVerticalRollSource(
                strike = contract.strikePrice,
                side = contract.optionType,
                zDelta = zDelta,
                spot = spot,
                zMap = zBySideAndStrike
            )

            StrikeMicrostructureMetrics(
                tradeDate = session.tradeDate,
                expiryDate = targetExpiry,
                strikePrice = contract.strikePrice,
                optionType = contract.optionType,
                distanceFromSpotPct = distPct,
                belt = belt,
                oiLots = contract.openInterestLots,
                deltaOiLots = contract.changeInOiLots,
                zDeltaOi = zDelta,
                settlePrice = contract.effectivePrice,
                priceChange = actualPriceChange,
                excessPriceDecay = excessDecay,
                positionalRetentionRatio = prr,
                averageTradeSizeLots = ats,
                atsRatioToMedian = atsRatio,
                persistenceSessions = persistence,
                activeDefenseSessions = activeDefense,
                isPairedWithOppositeRoll = rollSource != null,
                oppositeRollSourceStrike = rollSource
            )
        }.sortedWith(compareBy({ it.strikePrice }, { it.optionType }))

        return ChainEnvelopeResult(
            metrics = metricsList,
            envelopeMinStrike = envelopeMin,
            envelopeMaxStrike = envelopeMax,
            totalStrikesInExpiry = allStrikesSorted.size,
            retainedStrikesCount = retainedStrikesCount
        )
    }

    private fun resolveDailyVolatility(
        current: SessionMarketData,
        priorSessions: List<SessionMarketData>
    ): Double {
        current.volatilityRecord?.dailyVolatility?.takeIf { it.isFinite() && it in 0.002..0.10 }?.let { return it }

        val closes = (priorSessions.takeLast(15) + current)
            .map { it.underlyingClose }
            .filter { it.isFinite() && it > 0.0 }
        if (closes.size >= 4) {
            val logReturns = (1 until closes.size).map { i -> ln(closes[i] / closes[i - 1]) }
            val mean = logReturns.average()
            val sd = standardDeviation(logReturns, mean)
            if (sd.isFinite() && sd in 0.002..0.10) return sd
        }
        return 0.0095
    }

    /**
     * Separates passive static OI persistence from sessions where sellers actively defended
     * the strike (positive OI addition with positive retention or non-expanding premium).
     */
    private fun computePersistenceAndActiveDefense(
        strike: Double,
        side: OptionSide,
        targetExpiry: LocalDate,
        priorSessions: List<SessionMarketData>
    ): Pair<Int, Int> {
        var streak = 0
        var activeDefenseCount = 0
        for (past in priorSessions.asReversed()) {
            val sameExpiry = past.contracts.filter {
                it.expiryDate == targetExpiry && it.optionType == side && isReliableContract(it)
            }
            val fallbackAll = if (sameExpiry.isNotEmpty()) {
                sameExpiry
            } else {
                past.contracts.filter { it.optionType == side && isReliableContract(it) }
            }
            if (fallbackAll.isEmpty()) break

            val sortedOi = fallbackAll.map { it.openInterestLots }.sortedDescending()
            // Use a ceiling so a small option chain still includes the full top-20% group.
            // Flooring 8 strikes to one rank incorrectly excluded the second strongest wall.
            val topThresholdIdx = max(0, kotlin.math.ceil(sortedOi.size * 0.20).toInt() - 1)
            val topThresholdOi = sortedOi[topThresholdIdx]

            val match = fallbackAll.firstOrNull { it.strikePrice == strike } ?: break
            val breached = when (side) {
                OptionSide.CE -> past.underlyingClose > match.strikePrice + min(25.0, match.effectivePrice * 0.45)
                OptionSide.PE -> past.underlyingClose < match.strikePrice - min(25.0, match.effectivePrice * 0.45)
            }
            if (!breached && match.openInterestLots >= topThresholdOi && match.openInterestLots > 0.0) {
                streak++
                val prr = if (match.tradedVolumeLots > 0.0) abs(match.changeInOiLots) / match.tradedVolumeLots else 0.0
                val priceNotExpanding = match.prevClosePrice <= 0.0 || match.effectivePrice <= match.prevClosePrice * 1.03
                if (match.changeInOiLots > 0.0 && prr >= 0.12 && priceNotExpanding) {
                    activeDefenseCount++
                }
            } else {
                break
            }
        }
        return Pair(streak, activeDefenseCount)
    }

    private fun detectVerticalRollSource(
        strike: Double,
        side: OptionSide,
        zDelta: Double,
        spot: Double,
        zMap: Map<Pair<OptionSide, Double>, Double>
    ): Double? {
        if (zDelta < 1.20) return null
        val stepCandidates = listOf(50.0, 100.0, 150.0, 200.0, 250.0)
        for (step in stepCandidates) {
            val innerStrike = when (side) {
                OptionSide.CE -> strike - step
                OptionSide.PE -> strike + step
            }
            val innerValidSide = when (side) {
                OptionSide.CE -> innerStrike >= spot - 100.0
                OptionSide.PE -> innerStrike <= spot + 100.0
            }
            if (!innerValidSide) continue
            val innerZ = zMap[Pair(side, innerStrike)] ?: continue
            if (innerZ <= -1.15) {
                return innerStrike
            }
        }
        return null
    }

    private fun standardDeviation(values: List<Double>, mean: Double): Double {
        if (values.size < 2) return 0.0
        val variance = values.sumOf { (it - mean) * (it - mean) } / (values.size - 1)
        return sqrt(max(0.0, variance))
    }
}
