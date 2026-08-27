/**
 * 2.0.0: FormSchemaService now returns an ordered List of fields rather than a keyed Map. That is a
 * breaking change to a &#64;ProviderType interface - existing consumers will not compile against it -
 * so the package takes a major bump rather than a minor one.
 *
 * <p>1.1.0 added UrlService.getPublishDomain().</p>
 */
@org.osgi.annotation.versioning.Version("2.0.0")
package com.edsMobileApp.core.services;
