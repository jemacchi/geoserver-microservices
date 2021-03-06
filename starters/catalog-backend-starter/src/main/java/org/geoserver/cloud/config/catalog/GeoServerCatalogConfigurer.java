/*
 * (c) 2020 Open Source Geospatial Foundation - all rights reserved This code is licensed under the
 * GPL 2.0 license, available at the root application directory.
 */
package org.geoserver.cloud.config.catalog;

import org.geoserver.catalog.Catalog;
import org.geoserver.catalog.CatalogFacade;
import org.geoserver.catalog.LayerGroupVisibilityPolicy;
import org.geoserver.catalog.impl.AdvertisedCatalog;
import org.geoserver.catalog.impl.CatalogImpl;
import org.geoserver.catalog.impl.LocalWorkspaceCatalog;
import org.geoserver.platform.GeoServerResourceLoader;
import org.geoserver.security.SecureCatalogImpl;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.DependsOn;

/** Configurer interface for the {@link Catalog} subsystem. */
public interface GeoServerCatalogConfigurer {

    public @Bean CatalogFacade catalogFacade();

    @DependsOn({"resourceLoader", "catalogFacade"})
    default @Bean Catalog rawCatalog(
            GeoServerResourceLoader resourceLoader, CatalogFacade catalogFacadeImpl) {
        CatalogImpl catalog = new org.geoserver.catalog.plugin.CatalogImpl(catalogFacadeImpl);
        catalog.setResourceLoader(resourceLoader);
        return catalog;
    }

    /**
     * @return {@link SecureCatalogImpl} decorator if {@code properties.isSecure() == true}, {@code
     *     rawCatalog} otherwise.
     */
    default @Bean Catalog secureCatalog(
            @Qualifier("rawCatalog") Catalog rawCatalog,
            CatalogProperties properties,
            @Value("${geoserver.security.enabled:true}") boolean securityEnabled)
            throws Exception {
        if (securityEnabled && properties.isSecure()) return new SecureCatalogImpl(rawCatalog);
        return rawCatalog;
    }

    /**
     * @return {@link AdvertisedCatalog} decorator if {@code properties.isAdvertised() == true},
     *     {@code secureCatalog} otherwise.
     */
    default @Bean Catalog advertisedCatalog(
            @Qualifier("secureCatalog") Catalog secureCatalog, CatalogProperties properties)
            throws Exception {
        if (properties.isAdvertised()) {
            AdvertisedCatalog advertisedCatalog = new AdvertisedCatalog(secureCatalog);
            advertisedCatalog.setLayerGroupVisibilityPolicy(LayerGroupVisibilityPolicy.HIDE_NEVER);
            return advertisedCatalog;
        }
        return secureCatalog;
    }

    /**
     * @return {@link LocalWorkspaceCatalog} decorator if {@code properties.isLocalWorkspace() ==
     *     true}, {@code advertisedCatalog} otherwise
     */
    @Bean(name = {"catalog", "localWorkspaceCatalog"})
    default Catalog localWorkspaceCatalog(
            @Qualifier("advertisedCatalog") Catalog advertisedCatalog, CatalogProperties properties)
            throws Exception {
        return properties.isLocalWorkspace()
                ? new LocalWorkspaceCatalog(advertisedCatalog)
                : advertisedCatalog;
    }
}
