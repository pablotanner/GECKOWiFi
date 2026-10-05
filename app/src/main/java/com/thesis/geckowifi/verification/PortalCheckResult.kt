package com.thesis.geckowifi.verification

import com.thesis.geckowifi.data.model.VerificationResult
import com.thesis.geckowifi.discovery.ObservedHop

/** One hop and its verdict; [result] is null for transit hops that weren't judged. */
data class HopVerdict(val hop: ObservedHop, val result: VerificationResult?)

/** Outcome of [VerificationEngine.verifyPortalHops]: the network's verdict plus every hop. */
data class PortalCheckResult(val overall: VerificationResult, val hops: List<HopVerdict>)
