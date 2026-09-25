package com.intwfs.mintwf.tmf.resourceactivation;

import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.intwfs.mintwf.tmf.common.EventHub;
import com.intwfs.mintwf.tmf.common.EventSender;
import com.intwfs.mintwf.tmf.common.ResourceType;
import com.intwfs.mintwf.tmf.common.TmfCollection;
import com.intwfs.mintwf.tmf.common.TmfController;

/**
 * TMF702 Resource Activation and Configuration API v4.0.0.
 *
 * <p>As with TMF640, requests are answered synchronously, so the monitor collection stays
 * empty until asynchronous processing is added.
 */
@RestController
@RequestMapping(ResourceActivationController.BASE_PATH)
public class ResourceActivationController extends TmfController {

    public static final String BASE_PATH = "/tmf-api/ResourceActivationAndConfiguration/v4";

    private final EventHub hub;
    private final TmfCollection resources;
    private final TmfCollection monitors;

    public ResourceActivationController(EventSender sender) {
        super(BASE_PATH);
        hub = new EventHub(sender);
        resources = new TmfCollection(ResourceType.of("resource", "Resource"), hub);
        monitors = new TmfCollection(ResourceType.of("monitor", "Monitor"), hub);
    }

    @Override
    protected EventHub hub() {
        return hub;
    }

    @GetMapping("/resource")
    public ResponseEntity<List<Map<String, Object>>> listResources(@RequestParam MultiValueMap<String, String> params) {
        return list(resources.list(null, params));
    }

    @PostMapping("/resource")
    public ResponseEntity<Map<String, Object>> createResource(@RequestBody Map<String, Object> body) {
        return create(resources, null, body, "/resource");
    }

    @GetMapping("/resource/{id}")
    public Map<String, Object> getResource(@PathVariable String id, @RequestParam(required = false) String fields) {
        return resources.get(null, id, fields);
    }

    @PatchMapping(path = "/resource/{id}", consumes = {MERGE_PATCH_JSON, "application/json"})
    public Map<String, Object> patchResource(@PathVariable String id, @RequestBody Map<String, Object> patch) {
        return resources.patch(null, id, patch);
    }

    @DeleteMapping("/resource/{id}")
    public ResponseEntity<Void> deleteResource(@PathVariable String id) {
        resources.delete(null, id);
        return noContent();
    }

    @GetMapping("/monitor")
    public ResponseEntity<List<Map<String, Object>>> listMonitors(@RequestParam MultiValueMap<String, String> params) {
        return list(monitors.list(null, params));
    }

    @GetMapping("/monitor/{id}")
    public Map<String, Object> getMonitor(@PathVariable String id, @RequestParam(required = false) String fields) {
        return monitors.get(null, id, fields);
    }
}
