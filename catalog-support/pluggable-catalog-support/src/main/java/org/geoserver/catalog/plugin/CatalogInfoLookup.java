/* (c) 2017 Open Source Geospatial Foundation - all rights reserved
 * This code is licensed under the GPL 2.0 license, available at the root
 * application directory.
 */
package org.geoserver.catalog.plugin;

import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.geoserver.catalog.Catalog;
import org.geoserver.catalog.CatalogInfo;
import org.geoserver.catalog.DataStoreInfo;
import org.geoserver.catalog.LayerGroupInfo;
import org.geoserver.catalog.LayerInfo;
import org.geoserver.catalog.MapInfo;
import org.geoserver.catalog.NamespaceInfo;
import org.geoserver.catalog.ResourceInfo;
import org.geoserver.catalog.StoreInfo;
import org.geoserver.catalog.StyleInfo;
import org.geoserver.catalog.WorkspaceInfo;
import org.geoserver.catalog.impl.LayerInfoImpl;
import org.geoserver.ows.util.OwsUtils;
import org.geotools.feature.NameImpl;
import org.geotools.util.logging.Logging;
import org.opengis.feature.type.Name;
import org.opengis.filter.Filter;

/**
 * A support index for {@link DefaultCatalogFacade}, can perform fast lookups of {@link CatalogInfo}
 * objects by id or by "name", where the name is defined by a a user provided mapping function.
 *
 * <p>The lookups by predicate have been tested and optimized for performance, in particular the
 * current for loops turned out to be significantly faster than building and returning streams
 *
 * @param <T>
 */
class CatalogInfoLookup<T extends CatalogInfo> implements CatalogInfoRepository<T> {
    static final Logger LOGGER = Logging.getLogger(CatalogInfoLookup.class);

    /**
     * Name mapper for {@link MapInfo}, uses simple name mapping on {@link MapInfo#getName()} as it
     * doesn't have a namespace component
     */
    static final Function<MapInfo, Name> MAP_NAME_MAPPER = m -> new NameImpl(m.getName());

    /**
     * The name uses the workspace id as it does not need to be updated when the workspace is
     * renamed
     */
    static final Function<StoreInfo, Name> STORE_NAME_MAPPER =
            s -> new NameImpl(s.getWorkspace().getId(), s.getName());

    /**
     * The name uses the namspace id as it does not need to be updated when the namespace is renamed
     */
    static final Function<ResourceInfo, Name> RESOURCE_NAME_MAPPER =
            r -> new NameImpl(r.getNamespace().getId(), r.getName());

    /** Like LayerInfo, actually delegates to the resource logic */
    static final Function<LayerInfo, Name> LAYER_NAME_MAPPER =
            l -> RESOURCE_NAME_MAPPER.apply(l.getResource());

    /**
     * The name uses the workspace id as it does not need to be updated when the workspace is
     * renamed
     */
    static final Function<LayerGroupInfo, Name> LAYERGROUP_NAME_MAPPER =
            lg ->
                    new NameImpl(
                            lg.getWorkspace() != null ? lg.getWorkspace().getId() : null,
                            lg.getName());

    static final Function<NamespaceInfo, Name> NAMESPACE_NAME_MAPPER =
            n -> new NameImpl(n.getPrefix());

    static final Function<WorkspaceInfo, Name> WORKSPACE_NAME_MAPPER =
            w -> new NameImpl(w.getName());

    static final Function<StyleInfo, Name> STYLE_NAME_MAPPER =
            s ->
                    new NameImpl(
                            s.getWorkspace() != null ? s.getWorkspace().getId() : null,
                            s.getName());

    protected ConcurrentMap<Class<T>, ConcurrentMap<String, T>> idMultiMap =
            new ConcurrentHashMap<>();
    protected ConcurrentMap<Class<T>, ConcurrentMap<Name, T>> nameMultiMap =
            new ConcurrentHashMap<>();
    protected ConcurrentMap<Class<T>, ConcurrentMap<String, Name>> idToMameMultiMap =
            new ConcurrentHashMap<>();

    Function<T, Name> nameMapper;

    static final <T> Predicate<T> alwaysTrue() {
        return x -> true;
    }

