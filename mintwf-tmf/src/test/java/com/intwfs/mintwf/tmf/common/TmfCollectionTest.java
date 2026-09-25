package com.intwfs.mintwf.tmf.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class TmfCollectionTest {

    private final List<Map<String, Object>> sent = new ArrayList<>();
    private final EventHub hub = new EventHub((callback, event) -> sent.add(event));
    private final TmfCollection orders = new TmfCollection(ResourceType.of("serviceOrder", "ServiceOrder")
            .required("serviceOrderItem")
            .serverAssigned("state", "orderDate")
            .nonPatchable("orderDate")
            .defaultValue("state", "acknowledged")
            .defaultNow("orderDate"), hub);

    @Test
    void createAssignsIdHrefTypeAndDefaults() {
        Map<String, Object> order = orders.create(null, order("A"), "http://host/serviceOrder");

        assertThat(order.get("id")).isNotNull();
        assertThat(order.get("href")).isEqualTo("http://host/serviceOrder/" + order.get("id"));
        assertThat(order).containsEntry("@type", "ServiceOrder").containsEntry("state", "acknowledged");
        assertThat(order.get("orderDate")).isNotNull();
    }

    @Test
    void createIgnoresServerAssignedAttributesFromClient() {
        Map<String, Object> body = order("A");
        body.put("id", "client-id");
        body.put("state", "completed");

        Map<String, Object> order = orders.create(null, body, "http://host/serviceOrder");

        assertThat(order.get("id")).isNotEqualTo("client-id");
        assertThat(order).containsEntry("state", "acknowledged");
    }

    @Test
    void createRejectsMissingOrEmptyMandatoryAttribute() {
        assertThatThrownBy(() -> orders.create(null, new HashMap<>(Map.of("description", "x")), "u"))
                .isInstanceOf(TmfException.class)
                .hasMessageContaining("serviceOrderItem");
        assertThatThrownBy(() -> orders.create(null, new HashMap<>(Map.of("serviceOrderItem", List.of())), "u"))
                .isInstanceOf(TmfException.class);
    }

    @Test
    void patchMergesAndPublishesStateAndAttributeChanges() {
        String id = (String) orders.create(null, order("A"), "u").get("id");
        sent.clear();
        hub.subscribe(null, Map.of("callback", "http://listener"));

        Map<String, Object> patched = orders.patch(null, id, Map.of("state", "inProgress", "description", "changed"));

        assertThat(patched).containsEntry("state", "inProgress").containsEntry("description", "changed");
        assertThat(sent).extracting(e -> e.get("eventType"))
                .containsExactly("ServiceOrderAttributeValueChangeEvent", "ServiceOrderStateChangeEvent");
    }

    @Test
    void patchRemovesAttributesSetToNull() {
        Map<String, Object> body = order("A");
        body.put("note", "remove me");
        String id = (String) orders.create(null, body, "u").get("id");

        Map<String, Object> patch = new HashMap<>();
        patch.put("note", null);

        assertThat(orders.patch(null, id, patch)).doesNotContainKey("note");
    }

    @Test
    void patchRejectsNonPatchableAndMandatoryRemoval() {
        String id = (String) orders.create(null, order("A"), "u").get("id");
        Map<String, Object> removeItems = new HashMap<>();
        removeItems.put("serviceOrderItem", null);

        assertThatThrownBy(() -> orders.patch(null, id, Map.of("orderDate", "2020-01-01")))
                .hasMessageContaining("cannot be patched");
        assertThatThrownBy(() -> orders.patch(null, id, Map.of("href", "x")))
                .hasMessageContaining("cannot be patched");
        assertThatThrownBy(() -> orders.patch(null, id, removeItems))
                .hasMessageContaining("serviceOrderItem");
    }

    @Test
    void listFiltersSelectsFieldsAndPages() {
        orders.create(null, order("A"), "u");
        orders.create(null, order("B"), "u");
        orders.create(null, order("C"), "u");

        TmfCollection.Page page = orders.list(null, Map.of(
                "serviceOrderItem.service.name", List.of("A,C"),
                "fields", List.of("state"),
                "offset", List.of("1"),
                "limit", List.of("5")));

        assertThat(page.total()).isEqualTo(2);
        assertThat(page.items()).hasSize(1);
        assertThat(page.items().getFirst().keySet()).containsExactlyInAnyOrder("id", "href", "@type", "state");
    }

    @Test
    void listRejectsInvalidPaging() {
        assertThatThrownBy(() -> orders.list(null, Map.of("limit", List.of("abc"))))
                .hasMessageContaining("limit");
        assertThatThrownBy(() -> orders.list(null, Map.of("offset", List.of("-1"))))
                .hasMessageContaining("offset");
    }

    @Test
    void parentScopesLookups() {
        TmfCollection tasks = new TmfCollection(ResourceType.of("taskFlow", "TaskFlow"));
        String id = (String) tasks.create("flow-1", new HashMap<>(), "u").get("id");

        assertThat(tasks.exists("flow-1", id)).isTrue();
        assertThatThrownBy(() -> tasks.get("flow-2", id)).isInstanceOf(TmfException.class);
    }

    @Test
    void returnedResourcesAreCopies() {
        Map<String, Object> created = orders.create(null, order("A"), "u");
        created.put("state", "tampered");

        assertThat(orders.get(null, (String) created.get("id"))).containsEntry("state", "acknowledged");
    }

    @Test
    void hubHonoursEventTypeQuery() {
        hub.subscribe(null, Map.of("callback", "http://listener", "query", "eventType=ServiceOrderDeleteEvent"));
        String id = (String) orders.create(null, order("A"), "u").get("id");
        orders.delete(null, id);

        assertThat(sent).extracting(e -> e.get("eventType")).containsExactly("ServiceOrderDeleteEvent");
    }

    @Test
    void hubRejectsNonHttpCallback() {
        assertThatThrownBy(() -> hub.subscribe(null, Map.of("callback", "file:///etc/passwd")))
                .hasMessageContaining("callback");
    }

    private static Map<String, Object> order(String serviceName) {
        Map<String, Object> body = new HashMap<>();
        body.put("serviceOrderItem", List.of(Map.of("id", "1", "action", "add", "service", Map.of("name", serviceName))));
        return body;
    }
}
