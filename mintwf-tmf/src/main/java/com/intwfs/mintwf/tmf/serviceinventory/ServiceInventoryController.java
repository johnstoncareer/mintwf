package com.intwfs.mintwf.tmf.serviceinventory;

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
 * TMF638 Service Inventory Management API v4.0.0.
 */
@RestController
@RequestMapping(ServiceInventoryController.BASE_PATH)
public class ServiceInventoryController extends TmfController {

    public static final String BASE_PATH = "/tmf-api/serviceInventory/v4";

    private final EventHub hub;
    private final TmfCollection services;

    public ServiceInventoryController(EventSender sender) {
        super(BASE_PATH);
        hub = new EventHub(sender);
        services = new TmfCollection(ResourceType.of("service", "Service")
                .required("state", "serviceSpecification")
                .nonPatchable("serviceDate"), hub);
    }

    @Override
    protected EventHub hub() {
        return hub;
    }

    @GetMapping("/service")
    public ResponseEntity<List<Map<String, Object>>> listServices(@RequestParam MultiValueMap<String, String> params) {
        return list(services.list(null, params));
    }

    @PostMapping("/service")
    public ResponseEntity<Map<String, Object>> createService(@RequestBody Map<String, Object> body) {
        return create(services, null, body, "/service");
    }

    @GetMapping("/service/{id}")
    public Map<String, Object> getService(@PathVariable String id, @RequestParam(required = false) String fields) {
        return services.get(null, id, fields);
    }

    @PatchMapping(path = "/service/{id}", consumes = {MERGE_PATCH_JSON, "application/json"})
    public Map<String, Object> patchService(@PathVariable String id, @RequestBody Map<String, Object> patch) {
        return services.patch(null, id, patch);
    }

    @DeleteMapping("/service/{id}")
    public ResponseEntity<Void> deleteService(@PathVariable String id) {
        services.delete(null, id);
        return noContent();
    }
}
