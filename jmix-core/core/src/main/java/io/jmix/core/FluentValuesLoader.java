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

package io.jmix.core;

import io.jmix.core.entity.KeyValueEntity;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import javax.persistence.LockModeType;
import javax.persistence.TemporalType;
import java.io.Serializable;
import java.util.*;

@Component("core_FluentValuesLoader")
@Scope(BeanDefinition.SCOPE_PROTOTYPE)
public class FluentValuesLoader extends AbstractFluentValueLoader {

    private List<String> properties = new ArrayList<>();

    public FluentValuesLoader(String queryString) {
        super(queryString);
    }

    protected ValueLoadContext createLoadContext() {
        ValueLoadContext loadContext = super.createLoadContext();
        loadContext.setProperties(properties);
        return loadContext;
    }

    /**
     * Loads a list of entities.
     */
    public List<KeyValueEntity> list() {
        ValueLoadContext loadContext = createLoadContext();
        return dataManager.loadValues(loadContext);
    }

    /**
     * Loads a single instance and wraps it in Optional.
     */
    public Optional<KeyValueEntity> optional() {
        ValueLoadContext loadContext = createLoadContext();
        loadContext.getQuery().setMaxResults(1);
        List<KeyValueEntity> list = dataManager.loadValues(loadContext);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    /**
     * Loads a single instance.
     *
     * @throws IllegalStateException if nothing was loaded
     */
    public KeyValueEntity one() {
        ValueLoadContext loadContext = createLoadContext();
        loadContext.getQuery().setMaxResults(1);
        List<KeyValueEntity> list = dataManager.loadValues(loadContext);
        if (!list.isEmpty())
            return list.get(0);
        else
            throw new IllegalStateException("No results");
    }

    /**
     * Adds a key of a returned key-value pair. The sequence of adding properties must conform to the sequence of
     * result fields in the query "select" clause.
     * <p>For example, if the query is <code>select e.id, e.name from sample$Customer</code>
     * and you executed <code>property("customerId").property("customerName")</code>, the returned KeyValueEntity
     * will contain customer identifiers in "customerId" property and names in "customerName" property.
     */
    public FluentValuesLoader property(String name) {
        properties.add(name);
        return this;
    }

    /**
     * The same as invoking {@link #property(String)} multiple times.
     */
    public FluentValuesLoader properties(List<String> properties) {
        this.properties.clear();
        this.properties.addAll(properties);
        return this;
    }

    /**
     * The same as invoking {@link #property(String)} multiple times.
     */
    public FluentValuesLoader properties(String... properties) {
        return properties(Arrays.asList(properties));
    }

    @Override
    public FluentValuesLoader store(String store) {
        super.store(store);
        return this;
    }

    @Override
    public FluentValuesLoader hint(String hintName, Serializable value) {
        super.hint(hintName, value);
        return this;
    }

    @Override
    public FluentValuesLoader hints(Map<String, Serializable> hints) {
        super.hints(hints);
        return this;
    }

    @Override
    public FluentValuesLoader parameter(String name, Object value) {
        super.parameter(name, value);
        return this;
    }

    @Override
    public FluentValuesLoader parameter(String name, Date value, TemporalType temporalType) {
        super.parameter(name, value, temporalType);
        return this;
    }

    @Override
    public FluentValuesLoader parameter(String name, Object value, boolean implicitConversion) {
        super.parameter(name, value, implicitConversion);
        return this;
    }

    @Override
    public FluentValuesLoader setParameters(Map<String, Object> parameters) {
        super.setParameters(parameters);
        return this;
    }

    @Override
    public FluentValuesLoader firstResult(int firstResult) {
        super.firstResult(firstResult);
        return this;
    }

    @Override
    public FluentValuesLoader maxResults(int maxResults) {
        super.maxResults(maxResults);
        return this;
    }

    @Override
    public FluentValuesLoader lockMode(LockModeType lockMode) {
        super.lockMode(lockMode);
        return this;
    }
}
