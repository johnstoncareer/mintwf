package com.intwfs.mintwf.tmf.resourceordering;

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
 * TMF652 Resource Ordering Management API v4.0.0.
 */
@RestController
@RequestMapping(ResourceOrderingController.BASE_PATH)
public class ResourceOrderingController extends TmfController {

    public static final String BASE_PATH = "/tmf-api/resourceOrderingManagement/v4";

    private final EventHub hub;
    private final TmfCollection resourceOrders;
    private final TmfCollection cancelResourceOrders;

    public ResourceOrderingController(EventSender sender) {
        super(BASE_PATH);
        hub = new EventHub(sender);
        // Unlike TMF622/641, the TMF652 spec lets clients supply state and orderDate on create.
        resourceOrders = new TmfCollection(ResourceType.of("resourceOrder", "ResourceOrder")
                .nonPatchable("orderDate", "completionDate")
                .defaultValue("state", "acknowledged")
                .defaultNow("orderDate"), hub);
        cancelResourceOrders = new TmfCollection(ResourceType.of("cancelResourceOrder", "CancelResourceOrder")
                .required("resourceOrder")
                .serverAssigned("state", "effectiveCancellationDate")
                .defaultValue("state", "acknowledged"), hub);
    }

    @Override
    protected EventHub hub() {
        return hub;
    }

    @GetMapping("/resourceOrder")
    public ResponseEntity<List<Map<String, Object>>> listResourceOrders(@RequestParam MultiValueMap<String, String> params) {
        return list(resourceOrders.list(null, params));
    }

    @PostMapping("/resourceOrder")
    public ResponseEntity<Map<String, Object>> createResourceOrder(@RequestBody Map<String, Object> body) {
        return create(resourceOrders, null, body, "/resourceOrder");
    }

    @GetMapping("/resourceOrder/{id}")
    public Map<String, Object> getResourceOrder(@PathVariable String id, @RequestParam(required = false) String fields) {
        return resourceOrders.get(null, id, fields);
    }

    @PatchMapping(path = "/resourceOrder/{id}", consumes = {MERGE_PATCH_JSON, "application/json"})
    public Map<String, Object> patchResourceOrder(@PathVariable String id, @RequestBody Map<String, Object> patch) {
        return resourceOrders.patch(null, id, patch);
    }

    @DeleteMapping("/resourceOrder/{id}")
    public ResponseEntity<Void> deleteResourceOrder(@PathVariable String id) {
        resourceOrders.delete(null, id);
        return noContent();
    }

    @GetMapping("/cancelResourceOrder")
    public ResponseEntity<List<Map<String, Object>>> listCancelResourceOrders(@RequestParam MultiValueMap<String, String> params) {
        return list(cancelResourceOrders.list(null, params));
    }

    @PostMapping("/cancelResourceOrder")
    public ResponseEntity<Map<String, Object>> createCancelResourceOrder(@RequestBody Map<String, Object> body) {
        requireReference(body, "resourceOrder", resourceOrders);
        return create(cancelResourceOrders, null, body, "/cancelResourceOrder");
    }

    @GetMapping("/cancelResourceOrder/{id}")
    public Map<String, Object> getCancelResourceOrder(@PathVariable String id, @RequestParam(required = false) String fields) {
        return cancelResourceOrders.get(null, id, fields);
    }
}