    public CatalogInfoLookup(Function<T, Name> nameMapper) {
        super();
        this.nameMapper = nameMapper;
    }

    <K, V> ConcurrentMap<K, V> getMapForValue(
            ConcurrentMap<Class<T>, ConcurrentMap<K, V>> maps, T value) {
        @SuppressWarnings("unchecked")
        Class<T> vc = (Class<T>) value.getClass();
        return getMapForType(maps, vc);
    }

    @SuppressWarnings("unchecked")
    protected <K, V> ConcurrentMap<K, V> getMapForType(
            ConcurrentMap<Class<T>, ConcurrentMap<K, V>> maps, Class vc) {
        return maps.computeIfAbsent(vc, k -> new ConcurrentSkipListMap<K, V>());
    }

    private static void checkNotAProxy(CatalogInfo value) {
        if (Proxy.isProxyClass(value.getClass())) {
            throw new IllegalArgumentException(
                    "Proxy values shall not be passed to CatalogInfoLookup");
        }
    }

    @Override
    public void add(T value) {
        checkNotAProxy(value);
        Map<String, T> idMap = getMapForValue(idMultiMap, value);
        Map<Name, T> nameMap = getMapForValue(nameMultiMap, value);
        Map<String, Name> idToName = getMapForValue(idToMameMultiMap, value);

        synchronized (idMap) {
            Name name = nameMapper.apply(value);
            nameMap.put(name, value);
            idToName.put(value.getId(), name);
            idMap.put(value.getId(), value);
        }
    }

    @Override
    public List<T> findAll() {
        List<T> result = new ArrayList<>();
        for (Map<String, T> v : idMultiMap.values()) {
            result.addAll(v.values());
        }

        return result;
    }

    @Override
    public void remove(T value) {
        checkNotAProxy(value);
        Map<String, T> idMap = getMapForValue(idMultiMap, value);
        synchronized (idMap) {
            T removed = idMap.remove(value.getId());
            if (removed != null) {
                Name name = getMapForValue(idToMameMultiMap, value).remove(value.getId());
                getMapForValue(nameMultiMap, value).remove(name);
            }
        }
    }

    /** Updates the value in the name map. */
    @Override
    public void update(T value) {
        checkNotAProxy(value);
        Map<String, T> idMap = getMapForValue(idMultiMap, value);
        synchronized (idMap) {
            CatalogInfo oldValue = idMap.get(value.getId());
            if (oldValue == null) {
                throw new NoSuchElementException(
                        value.getClass().getSimpleName()
                                + " with id "
                                + value.getId()
                                + " does not exist");
            }
            ConcurrentMap<String, Name> idToName = getMapForValue(idToMameMultiMap, value);
            Name oldName = idToName.get(value.getId());
            Name newName = nameMapper.apply(value);
            if (!Objects.equals(oldName, newName)) {
                Map<Name, T> nameMap = getMapForValue(nameMultiMap, value);
                nameMap.remove(oldName);
                nameMap.put(newName, value);
                idToName.put(value.getId(), newName);
            }
        }
    }

    @Override
    public void dispose() {
        clear();
    }

    protected void clear() {
        idMultiMap.clear();
        nameMultiMap.clear();
        idToMameMultiMap.clear();
    }

    @Override
    public <U extends T> List<U> findAll(Filter filter) {
        return list(null, toPredicate(filter));
    }

    @Override
    public <U extends T> List<U> findAll(Filter filter, Class<U> infoType) {
        return list(infoType, toPredicate(filter));
    }

    protected <V> Predicate<V> toPredicate(Filter filter) {
        if (filter == null || filter == Filter.INCLUDE) {
            return CatalogInfoLookup.alwaysTrue();
        }
        return o -> filter.evaluate(o);
    }

    /**
     * Looks up objects by class and matching predicate.
     *
     * <p>This method is significantly faster than creating a stream and the applying the predicate
     * on it. Just using this approach instead of the stream makes the overall startup of GeoServer
     * with 20k layers go down from 50s to 44s (which is a lot, considering there is a lot of other
     * things going on)
     */
    @SuppressWarnings("unchecked")
    <U extends CatalogInfo> List<U> list(Class<U> clazz, Predicate<U> predicate) {
        ArrayList<U> result = new ArrayList<U>();
        if (clazz == null) {
            clazz = (Class<U>) CatalogInfo.class;
        }
        for (Class<T> key : nameMultiMap.keySet()) {
            if (clazz.isAssignableFrom(key)) {
                Map<Name, T> valueMap = nameMultiMap.get(key);
                if (valueMap != null) {
                    for (T v : valueMap.values()) {
                        final U u = clazz.cast(v);
                        if (predicate.test(u)) {
                            result.add(u);
                        }
                    }
                }
            }
        }

        return result;
    }

