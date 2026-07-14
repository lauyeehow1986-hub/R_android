package com.rmobile.console.data.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class ExecutionEngineChoiceTest {
    @Test fun `fromStorage defaults to LOCAL for unknown or null`() {
        assertEquals(ExecutionEngineChoice.LOCAL, ExecutionEngineChoice.fromStorage(null))
        assertEquals(ExecutionEngineChoice.LOCAL, ExecutionEngineChoice.fromStorage("nonsense"))
    }
    @Test fun `round-trips through storage keys`() {
        assertEquals(ExecutionEngineChoice.REMOTE, ExecutionEngineChoice.fromStorage(ExecutionEngineChoice.REMOTE.storageKey))
        assertEquals(ExecutionEngineChoice.LOCAL, ExecutionEngineChoice.fromStorage(ExecutionEngineChoice.LOCAL.storageKey))
    }

    @Test fun `resolve prefers the project engine`() {
        assertEquals(
            ExecutionEngineChoice.REMOTE,
            ExecutionEngineChoice.resolve(ExecutionEngineChoice.REMOTE, ExecutionEngineChoice.LOCAL),
        )
    }

    @Test fun `resolve falls back to the default when the project engine is null`() {
        assertEquals(
            ExecutionEngineChoice.REMOTE,
            ExecutionEngineChoice.resolve(null, ExecutionEngineChoice.REMOTE),
        )
    }
}
