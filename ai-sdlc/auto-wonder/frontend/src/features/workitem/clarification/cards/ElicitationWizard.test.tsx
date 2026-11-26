import { i18n } from '@/shared/i18n';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent, act } from '@testing-library/react';
import { ElicitationWizard } from './ElicitationWizard';

const schema = {
  type: 'object',
  properties: {
    q0: { type: 'string', title: '<b>技术栈</b>', oneOf: [{ const: 'A', title: 'A' }, { const: 'B', title: 'B' }] },
    q1: { type: 'array', title: '交付物', items: { anyOf: [{ const: 'C', title: 'C' }] } },
    q2: { type: 'string', title: '补充' },
  },
  required: ['q0', 'q1'],
};
const next = () => screen.getByTestId('elicitation-next-r1');
const prev = () => screen.getByTestId('elicitation-prev-r1');
const stepDot = (i: number) => screen.getByTestId(`elicitation-step-r1-${i}`);
const advance = () => act(() => { vi.advanceTimersByTime(320); });
/** 跨进最后一题后的主按钮静默期（400ms），过后才允许真正提交。 */
const settle = () => act(() => { vi.advanceTimersByTime(420); });
const submitBtn = () => screen.getByTestId('elicitation-submit-r1');
const clickSubmit = () => fireEvent.click(submitBtn());

