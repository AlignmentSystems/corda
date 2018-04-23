/*
 * R3 Proprietary and Confidential
 *
 * Copyright (c) 2018 R3 Limited.  All rights reserved.
 *
 * The intellectual and technical concepts contained herein are proprietary to R3 and its suppliers and are protected by trade secret law.
 *
 * Distribution of this file or any portion thereof via any medium without the express permission of R3 is strictly prohibited.
 */

package net.corda.core.utilities

object SgxSupport {
    @JvmStatic
    val isInsideEnclave: Boolean by lazy {
        (System.getProperty("os.name") == "Linux") && (System.getProperty("java.vm.name") == "Avian (Corda)")
    }
}
