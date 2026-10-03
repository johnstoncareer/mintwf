package com.intwfs.mintwf.core.parser;

import com.intwfs.mintwf.core.model.AdHocSubProcess;
import com.intwfs.mintwf.core.model.CallActivity;
import com.intwfs.mintwf.core.model.EndEvent;
import com.intwfs.mintwf.core.model.ExclusiveGateway;
import com.intwfs.mintwf.core.model.FlowNode;
import com.intwfs.mintwf.core.model.ParallelGateway;
import com.intwfs.mintwf.core.model.ProcessDefinition;
import com.intwfs.mintwf.core.model.ReceiveTask;
import com.intwfs.mintwf.core.model.SequenceFlow;
import com.intwfs.mintwf.core.model.ServiceTask;
import com.intwfs.mintwf.core.model.StartEvent;
import com.intwfs.mintwf.core.model.SubProcess;
import com.intwfs.mintwf.core.model.UserTask;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

/**
 * Parses BPMN 2.0 XML into a {@link ProcessDefinition}.
 *
 * <p>The document is validated against the OMG schemas, then checked against the subset of BPMN that mintwf runs.
 * Anything outside that subset is rejected with the id of the offending element rather than ignored.
 *
 * <p>A document must contain exactly one {@code process} with {@code isExecutable="true"}. Other processes, such as
 * the pools of external participants in a collaboration, are ignored.
 */
public final class BpmnParser {

    /** Namespace of the BPMN 2.0 semantic model. */
    public static final String BPMN_NS = "http://www.omg.org/spec/BPMN/20100524/MODEL";

    /** Namespace of mintwf's extension attributes and elements. */
    public static final String MINTWF_NS = "https://intwfs.com/mintwf";

    /** Process children that carry no execution semantics. */
    private static final Set<String> IGNORED_IN_PROCESS =
            Set.of("documentation", "extensionElements", "laneSet", "textAnnotation", "association");

    /** Flow node children that carry no execution semantics. */
    private static final Set<String> IGNORED_IN_NODE = Set.of("documentation", "extensionElements", "incoming", "outgoing");

    private static final String SUPPORTED = "startEvent, endEvent, sequenceFlow, serviceTask, userTask, receiveTask, "
            + "exclusiveGateway, parallelGateway, subProcess, adHocSubProcess, callActivity";

    /**
     * @throws BpmnParseException if the document is not a valid, supported BPMN process
     */
    public ProcessDefinition parse(byte[] xml) {
        Element process = executableProcess(read(xml));
        String key = process.getAttribute("id");
        if (key.isEmpty()) {
            throw new BpmnParseException("the executable process must have an id");
        }

        List<FlowNode> nodes = new ArrayList<>();
        List<SequenceFlow> flows = new ArrayList<>();
        Map<String, String> containers = new LinkedHashMap<>();
        Map<String, String> documentation = new LinkedHashMap<>();
        parseContainer(process, null, nodes, flows, containers, documentation);

        ProcessDefinition definition;
        try {
            definition = new ProcessDefinition(key, attribute(process, "name"), nodes, flows, containers,
                    documentation);
        } catch (IllegalArgumentException e) {
            throw new BpmnParseException(e.getMessage(), e);
        }
        validateGraph(definition);
        return definition;
    }

    /** Collects the nodes and flows of a process or subprocess, descending into nested subprocesses. */
    private static void parseContainer(Element container, String containerId, List<FlowNode> nodes,
                                       List<SequenceFlow> flows, Map<String, String> containers,
                                       Map<String, String> documentation) {
        for (Element child : children(container)) {
            if (!BPMN_NS.equals(child.getNamespaceURI())) {
                continue;
            }
            String type = child.getLocalName();
            if (IGNORED_IN_PROCESS.contains(type)) {
                continue;
            }
            if (type.equals("sequenceFlow")) {
                flows.add(sequenceFlow(child));
                continue;
            }
            if (type.equals("completionCondition")) {
                throw new BpmnParseException(describe(container) + ": completionCondition is not supported; the "
                        + "agent decides when it is done");
            }
            FlowNode node = flowNode(child);
            nodes.add(node);
            if (containerId != null) {
                containers.put(node.id(), containerId);
            }
            String text = documentationText(child);
            if (text != null) {
                documentation.put(node.id(), text);
            }
            if (node instanceof SubProcess || node instanceof AdHocSubProcess) {
                parseContainer(child, node.id(), nodes, flows, containers, documentation);
            }
        }
    }

