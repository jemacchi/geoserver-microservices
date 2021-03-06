/*
 * (c) 2020 Open Source Geospatial Foundation - all rights reserved This code is licensed under the
 * GPL 2.0 license, available at the root application directory.
 */
package org.geoserver.cloud.bus.event.catalog;

import lombok.EqualsAndHashCode;
import lombok.NonNull;
import org.geoserver.catalog.Catalog;
import org.geoserver.catalog.CatalogInfo;
import org.geoserver.cloud.bus.event.RemoteModifyEvent;
import org.geoserver.cloud.event.PropertyDiff;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

@Component
@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
@EqualsAndHashCode(callSuper = true)
public class RemoteCatalogModifyEvent extends RemoteModifyEvent<Catalog, CatalogInfo>
        implements RemoteCatalogEvent {
    private static final long serialVersionUID = 1L;

    /** default constructor, needed for deserialization */
    protected RemoteCatalogModifyEvent() {
        //
    }

    public RemoteCatalogModifyEvent(
            @NonNull Catalog source,
            @NonNull CatalogInfo object,
            @NonNull PropertyDiff diff,
            @NonNull String originService,
            String destinationService) {
        super(source, object, diff, originService, destinationService);
    }
}
