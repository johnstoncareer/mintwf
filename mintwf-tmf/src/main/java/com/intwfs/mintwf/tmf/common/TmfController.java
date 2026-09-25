package com.intwfs.mintwf.tmf.common;

import java.net.URI;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * Base class for TMF API controllers. Provides the {@code /hub} subscription endpoints every
 * TMF API has, and helpers that turn {@link TmfCollection} results into TMF responses.
 */
public abstract class TmfController {

    /** Content type TMF630 specifies for PATCH requests. */
    public static final String MERGE_PATCH_JSON = "application/merge-patch+json";

    private final String basePath;

    protected TmfController(String basePath) {
        this.basePath = basePath;
    }

    protected abstract EventHub hub();

    @PostMapping("/hub")
    public ResponseEntity<Map<String, Object>> subscribe(@RequestBody Map<String, Object> body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(hub().subscribe(null, body));
    }

    @DeleteMapping("/hub/{id}")
    public ResponseEntity<Void> unsubscribe(@PathVariable String id) {
        hub().unsubscribe(null, id);
        return ResponseEntity.noContent().build();
    }

    /**
     * Absolute URL of a path under this API, based on the current request's host. Outside a
     * request (for example when the engine creates a resource) the path is returned relative.
     */
    protected String url(String path) {
        String full = basePath + path;
        if (RequestContextHolder.getRequestAttributes() == null) {
            return full;
        }
        return ServletUriComponentsBuilder.fromCurrentContextPath().path(full).build().toUriString();
    }

    protected static ResponseEntity<List<Map<String, Object>>> list(TmfCollection.Page page) {
        return ResponseEntity.ok()
                .header("X-Total-Count", String.valueOf(page.total()))
                .header("X-Result-Count", String.valueOf(page.items().size()))
                .body(page.items());
    }

    protected ResponseEntity<Map<String, Object>> create(
            TmfCollection collection, String parentId, Map<String, Object> body, String collectionPath) {
        String collectionUrl = url(collectionPath);
        Map<String, Object> created = collection.create(parentId, body, collectionUrl);
        Object location = created.containsKey("href")
                ? created.get("href")
                : collectionUrl + "/" + created.get(collection.type().idField());
        return ResponseEntity.created(URI.create(String.valueOf(location))).body(created);
    }

    protected static ResponseEntity<Void> noContent() {
        return ResponseEntity.noContent().build();
    }

    /**
     * Checks that {@code body.<field>.id} refers to an existing resource, as cancel requests
     * must reference the order they cancel.
     */
    protected static void requireReference(Map<String, Object> body, String field, TmfCollection target) {
        if (!(body.get(field) instanceof Map<?, ?> ref) || !(ref.get("id") instanceof String id)) {
            throw TmfException.badRequest("Missing mandatory attribute '" + field + ".id'");
        }
        if (!target.exists(null, id)) {
            throw TmfException.badRequest("Referenced " + field + " '" + id + "' does not exist");
        }
    }
}
