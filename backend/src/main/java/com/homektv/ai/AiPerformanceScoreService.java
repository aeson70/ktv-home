package com.homektv.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.homektv.queue.SongFinishedEvent;
import com.homektv.ws.WsBroadcaster;
import com.homektv.ws.WsEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 演唱完成 AI 评分：收到演唱完成事件后异步调用已配置的 AI 模型打分，
 * 把分数和点评广播给 TV（score_result）和手机端。
 * 未配置 AI 或调用失败时静默跳过，绝不阻塞队列推进。
 *
 * AI performance scoring: after a song finishes, calls the configured AI model
 * asynchronously and broadcasts the score and comment (score_result) to the TV
 * and phones. Silently skipped when AI is not configured or the call fails;
 * never blocks queue advancement.
 */
@Service
public class AiPerformanceScoreService {

    private static final Logger log = LoggerFactory.getLogger(AiPerformanceScoreService.class);

    private final OpenAiCompatibleClient client;
    private final AiConfigService configService;
    private final WsBroadcaster broadcaster;

    public AiPerformanceScoreService(OpenAiCompatibleClient client, AiConfigService configService,
                                     WsBroadcaster broadcaster) {
        this.client = client;
        this.configService = configService;
        this.broadcaster = broadcaster;
    }

    @Async("aiBulkExecutor")
    @EventListener
    public void onSongFinished(SongFinishedEvent event) {
        if (event == null || event.title() == null || event.title().isBlank()) return;
        if (!configService.isConfigured()) return;
        try {
            JsonNode result = client.completeJson("BULK", scoringSystemPrompt(), scoringUserPrompt(event), 600);
            int score = result.path("score").asInt(-1);
            String comment = result.path("comment").asText("").trim();
            if (score < 0 || score > 100) {
                log.warn("AI 评分越界，已丢弃：score={}", score);
                return;
            }
            if (comment.isBlank()) comment = "唱得不错，继续加油！";
            if (comment.length() > 60) comment = comment.substring(0, 60);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("queue_id", event.queueId());
            payload.put("song_title", event.title());
            payload.put("artist", event.artist());
            payload.put("score", score);
            payload.put("comment", comment);
            broadcaster.broadcast(WsEvent.of(WsEvent.SCORE_RESULT, payload));
        } catch (Exception e) {
            log.warn("AI 评分失败，已跳过：{}", e.getMessage());
        }
    }

    private String scoringSystemPrompt() {
        return """
                你是家庭 KTV 的演唱评分员。只返回 JSON，不要 Markdown，不要解释。
                字段：score（整数，60-98 之间，结合歌曲难度和传唱度给分，鼓励为主）、
                comment（一句话点评，活泼热情，20 字以内）。
                """;
    }

    private String scoringUserPrompt(SongFinishedEvent event) {
        return "歌曲：《" + event.title() + "》\n歌手：" + event.artist()
                + "\n语种：" + event.language() + "\n请给这次演唱打分。";
    }
}
