import { useRef } from 'react';
import { describe, expect, it } from 'vitest';
import { fireEvent, render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QuoteCard, QuotePill, useTextQuote } from './TextQuote';
import { QUOTE_MAX_LENGTH } from './quote-block';
import { selectAcross, selectText, stubRangeRect } from '@/test/selection';

const LONG_TEXT = '长'.repeat(QUOTE_MAX_LENGTH + 1);

function Harness({ enabled = true }: { enabled?: boolean }) {
  const containerRef = useRef<HTMLDivElement>(null);
  const inputRef = useRef<HTMLTextAreaElement>(null);
  const { quote, truncated, pill, acceptPill, removeQuote } = useTextQuote({
    containerRef,
    enabled,
    onAccept: () => inputRef.current?.focus(),
  });
  return <>
    <div ref={containerRef} data-testid="feed">
      <div data-quote-message><div data-quote-ignore>数字人</div><p>这是一条可以引用的消息</p></div>
      <div data-quote-message><p>另一条消息</p></div>
      <div data-quote-message><p>单</p></div>
      <div data-quote-message><p>两个</p></div>
      <div data-quote-message><p>第一段</p><p>第二段</p></div>
      <div data-quote-message><p>前段文字</p><span data-quote-ignore>附件名称</span><button type="button">操作按钮</button><p>后段文字</p></div>
      <div data-quote-message><p><span>含链接前文</span><a href="#doc">链接文字</a><span>含链接后文</span></p></div>
      <div data-quote-message><p data-testid="long">{LONG_TEXT}</p></div>
    </div>
    <textarea ref={inputRef} aria-label="输入" />
    <QuotePill pill={pill} label="引用此内容" onAccept={acceptPill} />
    <QuoteCard quote={quote} truncated={truncated} label="引用内容" removeLabel="移除引用"
      truncatedLabel="引用内容已截断" onRemove={removeQuote} />
  </>;
}

describe('useTextQuote', () => {
  it('shows the pill for a message selection and inserts the quote on click', async () => {
    stubRangeRect();
    render(<Harness />);
    selectText(screen.getByText('这是一条可以引用的消息'));

    await userEvent.click(screen.getByRole('button', { name: '引用此内容' }));

    expect(screen.getByText('引用内容')).toBeInTheDocument();
    expect(screen.getAllByText('这是一条可以引用的消息')).toHaveLength(2);
    expect(screen.getByRole('textbox', { name: '输入' })).toHaveFocus();
  });

  it('ignores selections shorter than two characters', () => {
    stubRangeRect();
    render(<Harness />);
    selectText(screen.getByText('单'));
    expect(screen.queryByRole('button', { name: '引用此内容' })).not.toBeInTheDocument();
  });

  it('accepts a selection of exactly two characters', () => {
    stubRangeRect();
    render(<Harness />);
    selectText(screen.getByText('两个'));
    expect(screen.getByRole('button', { name: '引用此内容' })).toBeInTheDocument();
  });

  it('ignores selections that span two messages', () => {
    stubRangeRect();
    render(<Harness />);
    selectAcross(screen.getByText('这是一条可以引用的消息'), screen.getByText('另一条消息'));
    expect(screen.queryByRole('button', { name: '引用此内容' })).not.toBeInTheDocument();
  });

  it('ignores selections inside an ignored header', () => {
    stubRangeRect();
    render(<Harness />);
    selectText(screen.getByText('数字人'));
    expect(screen.queryByRole('button', { name: '引用此内容' })).not.toBeInTheDocument();
  });

  it('shows no pill while the composer is unavailable', () => {
    stubRangeRect();
    render(<Harness enabled={false} />);
    selectText(screen.getByText('这是一条可以引用的消息'));
    expect(screen.queryByRole('button', { name: '引用此内容' })).not.toBeInTheDocument();
  });

  it('dismisses the pill on an outside click or a feed scroll', () => {
    stubRangeRect();
    render(<Harness />);
    selectText(screen.getByText('这是一条可以引用的消息'));
    fireEvent.mouseDown(document.body);
    expect(screen.queryByRole('button', { name: '引用此内容' })).not.toBeInTheDocument();

    selectText(screen.getByText('这是一条可以引用的消息'));
    fireEvent.scroll(screen.getByTestId('feed'));
    expect(screen.queryByRole('button', { name: '引用此内容' })).not.toBeInTheDocument();
  });

  it('replaces the quoted text when another message is accepted', async () => {
    stubRangeRect();
    render(<Harness />);
    selectText(screen.getByText('这是一条可以引用的消息'));
    await userEvent.click(screen.getByRole('button', { name: '引用此内容' }));
    selectText(screen.getByText('另一条消息'));
    await userEvent.click(screen.getByRole('button', { name: '引用此内容' }));

    expect(screen.getAllByText('另一条消息')).toHaveLength(2);
    expect(screen.getAllByText('这是一条可以引用的消息')).toHaveLength(1);
  });

  it('removes the quote from the card', async () => {
    stubRangeRect();
    render(<Harness />);
    selectText(screen.getByText('这是一条可以引用的消息'));
    await userEvent.click(screen.getByRole('button', { name: '引用此内容' }));
    await userEvent.click(screen.getByRole('button', { name: '移除引用' }));

    expect(screen.queryByText('引用内容')).not.toBeInTheDocument();
  });

  it('keeps paragraph breaks when the selection spans blocks', async () => {
    stubRangeRect();
    render(<Harness />);
    selectAcross(screen.getByText('第一段'), screen.getByText('第二段'));
    await userEvent.click(screen.getByRole('button', { name: '引用此内容' }));

    const card = screen.getByText('引用内容').parentElement!;
    expect(card.textContent).toContain('第一段\n\n第二段');
  });

  it('skips ignored and interactive content the selection crosses', async () => {
    stubRangeRect();
    render(<Harness />);
    selectAcross(screen.getByText('前段文字'), screen.getByText('后段文字'));
    await userEvent.click(screen.getByRole('button', { name: '引用此内容' }));

    const card = screen.getByText('引用内容').parentElement!;
    expect(card.textContent).toContain('前段文字');
    expect(card.textContent).toContain('后段文字');
    expect(card.textContent).not.toContain('附件名称');
    expect(card.textContent).not.toContain('操作按钮');
  });

  it('keeps inline link text inside the selection', async () => {
    stubRangeRect();
    render(<Harness />);
    selectAcross(screen.getByText('含链接前文'), screen.getByText('含链接后文'));
    await userEvent.click(screen.getByRole('button', { name: '引用此内容' }));

    const card = screen.getByText('引用内容').parentElement!;
    expect(card.textContent).toContain('含链接前文链接文字含链接后文');
  });

  it('marks an over-long quote as truncated and clamps the card to three lines', async () => {
    stubRangeRect();
    render(<Harness />);
    selectText(screen.getByTestId('long'));
    await userEvent.click(screen.getByRole('button', { name: '引用此内容' }));

    expect(screen.getByText('引用内容已截断')).toBeInTheDocument();
    const card = screen.getByText('引用内容').parentElement!;
    const body = within(card).getByText(LONG_TEXT);
    expect((body as HTMLElement).style.getPropertyValue('-webkit-line-clamp')).toBe('3');
  });
});
