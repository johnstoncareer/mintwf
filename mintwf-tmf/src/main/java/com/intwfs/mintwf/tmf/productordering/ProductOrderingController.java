package com.intwfs.mintwf.tmf.productordering;

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
 * TMF622 Product Ordering Management API v4.0.0.
 */
@RestController
@RequestMapping(ProductOrderingController.BASE_PATH)
public class ProductOrderingController extends TmfController {

    public static final String BASE_PATH = "/tmf-api/productOrderingManagement/v4";

    private final EventHub hub;
    private final TmfCollection productOrders;
    private final TmfCollection cancelProductOrders;

    public ProductOrderingController(EventSender sender) {
        super(BASE_PATH);
        hub = new EventHub(sender);
        productOrders = new TmfCollection(ResourceType.of("productOrder", "ProductOrder")
                .required("productOrderItem")
                .serverAssigned("state", "orderDate", "completionDate", "expectedCompletionDate")
                .nonPatchable("orderDate")
                .defaultValue("state", "acknowledged")
                .defaultNow("orderDate"), hub);
        cancelProductOrders = new TmfCollection(ResourceType.of("cancelProductOrder", "CancelProductOrder")
                .required("productOrder")
                .serverAssigned("state", "effectiveCancellationDate")
                .defaultValue("state", "acknowledged"), hub);
    }

    @Override
    protected EventHub hub() {
        return hub;
    }

    @GetMapping("/productOrder")
    public ResponseEntity<List<Map<String, Object>>> listProductOrders(@RequestParam MultiValueMap<String, String> params) {
        return list(productOrders.list(null, params));
    }

    @PostMapping("/productOrder")
    public ResponseEntity<Map<String, Object>> createProductOrder(@RequestBody Map<String, Object> body) {
        return create(productOrders, null, body, "/productOrder");
    }

    @GetMapping("/productOrder/{id}")
    public Map<String, Object> getProductOrder(@PathVariable String id, @RequestParam(required = false) String fields) {
        return productOrders.get(null, id, fields);
    }

    @PatchMapping(path = "/productOrder/{id}", consumes = {MERGE_PATCH_JSON, "application/json"})
    public Map<String, Object> patchProductOrder(@PathVariable String id, @RequestBody Map<String, Object> patch) {
        return productOrders.patch(null, id, patch);
    }

    @DeleteMapping("/productOrder/{id}")
    public ResponseEntity<Void> deleteProductOrder(@PathVariable String id) {
        productOrders.delete(null, id);
        return noContent();
    }

    @GetMapping("/cancelProductOrder")
    public ResponseEntity<List<Map<String, Object>>> listCancelProductOrders(@RequestParam MultiValueMap<String, String> params) {
        return list(cancelProductOrders.list(null, params));
    }

    @PostMapping("/cancelProductOrder")
    public ResponseEntity<Map<String, Object>> createCancelProductOrder(@RequestBody Map<String, Object> body) {
        requireReference(body, "productOrder", productOrders);
        return create(cancelProductOrders, null, body, "/cancelProductOrder");
    }

    @GetMapping("/cancelProductOrder/{id}")
    public Map<String, Object> getCancelProductOrder(@PathVariable String id, @RequestParam(required = false) String fields) {
        return cancelProductOrders.get(null, id, fields);
    }
}
