/*
 * (c) 2014 Open Source Geospatial Foundation - all rights reserved (c) 2001 - 2013 OpenPlans This
 * code is licensed under the GPL 2.0 license, available at the root application directory.
 */
package org.geoserver.catalog.plugin;

import com.google.common.collect.Iterables;
import com.google.common.collect.Ordering;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.rmi.server.UID;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.Nullable;
import org.geoserver.catalog.Catalog;
import org.geoserver.catalog.CatalogFacade;
import org.geoserver.catalog.CatalogInfo;
import org.geoserver.catalog.DataStoreInfo;
import org.geoserver.catalog.LayerGroupInfo;
import org.geoserver.catalog.LayerInfo;
import org.geoserver.catalog.LockingCatalogFacade;
import org.geoserver.catalog.MapInfo;
import org.geoserver.catalog.NamespaceInfo;
import org.geoserver.catalog.PublishedInfo;
import org.geoserver.catalog.ResourceInfo;
import org.geoserver.catalog.StoreInfo;
import org.geoserver.catalog.StyleInfo;
import org.geoserver.catalog.WorkspaceInfo;
import org.geoserver.catalog.impl.CatalogImpl;
import org.geoserver.catalog.impl.LayerGroupInfoImpl;
import org.geoserver.catalog.impl.LayerInfoImpl;
import org.geoserver.catalog.impl.ModificationProxy;
import org.geoserver.catalog.impl.ProxyUtils;
import org.geoserver.catalog.impl.ResolvingProxy;
import org.geoserver.catalog.impl.ResourceInfoImpl;
import org.geoserver.catalog.impl.StoreInfoImpl;
import org.geoserver.catalog.plugin.CatalogInfoRepository.LayerGroupRepository;
import org.geoserver.catalog.plugin.CatalogInfoRepository.LayerRepository;
import org.geoserver.catalog.plugin.CatalogInfoRepository.MapRepository;
import org.geoserver.catalog.plugin.CatalogInfoRepository.NamespaceRepository;
import org.geoserver.catalog.plugin.CatalogInfoRepository.ResourceRepository;
import org.geoserver.catalog.plugin.CatalogInfoRepository.StoreRepository;
import org.geoserver.catalog.plugin.CatalogInfoRepository.StyleRepository;
import org.geoserver.catalog.plugin.CatalogInfoRepository.WorkspaceRepository;
import org.geoserver.catalog.util.CloseableIterator;
import org.geoserver.catalog.util.CloseableIteratorAdapter;
import org.geoserver.ows.util.OwsUtils;
import org.geotools.feature.NameImpl;
import org.geotools.util.logging.Logging;
import org.opengis.feature.type.Name;
import org.opengis.filter.Filter;
import org.opengis.filter.sort.SortBy;
import org.opengis.filter.sort.SortOrder;
import org.springframework.util.Assert;

public abstract class AbstractCatalogFacade implements CatalogFacade {

    private static final Logger LOGGER = Logging.getLogger(AbstractCatalogFacade.class);

    protected NamespaceRepository namespaces;
    protected WorkspaceRepository workspaces;
    protected StoreRepository stores;
    protected ResourceRepository resources;
    protected LayerRepository layers;
    protected LayerGroupRepository layerGroups;
    protected MapRepository maps;
    protected StyleRepository styles;
    protected CatalogImpl catalog;

    public AbstractCatalogFacade(Catalog catalog) {
        setCatalog(catalog);
    }

    public @Override void setCatalog(Catalog catalog) {
        this.catalog = (CatalogImpl) catalog;
        setCatalog(workspaces);
        setCatalog(workspaces);
        setCatalog(namespaces);
        setCatalog(stores);
        setCatalog(resources);
        setCatalog(layers);
        setCatalog(layerGroups);
        setCatalog(styles);
        setCatalog(maps);
    }

    public @Override Catalog getCatalog() {
        return catalog;
    }

    private <R extends CatalogInfoRepository<?>> R setCatalog(R repo) {
        if (repo != null) repo.setCatalog(getCatalog());
        return repo;
    }

    public void setNamespaces(NamespaceRepository namespaces) {
        this.namespaces = setCatalog(namespaces);
    }

    public void setWorkspaces(WorkspaceRepository workspaces) {
        this.workspaces = setCatalog(workspaces);
    }

    public void setStores(StoreRepository stores) {
        this.stores = setCatalog(stores);
    }

    public void setResources(ResourceRepository resources) {
        this.resources = setCatalog(resources);
    }

    public void setLayers(LayerRepository layers) {
        this.layers = setCatalog(layers);
    }

