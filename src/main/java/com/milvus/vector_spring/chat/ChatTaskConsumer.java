package com.milvus.vector_spring.chat;

import com.milvus.vector_spring.chat.dto.ChatCompleteEvent;
import com.milvus.vector_spring.config.mongo.document.ChatResponseDocument;
import com.milvus.vector_spring.project.ProjectService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

// Kafka annotations commented out — now called directly via @Async from ChatService.
// To restore Kafka: uncomment the annotations below, add KafkaProperties field back,
// restore KafkaTemplate publish in ChatService, and re-enable KafkaConfig.
//
// import com.milvus.vector_spring.config.properties.KafkaProperties;
// import org.springframework.kafka.annotation.DltHandler;
// import org.springframework.kafka.annotation.KafkaListener;
// import org.springframework.kafka.annotation.RetryableTopic;
// import org.springframework.kafka.retrytopic.TopicSuffixingStrategy;
// import org.springframework.kafka.support.KafkaHeaders;
// import org.springframework.messaging.handler.annotation.Header;
// import org.springframework.retry.annotation.Backoff;

@Component
@RequiredArgsConstructor
@Slf4j
public class ChatTaskConsumer {

    private final ProjectService projectService;
    private final MongoTemplate mongoTemplate;

    // private final KafkaProperties kafkaProperties; // Kafka disabled

    // @RetryableTopic(
    //         attempts = "5",
    //         backoff = @Backoff(delay = 2000, multiplier = 2),
    //         topicSuffixingStrategy = TopicSuffixingStrategy.SUFFIX_WITH_INDEX_VALUE,
    //         exclude = {NullPointerException.class}
    // )
    // @KafkaListener(
    //         topics = "${spring.kafka.topic}",
    //         groupId = "${spring.kafka.consumer.group-id}"
    // )
    @Async
    public void handlePostChatTask(ChatCompleteEvent event) {
        try {
            mongoTemplate.save(ChatResponseDocument.from(event));
            log.info("Chat result saved: sessionId={}", event.sessionId());
        } catch (DuplicateKeyException e) {
            log.warn("Duplicate skipped: sessionId={}", event.sessionId());
        } catch (Exception e) {
            // async void라 throw는 무의미 → 로깅만 (앞의 죽은 throw 제거)
            log.error("Mongo save failed (non-critical): sessionId={}, {}",
                    event.sessionId(), e.getMessage());
        }
    }

    // @DltHandler — Kafka dead-letter topic handler, disabled with Kafka
    // public void handleDlt(
    //         ChatCompleteEvent event,
    //         @Header(KafkaHeaders.RECEIVED_TOPIC) String topic,
    //         @Header(KafkaHeaders.EXCEPTION_MESSAGE) String errorMessage
    // ) {
    //     log.error("DLT received: topic={}, error={}, sessionId={}", topic, errorMessage, event.sessionId());
    // }
}
