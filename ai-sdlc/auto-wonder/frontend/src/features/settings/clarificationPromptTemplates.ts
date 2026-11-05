/**
 * 需求澄清提示词模板：生效模板解析、默认选择回退与工单号替换。
 *
 * 纯函数，不读写存储、不发请求；空间目录来自 /api/workspaces/current/ai-defaults，
 * 本地覆盖来自 aw:clarify-defaults:local。
 */

export const BUILTIN_FIRST_TEMPLATE_ID = 'bootstrap';
export const BUILTIN_RECLARIFY_TEMPLATE_ID = 'reclarify';
export const MAX_TEMPLATE_NAME_LENGTH = 50;
export const MAX_TEMPLATE_CONTENT_LENGTH = 10000;
export const MAX_CUSTOM_TEMPLATES = 100;

export type PromptTemplateKind = 'builtin' | 'custom';

export interface PromptTemplate {
  id: string;
  name: string;
  content: string;
  kind: PromptTemplateKind;
}

export interface SpaceBuiltinTemplate {
  content?: string | null;
}

export interface SpaceCustomTemplate {
  id: string;
  name: string;
  content?: string | null;
}

export interface SpacePromptTemplateDefaults {
  first?: string | null;
  reclarify?: string | null;
}

/** 与后端 clarify.promptTemplates 的 JSON 结构一致。 */
export interface PromptTemplateCatalog {
  version?: number;
  builtin?: Record<string, SpaceBuiltinTemplate> | null;
  custom?: SpaceCustomTemplate[] | null;
  defaults?: SpacePromptTemplateDefaults | null;
}

export interface BuiltinTemplateTexts {
  firstName: string;
  firstContent: string;
  reclarifyName: string;
  reclarifyContent: string;
}

export interface LocalPromptTemplateOverrides {
  firstTemplateId?: string;
  reclarifyTemplateId?: string;
  templateContents?: Record<string, string>;
}

export interface EffectivePromptTemplates {
  /** 两个内置模板始终在前，其后是空间自定义模板。 */
  templates: PromptTemplate[];
  firstDefault: PromptTemplate;
  reclarifyDefault: PromptTemplate;
}

function nonBlank(value: string | null | undefined): value is string {
  return typeof value === 'string' && value.trim() !== '';
}

/** 内置正文空值视为未覆盖：内置模板不能被保存为空。 */
function resolveBuiltinContent(
  factoryContent: string,
  spaceContent: string | null | undefined,
  localContent: string | undefined,
): string {
  if (nonBlank(localContent)) return localContent;
  if (nonBlank(spaceContent)) return spaceContent;
  return factoryContent;
}

/** 自定义正文允许为空；本地出现该键即覆盖，空串也是有效覆盖。 */
function resolveCustomContent(
  spaceContent: string | null | undefined,
  localContent: string | undefined,
): string {
  if (localContent !== undefined) return localContent;
  return spaceContent ?? '';
}

function pickDefault(
  localId: string | undefined,
  spaceId: string | null | undefined,
  builtinId: string,
  templateIds: Set<string>,
): string {
  if (localId && templateIds.has(localId)) return localId;
  if (spaceId && templateIds.has(spaceId)) return spaceId;
  return builtinId;
}

export function resolvePromptTemplates(
  builtin: BuiltinTemplateTexts,
  space: PromptTemplateCatalog | null | undefined,
  local: LocalPromptTemplateOverrides,
): EffectivePromptTemplates {
  const localContents = local.templateContents ?? {};
  const spaceBuiltin = space?.builtin ?? {};

  const templates: PromptTemplate[] = [
    {
      id: BUILTIN_FIRST_TEMPLATE_ID,
      name: builtin.firstName,
      content: resolveBuiltinContent(
        builtin.firstContent,
        spaceBuiltin[BUILTIN_FIRST_TEMPLATE_ID]?.content,
        localContents[BUILTIN_FIRST_TEMPLATE_ID],
      ),
      kind: 'builtin',
    },
    {
      id: BUILTIN_RECLARIFY_TEMPLATE_ID,
      name: builtin.reclarifyName,
      content: resolveBuiltinContent(
        builtin.reclarifyContent,
        spaceBuiltin[BUILTIN_RECLARIFY_TEMPLATE_ID]?.content,
        localContents[BUILTIN_RECLARIFY_TEMPLATE_ID],
      ),
      kind: 'builtin',
    },
  ];

  for (const item of space?.custom ?? []) {
    if (!item || !nonBlank(item.id) || !nonBlank(item.name)) continue;
    templates.push({
      id: item.id,
      name: item.name,
      content: resolveCustomContent(item.content, localContents[item.id]),
      kind: 'custom',
    });
  }

  const templateIds = new Set(templates.map((template) => template.id));
  const firstDefaultId = pickDefault(
    local.firstTemplateId,
    space?.defaults?.first,
    BUILTIN_FIRST_TEMPLATE_ID,
    templateIds,
  );
  const reclarifyDefaultId = pickDefault(
    local.reclarifyTemplateId,
    space?.defaults?.reclarify,
    BUILTIN_RECLARIFY_TEMPLATE_ID,
    templateIds,
  );

  return {
    templates,
    firstDefault: templates.find((template) => template.id === firstDefaultId)!,
    reclarifyDefault: templates.find((template) => template.id === reclarifyDefaultId)!,
  };
}