    public void setLayerGroups(LayerGroupRepository layerGroups) {
        this.layerGroups = setCatalog(layerGroups);
    }

    public void setStyles(StyleRepository styles) {
        this.styles = setCatalog(styles);
    }

    public void setMaps(MapRepository maps) {
        this.maps = setCatalog(maps);
    }

    public @Override abstract void resolve();

    protected <I extends CatalogInfo> void doSave(I info, CatalogInfoRepository<I> repository) {
        ModificationProxy h = (ModificationProxy) Proxy.getInvocationHandler(info);
        // figure out what changed
        List<String> propertyNames = h.getPropertyNames();
        List<Object> newValues = h.getNewValues();
        List<Object> oldValues = h.getOldValues();

        beforeSaved(info, propertyNames, oldValues, newValues);
        info = commitProxy(info);
        repository.update(info);
        afterSaved(info, propertyNames, oldValues, newValues);
    }

    //
    // Stores
    //
    public @Override StoreInfo add(StoreInfo store) {
        resolve(store);
        stores.add(store);
        return ModificationProxy.create(store, StoreInfo.class);
    }

    public @Override void remove(StoreInfo store) {
        stores.remove(unwrap(store));
    }

    public @Override void save(StoreInfo store) {
        doSave(store, stores);
    }

    public @Override <T extends StoreInfo> T detach(T store) {
        return store;
    }

    public @Override <T extends StoreInfo> T getStore(String id, Class<T> clazz) {
        return wrapInModificationProxy(stores.findById(id, clazz), clazz);
    }

    public @Override <T extends StoreInfo> T getStoreByName(
            WorkspaceInfo workspace, String name, Class<T> clazz) {

        T result;
        if (workspace == ANY_WORKSPACE) {
            result = stores.findOneByName(name, clazz);
        } else {
            Name qname = new NameImpl((workspace != null) ? workspace.getId() : null, name);
            result = stores.findByName(qname, clazz);
        }

        return wrapInModificationProxy(result, clazz);
    }

    public @Override <T extends StoreInfo> List<T> getStoresByWorkspace(
            WorkspaceInfo workspace, Class<T> clazz) {
        // TODO: support ANY_WORKSPACE?
        WorkspaceInfo ws = workspace;
        if (workspace == null) {
            ws = getDefaultWorkspace();
        }

        List<T> matches = stores.findAllByWorkspace(ws, clazz);
        return wrapInModificationProxy(matches, clazz);
    }

    public @Override <T extends StoreInfo> List<T> getStores(Class<T> clazz) {
        return wrapInModificationProxy(stores.findAllByType(clazz), clazz);
    }

    public @Override DataStoreInfo getDefaultDataStore(WorkspaceInfo workspace) {
        return wrapInModificationProxy(stores.getDefaultDataStore(workspace), DataStoreInfo.class);
    }

    public @Override void setDefaultDataStore(WorkspaceInfo workspace, DataStoreInfo store) {
        DataStoreInfo old = stores.getDefaultDataStore(workspace);
        if (store != null) {
            Objects.requireNonNull(store.getWorkspace());
            Assert.isTrue(
                    workspace.getId().equals(store.getWorkspace().getId()),
                    "Store workspace mismatch");
        }

        // fire modify event before change
        catalog.fireModified(
                catalog,
                Arrays.asList("defaultDataStore"),
                Arrays.asList(old),
                Arrays.asList(store));

        stores.setDefaultDataStore(workspace, store);

        // fire postmodify event after change
        catalog.firePostModified(
                catalog,
                Arrays.asList("defaultDataStore"),
                Arrays.asList(old),
                Arrays.asList(store));
    }

    //
    // Resources
    //
    public @Override ResourceInfo add(ResourceInfo resource) {
        resolve(resource);
        resources.add(resource);
        return ModificationProxy.create(resource, ResourceInfo.class);
    }

    public @Override void remove(ResourceInfo resource) {
        resources.remove(unwrap(resource));
    }

    public @Override void save(ResourceInfo resource) {
        doSave(resource, resources);
    }

    public @Override <T extends ResourceInfo> T detach(T resource) {
        return resource;
    }

    public @Override <T extends ResourceInfo> T getResource(String id, Class<T> clazz) {
        T result = resources.findById(id, clazz);
        return wrapInModificationProxy(result, clazz);
    }

    public @Override <T extends ResourceInfo> T getResourceByName(
            NamespaceInfo namespace, String name, Class<T> clazz) {
        T result;
        if (namespace == ANY_NAMESPACE) {
            result = resources.findOneByName(name, clazz);
        } else {
            Name qname = new NameImpl(namespace != null ? namespace.getId() : null, name);
            result = resources.findByName(qname, clazz);
        }

        return wrapInModificationProxy(result, clazz);
    }