    /** Looks up a CatalogInfo by class and identifier */
    @Override
    public <U extends T> U findById(String id, Class<U> clazz) {
        for (Class<T> key : idMultiMap.keySet()) {
            if (clazz.isAssignableFrom(key)) {
                Map<String, T> valueMap = idMultiMap.get(key);
                if (valueMap != null) {
                    T t = valueMap.get(id);
                    if (t != null) {
                        return clazz.cast(t);
                    }
                }
            }
        }

        return null;
    }

    /** Looks up a CatalogInfo by class and name */
    @Override
    public <U extends T> U findByName(Name name, Class<U> clazz) {
        for (Class<T> key : nameMultiMap.keySet()) {
            if (clazz.isAssignableFrom(key)) {
                Map<Name, T> valueMap = nameMultiMap.get(key);
                if (valueMap != null) {
                    T t = valueMap.get(name);
                    if (t != null) {
                        return clazz.cast(t);
                    }
                }
            }
        }

        return null;
    }

    /**
     * Looks up objects by class and matching predicate.
     *
     * <p>This method is significantly faster than creating a stream and the applying the predicate
     * on it. Just using this approach instead of the stream makes the overall startup of GeoServer
     * with 20k layers go down from 50s to 44s (which is a lot, considering there is a lot of other
     * things going on)
     */
    <U extends CatalogInfo> U findFirst(Class<U> clazz, Predicate<U> predicate) {
        for (Class<T> key : nameMultiMap.keySet()) {
            if (clazz.isAssignableFrom(key)) {
                Map<Name, T> valueMap = nameMultiMap.get(key);
                if (valueMap != null) {
                    for (T v : valueMap.values()) {
                        final U u = clazz.cast(v);
                        if (predicate.test(u)) {
                            return u;
                        }
                    }
                }
            }
        }

        return null;
    }

    @Override
    public void syncTo(CatalogInfoRepository<T> target) {
        if (target instanceof CatalogInfoLookup) {
            CatalogInfoLookup<T> other = (CatalogInfoLookup<T>) target;
            other.clear();
            other.idMultiMap.putAll(this.idMultiMap);
            other.nameMultiMap.putAll(this.nameMultiMap);
            other.idToMameMultiMap.putAll(this.idToMameMultiMap);
        } else {
            this.idMultiMap.values().forEach(typeMap -> typeMap.values().forEach(target::add));
        }
    }

    /** Sets the specified catalog into all CatalogInfo objects contained in this lookup */
    @Override
    public void setCatalog(Catalog catalog) {
        for (Map<Name, T> valueMap : nameMultiMap.values()) {
            if (valueMap != null) {
                for (T v : valueMap.values()) {
                    if (v instanceof CatalogInfo) {
                        Method setter = OwsUtils.setter(v.getClass(), "catalog", Catalog.class);
                        if (setter != null) {
                            try {
                                setter.invoke(v, catalog);
                            } catch (Exception e) {
                                LOGGER.log(
                                        Level.FINE,
                                        "Failed to switch CatalogInfo to new catalog impl",
                                        e);
                            }
                        }
                    }
                }
            }
        }
    }

    static class NamespaceInfoLookup extends CatalogInfoLookup<NamespaceInfo>
            implements NamespaceRepository {
        private NamespaceInfo defaultNamespace;

        public NamespaceInfoLookup() {
            super(NAMESPACE_NAME_MAPPER);
        }

        public @Override void setDefaultNamespace(NamespaceInfo namespace) {
            this.defaultNamespace =
                    namespace == null ? null : findById(namespace.getId(), NamespaceInfo.class);
        }

        public @Override NamespaceInfo getDefaultNamespace() {
            return defaultNamespace;
        }

        public @Override NamespaceInfo findOneByURI(String uri) {
            return findFirst(NamespaceInfo.class, ns -> uri.equals(ns.getURI()));
        }

        public @Override List<NamespaceInfo> findAllByURI(String uri) {
            return list(NamespaceInfo.class, ns -> ns.getURI().equals(uri));
        }
    }

