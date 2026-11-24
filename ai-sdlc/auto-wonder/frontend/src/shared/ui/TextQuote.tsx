import { useCallback, useEffect, useState } from 'react';
import type { RefObject } from 'react';
import { Button } from 'antd';
import { CloseOutlined } from '@ant-design/icons';
import { QUOTE_MAX_LENGTH, QUOTE_MIN_SELECTION_LENGTH } from './quote-block';

export interface TextQuotePillState {
  text: string;
  top: number;
  left: number;
  placement: 'above' | 'below';
}

interface UseTextQuoteOptions {
  containerRef: RefObject<HTMLElement>;
  enabled: boolean;
  resetKey?: string | number | null;
  onAccept?: () => void;
}

// 交互控件、消息头和显式标记的非正文区都不参与引用，避免把按钮文案或角色名带进引用块。
// 正文内联链接属于消息内容，不排除，否则引用文本会丢掉链接文字。
const INTERACTIVE_SELECTOR = 'button, input, textarea, select, [contenteditable="true"]';
const IGNORE_SELECTOR = '[data-quote-ignore]';
const MESSAGE_SELECTOR = '[data-quote-message]';

const PILL_EDGE_MARGIN = 72;
const PILL_VERTICAL_GAP = 8;
const PILL_FLIP_THRESHOLD = 48;
const PILL_Z_INDEX = 1100;
const QUOTE_CARD_LINES = 3;

// 选区跨段落时浏览器不会补换行，按块级元素边界补回，保留用户看到的分段。
const BLOCK_TAGS = new Set([
  'ADDRESS', 'ARTICLE', 'ASIDE', 'BLOCKQUOTE', 'DIV', 'DL', 'DD', 'DT', 'FIGCAPTION', 'FIGURE',
  'FOOTER', 'H1', 'H2', 'H3', 'H4', 'H5', 'H6', 'HEADER', 'LI', 'MAIN', 'OL', 'P', 'PRE',
  'SECTION', 'TABLE', 'TBODY', 'TD', 'TFOOT', 'TH', 'THEAD', 'TR', 'UL',
]);

function rangeText(range: Range): string {
  const fragment = range.cloneContents();
  const parts: string[] = [];
  const walk = (node: Node) => {
    if (node.nodeType === Node.TEXT_NODE) {
      parts.push(node.textContent ?? '');
      return;
    }
    if (node.nodeType !== Node.ELEMENT_NODE) {
      return;
    }
    const element = node as Element;
    // 选区中间跨过的按钮、附件或标记为忽略的区域也整段跳过，不能把非正文文案带进引用。
    if (element.matches(INTERACTIVE_SELECTOR) || element.matches(IGNORE_SELECTOR)) {
      return;
    }
    if (element.tagName === 'BR') {
      parts.push('\n');
      return;
    }
    const block = BLOCK_TAGS.has(element.tagName);
    if (block) {
      parts.push('\n');
    }
    element.childNodes.forEach(walk);
    if (block) {
      parts.push('\n');
    }
  };
  fragment.childNodes.forEach(walk);
  return parts.join('').replace(/\n{3,}/g, '\n\n');
}

function elementOf(node: Node | null): Element | null {
  if (!node) {
    return null;
  }
  return node.nodeType === Node.ELEMENT_NODE ? (node as Element) : node.parentElement;
}

