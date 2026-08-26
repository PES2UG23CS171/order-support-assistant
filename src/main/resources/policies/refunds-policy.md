---
policy_id: refunds-policy
title: Refunds Policy
version: 2026-03-01
owner: Finance Operations
---

## When a refund is issued

A refund is created automatically in three situations: an order is cancelled before
it ships, a returned item passes inspection at the warehouse, or an agent approves a
goodwill adjustment. Refunds for returns are only released **after** the item has
been received and inspected, which typically adds 2 to 3 business days after the
carrier delivers the parcel back to us.

## Processing time

Once a refund is released it settles to the original payment method in **5 to 10
business days**. The exact timing is controlled by the issuing bank, not by us. The
refund record moves through the states `PENDING`, `PROCESSING`, and `SETTLED`; a
customer asking "where is my money" is almost always looking at a refund in
`PROCESSING`, meaning we have released the funds and the bank has not yet posted
them.

Refunds to a debit card generally post more slowly than refunds to a credit card.

## Refund method

Refunds always return to the original payment method. We cannot redirect a refund to
a different card, a different account, or store credit at the customer's request,
because the reversal is matched against the original authorisation. If the original
card has expired or been closed, the issuing bank still routes the credit to the
replacement account; if the account is fully closed the bank returns the funds to us
and we contact the customer for bank details.

## Partial refunds and restocking fees

A refund may be reduced when an item arrives back incomplete or with avoidable
damage. Deductions are itemised on the refund record. Standard deductions are the
**$6.95** change-of-mind return label fee and, for opened FURNITURE items, a
restocking fee of up to **15%** of the item price.

Original outbound shipping charges are refunded only when the entire order is
returned or cancelled, or when the return is our fault.

## Cancelled orders

When an order is cancelled before shipment, any authorisation captured is voided or
refunded in full, including outbound shipping. No restocking fee and no label fee
apply to a cancellation, because nothing left the warehouse.