    /** Returns the text of an element's {@code documentation} children, or {@code null} when it has none. */
    private static String documentationText(Element element) {
        StringBuilder text = new StringBuilder();
        for (Element child : children(element)) {
            if (BPMN_NS.equals(child.getNamespaceURI()) && "documentation".equals(child.getLocalName())) {
                text.append(child.getTextContent().strip()).append('\n');
            }
        }
        String joined = text.toString().strip();
        return joined.isEmpty() ? null : joined;
    }

    private static Document read(byte[] xml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            factory.setSchema(SchemaHolder.SCHEMA);
            DocumentBuilder builder = factory.newDocumentBuilder();
            builder.setErrorHandler(new FailingErrorHandler());
            Document document = builder.parse(new ByteArrayInputStream(xml));
            Element root = document.getDocumentElement();
            if (!BPMN_NS.equals(root.getNamespaceURI()) || !"definitions".equals(root.getLocalName())) {
                throw new BpmnParseException("the root element must be <definitions> in namespace " + BPMN_NS);
            }
            return document;
        } catch (SAXParseException e) {
            throw new BpmnParseException(
                    "invalid BPMN at line " + e.getLineNumber() + ", column " + e.getColumnNumber() + ": "
                            + e.getMessage(), e);
        } catch (SAXException | IOException e) {
            throw new BpmnParseException("invalid BPMN: " + e.getMessage(), e);
        } catch (ParserConfigurationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Element executableProcess(Document document) {
        List<Element> executable = new ArrayList<>();
        for (Element child : children(document.getDocumentElement())) {
            if (BPMN_NS.equals(child.getNamespaceURI()) && "process".equals(child.getLocalName())
                    && isTrue(child, "isExecutable")) {
                executable.add(child);
            }
        }
        if (executable.size() != 1) {
            throw new BpmnParseException("the definitions must contain exactly one process with "
                    + "isExecutable=\"true\", found " + executable.size());
        }
        return executable.getFirst();
    }

    private static FlowNode flowNode(Element element) {
        String type = element.getLocalName();
        String id = element.getAttribute("id");
        if (id.isEmpty()) {
            throw new BpmnParseException("<" + type + "> must have an id");
        }
        String name = attribute(element, "name");
        String defaultFlow = attribute(element, "default");
        FlowNode node = switch (type) {
            case "startEvent" -> new StartEvent(id, name);
            case "endEvent" -> new EndEvent(id, name);
            case "serviceTask" -> serviceTask(element, id, name, defaultFlow);
            case "userTask" -> new UserTask(id, name, defaultFlow);
            case "receiveTask" -> {
                rejectAttribute(element, "messageRef", "message correlation is not supported yet");
                if (isTrue(element, "instantiate")) {
                    throw new BpmnParseException(
                            describe(element) + ": starting a process from a receiveTask is not supported");
                }
                yield new ReceiveTask(id, name, defaultFlow);
            }
            case "exclusiveGateway" -> new ExclusiveGateway(id, name, defaultFlow);
            case "parallelGateway" -> new ParallelGateway(id, name);
            case "subProcess" -> {
                if (isTrue(element, "triggeredByEvent")) {
                    throw new BpmnParseException(describe(element) + ": event subprocesses are not supported");
                }
                yield new SubProcess(id, name, defaultFlow);
            }
            case "adHocSubProcess" -> adHocSubProcess(element, id, name, defaultFlow);
            case "callActivity" -> {
                String calledElement = element.getAttribute("calledElement").strip();
                if (calledElement.isEmpty()) {
                    throw new BpmnParseException(describe(element) + " must name the process to call in calledElement");
                }
                yield new CallActivity(id, name, calledElement, defaultFlow);
            }
            default -> throw new BpmnParseException(
                    "<" + type + " id=\"" + id + "\"> is not supported; supported elements are " + SUPPORTED);
        };
        if (isTrue(element, "isForCompensation")) {
            throw new BpmnParseException(describe(element) + ": compensation is not supported yet");
        }
        if (node instanceof SubProcess || node instanceof AdHocSubProcess) {
            // Its children are the subprocess's own nodes and flows, parsed as a container.
            return node;
        }
        for (Element child : children(element)) {
            if (BPMN_NS.equals(child.getNamespaceURI()) && !IGNORED_IN_NODE.contains(child.getLocalName())) {
                throw new BpmnParseException(
                        describe(element) + ": child element <" + child.getLocalName() + "> is not supported");
            }
        }
        return node;
    }

    private static AdHocSubProcess adHocSubProcess(Element element, String id, String name, String defaultFlow) {
        String goal = documentationText(element);
        if (goal == null) {
            throw new BpmnParseException(describe(element) + " needs a documentation element that states the "
                    + "agent's goal");
        }
        String ordering = element.getAttribute("ordering").strip();
        if (!ordering.isEmpty() && !ordering.equals("Parallel") && !ordering.equals("Sequential")) {
            throw new BpmnParseException(describe(element) + ": ordering must be Parallel or Sequential");
        }
        Map<String, String> fields = fields(element);
        int maxActivations = AdHocSubProcess.DEFAULT_MAX_ACTIVATIONS;
        if (fields.containsKey("maxActivations")) {
            try {
                maxActivations = Integer.parseInt(fields.get("maxActivations").strip());
            } catch (NumberFormatException e) {
                maxActivations = 0;
            }
            if (maxActivations < 1) {
                throw new BpmnParseException(describe(element) + ": maxActivations must be a positive whole number");
            }
        }
        return new AdHocSubProcess(id, name, defaultFlow, goal, fields, ordering.equals("Sequential"),
                maxActivations);
    }

    private static ServiceTask serviceTask(Element element, String id, String name, String defaultFlow) {
        String handlerType = element.getAttributeNS(MINTWF_NS, "type");
        if (handlerType.isBlank()) {
            throw new BpmnParseException(describe(element) + " must declare a handler with the mintwf:type attribute");
        }
        Map<String, String> fields = fields(element);
        String async = element.getAttributeNS(MINTWF_NS, "async").strip();
        if (!async.isEmpty() && !async.equals("true") && !async.equals("false")) {
            throw new BpmnParseException(describe(element) + ": mintwf:async must be true or false");
        }
        return new ServiceTask(id, name, handlerType.strip(), fields, !async.equals("false"), defaultFlow);
    }

    /** Reads the {@code mintwf:field} extension elements of a node. */
    private static Map<String, String> fields(Element element) {
        Map<String, String> fields = new LinkedHashMap<>();
        for (Element extensions : children(element)) {
            if (!BPMN_NS.equals(extensions.getNamespaceURI()) || !"extensionElements".equals(extensions.getLocalName())) {
                continue;
            }
            for (Element field : children(extensions)) {
                if (!MINTWF_NS.equals(field.getNamespaceURI()) || !"field".equals(field.getLocalName())) {
                    continue;
                }
                String fieldName = field.getAttribute("name");
                if (fieldName.isBlank() || !field.hasAttribute("value")) {
                    throw new BpmnParseException(describe(element) + ": mintwf:field needs a name and a value");
                }
                if (fields.put(fieldName, field.getAttribute("value")) != null) {
                    throw new BpmnParseException(describe(element) + ": duplicate mintwf:field '" + fieldName + "'");
                }
            }
        }
        return fields;
    }

    private static SequenceFlow sequenceFlow(Element element) {
        String id = element.getAttribute("id");
        if (id.isEmpty()) {
            throw new BpmnParseException("<sequenceFlow> must have an id");
        }
        String condition = null;
        for (Element child : children(element)) {
            if (!BPMN_NS.equals(child.getNamespaceURI())) {
                continue;
            }
            switch (child.getLocalName()) {
                case "documentation", "extensionElements" -> {
                }
                case "conditionExpression" -> {
                    condition = child.getTextContent().strip();
                    if (condition.isEmpty()) {
                        throw new BpmnParseException(describe(element) + " has an empty conditionExpression");
                    }
                }
                default -> throw new BpmnParseException(
                        describe(element) + ": child element <" + child.getLocalName() + "> is not supported");
            }
        }
        return new SequenceFlow(id, attribute(element, "name"), element.getAttribute("sourceRef"),
                element.getAttribute("targetRef"), condition);
    }

    private static void validateGraph(ProcessDefinition definition) {
        List<String> containerIds = new ArrayList<>();
        containerIds.add(null);
        definition.nodes().stream().filter(SubProcess.class::isInstance).forEach(node -> containerIds.add(node.id()));
        for (FlowNode agent : definition.nodes()) {
            if (!(agent instanceof AdHocSubProcess)) {
                continue;
            }
            List<FlowNode> inside = definition.children(agent.id());
            for (FlowNode node : inside) {
                if (node instanceof StartEvent || node instanceof EndEvent) {
                    throw new BpmnParseException(describe(node) + ": an adHocSubProcess cannot contain start or end "
                            + "events; paths inside it end where they have no outgoing flow");
                }
            }
            if (inside.stream().noneMatch(node -> definition.incoming(node.id()).isEmpty())) {
                throw new BpmnParseException(describe(agent) + " needs at least one activity without an incoming "
                        + "flow for the agent to start");
            }
        }
        for (String containerId : containerIds) {
            long starts = definition.children(containerId).stream().filter(StartEvent.class::isInstance).count();
            if (starts != 1) {
                String container = containerId == null
                        ? "process '" + definition.key() + "'"
                        : "subProcess '" + containerId + "'";
                throw new BpmnParseException(container + " must have exactly one startEvent, found " + starts);
            }
        }
        for (FlowNode node : definition.nodes()) {
            List<SequenceFlow> outgoing = definition.outgoing(node.id());
            List<SequenceFlow> incoming = definition.incoming(node.id());
            String label = describe(node);
            boolean inAgent = definition.container(node.id()) != null
                    && definition.node(definition.container(node.id())) instanceof AdHocSubProcess;
            boolean gateway = node instanceof ExclusiveGateway || node instanceof ParallelGateway;
            if (node instanceof StartEvent && !incoming.isEmpty()) {
                throw new BpmnParseException(label + " must not have incoming sequence flows");
            }
            if (!(node instanceof StartEvent) && incoming.isEmpty() && !inAgent) {
                throw new BpmnParseException(label + " is unreachable: it has no incoming sequence flow");
            }
            if (node instanceof EndEvent && !outgoing.isEmpty()) {
                throw new BpmnParseException(label + " must not have outgoing sequence flows");
            }
            if (!(node instanceof EndEvent) && outgoing.isEmpty() && !(inAgent && !gateway)) {
                throw new BpmnParseException(label + " has no outgoing sequence flow; end the path with an endEvent");
            }
            String defaultFlow = node.defaultFlow();
            if (defaultFlow != null) {
                SequenceFlow flow = outgoing.stream().filter(f -> f.id().equals(defaultFlow)).findFirst()
                        .orElseThrow(() -> new BpmnParseException(
                                label + ": default flow '" + defaultFlow + "' is not one of its outgoing flows"));
                if (flow.condition() != null) {
                    throw new BpmnParseException(label + ": default flow '" + defaultFlow + "' must not have a condition");
                }
            }
            if (node instanceof ParallelGateway) {
                for (SequenceFlow flow : outgoing) {
                    if (flow.condition() != null) {
                        throw new BpmnParseException(label + ": outgoing flow '" + flow.id()
                                + "' must not have a condition");
                    }
                }
            }
            if (node instanceof ExclusiveGateway && outgoing.size() > 1) {
                for (SequenceFlow flow : outgoing) {
                    if (flow.condition() == null && !flow.id().equals(defaultFlow)) {
                        throw new BpmnParseException(label + ": outgoing flow '" + flow.id()
                                + "' needs a conditionExpression or must be the default flow");
                    }
                }
            }
        }
    }

    private static void rejectAttribute(Element element, String attribute, String reason) {
        if (element.hasAttribute(attribute)) {
            throw new BpmnParseException(describe(element) + ": " + reason);
        }
    }

    /** Reads an {@code xsd:boolean} attribute, which is true as {@code "true"} or {@code "1"}. */
    private static boolean isTrue(Element element, String name) {
        String value = element.getAttribute(name).strip();
        return value.equals("true") || value.equals("1");
    }

    private static String attribute(Element element, String name) {
        return element.hasAttribute(name) ? element.getAttribute(name) : null;
    }

    private static List<Element> children(Element parent) {
        List<Element> elements = new ArrayList<>();
        for (Node child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element element) {
                elements.add(element);
            }
        }
        return elements;
    }

