package com.acme.ordersupport.agent.model;

/** Everything a tool can hand back. Sealed so a new payload has to be a deliberate act. */
public sealed interface ToolResult
        permits OrderSnapshot, DeliverySnapshot, RefundSnapshot, ActionProposal, ToolRefusal {
}
