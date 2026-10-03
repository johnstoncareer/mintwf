package com.intwfs.mintwf.claude;

import com.intwfs.mintwf.core.spi.TaskHandler;
import com.intwfs.mintwf.core.spi.TaskHandlerProvider;

/**
 * Registers {@link SkillTaskHandler} for {@code mintwf:type="skill"}.
 */
public final class SkillTaskHandlerProvider implements TaskHandlerProvider {

    @Override
    public String type() {
        return SkillTaskHandler.TYPE;
    }

    @Override
    public TaskHandler handler() {
        return new SkillTaskHandler();
    }
}
