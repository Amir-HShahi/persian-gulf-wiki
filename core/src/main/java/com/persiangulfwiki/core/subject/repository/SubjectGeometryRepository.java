package com.persiangulfwiki.core.subject.repository;

import com.persiangulfwiki.core.subject.entity.SubjectGeometry;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface SubjectGeometryRepository extends JpaRepository<SubjectGeometry, UUID> {

    List<SubjectGeometry> findBySubjectId(UUID subjectId);
}