    public @Override <T extends ResourceInfo> List<T> getResources(Class<T> clazz) {
        return wrapInModificationProxy(resources.findAllByType(clazz), clazz);
    }

    public @Override <T extends ResourceInfo> List<T> getResourcesByNamespace(
            NamespaceInfo namespace, Class<T> clazz) {
        // TODO: support ANY_NAMESPACE?
        NamespaceInfo ns = namespace == null ? getDefaultNamespace() : namespace;
        List<T> matches = resources.findAllByNamespace(ns, clazz);
        return wrapInModificationProxy(matches, clazz);
    }

    public @Override <T extends ResourceInfo> T getResourceByStore(
            StoreInfo store, String name, Class<T> clazz) {
        T resource = null;
        NamespaceInfo ns = null;
        if (store.getWorkspace() != null
                && store.getWorkspace().getName() != null
                && (ns = getNamespaceByPrefix(store.getWorkspace().getName())) != null) {
            resource = resources.findByName(new NameImpl(ns.getId(), name), clazz);
            if (resource != null && !(store.equals(resource.getStore()))) {
                return null;
            }
        } else {
            // should not happen, but some broken test code sets up namespaces without equivalent
            // workspaces
            // or stores without workspaces
            resource = resources.findByStoreAndName(store, name, clazz);
        }
        return wrapInModificationProxy(resource, clazz);
    }

    public @Override <T extends ResourceInfo> List<T> getResourcesByStore(
            StoreInfo store, Class<T> clazz) {
        List<T> matches = resources.findAllByStore(store, clazz);
        return wrapInModificationProxy(matches, clazz);
    }

    //
    // Layers
    //
    public @Override LayerInfo add(LayerInfo layer) {
        resolve(layer);
        layers.add(layer);

        return ModificationProxy.create(layer, LayerInfo.class);
    }

    public @Override void remove(LayerInfo layer) {
        layers.remove(unwrap(layer));
    }

    public @Override void save(LayerInfo layer) {
        doSave(layer, layers);
    }

    public @Override LayerInfo detach(LayerInfo layer) {
        return layer;
    }

    public @Override LayerInfo getLayer(String id) {
        LayerInfo li = layers.findById(id, LayerInfo.class);
        return wrapInModificationProxy(li, LayerInfo.class);
    }

    public @Override LayerInfo getLayerByName(String name) {
        LayerInfo result = layers.findOneByName(name);
        return wrapInModificationProxy(result, LayerInfo.class);
    }

    public @Override List<LayerInfo> getLayers(ResourceInfo resource) {
        List<LayerInfo> matches = layers.findAllByResource(resource);
        return wrapInModificationProxy(matches, LayerInfo.class);
    }

    public @Override List<LayerInfo> getLayers(StyleInfo style) {
        List<LayerInfo> matches = layers.findAllByDefaultStyleOrStyles(style);
        return wrapInModificationProxy(matches, LayerInfo.class);
    }

    public @Override List<LayerInfo> getLayers() {
        return wrapInModificationProxy(layers.findAll(), LayerInfo.class);
    }

    //
    // Maps
    //
    public @Override MapInfo add(MapInfo map) {
        resolve(map);
        maps.add(map);
        return ModificationProxy.create(map, MapInfo.class);
    }

    public @Override void remove(MapInfo map) {
        maps.remove(unwrap(map));
    }

    public @Override void save(MapInfo map) {
        doSave(map, maps);
    }

    public @Override MapInfo detach(MapInfo map) {
        return map;
    }

    public @Override MapInfo getMap(String id) {
        return wrapInModificationProxy(maps.findById(id, MapInfo.class), MapInfo.class);
    }

    public @Override MapInfo getMapByName(String name) {
        return wrapInModificationProxy(
                maps.findByName(new NameImpl(null, name), MapInfo.class), MapInfo.class);
    }

    public @Override List<MapInfo> getMaps() {
        return wrapInModificationProxy(maps.findAll(), MapInfo.class);
    }

    //
    // Layer groups
    //
    public @Override LayerGroupInfo add(LayerGroupInfo layerGroup) {
        resolve(layerGroup);
        layerGroups.add(layerGroup);
        return ModificationProxy.create(layerGroup, LayerGroupInfo.class);
    }

    public @Override void remove(LayerGroupInfo layerGroup) {
        layerGroups.remove(unwrap(layerGroup));
    }