describe('ElicitationWizard', () => {
  beforeEach(() => { vi.useFakeTimers(); });
  afterEach(() => { vi.useRealTimers(); });

  it('一次只显示一题，含计数与步骤点', () => {
    render(<ElicitationWizard requestId="r1" schema={schema} onReply={vi.fn()} />);
    expect(screen.getByTestId('elicitation-count-r1')).toHaveTextContent('第 1/3 题');
    expect(screen.getByTestId('elicitation-option-q0-0')).toBeInTheDocument();
    expect(screen.queryByTestId('elicitation-field-q2')).toBeNull();
    expect(stepDot(0)).toBeInTheDocument();
    expect(stepDot(2)).toBeInTheDocument();
  });

  it('把问题消息作为可见标题渲染', () => {
    render(<ElicitationWizard requestId="r1" message="请确认以下几点" schema={schema} onReply={vi.fn()} />);
    expect(screen.getByText('请确认以下几点')).toBeInTheDocument();
  });

  it('单选取值后自动进入下一题', () => {
    render(<ElicitationWizard requestId="r1" schema={schema} onReply={vi.fn()} />);
    fireEvent.click(screen.getByTestId('elicitation-option-q0-0'));
    advance();
    expect(screen.getByTestId('elicitation-count-r1')).toHaveTextContent('第 2/3 题');
  });

  it('多选题点「下一题」推进；上一题返回且答案保留', () => {
    render(<ElicitationWizard requestId="r1" schema={schema} onReply={vi.fn()} />);
    fireEvent.click(screen.getByTestId('elicitation-option-q0-0'));
    advance();
    fireEvent.click(screen.getByTestId('elicitation-option-q1-0'));
    fireEvent.click(next());
    expect(screen.getByTestId('elicitation-count-r1')).toHaveTextContent('第 3/3 题');
    fireEvent.click(prev());
    expect(screen.getByTestId('elicitation-count-r1')).toHaveTextContent('第 2/3 题');
    expect(screen.getByTestId('elicitation-option-q1-0')).toBeInTheDocument();
  });

  it('步骤点可跳回任意题', () => {
    render(<ElicitationWizard requestId="r1" schema={schema} onReply={vi.fn()} />);
    fireEvent.click(screen.getByTestId('elicitation-option-q0-0'));
    advance();
    fireEvent.click(stepDot(0));
    expect(screen.getByTestId('elicitation-count-r1')).toHaveTextContent('第 1/3 题');
  });

  it('必填未答点提交：跳到第一个缺题并标红，不回传', () => {
    const onReply = vi.fn();
    render(<ElicitationWizard requestId="r1" schema={schema} onReply={onReply} />);
    fireEvent.click(stepDot(2));
    settle();
    clickSubmit();
    expect(onReply).not.toHaveBeenCalled();
    expect(screen.getByTestId('elicitation-count-r1')).toHaveTextContent('第 1/3 题');
    expect(screen.getByTestId('elicitation-error-q0')).toBeInTheDocument();
  });

  it('末题全答完提交：回传编码内容', () => {
    const onReply = vi.fn();
    render(<ElicitationWizard requestId="r1" schema={schema} onReply={onReply} />);
    fireEvent.click(screen.getByTestId('elicitation-option-q0-1'));
    advance();
    fireEvent.click(screen.getByTestId('elicitation-option-q1-0'));
    fireEvent.click(next());
    settle();
    fireEvent.change(screen.getByTestId('elicitation-field-q2'), { target: { value: '备注' } });
    clickSubmit();
    expect(onReply).toHaveBeenCalledWith({
      action: 'accept',
      content: { q0: 'B', q1: ['C'], q2: '备注' },
      text: 'Q1 <b>技术栈</b>：B\nQ2 交付物：C\nQ3 补充：备注',
    });
  });

  it('跳过回传 decline', () => {
    const onReply = vi.fn();
    render(<ElicitationWizard requestId="r1" schema={schema} onReply={onReply} />);
    fireEvent.click(screen.getByTestId('elicitation-skip-r1'));
    expect(onReply).toHaveBeenCalledWith({ action: 'decline' });
  });

  it('单选选「有其他想法」填字后提交，文本替换原值', () => {
    const onReply = vi.fn();
    render(<ElicitationWizard requestId="r1" schema={schema} onReply={onReply} />);
    fireEvent.click(screen.getByTestId('elicitation-option-q0-other'));
    fireEvent.change(screen.getByTestId('elicitation-other-input-q0'), { target: { value: '自定义' } });
    fireEvent.click(next());
    fireEvent.click(screen.getByTestId('elicitation-option-q1-0'));
    fireEvent.click(next());
    settle();
    clickSubmit();
    // 可选第 3 题未答：先确认，确认后提交已答字段。
    expect(onReply).not.toHaveBeenCalled();
    expect(screen.getByTestId('elicitation-unanswered-r1')).toHaveTextContent('补充');
    fireEvent.click(screen.getByTestId('elicitation-confirm-submit-r1'));
    expect(onReply).toHaveBeenCalledWith({
      action: 'accept',
      content: { q0: '自定义', q1: ['C'] },
      text: 'Q1 <b>技术栈</b>：自定义\nQ2 交付物：C',
    });
  });

  it('选「有其他想法」但空值，必填拦下并提示', () => {
    const onReply = vi.fn();
    render(<ElicitationWizard requestId="r1" schema={schema} onReply={onReply} />);
    fireEvent.click(screen.getByTestId('elicitation-option-q0-other'));
    fireEvent.click(next());
    expect(onReply).not.toHaveBeenCalled();
    expect(screen.getByTestId('elicitation-error-q0')).toHaveTextContent('请填写其他想法');
  });

  it('自动切换延时内手动导航，则不再叠加自动跳题', () => {
    render(<ElicitationWizard requestId="r1" schema={schema} onReply={vi.fn()} />);
    fireEvent.click(screen.getByTestId('elicitation-option-q0-0')); // 安排 300ms 后自动切到第2题
    fireEvent.click(stepDot(1));                                    // 延时内手动跳到第2题
    advance();                                                      // 旧定时器若仍生效会再叠加跳到第3题
    expect(screen.getByTestId('elicitation-count-r1')).toHaveTextContent('第 2/3 题');
  });

  it('多选勾选「有其他想法」但为空，必填拦下', () => {
    const onReply = vi.fn();
    render(<ElicitationWizard requestId="r1" schema={schema} onReply={onReply} />);
    fireEvent.click(screen.getByTestId('elicitation-option-q0-0'));
    advance();
    fireEvent.click(screen.getByTestId('elicitation-option-q1-other'));
    fireEvent.click(next());
    expect(onReply).not.toHaveBeenCalled();
    expect(screen.getByTestId('elicitation-error-q1')).toHaveTextContent('请填写其他想法');
  });

  it('多选勾选「有其他想法」并填写，提交时追加到数组末尾', () => {
    const onReply = vi.fn();
    render(<ElicitationWizard requestId="r1" schema={schema} onReply={onReply} />);
    fireEvent.click(screen.getByTestId('elicitation-option-q0-0'));
    advance();
    fireEvent.click(screen.getByTestId('elicitation-option-q1-0'));
    fireEvent.click(screen.getByTestId('elicitation-option-q1-other'));
    fireEvent.change(screen.getByTestId('elicitation-other-input-q1'), { target: { value: '要离线' } });
    fireEvent.click(next());
    settle();
    clickSubmit();
    fireEvent.click(screen.getByTestId('elicitation-confirm-submit-r1'));
    expect(onReply).toHaveBeenCalledWith({
      action: 'accept',
      content: { q0: 'A', q1: ['C', '要离线'] },
      text: 'Q1 <b>技术栈</b>：A\nQ2 交付物：C、要离线',
    });
  });

  it('单选题作为末题时停在提交、不自动切换或提交', () => {
    const onReply = vi.fn();
    const singleLast = {
      type: 'object',
      properties: { q0: { type: 'string', title: '唯一题', oneOf: [{ const: 'A', title: 'A' }] } },
      required: ['q0'],
    };
    render(<ElicitationWizard requestId="r1" schema={singleLast} onReply={onReply} />);
    fireEvent.click(screen.getByTestId('elicitation-option-q0-0'));
    advance();
    expect(screen.getByTestId('elicitation-submit-r1')).toBeInTheDocument();
    expect(onReply).not.toHaveBeenCalled();
  });

  // 回归：主按钮在「下一题」与「提交」之间原位复用同一节点，用户连点第二下
  // （或焦点未移出时回车）会把上一步的意图落到提交上，导致部分答案被静默提交。
  it('跨进最后一题时主按钮短暂静默：连点第二下不会提交', () => {
    const onReply = vi.fn();
    render(<ElicitationWizard requestId="r1" schema={schema} onReply={onReply} />);
    fireEvent.click(screen.getByTestId('elicitation-option-q0-0'));
    advance();
    fireEvent.click(screen.getByTestId('elicitation-option-q1-0'));
    fireEvent.click(next());
    expect(submitBtn()).toBeDisabled();
    clickSubmit();
    clickSubmit();
    expect(onReply).not.toHaveBeenCalled();
    expect(screen.getByTestId('elicitation-count-r1')).toHaveTextContent('第 3/3 题');
  });

  it('跨进最后一题后焦点被移出主按钮，回车不会落到提交上', () => {
    render(<ElicitationWizard requestId="r1" schema={schema} onReply={vi.fn()} />);
    fireEvent.click(screen.getByTestId('elicitation-option-q0-0'));
    advance();
    fireEvent.click(screen.getByTestId('elicitation-option-q1-0'));
    const nextButton = next();
    nextButton.focus();
    expect(nextButton).toHaveFocus();
    fireEvent.click(nextButton);
    expect(submitBtn()).not.toHaveFocus();
  });

  it('有未作答题目时先确认：取消不提交且已答内容保留，确认后才回传', () => {
    const onReply = vi.fn();
    render(<ElicitationWizard requestId="r1" schema={schema} onReply={onReply} />);
    fireEvent.click(screen.getByTestId('elicitation-option-q0-1'));
    advance();
    fireEvent.click(screen.getByTestId('elicitation-option-q1-0'));
    fireEvent.click(next());
    settle();
    clickSubmit();
    expect(onReply).not.toHaveBeenCalled();
    expect(screen.getByTestId('elicitation-unanswered-r1')).toHaveTextContent('补充');
    fireEvent.click(screen.getByTestId('elicitation-confirm-cancel-r1'));
    expect(onReply).not.toHaveBeenCalled();
    expect(screen.getByTestId('elicitation-count-r1')).toHaveTextContent('第 3/3 题');
    clickSubmit();
    fireEvent.click(screen.getByTestId('elicitation-confirm-submit-r1'));
    expect(onReply).toHaveBeenCalledWith({
      action: 'accept',
      content: { q0: 'B', q1: ['C'] },
      text: 'Q1 <b>技术栈</b>：B\nQ2 交付物：C',
    });
  });
});


