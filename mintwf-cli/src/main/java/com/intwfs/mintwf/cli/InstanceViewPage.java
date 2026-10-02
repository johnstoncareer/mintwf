package com.intwfs.mintwf.cli;

import com.intwfs.mintwf.core.api.NodeInstance;
import com.intwfs.mintwf.core.api.ProcessInstance;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Renders a self-contained HTML page that draws an instance's BPMN diagram and marks which nodes ran, which are
 * active, and which failed. The page loads bpmn-js from a CDN; the instance data is embedded in it.
 */
public final class InstanceViewPage {

    private static final String TEMPLATE = "instance-view.html";
    private static final String DATA_PLACEHOLDER = "__MINTWF_DATA__";

    private InstanceViewPage() {
    }

    public static String render(ProcessInstance instance, List<NodeInstance> history, byte[] bpmnXml) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("instance", instance);
        data.put("history", history);
        data.put("bpmn", new String(bpmnXml, StandardCharsets.UTF_8));
        // In JSON, '<' can only occur inside strings, where < means the same. Escaping it stops a variable such as
        // "</script>" from ending the script element the data sits in.
        String json = JsonOutput.JSON.writeValueAsString(data).replace("<", "\\u003c");
        return template().replace(DATA_PLACEHOLDER, json);
    }

    private static String template() {
        try (InputStream in = InstanceViewPage.class.getResourceAsStream(TEMPLATE)) {
            if (in == null) {
                throw new IllegalStateException("page template " + TEMPLATE + " is missing");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
