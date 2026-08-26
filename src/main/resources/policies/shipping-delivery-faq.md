---
policy_id: shipping-delivery-faq
title: Shipping and Delivery FAQ
version: 2026-03-01
owner: Logistics
---

## Delivery estimates

Standard shipping is 3 to 5 business days after dispatch, express is 1 to 2. The
estimated delivery date shown on an order is the carrier's projection and moves as
the parcel is scanned through the network. Orders placed after 14:00 local warehouse
time are picked the following business day.

## Tracking a parcel

Every shipment carries a carrier and a tracking number, and the shipment status
follows `LABEL_CREATED`, `IN_TRANSIT`, `OUT_FOR_DELIVERY`, `DELIVERED`. A tracking
number that shows no movement for 24 hours after label creation usually means the
parcel is still waiting for its first carrier scan, which is normal over weekends.

## Delayed shipments

A parcel is considered delayed when it is more than **3 business days** past its
estimated delivery date with no new carrier scan. At that point we open a carrier
trace, which takes up to 5 business days to resolve. Customers do not need to wait
for the trace to finish before we act: we will reship or refund at the customer's
choice once the trace is opened.

## Lost or stolen parcels

If tracking shows `DELIVERED` but the customer cannot find the parcel, ask them to
check with neighbours and around the property first, because carriers frequently
mark a parcel delivered several hours before the final drop. If it has not appeared
after **48 hours**, we treat it as lost and reship or refund. Claims for a parcel
marked delivered must be raised within 21 days of the delivery scan.

## Changing a delivery address

An address can be changed only while the order is still `PROCESSING`. Once a label
is created the address is locked into the carrier's system and we cannot alter it.
The workaround is to cancel the order, if it has not shipped, and reorder with the
correct address.

## International shipping

We ship to 31 countries. Duties and import taxes are collected by the carrier on
delivery and are not included in the order total. Refused international parcels are
refunded minus outbound and return freight.
