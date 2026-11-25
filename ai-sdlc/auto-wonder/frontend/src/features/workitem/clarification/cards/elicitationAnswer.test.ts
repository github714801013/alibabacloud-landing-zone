import { describe, it, expect } from 'vitest';
import { OTHER_SENTINEL, computeElicitationContent, computeElicitationText, unansweredFields, validateElicitation } from './elicitationAnswer';
import type { ElicitationField } from './schemaForm';

const f = (over: Partial<ElicitationField>): ElicitationField =>
  ({ name: 'q0', label: '题', control: 'text', required: false, ...over });

describe('computeElicitationContent', () => {
  it('single-select: 其他想法文本替换原值', () => {
    const fields = [f({ control: 'select', required: true, options: [{ value: 'A', label: 'A' }] })];
    expect(computeElicitationContent(fields, { q0: OTHER_SENTINEL }, { q0: '自定义方案' }))
      .toEqual({ q0: '自定义方案' });
  });

  it('single-select: 选真实项回传该项', () => {
    const fields = [f({ control: 'select', options: [{ value: 'A', label: 'A' }] })];
    expect(computeElicitationContent(fields, { q0: 'A' }, {})).toEqual({ q0: 'A' });
  });

  it('multiselect: 自定义值原样追加到数组末尾，不添加会改变含义的前缀', () => {
    const fields = [f({ control: 'multiselect', options: [{ value: 'A', label: 'A' }] })];
    expect(computeElicitationContent(fields, { q0: ['A', OTHER_SENTINEL] }, { q0: 'cn-beijing' }))
      .toEqual({ q0: ['A', 'cn-beijing'] });
  });

  it('multiselect: 未勾其他想法则只回传已选', () => {
    const fields = [f({ control: 'multiselect', options: [{ value: 'A', label: 'A' }] })];
    expect(computeElicitationContent(fields, { q0: ['A'] }, {})).toEqual({ q0: ['A'] });
  });

  it('text: 原值逐字回传', () => {
    const fields = [f({ control: 'text' })];
    expect(computeElicitationContent(fields, { q0: '  原样  ' }, {})).toEqual({ q0: '  原样  ' });
  });

  it('空值/未答的可选字段不回传，绝不产生未声明字段', () => {
    const fields = [f({ name: 'a', control: 'text' }), f({ name: 'b', control: 'boolean' })];
    expect(computeElicitationContent(fields, { b: false }, {})).toEqual({ b: false });
  });

  it('json 控件解析对象后平铺，非法则跳过', () => {
    const fields = [f({ name: 'j', control: 'json' })];
    expect(computeElicitationContent(fields, { j: '{"x":1}' }, {})).toEqual({ x: 1 });
    expect(computeElicitationContent(fields, { j: 'not-json' }, {})).toEqual({});
  });
});

describe('computeElicitationText', () => {
  it('只拼已答字段：多选用顿号、其他用自填文本，0/false 也算已答', () => {
    const fields = [
      f({ name: 'a', label: '技术栈', control: 'select', options: [{ value: 'java', label: 'Java' }] }),
      f({ name: 'b', label: '范围', control: 'multiselect', options: [
        { value: 'web', label: '前端' }, { value: 'api', label: '后端' },
      ] }),
      f({ name: 'c', label: '数量', control: 'number' }),
      f({ name: 'd', label: '启用', control: 'boolean' }),
      f({ name: 'e', label: '备注', control: 'text' }),
    ];
    expect(computeElicitationText(fields, {
      a: 'java', b: ['web', OTHER_SENTINEL], c: 0, d: false,
    }, { b: '移动端' })).toBe('技术栈：Java\n范围：前端、移动端\n数量：0\n启用：false');
  });

  it('无标题回退「问题N」，属性名兜底不算标题，单选用选项文案而非原始值', () => {
    const fields = [
      f({ name: 'a', label: '', control: 'select', options: [{ value: 'java', label: 'Java' }] }),
      f({ name: 'q2', label: 'q2', control: 'text' }),
    ];
    expect(computeElicitationText(fields, { a: 'java', q2: '带 空格' }, {}))
      .toBe('问题1：Java\n问题2：带 空格');
  });

  it('单选「其他想法」用自填文本；多选勾其他但填字为空时只保留真实项', () => {
    const fields = [
      f({ name: 'a', label: '方案', control: 'select', options: [{ value: 'x', label: 'X' }] }),
      f({ name: 'b', label: '范围', control: 'multiselect', options: [{ value: 'web', label: '前端' }] }),
    ];
    expect(computeElicitationText(fields, { a: OTHER_SENTINEL, b: ['web', OTHER_SENTINEL] }, { a: ' 自研方案 ', b: '  ' }))
      .toBe('方案：自研方案\n范围：前端');
  });

  it('一项未答返回空串；长文本原样保留', () => {
    const long = 'A'.repeat(500);
    const fields = [f({ name: 'a', label: '备注', control: 'text' })];
    expect(computeElicitationText(fields, {}, {})).toBe('');
    expect(computeElicitationText(fields, { a: long }, {})).toBe(`备注：${long}`);
  });
});

describe('unansweredFields', () => {
  it('列出未作答题目，答过的（含「其他想法」填字）不再计入', () => {
    const fields = [
      f({ name: 'a', control: 'select' }),
      f({ name: 'b', control: 'text' }),
      f({ name: 'c', control: 'multiselect' }),
    ];
    expect(unansweredFields(fields, {}, {}).map((x) => x.name)).toEqual(['a', 'b', 'c']);
    expect(unansweredFields(fields, { a: 'A', b: 'x', c: ['C'] }, {}).map((x) => x.name)).toEqual([]);
    expect(unansweredFields([fields[0]], { a: OTHER_SENTINEL }, { a: '自定义' })).toEqual([]);
  });

  it('json 控件把内容展开成任意键，不能算作未作答', () => {
    expect(unansweredFields([f({ name: 'j', control: 'json' })], {}, {})).toEqual([]);
  });
});

describe('validateElicitation', () => {
  it('必填空值给出「请填写「字段名」」并标记 firstInvalidIndex', () => {
    const fields = [f({ name: 'a', control: 'select', required: true }), f({ name: 'b', control: 'text', required: true })];
    const r = validateElicitation(fields, { a: 'A' }, {});
    expect(r.errors.b).toBe('请填写「题」');
    expect(r.firstInvalidIndex).toBe(1);
  });

  it('选了其他想法但输入为空：必填报「请填写其他想法」', () => {
    const fields = [f({ control: 'select', required: true })];
    const r = validateElicitation(fields, { q0: OTHER_SENTINEL }, { q0: '   ' });
    expect(r.errors.q0).toBe('请填写其他想法');
    expect(r.firstInvalidIndex).toBe(0);
  });

  it('多选勾其他想法但输入为空：必填报「请填写其他想法」', () => {
    const fields = [f({ control: 'multiselect', required: true })];
    const r = validateElicitation(fields, { q0: [OTHER_SENTINEL] }, { q0: '  ' });
    expect(r.errors.q0).toBe('请填写其他想法');
    expect(r.firstInvalidIndex).toBe(0);
  });

  it('全答完无错误，firstInvalidIndex 为 -1', () => {
    const fields = [f({ control: 'select', required: true })];
    const r = validateElicitation(fields, { q0: 'A' }, {});
    expect(r.errors).toEqual({});
    expect(r.firstInvalidIndex).toBe(-1);
  });
});
