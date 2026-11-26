import { describe, it, expect, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import type { QuestionHistoryEntry } from '../answerHistory';
import { AnswerHistory } from './AnswerHistory';

const schema = {
  type: 'object',
  properties: {
    q0: {
      type: 'string',
      title: '问题',
      oneOf: [{ const: 'a', title: 'A' }, { const: 'b', title: 'B' }],
    },
  },
  required: ['q0'],
};

const entry = (status: string, requestId: string): QuestionHistoryEntry => ({
  turnId: 1,
  requestId,
  message: `问题 ${requestId}`,
  requestedSchema: schema,
  resolved: true,
  status,
  action: status === 'ANSWERED' ? 'accept' : status === 'DECLINED' ? 'decline' : 'cancel',
});

describe('AnswerHistory 继续回答入口', () => {
  it.each(['EXPIRED', 'CANCELED', 'DECLINED'])('settled %s card can reopen the form', (status) => {
    const onContinue = vi.fn();
    render(<AnswerHistory entries={[entry(status, `req-${status}`)]} canContinue onContinue={onContinue} />);

    fireEvent.click(screen.getByRole('button', { name: '继续回答' }));

    expect(onContinue).toHaveBeenCalledWith(expect.objectContaining({ status, requestId: `req-${status}` }));
  });

  it('answered card keeps no reopen entry', () => {
    render(<AnswerHistory entries={[entry('ANSWERED', 'req-answered')]} canContinue onContinue={vi.fn()} />);

    expect(screen.queryByRole('button', { name: '继续回答' })).toBeNull();
  });
});
