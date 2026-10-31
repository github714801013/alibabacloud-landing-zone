import { beforeEach, describe, expect, it } from 'vitest';
import {
  clearLocalDeliveryDefaults,
  clearLocalClarifyDefaults,
  diffClarifyDefaults,
  diffDeliveryDefaults,
  hasDeliveryLocalOverride,
  hasClarifyLocalOverride,
  mergeClarifyDefaults,
  mergeDeliveryDefaults,
  readLocalClarifyDefaults,
  readLocalDeliveryDefaults,
  resolveClarificationSelection,
  resolveDeliverySelection,
  writeLocalClarifyDefaults,
  writeLocalDeliveryDefaults,
  type ClarifySpaceDefaults,
  type DeliverySpaceDefaults,
} from './aiDefaults';

const spaceClarify: ClarifySpaceDefaults = {
  defaultSquadId: 9,
  defaultAgentId: 42,
  autoSendFirstPrompt: true,
  valid: true,
};

const spaceDelivery: DeliverySpaceDefaults = {
  defaultSquadId: 8,
  defaultAgentId: 41,
  valid: true,
};

describe('aiDefaults merge', () => {
  it('follows the space default for fields without a local override', () => {
    expect(mergeClarifyDefaults(spaceClarify, { autoSendFirstPrompt: false })).toEqual({
      defaultSquadId: 9,
      defaultAgentId: 42,
      autoSendFirstPrompt: false,
    });
  });

  it('lets an explicit null local field clear the space default', () => {
    expect(mergeClarifyDefaults(spaceClarify, { defaultSquadId: null, defaultAgentId: null })).toEqual({
      defaultSquadId: null,
      defaultAgentId: null,
      autoSendFirstPrompt: true,
    });
  });

  it('merges clarify and delivery independently', () => {
    expect(mergeDeliveryDefaults(spaceDelivery, { defaultAgentId: 77 })).toEqual({
      defaultSquadId: 8,
      defaultAgentId: 77,
    });
  });
});

describe('aiDefaults resolution', () => {
  const selection = (squadId: number, agentId: number) => ({ squadId, agentId });

  it('prefers the current selection over URL, memory and defaults', () => {
    const resolved = resolveClarificationSelection({
      explicit: selection(1, 11),
      url: selection(2, 22),
      memory: selection(3, 33),
      local: {},
      space: spaceClarify,
      localValid: true,
    });
    expect(resolved).toEqual({ selection: selection(1, 11), defaultSkipped: false });
  });

  it('falls through URL then memory before defaults', () => {
    expect(resolveClarificationSelection({
      explicit: null, url: selection(2, 22), memory: selection(3, 33),
      local: {}, space: spaceClarify, localValid: true,
    }).selection).toEqual(selection(2, 22));
    expect(resolveClarificationSelection({
      explicit: null, url: null, memory: selection(3, 33),
      local: {}, space: spaceClarify, localValid: true,
    }).selection).toEqual(selection(3, 33));
  });

  it('uses local over space defaults when no higher tier exists', () => {
    expect(resolveClarificationSelection({
      explicit: null, url: null, memory: null,
      local: { defaultSquadId: 5, defaultAgentId: 55 },
      space: spaceClarify,
      localValid: true,
    })).toEqual({ selection: selection(5, 55), defaultSkipped: false });
  });

  it('skips a space default the server marked invalid and reports it', () => {
    expect(resolveClarificationSelection({
      explicit: null, url: null, memory: null,
      local: {}, space: { ...spaceClarify, valid: false }, localValid: false,
    })).toEqual({ selection: null, defaultSkipped: true });
  });

  it('falls back to a valid space default when the local default is invalid', () => {
    expect(resolveClarificationSelection({
      explicit: null, url: null, memory: null,
      local: { defaultSquadId: 5, defaultAgentId: 55 },
      space: spaceClarify,
      localValid: false,
    })).toEqual({ selection: selection(9, 42), defaultSkipped: true });
  });

  it('keeps an explicit local clear instead of falling back to the space default', () => {
    expect(resolveClarificationSelection({
      explicit: null, url: null, memory: null,
      local: { defaultSquadId: null, defaultAgentId: null },
      space: spaceClarify,
      localValid: false,
    })).toEqual({ selection: null, defaultSkipped: false });
  });

  it('reports a half configured default as skipped', () => {
    expect(resolveClarificationSelection({
      explicit: null, url: null, memory: null,
      local: { defaultAgentId: 42 },
      space: { ...spaceClarify, defaultSquadId: null },
      localValid: true,
    })).toEqual({ selection: null, defaultSkipped: true });
  });

  it('keeps the selection page when nothing is configured', () => {
    expect(resolveClarificationSelection({
      explicit: null, url: null, memory: null,
      local: {},
      space: { defaultSquadId: null, defaultAgentId: null, autoSendFirstPrompt: false, valid: true },
      localValid: true,
    })).toEqual({ selection: null, defaultSkipped: false });
  });

  it('resolves delivery from memory before its own defaults', () => {
    expect(resolveDeliverySelection({
      memory: selection(3, 33),
      local: { defaultSquadId: 8, defaultAgentId: 41 },
      space: spaceDelivery,
      localValid: true,
    })).toEqual({ selection: selection(3, 33), defaultSkipped: false });
    expect(resolveDeliverySelection({
      memory: null,
      local: {},
      space: spaceDelivery,
      localValid: true,
    })).toEqual({ selection: selection(8, 41), defaultSkipped: false });
  });

  it('falls back to the space delivery default when the local pair is invalid', () => {
    expect(resolveDeliverySelection({
      memory: null,
      local: { defaultSquadId: 5, defaultAgentId: 55 },
      space: spaceDelivery,
      localValid: false,
    })).toEqual({ selection: selection(8, 41), defaultSkipped: true });
  });
});

