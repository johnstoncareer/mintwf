package com.intwfs.mintwf.tmf.serviceordering;

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
 * TMF641 Service Ordering Management API v4.0.0.
 */
@RestController
@RequestMapping(ServiceOrderingController.BASE_PATH)
public class ServiceOrderingController extends TmfController {

    public static final String BASE_PATH = "/tmf-api/serviceOrdering/v4";

    private final EventHub hub;
    private final TmfCollection serviceOrders;
    private final TmfCollection cancelServiceOrders;

    public ServiceOrderingController(EventSender sender) {
        super(BASE_PATH);
        hub = new EventHub(sender);
        serviceOrders = new TmfCollection(ResourceType.of("serviceOrder", "ServiceOrder")
                .required("serviceOrderItem")
                .serverAssigned("state", "orderDate", "startDate", "completionDate", "expectedCompletionDate")
                .nonPatchable("orderDate")
                .defaultValue("state", "acknowledged")
                .defaultNow("orderDate"), hub);
        cancelServiceOrders = new TmfCollection(ResourceType.of("cancelServiceOrder", "CancelServiceOrder")
                .required("serviceOrder")
                .serverAssigned("state", "effectiveCancellationDate")
                .defaultValue("state", "acknowledged"), hub);
    }

    @Override
    protected EventHub hub() {
        return hub;
    }

    @GetMapping("/serviceOrder")
    public ResponseEntity<List<Map<String, Object>>> listServiceOrders(@RequestParam MultiValueMap<String, String> params) {
        return list(serviceOrders.list(null, params));
    }

    @PostMapping("/serviceOrder")
    public ResponseEntity<Map<String, Object>> createServiceOrder(@RequestBody Map<String, Object> body) {
        return create(serviceOrders, null, body, "/serviceOrder");
    }

    @GetMapping("/serviceOrder/{id}")
    public Map<String, Object> getServiceOrder(@PathVariable String id, @RequestParam(required = false) String fields) {
        return serviceOrders.get(null, id, fields);
    }

    @PatchMapping(path = "/serviceOrder/{id}", consumes = {MERGE_PATCH_JSON, "application/json"})
    public Map<String, Object> patchServiceOrder(@PathVariable String id, @RequestBody Map<String, Object> patch) {
        return serviceOrders.patch(null, id, patch);
    }

    @DeleteMapping("/serviceOrder/{id}")
    public ResponseEntity<Void> deleteServiceOrder(@PathVariable String id) {
        serviceOrders.delete(null, id);
        return noContent();
    }

    @GetMapping("/cancelServiceOrder")
    public ResponseEntity<List<Map<String, Object>>> listCancelServiceOrders(@RequestParam MultiValueMap<String, String> params) {
        return list(cancelServiceOrders.list(null, params));
    }

    @PostMapping("/cancelServiceOrder")
    public ResponseEntity<Map<String, Object>> createCancelServiceOrder(@RequestBody Map<String, Object> body) {
        requireReference(body, "serviceOrder", serviceOrders);
        return create(cancelServiceOrders, null, body, "/cancelServiceOrder");
    }

    @GetMapping("/cancelServiceOrder/{id}")
    public Map<String, Object> getCancelServiceOrder(@PathVariable String id, @RequestParam(required = false) String fields) {
        return cancelServiceOrders.get(null, id, fields);
    }
}
