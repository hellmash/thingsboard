/**
 * Copyright © 2016-2017 The Thingsboard Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.thingsboard.server.dao.relation;

import com.google.common.base.Function;
import com.google.common.util.concurrent.AsyncFunction;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.thingsboard.server.common.data.id.EntityId;
import org.thingsboard.server.common.data.relation.EntityRelation;
import org.thingsboard.server.dao.exception.DataValidationException;

import javax.annotation.Nullable;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Created by ashvayka on 28.04.17.
 */
@Service
@Slf4j
public class BaseRelationService implements RelationService {

    @Autowired
    private RelationDao relationDao;

    @Override
    public ListenableFuture<Boolean> checkRelation(EntityId from, EntityId to, String relationType) {
        log.trace("Executing checkRelation [{}][{}][{}]", from, to, relationType);
        validate(from, to, relationType);
        return relationDao.checkRelation(from, to, relationType);
    }

    @Override
    public ListenableFuture<Boolean> saveRelation(EntityRelation relation) {
        log.trace("Executing saveRelation [{}]", relation);
        validate(relation);
        return relationDao.saveRelation(relation);
    }

    @Override
    public ListenableFuture<Boolean> deleteRelation(EntityRelation relation) {
        log.trace("Executing deleteRelation [{}]", relation);
        validate(relation);
        return relationDao.deleteRelation(relation);
    }

    @Override
    public ListenableFuture<Boolean> deleteRelation(EntityId from, EntityId to, String relationType) {
        log.trace("Executing deleteRelation [{}][{}][{}]", from, to, relationType);
        validate(from, to, relationType);
        return relationDao.deleteRelation(from, to, relationType);
    }

    @Override
    public ListenableFuture<Boolean> deleteEntityRelations(EntityId entity) {
        log.trace("Executing deleteEntityRelations [{}]", entity);
        validate(entity);
        ListenableFuture<List<EntityRelation>> inboundRelations = relationDao.findAllByTo(entity);
        ListenableFuture<List<Boolean>> inboundDeletions = Futures.transform(inboundRelations, new AsyncFunction<List<EntityRelation>, List<Boolean>>() {
            @Override
            public ListenableFuture<List<Boolean>> apply(List<EntityRelation> relations) throws Exception {
                List<ListenableFuture<Boolean>> results = new ArrayList<>();
                for (EntityRelation relation : relations) {
                    results.add(relationDao.deleteRelation(relation));
                }
                return Futures.allAsList(results);
            }
        });

        ListenableFuture<Boolean> inboundFuture = Futures.transform(inboundDeletions, getListToBooleanFunction());

        ListenableFuture<Boolean> outboundFuture = relationDao.deleteOutboundRelations(entity);

        return Futures.transform(Futures.allAsList(Arrays.asList(inboundFuture, outboundFuture)), getListToBooleanFunction());
    }

    @Override
    public ListenableFuture<List<EntityRelation>> findByFrom(EntityId from) {
        log.trace("Executing findByFrom [{}]", from);
        validate(from);
        return relationDao.findAllByFrom(from);
    }

    @Override
    public ListenableFuture<List<EntityRelation>> findByFromAndType(EntityId from, String relationType) {
        log.trace("Executing findByFromAndType [{}][{}]", from, relationType);
        validate(from);
        validateType(relationType);
        return relationDao.findAllByFromAndType(from, relationType);
    }

    @Override
    public ListenableFuture<List<EntityRelation>> findByTo(EntityId to) {
        log.trace("Executing findByTo [{}]", to);
        validate(to);
        return relationDao.findAllByTo(to);
    }

    @Override
    public ListenableFuture<List<EntityRelation>> findByToAndType(EntityId to, String relationType) {
        log.trace("Executing findByToAndType [{}][{}]", to, relationType);
        validate(to);
        validateType(relationType);
        return relationDao.findAllByToAndType(to, relationType);
    }

