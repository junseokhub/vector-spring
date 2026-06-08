package com.milvus.vector_spring.project;

import com.milvus.vector_spring.common.apipayload.status.ErrorStatus;
import com.milvus.vector_spring.common.exception.CustomException;
import com.milvus.vector_spring.common.service.EncryptionService;
import com.milvus.vector_spring.invite.dto.CombinedProjectListResponseDto;
import com.milvus.vector_spring.llm.LlmModelRegistry;
import com.milvus.vector_spring.llm.LlmPlatform;
import com.milvus.vector_spring.milvus.MilvusService;
import com.milvus.vector_spring.project.dto.ProjectUpdateRequestDto;
import com.milvus.vector_spring.user.User;
import com.milvus.vector_spring.user.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class ProjectService {

    private final ProjectRepository projectRepository;
    private final UserService userService;
    private final EncryptionService encryptionService;
    private final MilvusService milvusService;
    private final LlmModelRegistry modelRegistry;

    public List<Project> findAllProject() {
        return projectRepository.findAll();
    }

    public List<CombinedProjectListResponseDto> findMyProjectsAsDto(Long userId) {
        return projectRepository.findMyProjectsAsDto(userId);
    }

    public Project findOneProject(Long id) {
        return projectRepository.findById(id)
                .orElseThrow(() -> new CustomException(ErrorStatus.NOT_FOUND_PROJECT));
    }

    public Project findOneProjectByKey(String key) {
        return projectRepository.findProjectByKey(key)
                .orElseThrow(() -> new CustomException(ErrorStatus.NOT_FOUND_PROJECT));
    }

    @Transactional
    public Project create(Long userId, String name, long dimensions) {
        User user = userService.findOneUser(userId);
        Project project = Project.builder()
                .name(name)
                .key(UUID.randomUUID().toString())
                .dimensions(dimensions)
                .totalToken(0)
                .createdBy(user)
                .updatedBy(user)
                .build();
        Project saved = projectRepository.save(project);
        try {
            milvusService.createSchema(saved.getId(), (int) dimensions);
        } catch (Exception e) {
            log.error("Milvus schema creation failed for project {}: {}", saved.getId(), e.getMessage());
            throw new CustomException(ErrorStatus.MILVUS_DATABASE_ERROR);
        }
        return saved;
    }

    @Transactional
    public Project updateProject(Long updateUserId, String key, ProjectUpdateRequestDto dto) {
        User user = userService.findOneUser(updateUserId);
        Project project = projectRepository.findProjectByKey(key)
                .orElseThrow(() -> new CustomException(ErrorStatus.NOT_FOUND_PROJECT));

        LlmPlatform platform = dto.llmPlatform() != null ? dto.llmPlatform() : project.getLlmPlatform();

        // Dimensions are immutable once the Milvus collection is created
        if (project.getDimensions() > 0 && dto.dimensions() > 0
                && dto.dimensions() != project.getDimensions()) {
            throw new CustomException(ErrorStatus.DIMENSION_MISMATCH);
        }

        if (dto.embedModel() != null && !dto.embedModel().isBlank() && platform != null) {
            // validate against existing dimensions (not the requested ones)
            long dims = project.getDimensions() > 0 ? project.getDimensions()
                    : (dto.dimensions() > 0 ? dto.dimensions() : 0);
            if (dims > 0) modelRegistry.validateEmbedModel(platform, dto.embedModel(), dims);
        }
        if (dto.chatModel() != null && !dto.chatModel().isBlank() && platform != null) {
            modelRegistry.validateChatModel(platform, dto.chatModel());
        }

        String resolvedApiKey = resolveApiKey(project.getApiKey(), dto.apiKey());
        project.update(dto.name(), resolvedApiKey, dto.chatModel(), dto.embedModel(), dto.prompt(), dto.llmPlatform(), user);
        return project;
    }

    @Transactional
    public void delete(Long userId, String projectKey) {
        Project project = projectRepository.findProjectByKey(projectKey)
                .orElseThrow(() -> new CustomException(ErrorStatus.NOT_FOUND_PROJECT));
        if (!Objects.equals(project.getCreatedBy().getId(), userId)) {
            throw new CustomException(ErrorStatus.NOT_PROJECT_MASTER_USER);
        }
        milvusService.deleteCollection(project.getId());
        projectRepository.delete(project);
    }

    @Transactional
    public void plusTotalToken(String key, long additionalTokens) {
        int updated = projectRepository.plusTotalToken(key, additionalTokens);
        if (updated == 0) {
            log.warn("Token accumulate skipped, project not found: key={}", key);
        }
    }

    @Transactional
    public void transferOwnership(Project project, User newOwner) {
        project.transferOwnership(newOwner);
        projectRepository.save(project);
    }

    public String decryptApiKey(Project project) {
        if (project.getLlmPlatform() == LlmPlatform.OLLAMA) {
            return null;
        }
        if (project.getApiKey() == null || project.getApiKey().isBlank()) {
            throw new CustomException(ErrorStatus.REQUIRE_OPEN_AI_INFO);
        }
        return encryptionService.decryptData(project.getApiKey());
    }

    private String resolveApiKey(String existingEncryptedKey, String incomingPlainKey) {
        boolean hasExisting = existingEncryptedKey != null && !existingEncryptedKey.isBlank();
        boolean hasIncoming = incomingPlainKey != null && !incomingPlainKey.isBlank();

        if (!hasExisting && !hasIncoming) return null;
        if (!hasExisting) return encryptionService.encryptData(incomingPlainKey);
        if (!hasIncoming) return existingEncryptedKey;

        String decrypted = encryptionService.decryptData(existingEncryptedKey);
        return decrypted.equals(incomingPlainKey) ? existingEncryptedKey : encryptionService.encryptData(incomingPlainKey);
    }
}