describe('ElicitationWizard 收起/展开（工单 56330）', () => {
  const toggle = () => screen.getByTestId('elicitation-toggle-r1');

  beforeEach(() => { vi.useFakeTimers(); });
  afterEach(() => { vi.useRealTimers(); vi.restoreAllMocks(); });

  it('collapsible 缺省：无收起入口、无动效类，结构与现状一致', () => {
    render(<ElicitationWizard requestId="r1" schema={schema} onReply={vi.fn()} />);
    expect(screen.queryByTestId('elicitation-toggle-r1')).toBeNull();
    expect(screen.getByTestId('elicitation-skip-r1')).toBeInTheDocument();
    expect(screen.getByTestId('elicitation-step-r1-0')).toBeInTheDocument();
    expect(screen.getByTestId('elicitation-option-q0-0')).toBeInTheDocument();
    expect(screen.getByTestId('elicitation-wizard-r1').className).toBe('');
  });

  it('默认展开，点箭头收起为一行，再点恢复完整卡片', () => {
    render(<ElicitationWizard requestId="r1" message="请确认以下几点" schema={schema} collapsible onReply={vi.fn()} />);
    expect(screen.getByTestId('elicitation-wizard-r1')).toHaveClass('aw-elicitation-card');
    expect(toggle()).toHaveAttribute('aria-expanded', 'true');
    expect(toggle()).toHaveAccessibleName('收起问题卡片');

    fireEvent.click(toggle());
    expect(screen.getByTestId('elicitation-collapsed-r1')).toBeInTheDocument();
    expect(screen.getByText('请确认以下几点')).toBeInTheDocument();
    expect(screen.getByTestId('elicitation-count-r1')).toHaveTextContent('第 1/3 题');
    expect(screen.queryByTestId('elicitation-skip-r1')).toBeNull();
    expect(screen.queryByTestId('elicitation-step-r1-0')).toBeNull();
    expect(screen.queryByTestId('elicitation-option-q0-0')).toBeNull();
    expect(screen.queryByTestId('elicitation-prev-r1')).toBeNull();
    expect(screen.queryByTestId('elicitation-next-r1')).toBeNull();
    expect(toggle()).toHaveAttribute('aria-expanded', 'false');
    expect(toggle()).toHaveAccessibleName('展开问题卡片');

    fireEvent.click(toggle());
    expect(screen.getByTestId('elicitation-skip-r1')).toBeInTheDocument();
    expect(screen.getByTestId('elicitation-step-r1-0')).toBeInTheDocument();
    expect(screen.getByTestId('elicitation-option-q0-0')).toBeInTheDocument();
    expect(toggle()).toHaveAttribute('aria-expanded', 'true');
  });

  it('收起不丢作答：恢复后当前题与已填答案原样保留', () => {
    const onReply = vi.fn();
    render(<ElicitationWizard requestId="r1" schema={schema} collapsible onReply={onReply} />);
    fireEvent.click(screen.getByTestId('elicitation-option-q0-1'));
    advance();
    fireEvent.click(toggle());
    expect(screen.getByTestId('elicitation-count-r1')).toHaveTextContent('第 2/3 题');
    fireEvent.click(toggle());
    expect(screen.getByTestId('elicitation-count-r1')).toHaveTextContent('第 2/3 题');
    fireEvent.click(screen.getByTestId('elicitation-option-q1-0'));
    fireEvent.click(next());
    settle();
    clickSubmit();
    fireEvent.click(screen.getByTestId('elicitation-confirm-submit-r1'));
    expect(onReply).toHaveBeenCalledWith({
      action: 'accept',
      content: { q0: 'B', q1: ['C'] },
      text: 'Q1 <b>技术栈</b>：B\nQ2 交付物：C',
    });
  });

  it('提交/跳过在途（submitting）时收起按钮仍可点击，不阻塞原有流程', () => {
    render(<ElicitationWizard requestId="r1" schema={schema} submitting collapsible onReply={vi.fn()} />);
    expect(toggle()).toBeEnabled();
    fireEvent.click(toggle());
    expect(screen.getByTestId('elicitation-collapsed-r1')).toBeInTheDocument();
    expect(toggle()).toHaveAttribute('aria-expanded', 'false');
  });

  it('收起/展开按旧高度到新高度做像素过渡，动画结束归还内联样式', () => {
    render(<ElicitationWizard requestId="r1" schema={schema} collapsible onReply={vi.fn()} />);
    const root = screen.getByTestId('elicitation-wizard-r1');
    // jsdom 无布局，这里显式模拟：点击时读到旧高度 300，重排后读到新高度 64。
    const heights = [300, 64, 300];
    Object.defineProperty(root, 'offsetHeight', {
      configurable: true,
      get: () => heights.shift() ?? 64,
    });

    fireEvent.click(toggle());
    expect(root.style.height).toBe('64px');
    expect(root.style.transition).toContain('180ms');
    fireEvent.transitionEnd(root);
    expect(root.style.height).toBe('');
    expect(root.style.transition).toBe('');

    // 新旧高度相同（无布局变化）时不叠加过渡，直接瞬时切换。
    fireEvent.click(toggle());
    expect(screen.getByTestId('elicitation-skip-r1')).toBeInTheDocument();
    expect(root.style.height).toBe('');
    expect(root.style.transition).toBe('');
  });

  it('减少动画偏好下不做像素过渡，收起/展开瞬时呈现', () => {
    vi.spyOn(window, 'matchMedia').mockImplementation(((query: string) => ({
      matches: query.includes('prefers-reduced-motion'),
      media: query,
      onchange: null,
      addListener: () => {},
      removeListener: () => {},
      addEventListener: () => {},
      removeEventListener: () => {},
      dispatchEvent: () => false,
    })) as unknown as typeof window.matchMedia);
    render(<ElicitationWizard requestId="r1" schema={schema} collapsible onReply={vi.fn()} />);
    const root = screen.getByTestId('elicitation-wizard-r1');
    Object.defineProperty(root, 'offsetHeight', { configurable: true, value: 300 });

    fireEvent.click(toggle());
    expect(screen.getByTestId('elicitation-collapsed-r1')).toBeInTheDocument();
    expect(root.style.height).toBe('');
    expect(root.style.transition).toBe('');
  });

  it('dismissing 只在开启 collapsible 时标记淡出类', () => {
    const first = render(<ElicitationWizard requestId="r1" schema={schema} collapsible dismissing onReply={vi.fn()} />);
    expect(screen.getByTestId('elicitation-wizard-r1')).toHaveClass('is-dismissing');
    first.unmount();
    render(<ElicitationWizard requestId="r1" schema={schema} dismissing onReply={vi.fn()} />);
    expect(screen.getByTestId('elicitation-wizard-r1').className).toBe('');
  });
});