    public @Override void save(LayerGroupInfo layerGroup) {
        doSave(layerGroup, layerGroups);
    }

    public @Override LayerGroupInfo detach(LayerGroupInfo layerGroup) {
        return layerGroup;
    }

    public @Override List<LayerGroupInfo> getLayerGroups() {
        return wrapInModificationProxy(layerGroups.findAll(), LayerGroupInfo.class);
    }

    public @Override List<LayerGroupInfo> getLayerGroupsByWorkspace(WorkspaceInfo workspace) {
        // TODO: support ANY_WORKSPACE?

        WorkspaceInfo ws;
        if (workspace == null) {
            ws = getDefaultWorkspace();
        } else {
            ws = workspace;
        }
        List<LayerGroupInfo> matches;
        if (workspace == NO_WORKSPACE) {
            matches = layerGroups.findAllByWorkspaceIsNull();
        } else {
            matches = layerGroups.findAllByWorkspace(ws);
        }
        return wrapInModificationProxy(matches, LayerGroupInfo.class);
    }

    public @Override LayerGroupInfo getLayerGroup(String id) {
        LayerGroupInfo result = layerGroups.findById(id, LayerGroupInfo.class);
        return wrapInModificationProxy(result, LayerGroupInfo.class);
    }

    public @Override LayerGroupInfo getLayerGroupByName(String name) {
        return getLayerGroupByName(NO_WORKSPACE, name);
    }

    public @Override LayerGroupInfo getLayerGroupByName(WorkspaceInfo workspace, String name) {
        LayerGroupInfo match;
        if (workspace == NO_WORKSPACE) {
            match = layerGroups.findByName(new NameImpl(null, name), LayerGroupInfo.class);
        } else if (ANY_WORKSPACE == workspace) {
            match = layerGroups.findOneByName(name);
        } else {
            match =
                    layerGroups.findByName(
                            new NameImpl(workspace.getId(), name), LayerGroupInfo.class);
        }
        return wrapInModificationProxy(match, LayerGroupInfo.class);
    }

    //
    // Namespaces
    //
    public @Override NamespaceInfo add(NamespaceInfo namespace) {
        resolve(namespace);
        NamespaceInfo unwrapped = unwrap(namespace);
        namespaces.add(unwrapped);

        return ModificationProxy.create(unwrapped, NamespaceInfo.class);
    }

    public @Override void remove(NamespaceInfo namespace) {
        if (namespace.equals(getDefaultNamespace())) {
            setDefaultNamespace(null);
        }
        namespaces.remove(unwrap(namespace));
    }

    public @Override void save(NamespaceInfo namespace) {
        doSave(namespace, namespaces);
    }

    public @Override NamespaceInfo detach(NamespaceInfo namespace) {
        return namespace;
    }

    public @Override NamespaceInfo getDefaultNamespace() {
        return wrapInModificationProxy(namespaces.getDefaultNamespace(), NamespaceInfo.class);
    }

    public @Override void setDefaultNamespace(NamespaceInfo defaultNamespace) {
        NamespaceInfo old = getDefaultNamespace();
        // fire modify event before change
        catalog.fireModified(
                catalog,
                Arrays.asList("defaultNamespace"),
                Arrays.asList(old),
                Arrays.asList(defaultNamespace));

        namespaces.setDefaultNamespace(unwrap(defaultNamespace));

        // fire postmodify event after change
        catalog.firePostModified(
                catalog,
                Arrays.asList("defaultNamespace"),
                Arrays.asList(old),
                Arrays.asList(defaultNamespace));
    }

    public @Override NamespaceInfo getNamespace(String id) {
        NamespaceInfo ns = namespaces.findById(id, NamespaceInfo.class);
        return wrapInModificationProxy(ns, NamespaceInfo.class);
    }

    public @Override NamespaceInfo getNamespaceByPrefix(String prefix) {
        NamespaceInfo ns = namespaces.findByName(new NameImpl(prefix), NamespaceInfo.class);
        return wrapInModificationProxy(ns, NamespaceInfo.class);
    }

    public @Override NamespaceInfo getNamespaceByURI(String uri) {
        return wrapInModificationProxy(namespaces.findOneByURI(uri), NamespaceInfo.class);
    }

    public @Override List<NamespaceInfo> getNamespacesByURI(String uri) {
        return wrapInModificationProxy(namespaces.findAllByURI(uri), NamespaceInfo.class);
    }

    public @Override List<NamespaceInfo> getNamespaces() {
        return wrapInModificationProxy(namespaces.findAll(), NamespaceInfo.class);
    }