describe('aiDefaults local diff', () => {
  it('stores only the switch when the pair still follows the space default', () => {
    expect(diffClarifyDefaults(spaceClarify, {
      defaultSquadId: 9, defaultAgentId: 42, autoSendFirstPrompt: false,
    })).toEqual({ autoSendFirstPrompt: false });
  });

  it('stores a changed pair and clears it with explicit nulls', () => {
    expect(diffClarifyDefaults(spaceClarify, {
      defaultSquadId: 5, defaultAgentId: 55, autoSendFirstPrompt: true,
    })).toEqual({ defaultSquadId: 5, defaultAgentId: 55 });
    expect(diffClarifyDefaults(spaceClarify, {
      defaultSquadId: null, defaultAgentId: null, autoSendFirstPrompt: true,
    })).toEqual({ defaultSquadId: null, defaultAgentId: null });
  });

  it('returns an empty override when everything matches the space default', () => {
    expect(diffDeliveryDefaults(spaceDelivery, {
      defaultSquadId: 8, defaultAgentId: 41,
    })).toEqual({});
  });
});

describe('aiDefaults local storage', () => {
  beforeEach(() => {
    localStorage.clear();
  });

  it('round-trips per-field local overrides', () => {
    writeLocalClarifyDefaults({ autoSendFirstPrompt: false });
    expect(readLocalClarifyDefaults()).toEqual({ autoSendFirstPrompt: false });
    expect(hasClarifyLocalOverride(readLocalClarifyDefaults())).toBe(true);

    clearLocalClarifyDefaults();
    expect(readLocalClarifyDefaults()).toEqual({});
    expect(hasClarifyLocalOverride(readLocalClarifyDefaults())).toBe(false);
  });

  it('keeps delivery overrides separate', () => {
    writeLocalDeliveryDefaults({ defaultSquadId: null, defaultAgentId: null });
    expect(readLocalClarifyDefaults()).toEqual({});
    expect(readLocalDeliveryDefaults()).toEqual({ defaultSquadId: null, defaultAgentId: null });
    expect(hasDeliveryLocalOverride(readLocalDeliveryDefaults())).toBe(true);
  });

  it('ignores malformed storage payloads', () => {
    localStorage.setItem('aw:clarify-defaults:local', '{oops');
    expect(readLocalClarifyDefaults()).toEqual({});
    clearLocalClarifyDefaults();
    clearLocalDeliveryDefaults();
  });
});
