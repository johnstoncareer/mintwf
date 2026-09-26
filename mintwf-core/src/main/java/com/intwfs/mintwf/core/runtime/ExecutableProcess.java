package com.intwfs.mintwf.core.runtime;

import com.intwfs.mintwf.core.model.ProcessDefinition;
import com.intwfs.mintwf.core.model.SequenceFlow;
import com.intwfs.mintwf.core.parser.BpmnParseException;
import com.intwfs.mintwf.core.spi.CompiledExpression;
import com.intwfs.mintwf.core.spi.ExpressionEvaluator;
import com.intwfs.mintwf.core.spi.ExpressionException;
import java.util.HashMap;
import java.util.Map;

/**
 * A deployed process version with its sequence flow conditions compiled.
 */
public record ExecutableProcess(ProcessDefinition definition, int version, Map<String, CompiledExpression> conditions) {

    public ExecutableProcess {
        conditions = Map.copyOf(conditions);
    }

    /**
     * @throws BpmnParseException if a condition does not compile
     */
    public static ExecutableProcess compile(ProcessDefinition definition, int version, ExpressionEvaluator evaluator) {
        Map<String, CompiledExpression> conditions = new HashMap<>();
        for (SequenceFlow flow : definition.flows()) {
            if (flow.condition() != null) {
                try {
                    conditions.put(flow.id(), evaluator.compile(flow.condition()));
                } catch (ExpressionException e) {
                    throw new BpmnParseException(
                            "sequenceFlow '" + flow.id() + "' has an invalid condition: " + e.getMessage(), e);
                }
            }
        }
        return new ExecutableProcess(definition, version, conditions);
    }

    public String key() {
        return definition.key();
    }
}