    //
    // Workspaces
    //
    // Workspace methods
    public @Override WorkspaceInfo add(WorkspaceInfo workspace) {
        resolve(workspace);
        WorkspaceInfo unwrapped = unwrap(workspace);
        workspaces.add(unwrapped);
        return ModificationProxy.create(unwrapped, WorkspaceInfo.class);
    }

    public @Override void remove(WorkspaceInfo workspace) {
        if (workspace.equals(getDefaultWorkspace())) {
            workspaces.setDefaultWorkspace(null);
        }
        workspaces.remove(unwrap(workspace));
    }

    public @Override void save(WorkspaceInfo workspace) {
        doSave(workspace, workspaces);
    }

    public @Override WorkspaceInfo detach(WorkspaceInfo workspace) {
        return workspace;
    }

    public @Override WorkspaceInfo getDefaultWorkspace() {
        return wrapInModificationProxy(workspaces.getDefaultWorkspace(), WorkspaceInfo.class);
    }

    public @Override void setDefaultWorkspace(WorkspaceInfo workspace) {
        WorkspaceInfo old = getDefaultWorkspace();
        // fire modify event before change
        catalog.fireModified(
                catalog,
                Arrays.asList("defaultWorkspace"),
                Arrays.asList(old),
                Arrays.asList(workspace));

        workspaces.setDefaultWorkspace(unwrap(workspace));

        // fire postmodify event after change
        catalog.firePostModified(
                catalog,
                Arrays.asList("defaultWorkspace"),
                Arrays.asList(old),
                Arrays.asList(workspace));
    }

    public @Override List<WorkspaceInfo> getWorkspaces() {
        return wrapInModificationProxy(workspaces.findAll(), WorkspaceInfo.class);
    }

    public @Override WorkspaceInfo getWorkspace(String id) {
        WorkspaceInfo ws = workspaces.findById(id, WorkspaceInfo.class);
        return wrapInModificationProxy(ws, WorkspaceInfo.class);
    }

    public @Override WorkspaceInfo getWorkspaceByName(String name) {
        WorkspaceInfo ws = workspaces.findByName(new NameImpl(name), WorkspaceInfo.class);
        return wrapInModificationProxy(ws, WorkspaceInfo.class);
    }

    //
    // Styles
    //
    public @Override StyleInfo add(StyleInfo style) {
        resolve(style);
        styles.add(style);
        return ModificationProxy.create(style, StyleInfo.class);
    }

    public @Override void remove(StyleInfo style) {
        styles.remove(unwrap(style));
    }

    public @Override void save(StyleInfo style) {
        doSave(style, styles);
    }

    public @Override StyleInfo detach(StyleInfo style) {
        return style;
    }

    public @Override StyleInfo getStyle(String id) {
        StyleInfo match = styles.findById(id, StyleInfo.class);
        return wrapInModificationProxy(match, StyleInfo.class);
    }

    public @Override StyleInfo getStyleByName(String name) {
        StyleInfo match = styles.findByName(new NameImpl(null, name), StyleInfo.class);
        if (match == null) {
            match = styles.findOneByName(name);
        }
        return wrapInModificationProxy(match, StyleInfo.class);
    }

    public @Override StyleInfo getStyleByName(WorkspaceInfo workspace, String name) {
        if (null == workspace) {
            throw new NullPointerException("workspace");
        }
        if (null == name) {
            throw new NullPointerException("name");
        }
        if (workspace == ANY_WORKSPACE) {
            return getStyleByName(name);
        } else {
            Name sn = new NameImpl(workspace == null ? null : workspace.getId(), name);
            StyleInfo match = styles.findByName(sn, StyleInfo.class);
            return wrapInModificationProxy(match, StyleInfo.class);
        }
    }

    public @Override List<StyleInfo> getStyles() {
        return wrapInModificationProxy(styles.findAll(), StyleInfo.class);
    }

    public @Override List<StyleInfo> getStylesByWorkspace(WorkspaceInfo workspace) {
        // TODO: support ANY_WORKSPACE?
        List<StyleInfo> matches;
        if (workspace == NO_WORKSPACE) {
            matches = styles.findAllByNullWorkspace();
        } else {
            WorkspaceInfo ws;
            if (workspace == null) {
                ws = getDefaultWorkspace();
            } else {
                ws = workspace;
            }

            matches = styles.findAllByWorkspace(ws);
        }

        return wrapInModificationProxy(matches, StyleInfo.class);
    }

    public @Override void dispose() {
        dispose(stores);
        dispose(resources);
        dispose(namespaces);
        dispose(workspaces);
        dispose(layers);
        dispose(layerGroups);
        dispose(maps);
        dispose(styles);
    }

