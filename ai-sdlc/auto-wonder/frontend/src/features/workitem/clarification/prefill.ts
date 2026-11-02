/**
 * Workitem squad/agent prefill persistence.
 *
 * Clarification and delivery keep separate memories: the clarification panel
 * remembers its own selection, and the Start Delivery modal remembers its own,
 * so one entry never preselects the other.
 */

export interface WorkitemPrefill {
  squadId: number;
  agentId: number;
  updatedAt: number;
}

const CLARIFY_KEY_PREFIX = 'aw:clarification-prefill:';
const DELIVERY_KEY_PREFIX = 'aw:delivery-prefill:';
const TTL_MS = 1000 * 60 * 60 * 24 * 30; // 30 days

function storage(): Storage | null {
  try {
    return typeof window !== 'undefined' ? window.localStorage : null;
  } catch {
    return null;
  }
}

function keyFor(prefix: string, workitemId: number | string): string {
  return `${prefix}${workitemId}`;
}

function readPrefill(prefix: string, workitemId: number | string): WorkitemPrefill | null {
  const store = storage();
  if (!store) return null;
  const raw = store.getItem(keyFor(prefix, workitemId));
  if (!raw) return null;
  try {
    const parsed = JSON.parse(raw) as WorkitemPrefill;
    if (
      typeof parsed.squadId !== 'number' ||
      typeof parsed.agentId !== 'number' ||
      Date.now() - parsed.updatedAt > TTL_MS
    ) {
      store.removeItem(keyFor(prefix, workitemId));
      return null;
    }
    return parsed;
  } catch {
    store.removeItem(keyFor(prefix, workitemId));
    return null;
  }
}

function writePrefill(
  prefix: string,
  workitemId: number | string,
  prefill: Omit<WorkitemPrefill, 'updatedAt'>,
): void {
  const store = storage();
  if (!store) return;
  const payload: WorkitemPrefill = {
    ...prefill,
    updatedAt: Date.now(),
  };
  try {
    store.setItem(keyFor(prefix, workitemId), JSON.stringify(payload));
  } catch {
    // Storage full or disabled; ignore.
  }
}

function clearPrefill(prefix: string, workitemId: number | string): void {
  storage()?.removeItem(keyFor(prefix, workitemId));
}

export function readClarificationPrefill(workitemId: number | string): WorkitemPrefill | null {
  return readPrefill(CLARIFY_KEY_PREFIX, workitemId);
}

export function writeClarificationPrefill(
  workitemId: number | string,
  prefill: Omit<WorkitemPrefill, 'updatedAt'>,
): void {
  writePrefill(CLARIFY_KEY_PREFIX, workitemId, prefill);
}

export function clearClarificationPrefill(workitemId: number | string): void {
  clearPrefill(CLARIFY_KEY_PREFIX, workitemId);
}

export function readDeliveryPrefill(workitemId: number | string): WorkitemPrefill | null {
  return readPrefill(DELIVERY_KEY_PREFIX, workitemId);
}

export function writeDeliveryPrefill(
  workitemId: number | string,
  prefill: Omit<WorkitemPrefill, 'updatedAt'>,
): void {
  writePrefill(DELIVERY_KEY_PREFIX, workitemId, prefill);
}

export function clearDeliveryPrefill(workitemId: number | string): void {
  clearPrefill(DELIVERY_KEY_PREFIX, workitemId);
}
