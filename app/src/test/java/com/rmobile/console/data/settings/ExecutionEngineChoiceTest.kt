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
}