    private void dispose(CatalogInfoRepository<?> repository) {
        if (repository != null) repository.dispose();
    }

    public @Override void syncTo(CatalogFacade dao) {
        dao = ProxyUtils.unwrap(dao, LockingCatalogFacade.class);
        if (dao instanceof AbstractCatalogFacade) {
            // do an optimized sync
            AbstractCatalogFacade other = (AbstractCatalogFacade) dao;
            this.workspaces.syncTo(other.workspaces);
            other.workspaces.setDefaultWorkspace(this.workspaces.getDefaultWorkspace());

            this.namespaces.syncTo(other.namespaces);
            other.namespaces.setDefaultNamespace(this.namespaces.getDefaultNamespace());

            this.stores.syncTo(other.stores);
            List<DataStoreInfo> defaultDataStores = this.stores.getDefaultDataStores();
            for (DataStoreInfo defaultDataStore : defaultDataStores) {
                other.stores.setDefaultDataStore(defaultDataStore.getWorkspace(), defaultDataStore);
            }
            this.resources.syncTo(other.resources);
            this.layers.syncTo(other.layers);

            this.layerGroups.syncTo(other.layerGroups);
            this.styles.syncTo(other.styles);
            this.maps.syncTo(other.maps);
            other.setCatalog(catalog);
        } else {
            // do a manual import
            for (WorkspaceInfo ws : workspaces.findAll()) {
                dao.add(ws);
            }
            for (NamespaceInfo ns : namespaces.findAll()) {
                dao.add(ns);
            }
            for (StoreInfo store : stores.findAll()) {
                dao.add(store);
            }
            for (ResourceInfo resource : resources.findAll()) {
                dao.add(resource);
            }
            for (StyleInfo s : styles.findAll()) {
                dao.add(s);
            }
            for (LayerInfo l : layers.findAll()) {
                dao.add(l);
            }
            for (LayerGroupInfo lg : layerGroups.findAll()) {
                dao.add(lg);
            }
            for (MapInfo m : maps.findAll()) {
                dao.add(m);
            }

            dao.setDefaultWorkspace(getDefaultWorkspace());
            dao.setDefaultNamespace(getDefaultNamespace());
            List<DataStoreInfo> defaultDataStores = stores.getDefaultDataStores();
            for (DataStoreInfo dds : defaultDataStores) {
                dao.setDefaultDataStore(dds.getWorkspace(), dds);
            }
        }
    }

    public @Override <T extends CatalogInfo> int count(final Class<T> of, final Filter filter) {
        return Iterables.size(iterable(of, filter, null));
    }

    /**
     * This default implementation supports sorting against properties (could be nested) that are
     * either of a primitive type or implement {@link Comparable}.
     *
     * @param type the type of object to sort
     * @param propertyName the property name of the objects of type {@code type} to sort by
     * @see org.geoserver.catalog.CatalogFacade#canSort(java.lang.Class, java.lang.String)
     */
    public @Override boolean canSort(
            final Class<? extends CatalogInfo> type, final String propertyName) {
        final String[] path = propertyName.split("\\.");
        Class<?> clazz = type;
        for (int i = 0; i < path.length; i++) {
            String property = path[i];
            Method getter;
            try {
                getter = OwsUtils.getter(clazz, property, null);
            } catch (RuntimeException e) {
                return false;
            }
            clazz = getter.getReturnType();
            if (i == path.length - 1) {
                boolean primitive = clazz.isPrimitive();
                boolean comparable = Comparable.class.isAssignableFrom(clazz);
                boolean canSort = primitive || comparable;
                return canSort;
            }
        }
        throw new IllegalStateException("empty property name");
    }

    public @Override <T extends CatalogInfo> CloseableIterator<T> list(
            final Class<T> of,
            final Filter filter,
            @Nullable Integer offset,
            @Nullable Integer count,
            @Nullable SortBy... sortOrder) {

        if (sortOrder != null) {
            for (SortBy so : sortOrder) {
                if (sortOrder != null && !canSort(of, so.getPropertyName().getPropertyName())) {
                    throw new IllegalArgumentException(
                            "Can't sort objects of type "
                                    + of.getName()
                                    + " by "
                                    + so.getPropertyName());
                }
            }
        }

        Iterable<T> iterable = iterable(of, filter, sortOrder);

        if (offset != null && offset.intValue() > 0) {
            iterable = Iterables.skip(iterable, offset.intValue());
        }

        if (count != null && count.intValue() >= 0) {
            iterable = Iterables.limit(iterable, count.intValue());
        }

        Iterator<T> iterator = iterable.iterator();

        return new CloseableIteratorAdapter<T>(iterator);
    }

