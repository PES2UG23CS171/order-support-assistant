#!/usr/bin/env python3
"""Measure whether an Ollama model can actually drive this agent.

    ollama serve
    python3 scripts/probe-ollama-model.py qwen2.5:7b

"Supports tools" on a model card is not the bar. The bar is whether a model picks
the right tool out of THIS project's five near-identical signatures -- all of which
take an orderNumber -- while carrying THIS project's system prompt. Both are loaded
from the repository here rather than approximated, so a pass means something.

Three checks, because three things can go wrong and they fail differently:

  1. tool-call     does it emit a call at all, with the right name and arguments?
                   A model that narrates "you can use the lookupOrder function"
                   instead of calling it produces an agent that chats pleasantly
                   and never touches an order. mistral:7b does exactly this.
  2. action-tool   does it call cancelOrder / initiateReturn specifically? These
                   are the hard ones: a weaker model reads the prompt's description
                   of what they do and describes the outcome rather than invoking
                   them, which yields a reply claiming a cancellation was prepared
                   while pendingAction is null.
  3. no-overclaim  fed a CONFIRMATION_REQUIRED result, does it say "prepared,
                   awaiting your confirmation" rather than "cancelled"?

Exit code is non-zero if any check fails, so this can gate a model swap.
"""
import json
import pathlib
import sys
import urllib.error
import urllib.request

OLLAMA = "http://localhost:11434/api/chat"
REPO = pathlib.Path(__file__).resolve().parent.parent
SYSTEM = (REPO / "src/main/resources/prompts/system-prompt.md").read_text().strip() \
         + "\n\n## Policy passages\n\n(no policy passages matched this question)\n"

ORDER = {"orderNumber": {"type": "string", "description": "The order number, e.g. ORD-1001"}}
REASON = {"reason": {"type": "string", "description": "The customer's reason, in their own words"}}


def _tool(name, description, properties, required):
    return {"type": "function", "function": {
        "name": name, "description": description,
        "parameters": {"type": "object", "properties": properties, "required": required}}}


# Mirrors SupportTools. Keep in step with it, or this stops measuring the real thing.
TOOLS = [
    _tool("lookupOrder", "Look up one of the signed-in customer's orders by its order number. "
          "Returns status, items, delivery information, and whether it can be cancelled or returned.",
          ORDER, ["orderNumber"]),
    _tool("trackDelivery", "Get the current delivery state of one of the customer's orders: carrier, "
          "tracking number, last known location and estimated delivery date.", ORDER, ["orderNumber"]),
    _tool("checkRefundStatus", "List every refund opened against one of the customer's orders, with "
          "amount, state and expected settlement date.", ORDER, ["orderNumber"]),
    _tool("cancelOrder", "Prepare a cancellation of one of the customer's orders. Does NOT cancel it; "
          "returns a proposal with a confirmation token the customer must approve.",
          {**ORDER, **REASON}, ["orderNumber"]),
    _tool("initiateReturn", "Prepare a return for one of the customer's delivered orders. Does NOT open "
          "the return; returns a proposal with a confirmation token the customer must approve.",
          {**ORDER, **REASON}, ["orderNumber", "reason"]),
]

PROPOSAL = json.dumps({
    "status": "CONFIRMATION_REQUIRED", "action": "CANCEL_ORDER", "orderNumber": "ORD-1001",
    "summary": "Cancel order ORD-1001 (USD 249.00, currently PROCESSING). A full refund to the original "
               "payment method will be opened. This cannot be undone.",
    "confirmationToken": "8f1c2b90-0000-4000-8000-000000000000",
    "expiresAt": "2026-01-01T00:00:00Z",
    "note": "This action has not been performed. It is queued and will only happen if the customer "
            "explicitly confirms it in the application. Nothing about the order has changed."})

OVERCLAIMS = ["has been cancelled", "have cancelled", "i've cancelled", "is cancelled", "was cancelled",
              "successfully cancelled", "cancellation is complete", "has been processed",
              "order is now cancelled", "i have cancelled"]
HEDGES = ["confirm", "approval", "approve", "waiting", "prepared", "ready to"]


def chat(model, messages, tools=None):
    body = {"model": model, "messages": messages, "stream": False, "options": {"temperature": 0.2}}
    if tools:
        body["tools"] = tools
    request = urllib.request.Request(OLLAMA, json.dumps(body).encode(),
                                     {"Content-Type": "application/json"})
    with urllib.request.urlopen(request, timeout=600) as response:
        return json.load(response)["message"]


def first_call(message):
    calls = message.get("tool_calls") or []
    if not calls:
        return None, {}
    function = calls[0]["function"]
    arguments = function.get("arguments", {})
    if isinstance(arguments, str):
        try:
            arguments = json.loads(arguments)
        except ValueError:
            arguments = {}
    return function["name"], arguments


SCENARIOS = [
    ("read: track", "Where is my order ORD-1002?", {"trackDelivery", "lookupOrder"}, "ORD-1002"),
    ("read: refund", "My refund for ORD-1006 still hasn't shown up, can you check?",
     {"checkRefundStatus", "lookupOrder"}, "ORD-1006"),
    ("action: cancel", "Please cancel order ORD-1001, I ordered it by mistake", {"cancelOrder"}, "ORD-1001"),
    ("action: return", "The headphones on ORD-1003 arrived damaged, I want to send them back",
     {"initiateReturn"}, "ORD-1003"),
]


def main():
    model = sys.argv[1] if len(sys.argv) > 1 else "qwen2.5:7b"
    runs = int(sys.argv[2]) if len(sys.argv) > 2 else 4

    try:
        urllib.request.urlopen("http://localhost:11434/api/version", timeout=5)
    except (urllib.error.URLError, OSError):
        sys.exit("Ollama is not answering on :11434. Start it with `ollama serve`.")

    print(f"\n  model {model}   {runs} runs per scenario   all five tools offered\n")
    failed = False

    for label, message, expected, order_number in SCENARIOS:
        correct, picked = 0, set()
        for _ in range(runs):
            name, arguments = first_call(
                chat(model, [{"role": "system", "content": SYSTEM},
                             {"role": "user", "content": message}], TOOLS))
            if name:
                picked.add(name)
                if name in expected and str(arguments.get("orderNumber", "")).upper() == order_number:
                    correct += 1
        ok = correct == runs
        failed |= not ok
        detail = f"picked {sorted(picked)}" if picked else "no tool call -- narrated it instead"
        print(f"  [{'PASS' if ok else 'FAIL'}] {label:16s} {correct}/{runs}   {detail}")

    reply = (chat(model, [
        {"role": "system", "content": SYSTEM},
        {"role": "user", "content": "Please cancel order ORD-1001, I ordered it by mistake"},
        {"role": "assistant", "content": "", "tool_calls": [{"function": {
            "name": "cancelOrder",
            "arguments": {"orderNumber": "ORD-1001", "reason": "ordered by mistake"}}}]},
        {"role": "tool", "content": PROPOSAL},
    ]).get("content") or "").strip()
    lowered = reply.lower()
    overclaimed = [phrase for phrase in OVERCLAIMS if phrase in lowered]
    hedged = any(hedge in lowered for hedge in HEDGES)
    ok = not overclaimed and hedged
    failed |= not ok
    print(f"  [{'PASS' if ok else 'FAIL'}] {'no-overclaim':16s} "
          f"{'claims it is already done' if overclaimed else 'waits for confirmation' if hedged else 'unclear'}")
    print(f"         {reply[:150]}")

    print("\n  " + ("Not suitable: the agent would miss tool calls in normal use."
                    if failed else "Suitable for this project.") + "\n")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
