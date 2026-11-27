import Table from '@tiptap/extension-table';

/** tiptap-markdown 的表格只接受「首行全为表头、无合并单元格、每格单子节点」，
 *  其余表格（Excel/网页/聊天记录粘贴常见）会因 html:false 输出 `[table]` 占位。
 *  这里覆盖其序列化：始终输出 GFM 表格，优先保证文本内容不丢失。
 *  代价：单元格内行内格式（粗体/链接/代码）与合并单元格的视觉语义降级为纯文本。 */
interface MarkdownTableNode {
  childCount: number;
  textContent: string;
  forEach: (fn: (child: MarkdownTableNode) => void) => void;
}

interface MarkdownTableState {
  inTable: boolean;
  write: (text: string) => void;
  ensureNewLine: () => void;
  closeBlock: (node: unknown) => void;
}

function cellText(cell: MarkdownTableNode): string {
  const parts: string[] = [];
  cell.forEach((child) => parts.push(child.textContent));
  return parts
    .map((part) => part.replace(/\s+/g, ' ').trim())
    .filter(Boolean)
    .join(' ')
    .replace(/\|/g, '\\|');
}

export const MarkdownTable = Table.extend({
  addStorage() {
    return {
      markdown: {
        serialize(state: MarkdownTableState, node: MarkdownTableNode) {
          const rows: MarkdownTableNode[] = [];
          node.forEach((row) => rows.push(row));
          const columns = Math.max(1, ...rows.map((row) => row.childCount));
          state.inTable = true;
          rows.forEach((row, rowIndex) => {
            const cells: string[] = [];
            row.forEach((cell) => cells.push(cellText(cell)));
            while (cells.length < columns) cells.push('');
            state.write(`| ${cells.join(' | ')} |`);
            state.ensureNewLine();
            if (rowIndex === 0) {
              const delimiter = Array.from({ length: columns }, () => '---').join(' | ');
              state.write(`| ${delimiter} |`);
              state.ensureNewLine();
            }
          });
          state.closeBlock(node);
          state.inTable = false;
        },
      },
    };
  },
});
