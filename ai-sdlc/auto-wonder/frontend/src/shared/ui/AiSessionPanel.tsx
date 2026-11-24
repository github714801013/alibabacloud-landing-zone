import { i18n } from '@/shared/i18n';
import { useTranslation } from 'react-i18next';
import { useState, useRef, useEffect, useCallback } from 'react';
import { Alert, Input, Button, List, Typography, Space, Tag, Spin, message } from 'antd';
import type { TextAreaRef } from 'antd/es/input/TextArea';
import { SendOutlined } from '@ant-design/icons';
import { apiClient } from '@/shared/api/client';
import { useRealtime } from '@/shared/realtime/useRealtime';
import { MarkdownView } from './MarkdownView';
import { composeQuotedContent } from './quote-block';
import { QuoteCard, QuotePill, useTextQuote } from './TextQuote';
import { ResultPane } from './aiResult/ResultPane';
import { rendererRegistry } from './aiResult/registry';
import type { AiSession, AiSessionVOResponse, AiStreamEvent, AiSessionStatus } from '@/shared/types/ai';
import { useAccessCommand } from '@/shared/auth/useAccessCommand';

const { Text } = Typography;
const { TextArea } = Input;

interface AiSessionPanelProps {
  scene: string;
  bizRefType: string;
  bizRefId: string | number;
  autoStartInput?: string;
  onConfirm?: (resultJson: string) => void;
}

const statusColor: Record<AiSessionStatus, string> = {
  QUEUED: 'default',
  RUNNING: 'processing',
  WAIT_USER: 'warning',
  COMPLETED: 'success',
  FAILED: 'error',
  CANCELED: 'default',
};

function stableStringify(value: unknown): string {
  if (Array.isArray(value)) {
    return `[${value.map(stableStringify).join(',')}]`;
  }
  if (value && typeof value === 'object') {
    return `{${Object.keys(value as Record<string, unknown>).sort()
      .map((key) => `${JSON.stringify(key)}:${stableStringify((value as Record<string, unknown>)[key])}`)
      .join(',')}}`;
  }
  return JSON.stringify(value);
}

