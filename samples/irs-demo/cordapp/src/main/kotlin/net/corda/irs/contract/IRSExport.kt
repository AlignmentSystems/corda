/*
 * R3 Proprietary and Confidential
 *
 * Copyright (c) 2018 R3 Limited.  All rights reserved.
 *
 * The intellectual and technical concepts contained herein are proprietary to R3 and its suppliers and are protected by trade secret law.
 *
 * Distribution of this file or any portion thereof via any medium without the express permission of R3 is strictly prohibited.
 */

package net.corda.irs.contract

fun InterestRateSwap.State.exportIRSToCSV(): String =
        "Fixed Leg\n" + FixedRatePaymentEvent.CSVHeader + "\n" +
                this.calculation.fixedLegPaymentSchedule.toSortedMap().values.map { it.asCSV() }.joinToString("\n") + "\n" +
                "Floating Leg\n" + FloatingRatePaymentEvent.CSVHeader + "\n" +
                this.calculation.floatingLegPaymentSchedule.toSortedMap().values.map { it.asCSV() }.joinToString("\n") + "\n"
