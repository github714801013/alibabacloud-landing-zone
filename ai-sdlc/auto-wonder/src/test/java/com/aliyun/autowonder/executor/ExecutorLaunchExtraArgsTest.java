package com.aliyun.autowonder.executor;

import com.aliyun.autowonder.common.error.BizException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ExecutorLaunchExtraArgsTest {

    @Test
    void normalizeTrimsAndTreatsBlankAsAbsent() {
        assertNull(ExecutorLaunchExtraArgs.normalize(null));
        assertNull(ExecutorLaunchExtraArgs.normalize("   "));
        assertEquals("--foo bar", ExecutorLaunchExtraArgs.normalize("  --foo bar  "));
    }

    @Test
    void normalizeRejectsTextOverTheLengthLimit() {
        String tooLong = "--x " + "a".repeat(ExecutorLaunchExtraArgs.MAX_LENGTH);

        BizException ex = assertThrows(BizException.class, () -> ExecutorLaunchExtraArgs.normalize(tooLong));

        assertEquals("17012", ex.getCode());
    }

    @Test
    void normalizeRejectsOverridingPlatformConnectionFlags() {
        for (String flag : List.of("--ws-url", "--token", "--executor-id")) {
            BizException ex = assertThrows(BizException.class,
                    () -> ExecutorLaunchExtraArgs.normalize(flag + "=attacker-value"));
            assertEquals("17012", ex.getCode());
        }
        assertEquals("--model new-model", ExecutorLaunchExtraArgs.normalize("--model new-model"));
    }

    @Test
    void tokenizeSplitsOnWhitespaceAndGroupsQuotedRuns() {
        assertEquals(List.of("--foo", "bar", "--msg", "a b", "--path", "a\tb"),
                ExecutorLaunchExtraArgs.tokenize("--foo bar --msg 'a b' --path \"a\tb\""));
    }

    @Test
    void tokenizeUnescapesABackslashOutsideQuotes() {
        assertEquals(List.of("--msg", "a b"), ExecutorLaunchExtraArgs.tokenize("--msg a\\ b"));
    }

    @Test
    void tokenizeKeepsAnEmptyQuotedArgument() {
        assertEquals(List.of("--empty", ""), ExecutorLaunchExtraArgs.tokenize("--empty ''"));
    }

    @Test
    void tokenizeRejectsMalformedText() {
        assertEquals("17012", assertThrows(BizException.class,
                () -> ExecutorLaunchExtraArgs.tokenize("--msg 'a b")).getCode());
        assertEquals("17012", assertThrows(BizException.class,
                () -> ExecutorLaunchExtraArgs.tokenize("--path a\\")).getCode());
        assertEquals("17012", assertThrows(BizException.class,
                () -> ExecutorLaunchExtraArgs.tokenize("--msg a\nb")).getCode());
    }

    @Test
    void flagNamesStripsTheEqualsValueAndKeepsOrder() {
        // 裸值也会被收进来，但它们不会与平台参数同名，因此不会误判为覆盖
        assertEquals(List.of("--model", "--memory-mode", "none", "bare"),
                List.copyOf(ExecutorLaunchExtraArgs.flagNames(
                        List.of("--model=my-model", "--memory-mode", "none", "bare"))));
    }

    @Test
    void tokenizeLetsExtraArgumentsOverridePlatformFlags() {
        // 扩展参数追加在平台参数之后，同名时以扩展参数为准——有意允许，因此这里不拦截
        assertEquals(List.of("--model", "my-model", "--context-window", "400000"),
                ExecutorLaunchExtraArgs.tokenize("--model my-model --context-window 400000"));
        assertEquals(List.of("--model=my-model", "--token", "other"),
                ExecutorLaunchExtraArgs.tokenize("--model=my-model --token other"));
    }
}
