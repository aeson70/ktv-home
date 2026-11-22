package com.homektv.queue;

/**
 * 演唱完成事件：PlaybackService 在一首歌自然播完（TV 上报 finished）后发布，
 * 供 AI 评分等监听器异步消费。事件只携带评分所需的快照数据，不依赖数据库。
 *
 * Song-finished event: published by PlaybackService when a song ends naturally
 * (TV reports "finished"), consumed asynchronously by listeners such as the AI
 * performance scorer. Carries only snapshot data, no database access needed.
 */
public record SongFinishedEvent(Long queueId, Long songId, String title, String artist, String language) {}
