import { describe, expect, it } from 'vitest';
import {
  BUILTIN_FIRST_TEMPLATE_ID,
  BUILTIN_RECLARIFY_TEMPLATE_ID,
  buildCatalogDraft,
  emptyCatalogDraft,
  newCustomTemplateId,
  renderPromptTemplate,
  resolvePromptTemplates,
  serializeCatalogDraft,
  withBuiltinContent,
  withCustomTemplate,
  withoutCustomTemplate,
  type BuiltinTemplateTexts,
  type PromptTemplateCatalog,
} from './clarificationPromptTemplates';

const builtin: BuiltinTemplateTexts = {
  firstName: '首次澄清',
  firstContent: '请读取工单 #{{workitemId}} 做首次澄清',
  reclarifyName: '再次澄清',
  reclarifyContent: '请基于已有结果对工单 #{{workitemId}} 再次澄清',
};

const catalog = (overrides: Partial<PromptTemplateCatalog> = {}): PromptTemplateCatalog => ({
  version: 1,
  builtin: {},
  custom: [],
  defaults: {},
  ...overrides,
});

describe('resolvePromptTemplates', () => {
  it('falls back to factory built-ins when no space catalog exists', () => {
    const effective = resolvePromptTemplates(builtin, null, {});

    expect(effective.templates.map((template) => template.id)).toEqual([
      BUILTIN_FIRST_TEMPLATE_ID,
      BUILTIN_RECLARIFY_TEMPLATE_ID,
    ]);
    expect(effective.firstDefault.id).toBe(BUILTIN_FIRST_TEMPLATE_ID);
    expect(effective.reclarifyDefault.id).toBe(BUILTIN_RECLARIFY_TEMPLATE_ID);
    expect(effective.firstDefault.content).toBe(builtin.firstContent);
  });

  it('resolves built-in content as local > space > factory', () => {
    const space = catalog({ builtin: { [BUILTIN_FIRST_TEMPLATE_ID]: { content: '空间首次' } } });

    expect(resolvePromptTemplates(builtin, space, {}).firstDefault.content).toBe('空间首次');
    expect(resolvePromptTemplates(builtin, space, {
      templateContents: { [BUILTIN_FIRST_TEMPLATE_ID]: '本地首次' },
    }).firstDefault.content).toBe('本地首次');
  });

  it('ignores blank local built-in content because built-ins cannot be empty', () => {
    const space = catalog({ builtin: { [BUILTIN_RECLARIFY_TEMPLATE_ID]: { content: '空间再次' } } });

    const effective = resolvePromptTemplates(builtin, space, {
      templateContents: { [BUILTIN_RECLARIFY_TEMPLATE_ID]: '   ' },
    });

    expect(effective.reclarifyDefault.content).toBe('空间再次');
  });

  it('appends custom templates and lets local content override including empty', () => {
    const space = catalog({
      custom: [
        { id: 'custom-1', name: '盘问清单', content: '空间自定义' },
        { id: 'custom-2', name: '空白模板', content: '' },
      ],
    });

    const effective = resolvePromptTemplates(builtin, space, {
      templateContents: { 'custom-1': '本地自定义' },
    });

    expect(effective.templates.map((template) => template.name)).toEqual([
      '首次澄清', '再次澄清', '盘问清单', '空白模板',
    ]);
    expect(effective.templates[2].content).toBe('本地自定义');
    expect(effective.templates[3].content).toBe('');
    expect(effective.templates[3].kind).toBe('custom');

    const emptied = resolvePromptTemplates(builtin, space, { templateContents: { 'custom-1': '' } });
    expect(emptied.templates[2].content).toBe('');
  });

  it('resolves defaults as local > space > built-in and falls back layer by layer', () => {
    const space = catalog({
      custom: [{ id: 'custom-1', name: '盘问清单', content: '' }],
      defaults: { first: 'custom-1', reclarify: 'custom-1' },
    });

    expect(resolvePromptTemplates(builtin, space, {}).firstDefault.id).toBe('custom-1');

    const localWins = resolvePromptTemplates(builtin, space, {
      firstTemplateId: BUILTIN_RECLARIFY_TEMPLATE_ID,
    });
    expect(localWins.firstDefault.id).toBe(BUILTIN_RECLARIFY_TEMPLATE_ID);

    const localMissing = resolvePromptTemplates(builtin, space, {
      firstTemplateId: 'custom-deleted',
    });
    expect(localMissing.firstDefault.id).toBe('custom-1');

    const bothMissing = resolvePromptTemplates(builtin, catalog({
      defaults: { first: 'custom-deleted' },
    }), { firstTemplateId: 'custom-also-deleted' });
    expect(bothMissing.firstDefault.id).toBe(BUILTIN_FIRST_TEMPLATE_ID);
  });
});

