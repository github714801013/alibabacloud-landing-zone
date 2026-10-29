package com.aliyun.autowonder.conversation;

import com.aliyun.autowonder.dispatch.ExecutorProtocolCompatibilityException;
import com.aliyun.autowonder.dispatch.ExecutorSelector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 会话准入与均衡（本地二次开发）：新建会话按四级顺序选机——
 * 沿用现有可用性/容量/特性过滤 → 排除存在排队积压的执行器 →
 * 剩余候选按均衡窗口内绑定数升序取最少（相同按轮询游标平局）→
 * 全部候选都有积压时选择队列深度最小的执行器。
 * Redis 不可用时返回 null，调用方退回现有轮询选机。绑定与队列计数经本组件旁路记录。
 */
@Component
public class ConversationAdmissionService {

    private static final Logger log = LoggerFactory.getLogger(ConversationAdmissionService.class);

    private final ConversationExecutorRouter router;
    private final ExecutorSelector selector;
    private final ConversationExecutorStats stats;

    public ConversationAdmissionService(ConversationExecutorRouter router, ExecutorSelector selector,
            ConversationExecutorStats stats) {
        this.router = router;
        this.selector = selector;
        this.stats = stats;
    }

    /** 为新建会话选择执行器；null 表示降级或没有可用候选，调用方使用既有选机。 */
    public Long selectNewConversation(long tenantId, long agentId, long agentVersionId, boolean platform) {
        String required = router.requiredFeatures(tenantId, agentVersionId, platform);
        List<Long> candidates;
        try {
            candidates = selector.eligibleExecutors(agentId, required);
        } catch (ExecutorProtocolCompatibilityException incompatible) {
            throw incompatible;
        } catch (RuntimeException degraded) {
            log.warn("conversation admission degraded agentId={}: {}", agentId, degraded.getMessage());
            return null;
        }
        if (candidates.isEmpty()) {
            return null;
        }
        Map<Long, Integer> queued = stats.queueDepths(tenantId, agentId, candidates);
        if (queued == null) {
            return null;
        }
        List<Long> idle = candidates.stream().filter(id -> queued.getOrDefault(id, 0) <= 0).toList();
        if (idle.isEmpty()) {
            int shortest = candidates.stream().mapToInt(id -> queued.getOrDefault(id, 0)).min().orElse(0);
            List<Long> leastQueued = candidates.stream()
                    .filter(id -> queued.getOrDefault(id, 0) == shortest).toList();
            log.info("conversation admission all backlogged agentId={} candidates={} shortest={}",
                    agentId, candidates.size(), shortest);
            return selector.selectRoundRobin(agentId, leastQueued);
        }
        Map<Long, Integer> bound = stats.bindingCounts(tenantId, agentId, idle);
        if (bound == null) {
            return null;
        }
        int least = idle.stream().mapToInt(id -> bound.getOrDefault(id, 0)).min().orElse(0);
        List<Long> leastBound = idle.stream().filter(id -> bound.getOrDefault(id, 0) == least).toList();
        return selector.selectRoundRobin(agentId, leastBound);
    }

    /** 新会话绑定执行器时记录一次绑定计数。 */
    public void recordBinding(long tenantId, long agentId, long executorId) {
        stats.recordBinding(tenantId, agentId, executorId);
    }

    /** 轮次入队自增、出队自减。 */
    public void recordQueueDepth(long tenantId, long agentId, long executorId, long delta) {
        stats.recordQueueDepth(tenantId, agentId, executorId, delta);
    }

    /** 等待空闲槽位超过排队上限。 */
    public boolean queueWaitExpired(long turnId) {
        return stats.queueWaitExpired(turnId);
    }

    /** 轮次进入派发或终结后清理排队起算点。 */
    public void clearQueueWait(long turnId) {
        stats.clearQueueWait(turnId);
    }
}
