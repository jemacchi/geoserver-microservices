/*
 * (c) 2020 Open Source Geospatial Foundation - all rights reserved This code is licensed under the
 * GPL 2.0 license, available at the root application directory.
 */
package org.geoserver.cloud.bus;

import java.lang.reflect.Proxy;
import lombok.extern.slf4j.Slf4j;
import org.geoserver.catalog.Catalog;
import org.geoserver.catalog.CatalogInfo;
import org.geoserver.catalog.CoverageStoreInfo;
import org.geoserver.catalog.DataStoreInfo;
import org.geoserver.catalog.FeatureTypeInfo;
import org.geoserver.catalog.Info;
import org.geoserver.catalog.ResourcePool;
import org.geoserver.catalog.StyleInfo;
import org.geoserver.catalog.WMSStoreInfo;
import org.geoserver.catalog.WMTSStoreInfo;
import org.geoserver.cloud.bus.event.RemoteInfoEvent;
import org.geoserver.cloud.bus.event.catalog.RemoteCatalogAddEvent;
import org.geoserver.cloud.bus.event.catalog.RemoteCatalogEvent;
import org.geoserver.cloud.bus.event.catalog.RemoteCatalogModifyEvent;
import org.geoserver.cloud.bus.event.catalog.RemoteCatalogRemoveEvent;
import org.geoserver.cloud.event.ConfigInfoInfoType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.bus.BusAutoConfiguration;
import org.springframework.cloud.bus.ServiceMatcher;
import org.springframework.cloud.bus.event.AckRemoteApplicationEvent;
import org.springframework.cloud.bus.event.RemoteApplicationEvent;
import org.springframework.context.event.EventListener;

/**
 * Listens to {@link RemoteCatalogEvent}s and acts accordingly
 *
 * @implNote {@code spring-cloud-bus} builds an abstraction layer over {@code spring-cloud-stream}
 *     to set up the event bus for the microservices on top of the configured broker (AMQP/Kafka),
 *     handling. See {@link BusAutoConfiguration}, it sets up the bus configuration and makes sure
 *     {@link RemoteApplicationEvent}s are not published to the same instance that broadcast them.
 */
@Slf4j(topic = "org.geoserver.cloud.bus.incoming")
public class RemoteEventResourcePoolProcessor {

    private Catalog rawCatalog;

    private @Autowired ServiceMatcher busServiceMatcher;

    /**
     * @param rawCatalog used to evict cached live data sources from its {@link
     *     Catalog#getResourcePool() ResourcePool}
     */
    public @Autowired RemoteEventResourcePoolProcessor(Catalog rawCatalog) {
        this.rawCatalog = rawCatalog;
    }

    /**
     * Logs ack events received from nodes which processed events sent by the remote event
     * broadcaster
     *
     * <p>{@code spring.cloud.bus.ack.enabled=true} must be set in order for these events to be
     * processed (see {@link BusAutoConfiguration})
     */
    public @EventListener(AckRemoteApplicationEvent.class) void ackReceived(
            AckRemoteApplicationEvent event) {
        if (!busServiceMatcher.isFromSelf(event)) {
            log.trace("Received event ack {}", event); // TODO improve log statement
        }
    }

    /**
     * no-op, really, what do we care if a CatalogInfo has been added until anincoming service
     * request needs it
     */
    @EventListener(RemoteCatalogAddEvent.class)
    public void onCatalogRemoteAddEvent(RemoteCatalogAddEvent event) {
        if (busServiceMatcher.isFromSelf(event)) {
            log.trace("Ignoring remote event from self: {}", event);
        } else {
            log.debug("remote add event, nothing to do. {}", event);
        }
    }

    @EventListener(RemoteCatalogRemoveEvent.class)
    public void onCatalogRemoteRemoveEvent(RemoteCatalogRemoveEvent event) {
        evictFromResourcePool(event);
    }

    @EventListener(RemoteCatalogModifyEvent.class)
    public void onCatalogRemoteModifyEvent(RemoteCatalogModifyEvent event) {
        evictFromResourcePool(event);
    }

    private void evictFromResourcePool(RemoteInfoEvent<Catalog, CatalogInfo> event) {
        if (busServiceMatcher.isFromSelf(event)) {
            log.trace("Ignoring event from self: {}", event);
            return;
        }
        final String id = event.getObjectId();
        final ConfigInfoInfoType infoType = event.getInfoType();
        switch (infoType) {
            case CoverageStoreInfo:
            case DataStoreInfo:
            case WmsStoreInfo:
            case WmtsStoreInfo:
            case FeatureTypeInfo:
            case StyleInfo:
                log.debug("Evict ResourcePool cache for {}", event);
                doEvict(id, infoType);
                break;
            default:
                log.trace(
                        "no need to clear resource pool cache entry for object of type {}",
                        infoType);
                break;
        }
    }

    private void doEvict(String id, ConfigInfoInfoType catalogInfoEnumType) {
        ResourcePool resourcePool = rawCatalog.getResourcePool();
        CatalogInfo catalogInfo = proxyInstanceOf(id, catalogInfoEnumType);
        switch (catalogInfoEnumType) {
            case CoverageStoreInfo:
                resourcePool.clear((CoverageStoreInfo) catalogInfo);
                break;
            case DataStoreInfo:
                resourcePool.clear((DataStoreInfo) catalogInfo);
                break;
            case FeatureTypeInfo:
                resourcePool.clear((FeatureTypeInfo) catalogInfo);
                break;
            case StyleInfo:
                // HACK: resourcePool.clear(StyleInfo) is key'ed by the object itself not the id
                StyleInfo style = rawCatalog.getStyle(catalogInfo.getId());
                if (style != null) {
                    resourcePool.clear(style);
                }
                break;
            case WmsStoreInfo:
                resourcePool.clear((WMSStoreInfo) catalogInfo);
                break;
            case WmtsStoreInfo:
                resourcePool.clear((WMTSStoreInfo) catalogInfo);
                break;
            default:
                throw new IllegalArgumentException();
        }
    }

    private CatalogInfo proxyInstanceOf(final String id, final ConfigInfoInfoType catalogInfoType) {

        Class<? extends Info> infoInterface = catalogInfoType.getType();

        return (CatalogInfo)
                Proxy.newProxyInstance(
                        getClass().getClassLoader(),
                        new Class[] {infoInterface},
                        (proxy, method, args) -> {
                            if (method.getName().equals("getId")) {
                                return id;
                            }
                            return null;
                        });
    }
}