    static class WorkspaceInfoLookup extends CatalogInfoLookup<WorkspaceInfo>
            implements WorkspaceRepository {

        private WorkspaceInfo defaultWorkspace;

        public WorkspaceInfoLookup() {
            super(WORKSPACE_NAME_MAPPER);
        }

        public @Override void setDefaultWorkspace(WorkspaceInfo workspace) {
            this.defaultWorkspace =
                    workspace == null ? null : findById(workspace.getId(), WorkspaceInfo.class);
        }

        public @Override WorkspaceInfo getDefaultWorkspace() {
            return defaultWorkspace;
        }
    }

    static class StoreInfoLookup extends CatalogInfoLookup<StoreInfo> implements StoreRepository {
        /** The default store keyed by workspace id */
        protected ConcurrentMap<String, DataStoreInfo> defaultStores = new ConcurrentHashMap<>();

        public StoreInfoLookup() {
            super(STORE_NAME_MAPPER);
        }

        public @Override void setDefaultDataStore(WorkspaceInfo workspace, DataStoreInfo store) {
            java.util.Objects.requireNonNull(workspace);
            String wsId = workspace.getId();
            final DataStoreInfo localStore;
            if (store == null) {
                localStore = null;
            } else {
                localStore = super.findById(store.getId(), DataStoreInfo.class);
            }
            defaultStores.compute(wsId, (ws, oldDefaultStore) -> localStore);
        }

        public @Override DataStoreInfo getDefaultDataStore(WorkspaceInfo workspace) {
            return defaultStores.get(workspace.getId());
        }

        public @Override List<DataStoreInfo> getDefaultDataStores() {
            return new ArrayList<>(defaultStores.values());
        }

        public @Override void dispose() {
            super.dispose();
            defaultStores.clear();
        }

        public @Override <T extends StoreInfo> T findOneByName(String name, Class<T> clazz) {
            return findFirst(clazz, s -> name.equals(s.getName()));
        }

        public @Override <T extends StoreInfo> List<T> findAllByWorkspace(
                WorkspaceInfo workspace, Class<T> clazz) {
            return list(clazz, s -> workspace.getId().equals(s.getWorkspace().getId()));
        }

        public @Override <T extends StoreInfo> List<T> findAllByType(Class<T> clazz) {
            return list(clazz, CatalogInfoLookup.alwaysTrue());
        }
    }

    static class LayerGroupInfoLookup extends CatalogInfoLookup<LayerGroupInfo>
            implements LayerGroupRepository {
        public LayerGroupInfoLookup() {
            super(LAYERGROUP_NAME_MAPPER);
        }

        public @Override LayerGroupInfo findOneByName(String name) {
            return findFirst(LayerGroupInfo.class, lg -> name.equals(lg.getName()));
        }

        public @Override List<LayerGroupInfo> findAllByWorkspaceIsNull() {
            return list(LayerGroupInfo.class, lg -> lg.getWorkspace() == null);
        }

        public @Override List<LayerGroupInfo> findAllByWorkspace(WorkspaceInfo workspace) {
            return list(
                    LayerGroupInfo.class,
                    lg ->
                            lg.getWorkspace() != null
                                    && lg.getWorkspace().getId().equals(workspace.getId()));
        }
    }

    static class MapInfoLookup extends CatalogInfoLookup<MapInfo> implements MapRepository {
        public MapInfoLookup() {
            super(MAP_NAME_MAPPER);
        }
    }

