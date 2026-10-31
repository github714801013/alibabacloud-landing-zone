/**
 * AI 协作默认值：空间默认与浏览器本地覆盖的合并、解析与持久化。
 *
 * 本地覆盖按字段生效：字段未出现 = 跟随空间默认；字段为 null = 显式取消；
 * 字段有值 = 本地覆盖。空间默认来自 /api/workspaces/current/ai-defaults。
 */

export interface ClarifySpaceDefaults {
  defaultSquadId: number | null;
  defaultAgentId: number | null;
  autoSendFirstPrompt: boolean;
  valid: boolean;
}

export interface DeliverySpaceDefaults {
  defaultSquadId: number | null;
  defaultAgentId: number | null;
  valid: boolean;
}

export interface LocalClarifyDefaults {
  defaultSquadId?: number | null;
  defaultAgentId?: number | null;
  autoSendFirstPrompt?: boolean;
}

export interface LocalDeliveryDefaults {
  defaultSquadId?: number | null;
  defaultAgentId?: number | null;
}

export interface ClarifyDefaults {
  defaultSquadId: number | null;
  defaultAgentId: number | null;
  autoSendFirstPrompt: boolean;
}

export interface DeliveryDefaults {
  defaultSquadId: number | null;
  defaultAgentId: number | null;
}

export interface TargetSelection {
  squadId: number;
  agentId: number;
}

export interface ResolvedSelection {
  selection: TargetSelection | null;
  /** 默认档存在但不可用（半配置或已失效），供面板显示轻提示。 */
  defaultSkipped: boolean;
}

export const CLARIFY_LOCAL_KEY = 'aw:clarify-defaults:local';
export const DELIVERY_LOCAL_KEY = 'aw:delivery-defaults:local';

function storage(): Storage | null {
  try {
    return typeof window !== 'undefined' ? window.localStorage : null;
  } catch {
    return null;
  }
}

function readObject<T>(key: string): T {
  const store = storage();
  if (!store) return {} as T;
  const raw = store.getItem(key);
  if (!raw) return {} as T;
  try {
    const parsed = JSON.parse(raw);
    return parsed && typeof parsed === 'object' && !Array.isArray(parsed)
      ? parsed as T
      : {} as T;
  } catch {
    store.removeItem(key);
    return {} as T;
  }
}

function normalizeId(value: unknown): number | null | undefined {
  if (value === undefined) return undefined;
  if (value === null) return null;
  return typeof value === 'number' && Number.isFinite(value) && value > 0 ? value : undefined;
}

function normalizeFlag(value: unknown): boolean | undefined {
  return typeof value === 'boolean' ? value : undefined;
}

export function readLocalClarifyDefaults(): LocalClarifyDefaults {
  const raw = readObject<LocalClarifyDefaults>(CLARIFY_LOCAL_KEY);
  const result: LocalClarifyDefaults = {};
  const squadId = normalizeId(raw.defaultSquadId);
  const agentId = normalizeId(raw.defaultAgentId);
  const autoSend = normalizeFlag(raw.autoSendFirstPrompt);
  if (squadId !== undefined) result.defaultSquadId = squadId;
  if (agentId !== undefined) result.defaultAgentId = agentId;
  if (autoSend !== undefined) result.autoSendFirstPrompt = autoSend;
  return result;
}

export function readLocalDeliveryDefaults(): LocalDeliveryDefaults {
  const raw = readObject<LocalDeliveryDefaults>(DELIVERY_LOCAL_KEY);
  const result: LocalDeliveryDefaults = {};
  const squadId = normalizeId(raw.defaultSquadId);
  const agentId = normalizeId(raw.defaultAgentId);
  if (squadId !== undefined) result.defaultSquadId = squadId;
  if (agentId !== undefined) result.defaultAgentId = agentId;
  return result;
}

function writeObject(key: string, value: LocalClarifyDefaults | LocalDeliveryDefaults): void {
  const store = storage();
  if (!store) return;
  try {
    store.setItem(key, JSON.stringify(value));
  } catch {
    // Storage full or disabled; ignore.
  }
}

export function writeLocalClarifyDefaults(value: LocalClarifyDefaults): void {
  writeObject(CLARIFY_LOCAL_KEY, value);
}

export function writeLocalDeliveryDefaults(value: LocalDeliveryDefaults): void {
  writeObject(DELIVERY_LOCAL_KEY, value);
}

export function clearLocalClarifyDefaults(): void {
  storage()?.removeItem(CLARIFY_LOCAL_KEY);
}

export function clearLocalDeliveryDefaults(): void {
  storage()?.removeItem(DELIVERY_LOCAL_KEY);
}

export function hasClarifyLocalOverride(value: LocalClarifyDefaults): boolean {
  return Object.keys(value).length > 0;
}

export function hasDeliveryLocalOverride(value: LocalDeliveryDefaults): boolean {
  return Object.keys(value).length > 0;
}

