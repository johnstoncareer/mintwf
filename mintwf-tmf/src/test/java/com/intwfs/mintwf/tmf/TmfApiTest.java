package com.intwfs.mintwf.tmf;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.intwfs.mintwf.tmf.common.EventSender;
import com.intwfs.mintwf.tmf.common.TmfController;
import com.intwfs.mintwf.tmf.processflow.ProcessFlowController;
import com.jayway.jsonpath.JsonPath;

@SpringBootTest
class TmfApiTest {

    private static final MediaType MERGE_PATCH = MediaType.parseMediaType(TmfController.MERGE_PATCH_JSON);

    /** Records events instead of posting them, so tests can assert on notifications. */
    record Delivery(String callback, Map<String, Object> event) {
    }

    @TestConfiguration
    static class CapturingSenderConfig {
        @Bean
        @Primary
        CapturingSender capturingSender() {
            return new CapturingSender();
        }
    }

    static class CapturingSender implements EventSender {
        final List<Delivery> deliveries = new CopyOnWriteArrayList<>();

        @Override
        public void send(String callback, Map<String, Object> event) {
            deliveries.add(new Delivery(callback, event));
        }
    }

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private CapturingSender sender;

    @Autowired
    private ProcessFlowController processFlowController;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
        sender.deliveries.clear();
    }

    static Stream<Arguments> crudCollections() {
        String orderItem = "[{\"id\":\"1\",\"action\":\"add\"}]";
        String service = "{\"state\":\"inactive\",\"serviceSpecification\":{\"id\":\"fiber\"}}";
        return Stream.of(
                Arguments.of("/tmf-api/productOrderingManagement/v4/productOrder", "{\"productOrderItem\":" + orderItem + "}"),
                Arguments.of("/tmf-api/serviceOrdering/v4/serviceOrder", "{\"serviceOrderItem\":" + orderItem + "}"),
                Arguments.of("/tmf-api/resourceOrderingManagement/v4/resourceOrder", "{\"name\":\"port\"}"),
                Arguments.of("/tmf-api/ServiceActivationAndConfiguration/v4/service", service),
                Arguments.of("/tmf-api/ResourceActivationAndConfiguration/v4/resource", "{\"name\":\"ont\"}"),
                Arguments.of("/tmf-api/serviceInventory/v4/service", service),
                Arguments.of("/tmf-api/resourceInventoryManagement/v4/resource", "{\"name\":\"olt\"}"),
                Arguments.of("/tmf-api/resourceInventoryManagement/v4/physicalResource", "{\"name\":\"card\"}"),
                Arguments.of("/tmf-api/resourceInventoryManagement/v4/logicalResource", "{\"name\":\"vlan\"}"));
    }

    @ParameterizedTest
    @MethodSource("crudCollections")
    void supportsCreateReadListPatchDelete(String collection, String body) throws Exception {
        String id = createAndGetId(collection, body);
        String marker = "patched-" + id;

        mvc.perform(get(collection + "/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.href").value("http://localhost" + collection + "/" + id));

        mvc.perform(patch(collection + "/" + id).contentType(MERGE_PATCH).content("{\"description\":\"" + marker + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.description").value(marker));

        mvc.perform(get(collection).param("description", marker).param("fields", "description"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Total-Count", "1"))
                .andExpect(header().string("X-Result-Count", "1"))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(id))
                .andExpect(jsonPath("$[0].description").value(marker));

        mvc.perform(delete(collection + "/" + id)).andExpect(status().isNoContent());

        mvc.perform(get(collection + "/" + id))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("404"))
                .andExpect(jsonPath("$['@type']").value("Error"));
    }

    @Test
    void ordersStartAcknowledgedAndRequireItems() throws Exception {
        mvc.perform(post("/tmf-api/serviceOrdering/v4/serviceOrder")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"serviceOrderItem\":[{\"id\":\"1\",\"action\":\"add\"}],\"state\":\"completed\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", startsWith("http://localhost/tmf-api/serviceOrdering/v4/serviceOrder/")))
                .andExpect(jsonPath("$.state").value("acknowledged"))
                .andExpect(jsonPath("$.orderDate").exists())
                .andExpect(jsonPath("$['@type']").value("ServiceOrder"));

        mvc.perform(post("/tmf-api/serviceOrdering/v4/serviceOrder")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":\"no items\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Missing mandatory attribute 'serviceOrderItem' in serviceOrder"));
    }

    @Test
    void rejectsNonPatchableAttributes() throws Exception {
        String id = createAndGetId("/tmf-api/productOrderingManagement/v4/productOrder",
                "{\"productOrderItem\":[{\"id\":\"1\",\"action\":\"add\"}]}");

        mvc.perform(patch("/tmf-api/productOrderingManagement/v4/productOrder/" + id)
                        .contentType(MERGE_PATCH)
                        .content("{\"orderDate\":\"2020-01-01T00:00:00Z\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsMalformedRequests() throws Exception {
        mvc.perform(post("/tmf-api/serviceInventory/v4/service").contentType(MediaType.APPLICATION_JSON).content("[1,2]"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$['@type']").value("Error"));

        mvc.perform(patch("/tmf-api/serviceInventory/v4/service/x").contentType(MediaType.TEXT_PLAIN).content("x"))
                .andExpect(status().isUnsupportedMediaType());

        mvc.perform(get("/tmf-api/serviceInventory/v4/service").param("limit", "many"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void cancelOrderMustReferenceExistingOrder() throws Exception {
        String base = "/tmf-api/resourceOrderingManagement/v4";
        mvc.perform(post(base + "/cancelResourceOrder").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resourceOrder\":{\"id\":\"missing\"}}"))
                .andExpect(status().isBadRequest());

        String orderId = createAndGetId(base + "/resourceOrder", "{\"name\":\"x\"}");
        String cancelId = createAndGetId(base + "/cancelResourceOrder", "{\"resourceOrder\":{\"id\":\"" + orderId + "\"}}");

        mvc.perform(get(base + "/cancelResourceOrder/" + cancelId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("acknowledged"));
    }

    @Test
    void logicalResourceSupportsPut() throws Exception {
        String collection = "/tmf-api/resourceInventoryManagement/v4/logicalResource";
        String id = createAndGetId(collection, "{\"name\":\"vlan-10\",\"description\":\"old\"}");

        mvc.perform(put(collection + "/" + id).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"vlan-20\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.name").value("vlan-20"))
                .andExpect(jsonPath("$.description").doesNotExist());
    }

    @Test
    void activationMonitorsAreReadOnlyAndEmpty() throws Exception {
        mvc.perform(get("/tmf-api/ServiceActivationAndConfiguration/v4/monitor"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
        mvc.perform(get("/tmf-api/ResourceActivationAndConfiguration/v4/monitor/unknown"))
                .andExpect(status().isNotFound());
    }

    @Test
    void hubSubscribersReceiveNotifications() throws Exception {
        String base = "/tmf-api/serviceOrdering/v4";
        String hubId = createAndGetId(base + "/hub",
                "{\"callback\":\"http://listener.example/events\",\"query\":\"eventType=ServiceOrderCreateEvent,ServiceOrderStateChangeEvent\"}");
        String orderId = createAndGetId(base + "/serviceOrder", "{\"serviceOrderItem\":[{\"id\":\"1\",\"action\":\"add\"}]}");
        mvc.perform(patch(base + "/serviceOrder/" + orderId).contentType(MERGE_PATCH).content("{\"state\":\"inProgress\"}"))
                .andExpect(status().isOk());

        assertThat(sender.deliveries).extracting(Delivery::callback).containsOnly("http://listener.example/events");
        assertThat(sender.deliveries).extracting(d -> d.event().get("eventType"))
                .containsExactly("ServiceOrderCreateEvent", "ServiceOrderStateChangeEvent");
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) sender.deliveries.getLast().event().get("event");
        assertThat(payload).containsKey("serviceOrder");

        mvc.perform(delete(base + "/hub/" + hubId)).andExpect(status().isNoContent());
        mvc.perform(delete(base + "/hub/" + hubId)).andExpect(status().isNotFound());
    }

    @Test
    void processFlowExposesTaskFlowsAddedByTheEngine() throws Exception {
        String base = "/tmf-api/processFlowManagement/v4";
        String flowId = createAndGetId(base + "/processFlow", "{\"processFlowSpecification\":\"ProvisionFiberService\"}");

        mvc.perform(get(base + "/processFlow/" + flowId))
                .andExpect(jsonPath("$.state").value("active"))
                .andExpect(jsonPath("$.taskFlow", hasSize(0)));

        String taskId = (String) processFlowController
                .addTaskFlow(flowId, Map.of("taskFlowSpecification", "ReservePort"))
                .get("id");

        mvc.perform(get(base + "/processFlow/" + flowId))
                .andExpect(jsonPath("$.taskFlow[0].id").value(taskId));
        mvc.perform(get(base + "/processFlow/" + flowId + "/taskFlow"))
                .andExpect(header().string("X-Total-Count", "1"))
                .andExpect(jsonPath("$[0].state").value("new"));
        mvc.perform(patch(base + "/processFlow/" + flowId + "/taskFlow/" + taskId)
                        .contentType(MERGE_PATCH).content("{\"state\":\"completed\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("completed"));
        mvc.perform(patch(base + "/processFlow/" + flowId + "/taskFlow/" + taskId)
                        .contentType(MERGE_PATCH).content("{\"taskFlowSpecification\":\"Other\"}"))
                .andExpect(status().isBadRequest());

        mvc.perform(delete(base + "/processFlow/" + flowId)).andExpect(status().isNoContent());
        mvc.perform(get(base + "/processFlow/" + flowId + "/taskFlow/" + taskId)).andExpect(status().isNotFound());
    }

    @Test
    void eventsPostedToTopicReachTopicHubs() throws Exception {
        String base = "/tmf-api/event/v4";
        String apiHub = createAndGetId(base + "/hub", "{\"callback\":\"http://api-listener.example\"}");
        String topicId = createAndGetId(base + "/topic", "{\"name\":\"orders\"}");
        createAndGetId(base + "/topic/" + topicId + "/hub", "{\"callback\":\"http://topic-listener.example\"}");

        mvc.perform(post(base + "/topic/" + topicId + "/event").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"eventId\":\"evt-1\",\"eventType\":\"OrderReceived\",\"event\":{\"orderId\":\"42\"}}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.eventId").value("evt-1"))
                .andExpect(jsonPath("$.eventTime").exists());

        assertThat(sender.deliveries)
                .filteredOn(d -> d.callback().equals("http://topic-listener.example"))
                .singleElement()
                .satisfies(d -> assertThat(d.event()).containsEntry("eventId", "evt-1"));
        assertThat(sender.deliveries)
                .filteredOn(d -> d.callback().equals("http://api-listener.example"))
                .extracting(d -> d.event().get("eventType"))
                .containsExactly("TopicCreateEvent");

        mvc.perform(get(base + "/topic/" + topicId + "/event/evt-1")).andExpect(status().isOk());
        mvc.perform(delete(base + "/topic/" + topicId)).andExpect(status().isNoContent());
        mvc.perform(get(base + "/topic/" + topicId + "/event")).andExpect(status().isNotFound());
        mvc.perform(delete(base + "/hub/" + apiHub)).andExpect(status().isNoContent());
    }

    private String createAndGetId(String collection, String body) throws Exception {
        String response = mvc.perform(post(collection).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(response, "$.id");
    }
}
