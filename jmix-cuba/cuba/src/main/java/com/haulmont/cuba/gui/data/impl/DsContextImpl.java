/*
 * Copyright 2019 Haulmont.
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
package com.haulmont.cuba.gui.data.impl;

import com.haulmont.cuba.core.global.AppBeans;
import com.haulmont.cuba.core.global.CommitContext;
import com.haulmont.cuba.core.global.Metadata;
import com.haulmont.cuba.core.global.PersistenceHelper;
import com.haulmont.cuba.core.global.filter.ParameterInfo;
import com.haulmont.cuba.gui.data.*;
import com.haulmont.cuba.gui.data.impl.compatibility.DsContextCommitListenerWrapper;
import com.haulmont.cuba.gui.screen.compatibility.LegacyFrame;
import io.jmix.core.Entity;
import io.jmix.core.FetchPlan;
import io.jmix.core.entity.EntityValues;
import io.jmix.core.metamodel.model.MetaClass;
import io.jmix.core.metamodel.model.MetaProperty;
import io.jmix.ui.component.Component;
import io.jmix.ui.component.Frame;
import io.jmix.ui.component.FrameContext;
import io.jmix.ui.screen.FrameOwner;

import java.util.*;

import static io.jmix.core.common.util.Preconditions.checkNotNullArgument;

public class DsContextImpl implements DsContextImplementation {

    protected FrameContext windowContext;
    protected DataSupplier dataservice;

    protected DsContext parent;
    protected List<DsContext> children = new ArrayList<>();

    protected Map<String, Datasource> datasourceMap = new HashMap<>();
    protected Map<Datasource, Datasource> dependencies = new HashMap<>();
    protected Map<String, String> aliasesMap = new HashMap<>();

    protected Metadata metadata;

    protected List<LazyTask> lazyTasks = new ArrayList<>();

    protected List<BeforeCommitListener> beforeCommitListeners; // lazily initialized list
    protected List<AfterCommitListener> afterCommitListeners; // lazily initialized list

    private boolean discardCommitted;

    public DsContextImpl(DataSupplier dataservice) {
        this.dataservice = dataservice;
        this.metadata = AppBeans.get(Metadata.class);
    }

    @Override
    public void addLazyTask(LazyTask lazyTask) {
        if (!lazyTasks.contains(lazyTask)) {
            lazyTasks.add(lazyTask);
        }
    }

    @Override
    public void executeLazyTasks() {
        for (LazyTask lazyTask : lazyTasks) {
            lazyTask.execute(this);
        }
    }

    @Override
    public void resumeSuspended() {
        List<CollectionDatasource.Suspendable> list = new ArrayList<>();

        addDsContextToResume(this, list);

        for (CollectionDatasource.Suspendable suspendable : list) {
            suspendable.setSuspended(false);
        }
    }

    protected void addDsContextToResume(DsContext dsContext, List<CollectionDatasource.Suspendable> list) {
        for (Datasource datasource : dsContext.getAll()) {
            if (datasource instanceof CollectionDatasource.Suspendable) {
                addDatasourceToResume(list, datasource);
            }
        }
        for (DsContext childContext : dsContext.getChildren()) {
            addDsContextToResume(childContext, list);
        }
    }

    protected void addDatasourceToResume(List<CollectionDatasource.Suspendable> list, Datasource datasource) {
        if (list.contains(datasource)) {
            return;
        }

        if (dependencies.containsKey(datasource)) {
            Datasource master = dependencies.get(datasource);
            addDatasourceToResume(list, master);
        }
        if (datasource instanceof CollectionDatasource.Suspendable
                && ((CollectionDatasource.Suspendable) datasource).isSuspended()) {
            list.add((CollectionDatasource.Suspendable) datasource);
        }
    }

    @Override
    public FrameContext getFrameContext() {
        return windowContext;
    }

    @Override
    public void setFrameContext(FrameContext windowContext) {
        this.windowContext = windowContext;
    }

    @Override
    public boolean commit() {
        Map<DataSupplier, Collection<Datasource<Entity>>> commitData = collectCommitData();

        boolean committed = false;

        if (!commitData.isEmpty()) {
            DataSupplier dataservice = getDataSupplier();
            Set<DataSupplier> suppliers = commitData.keySet();

            if (suppliers.size() == 1 &&
                    Objects.equals(suppliers.iterator().next(), dataservice)) {
                CommitContext context = createCommitContext(dataservice, commitData);

                fireBeforeCommit(context);

                Set<Entity> committedEntities = dataservice.commit(context);

                fireAfterCommit(context, committedEntities);

                notifyAllDsCommited(dataservice, committedEntities);

                committed = true;
            } else {
                throw new UnsupportedOperationException();
            }
        }

        for (DsContext childDsContext : children) {
            boolean c = commitToParent(childDsContext.getAll());
            committed = committed || c;
        }
        boolean c = commitToParent(datasourceMap.values());
        committed = committed || c;

        return committed;
    }

    @Override
    public boolean isDiscardCommitted() {
        return discardCommitted;
    }

    @Override
    public void setDiscardCommitted(boolean discardCommitted) {
        this.discardCommitted = discardCommitted;
    }

    protected boolean commitToParent(Collection<Datasource> datasources) {
        List<Datasource> list = new ArrayList<>();
        for (Datasource datasource : datasources) {
            if (Datasource.CommitMode.PARENT.equals(datasource.getCommitMode())
                    && (datasource.isModified() || !((DatasourceImplementation) datasource).getItemsToCreate().isEmpty())) {
                list.add(datasource);
            }
        }

        List<Datasource> sortedList = new DsTree(list).toDsList();
        for (Datasource datasource : sortedList) {
            datasource.commit();
        }
        return !list.isEmpty();
    }

    protected void notifyAllDsCommited(DataSupplier dataservice, Set<Entity> committedEntities) {
        // Notify all datasources in context
        List<Datasource> datasources = new ArrayList<>();
        for (DsContext childDsContext : children) {
            for (Datasource ds : childDsContext.getAll()) {
                if (Objects.equals(ds.getDataSupplier(), dataservice)
                        && ds.getCommitMode() == Datasource.CommitMode.DATASTORE) {
                    datasources.add(ds);
                }
            }
        }
        for (Datasource ds : datasourceMap.values()) {
            if (Objects.equals(ds.getDataSupplier(), dataservice)
                    && ds.getCommitMode() == Datasource.CommitMode.DATASTORE) {
                datasources.add(ds);
            }
        }

        List<Datasource> sortedList = new DsTree(datasources).toDsList();
        for (Datasource datasource : sortedList) {
            ((DatasourceImplementation) datasource).committed(committedEntities);
        }
    }

    @Override
    public void fireBeforeCommit(CommitContext context) {
        if (beforeCommitListeners != null) {
            for (BeforeCommitListener listener : new ArrayList<>(beforeCommitListeners)) {
                listener.beforeCommit(context);
            }
        }
        for (DsContext childDsContext : children) {
            ((DsContextImplementation) childDsContext).fireBeforeCommit(context);
        }
    }

    @Override
    public void fireAfterCommit(CommitContext context, Set<Entity> committedEntities) {
        for (DsContext childDsContext : children) {
            ((DsContextImplementation) childDsContext).fireAfterCommit(context, committedEntities);
        }

        if (afterCommitListeners != null) {
            for (AfterCommitListener listener : afterCommitListeners) {
                listener.afterCommit(context, committedEntities);
            }
        }
    }

    protected CommitContext createCommitContext(DataSupplier dataservice,
                                                Map<DataSupplier, Collection<Datasource<Entity>>> commitData) {
        CommitContext context = new CommitContext();
        context.setDiscardCommitted(discardCommitted);

        for (Datasource<Entity> datasource : commitData.get(dataservice)) {
            if (datasource instanceof DatasourceImplementation) {
                DatasourceImplementation<Entity> implementation = (DatasourceImplementation) datasource;

                boolean listenersEnabled = implementation.enableListeners(false);
                try {
                    for (Entity entity : implementation.getItemsToCreate()) {
                        addToContext(entity, datasource, context.getEntitiesToSave(), context.getFetchPlans());
                    }
                    for (Entity entity : implementation.getItemsToUpdate()) {
                        addToContext(entity, datasource, context.getEntitiesToSave(), context.getFetchPlans());
                    }
                    for (Entity entity : implementation.getItemsToDelete()) {
                        addToContext(entity, datasource, context.getEntitiesToRemove(), context.getFetchPlans());
                    }
                } finally {
                    implementation.enableListeners(listenersEnabled);
                }
            }
        }
        repairReferences(context);

        return context;
    }


    protected void repairReferences(CommitContext context) {
        for (Entity entity : context.getCommitInstances()) {
            for (Entity otherEntity : context.getCommitInstances()) {
                if (!entity.equals(otherEntity)) {
                    repairReferences(otherEntity, entity);
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    protected void repairReferences(Entity entity, Entity contextEntity) {
        MetaClass metaClass = metadata.getClass(entity);
        MetaClass contextEntityMetaClass = metadata.getClass(contextEntity);

        for (MetaProperty property : metaClass.getProperties()) {
            if (!property.getRange().isClass()
                    || !property.getRange().asClass().equals(contextEntityMetaClass)
                    || !PersistenceHelper.isLoaded(entity, property.getName()))
                continue;

            Object value = EntityValues.getValue(entity, property.getName());
            if (value != null) {
                if (property.getRange().getCardinality().isMany()) {
                    Collection collection = (Collection) value;
                    for (Object item : new ArrayList(collection)) {
                        if (contextEntity.equals(item) && contextEntity != item) {
                            if (collection instanceof List) {
                                List list = (List) collection;
                                list.set(list.indexOf(item), contextEntity);
                            } else {
                                collection.remove(item);
                                collection.add(contextEntity);
                            }
                        }
                    }
                } else {
                    if (contextEntity.equals(value) && contextEntity != value) {
                        EntityValues.setValue(entity, property.getName(), contextEntity);
                    }
                }
            }
        }
    }

    protected void addToContext(Entity entity, Datasource<Entity> datasource,
                                Collection<Object> entities, Map<Object, FetchPlan> views) {
        if (datasource instanceof NestedDatasource) {
            replaceMasterCopies(entity, ((NestedDatasource) datasource));
        }

        entities.add(entity);
        views.put(entity, datasource.getView());
    }

    // Replace the reference to master entity with actual entity containing in the master datasource,
    // because in case of nested property datasources there may be references to cloned master entities.
    protected void replaceMasterCopies(Entity entity, NestedDatasource datasource) {
        Datasource masterDs = datasource.getMaster();
        MetaProperty metaProperty = datasource.getProperty();
        if (masterDs != null && metaProperty != null) {
            MetaProperty inverseProp = metaProperty.getInverse();
            if (inverseProp != null && !inverseProp.getRange().getCardinality().isMany()) {
                MetaClass metaClass = metadata.getExtendedEntities().getEffectiveMetaClass(inverseProp.getDomain());
                if (metaClass.equals(datasource.getMetaClass())
                        && (PersistenceHelper.isLoaded(entity, inverseProp.getName())
                        && EntityValues.getValue(entity, inverseProp.getName()) != null)) // replace master only if it's already set
                {
                    Object masterItem = null;
                    if (masterDs instanceof CollectionDatasource) {
                        Entity value = EntityValues.getValue(entity, inverseProp.getName());
                        if (value != null) {
                            Object id = EntityValues.getId(value);
                            //noinspection unchecked
                            masterItem = ((CollectionDatasource) masterDs).getItem(id);
                        }
                    } else {
                        masterItem = masterDs.getItem();
                    }
                    if (masterItem != null) {
                        // CAUTION need to rework this mechanism in case of two or more nested collection datasources
                        EntityValues.setValue(entity, inverseProp.getName(), masterItem, false);
                    }
                }
            }
        }
    }

    protected Map<DataSupplier, Collection<Datasource<Entity>>> collectCommitData() {
        Collection<Datasource> datasources = new ArrayList<>();

        for (DsContext childDsContext : children) {
            datasources.addAll(childDsContext.getAll());
        }
        datasources.addAll(datasourceMap.values());

        Map<DataSupplier, Collection<Datasource<Entity>>> commitDatasources = new HashMap<>();

        for (Datasource datasource : datasources) {
            if (Datasource.CommitMode.DATASTORE == datasource.getCommitMode() &&
                    datasource.isAllowCommit() &&
                    (datasource.isModified() || !((DatasourceImplementation) datasource).getItemsToCreate().isEmpty())) {

                DataSupplier dataservice = datasource.getDataSupplier();
                Collection<Datasource<Entity>> collection = commitDatasources.get(dataservice);
                if (collection == null) {
                    collection = new ArrayList<>();
                    commitDatasources.put(dataservice, collection);
                }
                collection.add(datasource);
            }
        }
        return commitDatasources;
    }

    @Override
    public void registerDependency(Datasource datasource, Datasource dependFrom, String propertyName) {
        Datasource ds = dependencies.get(datasource);
        if (ds != null) {
            if (ds.equals(dependFrom)) {
                return;
            } else {
                throw new UnsupportedOperationException("Datasource couldn't depend from two different sources");
            }
        }

        //noinspection unchecked
        dependFrom.addItemChangeListener(e -> {
            if (datasource.getState() == Datasource.State.VALID) {
                datasource.refresh();
            }
        });

        if (dependFrom instanceof CollectionDatasource) {
            //noinspection unchecked
            ((CollectionDatasource) dependFrom).addCollectionChangeListener(e -> {
                if (e.getOperation() == CollectionDatasource.Operation.REFRESH) {
                    datasource.refresh();
                }
            });
        }

        //noinspection unchecked
        dependFrom.addItemPropertyChangeListener(e -> {
            if (propertyName != null) {
                // parameter can use a property path with more than one element, e.g. :ds$driversDs.status.id,
                // but we should listen the first level property
                String listeningProperty = propertyName.split("\\.")[0];
                if (listeningProperty.equals(e.getProperty())) {
                    final Entity item = Datasource.State.VALID.equals(dependFrom.getState()) ? dependFrom.getItem() : null;
                    if (Objects.equals(item, e.getItem())) {
                        datasource.refresh();
                    }
                }
            }
        });

        dependencies.put(datasource, dependFrom);
    }

    @Override
    public void addListener(CommitListener listener) {
        DsContextCommitListenerWrapper wrapper = new DsContextCommitListenerWrapper(listener);

        addBeforeCommitListener(wrapper);
        addAfterCommitListener(wrapper);
    }

    @Override
    public void removeListener(CommitListener listener) {
        DsContextCommitListenerWrapper wrapper = new DsContextCommitListenerWrapper(listener);

        removeBeforeCommitListener(wrapper);
        removeAfterCommitListener(wrapper);
    }

    @Override
    public void addBeforeCommitListener(BeforeCommitListener listener) {
        if (beforeCommitListeners == null) {
            beforeCommitListeners = new ArrayList<>();
        }
        if (!beforeCommitListeners.contains(listener)) {
            beforeCommitListeners.add(listener);
        }
    }

    @Override
    public void removeBeforeCommitListener(BeforeCommitListener listener) {
        if (beforeCommitListeners != null) {
            beforeCommitListeners.remove(listener);
        }
    }

    @Override
    public void addAfterCommitListener(AfterCommitListener listener) {
        if (afterCommitListeners == null) {
            afterCommitListeners = new ArrayList<>();
        }
        if (!afterCommitListeners.contains(listener)) {
            afterCommitListeners.add(listener);
        }
    }

    @Override
    public void removeAfterCommitListener(AfterCommitListener listener) {
        if (afterCommitListeners != null) {
            afterCommitListeners.remove(listener);
        }
    }

    @Override
    public DataSupplier getDataSupplier() {
        return dataservice;
    }

    @Override
    public Datasource get(String id) {
        checkNotNullArgument(id, "Null datasource ID");

        id = aliasesMap.getOrDefault(id, id);

        Datasource ds = null;
        if (!id.contains(".")) {
            ds = datasourceMap.get(id);
            if (ds == null && parent != null) {
                ds = parent.get(id);
            }
        } else {
            if (windowContext != null) {
                String nestedFramePath = id.substring(0, id.indexOf("."));
                Component nestedFrame = getFrameContext().getFrame().getComponent(nestedFramePath);

                if (nestedFrame instanceof Frame) {
                    String nestedDsId = id.substring(id.indexOf(".") + 1);
                    FrameOwner frameOwner = ((Frame) nestedFrame).getFrameOwner();
                    if (frameOwner instanceof LegacyFrame) {
                        ds = ((LegacyFrame) frameOwner).getDsContext().get(nestedDsId);
                    }
                }
            }
        }
        return ds;
    }

    @Override
    public Datasource getNN(String name) {
        Datasource datasource = get(name);
        if (datasource == null) {
            throw new IllegalArgumentException(String.format("Datasource '%s' is not found", name));
        }
        return datasource;
    }

    @Override
    public Collection<Datasource> getAll() {
        return datasourceMap.values();
    }

    public void addAlias(String aliasDatasourceId, String originalDatasourceId) {
        aliasesMap.put(aliasDatasourceId, originalDatasourceId);
    }

    @Override
    public boolean isModified() {
        for (Datasource datasource : datasourceMap.values()) {
            if (datasource.isModified()) {
                return true;
            }
        }
        for (DsContext childDsContext : children) {
            for (Datasource datasource : childDsContext.getAll()) {
                if (datasource.isModified()) {
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public void refresh() {
        final Collection<Datasource> datasources = datasourceMap.values();
        for (Datasource datasource : datasources) {
            if (dependencies.containsKey(datasource)) {
                continue;
            }
            datasource.refresh();
        }
    }

    @Override
    public void register(Datasource datasource) {
        datasourceMap.put(datasource.getId(), datasource);
    }

    @Override
    public void unregister(Datasource datasource) {
        datasourceMap.remove(datasource.getId());
    }

    @Override
    public void registerListener(ParameterInfo item, Datasource datasource) {
    }

    @Override
    public DsContext getParent() {
        return parent;
    }

    @Override
    public void setParent(DsContext parent) {
        this.parent = parent;
        if (!parent.getChildren().contains(this)) {
            parent.getChildren().add(this);
        }
    }

    @Override
    public List<DsContext> getChildren() {
        return children;
    }
}
