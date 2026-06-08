package com.milvus.vector_spring.project;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ProjectRepository  extends JpaRepository<Project, Long>, ProjectCustomRepository {
    Optional<Project> findProjectByKey(String key);

    @Modifying
    @Query("DELETE FROM Project c WHERE c.createdBy.id = :userId")
    void deleteByUserId(@Param("userId") Long userId);

    @Query("SELECT p FROM Project p JOIN FETCH p.createdBy WHERE p.createdBy.id = :userId")
    List<Project> findAllByCreatedBy(@Param("userId") Long userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Project p where p.key = :key")
    Project findProjectByKeyForUpdate(@Param("key") String key);

    @Modifying
    @Query("UPDATE Project p SET p.totalToken = p.totalToken + :tokens WHERE p.key = :key")
    int plusTotalToken(@Param("key") String key, @Param("tokens") long tokens);
}