export function useTextQuote({ containerRef, enabled, resetKey, onAccept }: UseTextQuoteOptions) {
  const [quote, setQuote] = useState<string | null>(null);
  const [pill, setPill] = useState<TextQuotePillState | null>(null);

  useEffect(() => {
    setQuote(null);
    setPill(null);
  }, [resetKey]);

  useEffect(() => {
    const container = containerRef.current;
    if (!enabled || !container) {
      setPill(null);
      return;
    }
    const dismiss = () => setPill(null);
    const update = () => {
      const selection = window.getSelection();
      if (!selection || selection.rangeCount === 0 || selection.isCollapsed) {
        setPill(null);
        return;
      }
      const range = selection.getRangeAt(0);
      const anchorNode = selection.anchorNode;
      const focusNode = selection.focusNode;
      if (!anchorNode || !focusNode || !container.contains(anchorNode) || !container.contains(focusNode)) {
        setPill(null);
        return;
      }
      const startElement = elementOf(anchorNode);
      const endElement = elementOf(focusNode);
      if (startElement?.closest(INTERACTIVE_SELECTOR) || endElement?.closest(INTERACTIVE_SELECTOR)) {
        setPill(null);
        return;
      }
      if (startElement?.closest(IGNORE_SELECTOR) || endElement?.closest(IGNORE_SELECTOR)) {
        setPill(null);
        return;
      }
      const startMessage = startElement?.closest(MESSAGE_SELECTOR);
      const endMessage = endElement?.closest(MESSAGE_SELECTOR);
      if (!startMessage || startMessage !== endMessage) {
        setPill(null);
        return;
      }
      // 先判定结构再克隆选中内容：选区常在容器外（如 Ctrl+A）或不属于任何消息，
      // 每次 selectionchange 都克隆整页内容代价高。
      const text = rangeText(range).trim();
      if (text.length < QUOTE_MIN_SELECTION_LENGTH) {
        setPill(null);
        return;
      }
      const rect = range.getBoundingClientRect();
      // 隐藏节点或已离开视口的选区拿不到有效矩形，此时不显示胶囊。
      if (rect.width === 0 && rect.height === 0) {
        setPill(null);
        return;
      }
      // 选区太靠近顶部时翻到下方，避免胶囊被视口裁掉。
      const placement = rect.top >= PILL_FLIP_THRESHOLD ? 'above' : 'below';
      setPill({
        text,
        left: Math.min(
          Math.max(rect.left + rect.width / 2, PILL_EDGE_MARGIN),
          window.innerWidth - PILL_EDGE_MARGIN,
        ),
        top: placement === 'above' ? rect.top - PILL_VERTICAL_GAP : rect.bottom + PILL_VERTICAL_GAP,
        placement,
      });
    };
    document.addEventListener('selectionchange', update);
    document.addEventListener('mousedown', dismiss);
    container.addEventListener('scroll', dismiss, { capture: true, passive: true });
    return () => {
      document.removeEventListener('selectionchange', update);
      document.removeEventListener('mousedown', dismiss);
      container.removeEventListener('scroll', dismiss, { capture: true });
    };
  }, [containerRef, enabled]);

  const acceptPill = useCallback(() => {
    if (!pill) {
      return;
    }
    setQuote(pill.text);
    setPill(null);
    window.getSelection()?.removeAllRanges();
    onAccept?.();
  }, [onAccept, pill]);

  const removeQuote = useCallback(() => setQuote(null), []);

  return {
    quote,
    truncated: quote != null && quote.length > QUOTE_MAX_LENGTH,
    pill,
    acceptPill,
    removeQuote,
    restoreQuote: setQuote,
  };
}

interface QuotePillProps {
  pill: TextQuotePillState | null;
  label: string;
  onAccept: () => void;
}

export function QuotePill({ pill, label, onAccept }: QuotePillProps) {
  if (!pill) {
    return null;
  }
  return (
    <button
      type="button"
      aria-label={label}
      onMouseDown={(event) => {
        // 按下胶囊时不抢焦点、不让事件冒泡到 document，否则选区会先塌陷、胶囊先被隐藏。
        event.preventDefault();
        event.stopPropagation();
      }}
      onClick={onAccept}
      style={{
        position: 'fixed',
        left: pill.left,
        top: pill.top,
        transform: pill.placement === 'above' ? 'translate(-50%, -100%)' : 'translate(-50%, 0)',
        zIndex: PILL_Z_INDEX,
        padding: '5px 12px',
        border: 'none',
        borderRadius: 999,
        background: 'var(--aw-text, #1f1f1f)',
        color: 'var(--aw-bg, #fff)',
        fontSize: 12,
        lineHeight: '18px',
        whiteSpace: 'nowrap',
        cursor: 'pointer',
        boxShadow: '0 4px 12px rgba(0, 0, 0, 0.24)',
      }}
    >
      {label}
    </button>
  );
}

interface QuoteCardProps {
  quote: string | null;
  truncated: boolean;
  label: string;
  removeLabel: string;
  truncatedLabel: string;
  onRemove: () => void;
}

export function QuoteCard({ quote, truncated, label, removeLabel, truncatedLabel, onRemove }: QuoteCardProps) {
  if (!quote) {
    return null;
  }
  return (
    <div style={{
      display: 'flex',
      alignItems: 'flex-start',
      gap: 8,
      padding: '8px 10px',
      border: '1px solid var(--aw-border, #e5e5e5)',
      borderRadius: 8,
      background: 'var(--aw-raised, #fafafa)',
    }}>
      <div style={{ minWidth: 0, flex: 1 }}>
        <div style={{ fontSize: 12, color: 'var(--aw-muted, #8c8c8c)', marginBottom: 4 }}>{label}</div>
        <div style={{
          display: '-webkit-box',
          WebkitLineClamp: QUOTE_CARD_LINES,
          WebkitBoxOrient: 'vertical',
          overflow: 'hidden',
          whiteSpace: 'pre-wrap',
          wordBreak: 'break-word',
          fontSize: 13,
        }}>{quote}</div>
        {truncated && <div style={{ marginTop: 4, fontSize: 12, color: 'var(--aw-warning, #d46b08)' }}>{truncatedLabel}</div>}
      </div>
      <Button type="text" size="small" aria-label={removeLabel} icon={<CloseOutlined />} onClick={onRemove} />
    </div>
  );
}
