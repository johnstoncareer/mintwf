/**
 * Claude-backed extensions: the {@code skill} task handler, discovered through
 * {@link com.intwfs.mintwf.core.spi.TaskHandlerProvider}, and the agent planner for {@code adHocSubProcess}, discovered
 * as a {@link com.intwfs.mintwf.core.spi.AgentPlanner}. Requests use the Anthropic Java SDK, configured from the
 * environment ({@code ANTHROPIC_API_KEY}).
 */
package com.intwfs.mintwf.claude;