    @SuppressWarnings("unchecked")
    protected <T extends CatalogInfo> Iterable<T> iterable(
            final Class<T> of, final Filter filter, final SortBy[] sortByList) {
        List<T> all;

        if (NamespaceInfo.class.isAssignableFrom(of)) {
            all = (List<T>) namespaces.findAll(filter);
        } else if (WorkspaceInfo.class.isAssignableFrom(of)) {
            all = (List<T>) workspaces.findAll(filter);
        } else if (StoreInfo.class.isAssignableFrom(of)) {
            all = (List<T>) stores.findAll(filter, (Class<StoreInfo>) of);
        } else if (ResourceInfo.class.isAssignableFrom(of)) {
            all = (List<T>) resources.findAll(filter, (Class<ResourceInfo>) of);
        } else if (LayerInfo.class.isAssignableFrom(of)) {
            all = (List<T>) layers.findAll(filter);
        } else if (LayerGroupInfo.class.isAssignableFrom(of)) {
            all = (List<T>) layerGroups.findAll(filter);
        } else if (PublishedInfo.class.isAssignableFrom(of)) {
            all = new ArrayList<>();
            all.addAll((List<T>) layers.findAll(filter));
            all.addAll((List<T>) layerGroups.findAll(filter));
        } else if (StyleInfo.class.isAssignableFrom(of)) {
            all = (List<T>) styles.findAll(filter);
        } else if (MapInfo.class.isAssignableFrom(of)) {
            all = (List<T>) maps.findAll(filter);
        } else {
            throw new IllegalArgumentException("Unknown type: " + of);
        }

        if (null != sortByList) {
            for (int i = sortByList.length - 1; i >= 0; i--) {
                SortBy sortBy = sortByList[i];
                Ordering<Object> ordering = Ordering.from(comparator(sortBy));
                if (SortOrder.DESCENDING.equals(sortBy.getSortOrder())) {
                    ordering = ordering.reverse();
                }
                all = ordering.sortedCopy(all);
            }
        }

        return wrapInModificationProxy(all, of);
    }

    private Comparator<Object> comparator(final SortBy sortOrder) {
        return new Comparator<Object>() {
            @Override
            public int compare(Object o1, Object o2) {
                Object v1 = OwsUtils.get(o1, sortOrder.getPropertyName().getPropertyName());
                Object v2 = OwsUtils.get(o2, sortOrder.getPropertyName().getPropertyName());
                if (v1 == null) {
                    if (v2 == null) {
                        return 0;
                    } else {
                        return -1;
                    }
                } else if (v2 == null) {
                    return 1;
                }
                @SuppressWarnings({"rawtypes", "unchecked"})
                Comparable<Object> c1 = (Comparable) v1;
                @SuppressWarnings({"rawtypes", "unchecked"})
                Comparable<Object> c2 = (Comparable) v2;
                return c1.compareTo(c2);
            }
        };
    }

    //
    // Utilities
    //
    public static <T> T unwrap(T obj) {
        return ModificationProxy.unwrap(obj);
    }

    protected <T extends CatalogInfo> T wrapInModificationProxy(T ci, Class<T> clazz) {
        return ci == null ? null : ModificationProxy.create(ci, clazz);
    }

    protected <T extends CatalogInfo> List<T> wrapInModificationProxy(
            List<T> proxyList, Class<T> clazz) {
        return ModificationProxy.createList(proxyList, clazz);
    }

    protected void beforeSaved(
            CatalogInfo object, List<String> propertyNames, List<?> oldValues, List<?> newValues) {
        CatalogInfo real = ModificationProxy.unwrap(object);

        // TODO: protect this original object, perhaps with another proxy
        getCatalog().fireModified(real, propertyNames, oldValues, newValues);
    }

    protected <T extends CatalogInfo> T commitProxy(T object) {
        // this object is a proxy
        ModificationProxy h = (ModificationProxy) Proxy.getInvocationHandler(object);

        // get the real object
        @SuppressWarnings("unchecked")
        T real = (T) h.getProxyObject();

        // commit to the original object
        h.commit();

        return real;
    }

    protected void afterSaved(
            CatalogInfo object, List<String> propertyNames, List<?> oldValues, List<?> newValues) {
        CatalogInfo real = ModificationProxy.unwrap(object);

        // fire the post modify event
        getCatalog().firePostModified(real, propertyNames, oldValues, newValues);
    }

