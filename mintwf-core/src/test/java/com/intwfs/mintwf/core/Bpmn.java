package com.intwfs.mintwf.core;

import java.nio.charset.StandardCharsets;

/**
 * Builds BPMN test documents from the elements inside a process.
 */
public final class Bpmn {

    private Bpmn() {
    }

    public static byte[] process(String key, String body) {
        return definitions("""
                <process id="%s" isExecutable="true">
                %s
                </process>""".formatted(key, body));
    }

    public static byte[] definitions(String content) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                             xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                             xmlns:mintwf="https://intwfs.com/mintwf"
                             targetNamespace="https://intwfs.com/mintwf/test">
                %s
                </definitions>
                """.formatted(content).getBytes(StandardCharsets.UTF_8);
    }

    public static String flow(String id, String source, String target) {
        return "<sequenceFlow id=\"%s\" sourceRef=\"%s\" targetRef=\"%s\"/>".formatted(id, source, target);
    }

    public static String conditionalFlow(String id, String source, String target, String condition) {
        return """
                <sequenceFlow id="%s" sourceRef="%s" targetRef="%s">
                  <conditionExpression xsi:type="tFormalExpression"><![CDATA[%s]]></conditionExpression>
                </sequenceFlow>""".formatted(id, source, target, condition);
    }
}
