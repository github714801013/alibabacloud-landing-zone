import { describe, it, expect } from 'vitest';
import { act, render } from '@testing-library/react';
import { MarkdownComposer } from './MarkdownComposer';
import { getComposerEditor } from './composerTestUtils';

// jsdom 未实现 ClipboardEvent，prosemirror-view 的 pasteHTML 会直接 new 它。
if (typeof (globalThis as { ClipboardEvent?: unknown }).ClipboardEvent === 'undefined') {
  class ClipboardEventPolyfill extends Event {
    clipboardData: DataTransfer | null = null;
  }
  (globalThis as unknown as { ClipboardEvent: typeof ClipboardEvent }).ClipboardEvent =
    ClipboardEventPolyfill as unknown as typeof ClipboardEvent;
}

async function pasteTable(html: string): Promise<string> {
  render(<MarkdownComposer value="" onChange={() => {}} onSendKey={() => {}} sendMode="shift-enter" height={null} />);
  const editor = getComposerEditor();
  await act(async () => {
    editor.view.pasteHTML(html);
  });
  return editor.storage.markdown.getMarkdown();
}

describe('MarkdownComposer 表格序列化', () => {
  it('无表头 HTML 表格保留单元格文本', async () => {
    const md = await pasteTable(
      '<table><tbody><tr><td>托管名称</td><td>状态</td></tr><tr><td>prod</td><td>失败</td></tr></tbody></table>',
    );
    expect(md).not.toContain('[table]');
    expect(md).toContain('托管名称');
    expect(md).toContain('prod');
    expect(md).toContain('| --- | --- |');
  });

  it('有表头表格继续输出 GFM 表格', async () => {
    const md = await pasteTable(
      '<table><tbody><tr><th>托管名称</th><th>状态</th></tr><tr><td>prod</td><td>失败</td></tr></tbody></table>',
    );
    expect(md).toContain('| 托管名称 | 状态 |');
    expect(md).toContain('| prod | 失败 |');
  });

  it('合并单元格保留文本', async () => {
    const md = await pasteTable(
      '<table><tbody><tr><th>分类</th><th>名称</th></tr><tr><td colspan="2">合并行</td></tr></tbody></table>',
    );
    expect(md).not.toContain('[table]');
    expect(md).toContain('合并行');
  });

  it('单元格内多个段落用空格连接，不出现占位', async () => {
    const md = await pasteTable(
      '<table><tbody><tr><th>名称</th><th>说明</th></tr>'
      + '<tr><td>prod</td><td><p>第一段</p><p>第二段</p></td></tr></tbody></table>',
    );
    expect(md).not.toContain('[table]');
    expect(md).toContain('第一段 第二段');
  });

  it('单元格文本中的竖线被转义', async () => {
    const md = await pasteTable(
      '<table><tbody><tr><th>表达式</th><th>结果</th></tr>'
      + '<tr><td>a|b</td><td>真</td></tr></tbody></table>',
    );
    expect(md).toContain('a\\|b');
  });
});