    /**
     * CatalogInfoLookup specialization for {@code ResourceInfo} that encapsulates the logic to
     * update the name lookup for the linked {@code LayerInfo} given that {@code LayerInfo.getName()
     * == LayerInfo.getResource().getName()}
     */
    static final class ResourceInfoLookup extends CatalogInfoLookup<ResourceInfo>
            implements ResourceRepository {
        private final LayerInfoLookup layers;

        public ResourceInfoLookup(LayerInfoLookup layers) {
            super(RESOURCE_NAME_MAPPER);
            this.layers = layers;
        }

        public @Override void update(ResourceInfo value) {
            Name oldName = getMapForValue(idToMameMultiMap, value).get(value.getId());
            Name newName = nameMapper.apply(value);
            super.update(value);
            if (!newName.equals(oldName)) {
                layers.updateName(oldName, newName);
            }
        }

        public @Override <T extends ResourceInfo> T findOneByName(String name, Class<T> clazz) {
            return findFirst(clazz, r -> name.equals(r.getName()));
        }

        public @Override <T extends ResourceInfo> List<T> findAllByType(Class<T> clazz) {
            return list(clazz, CatalogInfoLookup.alwaysTrue());
        }

        public @Override <T extends ResourceInfo> List<T> findAllByNamespace(
                NamespaceInfo ns, Class<T> clazz) {
            return list(clazz, r -> ns.equals(r.getNamespace()));
        }

        public @Override <T extends ResourceInfo> T findByStoreAndName(
                StoreInfo store, String name, Class<T> clazz) {
            return findFirst(
                    clazz,
                    r -> name.equals(r.getName()) && store.getId().equals(r.getStore().getId()));
        }

        @Override
        public <T extends ResourceInfo> List<T> findAllByStore(StoreInfo store, Class<T> clazz) {
            return list(clazz, r -> store.equals(r.getStore()));
        }
    }

    static final class LayerInfoLookup extends CatalogInfoLookup<LayerInfo>
            implements LayerRepository {

        public LayerInfoLookup() {
            super(LAYER_NAME_MAPPER);
        }

        void updateName(Name oldName, Name newName) {
            ConcurrentMap<Name, LayerInfo> nameLookup =
                    getMapForType(nameMultiMap, LayerInfoImpl.class);
            LayerInfo layer = nameLookup.remove(oldName);
            if (layer != null) {
                nameLookup.put(newName, layer);
                getMapForType(idToMameMultiMap, LayerInfoImpl.class).put(layer.getId(), newName);
            }
        }

        /** Override to remove by name instead of by id */
        @Override
        public void remove(LayerInfo value) {
            checkNotAProxy(value);
            ConcurrentMap<Name, LayerInfo> nameMap = getMapForValue(nameMultiMap, value);
            synchronized (nameMap) {
                Name name = nameMapper.apply(value);
                LayerInfo removed = nameMap.remove(name);
                if (removed != null) {
                    getMapForValue(idMultiMap, value).remove(value.getId());
                    getMapForValue(idToMameMultiMap, value).remove(value.getId());
                }
            }
        }

        public @Override LayerInfo findOneByName(String name) {
            return findFirst(LayerInfo.class, li -> name.equals(li.getName()));
        }

        public @Override List<LayerInfo> findAllByDefaultStyleOrStyles(StyleInfo style) {
            return list(
                    LayerInfo.class,
                    li -> style.equals(li.getDefaultStyle()) || li.getStyles().contains(style));
        }

        public @Override List<LayerInfo> findAllByResource(ResourceInfo resource) {
            // in the current setup we cannot have multiple layers associated to the same
            // resource, as they would all share the same name (the one of the resource) so
            // a direct lookup becomes possible
            Name name = RESOURCE_NAME_MAPPER.apply(resource);
            LayerInfo layer = findByName(name, LayerInfo.class);
            return layer == null ? emptyList() : singletonList(layer);
        }
    }

    static class StyleInfoLookup extends CatalogInfoLookup<StyleInfo> implements StyleRepository {
        public StyleInfoLookup() {
            super(STYLE_NAME_MAPPER);
        }

        public @Override StyleInfo findOneByName(String name) {
            return findFirst(StyleInfo.class, s -> name.equals(s.getName()));
        }

        public @Override List<StyleInfo> findAllByNullWorkspace() {
            return list(StyleInfo.class, s -> s.getWorkspace() == null);
        }

        public @Override List<StyleInfo> findAllByWorkspace(WorkspaceInfo ws) {
            return list(
                    StyleInfo.class,
                    s -> s.getWorkspace() != null && s.getWorkspace().getId().equals(ws.getId()));
        }
    }
}
