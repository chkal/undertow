package io.undertow.server.handlers.resource;

import java.io.IOException;
import java.util.Date;

import io.undertow.predicate.Predicate;
import io.undertow.predicate.Predicates;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.server.handlers.cache.ResponseCache;
import io.undertow.util.DateUtils;
import io.undertow.util.ETag;
import io.undertow.util.ETagUtils;
import io.undertow.util.Headers;
import io.undertow.util.Methods;
import io.undertow.util.MimeMappings;

/**
 * @author Stuart Douglas
 */
public class ResourceHandler implements HttpHandler {

    /**
     * If directory listing is enabled.
     */
    private volatile boolean directoryListingEnabled = false;

    /**
     * The mime mappings that are used to determine the content type.
     */
    private volatile MimeMappings mimeMappings = MimeMappings.DEFAULT;

    private volatile Predicate<HttpServerExchange> cachable = Predicates.truePredicate();

    private volatile Predicate<HttpServerExchange> allowed = Predicates.truePredicate();

    private volatile ResourceManager resourceManager;

    /**
     * If this is set this will be the maximum time the client will cache the resource.
     *
     * Note: Do not set this for private resources, as it will cause a Cache-Control: public
     * to be sent.
     *
     * TODO: make this more flexible
     *
     * This will only be used if the {@link #cachable} predicate returns true
     */
    private volatile Integer cacheTime;

    /**
     * we do not calculate a new expiry date every request. Instead calculate it once
     * and cache it until it is in the past.
     *
     * TODO: do we need this policy to be plugable
     */
    private volatile long lastExpiryDate;
    private volatile String lastExpiryHeader;

    @Override
    public void handleRequest(final HttpServerExchange exchange) throws Exception {
        if (exchange.getRequestMethod().equals(Methods.GET) ||
                exchange.getRequestMethod().equals(Methods.POST)) {
            serveResource(exchange, true);
        } else if (exchange.getRequestMethod().equals(Methods.HEAD)) {
            serveResource(exchange, false);
        } else {
            exchange.setResponseCode(405);
            exchange.endExchange();
        }
    }

    private void serveResource(final HttpServerExchange exchange, final boolean sendContent) {

        if (DirectoryUtils.sendRequestedBlobs(exchange)) {
            return;
        }

        if (!allowed.resolve(exchange)) {
            exchange.setResponseCode(403);
            exchange.endExchange();
            return;
        }

        ResponseCache cache = exchange.getAttachment(ResponseCache.ATTACHMENT_KEY);
        final boolean cachable = this.cachable.resolve(exchange);

        //we set caching headers before we try and serve from the cache
        if(cachable && cacheTime != null) {
            exchange.getResponseHeaders().put(Headers.CACHE_CONTROL, "public, max-age=" + cacheTime);
            if(System.currentTimeMillis() > lastExpiryDate ) {
                long date = System.currentTimeMillis();
                lastExpiryHeader = DateUtils.toDateString(new Date(date));
                lastExpiryDate = date;
            }
            exchange.getResponseHeaders().put(Headers.EXPIRES, lastExpiryHeader);
        }

        if (cache != null && cachable) {
            if (cache.tryServeResponse()) {
                return;
            }
        }


        //we now dispatch to a worker thread
        //as resource manager methods are potentially blocking
        exchange.dispatch(new Runnable() {
            @Override
            public void run() {
                Resource resource = null;
                try {
                    resource = resourceManager.getResource(exchange.getRelativePath());
                } catch (IOException e) {
                    exchange.setResponseCode(500);
                    exchange.endExchange();
                    return;
                }
                if (resource == null) {
                    exchange.setResponseCode(404);
                    exchange.endExchange();
                    return;
                }

                if (resource.isDirectory()) {
                    DirectoryUtils.renderDirectoryListing(exchange, resource);
                    return;
                }

                final ETag etag = resource.getETag();
                final Date lastModified = resource.getLastModified();
                if (!ETagUtils.handleIfMatch(exchange, etag, false) ||
                        !DateUtils.handleIfUnmodifiedSince(exchange, lastModified)) {
                    exchange.setResponseCode(412);
                    exchange.endExchange();
                    return;
                }
                if (!ETagUtils.handleIfNoneMatch(exchange, etag, true) ||
                        !DateUtils.handleIfModifiedSince(exchange, lastModified)) {
                    exchange.setResponseCode(304);
                    exchange.endExchange();
                    return;
                }
                //todo: handle range requests
                //we are going to proceed. Set the appropriate headers
                final String contentType = resource.getContentType(mimeMappings);
                if (contentType != null) {
                    exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, contentType);
                } else {
                    exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/octet-stream");
                }
                if (lastModified != null) {
                    exchange.getResponseHeaders().put(Headers.LAST_MODIFIED, DateUtils.toDateString(lastModified));
                }
                if (etag != null) {
                    exchange.getResponseHeaders().put(Headers.CONTENT_LANGUAGE, etag.toString());
                }
                Long contentLength = resource.getContentLength();
                if (contentLength != null) {
                    exchange.getResponseHeaders().put(Headers.CONTENT_LENGTH, contentLength.toString());
                }
                if (!sendContent) {
                    exchange.endExchange();
                } else {
                    resource.serve(exchange);
                }
            }
        });


    }

    public boolean isDirectoryListingEnabled() {
        return directoryListingEnabled;
    }

    public ResourceHandler setDirectoryListingEnabled(final boolean directoryListingEnabled) {
        this.directoryListingEnabled = directoryListingEnabled;
        return this;
    }

    public MimeMappings getMimeMappings() {
        return mimeMappings;
    }

    public ResourceHandler setMimeMappings(final MimeMappings mimeMappings) {
        this.mimeMappings = mimeMappings;
        return this;
    }

    public Predicate<HttpServerExchange> getCachable() {
        return cachable;
    }

    public ResourceHandler setCachable(final Predicate<HttpServerExchange> cachable) {
        this.cachable = cachable;
        return this;
    }

    public Predicate<HttpServerExchange> getAllowed() {
        return allowed;
    }

    public ResourceHandler setAllowed(final Predicate<HttpServerExchange> allowed) {
        this.allowed = allowed;
        return this;
    }

    public ResourceManager getResourceManager() {
        return resourceManager;
    }

    public ResourceHandler setResourceManager(final ResourceManager resourceManager) {
        this.resourceManager = resourceManager;
        return this;
    }

    public Integer getCacheTime() {
        return cacheTime;
    }

    public ResourceHandler setCacheTime(final Integer cacheTime) {
        this.cacheTime = cacheTime;
        return this;
    }
}