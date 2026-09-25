package com.intwfs.mintwf.tmf.processflow;

import java.util.ArrayList;
import java.util.LinkedHashMap;
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
 * TMF701 Process Flow Management API v4.0.0.
 *
 * <p>This is how mintwf exposes running workflows: a process flow is a workflow instance and
 * its task flows are the steps. The spec has no endpoint for creating task flows, since the
 * server creates them, so the engine adds them through {@link #addTaskFlow}.
 */
@RestController
@RequestMapping(ProcessFlowController.BASE_PATH)
public class ProcessFlowController extends TmfController {

    public static final String BASE_PATH = "/tmf-api/processFlowManagement/v4";

    private final EventHub hub;
    private final TmfCollection processFlows;
    private final TmfCollection taskFlows;

    public ProcessFlowController(EventSender sender) {
        super(BASE_PATH);
        hub = new EventHub(sender);
        processFlows = new TmfCollection(ResourceType.of("processFlow", "ProcessFlow")
                .required("processFlowSpecification")
                .serverAssigned("state", "processFlowDate", "taskFlow")
                .defaultValue("state", "active")
                .defaultNow("processFlowDate")
                .defaultValue("taskFlow", List.of()), hub);
        taskFlows = new TmfCollection(ResourceType.of("taskFlow", "TaskFlow")
                .nonPatchable("taskFlowSpecification")
                .defaultValue("state", "new"), hub);
    }

    @Override
    protected EventHub hub() {
        return hub;
    }

    @GetMapping("/processFlow")
    public ResponseEntity<List<Map<String, Object>>> listProcessFlows(@RequestParam MultiValueMap<String, String> params) {
        return list(processFlows.list(null, params));
    }

    @PostMapping("/processFlow")
    public ResponseEntity<Map<String, Object>> createProcessFlow(@RequestBody Map<String, Object> body) {
        return create(processFlows, null, body, "/processFlow");
    }

    @GetMapping("/processFlow/{id}")
    public Map<String, Object> getProcessFlow(@PathVariable String id, @RequestParam(required = false) String fields) {
        return processFlows.get(null, id, fields);
    }

    @DeleteMapping("/processFlow/{id}")
    public ResponseEntity<Void> deleteProcessFlow(@PathVariable String id) {
        processFlows.delete(null, id);
        taskFlows.deleteAll(id);
        return noContent();
    }

    @GetMapping("/processFlow/{processFlowId}/taskFlow")
    public ResponseEntity<List<Map<String, Object>>> listTaskFlows(
            @PathVariable String processFlowId, @RequestParam MultiValueMap<String, String> params) {
        requireProcessFlow(processFlowId);
        return list(taskFlows.list(processFlowId, params));
    }

    @GetMapping("/processFlow/{processFlowId}/taskFlow/{id}")
    public Map<String, Object> getTaskFlow(
            @PathVariable String processFlowId, @PathVariable String id, @RequestParam(required = false) String fields) {
        requireProcessFlow(processFlowId);
        return taskFlows.get(processFlowId, id, fields);
    }

    @PatchMapping(path = "/processFlow/{processFlowId}/taskFlow/{id}", consumes = {MERGE_PATCH_JSON, "application/json"})
    public Map<String, Object> patchTaskFlow(
            @PathVariable String processFlowId, @PathVariable String id, @RequestBody Map<String, Object> patch) {
        requireProcessFlow(processFlowId);
        return taskFlows.patch(processFlowId, id, patch);
    }

    /**
     * Adds a task flow to a process flow and records a reference to it in the process flow's
     * {@code taskFlow} list. Intended for the engine, which creates task flows as it runs.
     */
    public Map<String, Object> addTaskFlow(String processFlowId, Map<String, Object> taskFlow) {
        Map<String, Object> processFlow = processFlows.get(null, processFlowId);
        Map<String, Object> created = taskFlows.create(
                processFlowId, taskFlow, url("/processFlow/" + processFlowId + "/taskFlow"));

        Map<String, Object> ref = new LinkedHashMap<>();
        ref.put("id", created.get("id"));
        ref.put("href", created.get("href"));
        ref.put("@referredType", "TaskFlow");
        List<Object> refs = processFlow.get("taskFlow") instanceof List<?> existing
                ? new ArrayList<>(existing)
                : new ArrayList<>();
        refs.add(ref);
        processFlows.patch(null, processFlowId, Map.of("taskFlow", refs));
        return created;
    }

    private void requireProcessFlow(String processFlowId) {
        processFlows.get(null, processFlowId);
    }
}
