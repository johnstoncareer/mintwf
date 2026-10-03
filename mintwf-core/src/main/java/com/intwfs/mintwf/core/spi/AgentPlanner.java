package com.intwfs.mintwf.core.spi;

/**
 * Decides what an agent, an {@code adHocSubProcess}, does next. The engine asks once per turn: when a token enters the
 * agent, and again each time every activity it started has finished.
 *
 * <p>A planner is called outside any transaction, like a {@link TaskHandler}, and may be called again for the same
 * turn if a worker dies before the decision is saved. If it throws, the turn is retried and then becomes an incident.
 *
 * <p>The engine uses the planner set on its builder, or else the one {@code ServiceLoader} finds. Implementations must
 * be thread-safe.
 */
public interface AgentPlanner {

    AgentDecision decide(AgentContext context) throws Exception;
}