export function AiSessionPanel({ scene, bizRefType, bizRefId, autoStartInput, onConfirm }: AiSessionPanelProps) {
  const { t } = useTranslation('shared');
  const runWithAccess = useAccessCommand();
  const [session, setSession] = useState<AiSession | null>(null);
  const [streamingContent, setStreamingContent] = useState('');
  const [input, setInput] = useState('');
  const [loading, setLoading] = useState(false);
  const [autoStartError, setAutoStartError] = useState<{ message: string } | { key: 'ai.createFailed' } | null>(null);
  const [narrow, setNarrow] = useState(false);
  const bottomRef = useRef<HTMLDivElement>(null);
  const rootRef = useRef<HTMLDivElement>(null);
  const feedRef = useRef<HTMLDivElement>(null);
  const inputRef = useRef<TextAreaRef>(null);
  const autoStartedRef = useRef(false);
  const composingRef = useRef(false);
  const sessionId = session?.id;
  const sessionStatus = session?.status;

  const loadSession = useCallback(async (id: number) => {
    const resp = await apiClient.get<AiSessionVOResponse>(`/api/ai/sessions/${id}`);
    const vo = resp.data;
    setSession({ ...vo, messages: vo.messages ?? [] });
  }, []);

  const createSession = useCallback(async (text: string) => {
    const resp = await apiClient.post<number>('/api/ai/sessions', {
      scene,
      bizRefType,
      bizRefId,
      input: text,
    });
    await loadSession(resp.data);
  }, [bizRefId, bizRefType, loadSession, scene]);

  useRealtime(session ? `ai:session:${session.id}` : null, {
    onEvent: (event) => {
      const data = event.payload as AiStreamEvent;
      if ((data.type === 'ai_token' || data.type === 'delta') && (data.token || data.text)) {
        setStreamingContent((prev) => prev + (data.token || data.text || ''));
      } else if (data.type === 'ai_message_done' && data.message) {
        setStreamingContent('');
        setSession((prev) => (prev ? { ...prev, messages: [...prev.messages, data.message!] } : prev));
      } else if ((data.type === 'session_status' || data.type === 'status') && data.status) {
        setSession((prev) =>
          prev ? { ...prev, status: data.status!, resultJson: data.resultJson ?? prev.resultJson } : prev,
        );
        if (data.status === 'WAIT_USER' || data.status === 'FAILED' || data.status === 'COMPLETED') {
          setStreamingContent('');
          const sid = data.sessionId as number | undefined;
          if (sid) loadSession(sid);
        }
      } else if (data.type === 'result' && data.resultJson) {
        setSession((prev) =>
          prev ? { ...prev, resultJson: data.resultJson!, status: data.status ?? prev.status } : prev,
        );
        setStreamingContent('');
        const sid = data.sessionId as number | undefined;
        if (sid) loadSession(sid);
      }
    },
    enabled: !!session,
  });

  useEffect(() => {
    if (!rootRef.current || typeof ResizeObserver === 'undefined') return;
    const ro = new ResizeObserver((entries) => {
      setNarrow(entries[0].contentRect.width < 960);
    });
    ro.observe(rootRef.current);
    return () => ro.disconnect();
  }, []);

  useEffect(() => {
    if (!sessionId || (sessionStatus !== 'QUEUED' && sessionStatus !== 'RUNNING')) return;
    const timer = setInterval(async () => {
      try {
        const resp = await apiClient.get<AiSessionVOResponse>(`/api/ai/sessions/${sessionId}`);
        const vo = resp.data;
        setSession({ ...vo, messages: vo.messages ?? [] });
      } catch { /* ignore */ }
    }, 3000);
    return () => clearInterval(timer);
  }, [sessionId, sessionStatus]);

  useEffect(() => {
    if (bottomRef.current && typeof bottomRef.current.scrollIntoView === 'function') {
      bottomRef.current.scrollIntoView({ behavior: 'smooth' });
    }
  }, [session?.messages, streamingContent]);

  useEffect(() => {
    const text = autoStartInput?.trim();
    if (!text || session || autoStartedRef.current || loading) return;
    autoStartedRef.current = true;
    const pending = runWithAccess('READ_WRITE', t('ai.start'), () => {
      setAutoStartError(null);
      setLoading(true);
      return createSession(text);
    });
    if (!pending) return;
    pending
      .catch((e: Error) => {
        setAutoStartError(e.message ? { message: e.message } : { key: 'ai.createFailed' });
      })
      .finally(() => setLoading(false));
  }, [autoStartInput, createSession, loading, runWithAccess, session, t]);

  const handleSend = () => {
    // 输入沿用乐观清空，但失败要回填草稿；引用只在成功后移除，保证失败可原样重试。
    const content = composeQuotedContent(quote, input.trim());
    if (!content || loading) return;
    const submitted = input;
    void runWithAccess('READ_WRITE', t('ai.sendAction'), async () => {
      const previousSession = session;
      setInput('');
      setLoading(true);
      try {
        if (!session) {
          await createSession(content);
        } else {
          setSession((prev) =>
            prev
              ? {
                  ...prev,
                  status: 'QUEUED' as AiSessionStatus,
                  messages: [
                    ...prev.messages,
                    { id: Date.now(), sessionId: prev.id, role: 'USER', content, gmtCreate: new Date().toISOString() },
                  ],
                }
              : prev,
          );
          await apiClient.post(`/api/ai/sessions/${session.id}/messages`, { content });
        }
        removeQuote();
      } catch (error) {
        setInput((current) => current || submitted);
        const errorMessage = error instanceof Error ? error.message : i18n.t('shared:ai.sendFailed');
        message.error(errorMessage);
        if (previousSession) {
          try {
            await loadSession(previousSession.id);
          } catch {
            setSession(previousSession);
          }
        }
      } finally {
        setLoading(false);
      }
    });
  };

  const handleConfirmed = (resultJson: string) => {
    if (scene === 'AGENT_CONFIG_GEN') {
      onConfirm?.(resultJson);
      return;
    }
    const sessionId = session?.id;
    setSession((prev) => (prev ? { ...prev, status: 'COMPLETED', resultJson } : prev));
    onConfirm?.(resultJson);
    if (sessionId) {
      loadSession(sessionId).catch(() => undefined);
    }
  };

  const canSend = session ? session.status === 'WAIT_USER' : !autoStartInput;
  const { quote, truncated, pill, acceptPill, removeQuote } = useTextQuote({
    containerRef: feedRef,
    enabled: canSend && !loading,
    resetKey: sessionId,
    onAccept: () => inputRef.current?.focus(),
  });
  const messages = session?.messages ?? [];

  const isStructuredResultMessage = (content: string) => {
    if (!session?.resultJson || !rendererRegistry[session.scene]) return false;
    if (content.trim() === session.resultJson.trim()) return true;
    try {
      const msgObj = JSON.parse(content);
      const resultObj = JSON.parse(session.resultJson);
      return stableStringify(msgObj) === stableStringify(resultObj);
    } catch {
      return false;
    }
  };

  const renderMessageContent = (msg: AiSession['messages'][number]) => {
    if (msg.role === 'AI' && isStructuredResultMessage(msg.content)) {
      return <Alert type="success" showIcon message={t('ai.resultReady')} />;
    }
    return <MarkdownView content={msg.content} />;
  };

  const autoStartErrorText = autoStartError && ('key' in autoStartError ? t(autoStartError.key) : autoStartError.message);
  const chat = (
    <div style={{ display: 'flex', flexDirection: 'column', flex: 1, minHeight: 0, minWidth: 0 }}>
      <div ref={feedRef} style={{ flex: 1, overflow: 'auto', padding: 16 }}>
        {!session && autoStartError && (
          <Alert
            type="error"
            showIcon
            message={t('ai.errorTitle')}
            description={autoStartErrorText}
            style={{ marginBottom: 12 }}
          />
        )}
        {!session && loading && (
          <div style={{ padding: '24px 0', textAlign: 'center' }}>
            <Spin />
            <div style={{ marginTop: 8 }}><Text type="secondary">{t('ai.creating')}</Text></div>
          </div>
        )}
        <List
          dataSource={messages}
          renderItem={(msg) => (
            <List.Item style={{ border: 'none', padding: '8px 0' }}>
              <div style={{ width: '100%' }} data-quote-message>
                <Text strong type={msg.role === 'USER' ? 'success' : msg.role === 'AI' ? undefined : 'secondary'} data-quote-ignore>
                  {msg.role === 'USER' ? t('ai.you') : msg.role === 'AI' ? 'AI' : t('ai.system')}
                </Text>
                <div style={{ marginTop: 4 }}>
                  {renderMessageContent(msg)}
                </div>
              </div>
            </List.Item>
          )}
        />
        {streamingContent && (
          <div style={{ padding: '8px 0' }}>
            <Text strong>AI</Text>
            <div style={{ marginTop: 4 }}><MarkdownView content={streamingContent} /></div>
            <Spin size="small" />
          </div>
        )}
        {session && (session.status === 'QUEUED' || session.status === 'RUNNING') && !streamingContent && (
          <div style={{ padding: '8px 0', display: 'flex', alignItems: 'center', gap: 8 }}>
            <Spin size="small" />
            <Text type="secondary">
              {scene === 'CLARIFICATION' && messages.length === 0
                ? t('ai.preparing')
                : t('ai.thinking')}
            </Text>
          </div>
        )}
        <div ref={bottomRef} />
      </div>
      <div style={{ padding: 12, borderTop: '1px solid var(--aw-border)' }}>
        {quote && (
          <div style={{ marginBottom: 8 }}>
            <QuoteCard
              quote={quote}
              truncated={truncated}
              label={t('quote.cardLabel')}
              removeLabel={t('quote.remove')}
              truncatedLabel={t('quote.truncated')}
              onRemove={removeQuote}
            />
          </div>
        )}
        <Space.Compact style={{ width: '100%' }}>
          <TextArea
            ref={inputRef}
            value={input}
            onChange={(e) => setInput(e.target.value)}
            placeholder={t('ai.input')}
            autoSize={{ minRows: 1, maxRows: 4 }}
            disabled={!canSend}
            onKeyDown={(e) => {
              if (e.nativeEvent.isComposing || composingRef.current) return;
              if (e.shiftKey) return;
              if (e.key === 'Enter') {
                e.preventDefault();
                handleSend();
              }
            }}
            onCompositionStart={() => { composingRef.current = true; }}
            onCompositionEnd={() => { composingRef.current = false; }}
          />
          <Button type="primary" icon={<SendOutlined />} onClick={handleSend} loading={loading} disabled={!canSend}>{t('ai.send')}</Button>
        </Space.Compact>
      </div>
    </div>
  );

  const result = (
    <div style={{ flex: 1, minWidth: 0, padding: 16, overflow: 'auto', borderLeft: narrow ? undefined : '1px solid var(--aw-border)', borderTop: narrow ? '1px solid var(--aw-border)' : undefined }}>
      <ResultPane session={session} onConfirmed={handleConfirmed} />
    </div>
  );

  return (
    <div ref={rootRef} style={{ display: 'flex', flexDirection: 'column', height: '100%', minHeight: 400 }}>
      <QuotePill pill={pill} label={t('quote.pill')} onAccept={acceptPill} />
      {session && (
        <div style={{ padding: '8px 12px', borderBottom: '1px solid var(--aw-border)' }}>
          <Space>
            <Text type="secondary">{t('ai.session', { id: session.id })}</Text>
            <Tag color={statusColor[session.status]}>{t(`ai.status.${session.status}`)}</Tag>
          </Space>
        </div>
      )}
      <div style={{ display: 'flex', flexDirection: narrow ? 'column' : 'row', flex: 1, minHeight: 0 }}>
        {chat}
        {result}
      </div>
    </div>
  );
}
