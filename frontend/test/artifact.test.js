import assert from 'node:assert/strict'
import test from 'node:test'

import { hasAgentArtifact } from '../src/artifact.js'

const codeConversation = { mode: 'CODE', artifactAvailable: true }

test('does not treat an uploaded project as an agent result', () => {
  assert.equal(hasAgentArtifact(codeConversation, null), false)
})

test('accepts a completed run with a successful file mutation', () => {
  const latestRun = {
    status: 'COMPLETED',
    result: {
      toolSteps: [
        { toolName: 'read_file', success: true },
        { toolName: 'edit_file', success: true },
      ],
    },
  }

  assert.equal(hasAgentArtifact(codeConversation, latestRun), true)
})

test('rejects failed runs and unsuccessful mutations', () => {
  assert.equal(hasAgentArtifact(codeConversation, {
    status: 'FAILED',
    toolSteps: [{ toolName: 'write_file', success: true }],
  }), false)
  assert.equal(hasAgentArtifact(codeConversation, {
    status: 'COMPLETED',
    toolSteps: [{ toolName: 'write_file', success: false }],
  }), false)
})

test('does not show a delivery result for read-only runs or chat conversations', () => {
  const latestRun = {
    status: 'COMPLETED',
    toolSteps: [{ toolName: 'read_file', success: true }],
  }

  assert.equal(hasAgentArtifact(codeConversation, latestRun), false)
  assert.equal(hasAgentArtifact({ mode: 'CHAT', artifactAvailable: false }, latestRun), false)
})