export interface CatalogDraft {
  version: 1;
  builtin: Record<string, { content: string }>;
  custom: Array<{ id: string; name: string; content: string }>;
  defaults: { first: string; reclarify: string };
}

export function emptyCatalogDraft(): CatalogDraft {
  return {
    version: 1,
    builtin: {},
    custom: [],
    defaults: { first: BUILTIN_FIRST_TEMPLATE_ID, reclarify: BUILTIN_RECLARIFY_TEMPLATE_ID },
  };
}

/** 把空间目录转成可编辑草稿；失效默认引用回退到对应内置模板。 */
export function buildCatalogDraft(catalog: PromptTemplateCatalog | null | undefined): CatalogDraft {
  const draft = emptyCatalogDraft();
  for (const [id, item] of Object.entries(catalog?.builtin ?? {})) {
    if (nonBlank(id) && nonBlank(item?.content)) {
      draft.builtin[id] = { content: item.content };
    }
  }
  draft.custom = (catalog?.custom ?? [])
    .filter((item) => item && nonBlank(item.id) && nonBlank(item.name))
    .map((item) => ({ id: item.id, name: item.name, content: item.content ?? '' }));

  const templateIds = new Set([
    BUILTIN_FIRST_TEMPLATE_ID,
    BUILTIN_RECLARIFY_TEMPLATE_ID,
    ...draft.custom.map((item) => item.id),
  ]);
  const spaceFirst = catalog?.defaults?.first;
  const spaceReclarify = catalog?.defaults?.reclarify;
  draft.defaults = {
    first: spaceFirst && templateIds.has(spaceFirst) ? spaceFirst : BUILTIN_FIRST_TEMPLATE_ID,
    reclarify: spaceReclarify && templateIds.has(spaceReclarify)
      ? spaceReclarify
      : BUILTIN_RECLARIFY_TEMPLATE_ID,
  };
  return draft;
}

export function withBuiltinContent(draft: CatalogDraft, id: string, content: string): CatalogDraft {
  return { ...draft, builtin: { ...draft.builtin, [id]: { content } } };
}

export function withoutBuiltinContent(draft: CatalogDraft, id: string): CatalogDraft {
  const builtin = { ...draft.builtin };
  delete builtin[id];
  return { ...draft, builtin };
}

/** 按 ID 新增或更新自定义模板。 */
export function withCustomTemplate(
  draft: CatalogDraft,
  item: { id: string; name: string; content: string },
): CatalogDraft {
  const exists = draft.custom.some((entry) => entry.id === item.id);
  return {
    ...draft,
    custom: exists
      ? draft.custom.map((entry) => (entry.id === item.id ? { ...item } : entry))
      : [...draft.custom, { ...item }],
  };
}

/** 删除自定义模板，并让引用它的默认选择回退到对应内置模板。 */
export function withoutCustomTemplate(draft: CatalogDraft, id: string): CatalogDraft {
  return {
    ...draft,
    custom: draft.custom.filter((entry) => entry.id !== id),
    defaults: {
      first: draft.defaults.first === id ? BUILTIN_FIRST_TEMPLATE_ID : draft.defaults.first,
      reclarify: draft.defaults.reclarify === id ? BUILTIN_RECLARIFY_TEMPLATE_ID : draft.defaults.reclarify,
    },
  };
}

export function serializeCatalogDraft(draft: CatalogDraft): string {
  const builtin: Record<string, { content: string }> = {};
  for (const id of [BUILTIN_FIRST_TEMPLATE_ID, BUILTIN_RECLARIFY_TEMPLATE_ID]) {
    const item = draft.builtin[id];
    if (item) builtin[id] = { content: item.content };
  }
  return JSON.stringify({
    version: draft.version,
    builtin,
    custom: draft.custom.map(({ id, name, content }) => ({ id, name, content })),
    defaults: { first: draft.defaults.first, reclarify: draft.defaults.reclarify },
  });
}

export function newCustomTemplateId(): string {
  const uuid = typeof globalThis.crypto?.randomUUID === 'function'
    ? globalThis.crypto.randomUUID()
    : `${Date.now().toString(16)}-${Math.random().toString(16).slice(2, 10)}`;
  return `custom-${uuid}`;
}

export function isBuiltinTemplate(template: PromptTemplate): boolean {
  return template.kind === 'builtin';
}

/** 只替换当前工单号占位符，其他占位符与文本原样保留。 */
export function renderPromptTemplate(content: string, workitemId: string | number): string {
  return content.replace(/\{\{workitemId\}\}/g, String(workitemId));
}
