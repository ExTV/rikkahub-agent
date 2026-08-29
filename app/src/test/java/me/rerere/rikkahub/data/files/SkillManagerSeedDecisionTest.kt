package me.rerere.rikkahub.data.files

import org.junit.Assert.assertEquals
import org.junit.Test

class SkillManagerSeedDecisionTest {
    @Test
    fun `owned directory with matching hash is skipped`() {
        val decision = decideSeedAction(
            ownedByUs = true,
            targetDirExists = true,
            targetDirNonEmpty = true,
            bundledHash = "abc123",
            storedHash = "abc123",
        )

        assertEquals(SeedDecision.SKIP, decision)
    }

    @Test
    fun `owned directory with differing hash is re-seeded`() {
        val decision = decideSeedAction(
            ownedByUs = true,
            targetDirExists = true,
            targetDirNonEmpty = true,
            bundledHash = "abc123",
            storedHash = "old-hash",
        )

        assertEquals(SeedDecision.SEED, decision)
    }

    @Test
    fun `unowned non-empty directory is skipped regardless of hash`() {
        val decision = decideSeedAction(
            ownedByUs = false,
            targetDirExists = true,
            targetDirNonEmpty = true,
            bundledHash = "abc123",
            storedHash = "old-hash",
        )

        assertEquals(SeedDecision.SKIP, decision)
    }

    @Test
    fun `missing directory is seeded`() {
        val decision = decideSeedAction(
            ownedByUs = false,
            targetDirExists = false,
            targetDirNonEmpty = false,
            bundledHash = "abc123",
            storedHash = "",
        )

        assertEquals(SeedDecision.SEED, decision)
    }

    @Test
    fun `unowned empty directory is seeded`() {
        val decision = decideSeedAction(
            ownedByUs = false,
            targetDirExists = true,
            targetDirNonEmpty = false,
            bundledHash = "abc123",
            storedHash = "",
        )

        assertEquals(SeedDecision.SEED, decision)
    }

    // #84: a bundled skill the user deleted must not be re-seeded, in either branch.

    @Test
    fun `deleted core skill is skipped even with a stale hash`() {
        val decision = decideSeedAction(
            ownedByUs = true,
            targetDirExists = false,
            targetDirNonEmpty = false,
            bundledHash = "abc123",
            storedHash = "",
            deletedByUser = true,
        )

        assertEquals(SeedDecision.SKIP, decision)
    }

    @Test
    fun `deleted non-core skill is skipped even when directory is missing`() {
        val decision = decideSeedAction(
            ownedByUs = false,
            targetDirExists = false,
            targetDirNonEmpty = false,
            bundledHash = "abc123",
            storedHash = "",
            deletedByUser = true,
        )

        assertEquals(SeedDecision.SKIP, decision)
    }

    @Test
    fun `a skill that was never deleted still seeds`() {
        val decision = decideSeedAction(
            ownedByUs = false,
            targetDirExists = false,
            targetDirNonEmpty = false,
            bundledHash = "abc123",
            storedHash = "",
            deletedByUser = false,
        )

        assertEquals(SeedDecision.SEED, decision)
    }

    @Test
    fun `deleting a bundled skill records its name`() {
        val updated = deletedBundledSkillsAfterDelete(
            current = emptySet(),
            deletedName = "my-skill",
            isBundled = true,
        )

        assertEquals(setOf("my-skill"), updated)
    }

    @Test
    fun `deleting an unknown or user skill name never enters the set`() {
        val updated = deletedBundledSkillsAfterDelete(
            current = emptySet(),
            deletedName = "user-created-skill",
            isBundled = false,
        )

        assertEquals(emptySet<String>(), updated)
    }
}