export function mergeClarifyDefaults(
  space: ClarifySpaceDefaults | null | undefined,
  local: LocalClarifyDefaults,
): ClarifyDefaults {
  return {
    defaultSquadId: local.defaultSquadId !== undefined
      ? local.defaultSquadId
      : space?.defaultSquadId ?? null,
    defaultAgentId: local.defaultAgentId !== undefined
      ? local.defaultAgentId
      : space?.defaultAgentId ?? null,
    autoSendFirstPrompt: local.autoSendFirstPrompt !== undefined
      ? local.autoSendFirstPrompt
      : space?.autoSendFirstPrompt ?? false,
  };
}

export function mergeDeliveryDefaults(
  space: DeliverySpaceDefaults | null | undefined,
  local: LocalDeliveryDefaults,
): DeliveryDefaults {
  return {
    defaultSquadId: local.defaultSquadId !== undefined
      ? local.defaultSquadId
      : space?.defaultSquadId ?? null,
    defaultAgentId: local.defaultAgentId !== undefined
      ? local.defaultAgentId
      : space?.defaultAgentId ?? null,
  };
}

export function diffClarifyDefaults(
  space: ClarifySpaceDefaults,
  values: ClarifyDefaults,
): LocalClarifyDefaults {
  const local: LocalClarifyDefaults = {};
  if (values.defaultSquadId !== space.defaultSquadId
      || values.defaultAgentId !== space.defaultAgentId) {
    local.defaultSquadId = values.defaultSquadId;
    local.defaultAgentId = values.defaultAgentId;
  }
  if (values.autoSendFirstPrompt !== space.autoSendFirstPrompt) {
    local.autoSendFirstPrompt = values.autoSendFirstPrompt;
  }
  return local;
}

export function diffDeliveryDefaults(
  space: DeliverySpaceDefaults,
  values: DeliveryDefaults,
): LocalDeliveryDefaults {
  if (values.defaultSquadId === space.defaultSquadId
      && values.defaultAgentId === space.defaultAgentId) {
    return {};
  }
  return {
    defaultSquadId: values.defaultSquadId,
    defaultAgentId: values.defaultAgentId,
  };
}

function hasPairOverride(local: {
  defaultSquadId?: number | null;
  defaultAgentId?: number | null;
}): boolean {
  return local.defaultSquadId !== undefined || local.defaultAgentId !== undefined;
}

/**
 * 默认档解析：本地档组合失效时跳过该档，回退到空间档并保留失效提示；
 * 本地档两字段显式为 null 表示主动不要默认，不回退。
 */
function pickDefault(
  effective: { defaultSquadId: number | null; defaultAgentId: number | null },
  localOverridden: boolean,
  localValid: boolean,
  space: { defaultSquadId: number | null; defaultAgentId: number | null; valid: boolean } | null | undefined,
): ResolvedSelection {
  const { defaultSquadId, defaultAgentId } = effective;
  if (defaultSquadId == null && defaultAgentId == null) {
    return { selection: null, defaultSkipped: false };
  }
  const complete = defaultSquadId != null && defaultAgentId != null;
  // 空间档的合法性由服务端读取时判定；本地档由调用方按当前小队成员校验。
  const effectiveValid = localOverridden ? localValid : space?.valid === true;
  if (complete && effectiveValid) {
    return { selection: { squadId: defaultSquadId, agentId: defaultAgentId }, defaultSkipped: false };
  }
  if (localOverridden && space?.valid === true
      && space.defaultSquadId != null && space.defaultAgentId != null) {
    return {
      selection: { squadId: space.defaultSquadId, agentId: space.defaultAgentId },
      defaultSkipped: true,
    };
  }
  return { selection: null, defaultSkipped: true };
}

export function resolveClarificationSelection(input: {
  explicit: TargetSelection | null;
  url: TargetSelection | null;
  memory: TargetSelection | null;
  local: LocalClarifyDefaults;
  space: ClarifySpaceDefaults | null | undefined;
  localValid: boolean;
}): ResolvedSelection {
  if (input.explicit) return { selection: input.explicit, defaultSkipped: false };
  if (input.url) return { selection: input.url, defaultSkipped: false };
  if (input.memory) return { selection: input.memory, defaultSkipped: false };
  return pickDefault(
    mergeClarifyDefaults(input.space, input.local),
    hasPairOverride(input.local),
    input.localValid,
    input.space,
  );
}

export function resolveDeliverySelection(input: {
  memory: TargetSelection | null;
  local: LocalDeliveryDefaults;
  space: DeliverySpaceDefaults | null | undefined;
  localValid: boolean;
}): ResolvedSelection {
  if (input.memory) return { selection: input.memory, defaultSkipped: false };
  return pickDefault(
    mergeDeliveryDefaults(input.space, input.local),
    hasPairOverride(input.local),
    input.localValid,
    input.space,
  );
}