    private static String describe(Element element) {
        return element.getLocalName() + " '" + element.getAttribute("id") + "'";
    }

    private static String describe(FlowNode node) {
        String type = node.getClass().getSimpleName();
        return Character.toLowerCase(type.charAt(0)) + type.substring(1) + " '" + node.id() + "'";
    }

    /** Loads the bundled OMG schemas once, on first use. */
    private static final class SchemaHolder {

        static final Schema SCHEMA = load();

        private static Schema load() {
            URL root = BpmnParser.class.getResource("xsd/BPMN20.xsd");
            if (root == null) {
                throw new IllegalStateException("BPMN schemas are missing from the classpath");
            }
            try {
                SchemaFactory factory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
                factory.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
                // The bundled schemas import each other by relative path, from a directory or a jar.
                factory.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "file,jar");
                return factory.newSchema(root);
            } catch (SAXException e) {
                throw new IllegalStateException("cannot load the BPMN schemas", e);
            }
        }
    }

    /** Turns validation errors into exceptions instead of printing them. */
    private static final class FailingErrorHandler implements ErrorHandler {

        @Override
        public void warning(SAXParseException exception) {
        }

        @Override
        public void error(SAXParseException exception) throws SAXException {
            throw exception;
        }

        @Override
        public void fatalError(SAXParseException exception) throws SAXException {
            throw exception;
        }
    }
}
