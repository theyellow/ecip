import { describe, it, expect } from 'vitest'
import { canChangeKnowledgeItem } from './permissions'

describe('canChangeKnowledgeItem', () => {
  it('lets ADMIN change global items', () => {
    expect(canChangeKnowledgeItem('ADMIN', null)).toBe(true)
  })
  it('does not let tenant roles change global items', () => {
    expect(canChangeKnowledgeItem('TENANT_ADMIN', null)).toBe(false)
    expect(canChangeKnowledgeItem('MODERATOR', undefined)).toBe(false)
  })
  it('lets tenant roles change tenant-owned items', () => {
    expect(canChangeKnowledgeItem('TENANT_ADMIN', 'a-tenant-id')).toBe(true)
  })
})
