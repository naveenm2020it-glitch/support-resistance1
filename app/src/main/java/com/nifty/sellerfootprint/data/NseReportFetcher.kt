package com.nifty.sellerfootprint.data

import java.time.LocalDate

interface NseReportFetcher {
    fun downloadUdiffBhavcopyCsv(tradeDate: LocalDate): Result<String>
    fun downloadParticipantOiCsv(tradeDate: LocalDate): Result<String>
    fun downloadIndexSnapshotCsv(tradeDate: LocalDate): Result<String>
    fun downloadFoVolatilityCsv(tradeDate: LocalDate): Result<String>
}
