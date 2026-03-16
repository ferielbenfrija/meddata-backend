package com.pfa.backend.repository;

import com.pfa.backend.model.FileEntity;
import com.pfa.backend.model.FileStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface FileRepository extends JpaRepository<FileEntity, Long> {
    List<FileEntity> findByStatus(FileStatus status);
}
