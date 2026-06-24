package com.reubenagent.chat.orchestrate;

import com.reubenagent.chat.enums.ExecutionMode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.time.Duration;

/**
 * Phase 2 stub 执行器 —— 回显问题，让 SSE 管线可端到端跑通。
 *
 * <p>占用 {@link ExecutionMode#REACT_AGENT} 模式位（与 OPEN_CHAT 一致），Phase 8
 * 接入真实执行器注册后由 registry 替换。</p>
 *
 * @author reuben
 * @since 2026-06-24
 */
@Slf4j
@Component
public class EchoExecutor implements ConversationExecutor {

    @Override
    public ExecutionMode mode() {
        return ExecutionMode.REACT_AGENT;
    }

    @Override
    public Flux<String> execute(ChatTaskInfo taskInfo) {
        String question = taskInfo.getQuestion();
        log.info("[stub] EchoExecutor 处理 → conversationId={} question={}",
                taskInfo.getConversationId(), question);
        // 阶段：分块回显，模拟流式生成
        String reply = "（stub 回显）你问的是：" + question + "\nPhase 2 管线已打通，等待接入真实执行器。";
        return Flux.fromStream(java.util.stream.IntStream.range(0, reply.length())
                        .mapToObj(reply::charAt)
                        .map(String::valueOf))
                .delayElements(Duration.ofMillis(15));
    }
}
