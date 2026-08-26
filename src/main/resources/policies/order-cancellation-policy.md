---
policy_id: order-cancellation-policy
title: Order Cancellation Policy
version: 2026-03-01
owner: Customer Operations
---

## Cancellation window

An order can be cancelled at any point up to the moment a shipping label is created.
In practice this means orders in status `CREATED`, `PAID`, or `PROCESSING` are
cancellable, and orders in `SHIPPED`, `IN_TRANSIT`, `DELIVERED`, `CANCELLED`, or
`REFUNDED` are not.

Because picking usually begins within a few hours, a same-day cancellation request
should be actioned immediately rather than queued.

## What happens on cancellation

Cancelling sets the order to `CANCELLED` and, when payment was already captured,
opens a full refund including outbound shipping. No restocking fee or return label
fee applies. Stock is released back to inventory in the same transaction, so a
cancellation never leaves a reservation stranded.

## After the order has shipped

Once a parcel is in the carrier network the order cannot be cancelled. The two
options are to refuse delivery at the door, which sends the parcel back to the
warehouse and triggers a refund on receipt, or to accept delivery and open a normal
return within the 30-day window.

## Partial cancellation

Individual lines cannot be cancelled separately. Cancel the whole order and reorder
the lines that are still wanted; this keeps the payment reversal matched to a single
authorisation.

## Pre-orders and backorders

Pre-orders may be cancelled at any time before the release date without charge,
because nothing is captured until the item is allocated to a parcel. A backordered
line that has not been allocated for more than 30 days is cancelled and refunded
automatically.