    @Override
    public ListenableFuture<List<EntityRelation>> findByQuery(EntityRelationsQuery query) {
        log.trace("Executing findByQuery [{}][{}]", query);
        RelationsSearchParameters params = query.getParameters();
        final List<EntityTypeFilter> filters = query.getFilters();
        if (filters == null || filters.isEmpty()) {
            log.warn("Failed to query relations. Filters are not set [{}]", query);
            throw new RuntimeException("Filters are not set!");
        }

        int maxLvl = params.getMaxLevel() > 0 ? params.getMaxLevel() : Integer.MAX_VALUE;

        try {
            ListenableFuture<Set<EntityRelation>> relationSet = findRelationsRecursively(params.getEntityId(), params.getDirection(), maxLvl, new ConcurrentHashMap<>());
            return Futures.transform(relationSet, (Function<Set<EntityRelation>, List<EntityRelation>>) input -> {
                List<EntityRelation> relations = new ArrayList<>();
                for (EntityRelation relation : input) {
                    for (EntityTypeFilter filter : filters) {
                        if (match(filter, relation, params.getDirection())) {
                            relations.add(relation);
                            break;
                        }
                    }
                }
                return relations;
            });
        } catch (Exception e) {
            log.warn("Failed to query relations: [{}]", query, e);
            throw new RuntimeException(e);
        }
    }

    protected void validate(EntityRelation relation) {
        if (relation == null) {
            throw new DataValidationException("Relation type should be specified!");
        }
        validate(relation.getFrom(), relation.getTo(), relation.getType());
    }

    protected void validate(EntityId from, EntityId to, String type) {
        validateType(type);
        if (from == null) {
            throw new DataValidationException("Relation should contain from entity!");
        }
        if (to == null) {
            throw new DataValidationException("Relation should contain to entity!");
        }
    }

    private void validateType(String type) {
        if (StringUtils.isEmpty(type)) {
            throw new DataValidationException("Relation type should be specified!");
        }
    }

    protected void validate(EntityId entity) {
        if (entity == null) {
            throw new DataValidationException("Entity should be specified!");
        }
    }

    private Function<List<Boolean>, Boolean> getListToBooleanFunction() {
        return new Function<List<Boolean>, Boolean>() {
            @Nullable
            @Override
            public Boolean apply(@Nullable List<Boolean> results) {
                for (Boolean result : results) {
                    if (result == null || !result) {
                        return false;
                    }
                }
                return true;
            }
        };
    }

    private boolean match(EntityTypeFilter filter, EntityRelation relation, EntitySearchDirection direction) {
        if (StringUtils.isEmpty(filter.getRelationType()) || filter.getRelationType().equals(relation.getType())) {
            if (filter.getEntityTypes() == null || filter.getEntityTypes().isEmpty()) {
                return true;
            } else {
                EntityId entityId = direction == EntitySearchDirection.FROM ? relation.getTo() : relation.getFrom();
                return filter.getEntityTypes().contains(entityId.getEntityType());
            }
        } else {
            return false;
        }
    }

    private ListenableFuture<Set<EntityRelation>> findRelationsRecursively(final EntityId rootId, final EntitySearchDirection direction, int lvl, final ConcurrentHashMap<EntityId, Boolean> uniqueMap) throws Exception {
        if (lvl == 0) {
            return Futures.immediateFuture(Collections.emptySet());
        }
        lvl--;
        //TODO: try to remove this blocking operation
        Set<EntityRelation> children = new HashSet<>(findRelations(rootId, direction).get());
        Set<EntityId> childrenIds = new HashSet<>();
        for (EntityRelation childRelation : children) {
            log.info("Found Relation: {}", childRelation);
            EntityId childId;
            if (direction == EntitySearchDirection.FROM) {
                childId = childRelation.getTo();
            } else {
                childId = childRelation.getFrom();
            }
            if (uniqueMap.putIfAbsent(childId, Boolean.TRUE) == null) {
                log.info("Adding Relation: {}", childId);
                if (childrenIds.add(childId)) {
                    log.info("Added Relation: {}", childId);
                }
            }
        }
        List<ListenableFuture<Set<EntityRelation>>> futures = new ArrayList<>();
        for (EntityId entityId : childrenIds) {
            futures.add(findRelationsRecursively(entityId, direction, lvl, uniqueMap));
        }
        //TODO: try to remove this blocking operation
        List<Set<EntityRelation>> relations = Futures.successfulAsList(futures).get();
        relations.forEach(r -> r.forEach(d -> children.add(d)));
        return Futures.immediateFuture(children);
    }

    private ListenableFuture<List<EntityRelation>> findRelations(final EntityId rootId, final EntitySearchDirection direction) {
        ListenableFuture<List<EntityRelation>> relations;
        if (direction == EntitySearchDirection.FROM) {
            relations = findByFrom(rootId);
        } else {
            relations = findByTo(rootId);
        }
        return relations;
    }

}
