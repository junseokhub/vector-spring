package com.milvus.vector_spring.chat;

import com.milvus.vector_spring.chat.dto.*;
import com.milvus.vector_spring.common.apipayload.status.ErrorStatus;
import com.milvus.vector_spring.common.exception.CustomException;
import com.milvus.vector_spring.content.ContentService;
import com.milvus.vector_spring.content.dto.ContentDto;
import com.milvus.vector_spring.llm.dto.ConversationTurn;
import com.milvus.vector_spring.llm.dto.EmbedRequestDto;
import com.milvus.vector_spring.llm.dto.EmbedResponseDto;
import com.milvus.vector_spring.llm.provider.LlmProviderRouter;
import com.milvus.vector_spring.milvus.VectorSearchService;
import com.milvus.vector_spring.llm.LlmPlatform;
import com.milvus.vector_spring.project.Project;
import com.milvus.vector_spring.project.ProjectService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

// Kafka publish commented out — ChatTaskConsumer is now called directly via @Async.
// To restore Kafka: uncomment the fields below, restore publishChatEvent body,
// and re-enable KafkaConfig + ChatTaskConsumer Kafka annotations.
//
// import com.milvus.vector_spring.config.properties.KafkaProperties;
// import org.springframework.kafka.core.KafkaTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class ChatService {

    private final ProjectService projectService;
    private final VectorSearchService vectorSearchService;
    private final ContentService contentService;
    private final ChatCompletionService chatCompletionService;
    private final LlmProviderRouter llmProviderRouter;
    private final ChatTaskConsumer chatTaskConsumer;
    private final ConversationHistoryService conversationHistoryService;

    // private final KafkaTemplate<String, Object> kafkaTemplate; // Kafka disabled
    // private final KafkaProperties kafkaProperties;             // Kafka disabled

    public ChatResponseDto chat(ChatRequestDto request) {
        try {
            Project project = projectService.findOneProjectByKey(request.projectKey());
            LlmPlatform platform = project.getLlmPlatform() != null ? project.getLlmPlatform() : LlmPlatform.OPENAI;
            String apiKey = projectService.decryptApiKey(project);
            LocalDateTime inputTime = LocalDateTime.now();

            List<ConversationTurn> history = conversationHistoryService.getRecentHistory(request.sessionId());

            EmbedResponseDto embedResponse = llmProviderRouter.embed(
                    EmbedRequestDto.from(platform, apiKey, project.getEmbedModel(), request.text(), project.getDimensions())
            );

            VectorSearchResponseDto searchResult = vectorSearchService.search(
                    embedResponse.embedding(), request.text(), project.getId()
            );

            AnswerGenerationResultDto answer = chatCompletionService.generateAnswer(
                    platform, project.getChatModel(), request.text(), apiKey,
                    searchResult.results(), project.getPrompt(), embedResponse.totalTokens(), history
            );

            ContentDto finalContent = Optional.of(answer)
                    .filter(a -> !a.isPromptAnswer())
                    .map(a -> searchResult.firstSearchId())
                    .map(contentService::findContentBySearchId)
                    .map(ContentDto::from)
                    .orElse(null);

            LocalDateTime outputTime = LocalDateTime.now();

            if (platform != LlmPlatform.OLLAMA) {
                projectService.plusTotalToken(project.getKey(), answer.totalToken());
            }

            publishChatEvent(project.getKey(), answer.totalToken(), request,
                    answer.finalAnswer(), finalContent, searchResult.results(), inputTime, outputTime);

            return new ChatResponseDto(
                    request.projectKey(),
                    request.sessionId(),
                    request.text(),
                    answer.finalAnswer(),
                    inputTime,
                    outputTime,
                    searchResult.results(),
                    finalContent
            );

        } catch (CustomException e) {
            throw e;
        } catch (Exception e) {
            throw new CustomException(ErrorStatus.INTERNAL_SERVER_ERROR, e);
        }
    }

    private void publishChatEvent(
            String projectKey, long totalToken, ChatRequestDto request,
            String output, ContentDto content,
            java.util.List<VectorSearchRankDto> rankList,
            LocalDateTime inputTime, LocalDateTime outputTime
    ) {
        ChatCompleteEvent event = new ChatCompleteEvent(
                projectKey, totalToken, request.sessionId(),
                request.text(), output, content, rankList, inputTime, outputTime
        );

        // Kafka disabled — calling ChatTaskConsumer directly via @Async (virtual thread)
        chatTaskConsumer.handlePostChatTask(event);

        // Kafka publish (re-enable with KafkaConfig + ChatTaskConsumer Kafka annotations):
        // kafkaTemplate.send(kafkaProperties.topic(), event.sessionId(), event);
    }
}