describe('catalog draft', () => {
  it('builds an empty catalog with built-in defaults and drops invalid references', () => {
    expect(buildCatalogDraft(null)).toEqual({
      version: 1,
      builtin: {},
      custom: [],
      defaults: { first: BUILTIN_FIRST_TEMPLATE_ID, reclarify: BUILTIN_RECLARIFY_TEMPLATE_ID },
    });

    const draft = buildCatalogDraft({
      version: 1,
      custom: [{ id: 'custom-1', name: '清单', content: '' }],
      defaults: { first: 'missing', reclarify: 'custom-1' },
    });
    expect(draft.defaults).toEqual({ first: BUILTIN_FIRST_TEMPLATE_ID, reclarify: 'custom-1' });
  });

  it('updates and adds custom templates', () => {
    const added = withCustomTemplate(emptyCatalogDraft(), { id: 'custom-1', name: '清单', content: '' });
    const updated = withCustomTemplate(added, { id: 'custom-1', name: '新清单', content: '正文' });
    const appended = withCustomTemplate(updated, { id: 'custom-2', name: '另一个', content: '' });

    expect(updated.custom).toEqual([{ id: 'custom-1', name: '新清单', content: '正文' }]);
    expect(appended.custom.map((item) => item.id)).toEqual(['custom-1', 'custom-2']);
  });

  it('deleting a referenced custom template falls back to the matching built-in', () => {
    const draft = buildCatalogDraft({
      version: 1,
      custom: [{ id: 'custom-1', name: '清单', content: '' }],
      defaults: { first: 'custom-1', reclarify: 'custom-1' },
    });

    const next = withoutCustomTemplate(draft, 'custom-1');

    expect(next.custom).toEqual([]);
    expect(next.defaults).toEqual({
      first: BUILTIN_FIRST_TEMPLATE_ID,
      reclarify: BUILTIN_RECLARIFY_TEMPLATE_ID,
    });
  });

  it('serializes the canonical catalog shape', () => {
    const draft = withBuiltinContent(
      withCustomTemplate(emptyCatalogDraft(), { id: 'custom-1', name: '清单', content: '' }),
      BUILTIN_FIRST_TEMPLATE_ID,
      '空间首次',
    );

    expect(serializeCatalogDraft(draft)).toBe(
      '{"version":1,"builtin":{"bootstrap":{"content":"空间首次"}},'
      + '"custom":[{"id":"custom-1","name":"清单","content":""}],'
      + '"defaults":{"first":"bootstrap","reclarify":"reclarify"}}',
    );
  });

  it('generates custom template ids', () => {
    expect(newCustomTemplateId()).toMatch(/^custom-/);
  });
});

describe('renderPromptTemplate', () => {
  it('replaces only the work item placeholder', () => {
    expect(renderPromptTemplate('工单 {{workitemId}} / {{other}} / {{workitemId}}', 'REQ-9'))
      .toBe('工单 REQ-9 / {{other}} / REQ-9');
  });

  it('keeps markup as plain text', () => {
    expect(renderPromptTemplate('<b>{{workitemId}}</b>', 7)).toBe('<b>7</b>');
  });
});
