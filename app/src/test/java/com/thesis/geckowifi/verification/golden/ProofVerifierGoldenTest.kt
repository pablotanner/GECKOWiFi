package com.thesis.geckowifi.verification.golden

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * Uses the reference query (not the port's encoder), so a verifier failure
 * can't be caused by an encoder bug.
 */
@RunWith(Parameterized::class)
class ProofVerifierGoldenTest(private val v: ProofVector, @Suppress("unused") private val name: String) {

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{1}")
        fun data(): List<Array<Any>> = GoldenVectors.golden.proofs.map { arrayOf(it, it.name) }
    }

    @Test
    fun matchesReference() {
        val q = GoldenVectors.query(v.query)
        val result = runCatching {
            UnderTest.verify(
                GoldenVectors.b64(v.responseB64),
                q.xy.map { it.value to it.len },
                q.minAlt,
                q.maxAlt,
                GoldenVectors.publicKeyDer,
            )
        }

        if (v.accept) {
            val hashes = result.getOrElse { throw AssertionError("reference accepts, port rejects: ${it.message}", it) }
            assertEquals("cert hashes", v.certHashes.toSet(), hashes)
        } else if (result.isSuccess) {
            fail("reference rejects (${v.err}), port accepts")
        }
    }
}