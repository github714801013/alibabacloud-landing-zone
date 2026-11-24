export const QUOTE_MIN_SELECTION_LENGTH = 2;

export const QUOTE_MAX_LENGTH = 4000;

export function buildQuoteBlock(text: string): string | null {
  const trimmed = text.trim();
  if (!trimmed) {
    return null;
  }
  // 超长选区只保留前 4000 字符并显式加省略号，避免把整段长文塞进输入框。
  const limited = trimmed.length > QUOTE_MAX_LENGTH
    ? `${trimmed.slice(0, QUOTE_MAX_LENGTH)}…`
    : trimmed;
  // 空行也写成单独的 `>`，否则 Markdown 会把引用块在空行处截断成两段。
  return limited
    .split(/\r?\n/)
    .map((line) => (line ? `> ${line}` : '>'))
    .join('\n');
}

/** 引用块与正文之间空一行；没有正文时只发送引用块本身。 */
export function composeQuotedContent(quote: string | null | undefined, body: string): string {
  const quoteBlock = buildQuoteBlock(quote ?? '');
  return quoteBlock ? (body ? `${quoteBlock}\n\n${body}` : quoteBlock) : body;
}