describe('Elicitation live locale changes', () => {  afterEach(async () => { await i18n.changeLanguage('zh-CN'); });
  it.each([undefined, 'unknown', 'CONFIRMATION_REQUIRED'])('translates only a declared Runtime heading (%s) without losing an answer draft', async (kind) => {
    const rawSchema = {
      type: 'object', 'x-autowonder-message-kind': kind,
      properties: { answer: { type: 'string', title: '原始问题 {0}' } },
    };
    render(<ElicitationWizard requestId="r1" message="需要你的确认" schema={rawSchema} onReply={vi.fn()} />);
    fireEvent.change(screen.getByTestId('elicitation-field-answer'), { target: { value: "用户原文 O'Reilly {1}" } });
    await act(async () => { await i18n.changeLanguage('en-US'); });
    const heading = kind === 'CONFIRMATION_REQUIRED' ? 'Your confirmation is needed' : '需要你的确认';
    expect(screen.getByTestId('elicitation-wizard-r1')).toHaveAttribute('aria-label', heading);
    expect(screen.getByText(heading)).toBeInTheDocument();
    expect(screen.getByText('原始问题 {0}')).toBeInTheDocument();
    expect(screen.getByTestId('elicitation-field-answer')).toHaveValue("用户原文 O'Reilly {1}");
    await act(async () => { await i18n.changeLanguage('zh-CN'); });
    expect(screen.getByText('需要你的确认')).toBeInTheDocument();
    expect(screen.getByTestId('elicitation-field-answer')).toHaveValue("用户原文 O'Reilly {1}");
  });
  it('updates visible validation without touching schema names, selected answers or other-text drafts', async () => {
    const reply = vi.fn();
    render(<ElicitationWizard requestId="r1" schema={schema} onReply={reply} />);
    fireEvent.click(screen.getByTestId('elicitation-option-q0-other'));
    fireEvent.click(next());
    expect(screen.getByTestId('elicitation-error-q0')).toHaveTextContent('请填写其他想法');
    await act(async () => { await i18n.changeLanguage('en-US'); });
    expect(screen.getByTestId('elicitation-error-q0')).toHaveTextContent('Please enter your other ideas.');
    expect(screen.getByText('<b>技术栈</b>')).toBeInTheDocument();
    expect(screen.getByTestId('elicitation-wizard-r1').querySelector('b')).toBeNull();
    expect(screen.getByTestId('elicitation-count-r1')).toHaveTextContent('Question 1/3');
    fireEvent.change(screen.getByTestId('elicitation-other-input-q0'), { target: { value: '<b>我的方案</b>' } });
    await act(async () => { await i18n.changeLanguage('zh-CN'); });
    expect(screen.getByTestId('elicitation-other-input-q0')).toHaveValue('<b>我的方案</b>');
    fireEvent.click(next());
    fireEvent.click(screen.getByTestId('elicitation-option-q1-0'));
    fireEvent.click(next());
    await act(async () => { await i18n.changeLanguage('en-US'); });
    // 该 describe 用真实计时器：等过跨进最后一题后的主按钮静默期。
    await act(async () => { await new Promise((resolve) => setTimeout(resolve, 450)); });
    fireEvent.click(screen.getByTestId('elicitation-submit-r1'));
    fireEvent.click(screen.getByTestId('elicitation-confirm-submit-r1'));
    expect(reply).toHaveBeenCalledWith({
      action: 'accept',
      content: { q0: '<b>我的方案</b>', q1: ['C'] },
      text: 'Q1 <b>技术栈</b>：<b>我的方案</b>\nQ2 交付物：C',
    });
  });
  it('refreshes memoized unsupported-schema fallback and required labels', async () => {
    const raw = { type: 'array', description: '工具提供的原始说明' };
    render(<ElicitationWizard requestId="r1" schema={raw} onReply={vi.fn()} />);
    fireEvent.click(screen.getByTestId('elicitation-submit-r1'));
    await act(async () => { await i18n.changeLanguage('en-US'); });
    expect(screen.getByText('Answer (JSON)')).toBeInTheDocument();
    expect(screen.getByTestId('elicitation-error-_raw')).toHaveTextContent('Please fill in “Answer (JSON)”.');
    expect(screen.getByTestId('elicitation-raw-schema-_raw')).toHaveTextContent('工具提供的原始说明');
  });
  it('switches the collapse/expand entry label with the live locale', async () => {
    render(<ElicitationWizard requestId="r1" schema={schema} collapsible onReply={vi.fn()} />);
    const toggle = () => screen.getByTestId('elicitation-toggle-r1');
    expect(toggle()).toHaveAccessibleName('收起问题卡片');
    await act(async () => { await i18n.changeLanguage('en-US'); });
    expect(toggle()).toHaveAccessibleName('Collapse question card');
    fireEvent.click(toggle());
    expect(toggle()).toHaveAccessibleName('Expand question card');
    expect(screen.getByTestId('elicitation-count-r1')).toHaveTextContent('Question 1/3');
  });
});

