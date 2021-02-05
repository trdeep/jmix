/*
 * Copyright 2020 Haulmont.
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

package io.jmix.eclipselink.impl.lazyloading;

import io.jmix.core.*;
import io.jmix.core.constraint.InMemoryConstraint;
import io.jmix.core.datastore.DataStoreAfterEntityLoadEvent;
import io.jmix.core.datastore.DataStoreEventListener;
import io.jmix.core.entity.EntityValues;
import io.jmix.core.metamodel.model.MetaClass;
import io.jmix.core.metamodel.model.MetaProperty;
import io.jmix.core.metamodel.model.Range;
import org.eclipse.persistence.indirection.ValueHolderInterface;
import org.eclipse.persistence.internal.indirection.QueryBasedValueHolder;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.persistence.Basic;
import javax.persistence.FetchType;
import java.io.Serializable;
import java.lang.reflect.AnnotatedElement;
import java.util.*;
import java.util.stream.Collectors;

import static io.jmix.eclipselink.impl.lazyloading.ValueHoldersSupport.*;

@Component("data_JpaLazyLoadingInterceptor")
public class JpaLazyLoadingListener implements DataStoreEventListener {
    @Autowired
    protected Metadata metadata;
    @Autowired
    protected MetadataTools metadataTools;
    @Autowired
    protected EntityStates entityStates;
    @Autowired
    protected DataManager dataManager;
    @Autowired
    protected BeanFactory beanFactory;
    @Autowired
    protected FetchPlanRepository fetchPlanRepository;
    @Autowired
    protected ExtendedEntities extendedEntities;

    @Override
    public void afterEntityLoad(DataStoreAfterEntityLoadEvent event) {
        LoadContext<?> context = event.getLoadContext();

        MetaClass metaClass = extendedEntities.getEffectiveMetaClass(context.getEntityMetaClass());

        FetchPlan fetchPlan = context.getFetchPlan();
        if (fetchPlan == null) {
            fetchPlan = fetchPlanRepository.getFetchPlan(metaClass, FetchPlan.LOCAL);
        }
        for (Object entity : event.getResultEntities()) {
            processValueHolders(entity, context, fetchPlan);
        }
    }

    public void processValueHolders(Object entity, LoadContext<?> loadContext, FetchPlan fetchPlan) {
        Map<Object, Set<FetchPlan>> collectedFetchPlans = new HashMap<>();

        if (fetchPlan != null) {
            collectFetchPlans(entity, fetchPlan, collectedFetchPlans);
        }

        Map<String, Object> hints = loadContext.getHints();
        Map<String, Serializable> serializableHints = new HashMap<>();

        for (Map.Entry<String, Object> entry : hints.entrySet()) {
            if (entry.getValue() instanceof Serializable) {
                serializableHints.put(entry.getKey(), (Serializable) entry.getValue());
            }
        }

        LoadOptions loadOptions = LoadOptions.with()
                .setSoftDeletion(loadContext.isSoftDeletion())
                .setAccessConstraints(loadContext.getAccessConstraints().stream()
                        .filter(c -> c instanceof InMemoryConstraint)
                        .collect(Collectors.toList()))
                .setHints(serializableHints);

        for (Map.Entry<Object, Set<FetchPlan>> entry : collectedFetchPlans.entrySet()) {
            MetaClass metaClass = metadata.getClass(entry.getKey().getClass());
            for (MetaProperty property : metaClass.getProperties()) {
                if (property.getRange().isClass() && !metadataTools.isEmbedded(property) &&
                        !isPropertyContainedInFetchPlans(property, entry.getValue())) {
                    if (!entityStates.isLoaded(entry.getKey(), property.getName())) {
                        if (property.getRange().getCardinality().isMany()) {
                            processCollectionValueHolder(entry.getKey(), property, loadOptions);
                        } else if (property.getRange().getCardinality() == Range.Cardinality.ONE_TO_ONE) {
                            processOneToOneValueHolder(entry.getKey(), property, loadOptions);
                        } else if (property.getRange().getCardinality() == Range.Cardinality.MANY_TO_ONE) {
                            processManyToOneValueHolder(entry.getKey(), property, loadOptions);
                        }
                    }
                }
            }
        }
    }

    protected void processCollectionValueHolder(Object owner, MetaProperty property, LoadOptions loadOptions) {
        Object valueHolder = getCollectionValueHolder(owner, property.getName());
        if (valueHolder != null && !(valueHolder instanceof AbstractValueHolder)) {
            AbstractValueHolder wrappedValueHolder =
                    new CollectionValuePropertyHolder(beanFactory, (ValueHolderInterface) valueHolder, owner, property);

            wrappedValueHolder.setLoadOptions(LoadOptions.with(loadOptions));

            setCollectionValueHolder(owner, property.getName(), wrappedValueHolder);
        }
    }

    protected void processOneToOneValueHolder(Object owner, MetaProperty property, LoadOptions loadOptions) {
        Object originalValueHolder = getSingleValueHolder(owner, property.getName());

        if (originalValueHolder != null && !(originalValueHolder instanceof AbstractValueHolder)) {
            AbstractValueHolder wrappedValueHolder = null;

            if (metadataTools.isOwningSide(property)) {
                QueryBasedValueHolder queryBasedValueHolder = unwrapToQueryBasedValueHolder(originalValueHolder);
                if (queryBasedValueHolder != null) {
                    Object entityId = getEntityIdFromValueHolder(queryBasedValueHolder);

                    wrappedValueHolder =
                            new SingleValueOwningPropertyHolder(beanFactory, (ValueHolderInterface) originalValueHolder,
                                    owner, property, entityId);

                    wrappedValueHolder.setLoadOptions(LoadOptions.with(loadOptions));
                }
            } else {
                //noinspection ConstantConditions
                wrappedValueHolder = new SingleValueMappedByPropertyHolder(beanFactory, (ValueHolderInterface) originalValueHolder,
                        owner, property);

                wrappedValueHolder.setLoadOptions(LoadOptions.with(loadOptions));
            }

            setSingleValueHolder(owner, property.getName(), wrappedValueHolder);
        }
    }

    protected void processManyToOneValueHolder(Object owner, MetaProperty property, LoadOptions loadOptions) {
        Object originalValueHolder = getSingleValueHolder(owner, property.getName());

        if (originalValueHolder != null && !(originalValueHolder instanceof AbstractValueHolder)) {
            QueryBasedValueHolder queryBasedValueHolder = unwrapToQueryBasedValueHolder(originalValueHolder);
            if (queryBasedValueHolder != null) {
                Object entityId = getEntityIdFromValueHolder(queryBasedValueHolder);

                AbstractValueHolder wrappedValueHolder =
                        new SingleValueOwningPropertyHolder(beanFactory, (ValueHolderInterface) originalValueHolder,
                                owner, property, entityId);

                wrappedValueHolder.setLoadOptions(LoadOptions.with(loadOptions));

                setSingleValueHolder(owner, property.getName(), wrappedValueHolder);
            }
        }
    }

    protected void collectFetchPlans(Object instance, FetchPlan fetchPlan, Map<Object, Set<FetchPlan>> collectedFetchPlans) {
        Set<FetchPlan> fetchPlans = collectedFetchPlans.get(instance);
        if (fetchPlans == null) {
            fetchPlans = new HashSet<>();
            collectedFetchPlans.put(instance, fetchPlans);
        } else if (fetchPlans.contains(fetchPlan)) {
            return;
        }
        fetchPlans.add(fetchPlan);

        MetaClass metaClass = metadata.getClass(instance.getClass());
        for (FetchPlanProperty property : fetchPlan.getProperties()) {
            MetaProperty metaProperty = metaClass.getProperty(property.getName());
            if (!metaProperty.getRange().isClass() && !isLazyFetchedLocalAttribute(metaProperty)
                    || !metadataTools.isJpa(metaProperty)) {
                continue;
            }

            if (metadataTools.isEmbedded(metaProperty) &&
                    !entityStates.isLoaded(instance, property.getName())) {
                continue;
            }

            Object value = EntityValues.getValue(instance, property.getName());
            FetchPlan propertyFetchPlan = property.getFetchPlan();
            if (value != null && propertyFetchPlan != null) {
                if (value instanceof Collection) {
                    //noinspection unchecked
                    for (Object item : new ArrayList<>((Collection<Object>) value)) {
                        if (item instanceof Entity) {
                            collectFetchPlans(item, propertyFetchPlan, collectedFetchPlans);
                        }
                    }
                } else if (value instanceof Entity) {
                    collectFetchPlans(value, propertyFetchPlan, collectedFetchPlans);
                }
            }
        }
    }

    protected boolean isPropertyContainedInFetchPlans(MetaProperty metaProperty, Set<FetchPlan> fetchPlans) {
        boolean contains = false;
        for (FetchPlan fetchPlan : fetchPlans) {
            if (fetchPlan.containsProperty(metaProperty.getName())) {
                contains = true;
                break;
            }
        }
        return contains;
    }

    protected boolean isLazyFetchedLocalAttribute(MetaProperty metaProperty) {
        AnnotatedElement annotatedElement = metaProperty.getAnnotatedElement();
        Basic annotation = annotatedElement.getAnnotation(Basic.class);
        return annotation != null && annotation.fetch() == FetchType.LAZY;
    }
}