    protected void resolve(LayerInfo layer) {
        setId(layer);

        ResourceInfo resource = ResolvingProxy.resolve(getCatalog(), layer.getResource());
        if (resource != null) {
            resource = unwrap(resource);
            layer.setResource(resource);
        }

        StyleInfo style = ResolvingProxy.resolve(getCatalog(), layer.getDefaultStyle());
        if (style != null) {
            style = unwrap(style);
            layer.setDefaultStyle(style);
        }

        LinkedHashSet<StyleInfo> styles = new LinkedHashSet<StyleInfo>();
        for (StyleInfo s : layer.getStyles()) {
            s = ResolvingProxy.resolve(getCatalog(), s);
            s = unwrap(s);
            styles.add(s);
        }
        ((LayerInfoImpl) layer).setStyles(styles);
    }

    protected void resolve(LayerGroupInfo layerGroup) {
        setId(layerGroup);

        LayerGroupInfoImpl lg = (LayerGroupInfoImpl) layerGroup;

        for (int i = 0; i < lg.getLayers().size(); i++) {
            PublishedInfo l = lg.getLayers().get(i);

            if (l != null) {
                PublishedInfo resolved;
                if (l instanceof LayerGroupInfo) {
                    resolved = unwrap(ResolvingProxy.resolve(getCatalog(), (LayerGroupInfo) l));
                    // special case to handle catalog loading, when nested publishibles might not be
                    // loaded.
                    if (resolved == null) {
                        resolved = l;
                    }
                } else if (l instanceof LayerInfo) {
                    resolved = unwrap(ResolvingProxy.resolve(getCatalog(), (LayerInfo) l));
                    // special case to handle catalog loading, when nested publishibles might not be
                    // loaded.
                    if (resolved == null) {
                        resolved = l;
                    }
                } else {
                    // Special case for null layer (style group)
                    resolved = unwrap(ResolvingProxy.resolve(getCatalog(), l));
                }
                lg.getLayers().set(i, resolved);
            }
        }

        for (int i = 0; i < lg.getStyles().size(); i++) {
            StyleInfo s = lg.getStyles().get(i);
            if (s != null) {
                StyleInfo resolved = unwrap(ResolvingProxy.resolve(getCatalog(), s));
                lg.getStyles().set(i, resolved);
            }
        }
    }

    protected void resolve(StyleInfo style) {
        setId(style);

        // resolve the workspace
        WorkspaceInfo ws = style.getWorkspace();
        if (ws != null) {
            WorkspaceInfo resolved = ResolvingProxy.resolve(getCatalog(), ws);
            if (resolved != null) {
                resolved = unwrap(resolved);
                style.setWorkspace(resolved);
            } else {
                LOGGER.log(
                        Level.INFO,
                        "Failed to resolve workspace for style \""
                                + style.getName()
                                + "\". This means the workspace has not yet been added to the catalog, keep the proxy around");
            }
        }
    }

    protected void resolve(MapInfo map) {
        setId(map);
    }

    protected void resolve(WorkspaceInfo workspace) {
        setId(workspace);
    }

    protected void resolve(NamespaceInfo namespace) {
        setId(namespace);
    }

    protected void resolve(StoreInfo store) {
        setId(store);
        StoreInfoImpl s = (StoreInfoImpl) store;

        // resolve the workspace
        WorkspaceInfo resolved = ResolvingProxy.resolve(getCatalog(), s.getWorkspace());
        if (resolved != null) {
            resolved = unwrap(resolved);
            s.setWorkspace(resolved);
        } else {
            LOGGER.log(
                    Level.INFO,
                    "Failed to resolve workspace for store \""
                            + store.getName()
                            + "\". This means the workspace has not yet been added to the catalog, keep the proxy around");
        }
    }

    protected void resolve(ResourceInfo resource) {
        setId(resource);
        ResourceInfoImpl r = (ResourceInfoImpl) resource;

        // resolve the store
        StoreInfo store = ResolvingProxy.resolve(getCatalog(), r.getStore());
        if (store != null) {
            store = unwrap(store);
            r.setStore(store);
        }

        // resolve the namespace
        NamespaceInfo namespace = ResolvingProxy.resolve(getCatalog(), r.getNamespace());
        if (namespace != null) {
            namespace = unwrap(namespace);
            r.setNamespace(namespace);
        }
    }

    protected void setId(Object o) {
        if (OwsUtils.get(o, "id") == null) {
            String uid = new UID().toString();
            OwsUtils.set(o, "id", o.getClass().getSimpleName() + "-" + uid);
        }
    }
}
