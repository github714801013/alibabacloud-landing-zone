import { apiClient } from '@/shared/api/client';

export interface WecomBinding {
  id: number;
  platformConfigId: number | null;
  groupId: string;
  routeVersion: number;
  agentId: number;
  creatorId: number | null;
  status: 'ENABLED' | 'DISABLED';
  version: number;
  lastSuccessAt: string | null;
  lastError: string | null;
}

export interface WecomBindingRequest {
  groupId?: string;
  agentId?: number;
  status?: 'ENABLED' | 'DISABLED';
  version?: number;
  authorizationConfirmed?: boolean;
}

const BASE = '/api/integrations/wecom/bindings';

export async function listWecomBindings(): Promise<WecomBinding[]> {
  return (await apiClient.get<WecomBinding[]>(BASE)).data;
}

export async function saveWecomBinding(
  id: number | undefined,
  body: WecomBindingRequest,
): Promise<WecomBinding> {
  return (id == null
    ? await apiClient.post<WecomBinding>(BASE, body)
    : await apiClient.put<WecomBinding>(`${BASE}/${id}`, body)).data;
}

export async function deleteWecomBinding(id: number): Promise<void> {
  await apiClient.delete(`${BASE}/${id}`);
}
