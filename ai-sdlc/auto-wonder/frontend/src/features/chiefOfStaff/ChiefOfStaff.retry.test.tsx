import { beforeEach, afterEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor, cleanup, fireEvent, within } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { useAuthStore } from '@/shared/auth/store';
import { i18n } from '@/shared/i18n';
import { ChiefOfStaff } from './ChiefOfStaff';
import * as api from './api';

vi.mock('./api');
vi.mock('@/features/executor/api');
vi.mock('@/shared/realtime/useRealtime', () => ({ useRealtime: vi.fn() }));

const ready: api.SetupStatus = {
  agentId: 10, executorId: 20, agentConfigured: true, skillsConfigured: true,
  executorConfigured: true, launchConfigured: true, ready: true, missingSkills: [],
};

interface TurnSeed {
  id: number;
  direction: string;
  content: string;
  status: string;
  error: string | null;
}

function turn(seed: TurnSeed) {
  return { ...seed, gmtCreate: '' };
}

function conversation(overrides: Partial<api.Conversation> = {}): api.Conversation {
  return {
    id: 100, agentId: 10, agentName: 'Chief of Staff', channelConversationId: 'private',
    title: 'My conversation', archivedAt: null, status: 'ACTIVE', executorOnline: true,
    streamingSupported: true, cancelSupported: true, cliSessionRef: null,
    processingStatus: null, processingTurnId: null, lastTurnAt: null, gmtCreate: '',
    owner: true, turns: [],
    ...overrides,
  };
}

const failedConversation = conversation({
  turns: [
    turn({ id: 11, direction: 'IN', content: '出问题的消息', status: 'FAILED', error: null }),
    turn({ id: 12, direction: 'OUT', content: '', status: 'FAILED',
      error: 'conversation actor capabilities unavailable' }),
  ],
});

let clients: QueryClient[] = [];

class TestResizeObserver {
  observe() {}
  unobserve() {}
  disconnect() {}
}

function mount() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  clients.push(client);
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={['/workitems/128']}>
        <ChiefOfStaff />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

async function openPanel() {
  fireEvent.click(await screen.findByRole('button', { name: '打开 Chief of Staff' }));
  return screen.findByText('出问题的消息');
}

beforeEach(async () => {
  localStorage.clear();
  vi.resetAllMocks();
  useAuthStore.getState().clear();
  await i18n.changeLanguage('zh-CN');
  Object.defineProperty(window, 'innerWidth', { configurable: true, value: 1280 });
  Object.defineProperty(window, 'innerHeight', { configurable: true, value: 800 });
  Element.prototype.scrollIntoView = vi.fn();
  Element.prototype.setPointerCapture = vi.fn();
  Element.prototype.releasePointerCapture = vi.fn();
  Object.defineProperty(globalThis, 'ResizeObserver', { configurable: true, writable: true, value: TestResizeObserver });
  Object.defineProperty(window, 'matchMedia', { configurable: true, writable: true, value: (query: string) => ({
    matches: false, media: query, onchange: null, addEventListener: vi.fn(), removeEventListener: vi.fn(),
    addListener: vi.fn(), removeListener: vi.fn(), dispatchEvent: vi.fn(),
  }) });
  useAuthStore.getState().setUser({ id: 7, username: 'owner', nickname: 'Owner', email: '' });
  useAuthStore.getState().setCurrentWorkspace({ id: 1, name: 'Project', description: '' }, 'ADMIN');
  localStorage.setItem('chief-of-staff:7:1', JSON.stringify({ conversationId: 100 }));
  vi.mocked(api.setupStatus).mockResolvedValue(ready);
  vi.mocked(api.getConversation).mockResolvedValue(failedConversation);
  vi.mocked(api.listConversations).mockResolvedValue([]);
  vi.mocked(api.getPlans).mockResolvedValue([]);
  vi.mocked(api.getTurnEventPage).mockResolvedValue({ rows: [], nextAfterId: 0, hasMore: false });
  vi.mocked(api.retryTurn).mockResolvedValue(undefined);
});

afterEach(() => {
  cleanup();
  clients.forEach(client => client.clear());
  clients = [];
});

describe('ChiefOfStaff 会话重试', () => {
  it('offers retry on the failed user message and posts that turn id', async () => {
    mount();
    const userMessage = await openPanel();
    const section = userMessage.closest('section') as HTMLElement;

    fireEvent.click(await within(section).findByRole('button', { name: /重试/ }));

    await waitFor(() => expect(api.retryTurn).toHaveBeenCalledWith(100, 11));
  });

  it('hides the stale failure once the retried turn is processing again', async () => {
    vi.mocked(api.getConversation)
      .mockResolvedValueOnce(failedConversation)
      .mockResolvedValue(conversation({
        processingStatus: 'PROCESSING',
        processingTurnId: 11,
        turns: [
          turn({ id: 11, direction: 'IN', content: '出问题的消息', status: 'PROCESSING', error: null }),
          turn({ id: 12, direction: 'OUT', content: '', status: 'FAILED',
            error: 'conversation actor capabilities unavailable' }),
        ],
      }));
    mount();
    const userMessage = await openPanel();
    expect(screen.getByText('conversation actor capabilities unavailable')).toBeInTheDocument();

    fireEvent.click(await within(userMessage.closest('section') as HTMLElement)
      .findByRole('button', { name: /重试/ }));

    await waitFor(() => {
      expect(screen.queryByText('conversation actor capabilities unavailable')).toBeNull();
      expect(screen.queryByRole('button', { name: /重试/ })).toBeNull();
    });
  });

  it('hides the stale failure while the retried turn waits in the queue', async () => {
    vi.mocked(api.getConversation)
      .mockResolvedValueOnce(failedConversation)
      .mockResolvedValue(conversation({
        turns: [
          turn({ id: 11, direction: 'IN', content: '出问题的消息', status: 'QUEUED', error: null }),
          turn({ id: 12, direction: 'OUT', content: '', status: 'FAILED',
            error: 'conversation actor capabilities unavailable' }),
        ],
      }));
    mount();
    const userMessage = await openPanel();
    expect(screen.getByText('conversation actor capabilities unavailable')).toBeInTheDocument();

    fireEvent.click(await within(userMessage.closest('section') as HTMLElement)
      .findByRole('button', { name: /重试/ }));

    await waitFor(() => {
      expect(screen.queryByText('conversation actor capabilities unavailable')).toBeNull();
    });
  });

  it('keeps the retry button hidden for users who do not own the conversation', async () => {
    vi.mocked(api.getConversation).mockResolvedValue(conversation({ owner: false, turns: failedConversation.turns }));
    mount();
    const userMessage = await openPanel();

    expect(within(userMessage.closest('section') as HTMLElement)
      .queryByRole('button', { name: /重试/ })).toBeNull();
  });

  it('keeps the retry button hidden for read-only members', async () => {
    useAuthStore.getState().setCurrentWorkspace({ id: 1, name: 'Project', description: '' }, 'READ_ONLY');
    mount();
    const userMessage = await openPanel();

    expect(within(userMessage.closest('section') as HTMLElement)
      .queryByRole('button', { name: /重试/ })).toBeNull();
  });
});