describe('ElicitationWizard 超时后改发文本', () => {
  it('超时后提交：跳过未答确认，只把已选项拼成文本回传', async () => {
    const onReply = vi.fn();
    const onReplyText = vi.fn();
    render(<ElicitationWizard requestId="r1" schema={schema} expired onReply={onReply} onReplyText={onReplyText} />);
    fireEvent.click(screen.getByTestId('elicitation-option-q0-0'));
    fireEvent.click(stepDot(1));
    fireEvent.click(screen.getByTestId('elicitation-option-q1-0'));
    fireEvent.click(next());
    await act(async () => { await new Promise((resolve) => setTimeout(resolve, 450)); });
    clickSubmit();
    expect(screen.queryByTestId('elicitation-unanswered-r1')).toBeNull();
    expect(onReplyText).toHaveBeenCalledWith('Q1 <b>技术栈</b>：A\nQ2 交付物：C');
    expect(onReply).not.toHaveBeenCalled();
  });

  it('超时后必填未答：仍先走必填校验，不发文本', async () => {
    const onReplyText = vi.fn();
    render(<ElicitationWizard requestId="r1" schema={schema} expired onReply={vi.fn()} onReplyText={onReplyText} />);
    fireEvent.click(stepDot(2));
    await act(async () => { await new Promise((resolve) => setTimeout(resolve, 450)); });
    clickSubmit();
    expect(onReplyText).not.toHaveBeenCalled();
    expect(screen.getByTestId('elicitation-count-r1')).toHaveTextContent('第 1/3 题');
    expect(screen.getByTestId('elicitation-error-q0')).toBeInTheDocument();
  });

  it('超时后一项未选：提示至少选择一项且不发送', async () => {
    const optional = {
      type: 'object',
      properties: { q0: { type: 'string', title: '技术栈', oneOf: [{ const: 'A', title: 'A' }] } },
    };
    const onReplyText = vi.fn();
    render(<ElicitationWizard requestId="r1" schema={optional} expired onReply={vi.fn()} onReplyText={onReplyText} />);
    clickSubmit();
    expect(await screen.findByText('请至少选择一项')).toBeInTheDocument();
    expect(onReplyText).not.toHaveBeenCalled();
  });

  it('无写权限：提交按钮禁用并展示提示，点击不发送', async () => {
    const onReplyText = vi.fn();
    render(<ElicitationWizard requestId="r1" schema={schema} expired canSubmit={false}
      readOnlyHint="有工作空间写权限的成员可以回答。" onReply={vi.fn()} onReplyText={onReplyText} />);
    fireEvent.click(stepDot(2));
    await act(async () => { await new Promise((resolve) => setTimeout(resolve, 450)); });
    expect(submitBtn()).toBeDisabled();
    expect(screen.getByTestId('elicitation-readonly-r1')).toHaveTextContent('有工作空间写权限的成员可以回答。');
    fireEvent.click(submitBtn());
    expect(onReplyText).not.toHaveBeenCalled();
  });

  it('超时后跳过：只关闭卡片，不回传也不发送', () => {
    const onReply = vi.fn();
    const onReplyText = vi.fn();
    const onSkip = vi.fn();
    render(<ElicitationWizard requestId="r1" schema={schema} expired onReply={onReply} onReplyText={onReplyText} onSkip={onSkip} />);
    fireEvent.click(screen.getByTestId('elicitation-skip-r1'));
    expect(onSkip).toHaveBeenCalledOnce();
    expect(onReply).not.toHaveBeenCalled();
    expect(onReplyText).not.toHaveBeenCalled();
  });
});
