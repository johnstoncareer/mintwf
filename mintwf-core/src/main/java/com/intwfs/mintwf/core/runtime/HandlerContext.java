package com.intwfs.mintwf.core.runtime;

import com.intwfs.mintwf.core.model.ServiceTask;
import com.intwfs.mintwf.core.spi.InstanceState;
import com.intwfs.mintwf.core.spi.TaskContext;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The {@link TaskContext} a handler runs with. Writes go to {@code variables} and are also recorded in
 * {@link #changes()}, so a job can apply them to a newer copy of the instance.
 */
final class HandlerContext implements TaskContext {

    private final InstanceState instance;
    private final ServiceTask task;
    private final Map<String, Object> variables;
    private final Map<String, Object> changes = new LinkedHashMap<>();

    HandlerContext(InstanceState instance, ServiceTask task, Map<String, Object> variables) {
        this.instance = instance;
        this.task = task;
        this.variables = variables;
    }

    Map<String, Object> changes() {
        return Collections.unmodifiableMap(changes);
    }

    @Override
    public String processKey() {
        return instance.processKey();
    }

    @Override
    public String instanceId() {
        return instance.id();
    }

    @Override
    public String businessKey() {
        return instance.businessKey();
    }

    @Override
    public String activityId() {
        return task.id();
    }

    @Override
    public String activityName() {
        return task.name();
    }

    @Override
    public Map<String, String> fields() {
        return task.fields();
    }

    @Override
    public Map<String, Object> variables() {
        return Collections.unmodifiableMap(variables);
    }

    @Override
    public Object variable(String name) {
        return variables.get(name);
    }

    @Override
    public void setVariable(String name, Object value) {
        Variables.requireName(name);
        Object copy = Variables.copyValue(name, value);
        variables.put(name, copy);
        changes.put(name, copy);
    }
}
