package com.intwfs.mintwf.tmf.resourceinventory;

import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
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
 * TMF639 Resource Inventory Management API v4.0.0.
 *
 * <p>The spec exposes {@code resource}, {@code physicalResource} and {@code logicalResource}
 * as separate collections; each is stored separately here.
 */
@RestController
@RequestMapping(ResourceInventoryController.BASE_PATH)
public class ResourceInventoryController extends TmfController {

    public static final String BASE_PATH = "/tmf-api/resourceInventoryManagement/v4";

    private final EventHub hub;
    private final TmfCollection resources;
    private final TmfCollection physicalResources;
    private final TmfCollection logicalResources;

    public ResourceInventoryController(EventSender sender) {
        super(BASE_PATH);
        hub = new EventHub(sender);
        resources = new TmfCollection(ResourceType.of("resource", "Resource").required("name"), hub);
        physicalResources = new TmfCollection(ResourceType.of("physicalResource", "PhysicalResource").required("name"), hub);
        logicalResources = new TmfCollection(ResourceType.of("logicalResource", "LogicalResource"), hub);
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

    @GetMapping("/physicalResource")
    public ResponseEntity<List<Map<String, Object>>> listPhysicalResources(@RequestParam MultiValueMap<String, String> params) {
        return list(physicalResources.list(null, params));
    }

    @PostMapping("/physicalResource")
    public ResponseEntity<Map<String, Object>> createPhysicalResource(@RequestBody Map<String, Object> body) {
        return create(physicalResources, null, body, "/physicalResource");
    }

    @GetMapping("/physicalResource/{id}")
    public Map<String, Object> getPhysicalResource(@PathVariable String id, @RequestParam(required = false) String fields) {
        return physicalResources.get(null, id, fields);
    }

    @PatchMapping(path = "/physicalResource/{id}", consumes = {MERGE_PATCH_JSON, "application/json"})
    public Map<String, Object> patchPhysicalResource(@PathVariable String id, @RequestBody Map<String, Object> patch) {
        return physicalResources.patch(null, id, patch);
    }

    @DeleteMapping("/physicalResource/{id}")
    public ResponseEntity<Void> deletePhysicalResource(@PathVariable String id) {
        physicalResources.delete(null, id);
        return noContent();
    }

    @GetMapping("/logicalResource")
    public ResponseEntity<List<Map<String, Object>>> listLogicalResources(@RequestParam MultiValueMap<String, String> params) {
        return list(logicalResources.list(null, params));
    }

    @PostMapping("/logicalResource")
    public ResponseEntity<Map<String, Object>> createLogicalResource(@RequestBody Map<String, Object> body) {
        return create(logicalResources, null, body, "/logicalResource");
    }

    @GetMapping("/logicalResource/{id}")
    public Map<String, Object> getLogicalResource(@PathVariable String id, @RequestParam(required = false) String fields) {
        return logicalResources.get(null, id, fields);
    }

    @PutMapping("/logicalResource/{id}")
    public Map<String, Object> replaceLogicalResource(@PathVariable String id, @RequestBody Map<String, Object> body) {
        return logicalResources.replace(null, id, body);
    }

    @PatchMapping(path = "/logicalResource/{id}", consumes = {MERGE_PATCH_JSON, "application/json"})
    public Map<String, Object> patchLogicalResource(@PathVariable String id, @RequestBody Map<String, Object> patch) {
        return logicalResources.patch(null, id, patch);
    }

    @DeleteMapping("/logicalResource/{id}")
    public ResponseEntity<Void> deleteLogicalResource(@PathVariable String id) {
        logicalResources.delete(null, id);
        return noContent();
    }
}
