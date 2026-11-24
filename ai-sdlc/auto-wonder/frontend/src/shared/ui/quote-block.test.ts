import { describe, expect, it } from 'vitest';
import { buildQuoteBlock, composeQuotedContent, QUOTE_MAX_LENGTH } from './quote-block';

describe('buildQuoteBlock', () => {
  it('trims the text and prefixes every line', () => {
    expect(buildQuoteBlock('  第一行\n第二行  ')).toBe('> 第一行\n> 第二行');
  });

  it('keeps empty lines as quote markers', () => {
    expect(buildQuoteBlock('第一行\n\n第三行')).toBe('> 第一行\n>\n> 第三行');
  });

  it('returns null for blank text', () => {
    expect(buildQuoteBlock(' \n\t ')).toBeNull();
  });

  it('keeps text at the limit untouched', () => {
    const block = buildQuoteBlock('a'.repeat(QUOTE_MAX_LENGTH));
    expect(block).toBe(`> ${'a'.repeat(QUOTE_MAX_LENGTH)}`);
  });

  it('truncates text above the limit and appends an ellipsis', () => {
    const block = buildQuoteBlock('a'.repeat(QUOTE_MAX_LENGTH + 1));
    expect(block).toBe(`> ${'a'.repeat(QUOTE_MAX_LENGTH)}…`);
  });

  it('keeps markdown markers untouched', () => {
    expect(buildQuoteBlock('**粗体**\n# 标题')).toBe('> **粗体**\n> # 标题');
  });
});

describe('composeQuotedContent', () => {
  it('returns the body unchanged without a quote', () => {
    expect(composeQuotedContent(null, '正文')).toBe('正文');
    expect(composeQuotedContent('', '正文')).toBe('正文');
  });

  it('separates the quote and body with a blank line', () => {
    expect(composeQuotedContent('原文', '正文')).toBe('> 原文\n\n正文');
  });

  it('sends only the quote when the body is empty', () => {
    expect(composeQuotedContent('原文', '')).toBe('> 原文');
  });
});
