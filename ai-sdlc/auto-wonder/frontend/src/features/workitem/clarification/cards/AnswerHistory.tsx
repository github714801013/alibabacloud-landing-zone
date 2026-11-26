import { Button, Typography } from 'antd';
import { useTranslation } from 'react-i18next';
import type { QuestionHistoryEntry } from '../answerHistory';
import { ElicitationCard } from './ElicitationCard';

export function AnswerHistory({ entries, onContinue, canContinue = false }: {
  entries: readonly QuestionHistoryEntry[];
  onContinue?: (entry: QuestionHistoryEntry) => void;
  canContinue?: boolean;
}) {
  const { t } = useTranslation('workitem');
  const history = entries.map(entry => {
    const expired = entry.status === 'EXPIRED';
    // 超时、系统取消、用户跳过都保留历史卡，用户仍可改选后按文本发送。
    const continuable = expired || entry.status === 'CANCELED' || entry.status === 'DECLINED'
      || entry.action === 'cancel' || entry.action === 'decline';
    const issue = entry.deliveryStatus === 'UNKNOWN' || entry.deliveryStatus === 'UNAVAILABLE'
      ? t(`clarification.answerDelivery.${entry.deliveryStatus}`) : null;
    return <div key={`${entry.turnId}:${entry.requestId}`} data-testid={`question-history-${entry.turnId}-${entry.requestId}`}>
    <ElicitationCard requestId={entry.requestId} message={entry.message} schema={entry.requestedSchema}
      resolved action={entry.action} answers={entry.content} expired={expired} />
    {entry.rawAnswer && <details><summary>{t('clarification.history.originalAnswer')}</summary><pre style={{ whiteSpace: 'pre-wrap' }}>{entry.rawAnswer}</pre></details>}
    {issue && <Typography.Text type="warning" style={{ display: 'block', marginBottom: 8 }}>{issue}</Typography.Text>}
    {continuable && onContinue && <div style={{ marginBottom: 12 }}>
      <Button size="small" disabled={!canContinue} onClick={() => onContinue(entry)}>{t('clarification.history.continue')}</Button>
      {!canContinue && <Typography.Text type="secondary"> {t('clarification.history.continueUnavailable')}</Typography.Text>}
    </div>}
  </div>; });
  return <>{history}</>;
}
