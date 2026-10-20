package com.persiangulfwiki.core.subject.repository;

import com.persiangulfwiki.core.subject.entity.SubjectKind;
import com.persiangulfwiki.core.subject.repository.SubjectFacetFilter.Condition;
import com.persiangulfwiki.core.subject.repository.SubjectFacetFilter.Has360TourCondition;
import com.persiangulfwiki.core.subject.repository.SubjectFacetFilter.OptionCondition;
import com.persiangulfwiki.core.subject.repository.SubjectFacetFilter.ReferenceCondition;
import com.persiangulfwiki.core.subject.repository.SubjectFacetFilter.TrueCondition;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import lombok.RequiredArgsConstructor;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

// Builds the facet listing's SQL from a SubjectFacetFilter. Only fixed SQL and generated parameter
// names are ever concatenated; every value from the request, kind names included, is bound.
@RequiredArgsConstructor
class SubjectFacetRepositoryImpl implements SubjectFacetRepository {

    private static final String PREFERRED_FACT = """
            exists (select 1 from subject_facts f
                    where f.subject_id = s.id and f.attribute_id = :%s and f.rank = 'PREFERRED' and %s)""";

    // The shared, transaction-bound proxy Spring Data registers, so these queries join the
    // caller's transaction.
    private final EntityManager entityManager;

    @Override
    public List<UUID> findMatchingIds(SubjectFacetFilter filter, long offset, int limit) {
        Map<String, Object> params = new HashMap<>();
        String sql = "select s.id from subjects s where " + where(filter, params)
                + " order by s.created_at desc, s.id";
        Query query = nativeQuery(sql, params);
        query.setFirstResult(Math.toIntExact(offset));
        query.setMaxResults(limit);
        return rows(query).stream().map(UUID.class::cast).toList();
    }

    @Override
    public long countMatching(SubjectFacetFilter filter) {
        Map<String, Object> params = new HashMap<>();
        Query query = nativeQuery("select count(*) from subjects s where " + where(filter, params), params);
        return ((Number) query.getSingleResult()).longValue();
    }

    @Override
    public Map<UUID, Long> countByOption(UUID attributeId, SubjectFacetFilter filter) {
        return countByFactColumn("value_option_id", attributeId, filter);
    }

    @Override
    public Map<UUID, Long> countByReferencedSubject(UUID attributeId, SubjectFacetFilter filter) {
        return countByFactColumn("value_ref_subject_id", attributeId, filter);
    }

    @Override
    public Map<SubjectKind, Long> countByKind(SubjectFacetFilter filter) {
        Map<String, Object> params = new HashMap<>();
        Query query = nativeQuery("select s.kind, count(*) from subjects s where " + where(filter, params)
                + " group by s.kind", params);
        Map<SubjectKind, Long> counts = new HashMap<>();
        for (Object row : rows(query)) {
            Object[] columns = (Object[]) row;
            counts.put(SubjectKind.valueOf((String) columns[0]), ((Number) columns[1]).longValue());
        }
        return counts;
    }

    // `column` is one of two constants above, never request input. A multi-valued field holding
    // several of the options counts its subject once under each.
    private Map<UUID, Long> countByFactColumn(String column, UUID attributeId, SubjectFacetFilter filter) {
        Map<String, Object> params = new HashMap<>();
        params.put("facetAttribute", attributeId);
        String sql = "select f." + column + ", count(distinct s.id) from subjects s"
                + " join subject_facts f on f.subject_id = s.id and f.attribute_id = :facetAttribute"
                + " and f.rank = 'PREFERRED' and f." + column + " is not null"
                + " where " + where(filter, params)
                + " group by f." + column;
        Map<UUID, Long> counts = new HashMap<>();
        for (Object row : rows(nativeQuery(sql, params))) {
            Object[] columns = (Object[]) row;
            counts.put((UUID) columns[0], ((Number) columns[1]).longValue());
        }
        return counts;
    }

    // Deleted subjects (soft-deleted or merged away) are never listed or counted.
    private static String where(SubjectFacetFilter filter, Map<String, Object> params) {
        StringBuilder where = new StringBuilder("s.deleted_at is null");
        if (!filter.kinds().isEmpty()) {
            where.append(" and s.kind in (:kinds)");
            params.put("kinds", filter.kinds().stream().map(SubjectKind::name).toList());
        }
        int index = 0;
        for (Condition condition : filter.conditions()) {
            String attribute = "attribute" + index;
            String values = "values" + index;
            where.append(" and ");
            switch (condition) {
                case OptionCondition option -> {
                    params.put(attribute, option.attributeId());
                    params.put(values, option.optionIds());
                    where.append(PREFERRED_FACT.formatted(attribute, "f.value_option_id in (:" + values + ")"));
                }
                case ReferenceCondition reference -> {
                    params.put(attribute, reference.attributeId());
                    params.put(values, reference.subjectIds());
                    where.append(PREFERRED_FACT.formatted(attribute, "f.value_ref_subject_id in (:" + values + ")"));
                }
                case TrueCondition isTrue -> {
                    params.put(attribute, isTrue.attributeId());
                    where.append(PREFERRED_FACT.formatted(attribute, "f.value_bool"));
                }
                case Has360TourCondition _ -> where.append(HAS_360_TOUR_OF_S);
            }
            index++;
        }
        return where.toString();
    }

    private Query nativeQuery(String sql, Map<String, Object> params) {
        Query query = entityManager.createNativeQuery(sql);
        params.forEach(query::setParameter);
        return query;
    }

    // The persistence API types native results as a raw List.
    @SuppressWarnings("unchecked")
    private static List<Object> rows(Query query) {
        return query.getResultList();
    }
}
